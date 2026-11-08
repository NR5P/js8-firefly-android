package com.js8firefly.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port of JS8Call's Varicode: message framing (heartbeat, compound, directed
 * and data frames), callsign/grid packing, Huffman and JSC text coding, and
 * the buildMessageFrames() splitter. Behaviour mirrors Varicode.cpp.
 *
 * Swift's fixed-width unsigned values (UInt8/UInt16/UInt32) are carried in
 * Int and UInt64 in Long (shifted logically where the sign bit can be set).
 */
object Varicode {
    // MARK: Transmission and frame types

    /** OptionSet of transmission flags. */
    data class TransmissionType(val rawValue: Int) {
        companion object {
            val normal = TransmissionType(0) // 000
            val first = TransmissionType(1) // 001
            val last = TransmissionType(2) // 010
            val data = TransmissionType(4) // 100
        }
    }

    enum class FrameType(val rawValue: Int) {
        heartbeat(0),
        compound(1),
        compoundDirected(2),
        directed(3),
        `data`(4),
        dataCompressed(6),
        unknown(255);

        companion object {
            fun fromRaw(rawValue: Int): FrameType? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    // MARK: Tables

    internal const val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
    internal const val alphabetChars = alphabet
    internal const val nalphabet = 41
    internal const val alphabet72Chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-+/?."
    internal const val alphanumericChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ /@"

    internal const val gridPattern = """((?<grid>[A-X]{2}[0-9]{2}(?:[A-X]{2}(?:[0-9]{2})?)*)+)"""
    internal const val baseCallsignPattern = """((?<callsign>\b(?<base>([0-9A-Z])?([0-9A-Z])([0-9])([A-Z])?([A-Z])?([A-Z])?)(?<portable>[/][P])?\b))"""
    internal const val compoundCallsignPattern = """((?<callsign>(?:[@]?|\b)(?<extended>[A-Z0-9\/@][A-Z0-9\/]{0,2}[\/]?[A-Z0-9\/]{0,3}[\/]?[A-Z0-9\/]{0,3})\b))"""
    internal const val packCallsignPattern = """(([0-9A-Z ])([0-9A-Z])([0-9])([A-Z ])([A-Z ])([A-Z ]))"""

    /**
     * Directed commands. Order matches the QMap key ordering where it matters
     * for reverse lookup (see `commandName(for:)`).
     */
    val directedCommands: List<Pair<String, Int>> = listOf(
        " HEARTBEAT" to -1, " HB" to -1, " CQ" to -1,
        " SNR?" to 0, "?" to 0,
        " DIT DIT" to 1,
        " HEARING?" to 3,
        " GRID?" to 4,
        ">" to 5,
        " STATUS?" to 6,
        " STATUS" to 7,
        " HEARING" to 8,
        " MSG" to 9,
        " MSG TO:" to 10,
        " QUERY" to 11,
        " QUERY MSGS" to 12, " QUERY MSGS?" to 12,
        " QUERY CALL" to 13,
        " GRID" to 15,
        " INFO?" to 16, " INFO" to 17,
        " FB" to 18, " HW CPY?" to 19, " SK" to 20, " RR" to 21,
        " QSL?" to 22, " QSL" to 23,
        " CMD" to 24,
        " SNR" to 25, " NO" to 26, " YES" to 27, " 73" to 28,
        " NACK" to 2, " ACK" to 14,
        " HEARTBEAT SNR" to 29,
        " AGN?" to 30, "  " to 31, " " to 31,
    )
    internal val directedMap: Map<String, Int> = LinkedHashMap<String, Int>().also { m ->
        for ((k, v) in directedCommands) if (k !in m) m[k] = v
    }
    internal val sortedDirectedKeys: List<String> = directedCommands.map { it.first }.sortedWith { a, b -> a.compareTo(b) }

    internal val allowedCmds: Set<Int> = (-1..31).toSet()
    internal val autoreplyCmds: Set<Int> = setOf(0, 2, 3, 4, 6, 9, 10, 11, 12, 13, 14, 16, 30)
    internal val bufferedCmds: Set<Int> = setOf(5, 9, 10, 11, 12, 13, 15, 24)
    internal val snrCmds: Set<Int> = setOf(25, 29)
    internal val checksumCmds: Map<Int, Int> = mapOf(5 to 16, 9 to 16, 10 to 16, 11 to 16, 12 to 16, 13 to 16, 15 to 0, 24 to 16)

    internal const val callsignPattern = "(?<callsign>[@]?[A-Z0-9/]+)"
    internal const val optionalCmdPattern = """(?<cmd>\s?(?:AGN[?]|QSL[?]|HW CPY[?]|MSG TO[:]|SNR[?]|INFO[?]|GRID[?]|STATUS[?]|QUERY MSGS[?]|HEARING[?]|(?:(?:STATUS|HEARING|QUERY CALL|QUERY MSGS|QUERY|CMD|MSG|NACK|ACK|73|YES|NO|HEARTBEAT SNR|SNR|QSL|RR|SK|FB|INFO|GRID|DIT DIT)(?=[ ]|$))|[?> ]))?"""
    internal const val optionalGridPattern = """(?<grid>\s?[A-R]{2}[0-9]{2})?"""
    internal const val optionalNumPattern = """(?<num>(?<=SNR)\s?[-+]?(?:3[01]|[0-2]?[0-9]))?"""

    internal val directedRe = QRegex("^" + callsignPattern + optionalCmdPattern + optionalNumPattern)
    internal val heartbeatRe = QRegex("""^\s*(?<callsign>[@](?:ALLCALL|HB)\s+)?(?<type>CQ CQ CQ|CQ DX|CQ QRP|CQ CONTEST|CQ FIELD|CQ FD|CQ CQ|CQ|HB|HEARTBEAT(?!\s+SNR))(?:\s(?<grid>[A-R]{2}[0-9]{2}))?\b""")
    internal val compoundRe = QRegex("""^\s*[`]""" + callsignPattern + "(?<extra>" + optionalGridPattern + optionalCmdPattern + optionalNumPattern + ")")
    internal val gridRe = QRegex(gridPattern)
    internal val baseCallsignRe = QRegex(baseCallsignPattern)
    internal val compoundCallsignRe = QRegex(compoundCallsignPattern)
    internal val compoundCallsignAnchoredRe = QRegex("^" + compoundCallsignPattern)
    internal val packCallsignRe = QRegex(packCallsignPattern)
    internal val alnumMixRe = QRegex("[0-9][A-Z]|[A-Z][0-9]")

    val huffTable: List<Pair<String, String>> = listOf(
        " " to "01", "E" to "100", "T" to "1101", "A" to "0011", "O" to "11111", "I" to "11100",
        "N" to "10111", "S" to "10100", "H" to "00011", "R" to "00000", "D" to "111011", "L" to "110011",
        "C" to "110001", "U" to "101101", "M" to "101011", "W" to "001011", "F" to "001001", "G" to "000101",
        "Y" to "000011", "P" to "1111011", "B" to "1111001", "." to "1110100", "V" to "1100101",
        "K" to "1100100", "-" to "1100001", "+" to "1100000", "?" to "1011001", "!" to "1011000",
        "\"" to "1010101", "X" to "1010100", "0" to "0010101", "J" to "0010100", "1" to "0010001",
        "Q" to "0010000", "2" to "0001001", "Z" to "0001000", "3" to "0000101", "5" to "0000100",
        "4" to "11110101", "9" to "11110100", "8" to "11110001", "6" to "11110000", "7" to "11101011",
        "/" to "11101010",
    )
    internal val huffValid: Set<String> = huffTable.map { it.first }.toSet()
    internal val huffDict: Map<String, List<Boolean>> = huffTable.associate { (k, v) -> k to v.map { it == '1' } }

    internal const val nbasecall: Int = 37 * 36 * 10 * 27 * 27 * 27
    internal const val nbasegrid: Int = 180 * 180
    internal const val nusergrid: Int = nbasegrid + 10
    internal const val nmaxgrid: Int = (1 shl 15) - 1

    val groupCalls: List<String> = listOf(
        "@ALLCALL", "@JS8NET", "@DX/NA", "@DX/SA", "@DX/EU", "@DX/AS", "@DX/AF", "@DX/OC", "@DX/AN",
        "@REGION/1", "@REGION/2", "@REGION/3",
        "@GROUP/0", "@GROUP/1", "@GROUP/2", "@GROUP/3", "@GROUP/4", "@GROUP/5", "@GROUP/6", "@GROUP/7", "@GROUP/8", "@GROUP/9",
        "@COMMAND", "@CONTROL", "@NET", "@NTS",
        "@RESERVE/0", "@RESERVE/1", "@RESERVE/2", "@RESERVE/3", "@RESERVE/4",
        "@APRSIS", "@RAGCHEW", "@JS8", "@EMCOMM", "@ARES", "@MARS", "@AMRRON", "@RACES", "@RAYNET",
        "@RADAR", "@SKYWARN", "@CQ", "@HB", "@QSO", "@QSOPARTY", "@CONTEST", "@FIELDDAY", "@SOTA",
        "@IOTA", "@POTA", "@QRP", "@QRO",
    )
    internal val basecalls: Map<String, Int> = HashMap<String, Int>().also { m ->
        m["<....>"] = nbasecall + 1
        for ((i, g) in groupCalls.withIndex()) m[g] = nbasecall + 2 + i
    }
    internal val basecallsByValue: Map<Int, String> = HashMap<Int, String>().also { m ->
        // QMap iteration is key-ordered; first key wins on duplicates (none).
        for (k in basecalls.keys.sortedWith { a, b -> a.compareTo(b) }) {
            val v = basecalls.getValue(k)
            if (v !in m) m[v] = k
        }
    }

    internal val cqs: Map<Int, String> = mapOf(0 to "CQ CQ CQ", 1 to "CQ DX", 2 to "CQ QRP", 3 to "CQ CONTEST", 4 to "CQ FIELD", 5 to "CQ FD", 6 to "CQ CQ", 7 to "CQ")
    internal val hbs: Map<Int, String> = mapOf(0 to "HB", 1 to "HB", 2 to "HB", 3 to "HB", 4 to "HB", 5 to "HB", 6 to "HB", 7 to "HB")

    /** QString ordering (UTF-16 code unit comparison); Kotlin's compareTo is the same. */
    internal fun qLess(a: String, b: String): Boolean = a < b

    /** QMap::key(value): the smallest key mapping to `value`. */
    internal fun commandName(value: Int): String {
        for (k in sortedDirectedKeys) if (directedMap[k] == value) return k
        return ""
    }

    // MARK: Utilities

    fun rstrip(s: String): String {
        var end = s.length
        while (end > 0 && isSpace(s[end - 1])) end--
        return s.substring(0, end)
    }

    fun lstrip(s: String): String {
        var i = 0
        while (i < s.length && isSpace(s[i])) i++
        return s.substring(i)
    }

    internal fun isSpace(c: Char): Boolean = qIsSpace(c)

    fun cqString(n: Int): String = cqs[n] ?: ""
    fun hbString(n: Int): String = hbs[n] ?: ""

    fun startsWithCQ(text: String): Boolean = cqs.values.any { text.startsWith(it) }
    fun startsWithHB(text: String): Boolean = hbs.values.any { text.startsWith(it) }

    /** "+05", "-12", "-05" (Qt arg() with zero fill keeps the sign first). */
    fun formatSNR(snr: Int): String {
        if (snr < -60 || snr > 60) return ""
        val mag = abs(snr)
        val digits = if (mag < 10) "0$mag" else "$mag"
        return (if (snr < 0) "-" else "+") + digits
    }

    // MARK: Checksums

    internal fun crc16Kermit(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (k in 0 until 8) crc = if ((crc and 1) != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
        }
        return crc and 0xFFFF
    }

    internal fun crc32Bzip2(data: ByteArray): Long {
        var crc = 0xFFFF_FFFFL
        for (b in data) {
            crc = crc xor ((b.toLong() and 0xFF) shl 24)
            for (k in 0 until 8) {
                crc = if ((crc and 0x8000_0000L) != 0L) ((crc shl 1) xor 0x04C1_1DB7L) else (crc shl 1)
                crc = crc and 0xFFFF_FFFFL
            }
        }
        return crc xor 0xFFFF_FFFFL
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun checksum16(input: String): String {
        var c = pack16bits(crc16Kermit(utf8(input)))
        if (c.u16Count < 3) c += " ".qRepeated(3 - c.u16Count)
        return c
    }

    fun checksum16Valid(checksum: String, input: String): Boolean = pack16bits(crc16Kermit(utf8(input))) == checksum

    fun checksum32(input: String): String {
        var c = pack32bits(crc32Bzip2(utf8(input)))
        if (c.u16Count < 6) c += " ".qRepeated(6 - c.u16Count)
        return c
    }

    fun checksum32Valid(checksum: String, input: String): Boolean = pack32bits(crc32Bzip2(utf8(input))) == checksum

    // MARK: Callsign and grid parsing

    fun parseCallsigns(input: String): List<String> {
        val calls = ArrayList<String>()
        for (m in compoundCallsignRe.globalMatch(input)) {
            val callsign = m.captured("callsign").qTrimmed()
            if (!isValidCallsign(callsign).valid) continue
            if (gridRe.hasMatch(callsign)) continue
            calls.add(callsign)
        }
        return calls
    }

    fun parseGrids(input: String): List<String> =
        gridRe.globalMatch(input).map { it.captured("grid") }.filter { it != "RR73" }

    // MARK: Huffman

    internal fun huffEncode(text: String): List<Pair<Int, List<Boolean>>> {
        // All keys are single characters, so the length-desc / reverse-lexical
        // search in JS8Call reduces to a direct lookup per UTF-16 unit.
        val out = ArrayList<Pair<Int, List<Boolean>>>()
        for (u in text) {
            val bits = huffDict[u.toString()]
            if (bits != null) out.add(Pair(1, bits))
        }
        return out
    }

    internal fun huffDecode(bitvec: List<Boolean>): String {
        val text = StringBuilder()
        var i = 0
        outer@ while (i < bitvec.size) {
            for ((key, code) in huffTable) {
                val bits = huffDict.getValue(key)
                if (i + bits.size <= bitvec.size && bitvec.subList(i, i + bits.size) == bits) {
                    text.append(key)
                    i += code.length
                    continue@outer
                }
            }
            break
        }
        return text.toString()
    }

    // MARK: Base-41 / 72-bit packing

    /** `packed` is a UInt16 value. */
    internal fun pack16bits(packed: Int): String {
        val p = packed and 0xFFFF
        val n = nalphabet
        val out = StringBuilder()
        var tmp = p / (n * n)
        out.append(alphabetChars[tmp])
        tmp = (p - tmp * (n * n)) / n
        out.append(alphabetChars[tmp])
        out.append(alphabetChars[p % n])
        return out.toString()
    }

    internal fun unpack16bits(value: String): Int {
        if (value.length < 3) return 0
        fun idx(c: Char): Int = alphabetChars.indexOf(c)
        val unpacked = nalphabet * nalphabet * idx(value[0]) + nalphabet * idx(value[1]) + idx(value[2])
        if (unpacked > 65535 || unpacked < 0) return 0
        return unpacked
    }

    /** `packed` is a UInt32 value. */
    internal fun pack32bits(packed: Long): String =
        pack16bits(((packed ushr 16) and 0xFFFF).toInt()) + pack16bits((packed and 0xFFFF).toInt())

    /** Returns (UInt64 value, UInt8 rem). */
    internal fun unpack72bits(text: String): Pair<Long, Int> {
        fun idx(c: Char): Int = alphabet72Chars.indexOf(c)
        var value = 0L
        for (i in 0 until 10) {
            value = value or (idx(text[i]).toLong() shl (58 - 6 * i))
        }
        val remHigh = idx(text[10]) and 0xFF
        value = value or (remHigh ushr 2).toLong()
        val remLow = idx(text[11]) and 0xFF
        val rem = (((remHigh and 0x3) shl 6) or remLow) and 0xFF
        return Pair(value, rem)
    }

    internal fun pack72bits(value: Long, rem: Int): String {
        val packed = CharArray(12)
        val r = rem and 0xFF
        val remHigh = (((value and 0xF).toInt() shl 2) or (r ushr 6)) and 0xFF
        val remLow = r and 0x3F
        var v = value ushr 4
        packed[11] = alphabet72Chars[remLow]
        packed[10] = alphabet72Chars[remHigh]
        for (i in 0 until 10) {
            packed[9 - i] = alphabet72Chars[(v and 0x3F).toInt()]
            v = v ushr 6
        }
        return String(packed)
    }

    // MARK: Alphanumeric / callsign packing

    internal fun alnumIndex(c: Char): Long = alphanumericChars.indexOf(c).toLong()

    internal fun packAlphaNumeric50(value: String): Long {
        val word = StringBuilder()
        for (c in value) {
            if (c in '0'..'9' || c in 'A'..'Z' || c == ' ' || c == '/' || c == '@') word.append(c)
        }
        val slash = '/'
        val space = ' '
        if (word.length > 3 && word[3] != slash) word.insert(3, space)
        if (word.length > 7 && word[7] != slash) word.insert(7, space)
        while (word.length < 11) word.append(space)

        fun ix(i: Int): Long {
            val f = alphanumericChars.indexOf(word[i])
            return if (f >= 0) f.toLong() else -1L // indexOf == -1 (cannot occur after filtering)
        }
        val k38x3 = 38L * 38 * 38
        val a = k38x3 * 2 * 38 * 38 * 38 * 2 * 38 * 38 * ix(0)
        val b = k38x3 * 2 * 38 * 38 * 38 * 2 * 38 * ix(1)
        val c = k38x3 * 2 * 38 * 38 * 38 * 2 * ix(2)
        val d = k38x3 * 2 * 38 * 38 * 38 * (if (word[3] == slash) 1 else 0)
        val e = k38x3 * 2 * 38 * 38 * ix(4)
        val f = k38x3 * 2 * 38 * ix(5)
        val g = k38x3 * 2 * ix(6)
        val h = k38x3 * (if (word[7] == slash) 1 else 0)
        val i = 38L * 38 * ix(8)
        val j = 38L * ix(9)
        val k = ix(10)
        return a + b + c + d + e + f + g + h + i + j + k
    }

    internal fun unpackAlphaNumeric50(packedIn: Long): String {
        var packed = packedIn
        val word = CharArray(11) { ' ' }
        fun take(m: Long): Int {
            val t = java.lang.Long.remainderUnsigned(packed, m)
            packed = java.lang.Long.divideUnsigned(packed, m)
            return t.toInt()
        }
        word[10] = alphanumericChars[take(38)]
        word[9] = alphanumericChars[take(38)]
        word[8] = alphanumericChars[take(38)]
        word[7] = if (take(2) != 0) '/' else ' '
        word[6] = alphanumericChars[take(38)]
        word[5] = alphanumericChars[take(38)]
        word[4] = alphanumericChars[take(38)]
        word[3] = if (take(2) != 0) '/' else ' '
        word[2] = alphanumericChars[take(38)]
        word[1] = alphanumericChars[take(38)]
        word[0] = alphanumericChars[min(take(39), alphanumericChars.length - 1)]
        return String(word.filter { it != ' ' }.toCharArray())
    }

    /** Returns (packed UInt32, portable). Packed 0 means unpackable. */
    internal fun packCallsign(value: String): Pair<Int, Boolean> {
        var callsign = value.uppercase().qTrimmed()
        basecalls[callsign]?.let { return Pair(it, false) }
        var portable = false
        if (callsign.endsWith("/P")) {
            callsign = callsign.qLeft(callsign.u16Count - 2)
            portable = true
        }
        if (callsign.startsWith("3DA0")) callsign = "3D0" + callsign.qMid(4)
        if (callsign.startsWith("3X") && callsign.u16Count > 2) {
            val c = callsign.qAt(2)
            if (c in 'A'..'Z') callsign = "Q" + callsign.qMid(2)
        }
        val slen = callsign.u16Count
        if (slen < 2 || slen > 6) return Pair(0, portable)

        val permutations = arrayListOf(callsign)
        when (slen) {
            2 -> permutations.add(" $callsign   ")
            3 -> { permutations.add(" $callsign  "); permutations.add("$callsign   ") }
            4 -> { permutations.add(" $callsign "); permutations.add("$callsign  ") }
            5 -> { permutations.add(" $callsign"); permutations.add("$callsign ") }
            else -> {}
        }
        var matched = ""
        for (p in permutations) {
            val m = packCallsignRe.match(p)
            if (m != null) matched = m.captured(0)
        }
        if (matched.isEmpty() || matched.u16Count < 6) return Pair(0, portable)
        fun ix(c: Char): Int = alphanumericChars.indexOf(c)
        var packed = ix(matched[0])
        packed = 36 * packed + ix(matched[1])
        packed = 10 * packed + ix(matched[2])
        packed = 27 * packed + ix(matched[3]) - 10
        packed = 27 * packed + ix(matched[4]) - 10
        packed = 27 * packed + ix(matched[5]) - 10
        return Pair(packed, portable)
    }

    /** `valueIn` is a UInt32 value. */
    internal fun unpackCallsign(valueIn: Int, portable: Boolean): String {
        basecallsByValue[valueIn]?.let { return it }
        var value = valueIn.toLong() and 0xFFFF_FFFFL
        val word = CharArray(6) { ' ' }
        fun ch(i: Long): Char = if (i < alphanumericChars.length) alphanumericChars[i.toInt()] else '?'
        word[5] = ch(value % 27 + 10); value /= 27
        word[4] = ch(value % 27 + 10); value /= 27
        word[3] = ch(value % 27 + 10); value /= 27
        word[2] = ch(value % 10); value /= 10
        word[1] = ch(value % 36); value /= 36
        word[0] = ch(value)
        var callsign = String(word)
        if (callsign.startsWith("3D0")) callsign = "3DA0" + callsign.qMid(3)
        if (callsign.startsWith("Q") && callsign.u16Count > 1) {
            val c = callsign.qAt(1)
            if (c in 'A'..'Z') callsign = "3X" + callsign.qMid(1)
        }
        if (portable) callsign = callsign.qTrimmed() + "/P"
        return callsign.qTrimmed()
    }

    fun deg2grid(dlongIn: Float, dlat: Float): String {
        var dlong = dlongIn
        if (dlong < -180) dlong += 360
        if (dlong > 180) dlong -= 360
        val nlong = (60.0 * (180.0 - dlong.toDouble()) / 5).toInt()
        var n1 = nlong / 240
        var n2 = (nlong - 240 * n1) / 24
        var n3 = nlong - 240 * n1 - 24 * n2
        val g = CharArray(6)
        g[0] = (65 + n1).toChar(); g[2] = (48 + n2).toChar(); g[4] = (97 + n3).toChar()
        val nlat = (60.0 * (dlat.toDouble() + 90) / 2.5).toInt()
        n1 = nlat / 240
        n2 = (nlat - 240 * n1) / 24
        n3 = nlat - 240 * n1 - 24 * n2
        g[1] = (65 + n1).toChar(); g[3] = (48 + n2).toChar(); g[5] = (97 + n3).toChar()
        return String(g)
    }

    /** (longitude, latitude) of the centre of a grid square. */
    fun grid2deg(grid: String): Pair<Float, Float> {
        var g = grid
        if (g.u16Count < 6) g = grid.qLeft(4) + "mm"
        g = g.qLeft(4).uppercase() + g.qRight(2).lowercase()
        val u = IntArray(g.length) { g[it].code }
        val nlong = 180 - 20 * (u[0] - 65)
        val n20d = 2 * (u[2] - 48)
        val xminlong = (5.0 * ((u[4] - 97).toDouble() + 0.5)).toFloat()
        val dlong = (nlong - n20d).toFloat() - xminlong / 60.0f
        val nlat = -90 + 10 * (u[1] - 65) + u[3] - 48
        val xminlat = (2.5 * ((u[5] - 97).toDouble() + 0.5)).toFloat()
        val dlat = nlat.toFloat() + xminlat / 60.0f
        return Pair(dlong, dlat)
    }

    /** Returns a UInt16 value. */
    internal fun packGrid(value: String): Int {
        val grid = value.qTrimmed()
        if (grid.u16Count < 4) return (1 shl 15) - 1
        val (lon, lat) = grid2deg(grid.qLeft(4))
        val ilong = lon.toInt()
        val ilat = (lat + 90).toInt()
        return (((ilong + 180) / 2) * 180 + ilat) and 0xFFFF
    }

    internal fun unpackGrid(value: Int): String {
        if (value > nbasegrid) return ""
        val dlat = (value % 180 - 90).toFloat()
        val dlong = (value / 180 * 2 - 180 + 2).toFloat()
        return deg2grid(dlong, dlat).qLeft(4)
    }

    /** Swift Int(String): optional sign then ASCII digits, nil on Int64 overflow. */
    private fun swiftInt(s: String): Long? {
        if (s.isEmpty()) return null
        var i = 0
        var neg = false
        if (s[0] == '+' || s[0] == '-') {
            neg = s[0] == '-'
            i = 1
        }
        if (i >= s.length) return null
        var v = 0L // accumulated negatively so Long.MIN_VALUE parses
        while (i < s.length) {
            val c = s[i]
            if (c !in '0'..'9') return null
            try {
                v = Math.subtractExact(Math.multiplyExact(v, 10L), (c - '0').toLong())
            } catch (e: ArithmeticException) {
                return null
            }
            i++
        }
        if (neg) return v
        return if (v == Long.MIN_VALUE) null else -v
    }

    /** Returns (UInt8 packed number, parsed ok). */
    internal fun packNum(num: String): Pair<Int, Boolean> {
        if (num.isEmpty()) return Pair(0, false)
        val parsed = swiftInt(num.qTrimmedForInt())
        val inum = max(-30L, min(parsed ?: 0L, 31L)).toInt()
        return Pair(inum + 30 + 1, parsed != null)
    }

    /** `num` and the result are UInt8 values. */
    internal fun packCmd(cmd: Int, num: Int): Int {
        val cmdStr = commandName(cmd)
        if (isSNRCommand(cmdStr)) {
            var value = (((1 shl 1) or (if (cmdStr == " HEARTBEAT SNR") 1 else 0)) shl 6) and 0xFF
            value = (value + (num and 0x3F)) and 0xFF
            return value
        }
        return cmd and 0x7F
    }

    /** `value` is a UInt8; returns (cmd, UInt8 num). */
    internal fun unpackCmd(value: Int): Pair<Int, Int> {
        if (value and 0x80 != 0) {
            val num = value and 0x3F
            var cmd = directedMap.getValue(" SNR")
            if (value and 0x40 != 0) cmd = directedMap.getValue(" HEARTBEAT SNR")
            return Pair(cmd, num)
        }
        return Pair(value and 0x7F, 0)
    }

    fun isSNRCommand(cmd: String): Boolean {
        val v = directedMap[cmd] ?: return false
        return v in snrCmds
    }

    fun isCommandAllowed(cmd: String): Boolean {
        val v = directedMap[cmd] ?: return false
        return v in allowedCmds
    }

    fun isCommandBuffered(cmd: String): Boolean {
        val v = directedMap[cmd] ?: return false
        return cmd.contains(" ") || v in bufferedCmds
    }

    fun isCommandChecksumed(cmd: String): Int {
        val v = directedMap[cmd] ?: return 0
        return checksumCmds[v] ?: 0
    }

    fun isCommandAutoreply(cmd: String): Boolean {
        val v = directedMap[cmd] ?: return false
        return v in autoreplyCmds
    }

    internal fun isValidCompoundCallsign(callsign: String): Boolean {
        val slashCount = callsign.count { it == '/' }
        if (callsign.u16Count - slashCount > 9) return false
        val idx = callsign.qIndexOf("/")
        if (idx != -1) return basecalls[callsign.qLeft(idx)] == null
        if (callsign.startsWith("@")) return true
        if (callsign.u16Count > 2 && alnumMixRe.hasMatch(callsign)) return true
        return false
    }

    /** Result of [isValidCallsign] (Swift tuple `(valid:, compound:)`). */
    data class IsValidCallsignResult(val valid: Boolean, val compound: Boolean)

    fun isValidCallsign(callsign: String): IsValidCallsignResult {
        if (basecalls[callsign] != null) return IsValidCallsignResult(true, false)
        val bm = baseCallsignRe.match(callsign)
        if (bm != null && bm.capturedLength0 == callsign.u16Count) {
            return IsValidCallsignResult(callsign.u16Count > 2 && alnumMixRe.hasMatch(callsign), false)
        }
        val cm = compoundCallsignAnchoredRe.match(callsign)
        if (cm != null && cm.capturedLength0 == callsign.u16Count) {
            val valid = isValidCompoundCallsign(cm.captured(0))
            return IsValidCallsignResult(valid, valid)
        }
        return IsValidCallsignResult(false, false)
    }

    fun isCompoundCallsign(callsign: String): Boolean {
        if (basecalls[callsign] != null && !callsign.startsWith("@")) return false
        val bm = baseCallsignRe.match(callsign)
        if (bm != null && bm.capturedLength0 == callsign.u16Count) return false
        val m = compoundCallsignAnchoredRe.match(callsign) ?: return false
        if (m.capturedLength0 != callsign.u16Count) return false
        return isValidCompoundCallsign(m.captured(0))
    }

    fun isGroupAllowed(group: String): Boolean = group != "@APRSIS" && group != "@JS8NET"

    /** Radio::base_callsign — split on the first '/' and keep the larger part. */
    fun baseCallsign(callsign: String): String {
        var c = callsign
        val slash = c.qIndexOf("/")
        if (slash >= 0) {
            val right = c.u16Count - slash - 1
            c = if (right >= slash) c.qMid(slash + 1) else c.qLeft(slash)
        }
        return c.uppercase()
    }

    // MARK: Heartbeat / compound frames

    internal fun packHeartbeatMessage(text: String, callsign: String): Pair<String, Int> {
        val parsed = heartbeatRe.match(text) ?: return Pair("", 0)
        val extra = parsed.captured("grid")
        val type = parsed.captured("type")
        val isAlt = type.startsWith("CQ")
        if (callsign.isEmpty()) return Pair("", 0)

        var packedExtra = nmaxgrid
        if (extra.u16Count == 4 && gridRe.hasMatch(extra)) packedExtra = packGrid(extra)

        var number = hbs.keys.sorted().firstOrNull { hbs[it] == type } ?: 0
        if (isAlt) {
            packedExtra = packedExtra or (1 shl 15)
            number = cqs.keys.sorted().firstOrNull { cqs[it] == type } ?: 0
        }
        val frame = packCompoundFrame(callsign, FrameType.heartbeat.rawValue, packedExtra, number and 0xFF)
        if (frame.isEmpty()) return Pair("", 0)
        return Pair(frame, parsed.capturedLength0)
    }

    data class HeartbeatInfo(
        var parts: List<String>,
        var type: Int,
        var isAlt: Boolean,
        var bits3: Int,
    )

    internal fun unpackHeartbeatMessage(text: String): HeartbeatInfo? {
        val f = unpackCompoundFrame(text) ?: return null
        if (f.type != FrameType.heartbeat.rawValue) return null
        val parts = f.parts + unpackGrid(f.num and ((1 shl 15) - 1))
        return HeartbeatInfo(parts = parts, type = f.type, isAlt = f.num and (1 shl 15) != 0, bits3 = f.bits3)
    }

    internal fun packCompoundMessage(text: String): Pair<String, Int> {
        val parsed = compoundRe.match(text) ?: return Pair("", 0)
        val callsign = parsed.captured("callsign")
        val grid = parsed.captured("grid")
        val cmd = parsed.captured("cmd")
        val num = parsed.captured("num").qTrimmed()
        if (callsign.isEmpty()) return Pair("", 0)

        var type = FrameType.compound.rawValue
        var extra = nmaxgrid
        if (cmd.isNotEmpty() && directedMap[cmd] != null && isCommandAllowed(cmd)) {
            val inum = packNum(num).first
            extra = (nusergrid + packCmd(directedMap.getValue(cmd), inum)) and 0xFFFF
            type = FrameType.compoundDirected.rawValue
        } else if (grid.isNotEmpty()) {
            extra = packGrid(grid)
        }
        val frame = packCompoundFrame(callsign, type, extra, 0)
        return Pair(frame, parsed.capturedLength0)
    }

    data class CompoundInfo(
        var parts: List<String>,
        var type: Int,
        var bits3: Int,
    )

    internal fun unpackCompoundMessage(text: String): CompoundInfo? {
        val f = unpackCompoundFrame(text) ?: return null
        if (f.type != FrameType.compound.rawValue && f.type != FrameType.compoundDirected.rawValue) return null
        val parts = f.parts.toMutableList()
        val extra = f.num
        if (extra <= nbasegrid) {
            parts.add(" " + unpackGrid(extra))
        } else if (nusergrid <= extra && extra < nmaxgrid) {
            val (cmd, num) = unpackCmd((extra - nusergrid) and 0xFF)
            val cmdStr = commandName(cmd)
            parts.add(cmdStr)
            if (isSNRCommand(cmdStr)) parts.add(formatSNR(num - 31))
        }
        return CompoundInfo(parts = parts, type = f.type, bits3 = f.bits3)
    }

    /** `num` is a UInt16 and `bits3` a UInt8 value. */
    internal fun packCompoundFrame(callsign: String, type: Int, num: Int, bits3: Int): String {
        if (type == FrameType.data.rawValue || type == FrameType.directed.rawValue) return ""
        val packedCallsign = packAlphaNumeric50(callsign)
        if (packedCallsign == 0L) return ""
        val n = num and 0xFFFF
        val packed11 = (n and (((1 shl 11) - 1) shl 5)) ushr 5
        val packed5 = n and 0x1F
        val packed8 = ((packed5 shl 3) or bits3) and 0xFF
        val bits = Bits.intToBits(type.toLong(), expected = 3) +
            Bits.intToBits(packedCallsign, expected = 50) +
            Bits.intToBits(packed11.toLong(), expected = 11)
        return pack72bits(Bits.bitsToInt(bits), packed8)
    }

    data class CompoundFrame(
        var parts: List<String>,
        var type: Int,
        var num: Int, // UInt16
        var bits3: Int, // UInt8
    )

    internal fun unpackCompoundFrame(text: String): CompoundFrame? {
        if (text.u16Count < 12 || text.contains(" ")) return null
        val (value, packed8) = unpack72bits(text)
        val bits = Bits.intToBits(value, expected = 64)
        val packed5 = packed8 ushr 3
        val packed3 = packed8 and 0x7
        val flag = Bits.bitsToInt(Bits.mid(bits, 0, 3)).toInt()
        if (flag == FrameType.data.rawValue || flag == FrameType.directed.rawValue) return null
        val packedCallsign = Bits.bitsToInt(Bits.mid(bits, 3, 50))
        val packed11 = Bits.bitsToInt(Bits.mid(bits, 53, 11)).toInt() and 0xFFFF
        val callsign = unpackAlphaNumeric50(packedCallsign)
        val num = ((packed11 shl 5) or packed5) and 0xFFFF
        return CompoundFrame(parts = listOf(callsign, ""), type = flag, num = num, bits3 = packed3)
    }

    // MARK: Directed frames

    data class DirectedPack(
        var frame: String,
        var n: Int,
        var to: String = "",
        var toCompound: Boolean = false,
        var cmd: String = "",
        var num: String = "",
    )

    internal fun packDirectedMessage(text: String, mycall: String): DirectedPack {
        val result = DirectedPack(frame = "", n = 0)
        val match = directedRe.match(text) ?: return result

        var from = mycall
        if (isCompoundCallsign(from)) from = "<....>"
        var to = match.captured("callsign")
        val cmd = match.captured("cmd")
        val num = match.captured("num")

        if (cmd.isEmpty()) return result

        val v = isValidCallsign(to)
        val validTo = to != mycall && v.valid
        if (!validTo) return result

        result.to = to
        result.toCompound = v.compound
        if (v.compound) to = "<....>"

        if (!isCommandAllowed(cmd) && !isCommandAllowed(cmd.qTrimmed())) return result

        val (inum, numOK) = packNum(num.qTrimmed())
        if (numOK) result.num = num

        val (packedFrom, portableFrom) = packCallsign(from)
        val (packedTo, portableTo) = packCallsign(to)
        if (packedFrom == 0 || packedTo == 0) return result

        var cmdOut = ""
        var packedCmd = 0
        directedMap[cmd]?.let {
            cmdOut = cmd
            packedCmd = it
        }
        directedMap[cmd.qTrimmed()]?.let {
            cmdOut = cmd.qTrimmed()
            packedCmd = it
        }
        val flag = FrameType.directed.rawValue
        val packedExtra = (((if (portableFrom) 1 else 0) shl 7) + ((if (portableTo) 1 else 0) shl 6) + inum) and 0xFF

        val pcmd = ((packedCmd % 32) + 32) % 32
        val bits = Bits.intToBits(flag.toLong(), expected = 3) +
            Bits.intToBits(packedFrom.toLong() and 0xFFFF_FFFFL, expected = 28) +
            Bits.intToBits(packedTo.toLong() and 0xFFFF_FFFFL, expected = 28) +
            Bits.intToBits(pcmd.toLong(), expected = 5)
        result.cmd = cmdOut
        result.n = match.capturedLength0
        result.frame = pack72bits(Bits.bitsToInt(bits), packedExtra)
        return result
    }

    /** Result of [unpackDirectedMessage] (Swift tuple `(parts:, type:)`). */
    data class UnpackDirectedResult(val parts: List<String>, val type: Int)

    internal fun unpackDirectedMessage(text: String): UnpackDirectedResult? {
        if (text.u16Count < 12 || text.contains(" ")) return null
        val (value, extraIn) = unpack72bits(text)
        val bits = Bits.intToBits(value, expected = 64)
        val flag = Bits.bitsToInt(Bits.mid(bits, 0, 3)).toInt()
        if (flag != FrameType.directed.rawValue) return null
        val packedFrom = Bits.bitsToInt(Bits.mid(bits, 3, 28)).toInt()
        val packedTo = Bits.bitsToInt(Bits.mid(bits, 31, 28)).toInt()
        val packedCmd = Bits.bitsToInt(Bits.mid(bits, 59, 5)).toInt()
        val portableFrom = (extraIn ushr 7) and 1 == 1
        val portableTo = (extraIn ushr 6) and 1 == 1
        val extra = extraIn % 64

        val from = unpackCallsign(packedFrom, portable = portableFrom)
        val to = unpackCallsign(packedTo, portable = portableTo)
        val cmd = commandName(packedCmd % 32)
        val parts = mutableListOf(from, to, cmd)
        if (extra != 0) {
            if (isSNRCommand(cmd)) {
                parts.add(formatSNR(extra - 31))
            } else {
                parts.add((extra - 31).toString())
            }
        }
        return UnpackDirectedResult(parts, flag)
    }

    // MARK: Data frames

    internal fun padFrame(frameBitsIn: List<Boolean>): String {
        val frameBits = ArrayList(frameBitsIn)
        val pad = 72 - frameBits.size
        if (pad > 0) {
            for (i in 0 until pad) frameBits.add(i != 0)
        }
        val value = Bits.bitsToInt(frameBits, 0, 64)
        val rem = Bits.bitsToInt(frameBits, 64, 8).toInt()
        return pack72bits(value, rem)
    }

    internal fun packHuffMessage(input: String, prefix: List<Boolean>): Pair<String, Int> {
        for (u in input) {
            val ch = u.toString().uppercase()
            if (ch !in huffValid) return Pair("", 0)
        }
        val frameBits = ArrayList(prefix)
        var i = 0
        for ((n, bits) in huffEncode(input)) {
            if (frameBits.size + bits.size < 72) {
                frameBits.addAll(bits)
                i += n
                continue
            }
            break
        }
        return Pair(padFrame(frameBits), i)
    }

    internal fun packCompressedMessage(input: String, prefix: List<Boolean>): Pair<String, Int> {
        val frameBits = ArrayList(prefix)
        var i = 0
        for ((bits, chars) in JSC.shared.compress(input)) {
            if (frameBits.size + bits.size < 72) {
                frameBits.addAll(bits)
                i += chars
                continue
            }
            break
        }
        return Pair(padFrame(frameBits), i)
    }

    internal fun packDataMessage(input: String): Pair<String, Int> {
        val (huffFrame, huffChars) = packHuffMessage(input, prefix = listOf(true, false))
        val (compFrame, compChars) = packCompressedMessage(input, prefix = listOf(true, true))
        if (huffChars > compChars) return Pair(huffFrame, huffChars)
        return Pair(compFrame, compChars)
    }

    internal fun unpackDataMessage(text: String): String {
        if (text.u16Count < 12 || text.contains(" ")) return ""
        val (value, rem) = unpack72bits(text)
        var bits = Bits.intToBits(value, expected = 64) + Bits.intToBits(rem.toLong(), expected = 8)
        if (!bits[0]) return ""
        bits = Bits.mid(bits, 1)
        val compressed = bits[0]
        val n = bits.lastIndexOf(false)
        bits = Bits.mid(bits, 1, n - 1)
        if (compressed) return JSC.shared.decompress(bits)
        return huffDecode(bits)
    }

    internal fun packFastDataMessage(input: String): Pair<String, Int> = packCompressedMessage(input, prefix = emptyList())

    internal fun unpackFastDataMessage(text: String): String {
        if (text.u16Count < 12 || text.contains(" ")) return ""
        val (value, rem) = unpack72bits(text)
        var bits = Bits.intToBits(value, expected = 64) + Bits.intToBits(rem.toLong(), expected = 8)
        val n = bits.lastIndexOf(false)
        bits = Bits.mid(bits, 0, n)
        return JSC.shared.decompress(bits)
    }

    // MARK: Message framing

    data class MessageInfo(
        var dirTo: String = "",
        var dirCmd: String = "",
        var dirNum: String = "",
    )

    data class Frame(
        var frame: String,
        var type: Int,
    )

    /**
     * Split `text` into JS8 frames exactly as JS8Call does. `info` (Swift
     * `inout`) is updated in place with the last directed command packed.
     */
    fun buildMessageFrames(
        mycall: String,
        mygrid: String,
        selectedCall: String,
        text: String,
        forceIdentify: Boolean,
        forceData: Boolean,
        submode: JS8Submode,
        info: MessageInfo = MessageInfo(),
    ): List<Frame> {
        val mycallCompound = isCompoundCallsign(mycall)
        val allFrames = ArrayList<Frame>()
        var forceIdentifyVar = forceIdentify

        for (lineIn in listOf(text)) {
            var line = lineIn
            val lineFrames = ArrayList<Frame>()
            var hasDirected = false
            var hasData = false
            if (forceData) {
                forceIdentifyVar = false
                hasData = true
            }

            if (line.startsWith("$mycall:") || line.startsWith("$mycall ")) {
                line = lstrip(line.qMid(mycall.u16Count + 1))
            }

            if (selectedCall.isNotEmpty() && !line.startsWith(selectedCall) && !line.startsWith("`") && !forceData) {
                val startsWithBase = line.startsWith("@ALLCALL") || startsWithCQ(line) || startsWithHB(line)
                val calls = parseCallsigns(line)
                val startsWithStandard = calls.isNotEmpty() && line.startsWith(calls[0]) && calls[0].u16Count > 3
                if (!(startsWithBase || startsWithStandard)) {
                    val sep = if (line.startsWith(" ")) "" else " "
                    line = selectedCall + sep + line
                }
            }

            while (line.u16Count > 0) {
                var frame = ""
                var useBcn = false
                var useCmp = false
                var useDir = false
                var useDat = false

                val (bcnFrame, l) = packHeartbeatMessage(line, mycall)
                val (cmpFrame, o) = packCompoundMessage(line)
                val dir = packDirectedMessage(line, mycall)
                val n = dir.n

                val isLikelyDataFrame = lineFrames.isEmpty() && selectedCall.isEmpty() && dir.to.isEmpty() && l == 0 && o == 0
                if (forceIdentifyVar && isLikelyDataFrame && !line.contains(mycall)) {
                    line = "$mycall: $line"
                }

                val datFrame: String
                val m: Int
                val fastDataFrame: Boolean
                if (submode == JS8Submode.normal) {
                    val r = packDataMessage(line)
                    datFrame = r.first
                    m = r.second
                    fastDataFrame = false
                } else {
                    val r = packFastDataMessage(line)
                    datFrame = r.first
                    m = r.second
                    fastDataFrame = true
                }

                if (!hasDirected && !hasData && l > 0) {
                    useBcn = true
                    hasDirected = false
                    frame = bcnFrame
                } else if (!hasDirected && !hasData && o > 0) {
                    useCmp = true
                    hasDirected = false
                    frame = cmpFrame
                } else if (!hasDirected && !hasData && n > 0) {
                    useDir = true
                    hasDirected = true
                    frame = dir.frame
                } else if (m > 0) {
                    useDat = true
                    hasData = true
                    frame = datFrame
                }

                if (useBcn) {
                    lineFrames.add(Frame(frame = frame, type = 0))
                    line = line.qMid(l)
                }
                if (useCmp) {
                    lineFrames.add(Frame(frame = frame, type = 0))
                    line = line.qMid(o)
                }
                if (useDir) {
                    var shouldUseStandardFrame = true
                    if (mycallCompound || dir.toCompound) {
                        val deFrame = packCompoundMessage("`$mycall $mygrid").first
                        if (deFrame.isNotEmpty()) lineFrames.add(Frame(frame = deFrame, type = 0))
                        val dcFrame = packCompoundMessage("`${dir.to}${dir.cmd}${dir.num}").first
                        if (dcFrame.isNotEmpty()) lineFrames.add(Frame(frame = dcFrame, type = 0))
                        shouldUseStandardFrame = false
                    }
                    if (shouldUseStandardFrame) lineFrames.add(Frame(frame = frame, type = 0))

                    line = line.qMid(n)

                    if (isCommandBuffered(dir.cmd) && line.isNotEmpty()) {
                        line = lstrip(line)
                        val skipAprs = dir.to.equals("@APRSIS", ignoreCase = true) &&
                            (dir.cmd == " MSG" || dir.cmd == " MSG TO:")
                        val checksumSize = if (skipAprs) 0 else isCommandChecksumed(dir.cmd)
                        if (checksumSize == 32) {
                            line = line + " " + checksum32(line)
                        } else if (checksumSize == 16) {
                            line = line + " " + checksum16(line)
                        }
                    }
                    info.dirCmd = dir.cmd
                    info.dirTo = dir.to
                    info.dirNum = dir.num
                }
                if (useDat) {
                    lineFrames.add(Frame(frame = frame, type = if (fastDataFrame) TransmissionType.data.rawValue else 0))
                    line = line.qMid(m)
                }
                if (!useBcn && !useCmp && !useDir && !useDat) {
                    // Nothing could be packed (e.g. unsupported characters); stop.
                    break
                }
            }

            if (lineFrames.isNotEmpty()) {
                lineFrames[0].type = lineFrames[0].type or TransmissionType.first.rawValue
                lineFrames[lineFrames.size - 1].type = lineFrames[lineFrames.size - 1].type or TransmissionType.last.rawValue
            }
            allFrames.addAll(lineFrames)
        }
        return allFrames
    }
}

/** QString::toInt tolerates surrounding whitespace and a leading '+'. */
internal fun String.qTrimmedForInt(): String {
    var t = qTrimmed()
    if (t.startsWith("+")) t = t.substring(1)
    return t
}
