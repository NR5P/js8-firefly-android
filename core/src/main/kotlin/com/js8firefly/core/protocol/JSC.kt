package com.js8firefly.core

import java.util.concurrent.ConcurrentHashMap

/**
 * JS8Call's dictionary compressor ("JSC"): (s,c)-dense coding over a
 * 262,144-entry word list. The tables are exported verbatim from JS8Call into
 * `jsc.bin` (a classpath resource) so that compression and decompression match
 * the desktop app. Entries are kept in parallel int arrays rather than objects.
 */
class JSC private constructor() {
    companion object {
        val shared: JSC by lazy { JSC() }

        internal const val size = 262144

        internal fun latin1(s: String): ByteArray =
            ByteArray(s.length) { val u = s[it].code; if (u < 256) u.toByte() else '?'.code.toByte() }

        internal fun codeword(index: Int, separate: Boolean, bytesize: Int, s: Int, c: Int): List<Boolean> {
            val out = ArrayList<List<Boolean>>()
            val v = ((index % s) shl 1) + (if (separate) 1 else 0)
            out.add(0, Bits.intToBits(v.toLong(), expected = bytesize + 1))
            var x = index / s
            while (x > 0) {
                x -= 1
                out.add(0, Bits.intToBits(((x % c) + s).toLong(), expected = bytesize))
                x /= c
            }
            return out.flatten()
        }
    }

    /** One table: offset into `strings`, strlen, declared size, index. */
    internal class Table(n: Int) {
        val offset = IntArray(n)
        val length = IntArray(n)
        val size = IntArray(n)
        val index = IntArray(n)
        val count get() = offset.size
    }

    internal val strings: ByteArray
    internal val map: Table
    internal val list: Table
    internal val prefix: Table
    private val cache = ConcurrentHashMap<String, Int>()

    init {
        val bytes = JSC::class.java.getResourceAsStream("/jsc.bin")?.use { it.readBytes() }
            ?: error("jsc.bin resource missing")
        var pos = 4
        fun u32(): Int {
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun u8(): Int = bytes[pos++].toInt() and 0xFF

        val mapCount = u32()
        val listCount = u32()
        val prefixCount = u32()
        val str = ByteArray(bytes.size)
        var strLen = 0
        fun read(t: Table, i: Int, prefixed: Boolean) {
            val n = u8()
            t.size[i] = if (prefixed) u32() else u8()
            t.index[i] = u32()
            t.offset[i] = strLen
            t.length[i] = n
            System.arraycopy(bytes, pos, str, strLen, n)
            strLen += n
            pos += n
        }
        map = Table(mapCount)
        for (i in 0 until mapCount) read(map, i, false)
        list = Table(listCount)
        for (i in 0 until listCount) read(list, i, false)
        prefix = Table(prefixCount)
        for (i in 0 until prefixCount) read(prefix, i, true)
        strings = str.copyOf(strLen)
    }

    /** Latin-1 decoding of a map entry string. */
    internal fun mapString(j: Int): String = String(strings, map.offset[j], map.length[j], Charsets.ISO_8859_1)

    /** JSC::lookup — prefix match followed by strncmp over the list. */
    internal fun lookup(w: String): Int? {
        cache[w]?.let { return it }
        val result = lookup(latin1(w)) ?: return null
        cache[w] = result
        return result
    }

    private fun lookup(b: ByteArray): Int? {
        val b0: Byte = if (b.isNotEmpty()) b[0] else 0
        var index = 0
        var count = 0
        var found = false
        for (p in 0 until prefix.count) {
            val first: Byte = if (prefix.length[p] > 0) strings[prefix.offset[p]] else 0
            if (b0 != first) continue
            if (prefix.size[p] == 1) return list.index[prefix.index[p]]
            index = prefix.index[p]
            count = prefix.size[p]
            found = true
            break
        }
        if (!found) return null
        for (i in index until index + count) {
            if (strncmpEqual(b, i)) return list.index[i]
        }
        return null
    }

    private fun strncmpEqual(b: ByteArray, e: Int): Boolean {
        val len = list.size[e]
        val off = list.offset[e]
        val elen = list.length[e]
        for (k in 0 until len) {
            val c1: Byte = if (k < b.size) b[k] else 0
            val c2: Byte = if (k < elen) strings[off + k] else 0
            if (c1 != c2) return false
            if (c1 == 0.toByte()) return true
        }
        return true
    }

    fun exists(w: String): Boolean {
        val idx = lookup(w) ?: return false
        return map.size[idx] == w.u16Count
    }

    /** Returns (codeword bits, characters consumed) pairs. */
    fun compress(text: String): List<Pair<List<Boolean>, Int>> {
        val out = ArrayList<Pair<List<Boolean>, Int>>()
        val b = 4
        val s = 7
        val c = (1 shl 4) - s
        val words = text.split(" ")
        for ((i, w0) in words.withIndex()) {
            var w = w0
            val isLastWord = i == words.size - 1
            var isSpaceCharacter = false
            if (w.isEmpty() && !isLastWord) {
                w = " "
                isSpaceCharacter = true
            }
            while (w.isNotEmpty()) {
                val index = lookup(w) ?: break
                val tsize = map.size[index]
                w = w.qMid(tsize)
                val isLast = w.isEmpty()
                val shouldAppendSpace = isLast && !isSpaceCharacter && !isLastWord
                out.add(
                    Pair(
                        codeword(index, shouldAppendSpace, b, s, c),
                        tsize + (if (shouldAppendSpace) 1 else 0),
                    ),
                )
            }
        }
        return out
    }

    fun decompress(bitvec: List<Boolean>): String {
        val s = 7
        val c = 16 - s
        val base = IntArray(8)
        base[1] = s
        base[2] = base[1] + s * c
        base[3] = base[2] + s * c * c
        base[4] = base[3] + s * c * c * c
        base[5] = base[4] + s * c * c * c * c
        base[6] = base[5] + s * c * c * c * c * c
        base[7] = base[6] + s * c * c * c * c * c * c

        val bytes = ArrayList<Int>()
        val separators = ArrayDeque<Int>()
        var i = 0
        val count = bitvec.size
        while (i < count) {
            if (i + 4 > count) break
            val byte = Bits.bitsToInt(bitvec, i, 4).toInt()
            bytes.add(byte)
            i += 4
            if (byte < s) {
                if (count - i > 0 && bitvec[i]) separators.addLast(bytes.size - 1)
                i += 1
            }
        }

        val out = StringBuilder()
        var start = 0
        while (start < bytes.size) {
            var k = 0
            var j = 0L
            while (start + k < bytes.size && bytes[start + k] >= s) {
                j = j * c + (bytes[start + k] - s)
                k += 1
            }
            if (j >= size) break
            if (start + k >= bytes.size) break
            j = j * s + bytes[start + k] + base[k]
            if (j >= size) break
            out.append(mapString(j.toInt()))
            if (separators.isNotEmpty() && separators.first() == start + k) {
                out.append(" ")
                separators.removeFirst()
            }
            start += k + 1
        }
        return out.toString()
    }

    /** Single-character entries in the prefix table (extended charset). */
    val extendedChars: String by lazy {
        val sb = StringBuilder()
        for (p in 0 until prefix.count) {
            if (prefix.size[p] == 1) sb.append((strings[prefix.offset[p]].toInt() and 0xFF).toChar())
        }
        sb.toString()
    }
}

internal object Bits {
    fun intToBits(value: Long, expected: Int = 0): List<Boolean> {
        val bits = ArrayList<Boolean>(64)
        var v = value
        while (v != 0L) {
            bits.add(v and 1L == 1L)
            v = v ushr 1
        }
        if (expected > 0) {
            while (bits.size < expected) bits.add(false)
        }
        bits.reverse()
        return bits
    }

    fun bitsToInt(bits: List<Boolean>): Long {
        var v = 0L
        for (b in bits) v = (v shl 1) + (if (b) 1 else 0)
        return v
    }

    fun bitsToInt(bits: List<Boolean>, start: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            val b = if (start + i < bits.size) bits[start + i] else false
            v = (v shl 1) + (if (b) 1 else 0)
        }
        return v
    }

    /** QVector::mid semantics. */
    fun mid(bits: List<Boolean>, pos: Int, len: Int = -1): List<Boolean> {
        val size = bits.size
        if (pos > size) return emptyList()
        var p = pos
        var l = len
        if (p < 0) {
            if (l >= 0) l += p
            p = 0
        }
        if (l < 0 || l > size - p) l = size - p
        if (l <= 0) return emptyList()
        return ArrayList(bits.subList(p, p + l))
    }
}
