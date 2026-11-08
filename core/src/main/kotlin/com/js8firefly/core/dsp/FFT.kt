// Port of JS8Core/Sources/JS8Core/DSP/FFT.swift.
//
// Array convention: Swift [Complex] buffers become interleaved FloatArrays
// (re at 2*i, im at 2*i + 1) so the hot loops never box. The arithmetic and its
// order match the Swift Complex operators exactly (single precision, no FMA).
package com.js8firefly.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Complex sample, single precision (API parity with Swift; DSP code uses interleaved FloatArrays). */
data class Complex(var re: Float, var im: Float) {
    companion object {
        val zero: Complex get() = Complex(0f, 0f)
        fun polar(r: Float, theta: Float): Complex = Complex(r * cos(theta), r * sin(theta))
    }

    val conj: Complex get() = Complex(re, -im)
    val norm: Float get() = re * re + im * im
    val abs: Float get() = sqrt(re * re + im * im)

    operator fun plus(b: Complex) = Complex(re + b.re, im + b.im)
    operator fun minus(b: Complex) = Complex(re - b.re, im - b.im)
    operator fun times(b: Complex) = Complex(re * b.re - im * b.im, re * b.im + im * b.re)
    operator fun times(s: Float) = Complex(re * s, im * s)
}

/**
 * Mixed-radix Stockham FFT for arbitrary lengths.
 *
 * JS8 uses transform lengths such as 3008 (2^6 * 47) and 360960, so this
 * implementation supports any factorisation: specialised butterflies for radix
 * 2, 3, 4 and 5 and a generic DFT for larger prime factors. Transforms are
 * unnormalised, matching FFTW's FFTW_FORWARD / FFTW_BACKWARD conventions.
 *
 * Data is an interleaved complex FloatArray of at least 2 * count floats.
 * Not thread-safe (shares a work buffer), like the Swift class.
 */
class FFT(val count: Int) {
    private class Stage(
        val radix: Int,
        val n: Int,             // sub-sequence length at this stage
        val stride: Int,        // s
        val twiddles: FloatArray, // m * (radix - 1) complex, index p*(radix-1) + (u-1)
        val roots: FloatArray,    // radix-th roots of unity (generic radix only)
    )

    private val stages: Array<Stage>
    private val work: FloatArray

    init {
        require(count > 0)
        work = FloatArray(2 * count)

        val factors = ArrayList<Int>()
        var rem = count
        while (rem % 4 == 0) { factors.add(4); rem /= 4 }
        while (rem % 2 == 0) { factors.add(2); rem /= 2 }
        var f = 3
        while (rem > 1) {
            while (rem % f == 0) { factors.add(f); rem /= f }
            f += 2
            if (f * f > rem && rem > 1) { factors.add(rem); rem = 1 }
        }

        val st = ArrayList<Stage>()
        var n = count
        var s = 1
        for (r in factors) {
            val m = n / r
            val tw = FloatArray(2 * m * (r - 1))
            val theta0 = -2.0 * Math.PI / n.toDouble()
            for (p in 0 until m) {
                for (u in 1 until r) {
                    val a = theta0 * (p * u).toDouble()
                    val k = p * (r - 1) + (u - 1)
                    tw[2 * k] = cos(a).toFloat()
                    tw[2 * k + 1] = sin(a).toFloat()
                }
            }
            var roots = FloatArray(0)
            if (r > 5) {
                roots = FloatArray(2 * r)
                for (k in 0 until r) {
                    val a = -2.0 * Math.PI * k.toDouble() / r.toDouble()
                    roots[2 * k] = cos(a).toFloat()
                    roots[2 * k + 1] = sin(a).toFloat()
                }
            }
            st.add(Stage(r, n, s, tw, roots))
            n = m
            s *= r
        }
        stages = st.toTypedArray()
    }

    /** In-place forward transform (e^{-i...}). */
    fun forward(data: FloatArray) {
        require(data.size >= 2 * count)
        transform(data)
    }

    /** In-place backward transform (e^{+i...}), unnormalised. */
    fun inverse(data: FloatArray) {
        require(data.size >= 2 * count)
        for (i in 0 until count) data[2 * i + 1] = -data[2 * i + 1]
        transform(data)
        for (i in 0 until count) data[2 * i + 1] = -data[2 * i + 1]
    }

    private fun transform(data: FloatArray) {
        if (stages.isEmpty()) return
        var x = data
        var y = work
        for (stage in stages) {
            runStage(stage, x, y)
            val t = x; x = y; y = t
        }
        if (x !== data) {
            System.arraycopy(x, 0, data, 0, 2 * count)
        }
    }

    private companion object {
        private const val R3C1: Float = -0.5f
        private const val R3C2: Float = -0.86602540378443864676f // -sin(2pi/3)
        private val R5C1 = cos(2.0 * Math.PI / 5.0).toFloat()
        private val R5C2 = cos(4.0 * Math.PI / 5.0).toFloat()
        private val R5S1 = sin(2.0 * Math.PI / 5.0).toFloat()
        private val R5S2 = sin(4.0 * Math.PI / 5.0).toFloat()

        @JvmStatic
        private fun runStage(st: Stage, x: FloatArray, y: FloatArray) {
            val r = st.radix
            val n = st.n
            val s = st.stride
            val m = n / r
            val tw = st.twiddles
            when (r) {
                2 -> {
                    for (p in 0 until m) {
                        val w1r = tw[2 * p]; val w1i = tw[2 * p + 1]
                        val xa = 2 * (s * p)
                        val xb = 2 * (s * (p + m))
                        val y0 = 2 * (s * (2 * p))
                        val y1 = 2 * (s * (2 * p + 1))
                        for (q in 0 until s) {
                            val q2 = 2 * q
                            val ar = x[xa + q2]; val ai = x[xa + q2 + 1]
                            val br = x[xb + q2]; val bi = x[xb + q2 + 1]
                            y[y0 + q2] = ar + br
                            y[y0 + q2 + 1] = ai + bi
                            val dr = ar - br; val di = ai - bi
                            y[y1 + q2] = dr * w1r - di * w1i
                            y[y1 + q2 + 1] = dr * w1i + di * w1r
                        }
                    }
                }
                3 -> {
                    val c1 = R3C1
                    val c2 = R3C2
                    for (p in 0 until m) {
                        val w1r = tw[4 * p]; val w1i = tw[4 * p + 1]
                        val w2r = tw[4 * p + 2]; val w2i = tw[4 * p + 3]
                        val x0 = 2 * (s * p); val x1 = 2 * (s * (p + m)); val x2 = 2 * (s * (p + 2 * m))
                        val y0 = 2 * (s * (3 * p)); val y1 = 2 * (s * (3 * p + 1)); val y2 = 2 * (s * (3 * p + 2))
                        for (q in 0 until s) {
                            val q2 = 2 * q
                            val ar = x[x0 + q2]; val ai = x[x0 + q2 + 1]
                            val br = x[x1 + q2]; val bi = x[x1 + q2 + 1]
                            val cr = x[x2 + q2]; val ci = x[x2 + q2 + 1]
                            val t1r = br + cr; val t1i = bi + ci
                            val t2r = ar + c1 * t1r; val t2i = ai + c1 * t1i
                            val dr = br - cr; val di = bi - ci
                            val t3r = -c2 * di; val t3i = c2 * dr // c2 * i * d
                            y[y0 + q2] = ar + t1r
                            y[y0 + q2 + 1] = ai + t1i
                            val pr = t2r + t3r; val pi = t2i + t3i
                            y[y1 + q2] = pr * w1r - pi * w1i
                            y[y1 + q2 + 1] = pr * w1i + pi * w1r
                            val mr = t2r - t3r; val mi = t2i - t3i
                            y[y2 + q2] = mr * w2r - mi * w2i
                            y[y2 + q2 + 1] = mr * w2i + mi * w2r
                        }
                    }
                }
                4 -> {
                    for (p in 0 until m) {
                        val w1r = tw[6 * p]; val w1i = tw[6 * p + 1]
                        val w2r = tw[6 * p + 2]; val w2i = tw[6 * p + 3]
                        val w3r = tw[6 * p + 4]; val w3i = tw[6 * p + 5]
                        val x0 = 2 * (s * p); val x1 = 2 * (s * (p + m))
                        val x2 = 2 * (s * (p + 2 * m)); val x3 = 2 * (s * (p + 3 * m))
                        val y0 = 2 * (s * (4 * p)); val y1 = 2 * (s * (4 * p + 1))
                        val y2 = 2 * (s * (4 * p + 2)); val y3 = 2 * (s * (4 * p + 3))
                        for (q in 0 until s) {
                            val q2 = 2 * q
                            val ar = x[x0 + q2]; val ai = x[x0 + q2 + 1]
                            val br = x[x1 + q2]; val bi = x[x1 + q2 + 1]
                            val cr = x[x2 + q2]; val ci = x[x2 + q2 + 1]
                            val dr = x[x3 + q2]; val di = x[x3 + q2 + 1]
                            val apcr = ar + cr; val apci = ai + ci
                            val amcr = ar - cr; val amci = ai - ci
                            val bpdr = br + dr; val bpdi = bi + di
                            val bmdr = br - dr; val bmdi = bi - di
                            val jr = bmdi; val ji = -bmdr // -i * (b - d)
                            y[y0 + q2] = apcr + bpdr
                            y[y0 + q2 + 1] = apci + bpdi
                            var er = amcr + jr; var ei = amci + ji
                            y[y1 + q2] = er * w1r - ei * w1i
                            y[y1 + q2 + 1] = er * w1i + ei * w1r
                            er = apcr - bpdr; ei = apci - bpdi
                            y[y2 + q2] = er * w2r - ei * w2i
                            y[y2 + q2 + 1] = er * w2i + ei * w2r
                            er = amcr - jr; ei = amci - ji
                            y[y3 + q2] = er * w3r - ei * w3i
                            y[y3 + q2 + 1] = er * w3i + ei * w3r
                        }
                    }
                }
                5 -> {
                    val c1 = R5C1
                    val c2 = R5C2
                    val s1 = R5S1
                    val s2 = R5S2
                    for (p in 0 until m) {
                        val w1r = tw[8 * p]; val w1i = tw[8 * p + 1]
                        val w2r = tw[8 * p + 2]; val w2i = tw[8 * p + 3]
                        val w3r = tw[8 * p + 4]; val w3i = tw[8 * p + 5]
                        val w4r = tw[8 * p + 6]; val w4i = tw[8 * p + 7]
                        val x0 = 2 * (s * p); val x1 = 2 * (s * (p + m)); val x2 = 2 * (s * (p + 2 * m))
                        val x3 = 2 * (s * (p + 3 * m)); val x4 = 2 * (s * (p + 4 * m))
                        val y0 = 2 * (s * (5 * p)); val y1 = 2 * (s * (5 * p + 1)); val y2 = 2 * (s * (5 * p + 2))
                        val y3 = 2 * (s * (5 * p + 3)); val y4 = 2 * (s * (5 * p + 4))
                        for (q in 0 until s) {
                            val q2 = 2 * q
                            val ar = x[x0 + q2]; val ai = x[x0 + q2 + 1]
                            val br = x[x1 + q2]; val bi = x[x1 + q2 + 1]
                            val cr = x[x2 + q2]; val ci = x[x2 + q2 + 1]
                            val dr = x[x3 + q2]; val di = x[x3 + q2 + 1]
                            val er = x[x4 + q2]; val ei = x[x4 + q2 + 1]
                            val bper = br + er; val bpei = bi + ei
                            val bmer = br - er; val bmei = bi - ei
                            val cpdr = cr + dr; val cpdi = ci + di
                            val cmdr = cr - dr; val cmdi = ci - di
                            val t1r = ar + c1 * bper + c2 * cpdr; val t1i = ai + c1 * bpei + c2 * cpdi
                            val t2r = ar + c2 * bper + c1 * cpdr; val t2i = ai + c2 * bpei + c1 * cpdi
                            // -i * (s1*bme + s2*cmd), -i * (s2*bme - s1*cmd)
                            val u1r = s1 * bmei + s2 * cmdi; val u1i = -(s1 * bmer + s2 * cmdr)
                            val u2r = s2 * bmei - s1 * cmdi; val u2i = -(s2 * bmer - s1 * cmdr)
                            y[y0 + q2] = ar + bper + cpdr
                            y[y0 + q2 + 1] = ai + bpei + cpdi
                            var vr = t1r + u1r; var vi = t1i + u1i
                            y[y1 + q2] = vr * w1r - vi * w1i
                            y[y1 + q2 + 1] = vr * w1i + vi * w1r
                            vr = t2r + u2r; vi = t2i + u2i
                            y[y2 + q2] = vr * w2r - vi * w2i
                            y[y2 + q2 + 1] = vr * w2i + vi * w2r
                            vr = t2r - u2r; vi = t2i - u2i
                            y[y3 + q2] = vr * w3r - vi * w3i
                            y[y3 + q2 + 1] = vr * w3i + vi * w3r
                            vr = t1r - u1r; vi = t1i - u1i
                            y[y4 + q2] = vr * w4r - vi * w4i
                            y[y4 + q2 + 1] = vr * w4i + vi * w4r
                        }
                    }
                }
                else -> {
                    val roots = st.roots
                    val tmp = FloatArray(2 * r)
                    for (p in 0 until m) {
                        for (q in 0 until s) {
                            for (t in 0 until r) {
                                val xi = 2 * (q + s * (p + t * m))
                                tmp[2 * t] = x[xi]
                                tmp[2 * t + 1] = x[xi + 1]
                            }
                            for (u in 0 until r) {
                                var accr = 0f
                                var acci = 0f
                                var idx = 0
                                for (t in 0 until r) {
                                    val tr = tmp[2 * t]; val ti = tmp[2 * t + 1]
                                    val rr = roots[2 * idx]; val ri = roots[2 * idx + 1]
                                    accr += tr * rr - ti * ri
                                    acci += tr * ri + ti * rr
                                    idx += u
                                    if (idx >= r) idx -= r
                                }
                                val yi = 2 * (q + s * (r * p + u))
                                if (u == 0) {
                                    y[yi] = accr
                                    y[yi + 1] = acci
                                } else {
                                    val k = 2 * (p * (r - 1) + (u - 1))
                                    val wr = tw[k]; val wi = tw[k + 1]
                                    y[yi] = accr * wr - acci * wi
                                    y[yi + 1] = accr * wi + acci * wr
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Real-input forward FFT of even length `count`, producing `count/2 + 1`
 * complex bins (matching fftwf_plan_dft_r2c_1d).
 */
class RealFFT(val count: Int) {
    private val half: FFT
    private val buffer: FloatArray
    private val twiddles: FloatArray

    init {
        require(count % 2 == 0)
        half = FFT(count / 2)
        buffer = FloatArray(count) // count/2 complex
        val h = count / 2
        twiddles = FloatArray(2 * h)
        for (k in 0 until h) {
            val a = -2.0 * Math.PI * k.toDouble() / count.toDouble()
            twiddles[2 * k] = cos(a).toFloat()
            twiddles[2 * k + 1] = sin(a).toFloat()
        }
    }

    /** `input` must hold `count` reals; `output` (interleaved complex) receives `count/2 + 1` bins. */
    fun forward(input: FloatArray, output: FloatArray) {
        val h = count / 2
        val z = buffer
        System.arraycopy(input, 0, z, 0, 2 * h) // z[i] = Complex(input[2i], input[2i+1])
        half.forward(z)
        val tw = twiddles
        val z0r = z[0]; val z0i = z[1]
        output[0] = z0r + z0i
        output[1] = 0f
        output[2 * h] = z0r - z0i
        output[2 * h + 1] = 0f
        if (h > 1) {
            for (k in 1 until h) {
                val ar = z[2 * k]; val ai = z[2 * k + 1]
                val br = z[2 * (h - k)]; val bi = -z[2 * (h - k) + 1]
                val evr = (ar + br) * 0.5f; val evi = (ai + bi) * 0.5f
                val dr = ar - br; val di = ai - bi
                val odr = di * 0.5f; val odi = -dr * 0.5f // -i/2 * (a - b)
                val wr = tw[2 * k]; val wi = tw[2 * k + 1]
                output[2 * k] = evr + (odr * wr - odi * wi)
                output[2 * k + 1] = evi + (odr * wi + odi * wr)
            }
        }
    }
}
