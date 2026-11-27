package com.agentkeyboard.core.editor

import com.agentkeyboard.core.protocol.Command
import com.agentkeyboard.core.protocol.EditorState
import com.agentkeyboard.core.protocol.ImeActionKind

/**
 * Text operations queued between two frames and applied inside one
 * `beginBatchEdit()/endBatchEdit()` (SPEC §8.3, §9). Counts are Unicode code points, matching
 * `InputConnection.deleteSurroundingTextInCodePoints` (API 24; our minSdk is 29) and the wire unit of
 * `text.delete` (SPEC §8.2).
 */
sealed interface EditOp {
    data class Commit(val text: String) : EditOp
    data class Delete(val before: Int, val after: Int) : EditOp
    data class ReplaceField(val text: String) : EditOp
    data class ReplaceWord(val text: String) : EditOp
    data class Action(val action: ImeActionKind) : EditOp
    data class Cursor(val delta: Int) : EditOp

    companion object {
        /** Maps a decoded text/editor command to an op; null for non-text commands. */
        fun of(c: Command): EditOp? = when (c) {
            is Command.Commit -> Commit(c.text)
            is Command.Delete -> Delete(c.before, c.after)
            is Command.Replace -> when (c.scope) {
                "field" -> ReplaceField(c.text)
                "word" -> ReplaceWord(c.text)
                else -> Commit(c.text) // "selection": commitText replaces the selection atomically
            }
            is Command.Action -> Action(c.action)
            is Command.Cursor -> Cursor(c.delta)
            else -> null
        }
    }
}

object EditBatcher {
    /**
     * Merges a frame's ops into the minimal equivalent sequence. Order is preserved; only
     * provably-equivalent rewrites are applied:
     *  - `Commit(a) + Commit(b)` → `Commit(a+b)`
     *  - `Commit(t) + Delete(n ≤ |t|, 0)` → `Commit(t minus its last n code points)` — typing then
     *    backspacing within one frame never touches the host editor twice. This stays correct when
     *    the commit replaced a selection: the selection is still replaced, by the shorter text.
     *  - `Delete(a,0) + Delete(c,0)` → `Delete(a+c,0)` (same for forward deletes). Mixed directions are
     *    kept apart: with a live selection the first delete consumes the selection (see the executor),
     *    and which side it is charged to depends on direction.
     *  - `Cursor(a) + Cursor(b)` → `Cursor(a+b)`, dropped when zero.
     * Actions, field/word replaces and partial overlaps are never merged.
     */
    fun coalesce(ops: List<EditOp>): List<EditOp> {
        if (ops.size < 2) return ops
        val out = ArrayList<EditOp>(ops.size)
        for (op in ops) {
            val last = out.lastOrNull()
            val merged: EditOp? = when {
                last is EditOp.Commit && op is EditOp.Commit -> EditOp.Commit(last.text + op.text)
                last is EditOp.Commit && op is EditOp.Delete && op.after == 0 &&
                    op.before <= last.text.codePointCount(0, last.text.length) ->
                    EditOp.Commit(dropLastCodePoints(last.text, op.before))
                last is EditOp.Delete && op is EditOp.Delete && sameDirection(last, op) ->
                    EditOp.Delete(last.before + op.before, last.after + op.after)
                last is EditOp.Cursor && op is EditOp.Cursor -> EditOp.Cursor(last.delta + op.delta)
                else -> null
            }
            if (merged != null) out[out.size - 1] = merged else out.add(op)
        }
        out.removeAll { it is EditOp.Cursor && it.delta == 0 }
        return out
    }

    private fun sameDirection(a: EditOp.Delete, b: EditOp.Delete) =
        (a.after == 0 && b.after == 0) || (a.before == 0 && b.before == 0)

    fun dropLastCodePoints(s: String, n: Int): String {
        if (n <= 0) return s
        val end = s.offsetByCodePoints(s.length, -n)
        return s.substring(0, end)
    }
}

object TextWindow {
    /** Max chars sent on each side of the cursor (EditorState contract, SPEC §8). */
    const val SIDE_CHARS = 2_000

    /**
     * Bounds raw editor text to the wire contract without splitting a surrogate pair at the cut
     * (a lone surrogate would corrupt the shell's word-boundary logic).
     */
    fun bound(before: CharSequence?, after: CharSequence?, selected: CharSequence?): EditorState =
        EditorState(tail(before, SIDE_CHARS), head(after, SIDE_CHARS), head(selected, SIDE_CHARS))

    fun tail(s: CharSequence?, max: Int): String {
        if (s == null) return ""
        if (s.length <= max) return s.toString()
        var start = s.length - max
        if (Character.isLowSurrogate(s[start])) start++
        return s.subSequence(start, s.length).toString()
    }

    fun head(s: CharSequence?, max: Int): String {
        if (s == null) return ""
        if (s.length <= max) return s.toString()
        var end = max
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.subSequence(0, end).toString()
    }

    /**
     * Code points of the word immediately before the cursor — what `text.replace{scope:"word"}`
     * replaces (e.g. applying a correction from the suggestion strip). Word characters are exactly the
     * shell's `currentWord` set (`[\p{L}\p{M}\p{N}'’-]`, keyboard-engine `text.ts`): letters, combining
     * marks (Devanagari vowel signs, decomposed accents), digits, apostrophes and hyphens. Any mismatch
     * would replace a different span than the shell computed its suggestion for.
     */
    fun wordBeforeCursorCodePoints(before: String): Int {
        var i = before.length
        var count = 0
        while (i > 0) {
            val cp = before.codePointBefore(i)
            if (!isWordCodePoint(cp)) break
            i -= Character.charCount(cp)
            count++
        }
        return count
    }

    /**
     * Code points of the last user-perceived character (grapheme cluster) of [before]: what one
     * backspace removes (FR-KB-05), e.g. 1 for "a", 2 for 👍🏽, 5 for a ZWJ family. Used by the native
     * fallback keyboard; the shell computes the same with `Intl.Segmenter`.
     */
    fun lastGraphemeCodePoints(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val s = before.toString()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous().takeIf { b -> b != java.text.BreakIterator.DONE } ?: 0
        return s.codePointCount(start, end).coerceAtLeast(1)
    }

    private fun isWordCodePoint(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> cp == '\''.code || cp == '’'.code || cp == '-'.code
    }
}
