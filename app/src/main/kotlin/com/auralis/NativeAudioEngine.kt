package com.auralis

import java.nio.ByteBuffer

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
    fun setEqBand(index: Int, enabled: Boolean, type: Int, frequencyHz: Int, q: Double, gainDb: Double, channel: Int = 0): Boolean =
        nativeSetEqBand(handle, index, enabled, type, frequencyHz, q, gainDb, channel)

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
        @JvmStatic private external fun nativeSetEqBand(handle: Long, index: Int, enabled: Boolean, type: Int,
                                                        frequencyHz: Int, q: Double, gainDb: Double, channel: Int): Boolean
    }
}
