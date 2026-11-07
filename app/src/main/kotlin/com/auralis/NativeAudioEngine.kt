package com.auralis

import java.nio.ByteBuffer

/** ReplayGain values read from a track's tags; null gain or peak means the tag was absent. */
internal data class ReplayGainTags(
    val albumAvailable: Boolean = false,
    val albumGainDb: Double? = null,
    val albumPeakLinear: Double? = null,
    val trackAvailable: Boolean = false,
    val trackGainDb: Double? = null,
    val trackPeakLinear: Double? = null
)

/** Thin JNI owner. Native queues and DSP state are created once per decoded stream. */
internal class NativeAudioEngine(sampleRate: Int) : AutoCloseable {
    private var handle: Long = nativeCreate(sampleRate)

    init {
        check(handle != 0L) { "Could not create the native audio engine" }
    }

    fun enqueueInput(buffer: ByteBuffer, frames: Int): Boolean = nativeEnqueueInput(handle, buffer, frames)
    fun processOne(): Int = nativeProcessOne(handle)
    fun dequeueOutput(buffer: ByteBuffer, capacityFrames: Int): Int = nativeDequeueOutput(handle, buffer, capacityFrames)
    fun setStereoWide(enabled: Boolean, sliderPercent: Double) = nativeSetStereoWide(handle, enabled, sliderPercent)
    fun setBalance(enabled: Boolean, normalized: Double) = nativeSetBalance(handle, enabled, normalized.coerceIn(-1.0, 1.0))
    fun setMono(enabled: Boolean) = nativeSetMono(handle, enabled)
    fun setLimiter(enabled: Boolean, path: Int) = nativeSetLimiter(handle, enabled, path.coerceIn(0, 1))
    fun setReverbEnabled(enabled: Boolean) = nativeSetReverbEnabled(handle, enabled)
    fun setReverbParameter(index: Int, value: Double) {
        require(index in 0..6)
        nativeSetReverbParameter(handle, index, value.coerceIn(0.0, 1.0))
    }
    fun setEqBand(index: Int, enabled: Boolean, type: Int, frequencyHz: Int, q: Double, gainDb: Double, channel: Int = 0): Boolean =
        nativeSetEqBand(handle, index, enabled, type, frequencyHz, q, gainDb, channel)
    fun setReplayGain(type: Int, source: Int, defaultGainMb: Int, preampMb: Int) =
        nativeSetReplayGain(handle, type, source, defaultGainMb, preampMb)
    fun setReplayGainMetadata(tags: ReplayGainTags) = nativeSetReplayGainMetadata(
        handle, tags.albumAvailable, tags.albumGainDb ?: 0.0, tags.albumPeakLinear ?: 0.0,
        tags.trackAvailable, tags.trackGainDb ?: 0.0, tags.trackPeakLinear ?: 0.0
    )

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
        @JvmStatic private external fun nativeEnqueueInput(handle: Long, buffer: ByteBuffer, frames: Int): Boolean
        @JvmStatic private external fun nativeProcessOne(handle: Long): Int
        @JvmStatic private external fun nativeDequeueOutput(handle: Long, buffer: ByteBuffer, capacityFrames: Int): Int
        @JvmStatic private external fun nativeSetStereoWide(handle: Long, enabled: Boolean, sliderPercent: Double)
        @JvmStatic private external fun nativeSetBalance(handle: Long, enabled: Boolean, normalized: Double)
        @JvmStatic private external fun nativeSetMono(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetLimiter(handle: Long, enabled: Boolean, path: Int)
        @JvmStatic private external fun nativeSetReverbEnabled(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetReverbParameter(handle: Long, index: Int, value: Double)
        @JvmStatic private external fun nativeSetEqBand(handle: Long, index: Int, enabled: Boolean, type: Int,
                                                        frequencyHz: Int, q: Double, gainDb: Double, channel: Int): Boolean
        @JvmStatic private external fun nativeSetReplayGain(handle: Long, type: Int, source: Int,
                                                            defaultGainMb: Int, preampMb: Int)
        @JvmStatic private external fun nativeSetReplayGainMetadata(handle: Long,
            albumAvailable: Boolean, albumGainDb: Double, albumPeakLinear: Double,
            trackAvailable: Boolean, trackGainDb: Double, trackPeakLinear: Double)
    }
}
