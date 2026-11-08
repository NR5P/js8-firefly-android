package com.js8firefly.core

/**
 * Matches decoded text against a watch list of callsigns, @GROUPs and words,
 * like JS8Call's highlight words: whole words only, case-insensitive.
 *
 * A plain word also matches the group of that name ("ARES" matches "@ARES"),
 * and a base callsign also matches its portable forms ("K4LMB" matches "K4LMB/P").
 */
object WatchMatcher {
    /** Upper-cases a watch term and drops a leading "#"; null if it is empty or more than one word. */
    fun normalize(term: String): String? {
        var t = term.qTrimmed().uppercase()
        while (t.startsWith("#")) t = t.substring(1)
        if (t.isEmpty() || t == "@" || t.any { it.isWhitespace() }) return null
        return t
    }

    private fun isWordChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '/' || c == '@'

    /** The words of a decoded line: runs of letters, digits, "/" and "@". */
    fun words(`in`: String): List<String> {
        val text = `in`.uppercase()
        val out = mutableListOf<String>()
        var start = -1
        for (i in text.indices) {
            if (isWordChar(text[i])) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(text.substring(start, i))
                start = -1
            }
        }
        if (start >= 0) out.add(text.substring(start))
        return out
    }

    fun matches(word: String, term: String): Boolean {
        if (word == term) return true
        if (term.startsWith("@")) return false
        if (word == "@$term") return true
        return word.contains("/") && !term.contains("/") && Varicode.baseCallsign(word) == term
    }

    /** Index of the first of `terms` (already normalized) found in `text`. */
    fun firstMatch(`in`: String, terms: List<String>): Int? {
        if (terms.isEmpty()) return null
        val ws = words(`in`)
        val i = terms.indexOfFirst { term -> ws.any { matches(word = it, term = term) } }
        return if (i < 0) null else i
    }
}
