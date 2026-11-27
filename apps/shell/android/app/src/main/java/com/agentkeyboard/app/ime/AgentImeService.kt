package com.agentkeyboard.app.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.editor.EditOp
import com.agentkeyboard.core.editor.EditorInfoSnapshot
import com.agentkeyboard.core.editor.FieldClassification
import com.agentkeyboard.core.editor.FieldClassifier
import com.agentkeyboard.core.editor.TextWindow
import com.agentkeyboard.core.protocol.EditorState
import com.agentkeyboard.core.protocol.ImeActionKind

/**
 * Android IME host (SPEC §9, ADR-0002).
 *
 *  - One WebView, created in [onCreate] and kept across show/hide; the native [FallbackKeyboardView]
 *    is shown until the shell says `hello`, after a renderer crash, or on protocol mismatch.
 *  - Never fullscreen/extract mode; the touchable region is the keyboard only ([onComputeInsets]).
 *  - `editor.state` is throttled to one per frame and read with as few IPCs as the API level allows.
 *  - All protocol and autonomy logic lives in [ImeController] and the pure `:core` module.
 */
class AgentImeService : InputMethodService(), ImeController.ImeHost, ImeWebView.Listener, FallbackKeyboardView.Listener {

    private lateinit var controller: ImeController
    private lateinit var web: ImeWebView
    private lateinit var executor: InputExecutor
    private var container: FrameLayout? = null
    private var fallback: FallbackKeyboardView? = null

    private var classification: FieldClassification? = null
    private var selStart = 0
    private var selEnd = 0
    private var shellReady = false
    private var shellIncompatible = false
    private var heightDp = DEFAULT_HEIGHT_DP
    private var stateScheduled = false
    private var rendererRestarts = 0
    private var lastRendererRestartAt = 0L

    private val stateFrame = Choreographer.FrameCallback {
        stateScheduled = false
        controller.onEditorState(editorState())
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    override fun onCreate() {
        super.onCreate()
        executor = InputExecutor(
            connection = { currentInputConnection },
            field = { classification },
            hasSelection = { selStart != selEnd },
            onApplied = { scheduleEditorState() },
        )
        controller = ImeController(this, this)
        web = ImeWebView(this, this)
        web.create() // pre-warm: the page boots while the user is still in the host app
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
    }

    override fun onCreateInputView(): View {
        val root = container ?: FrameLayout(this).also { container = it }
        (root.parent as? ViewGroup)?.removeView(root)
        root.removeAllViews()
        val fb = FallbackKeyboardView(this, this).also { fallback = it }
        // Both surfaces share one height so swapping between them never moves the app's layout.
        root.addView(fb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(heightDp)))
        web.view?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(heightDp)))
        }
        applyVisibility()
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        selStart = attribute.initialSelStart
        selEnd = attribute.initialSelEnd
        classification = FieldClassifier.classify(snapshot(attribute))
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        web.onShown()
        classification?.let(controller::onFieldStarted)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        web.onHidden()
        controller.onFieldFinished()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        executor.discard()
        classification = null
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        scheduleEditorState()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // The input view contains only the keyboard (no candidates/extract views), so the framework's
        // content inset is the keyboard top. Restrict touches to that region so the app above keeps
        // receiving them (SPEC §9).
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fallback?.updateTheme()
        controller.onThemeChanged((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    }

    override fun onDestroy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        Choreographer.getInstance().removeFrameCallback(stateFrame)
        executor.discard()
        controller.dispose()
        web.destroy()
        super.onDestroy()
    }

    // ───────────── ImeController.ImeHost ─────────────

    override fun classification(): FieldClassification? = classification

    override fun executor(): InputExecutor = executor

    override fun editorState(): EditorState {
        val ic = currentInputConnection ?: return EditorState.EMPTY
        if (classification?.context?.sensitive == true) return EditorState.EMPTY // never read secrets back
        if (Build.VERSION.SDK_INT >= 31) {
            val s = ic.getSurroundingText(TextWindow.SIDE_CHARS, TextWindow.SIDE_CHARS, 0) ?: return EditorState.EMPTY
            val text = s.text
            val a = s.selectionStart.coerceIn(0, text.length)
            val b = s.selectionEnd.coerceIn(a, text.length)
            return TextWindow.bound(text.subSequence(0, a), text.subSequence(b, text.length), text.subSequence(a, b))
        }
        return TextWindow.bound(
            ic.getTextBeforeCursor(TextWindow.SIDE_CHARS, 0),
            ic.getTextAfterCursor(TextWindow.SIDE_CHARS, 0),
            ic.getSelectedText(0),
        )
    }

    override fun sendRawEnter() {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    override fun hapticFeedback(kind: String) {
        // The shell only sends feedback.key when haptics or key sounds are enabled in its settings.
        val v = container ?: return
        v.performHapticFeedback(if (kind == "tap") HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.playSoundEffect(
            when (kind) {
                "delete" -> AudioManager.FX_KEYPRESS_DELETE
                "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
                "return" -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            },
            -1f,
        )
    }

    override fun switchToNextKeyboard() {
        switchToNextInputMethod(false)
    }

    override fun setKeyboardHeightDp(dp: Int) {
        if (dp == heightDp) return
        heightDp = dp
        web.view?.let { wv -> wv.layoutParams = wv.layoutParams?.apply { height = px(dp) } }
        fallback?.let { fb -> fb.layoutParams = fb.layoutParams?.apply { height = px(dp) } }
    }

    override fun onShellReady() {
        shellReady = true
        shellIncompatible = false
        applyVisibility()
    }

    override fun onShellIncompatible() {
        shellIncompatible = true
        applyVisibility()
    }

    override fun postToShell(raw: String) {
        web.post(raw)
    }

    // ───────────── ImeWebView.Listener ─────────────

    override fun onShellMessage(raw: String) = controller.router.receive(raw)

    override fun onRendererGone(crashed: Boolean) {
        shellReady = false
        controller.router.reset()
        applyVisibility()
        // Recreate, but never in a crash loop: at most 3 restarts per minute, then stay on the fallback.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRendererRestartAt > 60_000) rendererRestarts = 0
        lastRendererRestartAt = now
        if (++rendererRestarts <= 3) {
            web.create()
            if (container != null) onCreateInputView().also { setInputView(it) }
        } else {
            AkLog.w("renderer restart limit reached; fallback keyboard until next IME start")
        }
    }

    // ───────────── FallbackKeyboardView.Listener ─────────────

    override fun onFallbackText(text: String) = executor.submit(EditOp.Commit(text))
    override fun onFallbackBackspace() = executor.submit(EditOp.Delete(1, 0))
    override fun onFallbackEnter() = executor.submit(EditOp.Action(classification?.context?.imeAction ?: ImeActionKind.NEWLINE))
    override fun onFallbackSwitchKeyboard() = switchToNextKeyboard()

    // ───────────── internals ─────────────

    private fun applyVisibility() {
        val showWeb = shellReady && !shellIncompatible && web.view != null
        web.view?.visibility = if (showWeb) View.VISIBLE else View.INVISIBLE // INVISIBLE keeps it laid out and warm
        fallback?.visibility = if (showWeb) View.GONE else View.VISIBLE
    }

    private fun scheduleEditorState() {
        if (stateScheduled || !shellReady) return
        stateScheduled = true
        Choreographer.getInstance().postFrameCallback(stateFrame)
    }

    private fun onClipChanged() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (_: SecurityException) { null } ?: return
        val desc = clip.description
        val sensitive = Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.take(MAX_CLIP_CHARS) ?: return
        if (text.isBlank() || sensitive) return
        controller.onClipboardText(text, sensitive)
    }

    private fun snapshot(info: EditorInfo): EditorInfoSnapshot {
        val pkg = info.packageName.orEmpty()
        return EditorInfoSnapshot(
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            packageName = pkg,
            appLabel = appLabel(pkg),
            hintText = info.hintText?.toString(),
            label = info.label?.toString(),
            // EditorInfo carries no autofill hints; sensitivity falls back to inputType + hint/label text.
            autofillHints = null,
        )
    }

    private val labelCache = HashMap<String, String>()
    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val DEFAULT_HEIGHT_DP = 290
        const val MAX_CLIP_CHARS = 10_000
    }
}
