package com.agentkeyboard.core.capture

/**
 * In-memory frame buffer (ADR-0010): bounded by count, bytes and age, newest last. Evicted frames are
 * handed to [release] (the host zeroes the JPEG bytes) so no screen content lingers in the heap
 * longer than its TTL. Thread-safe: the capture thread writes, the IME main thread reads.
 */
class FrameRing(
    private val capacity: Int = 8,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val ttlMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val release: (Frame) -> Unit = { it.jpeg.fill(0) },
) {
    /** One stored frame. [jpeg] is owned by the ring: copy it if it must outlive eviction. */
    class Frame(
        val handle: String,
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val capturedAt: Long,
        /** Wall-clock time this content was last confirmed on screen (refreshed by unchanged frames). */
        @Volatile var seenAt: Long = capturedAt,
    )

    private val frames = ArrayDeque<Frame>()
    private var bytes = 0
    private var seq = 0L

    @Synchronized
    fun add(jpeg: ByteArray, width: Int, height: Int): Frame {
        purgeExpired()
        val f = Frame("f_${++seq}", jpeg, width, height, clock())
        frames.addLast(f)
        bytes += jpeg.size
        while (frames.size > capacity || (bytes > maxBytes && frames.size > 1)) evict(frames.removeFirst())
        return f
    }

    /** The newest frame still on screen within [maxAgeMs] (default: the TTL). */
    @Synchronized
    fun latest(maxAgeMs: Long = ttlMs): Frame? {
        purgeExpired()
        val f = frames.lastOrNull() ?: return null
        return f.takeIf { clock() - it.seenAt <= maxAgeMs }
    }

    @Synchronized
    fun get(handle: String): Frame? {
        purgeExpired()
        return frames.firstOrNull { it.handle == handle }
    }

    /** The screen still shows the newest frame's content: extend its life without copying pixels. */
    @Synchronized
    fun touchLatest() {
        frames.lastOrNull()?.seenAt = clock()
    }

    @Synchronized
    fun clear() {
        while (frames.isNotEmpty()) evict(frames.removeFirst())
    }

    @Synchronized
    fun size(): Int {
        purgeExpired()
        return frames.size
    }

    @Synchronized
    fun bytes(): Int = bytes

    private fun purgeExpired() {
        val now = clock()
        while (frames.isNotEmpty() && now - frames.first().seenAt > ttlMs) evict(frames.removeFirst())
    }

    private fun evict(f: Frame) {
        bytes -= f.jpeg.size
        release(f)
    }
}
