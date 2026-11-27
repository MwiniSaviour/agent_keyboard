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
import android.view.MotionEvent
import android.view.View
import com.agentkeyboard.core.protocol.InputKind

/**
 * Native keyboard shown while the WebView boots, after a renderer crash, on a protocol mismatch, or on
 * devices whose WebView lacks the listener API (ADR-0002 fallback, SPEC §9). Deliberately minimal and
 * allocation-free in `onDraw`: letters with shift, a numbers/symbols layer (opened automatically for
 * number, phone and OTP fields), backspace with repeat, space, comma/period, enter and keyboard switch.
 *
 * It follows the monochrome tokens of SPEC §7.4 / FR-UI-08 and the user's theme setting (not only the
 * system night mode), so the swap to and from the shell is visually quiet. Touch handling supports
 * rollover typing: a second finger landing commits the key the first finger is still holding.
 */
@SuppressLint("ViewConstructor")
class FallbackKeyboardView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onFallbackText(text: String)
        fun onFallbackBackspace()
        fun onFallbackEnter()
        fun onFallbackSwitchKeyboard()
        /** Haptics/sounds go through the same user settings as the shell's `feedback.key`. */
        fun onFallbackFeedback(kind: String)
    }

    private sealed interface Key {
        val label: String
        data class Char(override val label: String) : Key
        data object Shift : Key { override val label = "⇧" }
        data object Back : Key { override val label = "⌫" }
        data object Space : Key { override val label = "space" }
        data object Enter : Key { override val label = "↵" }
        data object Switch : Key { override val label = "🌐" }
        data object Layer : Key { override val label = "?123" }
    }

    private val bottomRow = listOf(Key.Switch to 1.2f, Key.Layer to 1.3f, Key.Char(",") to 1f, Key.Space to 4f, Key.Char(".") to 1f, Key.Enter to 1.5f)
    private val letters: List<List<Pair<Key, Float>>> = listOf(
        "qwertyuiop".map { Key.Char(it.toString()) to 1f },
        "asdfghjkl".map { Key.Char(it.toString()) to 1f },
        listOf(Key.Shift to 1.5f) + "zxcvbnm".map { Key.Char(it.toString()) to 1f } + listOf(Key.Back to 1.5f),
        bottomRow,
    )
    private val symbols: List<List<Pair<Key, Float>>> = listOf(
        "1234567890".map { Key.Char(it.toString()) to 1f },
        "@#\$_&-+()/".map { Key.Char(it.toString()) to 1f },
        listOf(Key.Char("%") to 1.5f) + "*\"':;!?".map { Key.Char(it.toString()) to 1f } + listOf(Key.Back to 1.5f),
        bottomRow,
    )
    private var rows = letters
    private val letterRects = letters.map { r -> r.map { RectF() } }
    private val symbolRects = symbols.map { r -> r.map { RectF() } }
    private val rects get() = if (rows === letters) letterRects else symbolRects

    private var shift = false
    private var pressed: Pair<Int, Int>? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    private val density = resources.displayMetrics.density
    private val gap = 6 * density
    private val radius = 9 * density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private var themeSetting = "system"
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
        isHapticFeedbackEnabled = false // feedback is routed through the user's settings instead
        updateTheme()
    }

    /** `light`, `dark` or `system`: the shell's theme setting (FR-KB-09). */
    fun setTheme(theme: String) {
        themeSetting = theme
        updateTheme()
    }

    /** Re-evaluates the theme; call on configuration (night mode) changes. */
    fun updateTheme() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = when (themeSetting) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
        setBackgroundColor(if (dark) 0xFF000000.toInt() else 0xFFF2F2F0.toInt())
        invalidate()
    }

    /** Number-like fields open on the numbers layer; text fields on letters. */
    fun setNumeric(kind: InputKind) {
        val numeric = kind == InputKind.NUMBER || kind == InputKind.PHONE || kind == InputKind.OTP
        val target = if (numeric) symbols else letters
        if (target !== rows) {
            rows = target
            resetTouch()
            invalidate()
        }
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
        layoutRows(letters, letterRects, w, top, keyH)
        layoutRows(symbols, symbolRects, w, top, keyH)
        textPaint.textSize = 20 * density
        smallTextPaint.textSize = 14 * density
    }

    private fun layoutRows(layout: List<List<Pair<Key, Float>>>, out: List<List<RectF>>, w: Int, top: Float, keyH: Float) {
        layout.forEachIndexed { ri, row ->
            val units = row.sumOf { it.second.toDouble() }.toFloat()
            val unit = (w - gap * (row.size + 1)) / units
            var x = gap
            val y = top + gap + ri * (keyH + gap)
            row.forEachIndexed { ki, (_, weight) ->
                val kw = unit * weight
                out[ri][ki].set(x, y, x + kw, y + keyH)
                x += kw + gap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val rs = rects
        rows.forEachIndexed { ri, row ->
            row.forEachIndexed { ki, (key, _) ->
                val modifier = key !is Key.Char && key !is Key.Space
                val p = pressed
                val isPressed = p != null && p.first == ri && p.second == ki
                keyPaint.color = when {
                    modifier && !dark -> if (isPressed) 0xFF333333.toInt() else 0xFF0A0A0A.toInt()
                    modifier && dark -> if (isPressed) 0xFFDDDDDD.toInt() else 0xFFFFFFFF.toInt()
                    dark -> if (isPressed) 0xFF444444.toInt() else 0xFF2A2A2A.toInt()
                    else -> if (isPressed) 0xFFE4E4E2.toInt() else 0xFFFFFFFF.toInt()
                }
                val r = rs[ri][ki]
                canvas.drawRoundRect(r, radius, radius, keyPaint)
                val label = when {
                    key is Key.Char && shift -> key.label.uppercase()
                    key is Key.Shift && shift -> "⬆"
                    key is Key.Layer && rows === symbols -> "ABC"
                    else -> key.label
                }
                val paint = if (key is Key.Layer || key is Key.Space) smallTextPaint else textPaint
                paint.color = if (modifier xor dark) 0xFFFFFFFF.toInt() else 0xFF0A0A0A.toInt()
                canvas.drawText(label, r.centerX(), r.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val hitKey = hit(event.getX(i), event.getY(i)) ?: return true
                // Rollover: a new finger commits whatever the previous finger is still holding.
                pressed?.let { p -> repeatHandler.removeCallbacks(repeatBackspace); fireOnRelease(keyAt(p)) }
                pressed = hitKey
                pointerId = event.getPointerId(i)
                val key = keyAt(hitKey)
                listener.onFallbackFeedback(feedbackKind(key))
                if (key is Key.Back) {
                    listener.onFallbackBackspace()
                    repeatHandler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(pointerId)
                val p = pressed
                if (i >= 0 && p != null && keyAt(p) !is Key.Back) {
                    // Sliding to a neighbour corrects the key before release, like the shell's grid.
                    val now = hit(event.getX(i), event.getY(i))
                    if (now != null && now != p) { pressed = now; invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    val p = pressed
                    resetTouch()
                    invalidate()
                    if (p != null) fireOnRelease(keyAt(p))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                resetTouch()
                invalidate()
            }
        }
        return true
    }

    private fun resetTouch() {
        repeatHandler.removeCallbacks(repeatBackspace)
        pressed = null
        pointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun feedbackKind(key: Key) = when (key) {
        Key.Back -> "delete"
        Key.Space -> "space"
        Key.Enter -> "return"
        is Key.Char -> "tap"
        else -> "mod"
    }

    private fun fireOnRelease(key: Key) {
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
            Key.Layer -> { rows = if (rows === letters) symbols else letters; shift = false; invalidate() }
        }
    }

    private fun keyAt(p: Pair<Int, Int>) = rows[p.first][p.second].first

    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        // Nearest key in the row band → no dead zones in the gaps.
        val rs = rects
        val ri = rs.indexOfFirst { row -> row.isNotEmpty() && y >= row[0].top - gap / 2 && y <= row[0].bottom + gap / 2 }
        if (ri < 0) return null
        val row = rs[ri]
        val ki = row.indices.minByOrNull { i -> val r = row[i]; if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f } ?: return null
        return ri to ki
    }

    override fun onDetachedFromWindow() {
        resetTouch()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val KEY_HEIGHT_DP = 46
        const val MAX_KEY_HEIGHT_DP = 58
        const val REPEAT_DELAY_MS = 350L // FR-KB-05, same as the shell
        const val REPEAT_INTERVAL_MS = 60L
    }
}
