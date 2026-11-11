package com.auralis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming stereo windowed-sinc resampler (32 taps, Kaiser window, 512 phases with linear
 * phase interpolation, DC gain 1). It is used only when a queued song's sample rate differs
 * from the open AudioTrack, so gapless and crossfade transitions can stay on one output.
 */
internal class StreamResampler(inRate: Int, outRate: Int) {
    private val step: Double
    private val table: Array<FloatArray>
    private var bufL = FloatArray(INITIAL)
    private var bufR = FloatArray(INITIAL)
    // HALF leading zeros give the filter its history at stream start.
    private var count = HALF
    private var pos = HALF.toDouble()

    init {
        require(inRate > 0 && outRate > 0) { "Invalid sample rate" }
        step = inRate.toDouble() / outRate.toDouble()
        val cutoff = min(1.0, outRate.toDouble() / inRate.toDouble()) * 0.95
        val norm = bessel0(BETA)
        table = Array(PHASES + 1) { p ->
            val frac = p.toDouble() / PHASES
            val row = DoubleArray(TAPS)
            var sum = 0.0
            for (k in 0 until TAPS) {
                val x = (k - HALF + 1) - frac
                val t = cutoff * x
                val sinc = if (abs(t) < 1e-9) 1.0 else sin(PI * t) / (PI * t)
                val r = x / HALF
                val w = if (abs(r) >= 1.0) 0.0 else bessel0(BETA * sqrt(1.0 - r * r)) / norm
                row[k] = cutoff * sinc * w
                sum += row[k]
            }
            FloatArray(TAPS) { (row[it] / sum).toFloat() }
        }
    }

    fun push(l: Float, r: Float, sink: (Float, Float) -> Unit) {
        if (count == bufL.size) {
            val drop = pos.toInt() - HALF + 1
            if (drop > 0) {
                System.arraycopy(bufL, drop, bufL, 0, count - drop)
                System.arraycopy(bufR, drop, bufR, 0, count - drop)
                count -= drop
                pos -= drop
            } else {
                bufL = bufL.copyOf(bufL.size * 2)
                bufR = bufR.copyOf(bufR.size * 2)
            }
        }
        bufL[count] = l
        bufR[count] = r
        count++
        while (true) {
            val i0 = pos.toInt()
            if (i0 + HALF >= count) break
            val ph = (pos - i0) * PHASES
            val p = ph.toInt()
            val a = (ph - p).toFloat()
            val c0 = table[p]
            val c1 = table[p + 1]
            val base = i0 - HALF + 1
            var accL = 0f
            var accR = 0f
            for (k in 0 until TAPS) {
                val c = c0[k] + (c1[k] - c0[k]) * a
                accL += c * bufL[base + k]
                accR += c * bufR[base + k]
            }
            sink(accL, accR)
            pos += step
        }
    }

    private companion object {
        const val TAPS = 32
        const val HALF = TAPS / 2
        const val PHASES = 512
        const val BETA = 9.0
        const val INITIAL = 4096

        fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (k < 60) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < 1e-12 * sum) break
                k++
            }
            return sum
        }
    }
}
