package com.agentkeyboard.core.speech

/*
 * Turns raw streaming-ASR output into text worth inserting (SPEC §13). The English zipformer models
 * are trained on upper-case transcripts with no punctuation, so without this every dictated word
 * would arrive SHOUTED.
 */
object Transcript {
    private val SPACES = Regex("\\s+")
    private val PRONOUN_I = Regex("\\bi\\b(?=$|[\\s'’])")
    private val SENTENCE_END = Regex("[.!?…]\\s*$")

    /**
     * [sentenceStart]: the text before the cursor is empty or ends a sentence, so the segment starts
     * with a capital. Mixed-case output (a model that already cases) is kept as is.
     */
    fun tidy(raw: String, sentenceStart: Boolean): String {
        var t = raw.replace(SPACES, " ").trim()
        if (t.isEmpty()) return t
        val letters = t.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            t = t.lowercase().replace(PRONOUN_I, "I")
        }
        if (sentenceStart) t = t.replaceFirstChar { it.titlecase() }
        return t
    }

    /** Whether text inserted after [before] starts a new sentence. */
    fun startsSentence(before: String): Boolean {
        val b = before.trimEnd()
        return b.isEmpty() || SENTENCE_END.containsMatchIn(b) || before.endsWith("\n")
    }
}
