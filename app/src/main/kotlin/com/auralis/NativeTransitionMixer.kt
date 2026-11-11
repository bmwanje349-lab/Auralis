package com.auralis

import java.nio.ByteBuffer

/** Native two-context transition mixer. Buffers must be direct, interleaved stereo float PCM. */
internal class NativeTransitionMixer(sampleRate: Int) : AutoCloseable {
    private var handle: Long = nativeCreate(sampleRate)

    init { check(handle != 0L) { "Could not create the transition mixer" } }

    fun startCrossfade(durationMs: Int, blockFrames: Int = 1024): Boolean =
        nativeStartCrossfade(handle, durationMs, blockFrames)

    fun startSingleFade(startGain: Float, endGain: Float, durationMs: Int,
                        blockFrames: Int = 1024): Boolean =
        nativeStartSingleFade(handle, startGain, endGain, durationMs, blockFrames)

    fun processCrossfade(outgoing: ByteBuffer?, incoming: ByteBuffer?, output: ByteBuffer, frames: Int) =
        nativeProcessCrossfade(handle, outgoing, incoming, output, frames)

    fun processSingle(input: ByteBuffer, output: ByteBuffer, frames: Int) =
        nativeProcessSingle(handle, input, output, frames)

    val crossfadeComplete: Boolean get() = nativeCrossfadeComplete(handle)
    val singleFadeComplete: Boolean get() = nativeSingleFadeComplete(handle)

    fun reset() = nativeReset(handle)

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
        @JvmStatic private external fun nativeStartCrossfade(handle: Long, durationMs: Int, blockFrames: Int): Boolean
        @JvmStatic private external fun nativeStartSingleFade(handle: Long, startGain: Float, endGain: Float,
                                                               durationMs: Int, blockFrames: Int): Boolean
        @JvmStatic private external fun nativeProcessCrossfade(handle: Long, outgoing: ByteBuffer?,
                                                               incoming: ByteBuffer?, output: ByteBuffer, frames: Int)
        @JvmStatic private external fun nativeProcessSingle(handle: Long, input: ByteBuffer,
                                                            output: ByteBuffer, frames: Int)
        @JvmStatic private external fun nativeCrossfadeComplete(handle: Long): Boolean
        @JvmStatic private external fun nativeSingleFadeComplete(handle: Long): Boolean
        @JvmStatic private external fun nativeReset(handle: Long)
    }
}
