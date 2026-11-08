// Port of JS8Core/Sources/JS8Core/Modem/JS8Decoder.swift.
//
// Conventions: Swift [Complex] buffers are interleaved FloatArrays (re, im);
// bit arrays are ByteArray (0/1). Swift `Float.rounded()` (ties away from zero),
// Swift's `max(x, y)` (`y >= x ? y : x`) and Swift's `Float.pi` (rounded toward
// zero, 0x40490FDA, one ulp below kotlin.math.PI.toFloat()) are reproduced exactly.
package com.js8firefly.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/** A frame recovered from the air. */
data class JS8RawDecode(
    var frame: String,      // 12 characters
    var type: Int,          // 3-bit transmission type (first/last/data flags)
    var snr: Int,
    var dt: Float,          // time offset in seconds
    var frequency: Float,   // audio offset in Hz
    var quality: Float,
    var submode: JS8Submode,
)

/** Candidate sync information, useful for drawing decode attempts. */
data class JS8SyncCandidate(
    var frequency: Float,
    var dt: Float,
    var sync: Float,
)

/**
 * Per-submode JS8 decoder. Port of JS8Call's `DecodeMode<Mode>` (JS8.cpp),
 * which is itself a conversion of the original Fortran decoder. Constants,
 * array sizes, index arithmetic and pass structure follow the reference so
 * that this decoder recovers the same frames from the same audio.
 * Not thread-safe: use one instance per thread.
 */
class JS8ModeDecoder(val submode: JS8Submode) {
    companion object {
        // Common parameters
        internal const val NN = 79
        internal const val ND = 58
        internal const val ASYNCMIN: Float = 1.5f
        internal const val NFSRCH = 5
        internal const val NMAXCAND = 300
        internal const val NFILT = 1400
        internal const val NROWS = 8
        internal const val NFOS = 2
        internal const val NSSY = 4
        internal const val NP = 3200
        internal const val NP2 = 2812 // note: the reference uses this shared constant in js8dec

        /** Swift's Float.pi (rounded toward zero). */
        internal val SWIFT_FLOAT_PI: Float = Float.fromBits(0x40490FDA)
        internal val TAU: Float = 2 * SWIFT_FLOAT_PI

        internal const val BASELINE_DEGREE = 5
        internal const val BASELINE_SAMPLE = 10
        internal const val BASELINE_MIN = 500
        internal const val BASELINE_MAX = 2500

        /** Swift `Float.rounded()` -> Int (to nearest, ties away from zero). */
        @JvmStatic
        internal fun roundedInt(x: Float): Int {
            val t = truncate(x)
            return (if (abs(x - t) >= 0.5f) t + (if (x < 0) -1f else 1f) else t).toInt()
        }

        /** Swift `Double.rounded()` -> Int. */
        @JvmStatic
        internal fun roundedInt(x: Double): Int {
            val t = truncate(x)
            return (if (abs(x - t) >= 0.5) t + (if (x < 0) -1.0 else 1.0) else t).toInt()
        }

        /** Swift's global `max(x, y)`. */
        @JvmStatic
        private fun smax(x: Float, y: Float): Float = if (y >= x) y else x

        /** Swift's variadic `max(x, y, z, w)`. */
        @JvmStatic
        private fun smax4(x: Float, y: Float, z: Float, w: Float): Float {
            var m = smax(smax(x, y), z)
            if (w >= m) m = w
            return m
        }

        /**
         * Solve V c = y for the polynomial coefficients (Gaussian elimination with
         * partial pivoting; V is square here).
         */
        @JvmStatic
        internal fun solveVandermonde(x: DoubleArray, y: DoubleArray): DoubleArray {
            val n = x.size
            val a = Array(n) { DoubleArray(n + 1) }
            for (r in 0 until n) {
                var v = 1.0
                for (col in 0 until n) {
                    a[r][col] = v
                    v *= x[r]
                }
                a[r][n] = y[r]
            }
            for (col in 0 until n) {
                var piv = col
                for (r in (col + 1) until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (piv != col) { val t = a[piv]; a[piv] = a[col]; a[col] = t }
                val d = a[col][col]
                if (d == 0.0) continue
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r][col] / d
                    if (f != 0.0) {
                        for (k in col..n) a[r][k] -= f * a[col][k]
                    }
                }
            }
            return DoubleArray(n) { if (a[it][it] == 0.0) 0.0 else a[it][n] / a[it][it] }
        }
    }

    // Mode parameters
    internal val NSPS: Int
    internal val NTXDUR: Int
    internal val NDOWNSPS: Int
    internal val NDD: Int
    internal val JZ: Int
    internal val ASTART: Float
    internal val BASESUB: Float
    internal val AZ: Float
    internal val NMAX: Int
    internal val NFFT1: Int
    internal val NSTEP: Int
    internal val NHSYM: Int
    internal val NDOWN: Int
    internal val NQSYMBOL: Int
    internal val NDFFT1: Int
    internal val NDFFT2: Int
    internal val MODE_NP2: Int
    internal val TSTEP: Float
    internal val JSTRT: Int
    internal val DF: Float

    internal val costas: Array<IntArray>

    // Buffers (complex buffers interleaved)
    internal val nuttal: FloatArray
    internal val csyncs: FloatArray         // [3][7][NDOWNSPS] complex
    internal val csymb: FloatArray
    internal val filter: FloatArray
    internal val cfilt: FloatArray
    internal val dsCx: FloatArray           // NDFFT1/2 + 1 complex
    internal val sd: FloatArray             // NFFT1/2 + 1 complex
    internal val cd0: FloatArray            // NP complex
    internal val dd: FloatArray             // NMAX
    internal val s: FloatArray              // [NSPS][NHSYM] row-major by frequency
    internal val savg: FloatArray           // NSPS
    internal val sdIn: FloatArray           // NFFT1 windowed input
    internal val bbIn: FloatArray           // NDFFT1 input

    // Scratch (allocated per call in Swift; preallocated here, same values)
    private val cd0Tmp: FloatArray
    private val freqAdjust: FloatArray
    private val s2: FloatArray
    private val llr0 = FloatArray(3 * ND)
    private val llr1 = FloatArray(3 * ND)
    private val ps = FloatArray(8)
    private val decodedBits = ByteArray(JS8Codec.K)
    private val cwBits = ByteArray(JS8Codec.N)
    private val cref: FloatArray            // NN * NSPS complex

    // FFTs
    internal val fftSD: RealFFT
    internal val fftBB: RealFFT
    internal val fftDS: FFT
    internal val fftCS: FFT
    internal val fftCF: FFT

    internal val taperHead: FloatArray
    internal val taperTail: FloatArray
    internal val baselineNodes: DoubleArray

    internal class Sync(var freq: Float, var step: Float, var sync: Float)

    init {
        when (submode) {
            JS8Submode.normal -> {
                NDOWNSPS = 32; NDD = 100; JZ = 62; ASTART = 0.5f; BASESUB = 40f
                AZ = (12000.0f / 1920.toFloat()) * 0.64f
            }
            JS8Submode.fast -> {
                NDOWNSPS = 20; NDD = 100; JZ = 144; ASTART = 0.2f; BASESUB = 39f
                AZ = (12000.0f / 1200.toFloat()) * 0.8f
            }
            JS8Submode.turbo -> {
                NDOWNSPS = 12; NDD = 120; JZ = 172; ASTART = 0.1f; BASESUB = 38f
                AZ = (12000.0f / 600.toFloat()) * 0.6f
            }
            JS8Submode.slow -> {
                NDOWNSPS = 32; NDD = 94; JZ = 32; ASTART = 0.5f; BASESUB = 42f
                AZ = (12000.0f / 3840.toFloat()) * 0.64f
            }
            JS8Submode.ultra -> {
                NDOWNSPS = 12; NDD = 125; JZ = 250; ASTART = 0.1f; BASESUB = 36f
                AZ = (12000.0f / 384.toFloat()) * 0.64f
            }
        }
        NSPS = submode.samplesPerSymbol
        NTXDUR = submode.period
        NMAX = NTXDUR * 12000
        NFFT1 = NSPS * NFOS
        NSTEP = NSPS / NSSY
        NHSYM = NMAX / NSTEP - 3
        NDOWN = NSPS / NDOWNSPS
        NQSYMBOL = NDOWNSPS / 4
        NDFFT1 = NSPS * NDD
        NDFFT2 = NDFFT1 / NDOWN
        MODE_NP2 = NN * NDOWNSPS
        TSTEP = NSTEP.toFloat() / 12000.0f
        JSTRT = (ASTART / TSTEP).toInt()
        DF = 12000.0f / NFFT1.toFloat()
        costas = submode.costas

        nuttal = FloatArray(NFFT1)
        csyncs = FloatArray(2 * 3 * 7 * NDOWNSPS)
        csymb = FloatArray(2 * NDOWNSPS)
        filter = FloatArray(2 * NMAX)
        cfilt = FloatArray(2 * NMAX)
        dsCx = FloatArray(2 * (NDFFT1 / 2 + 1))
        sd = FloatArray(2 * (NFFT1 / 2 + 1))
        cd0 = FloatArray(2 * NP)
        dd = FloatArray(NMAX)
        s = FloatArray(NSPS * NHSYM)
        savg = FloatArray(NSPS)
        sdIn = FloatArray(NFFT1)
        bbIn = FloatArray(NDFFT1)

        cd0Tmp = FloatArray(2 * NDFFT2)
        freqAdjust = FloatArray(2 * NDOWNSPS)
        s2 = FloatArray(NROWS * NN)
        cref = FloatArray(2 * NN * NSPS)

        fftSD = RealFFT(NFFT1)
        fftBB = RealFFT(NDFFT1)
        fftDS = FFT(NDFFT2)
        fftCS = FFT(NDOWNSPS)
        fftCF = FFT(NMAX)

        val head = FloatArray(NDD + 1)
        val tail = FloatArray(NDD + 1)
        for (i in 0..NDD) {
            val v = (0.5 * (1.0 + cos(i.toDouble() * Math.PI / NDD.toDouble()))).toFloat()
            tail[i] = v
            head[NDD - i] = v
        }
        taperHead = head
        taperTail = tail

        val nodeCount = BASELINE_DEGREE + 1
        val slice = Math.PI / (2.0 * nodeCount.toDouble())
        baselineNodes = DoubleArray(nodeCount) { i -> 0.5 * (1.0 - cos(slice * (2.0 * i.toDouble() + 1))) }

        // Nuttall window, normalised as in the reference.
        val a0 = 0.3635819
        val a1 = -0.4891775
        val a2 = 0.1365995
        val a3 = -0.0106411
        var sum = 0.0
        val win = DoubleArray(NFFT1)
        val pi = 4.0 * atan(1.0)
        for (i in 0 until NFFT1) {
            val x = i.toDouble() / NFFT1.toDouble()
            val v = (a0 + a1 * cos(2 * pi * x) + a2 * cos(4 * pi * x) + a3 * cos(6 * pi * x)).toFloat()
            win[i] = v.toDouble()
            sum += v.toDouble()
        }
        for (i in 0 until NFFT1) {
            nuttal[i] = (win[i] / sum * NFFT1.toDouble() / 300.0).toFloat()
        }

        // Costas reference waveforms.
        for (i in 0 until 7) {
            for (k in 0 until 3) {
                val dphi = TAU * costas[k][i].toFloat() / NDOWNSPS.toFloat()
                var phi = 0f
                for (j in 0 until NDOWNSPS) {
                    val idx = 2 * ((k * 7 + i) * NDOWNSPS + j)
                    csyncs[idx] = 1f * cos(phi)
                    csyncs[idx + 1] = 1f * sin(phi)
                    phi = (phi + dphi) % TAU
                }
            }
        }

        // Hann-like low-pass filter for signal subtraction, in the frequency domain.
        val fpi = pi.toFloat()
        var fsum = 0f
        val tmp = FloatArray(NFILT + 1)
        for (j in (-NFILT / 2)..(NFILT / 2)) {
            val c = cos(fpi * j.toFloat() / NFILT.toFloat())
            val v = c * c // powf(c, 2): exact square correctly rounded
            tmp[j + NFILT / 2] = v
            fsum += v
        }
        for (i in 0..NFILT) { filter[2 * i] = tmp[i] / fsum; filter[2 * i + 1] = 0f }
        // rotate first NFILT+1 elements left by NFILT/2
        val head0 = filter.copyOfRange(0, 2 * (NFILT / 2))
        val rest = filter.copyOfRange(2 * (NFILT / 2), 2 * (NFILT + 1))
        System.arraycopy(rest, 0, filter, 0, rest.size)
        System.arraycopy(head0, 0, filter, rest.size, head0.size)
        fftCF.forward(filter)
        val factor = 1.0f / NMAX.toFloat()
        for (i in 0 until 2 * NMAX) filter[i] = filter[i] * factor
    }

    // Entry point

    private data class Key(val type: Int, val data: String)

    /**
     * Decode one period of 12 kHz audio. `samples` should start at the
     * beginning of the transmit period; at most NMAX samples are used.
     * `nfqso` is the operator's selected offset; decodes near it are tried first.
     */
    fun decode(
        samples: FloatArray, nfqso: Int, nfa: Int = 0, nfb: Int = 5000,
        onDecode: (JS8RawDecode) -> Unit,
    ): Int {
        val sz = min(samples.size, NMAX)
        for (i in 0 until NMAX) dd[i] = if (i < sz) samples[i] else 0f

        val decodes = HashMap<Key, Int>()

        for (ipass in 1..3) {
            val candidates = syncjs8(nfa, nfb)
            if (candidates.isEmpty()) break

            val q = nfqso.toFloat()
            val less = { a: Sync, b: Sync ->
                val ad = abs(a.freq - q)
                val bd = abs(b.freq - q)
                if (ad < 10 && bd >= 10) true
                else if (bd < 10 && ad >= 10) false
                else if (ad != bd) ad < bd
                else a.freq < b.freq
            }
            candidates.sortWith { a, b -> if (less(a, b)) -1 else if (less(b, a)) 1 else 0 }

            computeBasebandFFT()

            val subtract = ipass < 3
            var improved = false

            for (cand in candidates) {
                io.f1 = cand.freq
                io.xdt = cand.step
                io.nharderrors = -1
                io.xsnr = 0f
                val res = js8dec(subtract, io) ?: continue
                val (type, data) = res
                val snr = roundedInt(io.xsnr)
                val key = Key(type, data)
                val prev = decodes[key]
                if (prev != null) {
                    if (prev < snr) {
                        decodes[key] = snr
                        improved = true
                    } else {
                        continue
                    }
                } else {
                    decodes[key] = snr
                    improved = true
                }
                onDecode(
                    JS8RawDecode(
                        frame = data, type = type, snr = snr, dt = io.xdt - ASTART, frequency = io.f1,
                        quality = 1 - io.nharderrors.toFloat() / 60, submode = submode,
                    )
                )
            }
            if (!improved) break
        }
        return decodes.size
    }

    /** Swift's `inout` parameters of js8dec. */
    internal class DecIO(var f1: Float = 0f, var xdt: Float = 0f, var nharderrors: Int = -1, var xsnr: Float = 0f)

    private val io = DecIO()

    // Spectra and sync search

    internal fun syncjs8(nfa: Int, nfb: Int): ArrayList<Sync> {
        val nfaIn = nfa
        val nfbIn = nfb
        val savg = savg
        val s = s
        for (i in 0 until NSPS) savg[i] = 0f

        val ddb = dd
        val inb = sdIn
        val sdb = sd
        val nut = nuttal
        for (j in 0 until NHSYM) {
            val ia = j * NSTEP
            val ib = ia + NFFT1
            if (ib > NMAX) break
            for (i in 0 until NFFT1) inb[i] = ddb[ia + i] * nut[i]
            fftSD.forward(inb, sdb)
            for (i in 0 until NSPS) {
                val re = sdb[2 * i]; val im = sdb[2 * i + 1]
                val p = re * re + im * im
                s[i * NHSYM + j] = p
                savg[i] += p
            }
        }

        var nfa2 = nfaIn
        var nfb2 = nfbIn
        val nwin = nfb2 - nfa2
        if (nfa2 < 100) {
            nfa2 = 100
            if (nwin < 100) nfb2 = nfa2 + nwin
        }
        if (nfb2 > 4910) {
            nfb2 = 4910
            if (nwin < 100) nfa2 = nfb2 - nwin
        }
        val ia = max(0, roundedInt(nfa2.toFloat() / DF))
        val ib = roundedInt(nfb2.toFloat() / DF)

        baselinejs8(ia, ib)

        val entries = ArrayList<Sync>(max(0, ib - ia + 1))
        val costas = costas
        val NHSYM = NHSYM
        val JZ = JZ
        val JSTRT = JSTRT
        for (i in ia..ib) {
            var maxValue = Float.NEGATIVE_INFINITY
            var maxIndex = -JZ
            for (j in -JZ..JZ) {
                var t00 = 0f; var t01 = 0f; var t02 = 0f
                var t10 = 0f; var t11 = 0f; var t12 = 0f
                for (p in 0 until 3) {
                    var a = 0f
                    var b = 0f
                    val cp = costas[p]
                    for (n in 0 until 7) {
                        val offset = j + JSTRT + NSSY * n + p * 36 * NSSY
                        if (offset >= 0 && offset < NHSYM) {
                            a += s[(i + NFOS * cp[n]) * NHSYM + offset]
                            for (freq in 0 until 7) {
                                b += s[(i + NFOS * freq) * NHSYM + offset]
                            }
                        }
                    }
                    when (p) {
                        0 -> { t00 = a; t10 = b }
                        1 -> { t01 = a; t11 = b }
                        else -> { t02 = a; t12 = b }
                    }
                }
                val s012 = cs(t00 + t01 + t02, t10 + t11 + t12)
                val s01 = cs(t00 + t01, t10 + t11)
                val s12 = cs(t01 + t02, t11 + t12)
                val v = smax(s012, smax(s01, s12))
                if (v > maxValue) {
                    maxValue = v
                    maxIndex = j
                }
            }
            entries.add(Sync(DF * i.toFloat(), TSTEP * (maxIndex.toFloat() + 0.5f), maxValue))
        }

        if (entries.isEmpty()) return ArrayList()

        // Normalise to the 40th percentile.
        val sortedSyncs = FloatArray(entries.size) { val v = entries[it].sync; if (v.isNaN()) Float.NEGATIVE_INFINITY else v }
        sortedSyncs.sort()
        val norm = sortedSyncs[sortedSyncs.size * 4 / 10]
        for (e in entries) e.sync /= norm

        // Candidate extraction in decreasing sync order, removing neighbours.
        val order = (0 until entries.size).sortedWith { a, b ->
            val sa = entries[a].sync
            val sbv = entries[b].sync
            val va = if (sa.isNaN()) Float.NEGATIVE_INFINITY else sa
            val vb = if (sbv.isNaN()) Float.NEGATIVE_INFINITY else sbv
            if (va != vb) { if (va > vb) -1 else 1 } else a.compareTo(b)
        }
        val removed = BooleanArray(entries.size)
        val candidates = ArrayList<Sync>()
        for (idx in order) {
            if (candidates.size >= NMAXCAND) break
            if (removed[idx]) continue
            val e = entries[idx]
            if (e.sync < ASYNCMIN || e.sync.isNaN()) break
            candidates.add(Sync(e.freq, e.step, e.sync))
            val lo = e.freq - AZ
            val hi = e.freq + AZ
            for (k in entries.indices) {
                if (!removed[k] && entries[k].freq >= lo && entries[k].freq <= hi) removed[k] = true
            }
        }
        return candidates
    }

    private fun cs(tx: Float, t0v: Float): Float = tx / ((t0v - tx) / 6.0f)

    internal fun baselinejs8(ia: Int, ib: Int) {
        val bmin = roundedInt(BASELINE_MIN.toFloat() / DF)
        val bmax = roundedInt(BASELINE_MAX.toFloat() / DF)
        val size = bmax - bmin + 1
        val nodeCount = baselineNodes.size
        val arm = size / (2 * nodeCount)

        for (i in bmin until (bmin + size)) savg[i] = 10 * log10(savg[i])

        val px = DoubleArray(nodeCount)
        val py = DoubleArray(nodeCount)
        for (i in 0 until nodeCount) {
            val node = size.toDouble() * baselineNodes[i]
            val base = roundedInt(node)
            val lo = min(max(base - arm, 0), size)
            val hi = min(max(base + arm, 0), size)
            val span = savg.copyOfRange(bmin + lo, bmin + hi)
            val n = span.size * BASELINE_SAMPLE / 100
            span.sort()
            px[i] = node
            py[i] = span[n].toDouble()
        }

        val c = solveVandermonde(px, py)

        val last = (size - 1).toFloat()
        for (i in 0 until NSPS) savg[i] = 0f
        for (i in ia..ib) {
            val x = ((i - ia).toFloat() * last / (ib - ia).toFloat()).toDouble()
            var baseline = 0.0
            var exponent = 1.0
            var k = 0
            while (k < c.size) {
                baseline += (c[k] + c[k + 1] * x) * exponent
                exponent *= x * x
                k += 2
            }
            savg[i] = baseline.toFloat() + 0.65f
        }
    }

    // Downsampling

    internal fun computeBasebandFFT() {
        for (i in 0 until NDFFT1) bbIn[i] = if (i < NMAX) dd[i] else 0f
        fftBB.forward(bbIn, dsCx)
    }

    internal fun js8Downsample(f0: Float) {
        val df = 12000.0f / NDFFT1.toFloat()
        val baud = 12000.0f / NSPS.toFloat()
        val ft = f0 + 8.5f * baud
        val fb = f0 - 1.5f * baud
        val i0 = roundedInt(f0 / df)
        val it = min(roundedInt(ft / df), NDFFT1 / 2)
        val ib = max(0, roundedInt(fb / df))
        val nddSize = NDD + 1
        val rangeSize = it - ib + 1
        val cd0 = cd0

        java.util.Arrays.fill(cd0, 0, 2 * NDFFT2, 0f)
        System.arraycopy(dsCx, 2 * ib, cd0, 0, 2 * rangeSize)

        for (i in 0 until nddSize) {
            val t = taperHead[i]
            cd0[2 * i] = cd0[2 * i] * t
            cd0[2 * i + 1] = cd0[2 * i + 1] * t
        }
        for (i in 0 until nddSize) {
            val t = taperTail[i]
            val k = 2 * (rangeSize - nddSize + i)
            cd0[k] = cd0[k] * t
            cd0[k + 1] = cd0[k + 1] * t
        }

        // rotate left by (i0 - ib) within [0, NDFFT2)
        val shift = i0 - ib
        if (shift > 0) {
            val tmp = cd0Tmp
            System.arraycopy(cd0, 0, tmp, 0, 2 * NDFFT2)
            for (i in 0 until NDFFT2) {
                val src = 2 * ((i + shift) % NDFFT2)
                cd0[2 * i] = tmp[src]
                cd0[2 * i + 1] = tmp[src + 1]
            }
        }

        fftDS.inverse(cd0)

        val factor = 1.0f / sqrt(NDFFT1.toFloat() * NDFFT2.toFloat())
        for (i in 0 until 2 * NP) cd0[i] = cd0[i] * factor
    }

    internal fun syncjs8d(i0: Int, delf: Float): Float {
        val baseDphi = TAU * (1.0f / (12000.0f / NDOWN.toFloat()))
        val fa = freqAdjust
        if (delf != 0f) {
            val dphi = baseDphi * delf
            var phi = 0f
            for (i in 0 until NDOWNSPS) {
                fa[2 * i] = 1f * cos(phi)
                fa[2 * i + 1] = 1f * sin(phi)
                phi = (phi + dphi) % TAU
                if (phi < 0) phi += TAU
            }
        } else {
            for (i in 0 until NDOWNSPS) { fa[2 * i] = 1f; fa[2 * i + 1] = 0f }
        }
        val cd0 = cd0
        val csyncs = csyncs
        var sync = 0f
        for (i in 0 until 3) {
            for (j in 0 until 7) {
                val offset = 36 * i * NDOWNSPS + i0 + j * NDOWNSPS
                if (offset >= 0 && offset + NDOWNSPS <= MODE_NP2) {
                    var accr = 0f
                    var acci = 0f
                    val base = (i * 7 + j) * NDOWNSPS
                    for (k in 0 until NDOWNSPS) {
                        val far = fa[2 * k]; val fai = fa[2 * k + 1]
                        val csr = csyncs[2 * (base + k)]; val csi = csyncs[2 * (base + k) + 1]
                        // (freqAdjust * csync).conj
                        val pr = far * csr - fai * csi
                        val pi = -(far * csi + fai * csr)
                        val cr = cd0[2 * (offset + k)]; val ci = cd0[2 * (offset + k) + 1]
                        accr += cr * pr - ci * pi
                        acci += cr * pi + ci * pr
                    }
                    sync += accr * accr + acci * acci
                }
            }
        }
        return sync
    }

    // Demodulation and decoding

    internal fun js8dec(lsubtract: Boolean, io: DecIO): Pair<Int, String>? {
        val FR = 12000.0f / NFFT1.toFloat()
        val FS2 = 12000.0f / NDOWN.toFloat()
        val DT2 = 1.0f / FS2

        val index = roundedInt(io.f1 / FR)
        val scaled = 0.1f * (savg[min(max(index, 0), NSPS - 1)] - BASESUB)
        val xbase = Math.pow(10.0, scaled.toDouble()).toFloat()

        var delfbest = 0f
        var ibest = 0

        js8Downsample(io.f1)

        var i0 = roundedInt((io.xdt + ASTART) * FS2)
        var smaxv = 0f
        for (idt in (i0 - NQSYMBOL)..(i0 + NQSYMBOL)) {
            val sy = syncjs8d(idt, 0f)
            if (sy > smaxv) {
                smaxv = sy
                ibest = idt
            }
        }

        val xdt2 = ibest.toFloat() * DT2
        i0 = roundedInt(xdt2 * FS2)
        smaxv = 0f
        for (ifr in -NFSRCH..NFSRCH) {
            val delf = ifr.toFloat() * 0.5f
            val sy = syncjs8d(i0, delf)
            if (sy > smaxv) {
                smaxv = sy
                delfbest = delf
            }
        }

        val cd0 = cd0
        val dphi = -delfbest * ((2 * SWIFT_FLOAT_PI) / FS2)
        val wsr = 1f * cos(dphi)
        val wsi = 1f * sin(dphi)
        var wr = 1f
        var wi = 0f
        for (i in 0 until NP2) {
            val nwr = wr * wsr - wi * wsi
            val nwi = wr * wsi + wi * wsr
            wr = nwr; wi = nwi
            val cr = cd0[2 * i]; val ci = cd0[2 * i + 1]
            cd0[2 * i] = cr * wr - ci * wi
            cd0[2 * i + 1] = cr * wi + ci * wr
        }

        io.xdt = xdt2
        io.f1 += delfbest

        val sync = syncjs8d(i0, 0f)

        val s2 = s2
        val csymb = csymb
        for (k in 0 until NN) {
            val i1 = ibest + k * NDOWNSPS
            java.util.Arrays.fill(csymb, 0f)
            if (i1 >= 0 && i1 + NDOWNSPS <= NP2) {
                System.arraycopy(cd0, 2 * i1, csymb, 0, 2 * NDOWNSPS)
            }
            fftCS.forward(csymb)
            for (i in 0 until NROWS) {
                val re = csymb[2 * i]; val im = csymb[2 * i + 1]
                s2[i * NN + k] = sqrt(re * re + im * im) / 1000
            }
        }

        var nsync = 0
        for (c in 0 until 3) {
            val offset = c * 36
            for (column in 0 until 7) {
                var maxRow = 0
                var maxVal = s2[offset + column]
                for (r in 1 until NROWS) {
                    if (s2[r * NN + offset + column] > maxVal) {
                        maxVal = s2[r * NN + offset + column]
                        maxRow = r
                    }
                }
                if (costas[c][column] == maxRow) nsync += 1
            }
        }
        if (nsync <= 6) return null

        val llr0 = llr0
        val llr1 = llr1
        val ps = ps
        for (j in 0 until ND) {
            val col = if (j < 29) 7 + j else 43 + (j - 29)
            for (i in 0 until 8) ps[i] = s2[i * NN + col]
            llr0[3 * j] = smax4(ps[4], ps[5], ps[6], ps[7]) - smax4(ps[0], ps[1], ps[2], ps[3])
            llr0[3 * j + 1] = smax4(ps[2], ps[3], ps[6], ps[7]) - smax4(ps[0], ps[1], ps[4], ps[5])
            llr0[3 * j + 2] = smax4(ps[1], ps[3], ps[5], ps[7]) - smax4(ps[0], ps[2], ps[4], ps[6])
            for (i in 0 until 8) ps[i] = ln(ps[i] + 1e-32f)
            llr1[3 * j] = smax4(ps[4], ps[5], ps[6], ps[7]) - smax4(ps[0], ps[1], ps[2], ps[3])
            llr1[3 * j + 1] = smax4(ps[2], ps[3], ps[6], ps[7]) - smax4(ps[0], ps[1], ps[4], ps[5])
            llr1[3 * j + 2] = smax4(ps[1], ps[3], ps[5], ps[7]) - smax4(ps[0], ps[2], ps[4], ps[6])
        }

        normalize(llr0)
        normalize(llr1)

        val decoded = decodedBits
        val cw = cwBits
        java.util.Arrays.fill(decoded, 0)
        java.util.Arrays.fill(cw, 0)

        for (ipass in 1..4) {
            if (ipass == 3) { for (i in 0 until 24) llr0[i] = 0f }
            else if (ipass == 4) { for (i in 24 until 48) llr0[i] = 0f }
            val llr = if (ipass == 2) llr1 else llr0

            io.nharderrors = JS8Codec.bpDecode(llr, decoded, cw)
            io.xsnr = -99f

            if (cw.all { it.toInt() == 0 }) continue

            val nharderrors = io.nharderrors
            if (nharderrors >= 0 && nharderrors < 60 &&
                !(sync < 2.0f && nharderrors > 35) &&
                !(ipass > 2 && nharderrors > 39) &&
                !(ipass == 4 && nharderrors > 30)
            ) {
                if (JS8Codec.checkCRC12(decoded)) {
                    val message = JS8Codec.extractMessage(decoded)
                    val i3bit = (decoded[72].toInt() shl 2) or (decoded[73].toInt() shl 1) or decoded[74].toInt()
                    val itone = JS8Codec.encode(i3bit, message, costas) ?: return null

                    if (lsubtract) subtractjs8(genjs8refsig(itone, io.f1), xdt2)

                    var xsig = 0f
                    for (i in itone.indices) {
                        val v = s2[itone[i] * NN + i]
                        xsig += v * v // powf(v, 2)
                    }

                    io.xsnr = smax(10 * log10(smax(xsig / xbase - 1, 1.259e-10f)) - 32, -60f)
                    return Pair(i3bit, message)
                }
            } else {
                io.nharderrors = -1
            }
        }
        return null
    }

    private fun normalize(llr: FloatArray) {
        var sum = 0f
        var sumsq = 0f
        for (v in llr) { sum += v; sumsq += v * v }
        val av = sum / llr.size.toFloat()
        val av2 = sumsq / llr.size.toFloat()
        val variance = av2 - av * av
        val sig = sqrt(if (variance > 0) variance else av2)
        for (i in llr.indices) llr[i] = (llr[i] / sig) * 2.83f
    }

    /** Returns the shared reference buffer (NN * NSPS interleaved complex samples). */
    internal fun genjs8refsig(itone: IntArray, f0: Float): FloatArray {
        val bfpi = TAU * f0 * (1.0f / 12000.0f)
        var phi = 0f
        val cref = cref
        var idx = 0
        for (i in 0 until NN) {
            val dphi = bfpi + TAU * itone[i].toFloat() / NSPS.toFloat()
            for (n in 0 until NSPS) {
                cref[idx] = 1f * cos(phi)
                cref[idx + 1] = 1f * sin(phi)
                idx += 2
                phi = (phi + dphi) % TAU
            }
        }
        return cref
    }

    internal fun subtractjs8(cref: FloatArray, dt: Float) {
        val crefCount = cref.size / 2
        val nstart = (dt * 12000).toInt()
        val crefStart = if (nstart < 0) -nstart else 0
        val ddStart = if (nstart > 0) nstart else 0
        val size = min(crefCount - crefStart, dd.size - ddStart)
        if (size <= 0) return

        val cfilt = cfilt
        val dd = dd
        for (i in 0 until size) {
            val d = dd[ddStart + i]
            val k = 2 * (crefStart + i)
            cfilt[2 * i] = cref[k] * d
            cfilt[2 * i + 1] = -cref[k + 1] * d
        }
        java.util.Arrays.fill(cfilt, 2 * size, 2 * NMAX, 0f)

        fftCF.forward(cfilt)
        val filter = filter
        for (i in 0 until NMAX) {
            val cr = cfilt[2 * i]; val ci = cfilt[2 * i + 1]
            val fr = filter[2 * i]; val fi = filter[2 * i + 1]
            cfilt[2 * i] = cr * fr - ci * fi
            cfilt[2 * i + 1] = cr * fi + ci * fr
        }
        fftCF.inverse(cfilt)

        for (i in 0 until size) {
            val k = 2 * (crefStart + i)
            dd[ddStart + i] -= 2 * (cfilt[2 * i] * cref[k] - cfilt[2 * i + 1] * cref[k + 1])
        }
    }
}
