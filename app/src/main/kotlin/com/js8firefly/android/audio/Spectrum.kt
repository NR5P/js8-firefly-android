package com.js8firefly.android.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Rolling spectrum for the waterfall: 4096-point Hann-windowed FFT of the
 * 12 kHz receive stream (2.93 Hz bins), hop of 1024 samples (~11.7 rows/s).
 */
class SpectrumAnalyzer {
    companion object {
        const val fftSize = 4096
        const val hop = 1024
        val binHz: Double = RxRingBuffer.rate.toDouble() / fftSize.toDouble()
    }

    // Hann window, peak 1 (vDSP .hanningDenormalized).
    private val window = FloatArray(fftSize) { i -> (0.5 * (1 - cos(2 * PI * i / fftSize))).toFloat() }
    private val history = FloatArray(fftSize)
    private var pending = 0
    private var writePos = 0
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val cosT = DoubleArray(fftSize / 2) { cos(-2 * PI * it / fftSize) }
    private val sinT = DoubleArray(fftSize / 2) { sin(-2 * PI * it / fftSize) }
    private val bitRev = IntArray(fftSize).also { r ->
        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Called with one row of power in dB for bins 0 until fftSize/2. */
    @Volatile
    var onRow: ((FloatArray) -> Unit)? = null

    @Synchronized
    fun process(data: FloatArray, count: Int = data.size) {
        for (i in 0 until count) {
            history[writePos] = data[i]
            writePos = (writePos + 1) % fftSize
            pending += 1
            if (pending >= hop) {
                pending = 0
                computeRow()
            }
        }
    }

    private fun computeRow() {
        val n = fftSize
        for (i in 0 until n) {
            val j = bitRev[i]
            re[j] = (history[(writePos + i) % n] * window[i]).toDouble()
            im[j] = 0.0
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        // vDSP's packed real FFT returns 2·X[k] (DC and Nyquist share bin 0);
        // the Swift path then scales |2X|² by 4/n². Same numbers here.
        val scale = 4.0 / (n.toDouble() * n.toDouble())
        val out = FloatArray(n / 2)
        for (k in 0 until n / 2) {
            var p = 4 * (re[k] * re[k] + im[k] * im[k])
            if (k == 0) p = 4 * (re[0] * re[0] + re[n / 2] * re[n / 2])
            out[k] = (10 * log10(p * scale + 1e-12)).toFloat()
        }
        onRow?.invoke(out)
    }
}
