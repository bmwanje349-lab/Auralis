package com.auralis

import java.nio.ByteBuffer

/**
 * Clip protection: the last stage before the AudioTrack. A transparent look-ahead true-peak limiter that
 * keeps hot tracks (and effect or crossfade sums) from clipping flat. Quiet audio passes through
 * bit-exact, delayed by about 2 ms. Buffers must be direct, interleaved stereo float PCM.
 *
 * One instance must see one continuous stream, from the output thread only.
 */
internal class NativeSafetyLimiter(sampleRate: Int) : AutoCloseable {
    private var handle: Long = nativeCreate(sampleRate)

    init { check(handle != 0L) { "Could not create the clip protection limiter" } }

    fun process(buffer: ByteBuffer, frames: Int) {
        if (handle != 0L) nativeProcess(handle, buffer, frames)
    }

    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    /** Deepest gain reduction since the last reset, in dB (0 when nothing was limited). */
    val maxReductionDb: Double get() = if (handle != 0L) nativeMaxReductionDb(handle) else 0.0

    override fun close() {
        val old = handle
        if (old != 0L) {
            handle = 0L
            nativeDestroy(old)
        }
    }

    companion object {
        init { System.loadLibrary("auralis") }
        @JvmStatic private external fun nativeCreate(sampleRate: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeProcess(handle: Long, buffer: ByteBuffer, frames: Int)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeMaxReductionDb(handle: Long): Double
    }
}
