package com.js8firefly.core

import java.util.regex.Matcher
import java.util.regex.Pattern

// Small helpers that reproduce the QString / QRegularExpression semantics the
// JS8Call protocol code relies on (UTF-16 indexing, mid() clamping, named
// captures). Kotlin strings are UTF-16 like QString, so indices here are
// QString indices. Keeping these semantics identical is what keeps frame
// packing byte-for-byte compatible with the desktop application.

internal val String.u16Count: Int get() = length

/** QString::mid(pos, len) semantics (len < 0 means "to the end"). */
internal fun String.qMid(pos: Int, len: Int = -1): String {
    val size = length
    if (pos >= size) return ""
    var p = pos
    var l = len
    if (p < 0) {
        if (l >= 0) l += p
        p = 0
    }
    if (l < 0 || l > size - p) l = size - p
    if (l <= 0) return ""
    return substring(p, p + l)
}

internal fun String.qLeft(n: Int): String {
    if (n < 0 || n >= length) return this
    return substring(0, n)
}

internal fun String.qRight(n: Int): String {
    val size = length
    if (n < 0 || n >= size) return this
    return substring(size - n)
}

/** UTF-16 code unit at `i` (QString::at). */
internal fun String.qAt(i: Int): Char = this[i]

internal fun String.qIndexOf(s: String, from: Int = 0): Int {
    if (from > length) return -1
    return indexOf(s, from)
}

/** QChar::isSpace: Unicode White_Space characters in the BMP. */
internal fun qIsSpace(c: Char): Boolean {
    val u = c.code
    return u == 0x20 || (u in 0x09..0x0D) || u == 0x85 || u == 0xA0 || u == 0x1680 ||
        (u in 0x2000..0x200A) || u == 0x2028 || u == 0x2029 || u == 0x202F || u == 0x205F || u == 0x3000
}

/** QString::trimmed removes QChar::isSpace characters from both ends. */
internal fun String.qTrimmed(): String {
    var start = 0
    var end = length
    while (start < end && qIsSpace(this[start])) start++
    while (end > start && qIsSpace(this[end - 1])) end--
    return if (start == 0 && end == length) this else substring(start, end)
}

internal fun String.qRepeated(n: Int): String = if (n > 0) repeat(n) else ""

/**
 * java.util.regex wrapper with QRegularExpression-like accessors.
 *
 * QRegularExpression (PCRE2 without UCP) treats `\b`, `\s` and `$` in ASCII /
 * newline-only terms, while java.util.regex (and Android's ICU-backed
 * implementation) can use Unicode rules. The pattern is rewritten so those
 * three tokens behave exactly as in PCRE on every platform.
 */
class QRegex(pattern: String) {
    val re: Pattern = try {
        Pattern.compile(translate(pattern))
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid regex $pattern: $e")
    }

    private val groupNames: List<String> =
        Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""").findAll(pattern).map { it.groupValues[1] }.toList()

    fun match(s: String, from: Int = 0): QMatch? {
        if (from > s.length) return null
        val m = re.matcher(s)
        m.region(from, s.length)
        if (!m.find()) return null
        return QMatch(m, s, groupNames)
    }

    fun globalMatch(s: String): List<QMatch> {
        val m = re.matcher(s)
        val out = ArrayList<QMatch>()
        while (m.find()) out.add(QMatch(m, s, groupNames))
        return out
    }

    fun hasMatch(s: String): Boolean = match(s) != null

    companion object {
        private const val WORD = "[A-Za-z0-9_]"
        private const val BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
        private const val SPACE_CHARS = "\\t\\n\\x0B\\f\\r "

        internal fun translate(p: String): String {
            val sb = StringBuilder()
            var inClass = false
            var i = 0
            while (i < p.length) {
                val c = p[i]
                if (c == '\\' && i + 1 < p.length) {
                    val n = p[i + 1]
                    when {
                        n == 's' && inClass -> sb.append(SPACE_CHARS)
                        n == 's' -> sb.append("[").append(SPACE_CHARS).append("]")
                        n == 'b' && !inClass -> sb.append(BOUNDARY)
                        else -> sb.append(c).append(n)
                    }
                    i += 2
                    continue
                }
                when {
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                }
                if (!inClass && c == '$') sb.append("(?=\\n?\\z)") else sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}

/** Snapshot of one match: UTF-16 ranges of the numbered and named groups. */
class QMatch internal constructor(m: Matcher, private val source: String, names: List<String>) {
    private val starts = IntArray(m.groupCount() + 1) { m.start(it) }
    private val ends = IntArray(m.groupCount() + 1) { m.end(it) }
    private val named: Map<String, Pair<Int, Int>> = names.associateWith { Pair(m.start(it), m.end(it)) }

    fun captured(name: String): String {
        val r = named[name] ?: return ""
        if (r.first < 0) return ""
        return source.substring(r.first, r.second)
    }

    fun captured(index: Int = 0): String {
        if (index < 0 || index >= starts.size || starts[index] < 0) return ""
        return source.substring(starts[index], ends[index])
    }

    fun capturedStart(name: String): Int {
        val r = named[name] ?: return -1
        return r.first
    }

    fun capturedLength(name: String): Int {
        val r = named[name] ?: return 0
        return if (r.first < 0) 0 else r.second - r.first
    }

    val capturedLength0: Int get() = ends[0] - starts[0]
    val capturedStart0: Int get() = starts[0]
}
