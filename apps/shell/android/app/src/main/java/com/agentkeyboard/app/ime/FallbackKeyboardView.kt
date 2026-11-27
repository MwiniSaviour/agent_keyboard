package com.agentkeyboard.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Native QWERTY shown while the WebView boots, after a renderer crash, on a protocol mismatch, or on
 * devices whose WebView lacks the listener API (ADR-0002 fallback, SPEC §9). Deliberately minimal and
 * allocation-free in `onDraw`: letters, shift, backspace (with repeat), space, period/comma, enter and
 * keyboard switch. It follows the monochrome tokens of SPEC §7.4 so the swap is visually quiet.
 */
@SuppressLint("ViewConstructor")
class FallbackKeyboardView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onFallbackText(text: String)
        fun onFallbackBackspace()
        fun onFallbackEnter()
        fun onFallbackSwitchKeyboard()
    }

    private sealed interface Key {
        val label: String
        data class Char(override val label: String) : Key
        data object Shift : Key { override val label = "⇧" }
        data object Back : Key { override val label = "⌫" }
        data object Space : Key { override val label = "space" }
        data object Enter : Key { override val label = "↵" }
        data object Switch : Key { override val label = "🌐" }
    }

    private val rows: List<List<Pair<Key, Float>>> = listOf(
        "qwertyuiop".map { Key.Char(it.toString()) to 1f },
        "asdfghjkl".map { Key.Char(it.toString()) to 1f },
        listOf(Key.Shift to 1.5f) + "zxcvbnm".map { Key.Char(it.toString()) to 1f } + listOf(Key.Back to 1.5f),
        listOf(Key.Switch to 1.5f, Key.Char(",") to 1f, Key.Space to 5f, Key.Char(".") to 1f, Key.Enter to 1.5f),
    )
    private val rects = rows.map { r -> r.map { RectF() } }
    private var shift = false
    private var pressed: Pair<Int, Int>? = null

    private val density = resources.displayMetrics.density
    private val gap = 6 * density
    private val radius = 9 * density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private var dark = false

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatBackspace = object : Runnable {
        override fun run() {
            listener.onFallbackBackspace()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        contentDescription = "Keyboard"
        isHapticFeedbackEnabled = true
        updateTheme()
    }

    fun updateTheme() {
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setBackgroundColor(if (dark) 0xFF000000.toInt() else 0xFFF2F2F0.toInt())
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val natural = ((4 * KEY_HEIGHT_DP + 5 * 6) * density).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> size // match the shell's height so the swap is seamless
            MeasureSpec.AT_MOST -> minOf(natural, size)
            else -> natural
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Keys fill the bottom of the area; extra height (shell bar/strip space) stays above as board.
        val keyH = minOf((h - 5 * gap) / 4, MAX_KEY_HEIGHT_DP * density)
        val top = h - (4 * keyH + 5 * gap)
        rows.forEachIndexed { ri, row ->
            val units = row.sumOf { it.second.toDouble() }.toFloat()
            val unit = (w - gap * (row.size + 1)) / units
            var x = gap
            val y = top + gap + ri * (keyH + gap)
            row.forEachIndexed { ki, (_, weight) ->
                val kw = unit * weight
                rects[ri][ki].set(x, y, x + kw, y + keyH)
                x += kw + gap
            }
        }
        textPaint.textSize = 20 * density
    }

    override fun onDraw(canvas: Canvas) {
        rows.forEachIndexed { ri, row ->
            row.forEachIndexed { ki, (key, _) ->
                val modifier = key !is Key.Char && key !is Key.Space
                val isPressed = pressed == ri to ki
                keyPaint.color = when {
                    modifier && !dark -> if (isPressed) 0xFF333333.toInt() else 0xFF0A0A0A.toInt()
                    modifier && dark -> if (isPressed) 0xFFDDDDDD.toInt() else 0xFFFFFFFF.toInt()
                    dark -> if (isPressed) 0xFF444444.toInt() else 0xFF2A2A2A.toInt()
                    else -> if (isPressed) 0xFFE4E4E2.toInt() else 0xFFFFFFFF.toInt()
                }
                val r = rects[ri][ki]
                canvas.drawRoundRect(r, radius, radius, keyPaint)
                textPaint.color = if (modifier xor dark) 0xFFFFFFFF.toInt() else 0xFF0A0A0A.toInt()
                val label = when {
                    key is Key.Char && shift -> key.label.uppercase()
                    key is Key.Shift && shift -> "⬆"
                    else -> key.label
                }
                canvas.drawText(label, r.centerX(), r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(event.x, event.y) ?: return true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (keyAt(pressed!!) is Key.Back) {
                    listener.onFallbackBackspace()
                    repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                repeatHandler.removeCallbacks(repeatBackspace)
                val p = pressed
                pressed = null
                invalidate()
                if (p != null && hit(event.x, event.y) == p) fire(keyAt(p))
            }
            MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatBackspace)
                pressed = null
                invalidate()
            }
        }
        return true
    }

    private fun fire(key: Key) {
        when (key) {
            is Key.Char -> {
                listener.onFallbackText(if (shift) key.label.uppercase() else key.label)
                if (shift) { shift = false; invalidate() }
            }
            Key.Shift -> { shift = !shift; invalidate() }
            Key.Back -> Unit // fired on touch-down (with repeat)
            Key.Space -> listener.onFallbackText(" ")
            Key.Enter -> listener.onFallbackEnter()
            Key.Switch -> listener.onFallbackSwitchKeyboard()
        }
    }

    private fun keyAt(p: Pair<Int, Int>) = rows[p.first][p.second].first

    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        // Nearest key in the row band → no dead zones in the gaps.
        val ri = rects.indexOfFirst { row -> row.isNotEmpty() && y >= row[0].top - gap / 2 && y <= row[0].bottom + gap / 2 }
        if (ri < 0) return null
        val row = rects[ri]
        val ki = row.indices.minByOrNull { i -> val r = row[i]; if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f } ?: return null
        return ri to ki
    }

    override fun onDetachedFromWindow() {
        repeatHandler.removeCallbacks(repeatBackspace)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val KEY_HEIGHT_DP = 46
        const val MAX_KEY_HEIGHT_DP = 58
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
