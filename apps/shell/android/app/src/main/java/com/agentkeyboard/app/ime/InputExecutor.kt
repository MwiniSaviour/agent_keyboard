package com.agentkeyboard.app.ime

import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.agentkeyboard.core.editor.EditBatcher
import com.agentkeyboard.core.editor.EditOp
import com.agentkeyboard.core.editor.FieldClassification
import com.agentkeyboard.core.editor.FieldClassifier
import com.agentkeyboard.core.editor.TextWindow
import com.agentkeyboard.core.protocol.ImeActionKind

/**
 * Applies shell text operations to the host editor (SPEC §8.3, §9). Main thread only.
 *
 * Latency vs. batching: the first op in a frame is applied immediately (a single keystroke never waits
 * for vsync, NFR-P-01); ops arriving later in the same frame are queued, coalesced by [EditBatcher]
 * and applied together in one `beginBatchEdit/endBatchEdit` on the next frame. Bursts (fast typists,
 * autonomy typing, voice) thus cost the host app one editor transaction per frame.
 */
class InputExecutor(
    private val connection: () -> InputConnection?,
    private val field: () -> FieldClassification?,
    /** True when the host reported a non-empty selection (tracked from onUpdateSelection, no IPC). */
    private val hasSelection: () -> Boolean,
    private val onApplied: () -> Unit,
) : Choreographer.FrameCallback {
    private val queue = ArrayList<EditOp>(8)
    private var frameScheduled = false
    private var lastImmediateFrameNs = 0L

    fun submit(op: EditOp) {
        val now = System.nanoTime()
        if (!frameScheduled && queue.isEmpty() && now - lastImmediateFrameNs > FRAME_NS) {
            lastImmediateFrameNs = now
            apply(listOf(op))
            return
        }
        queue += op
        if (!frameScheduled) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        flush()
    }

    /** Apply everything pending now (focus change, autonomy send). */
    fun flush() {
        if (queue.isEmpty()) return
        val ops = EditBatcher.coalesce(queue.toList())
        queue.clear()
        apply(ops)
    }

    fun discard() {
        queue.clear()
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this)
            frameScheduled = false
        }
    }

    private fun apply(ops: List<EditOp>) {
        val ic = connection() ?: return
        ic.beginBatchEdit()
        try {
            var selection = hasSelection()
            for (op in ops) {
                applyOne(ic, op, selection)
                selection = false // every op below collapses the selection
            }
        } finally {
            ic.endBatchEdit()
        }
        onApplied()
    }

    private fun applyOne(ic: InputConnection, op: EditOp, selection: Boolean) {
        when (op) {
            is EditOp.Commit -> ic.commitText(op.text, 1)
            is EditOp.Delete -> {
                var before = op.before
                var after = op.after
                if (selection && (before > 0 || after > 0)) {
                    // Backspace/delete with a live selection removes the selection first, like every keyboard.
                    ic.commitText("", 1)
                    if (before > 0) before-- else after--
                }
                if (before > 0 || after > 0) ic.deleteSurroundingTextInCodePoints(before, after)
            }
            is EditOp.ReplaceField -> {
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                // Editors may return a partial window (startOffset > 0): select to the end of the
                // whole text, not of the window, or the tail of a long field would survive.
                if (extracted?.text != null) ic.setSelection(0, maxOf(0, extracted.startOffset) + extracted.text.length)
                else ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText(op.text, 1)
            }
            is EditOp.ReplaceWord -> {
                val before = ic.getTextBeforeCursor(WORD_WINDOW, 0)?.toString().orEmpty()
                val n = TextWindow.wordBeforeCursorCodePoints(before)
                if (n > 0) ic.deleteSurroundingTextInCodePoints(n, 0)
                ic.commitText(op.text, 1)
            }
            is EditOp.Action -> performAction(ic, op.action)
            is EditOp.Cursor -> {
                val code = if (op.delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                repeat(minOf(kotlin.math.abs(op.delta), MAX_CURSOR_STEPS)) { sendKey(ic, code) }
            }
        }
    }

    private fun performAction(ic: InputConnection, action: ImeActionKind) {
        val f = field()
        when {
            action == ImeActionKind.NEWLINE || action == ImeActionKind.NONE ->
                if (f?.enterIsNewline == true) ic.commitText("\n", 1) else sendKey(ic, KeyEvent.KEYCODE_ENTER)
            else -> {
                val id = FieldClassifier.actionIdOf(action)
                if (!ic.performEditorAction(id)) sendKey(ic, KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    /**
     * A soft-keyboard Enter key event, for apps whose "Enter is send" setting listens for the key
     * rather than an editor action (autonomy strategy ENTER_IS_SEND, SPEC §11.6.3). Same flags as
     * every other key this IME sends. Call [flush] first so the draft lands before the Enter.
     */
    fun sendEnterKey(): Boolean {
        val ic = connection() ?: return false
        sendKey(ic, KeyEvent.KEYCODE_ENTER)
        return true
    }

    private fun sendKey(ic: InputConnection, code: Int) {
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyEvent.KEYCODE_UNKNOWN, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, 0, KeyEvent.KEYCODE_UNKNOWN, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    private companion object {
        const val FRAME_NS = 16_000_000L
        const val WORD_WINDOW = 64
        const val MAX_CURSOR_STEPS = 200
    }
}
