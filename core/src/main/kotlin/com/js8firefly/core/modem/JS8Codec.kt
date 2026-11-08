// Port of JS8Core/Sources/JS8Core/Modem/JS8Codec.swift.
// Conventions: Swift [UInt8] -> ByteArray (read with `and 0xFF`), [Int8] bit
// arrays -> ByteArray holding 0/1, tones [Int] -> IntArray, costas [[Int]] ->
// Array<IntArray>, alphabet [Character] -> CharArray, UInt16 CRC -> Int.
package com.js8firefly.core

import kotlin.math.atanh
import kotlin.math.tanh

/**
 * JS8 channel coding: the 12-character frame alphabet, CRC-12, the LDPC
 * (174,87) generator and belief-propagation decoder, and tone mapping.
 * A faithful port of the encoder and BP decoder in JS8Call's JS8.cpp.
 */
object JS8Codec {
    internal const val N = 174
    internal const val K = 87
    internal const val M = N - K

    /** Frame alphabet; each character carries 6 bits. */
    val alphabet: CharArray = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-+".toCharArray()
    internal val alphabetBytes: ByteArray = ByteArray(alphabet.size) { alphabet[it].code.toByte() }

    /** 0xFF marks bytes outside the alphabet. */
    internal val alphabetWord: IntArray = IntArray(256) { 0xFF }.also { words ->
        for ((i, b) in alphabetBytes.withIndex()) words[b.toInt() and 0xFF] = i
    }

    fun isValidFrame(frame: String): Boolean {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        return bytes.size == 12 && bytes.all { alphabetWord[it.toInt() and 0xFF] != 0xFF }
    }

    /** boost::augmented_crc<12, 0xc06>(data) ^ 42 */
    internal fun crc12(bytes: ByteArray): Int {
        var rem = 0
        for (b in bytes) {
            val byte = b.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val top = (rem ushr 11) and 1
                rem = ((rem shl 1) or ((byte ushr bit) and 1)) and 0xFFF
                if (top != 0) rem = rem xor 0xC06
            }
        }
        return (rem and 0xFFFF) xor 42
    }

    internal fun checkCRC12(decoded: ByteArray): Boolean {
        val bits = ByteArray(11)
        for (i in 0 until K) {
            if (decoded[i].toInt() != 0) {
                bits[i / 8] = (bits[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        val crc = ((bits[9].toInt() and 0x1F) shl 7) or ((bits[10].toInt() and 0xFF) ushr 1)
        bits[9] = (bits[9].toInt() and 0xE0).toByte()
        bits[10] = 0
        return crc == crc12(bits)
    }

    internal fun extractMessage(decoded: ByteArray): String {
        val s = StringBuilder(12)
        for (i in 0 until 12) {
            var w = 0
            for (b in 0 until 6) w = (w shl 1) or decoded[i * 6 + b].toInt()
            s.append(alphabet[w])
        }
        return s.toString()
    }

    /** Parity matrix: parity[row][col] for 87x87. */
    internal val parity: Array<BooleanArray> by lazy {
        Array(LDPCTables.parityHex.size) { r ->
            val hex = LDPCTables.parityHex[r]
            val row = BooleanArray(87)
            var count = 0
            for (c in hex) {
                val v = Character.digit(c, 16)
                for (mask in intArrayOf(8, 4, 2, 1)) {
                    if (count < 87) {
                        row[count] = (v and mask) != 0
                        count += 1
                    }
                }
            }
            row
        }
    }

    /**
     * Encode a 12-character frame with a 3-bit frame type into 79 tones.
     * Returns null if the frame contains characters outside the alphabet.
     */
    fun encode(type: Int, frame: String, costas: Array<IntArray>): IntArray? {
        val chars = frame.toByteArray(Charsets.UTF_8)
        if (chars.size < 12) return null
        val words = IntArray(12)
        for (i in 0 until 12) {
            val w = alphabetWord[chars[i].toInt() and 0xFF]
            if (w == 0xFF) return null
            words[i] = w
        }

        val bytes = IntArray(11) // UInt8 values 0...255
        var j = 0
        for (i in 0 until 12 step 4) {
            val v = (words[i] shl 18) or (words[i + 1] shl 12) or (words[i + 2] shl 6) or words[i + 3]
            bytes[j] = (v ushr 16) and 0xFF
            bytes[j + 1] = (v ushr 8) and 0xFF
            bytes[j + 2] = v and 0xFF
            j += 3
        }
        bytes[9] = ((type and 0b111) shl 5) and 0xFF
        val crc = crc12(ByteArray(11) { bytes[it].toByte() })
        bytes[9] = bytes[9] or ((crc ushr 7) and 0x1F)
        bytes[10] = ((crc and 0x7F) shl 1) and 0xFF

        val tones = IntArray(79)
        for ((k, array) in costas.withIndex()) {
            for (c in 0 until 7) tones[k * 36 + c] = array[c]
        }

        fun bit(i: Int): Boolean = (bytes[i / 8] and (0x80 ushr (i % 8))) != 0

        var parityIndex = 7
        var outputIndex = 43
        var outputBits = 0
        var parityWord = 0
        var outputWord = 0
        val parity = parity
        for (i in 0 until 87) {
            var parityBits = 0
            val row = parity[i]
            for (jj in 0 until 87) if (row[jj] && bit(jj)) parityBits += 1
            parityWord = (parityWord shl 1) or (parityBits and 1)
            outputWord = (outputWord shl 1) or (if (bit(i)) 1 else 0)
            outputBits += 1
            if (outputBits == 3) {
                tones[parityIndex] = parityWord
                tones[outputIndex] = outputWord
                parityIndex += 1
                outputIndex += 1
                parityWord = 0
                outputWord = 0
                outputBits = 0
            }
        }
        return tones
    }

    // Belief propagation

    internal const val BP_MAX_ROWS = 7
    internal const val BP_MAX_CHECKS = 3
    internal const val BP_MAX_ITERATIONS = 30

    internal val mn: IntArray = IntArray(LDPCTables.Mn.size * 3) { LDPCTables.Mn[it / 3][it % 3] }
    internal val nmCount: IntArray = IntArray(LDPCTables.Nm.size) { LDPCTables.Nm[it].first }
    internal val nm: IntArray = LDPCTables.Nm.flatMap { it.second.asList() }.toIntArray()

    /** Returns the number of hard errors, or -1 on failure. `decoded` holds K bits, `cw` N bits (0/1). */
    internal fun bpDecode(llr: FloatArray, decoded: ByteArray, cw: ByteArray): Int {
        val mn = mn
        val nm = nm
        val nmCount = nmCount
        val tov = FloatArray(N * BP_MAX_CHECKS)
        val toc = FloatArray(M * BP_MAX_ROWS)
        val tanhtoc = FloatArray(M * BP_MAX_ROWS)
        val zn = FloatArray(N)

        for (i in 0 until M) {
            for (j in 0 until nmCount[i]) {
                toc[i * BP_MAX_ROWS + j] = llr[nm[i * BP_MAX_ROWS + j]]
            }
        }

        var ncnt = 0
        var nclast = 0

        for (iter in 0..BP_MAX_ITERATIONS) {
            for (i in 0 until N) {
                zn[i] = llr[i] + (tov[i * 3] + tov[i * 3 + 1] + tov[i * 3 + 2])
            }
            for (i in 0 until N) cw[i] = if (zn[i] > 0) 1 else 0

            var ncheck = 0
            for (i in 0 until M) {
                var synd = 0
                for (j in 0 until nmCount[i]) synd += cw[nm[i * BP_MAX_ROWS + j]].toInt()
                if (synd % 2 != 0) ncheck += 1
            }

            if (ncheck == 0) {
                for (i in 0 until K) decoded[i] = cw[M + i]
                var nerr = 0
                for (i in 0 until N) if ((2 * cw[i].toInt() - 1).toFloat() * llr[i] < 0) nerr += 1
                return nerr
            }

            if (iter > 0) {
                val nd = ncheck - nclast
                ncnt = if (nd < 0) 0 else ncnt + 1
                if (ncnt >= 5 && iter >= 10 && ncheck > 15) return -1
            }
            nclast = ncheck

            for (i in 0 until M) {
                for (j in 0 until nmCount[i]) {
                    val ibj = nm[i * BP_MAX_ROWS + j]
                    var v = zn[ibj]
                    for (k in 0 until BP_MAX_CHECKS) {
                        if (mn[ibj * 3 + k] == i) v -= tov[ibj * 3 + k]
                    }
                    toc[i * BP_MAX_ROWS + j] = v
                }
            }

            for (i in 0 until M * BP_MAX_ROWS) {
                tanhtoc[i] = tanh(-toc[i] / 2)
            }

            for (i in 0 until N) {
                for (j in 0 until BP_MAX_CHECKS) {
                    val ichk = mn[i * 3 + j]
                    if (ichk >= 0) {
                        var tmn = 1f
                        for (k in 0 until nmCount[ichk]) {
                            if (nm[ichk * BP_MAX_ROWS + k] != i) tmn *= tanhtoc[ichk * BP_MAX_ROWS + k]
                        }
                        tov[i * 3 + j] = 2 * atanh(-tmn)
                    }
                }
            }
        }
        return -1
    }
}
