package com.agentkeyboard.core.capture

/**
 * A compact, content-sensitive fingerprint of one screen frame (ADR-0010): the mean luma of a
 * [COLS]×[ROWS] block grid over the analysed region.
 *
 * Why not a perceptual hash (dHash/pHash)? Those are built to *ignore* small edits, while the edits
 * that matter here are small: one new chat bubble, a changed price, a dialog line. Block means catch
 * them, yet stay cheap (≈ 25 k strided samples per frame, no allocation besides the 256-byte grid)
 * and blind to sub-block noise such as a blinking text cursor (see [FrameDiff]).
 *
 * The sampler is platform-free: the Android side passes a `(x, y) -> luma` reader over the
 * `ImageReader` plane, so this file and its policy are unit-tested on the JVM.
 */
class FrameSignature private constructor(val blocks: ByteArray, val mean: Int, val maxBlock: Int, val spread: Int) {

    /** True for frames that are (almost) uniformly black: `FLAG_SECURE` windows, screen off, DRM video. */
    val isBlank: Boolean get() = maxBlock <= BLANK_MAX_LUMA && spread <= BLANK_MAX_SPREAD

    fun block(i: Int): Int = blocks[i].toInt() and 0xFF

    companion object {
        const val COLS = 16
        const val ROWS = 16
        private const val BLANK_MAX_LUMA = 20
        private const val BLANK_MAX_SPREAD = 6

        /**
         * Samples the region [left, top, right, bottom) of a frame with [luma] (0–255 per pixel).
         * [step] is the sampling stride inside each block; 3–4 px keeps the cost well under 2 ms on a
         * 432×960 frame while still seeing 1 px text strokes on average.
         */
        fun of(left: Int, top: Int, right: Int, bottom: Int, step: Int = 3, luma: (x: Int, y: Int) -> Int): FrameSignature {
            require(right > left && bottom > top && step >= 1) { "empty region" }
            val w = right - left
            val h = bottom - top
            val out = ByteArray(COLS * ROWS)
            var total = 0L
            var minB = 255
            var maxB = 0
            for (r in 0 until ROWS) {
                val y0 = top + r * h / ROWS
                val y1 = maxOf(y0 + 1, top + (r + 1) * h / ROWS)
                for (c in 0 until COLS) {
                    val x0 = left + c * w / COLS
                    val x1 = maxOf(x0 + 1, left + (c + 1) * w / COLS)
                    var sum = 0L
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        var x = x0
                        while (x < x1) {
                            sum += luma(x, y).coerceIn(0, 255)
                            n++
                            x += step
                        }
                        y += step
                    }
                    val m = (sum / maxOf(n, 1)).toInt()
                    out[r * COLS + c] = m.toByte()
                    total += m
                    if (m < minB) minB = m
                    if (m > maxB) maxB = m
                }
            }
            return FrameSignature(out, (total / (COLS * ROWS)).toInt(), maxB, maxB - minB)
        }

        /** Rec. 601 luma of a packed RGBA/ARGB-agnostic triple. */
        fun luma(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000
    }
}

object FrameDiff {
    /** A block counts as changed when its mean luma moved by more than this. */
    const val BLOCK_THRESHOLD = 12

    /**
     * At least this many blocks must change. A blinking caret or a spinner touches one block, a new
     * message bubble or a scrolled list touches many.
     */
    const val MIN_CHANGED_BLOCKS = 2

    fun changedBlocks(a: FrameSignature, b: FrameSignature, threshold: Int = BLOCK_THRESHOLD): Int {
        var n = 0
        for (i in a.blocks.indices) if (kotlin.math.abs(a.block(i) - b.block(i)) > threshold) n++
        return n
    }

    fun changed(previous: FrameSignature?, current: FrameSignature): Boolean =
        previous == null || changedBlocks(previous, current) >= MIN_CHANGED_BLOCKS
}
