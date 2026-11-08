package com.js8firefly.core

import java.io.File
import java.time.Instant

// Port of JS8Core/Station/Inbox.swift. Swift persisted the messages with
// JSONEncoder to a file URL; here `url` is a java.io.File and the same JSON layout
// (Swift's default Date encoding: seconds since 2001-01-01 as a number) is written
// with the small hand-written codec in `StationJson` (no JSON dependency).

/**
 * Store-and-forward message storage, equivalent to JS8Call's inbox.db3.
 * Message types: "UNREAD", "READ", "STORE" (held for another station) and
 * "DELIVERED".
 */
data class InboxMessage(
    var id: Int,
    var type: String,
    var utc: Instant,
    var from: String,
    var to: String,
    var path: String,
    var text: String,
    var cmd: String,
    var grid: String,
    var extra: String,
    var snr: Int,
    var dial: Long,
    var offset: Int,
    var submode: Int,
    var tdrift: Float,
    /** Callsigns that have received this group message. */
    var groupDelivered: List<String> = emptyList(),
) {
    internal fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "type" to type, "utc" to StationJson.encodeDate(utc), "from" to from, "to" to to,
        "path" to path, "text" to text, "cmd" to cmd, "grid" to grid, "extra" to extra, "snr" to snr,
        "dial" to dial, "offset" to offset, "submode" to submode, "tdrift" to tdrift,
        "groupDelivered" to groupDelivered,
    )

    companion object {
        /** Decodes one message; throws when a key is missing or has the wrong type (as Swift's Codable). */
        internal fun fromJson(o: Map<String, Any?>): InboxMessage = InboxMessage(
            id = StationJson.int(o, "id"),
            type = StationJson.string(o, "type"),
            utc = StationJson.decodeDate(StationJson.number(o, "utc")),
            from = StationJson.string(o, "from"),
            to = StationJson.string(o, "to"),
            path = StationJson.string(o, "path"),
            text = StationJson.string(o, "text"),
            cmd = StationJson.string(o, "cmd"),
            grid = StationJson.string(o, "grid"),
            extra = StationJson.string(o, "extra"),
            snr = StationJson.int(o, "snr"),
            dial = StationJson.number(o, "dial").toLong(),
            offset = StationJson.int(o, "offset"),
            submode = StationJson.int(o, "submode"),
            tdrift = StationJson.number(o, "tdrift").toFloat(),
            groupDelivered = StationJson.stringList(o, "groupDelivered"),
        )

        fun encodeList(messages: List<InboxMessage>): String = StationJson.write(messages.map { it.toJson() })

        /** Throws on malformed JSON. */
        fun decodeList(json: String): List<InboxMessage> {
            val arr = StationJson.parse(json) as? List<*> ?: throw IllegalArgumentException("expected array")
            return arr.map {
                @Suppress("UNCHECKED_CAST")
                fromJson(it as? Map<String, Any?> ?: throw IllegalArgumentException("expected object"))
            }
        }
    }
}

class Inbox(url: File?) {
    private var _messages: MutableList<InboxMessage> = mutableListOf()
    /** A snapshot of the stored messages. */
    val messages: List<InboxMessage> get() = synchronized(lock) { _messages.toList() }
    private var nextId = 1
    private val url: File? = url
    private val lock = Any()
    var onChange: (() -> Unit)? = null

    init {
        if (url != null) {
            val saved = runCatching { InboxMessage.decodeList(url.readText(Charsets.UTF_8)) }.getOrNull()
            if (saved != null) {
                _messages = saved.toMutableList()
                nextId = (saved.maxOfOrNull { it.id } ?: 0) + 1
            }
        }
    }

    private fun save() {
        val url = url
        if (url != null) {
            runCatching {
                val data = synchronized(lock) { InboxMessage.encodeList(_messages) }
                val tmp = File(url.parentFile, url.name + ".tmp")
                tmp.writeText(data, Charsets.UTF_8)
                if (!tmp.renameTo(url)) {
                    url.delete()
                    tmp.renameTo(url)
                }
            }
        }
        onChange?.invoke()
    }

    fun append(type: String, d: CommandDetail): Int {
        val id: Int
        synchronized(lock) {
            id = nextId
            nextId += 1
            _messages.add(
                InboxMessage(
                    id = id, type = type, utc = d.utc ?: Instant.now(), from = d.from, to = d.to, path = d.relayPath,
                    text = d.text, cmd = d.cmd, grid = d.grid, extra = d.extra, snr = d.snr, dial = d.dial,
                    offset = d.offset, submode = d.submode.rawValue, tdrift = d.tdrift,
                ),
            )
        }
        save()
        return id
    }

    fun message(id: Int): InboxMessage? = synchronized(lock) { _messages.firstOrNull { it.id == id } }

    fun setType(id: Int, type: String) {
        synchronized(lock) {
            val i = _messages.indexOfFirst { it.id == id }
            if (i >= 0) _messages[i] = _messages[i].copy(type = type)
        }
        save()
    }

    fun delete(id: Int) {
        synchronized(lock) { _messages.removeAll { it.id == id } }
        save()
    }

    fun markGroupDelivered(id: Int, to: String) {
        val callsign = to
        synchronized(lock) {
            val i = _messages.indexOfFirst { it.id == id }
            if (i >= 0 && !_messages[i].groupDelivered.contains(callsign)) {
                _messages[i] = _messages[i].copy(groupDelivered = _messages[i].groupDelivered + callsign)
            }
        }
        save()
    }

    private fun likeMatch(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    private fun stored(to: String): List<InboxMessage> {
        val callsign = to
        return messages.filter { it.type == "STORE" && likeMatch(it.to, callsign) && it.text.qTrimmed().isNotEmpty() }
            .sortedBy { it.id }
    }

    internal fun nextMessageId(`for`: String): Int {
        val callsign = `for`
        stored(to = callsign).firstOrNull()?.let { return it.id }
        stored(to = Varicode.baseCallsign(callsign)).firstOrNull()?.let { return it.id }
        return -1
    }

    internal fun lookaheadMessageId(`for`: String, after: Int): Int {
        val callsign = `for`
        stored(to = callsign).firstOrNull { it.id != after }?.let { return it.id }
        stored(to = Varicode.baseCallsign(callsign)).firstOrNull { it.id != after }?.let { return it.id }
        return -1
    }

    internal fun countUnread(`for`: String): Int = stored(to = `for`).size

    private fun groupStored(group: String, callsign: String, now: Instant): List<InboxMessage> {
        val cutoff = now.addingTimeInterval(-2.0 * 86400)
        return stored(to = group).filter { it.utc > cutoff && !it.groupDelivered.contains(callsign) }
    }

    internal fun nextGroupMessageId(group: String, `for`: String, now: Instant): Int =
        groupStored(group, `for`, now = now).firstOrNull()?.id ?: -1

    internal fun lookaheadGroupMessageId(group: String, `for`: String, after: Int, now: Instant): Int {
        val callsign = `for`
        groupStored(group, callsign, now = now).firstOrNull { it.id != after }?.let { return it.id }
        groupStored(group, Varicode.baseCallsign(callsign), now = now).firstOrNull { it.id != after }?.let { return it.id }
        return -1
    }

    internal fun countGroupUnread(group: String, `for`: String, now: Instant): Int =
        groupStored(group, `for`, now = now).size

    val unreadCount: Int get() = messages.count { it.type == "UNREAD" }
}

/**
 * Minimal JSON reader/writer for the station's persisted types (inbox, watch list),
 * matching what Swift's JSONEncoder/JSONDecoder produced. Objects are Map<String, Any?>,
 * arrays List<Any?>, numbers Double (parsed) or any Number (written).
 */
internal object StationJson {
    /** Swift's default Date strategy: seconds since 2001-01-01T00:00:00Z. */
    private const val referenceDate = 978307200.0

    fun encodeDate(d: Instant): Double = d.timeIntervalSince1970 - referenceDate
    fun decodeDate(v: Double): Instant = instantFromTimeIntervalSince1970(v + referenceDate)

    fun string(o: Map<String, Any?>, k: String): String = o[k] as? String ?: throw IllegalArgumentException("key $k")
    fun number(o: Map<String, Any?>, k: String): Double = (o[k] as? Number)?.toDouble() ?: throw IllegalArgumentException("key $k")
    fun int(o: Map<String, Any?>, k: String): Int {
        val v = number(o, k)
        if (v != Math.floor(v)) throw IllegalArgumentException("key $k")
        return v.toInt()
    }
    fun stringList(o: Map<String, Any?>, k: String): List<String> =
        (o[k] as? List<*> ?: throw IllegalArgumentException("key $k")).map { it as? String ?: throw IllegalArgumentException("key $k") }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Float -> sb.append(if (v == Math.floor(v.toDouble()).toFloat() && kotlin.math.abs(v) < 1e15f) v.toLong().toString() else v.toString())
            is Double -> sb.append(if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(e, sb)
                }
                sb.append(']')
            }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw IllegalArgumentException("trailing data")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun fail(): Nothing = throw IllegalArgumentException("bad JSON at $i")

        fun value(): Any? {
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                fail()
            }
        }

        fun arr(): List<Any?> {
            val l = mutableListOf<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                fail()
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail()
        }
    }
}
