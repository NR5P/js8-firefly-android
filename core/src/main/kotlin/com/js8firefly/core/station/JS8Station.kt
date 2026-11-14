package com.js8firefly.core

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The JS8Call station state machine: band and call activity, message
 * buffering, directed command handling, auto-replies, heartbeats and the
 * transmit queue. Ported from JS8Call's mainwindow processing functions
 * (processDecodeEvent, processRxActivity, processCompoundActivity,
 * processBufferedActivity, processCommandActivity, processTxQueue,
 * prepareNextMessageFrame) so that the station behaves the same on air.
 *
 * ## Threading
 * All methods must be called from one thread (as in Swift, where the app uses the
 * main actor). On Android drive it from the main thread or a single-threaded
 * dispatcher: call `tick()` about once per second, `handleDecode()` for each
 * decode, `pollTransmit(now)` near each period boundary and
 * `transmissionFinished()` when the audio for `currentTx` has played.
 *
 * ## Observation (same design as Swift: plain properties + callbacks)
 * - `onChange: (() -> Unit)?` is called after anything observable changed.
 * - `onNotify: ((StationNotification, String) -> Unit)?` reports events
 *   (directed, inbox, cq, hb, ack, newCall, txStarted, txFinished).
 * - `revision: StateFlow<Long>` is bumped right before every `onChange`, so
 *   Compose can `collectAsState()` it and re-read the properties.
 * Public collection properties (`bandActivity`, `callActivity`, `conversation`,
 * `bandConversation`, `heardGraphOutgoing`, `heardGraphIncoming`,
 * `pendingReplies`, `txMessageQueue`) return immutable snapshots, matching Swift's
 * value semantics: read them again after a change.
 *
 * ## Time
 * Swift `Date` is `java.time.Instant`. The clock is injected through the
 * constructor (`clock: () -> Instant`, default `Instant::now`), and
 * `pollTransmit`, `shouldDecode` and `bandActivityRows` take `now` explicitly.
 */
class JS8Station(
    // MARK: Configuration and operator state
    var config: StationConfig,
    val inbox: Inbox,
    val clock: () -> Instant = { Instant.now() },
) {
    var submode: JS8Submode = JS8Submode.normal
    var dialFrequency: Long = 14_078_000
    /** The selected audio offset (JS8Call's freq()). */
    var offset: Int = 1500
    var selectedCallsign: String = ""
    /** Master transmit enable (JS8Call's "Enable TX" button). */
    var txEnabled = true
    /** Heartbeat networking mode (Mode > Enable Heartbeat Networking). */
    var heartbeatNetworking = true
    /** Seconds of transmit delay before the period boundary (PTT lead). */
    var txDelay: Double = 0.2

    // MARK: Observable state

    private val _bandActivity = LinkedHashMap<Int, MutableList<ActivityDetail>>()
    val bandActivity: Map<Int, List<ActivityDetail>> get() = _bandActivity.mapValues { (_, v) -> v.map { it.copy() } }
    private val _callActivity = LinkedHashMap<String, CallDetail>()
    val callActivity: Map<String, CallDetail> get() = _callActivity.mapValues { it.value.copy() }
    private val _conversation = mutableListOf<ConversationLine>()
    val conversation: List<ConversationLine> get() = _conversation.toList()
    /** Every transmission heard anywhere in the passband, assembled per offset.
     *  The conversation pane only shows our offset and traffic directed to us. */
    private val _bandConversation = mutableListOf<ConversationLine>()
    val bandConversation: List<ConversationLine> get() = _bandConversation.toList()
    private val _heardGraphOutgoing = LinkedHashMap<String, MutableSet<String>>()
    val heardGraphOutgoing: Map<String, Set<String>> get() = _heardGraphOutgoing.mapValues { it.value.toSet() }
    private val _heardGraphIncoming = LinkedHashMap<String, MutableSet<String>>()
    val heardGraphIncoming: Map<String, Set<String>> get() = _heardGraphIncoming.mapValues { it.value.toSet() }
    private val _pendingReplies = mutableListOf<PendingReply>()
    val pendingReplies: List<PendingReply> get() = _pendingReplies.toList()
    private val _txMessageQueue = mutableListOf<QueuedMessage>()
    val txMessageQueue: List<QueuedMessage> get() = _txMessageQueue.toList()
    /** Text in the outgoing message box that has not been sent. */
    var draftText = ""

    var isTransmitting = false
        private set
    var currentTx: TxRequest? = null
        private set
    var lastTxStart: Instant? = null
        private set
    var lastTxStop: Instant? = null
        private set
    var txFramesRemaining = 0
        private set
    var txFramesSent = 0
        private set
    var lastTxMessage = ""
        private set

    // Loops
    var heartbeatLoopActive = false
        private set
    var cqLoopActive = false
        private set
    private var nextHeartbeat: Instant? = null
    private var nextCQ: Instant? = null

    // MARK: Callbacks

    var onNotify: ((StationNotification, String) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private val _revision = MutableStateFlow(0L)
    /** Bumped before every `onChange` call; observe it to know when to re-read state. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun changed() {
        _revision.value = _revision.value + 1
        onChange?.invoke()
    }

    // MARK: Private state

    private val messageBuffer = LinkedHashMap<Int, MessageBuffer>()
    private val rxActivityQueue = mutableListOf<ActivityDetail>()
    private val rxCommandQueue = mutableListOf<CommandDetail>()
    private data class FrameKey(val submode: JS8Submode, val frame: String)
    private var dupeCache = LinkedHashMap<FrameKey, Instant>()
    private data class DirectedEntry(val isAllcall: Boolean, val date: Instant)
    private val rxDirectedCache = LinkedHashMap<Int, DirectedEntry>()
    private var rxFrameBlockNumbers = LinkedHashMap<Int, Int>()
    /** Open (unfinished) lines in bandConversation by offset; -1 keys our TX. */
    private var bandOpenLines = LinkedHashMap<Int, Int>()
    private val allcallReplies = LinkedHashMap<String, Instant>()
    private val txFrameQueue = mutableListOf<Varicode.Frame>()
    private var totalTxMessage = ""
    private var nextFrame: Varicode.Frame? = null
    private var offsetBeforeAuto = 0
    private var rxDirty = false
    private var lastClosedMessageBufferOffset = 0

    // MARK: - Helpers

    internal val mycall: String get() = config.callsign.uppercase().qTrimmed()
    internal val mygrid4: String get() = config.grid.qLeft(4)

    internal fun isAllCallIncluded(text: String): Boolean = text.contains("@ALLCALL") || text.contains("@HB")
    internal fun isGroupCallIncluded(text: String): Boolean = config.groups.contains(text)

    fun isMyCallIncluded(text: String): Boolean {
        val base = Varicode.baseCallsign(mycall)
        if (base.isEmpty() || !text.contains(base)) return false
        val calls = Varicode.parseCallsigns(text)
        return calls.contains(base) || calls.contains(mycall)
    }

    fun isDirectedOffset(offset: Int): Pair<Boolean, Boolean> {
        val e = rxDirectedCache[offset / 10 * 10]
        if (e == null || clock().timeIntervalSince(e.date) >= 120) return Pair(false, false)
        return Pair(true, e.isAllcall)
    }

    internal fun markOffsetDirected(offset: Int, isAllcall: Boolean) {
        val now = clock()
        rxDirectedCache[offset / 10 * 10] = DirectedEntry(isAllcall, now)
        rxDirectedCache[offset / 10 * 10 + 10] = DirectedEntry(isAllcall, now)
    }

    internal fun clearOffsetDirected(offset: Int) {
        rxDirectedCache.remove(offset / 10 * 10)
        rxDirectedCache.remove(offset / 10 * 10 + 10)
    }

    companion object {
        private const val FIRST = 1 // Varicode.TransmissionType.first
        private const val LAST = 2  // Varicode.TransmissionType.last

        /** Why `to` can't hold a message stored at this station, or null when
         *  it can. Takes a callsign (base or compound) or an @GROUP. */
        fun storeRecipientProblem(to: String, mycall: String = ""): String? {
            val t = to.qTrimmed().uppercase()
            if (t.isEmpty()) return "Enter a callsign or @GROUP."
            if (t.startsWith("@")) {
                if (t.length < 2) return "Enter a group name after @."
                if (t == "@ALLCALL" || t == "@HB" || !Varicode.isGroupAllowed(t)) return "$t can't hold messages."
                return if (t.contains(" ")) "$t isn't a valid group." else null
            }
            if (!Varicode.isValidCallsign(t).valid) return "$t isn't a valid callsign."
            val me = mycall.qTrimmed().uppercase()
            if (me.isNotEmpty() && Varicode.baseCallsign(t) == Varicode.baseCallsign(me)) return "That's your own callsign."
            return null
        }

        fun since(date: Instant, now: Instant): String {
            val delta = now.timeIntervalSince(date).toInt()
            if (delta >= 86400) return "${delta / 86400}d"
            if (delta >= 3600) return "${delta / 3600}h"
            if (delta >= 60) return "${delta / 60}m"
            if (delta >= 15) return "${delta - delta % 15}s"
            return "now"
        }

        private val relayPathRe = QRegex("""\s([*]DE[*]|VIA)\s(?<callsign>\b(?<prefix>[A-Z0-9]{1,4}\/)?(?<base>([0-9A-Z])?([0-9A-Z])([0-9])([A-Z])?([A-Z])?([A-Z])?)(?<suffix>\/[A-Z0-9]{1,4})?)\b""")
        private val relayRe = QRegex("""^(?<callsign>\b(?<prefix>[A-Z0-9]{1,4}\/)?(?<base>([0-9A-Z])?([0-9A-Z])([0-9])([A-Z])?([A-Z])?([A-Z])?)(?<suffix>\/[A-Z0-9]{1,4})?(?<type>[> ]))\b""")
        private val macroRe = Regex("[<](?:[^>]+)[>]")

        internal fun parseRelayPathCallsigns(from: String, text: String): List<String> {
            val calls = mutableListOf<String>()
            for (m in relayPathRe.globalMatch(text)) calls.add(0, m.captured("callsign"))
            calls.add(0, from)
            return calls
        }
    }

    fun macroValues(): Map<String, String> {
        val values = linkedMapOf(
            "<MYCALL>" to mycall,
            "<MYGRID4>" to mygrid4,
            "<MYGRID12>" to config.grid.qLeft(12),
            "<MYINFO>" to config.info,
            "<MYCQ>" to config.cqMessage,
            "<MYREPLY>" to config.replyMessage,
            "<MYSTATUS>" to config.status,
            "<MYVERSION>" to "JS8 FIREFLY 1.0",
            "<MYIDLE>" to "0M",
        )
        val cd = _callActivity[selectedCallsign]
        if (cd != null) {
            values["<CALL>"] = selectedCallsign
            values["<TDELTA>"] = "${(1000 * cd.tdrift).toInt()} ms"
            if (cd.snr > -31) values["<SNR>"] = Varicode.formatSNR(cd.snr)
        }
        for (k in listOf("<MYINFO>", "<MYSTATUS>", "<MYCQ>", "<MYREPLY>")) {
            values[k] = replaceMacros(values[k] ?: "", values, prune = false)
        }
        return values
    }

    fun replaceMacros(text: String, values: Map<String, String>? = null, prune: Boolean): String {
        var out = text
        for ((k, v) in (values ?: macroValues()).entries.sortedBy { it.key }) {
            out = out.replace(k, v.uppercase())
        }
        if (prune) {
            out = macroRe.replace(out, "")
        }
        return out
    }

    // MARK: - Receive

    /** Feed one decoded frame (JS8Call's processDecodeEvent). */
    fun handleDecode(raw: JS8RawDecode, dial: Long? = null) {
        val now = clock()
        val decoded = DecodedText(raw)
        val key = FrameKey(decoded.submode, decoded.frame)
        val seen = dupeCache[key]
        if (seen != null && now.timeIntervalSince(seen) < 0.5 * decoded.submode.period.toDouble()) {
            return
        }
        dupeCache[key] = now
        rxDirty = true

        val freq = dial ?: dialFrequency
        val d = ActivityDetail()
        var cmd = CommandDetail()

        if (decoded.messageWords.isNotEmpty()) {
            val off = decoded.frequencyOffset
            if (_bandActivity[off] == null) {
                val range = decoded.submode.rxThreshold
                for (prev in (off - range)..(off + range)) {
                    val prevActivity = _bandActivity[prev] ?: continue
                    _bandActivity[off] = prevActivity
                    _bandActivity.remove(prev)
                    break
                }
            }

            d.isLowConfidence = decoded.isLowConfidence
            d.isCompound = decoded.isCompound
            d.isDirected = decoded.isDirectedMessage
            d.bits = decoded.bits
            d.dial = freq
            d.offset = off
            d.text = decoded.message
            d.utc = now
            d.snr = decoded.snr
            d.isBuffered = false
            d.submode = decoded.submode
            d.tdrift = decoded.dt

            if (d.bits and FIRST != 0 &&
                hasExistingMessageBuffer(decoded.submode, d.offset, drift = true) != null
            ) {
                messageBuffer.remove(d.offset)
            }

            if (hasExistingMessageBuffer(decoded.submode, d.offset, drift = true) != null &&
                !decoded.isCompound && !decoded.isDirectedMessage
            ) {
                d.isBuffered = true
                messageBuffer[d.offset]?.msgs?.add(d.copy())
            }

            rxActivityQueue.add(d.copy())
            val list = _bandActivity.getOrPut(off) { mutableListOf() }
            list.add(d.copy())
            while (list.size > 10) list.removeAt(0)
        }

        if (decoded.isCompound && !decoded.isDirectedMessage) {
            val cd = CallDetail()
            cd.call = decoded.compoundCall
            cd.grid = decoded.extra
            cd.snr = decoded.snr
            cd.dial = freq
            cd.offset = decoded.frequencyOffset
            cd.utc = now
            cd.bits = decoded.bits
            cd.submode = decoded.submode
            cd.tdrift = decoded.dt

            if (decoded.isHeartbeat) {
                cmd.from = cd.call
                cmd.snr = cd.snr
                cmd.bits = cd.bits
                cmd.grid = cd.grid
                cmd.dial = cd.dial
                cmd.offset = cd.offset
                cmd.utc = cd.utc
                cmd.tdrift = cd.tdrift
                cmd.submode = cd.submode
                if (decoded.isAlt) {
                    cmd.to = "@ALLCALL"
                    cmd.cmd = " CQ"
                    cmd.text = decoded.message
                    rxCommandQueue.add(cmd.copy())
                    onNotify?.invoke(StationNotification.cq, cd.call)
                } else {
                    cmd.to = "@HB"
                    cmd.cmd = " HEARTBEAT"
                    rxCommandQueue.add(cmd.copy())
                    onNotify?.invoke(StationNotification.hb, cd.call)
                }
            } else {
                hasExistingMessageBuffer(cd.submode, cd.offset, drift = true)
                messageBuffer.getOrPut(cd.offset) { MessageBuffer() }.compound.add(cd)
            }
        }

        if (decoded.isDirectedMessage) {
            val parts = decoded.directed
            cmd = CommandDetail()
            cmd.from = parts[0]
            cmd.to = parts[1]
            cmd.cmd = parts[2]
            cmd.dial = freq
            cmd.offset = decoded.frequencyOffset
            cmd.snr = decoded.snr
            cmd.utc = now
            cmd.bits = decoded.bits
            cmd.extra = if (parts.size > 2) parts.drop(3).joinToString(" ") else ""
            cmd.submode = decoded.submode
            cmd.tdrift = decoded.dt

            if ((Varicode.isCommandBuffered(cmd.cmd) && cmd.bits and LAST == 0) ||
                cmd.from == "<....>" || cmd.to == "<....>"
            ) {
                if (cmd.from != "<....>" && cmd.to != "<....>") {
                    val cd = CallDetail()
                    cd.call = cmd.from
                    cd.bits = cmd.bits
                    cd.snr = cmd.snr
                    cd.dial = cmd.dial
                    cd.offset = cmd.offset
                    cd.utc = now
                    cd.ackTimestamp = if (cmd.to == mycall) now else null
                    cd.tdrift = cmd.tdrift
                    cd.submode = cmd.submode
                    logCallActivity(cd)
                    logHeardGraph(cmd.from, cmd.to)
                }
                hasExistingMessageBuffer(cmd.submode, cmd.offset, drift = true)
                val buffer = messageBuffer[cmd.offset]?.deepCopy() ?: MessageBuffer()
                buffer.cmd = cmd.copy()
                buffer.msgs.clear()
                messageBuffer[cmd.offset] = buffer
            } else {
                rxCommandQueue.add(cmd.copy())
            }
        }
        changed()
    }

    private fun hasExistingMessageBuffer(submode: JS8Submode, offset: Int, drift: Boolean): Int? {
        if (messageBuffer[offset] != null) return offset
        val range = submode.rxThreshold
        for (prev in (offset - range)..(offset + range)) {
            val b = messageBuffer[prev] ?: continue
            if (drift) {
                messageBuffer[offset] = b
                messageBuffer.remove(prev)
            }
            return prev
        }
        return null
    }

    private fun hasExistingMessageBufferToMe(): Boolean =
        messageBuffer.values.any { b ->
            b.cmd.utc != null && (b.cmd.to == mycall || b.cmd.to == Varicode.baseCallsign(mycall))
        }

    internal fun logCallActivity(dIn: CallDetail) {
        val d = dIn.copy()
        if (d.call.qTrimmed().isEmpty() || d.call.contains(">")) return
        val old = _callActivity[d.call]
        if (old != null) {
            // Keep what we know: an empty grid, or a heartbeat's 4 characters
            // after a GRID reply gave 6, shouldn't lose the finer locator.
            val newGrid = d.grid.qTrimmed().uppercase()
            val oldGrid = old.grid.qTrimmed().uppercase()
            if (oldGrid.isNotEmpty() && (newGrid.isEmpty() || (oldGrid.length > newGrid.length && oldGrid.startsWith(newGrid)))) {
                d.grid = old.grid
            }
            if (d.ackTimestamp == null) d.ackTimestamp = old.ackTimestamp
            if (d.cqTimestamp == null) d.cqTimestamp = old.cqTimestamp
            _callActivity[d.call] = d
        } else {
            _callActivity[d.call] = d
            onNotify?.invoke(StationNotification.newCall, d.call)
        }
    }

    internal fun logHeardGraph(from: String, to: String) {
        _heardGraphOutgoing.getOrPut(mycall) { LinkedHashSet() }.add(from)
        _heardGraphIncoming.getOrPut(from) { LinkedHashSet() }.add(mycall)
        if (to == "@ALLCALL") return
        _heardGraphOutgoing.getOrPut(from) { LinkedHashSet() }.add(to)
        _heardGraphIncoming.getOrPut(to) { LinkedHashSet() }.add(from)
    }

    // MARK: - Periodic processing

    /** Call about once per second. */
    fun tick() {
        val now = clock()
        processActivity()
        processLoops(now = now)
        processTxQueue()
        _pendingReplies.removeAll { it.expires < now }
        dupeCache = LinkedHashMap(dupeCache.filter { now.timeIntervalSince(it.value) <= it.key.submode.period.toDouble() })
        changed()
    }

    fun processActivity() {
        processRxActivity()
        processIdleActivity()
        processCompoundActivity()
        processBufferedActivity()
        processCommandActivity()
        rxDirty = false
    }

    internal fun processRxActivity() {
        while (rxActivityQueue.isNotEmpty()) {
            val d = rxActivityQueue.removeAt(0).copy()
            var shouldDisplay = abs(d.offset - offset) <= d.submode.rxThreshold

            val prev = hasExistingMessageBuffer(d.submode, d.offset, drift = false)
            val buffer = prev?.let { messageBuffer[it] }
            if (buffer != null && (buffer.cmd.to == mycall || isGroupCallIncluded(buffer.cmd.to))) {
                d.isBuffered = true
                shouldDisplay = true
                val last = buffer.compound.lastOrNull()
                if (last != null) {
                    d.text = "${last.call}: ${d.text}"
                    d.utc = minOf(d.utc, last.utc)
                }
            } else if (d.isDirected && d.text.contains("<....>")) {
                continue
            } else if (d.isDirected && (d.text.contains(": HB ") || d.text.contains(": @ALLCALL HB"))) {
                continue
            }

            logBandText(d.text, offset = d.offset, date = d.utc, snr = d.snr, submode = d.submode, bits = d.bits, isTx = false)

            if (d.bits and FIRST != 0 && !d.isDirected && !d.isCompound) {
                val calls = Varicode.parseCallsigns(d.text)
                val theirCall = calls.firstOrNull()
                if (theirCall != null && d.text.startsWith(theirCall) && d.text.qMid(theirCall.u16Count, 1) == ":") {
                    val cd = CallDetail()
                    cd.call = theirCall
                    cd.dial = d.dial
                    cd.offset = d.offset
                    cd.snr = d.snr
                    cd.bits = d.bits
                    cd.tdrift = d.tdrift
                    cd.utc = d.utc
                    cd.submode = d.submode
                    logCallActivity(cd)
                }
            }

            if (!shouldDisplay) continue

            val isFirst = d.bits and FIRST != 0
            val isLast = d.bits and LAST != 0
            if (isLast) d.text = "${Varicode.rstrip(d.text)} ${config.eot} "

            displayText(d.text, offset = d.offset, date = d.utc, isTx = false, isNewLine = isFirst, isLast = isLast)

            if (!isAllCallIncluded(d.text)) cancelCQLoop()
            if (isLast) clearOffsetDirected(d.offset)
            if (isLast && !d.isBuffered) rxFrameBlockNumbers.remove(d.offset)
        }
    }

    internal fun processIdleActivity() {
        val now = clock()
        for ((off, activity) in _bandActivity.entries.map { Pair(it.key, it.value.toList()) }) {
            val last = activity.lastOrNull() ?: continue
            if (last.bits and LAST != 0) continue
            if (last.text == config.mfi) continue
            if (now.timeIntervalSince(last.utc) < last.submode.period.toDouble() * 1.5) continue

            val d = ActivityDetail()
            d.text = config.mfi
            d.utc = last.utc
            d.snr = last.snr
            d.tdrift = last.tdrift
            d.dial = last.dial
            d.offset = last.offset
            d.submode = last.submode
            if (hasExistingMessageBuffer(d.submode, off, drift = false) != null) {
                messageBuffer[off]?.msgs?.add(d.copy())
            }
            rxActivityQueue.add(d.copy())
            _bandActivity[off]?.add(d.copy())
        }
    }

    internal fun processCompoundActivity() {
        for (freq in messageBuffer.keys.toList()) {
            val buffer = messageBuffer[freq]?.deepCopy() ?: continue
            if (buffer.compound.isEmpty()) continue
            val bits = buffer.cmd.bits
            val validBits = bits == 0 || bits and 1 != 0 || bits and 2 != 0 || bits and 4 != 0
            if (!validBits) continue
            if (buffer.cmd.from == "<....>" && buffer.cmd.to == "<....>" && buffer.compound.size < 2) continue
            if ((buffer.cmd.from == "<....>" || buffer.cmd.to == "<....>") && buffer.compound.isEmpty()) continue

            if (buffer.cmd.from == "<....>") {
                val d = buffer.compound.removeAt(0)
                buffer.cmd.from = d.call
                buffer.cmd.grid = d.grid
                buffer.cmd.isCompound = true
                buffer.cmd.utc = minDate(buffer.cmd.utc, d.utc)
                if (d.bits and LAST != 0) buffer.cmd.bits = d.bits
            }
            if (buffer.cmd.to == "<....>") {
                val d = buffer.compound.removeAt(0)
                buffer.cmd.to = d.call
                buffer.cmd.isCompound = true
                buffer.cmd.utc = minDate(buffer.cmd.utc, d.utc)
                if (d.bits and LAST != 0) buffer.cmd.bits = d.bits
            }
            messageBuffer[freq] = buffer.deepCopy()

            if (buffer.cmd.bits and LAST == 0) continue

            var dt = buffer.cmd.utc
            for (c in buffer.compound) dt = minDate(dt, c.utc)
            for (m in buffer.msgs) dt = minDate(dt, m.utc)
            buffer.cmd.utc = dt

            rxCommandQueue.add(buffer.cmd.copy())
            messageBuffer.remove(freq)
            lastClosedMessageBufferOffset = freq
        }
    }

    private fun minDate(a: Instant?, b: Instant): Instant = a?.let { minOf(it, b) } ?: b

    internal fun processBufferedActivity() {
        val now = clock()
        for (freq in messageBuffer.keys.toList()) {
            val buffer = messageBuffer[freq]?.deepCopy() ?: continue
            var dt = now.addingTimeInterval(-86400.0)
            buffer.cmd.utc?.let { dt = maxOf(dt, it) }
            buffer.compound.lastOrNull()?.let { dt = maxOf(dt, it.utc) }
            buffer.msgs.lastOrNull()?.let { dt = maxOf(dt, it.utc) }
            val age = now.timeIntervalSince(dt)

            if (age > 60 && buffer.msgs.isNotEmpty()) {
                val i = buffer.msgs.size - 1
                buffer.msgs[i] = buffer.msgs[i].copy(bits = buffer.msgs[i].bits or LAST)
                messageBuffer[freq] = buffer.deepCopy()
            }
            if (age > 90) {
                messageBuffer.remove(freq)
                continue
            }
            if (buffer.msgs.isEmpty()) continue
            if (buffer.msgs.last().bits and LAST == 0) continue

            var message = Varicode.rstrip(buffer.msgs.joinToString("") { it.text })
            var valid: Boolean
            if (Varicode.isCommandBuffered(buffer.cmd.cmd)) {
                val size = Varicode.isCommandChecksumed(buffer.cmd.cmd)
                if (size == 32) {
                    message = Varicode.lstrip(message)
                    val checksum = message.qRight(6)
                    message = message.qLeft(message.u16Count - 7)
                    valid = Varicode.checksum32Valid(checksum, message)
                } else if (size == 16) {
                    message = Varicode.lstrip(message)
                    val checksum = message.qRight(3)
                    message = message.qLeft(message.u16Count - 4)
                    valid = Varicode.checksum16Valid(checksum, message)
                } else {
                    valid = true
                }
            } else {
                valid = true
            }

            if (valid) {
                buffer.cmd.bits = buffer.cmd.bits or LAST
                buffer.cmd.text = message
                buffer.cmd.isBuffered = true
                rxCommandQueue.add(buffer.cmd.copy())
            }
            messageBuffer.remove(freq)
            lastClosedMessageBufferOffset = freq
        }
    }

    internal fun processCommandActivity() {
        val now = clock()
        while (rxCommandQueue.isNotEmpty()) {
            val d = rxCommandQueue.removeAt(0).copy()
            val isAllCall = isAllCallIncluded(d.to)
            val isGroupCall = isGroupCallIncluded(d.to) || d.to == "@APRSIS"

            if (d.from == "<....>" || d.to == "<....>") continue
            if (!Varicode.isCommandAllowed(d.cmd)) continue

            val toMe = d.to == mycall || d.to == Varicode.baseCallsign(mycall)

            val cd = CallDetail()
            cd.call = d.from
            cd.grid = d.grid
            cd.snr = d.snr
            cd.dial = d.dial
            cd.offset = d.offset
            cd.bits = d.bits
            cd.ackTimestamp = if (d.text.contains(": ACK") || toMe) d.utc else null
            cd.utc = d.utc ?: now
            cd.tdrift = d.tdrift
            cd.submode = d.submode
            logCallActivity(cd)
            logHeardGraph(d.from, d.to)

            var onSent: (() -> Unit)? = null

            if (d.cmd == " HEARING") {
                for (call in Varicode.parseCallsigns(d.text)) logHeardGraph(d.from, call)
            }
            if (d.cmd == " GRID") {
                for (grid in Varicode.parseGrids(d.text)) {
                    val g = CallDetail()
                    g.bits = d.bits
                    g.call = d.from
                    g.dial = d.dial
                    g.offset = d.offset
                    g.grid = grid
                    g.snr = d.snr
                    g.utc = d.utc ?: now
                    g.tdrift = d.tdrift
                    g.submode = d.submode
                    logCallActivity(g)
                }
            }

            val baseList = mutableListOf("${d.to}${d.cmd}")
            if (d.extra.isNotEmpty()) baseList.add(d.extra)
            if (d.text.isNotEmpty()) baseList.add(d.text)
            var baseText = baseList.joinToString(" ")
            if (d.bits and LAST != 0) {
                baseText = "${Varicode.rstrip(baseText)} ${config.eot} "
            }
            val text = "${d.from}: $baseText"

            if (isAllCall && config.avoidAllcall && d.cmd != " CQ" && d.cmd != " HB" && d.cmd != " HEARTBEAT") continue
            if (!isAllCall && !toMe && !isGroupCall) continue

            var shouldDisplay = true
            if (isAllCall && (d.cmd != " " || text.contains("@HB HEARTBEAT"))) shouldDisplay = false
            if (shouldDisplay) {
                displayText(
                    text, offset = d.offset, date = d.utc ?: now, isTx = false, isNewLine = true, isLast = false,
                    replaceSameSender = if (!d.cmd.contains(" ACK") && !d.cmd.contains(" SNR")) d.from else null,
                )
                if (!isAllCall) {
                    cancelCQLoop()
                    onNotify?.invoke(StationNotification.directed, text)
                }
            }

            if (config.autoWhitelist.isNotEmpty() &&
                !(config.autoWhitelist.contains(d.from) || config.autoWhitelist.contains(Varicode.baseCallsign(d.from)))
            ) {
                continue
            }
            if (config.autoBlacklist.contains(d.from) || config.autoBlacklist.contains(Varicode.baseCallsign(d.from))) {
                continue
            }
            if (isAllCall) {
                val last = allcallReplies[d.from]
                if (last != null && now.timeIntervalSince(last) / 60 < 55) continue
            }

            if (Varicode.isCommandAutoreply(d.cmd) && d.relayPath.isNotEmpty() && !d.cmd.startsWith(" MSG") && !d.cmd.startsWith(" QUERY")) {
                d.from = d.relayPath
            }

            var reply = ""
            val priority = Priority.normal
            val freq = -1

            if (d.cmd == " SNR?" && !isAllCall) {
                reply = "${d.from} SNR ${Varicode.formatSNR(d.snr)}"
            } else if (d.cmd == " INFO?" && !isAllCall) {
                if (config.info.isEmpty()) continue
                reply = "${d.from} INFO ${replaceMacros(config.info, prune = true)}"
            } else if (d.cmd == " STATUS?" && !isAllCall) {
                if (config.status.isEmpty()) continue
                reply = "${d.from} STATUS ${replaceMacros(config.status, prune = true)}"
            } else if (d.cmd == " GRID?" && !isAllCall) {
                if (config.grid.isEmpty()) continue
                reply = "${d.from} GRID ${config.grid}"
            } else if (d.cmd == " HEARING?" && !isAllCall) {
                val calls = _callActivity.keys.sortedWith { a, b -> _callActivity[b]!!.utc.compareTo(_callActivity[a]!!.utc) }
                val lines = mutableListOf<String>()
                for (call in calls) {
                    if (!(lines.size < 4 && call != d.from)) continue
                    val c = _callActivity[call]!!
                    if (config.callsignAgingMinutes > 0 && now.timeIntervalSince(c.utc) / 60 >= config.callsignAgingMinutes.toDouble()) continue
                    lines.add(c.call)
                }
                reply = (listOf("${d.from} HEARING") + lines).joinToString(" ")
            } else if (d.cmd == ">" && !isAllCall && !config.relayOff) {
                var relayText = d.text
                val m = relayRe.match(relayText)
                if (m != null && !isGroupCall) {
                    if (m.captured("type") != ">") {
                        val start = m.capturedStart("type")
                        relayText = relayText.substring(0, start) + ">" + relayText.substring(start + m.capturedLength("type"))
                    }
                    reply = "$relayText *DE* ${d.from}"
                } else if (!d.text.startsWith("ACK")) {
                    val calls = parseRelayPathCallsigns(d.from, d.text)
                    for (call in calls) {
                        val r = CallDetail()
                        r.call = call
                        r.snr = -64
                        r.dial = d.dial
                        r.offset = d.offset
                        r.through = d.from
                        r.utc = now
                        r.tdrift = d.tdrift
                        r.submode = d.submode
                        logCallActivity(r)
                    }
                    d.relayPath = calls.joinToString(">")
                    reply = "${d.relayPath} ACK"

                    val relayed = d.text.split(" ").toMutableList()
                    var first = relayed.firstOrNull()
                    if (first != null) {
                        var valid = Varicode.isCommandAllowed(first)
                        if (!valid) {
                            first = " $first"
                            valid = Varicode.isCommandAllowed(first)
                            if (valid) relayed.removeAt(0)
                        }
                        if (relayed.isNotEmpty()) {
                            if (first == " MSG") {
                                val second = relayed[0]
                                if (second == "TO:") {
                                    first = " MSG TO:"
                                    relayed.removeAt(0)
                                } else if (second.startsWith("TO:")) {
                                    first = " MSG TO:"
                                    relayed[0] = second.substring(3)
                                }
                            } else if (first == " QUERY") {
                                val second = relayed[0]
                                if (second == "MSGS" || second == "MSGS?") {
                                    first = " QUERY MSGS"
                                    relayed.removeAt(0)
                                } else if (second == "CALL") {
                                    first = " QUERY CALL"
                                    relayed.removeAt(0)
                                }
                            }
                        }
                        if (Varicode.isCommandAllowed(first) && Varicode.isCommandAutoreply(first)) {
                            val rd = CommandDetail()
                            rd.bits = d.bits
                            rd.cmd = first
                            rd.dial = d.dial
                            rd.offset = d.offset
                            rd.from = d.from
                            rd.relayPath = d.relayPath
                            rd.text = relayed.joinToString(" ")
                            rd.to = d.to
                            rd.utc = d.utc
                            rxCommandQueue.add(0, rd)
                            continue
                        }
                    }
                }
            } else if (d.cmd == " MSG TO:" && !isAllCall) {
                val segs = d.text.split(" ").toMutableList()
                if (segs.isEmpty()) continue
                val to = segs.removeAt(0)
                val msgText = segs.joinToString(" ").qTrimmed()
                if (d.to == "@APRSIS" || config.relayOff) continue
                val calls = parseRelayPathCallsigns(d.from, msgText)
                d.relayPath = calls.joinToString(">")
                val store = d.copy()
                store.text = msgText
                store.to = Varicode.baseCallsign(to)
                inbox.append(type = "STORE", d = store)
                reply = "${if (calls.size > 1) d.relayPath else d.from} ACK"
            } else if (d.cmd == " AGN?" && !isAllCall && !isGroupCall && lastTxMessage.isNotEmpty()) {
                reply = Varicode.rstrip(lastTxMessage)
            } else if ((d.cmd == " HB" || d.cmd == " HEARTBEAT") && submode.canSendHeartbeat && heartbeatNetworking &&
                config.autoReply && config.heartbeatAcknowledgements
            ) {
                if (messageBuffer.isNotEmpty()) continue
                if (config.heartbeatQSOPause && selectedCallsign.isNotEmpty()) continue
                if (config.hbBlacklist.contains(d.from) || config.hbBlacklist.contains(Varicode.baseCallsign(d.from))) continue

                var extra = ""
                var mid = inbox.nextMessageId(`for` = d.from)
                var pending = inbox.countUnread(`for` = d.from)
                if (isGroupCall) pending += inbox.countGroupUnread(d.to, `for` = d.from, now = now)
                pending -= 1
                if (mid != -1) {
                    extra = if (pending > 0) "MSG ID $mid +$pending)" else "MSG ID $mid"
                } else if (isGroupCall) {
                    mid = inbox.nextGroupMessageId(d.to, `for` = d.from, now = now)
                    if (mid != -1) extra = if (pending > 0) "MSG ID $mid +$pending)" else "MSG ID $mid"
                }
                sendHeartbeatAck(to = d.from, snr = d.snr, extra = extra)
                if (isAllCall) allcallReplies[d.from] = now
                continue
            } else if (d.cmd == " HEARTBEAT SNR") {
                continue
            } else if (d.cmd == " CQ") {
                val c = CallDetail()
                c.bits = d.bits
                c.call = d.from
                c.dial = d.dial
                c.offset = d.offset
                c.grid = d.grid
                c.snr = d.snr
                c.tdrift = d.tdrift
                c.submode = d.submode
                c.utc = d.utc ?: now
                c.cqTimestamp = d.utc ?: now
                logCallActivity(c)
                logHeardGraph(d.from, d.to)
                continue
            } else if (d.cmd == " MSG" && !isAllCall) {
                if (d.to == "@APRSIS") continue
                val calls = parseRelayPathCallsigns(d.from, d.text)
                d.cmd = " MSG "
                d.relayPath = calls.joinToString(">")
                inbox.append(type = "UNREAD", d = d.copy())
                onNotify?.invoke(StationNotification.inbox, "${d.from}: ${d.text}")
                reply = "${if (calls.size > 1) d.relayPath else d.from} ACK"
            } else if (d.cmd == " ACK" && !isAllCall) {
                onNotify?.invoke(StationNotification.ack, d.from)
                continue
            } else if (d.cmd == " CMD" && !isAllCall) {
                continue
            } else if (d.cmd == " QUERY" && !isAllCall) {
                var who = d.from
                var replyPath = d.from
                if (d.relayPath.contains(">")) {
                    who = d.relayPath.split(">").lastOrNull() ?: d.from
                    replyPath = d.relayPath
                }
                val segs = d.text.split(" ").toMutableList()
                if (segs.isEmpty()) continue
                val qcmd = segs.removeAt(0)
                if (qcmd == "MSG" && segs.isNotEmpty()) {
                    val mid = segs[0].toIntOrNull() ?: continue
                    val msg = inbox.message(mid) ?: continue
                    val from = msg.from.qTrimmed()
                    val to = msg.to.qTrimmed()
                    val isGroupMsg = to.startsWith("@")
                    if (!isGroupMsg && to != who && to != Varicode.baseCallsign(who)) continue
                    val msgText = msg.text.qTrimmed()
                    if (msgText.isEmpty()) continue
                    val inboxRef = inbox
                    if (!isGroupMsg) {
                        onSent = { inboxRef.setType(mid, "DELIVERED") }
                    } else {
                        val whoCopy = who
                        onSent = { inboxRef.markGroupDelivered(mid, to = whoCopy) }
                    }
                    var pending = inbox.countUnread(`for` = who)
                    if (isGroupCall) pending += inbox.countGroupUnread(d.to, `for` = who, now = now)
                    pending -= 2
                    var look = inbox.lookaheadMessageId(`for` = who, after = mid)
                    if (look == -1 && isGroupMsg) look = inbox.lookaheadGroupMessageId(d.to, `for` = who, after = mid, now = now)
                    reply = if (look != -1) {
                        if (pending > 0) {
                            "$replyPath MSG $msgText FROM $from NEXT MSG ID $look +$pending"
                        } else {
                            "$replyPath MSG $msgText FROM $from NEXT MSG ID $look"
                        }
                    } else {
                        "$replyPath MSG $msgText FROM $from"
                    }
                }
            } else if (d.cmd == " QUERY MSGS" && config.autoReply) {
                var who = d.from
                var replyPath = d.from
                if (d.relayPath.contains(">")) {
                    who = d.relayPath.split(">").lastOrNull() ?: d.from
                    replyPath = d.relayPath
                }
                var mid = inbox.nextMessageId(`for` = who)
                var pending = inbox.countUnread(`for` = who)
                if (isGroupCall) pending += inbox.countGroupUnread(d.to, `for` = d.from, now = now)
                pending -= 1
                if (mid != -1) {
                    reply = if (pending > 0) "$replyPath YES MSG ID $mid +$pending" else "$replyPath YES MSG ID $mid"
                } else if (isGroupCall) {
                    mid = inbox.nextGroupMessageId(d.to, `for` = d.from, now = now)
                    if (mid != -1) {
                        reply = if (pending > 0) "$replyPath YES MSG ID $mid +$pending" else "$replyPath YES MSG ID $mid"
                    }
                }
                if (!isAllCall && reply.isEmpty()) reply = "$replyPath NO"
            } else if (d.cmd == " QUERY CALL" && config.autoReply) {
                var replyPath = d.from
                if (d.relayPath.contains(">")) replyPath = d.relayPath
                if (d.text.isEmpty()) continue
                val calls = Varicode.parseCallsigns(d.text)
                val baseCall = calls.firstOrNull() ?: continue
                val replies = mutableListOf<String>()
                for (c in _callActivity.values) {
                    if (config.callsignAgingMinutes > 0 && now.timeIntervalSince(c.utc) / 60 >= config.callsignAgingMinutes.toDouble()) continue
                    if (baseCall == c.call || baseCall == Varicode.baseCallsign(c.call)) {
                        replies.add("${Varicode.formatSNR(c.snr)} (${since(c.utc, now = now)})".qTrimmed())
                        break
                    }
                }
                if (replies.isNotEmpty()) replies.add(0, "$replyPath YES")
                reply = replies.joinToString(" ")
            }

            if (reply.isEmpty()) continue
            if (!config.autoReply && isAllCall) continue
            if (draftText.isNotEmpty()) continue
            if (hasExistingMessageBufferToMe()) continue
            if (isAllCall) allcallReplies[d.from] = now

            val message = QueuedMessage(date = now, priority = priority, message = reply, offset = freq, onSent = onSent)
            if (config.autoReplyConfirmation) {
                _pendingReplies.add(PendingReply(created = now, expires = now.addingTimeInterval(90.0), message = message))
            } else {
                enqueue(message)
            }
        }
    }

    // MARK: - Conversation display (displayTextForFreq)

    internal fun displayText(
        text: String, offset: Int, date: Instant, isTx: Boolean, isNewLine: Boolean, isLast: Boolean,
        replaceSameSender: String? = null,
    ) {
        var freq = offset
        val low = freq / 10 * 10
        val high = low + 10
        var block: Int? = null
        val bFreq = rxFrameBlockNumbers[freq]
        val bLow = rxFrameBlockNumbers[low]
        val bHigh = rxFrameBlockNumbers[high]
        if (bFreq != null) {
            block = bFreq
        } else if (bLow != null) {
            block = bLow
            freq = low
        } else if (bHigh != null) {
            block = bHigh
            freq = high
        }
        if (isNewLine) {
            rxFrameBlockNumbers.remove(freq)
            rxFrameBlockNumbers.remove(low)
            rxFrameBlockNumbers.remove(high)
            block = null
        }

        // Duplicate ACK/HB SNR suppression.
        if ((text.contains(" ACK ") || text.contains(" HEARTBEAT SNR ")) &&
            _conversation.takeLast(20).any { it.text == text && abs(it.date.timeIntervalSince(date)) < 1 }
        ) {
            return
        }
        // The same frame can reach the display both from the offset feed and as
        // a directed command; show it once.
        val trimmed = text.qTrimmed()
        if (isNewLine && _conversation.takeLast(10).any { !it.isTx && it.date == date && it.text.qTrimmed() == trimmed }) {
            return
        }
        if (replaceSameSender != null) {
            val sender = replaceSameSender
            val idx = _conversation.indexOfLast { !it.isTx && it.date == date && it.text.startsWith("$sender:") }
            if (idx >= 0) _conversation.removeAt(idx)
        }

        var index: Int
        val b = block
        if (b != null && b < _conversation.size && !_conversation[b].text.contains(config.eot)) {
            _conversation[b] = _conversation[b].copy(text = _conversation[b].text + text, isComplete = isLast)
            index = b
        } else {
            _conversation.add(ConversationLine(date = date, offset = freq, text = text, isTx = isTx, isComplete = isLast))
            index = _conversation.size - 1
        }
        if (_conversation.size > 2000) {
            val drop = _conversation.size - 2000
            _conversation.subList(0, drop).clear()
            index -= drop
            rxFrameBlockNumbers = compactShift(rxFrameBlockNumbers, drop)
        }

        if (isLast) {
            rxFrameBlockNumbers.clear()
        } else {
            rxFrameBlockNumbers[freq] = index
            rxFrameBlockNumbers[low] = index
            rxFrameBlockNumbers[high] = index
        }
    }

    private fun compactShift(m: Map<Int, Int>, drop: Int): LinkedHashMap<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for ((k, v) in m) if (v - drop >= 0) out[k] = v - drop
        return out
    }

    fun clearConversation() {
        _conversation.clear()
        rxFrameBlockNumbers.clear()
        changed()
    }

    /** Assemble frames into one line per transmission, for every offset. */
    internal fun logBandText(textIn: String, offset: Int, date: Instant, snr: Int?, submode: JS8Submode, bits: Int, isTx: Boolean) {
        val isFirst = bits and FIRST != 0
        val isLast = bits and LAST != 0
        val isIdle = !isTx && textIn == config.mfi
        var text = textIn
        if (isLast) text = "${Varicode.rstrip(text)} ${config.eot} "

        // Follow a stream that drifts a few Hz between frames.
        val key: Int? = if (isTx) {
            if (bandOpenLines[-1] != null) -1 else null
        } else {
            bandOpenLines.keys
                .filter { it >= 0 && abs(it - offset) <= submode.rxThreshold }
                .minByOrNull { abs(it - offset) }
        }

        var index: Int
        val i = key?.let { bandOpenLines[it] }
        if (!isFirst && key != null && i != null && i < _bandConversation.size && !_bandConversation[i].isComplete) {
            bandOpenLines.remove(key)
            var line = _bandConversation[i].copy(text = _bandConversation[i].text + text, isComplete = isLast || isIdle)
            if (snr != null) line = line.copy(snr = snr)
            _bandConversation[i] = line
            index = i
        } else {
            if (key != null) bandOpenLines.remove(key)
            if (isIdle) return
            _bandConversation.add(
                ConversationLine(date = date, offset = offset, text = text, isTx = isTx, isComplete = isLast, snr = snr, submode = submode),
            )
            index = _bandConversation.size - 1
        }
        if (_bandConversation.size > 1000) {
            val drop = _bandConversation.size - 1000
            _bandConversation.subList(0, drop).clear()
            index -= drop
            bandOpenLines = compactShift(bandOpenLines, drop)
        }
        if (!isLast && !isIdle) bandOpenLines[if (isTx) -1 else offset] = index
    }

    fun clearBandConversation() {
        _bandConversation.clear()
        bandOpenLines.clear()
        changed()
    }

    fun clearBandActivity() {
        _bandActivity.clear()
        changed()
    }

    fun clearCallActivity() {
        _callActivity.clear()
        changed()
    }

    // MARK: - Transmit queue

    fun enqueue(m: QueuedMessage) {
        _txMessageQueue.add(m)
        // Stable sort, like Swift's sort for these keys.
        _txMessageQueue.sortWith { a, b ->
            if (a.priority != b.priority) b.priority.compareTo(a.priority) else a.date.compareTo(b.date)
        }
    }

    fun enqueue(priority: Int, message: String, offset: Int, onSent: (() -> Unit)? = null) {
        enqueue(QueuedMessage(date = clock(), priority = priority, message = message, offset = offset, onSent = onSent))
    }

    fun approvePendingReply(id: UUID) {
        val i = _pendingReplies.indexOfFirst { it.id == id }
        if (i < 0) return
        enqueue(_pendingReplies.removeAt(i).message)
        processTxQueue()
    }

    fun rejectPendingReply(id: UUID) {
        _pendingReplies.removeAll { it.id == id }
    }

    val isMessageQueuedForTransmit: Boolean get() = isTransmitting || txFramesRemaining > 0 || nextFrame != null

    internal fun processTxQueue() {
        val head = _txMessageQueue.firstOrNull() ?: return
        val f = if (head.offset == -1) offset else head.offset
        if (f <= 0) return
        if (txFrameQueue.isNotEmpty()) return
        if (draftText.isNotEmpty()) return
        if (isMessageQueuedForTransmit) return

        val isAuto = head.priority >= Priority.high || head.message.contains(" HEARTBEAT ") ||
            head.message.contains(" HB ") || head.message.contains(" ACK ") || config.autoReply
        if (isAuto && !txEnabled) {
            _txMessageQueue.removeAt(0)
            return
        }
        val last = lastTxStart
        if (head.priority <= Priority.low && last != null && clock().timeIntervalSince(last) <= 30) {
            return
        }
        val message = _txMessageQueue.removeAt(0)

        if (isAuto) {
            if (offsetBeforeAuto == 0) offsetBeforeAuto = offset
            offset = f
            send(message.message)
        } else {
            draftText = message.message
        }
        message.onSent?.invoke()
    }

    /**
     * JS8Call's "Store Message…": keep a message at this station for `to`
     * without transmitting. It is held as STORE under the base callsign, the
     * same as a message relayed here with MSG TO:, so `to` hears it's waiting
     * in our HB ACK / QUERY MSGS replies and fetches it with QUERY MSG <id>.
     * Returns the inbox id, or null when nothing was stored.
     */
    fun storeMessage(to: String, text: String): Int? {
        val t = to.qTrimmed().uppercase()
        val body = text.split('\n', '\r').joinToString(" ").qTrimmed().uppercase()
        if (storeRecipientProblem(t, mycall = mycall) != null || body.isEmpty() || mycall.isEmpty()) return null
        val d = CommandDetail()
        d.cmd = " MSG "
        d.from = mycall
        d.to = if (t.startsWith("@")) t else Varicode.baseCallsign(t)
        d.relayPath = mycall
        d.text = body
        d.utc = clock()
        d.dial = dialFrequency
        d.offset = offset
        d.submode = submode
        return inbox.append(type = "STORE", d = d)
    }

    /** Compose and start sending a message (JS8Call's Send button). */
    fun send(text: String): Boolean {
        val t = text.qTrimmed()
        if (t.isEmpty() || mycall.isEmpty()) return false
        if (!txEnabled) return false
        if (t.u16Count > 5) {
            val firstChar = t.substring(0, Character.charCount(t.codePointAt(0)))
            if (t.replace(firstChar, "").qTrimmed().isEmpty()) return false
        }

        resetMessageTransmitQueue()
        val frames = Varicode.buildMessageFrames(
            mycall = mycall, mygrid = mygrid4, selectedCall = selectedCallsign,
            text = replaceMacros(text, prune = false),
            forceIdentify = !config.avoidForcedIdentify, forceData = false,
            submode = submode,
        )
        if (frames.isEmpty()) return false
        txFrameQueue.addAll(frames)
        txFramesRemaining = frames.size
        draftText = ""
        prepareNextMessageFrame()
        changed()
        return true
    }

    /** Preview of how `text` will be framed and what the other side will see. */
    fun preview(text: String): List<DecodedText> {
        val frames = Varicode.buildMessageFrames(
            mycall = mycall, mygrid = mygrid4, selectedCall = selectedCallsign,
            text = replaceMacros(text, prune = false),
            forceIdentify = !config.avoidForcedIdentify, forceData = false,
            submode = submode,
        )
        return frames.map { DecodedText(frame = it.frame, bits = it.type, submode = submode) }
    }

    internal fun resetMessageTransmitQueue() {
        txFrameQueue.clear()
        txFramesRemaining = 0
        txFramesSent = 0
        totalTxMessage = ""
        nextFrame = null
    }

    /** Halt everything queued (JS8Call's Halt TX). */
    fun haltTransmit() {
        resetMessageTransmitQueue()
        _txMessageQueue.clear()
        heartbeatLoopActive = false
        cqLoopActive = false
        restoreOffset()
        changed()
    }

    private fun prepareNextMessageFrame(): Boolean {
        if (txFrameQueue.isEmpty()) {
            nextFrame = null
            return false
        }
        var f = txFrameQueue.removeAt(0)
        if (txFramesSent > 0) f = f.copy(type = f.type and FIRST.inv())
        if (txFrameQueue.isEmpty()) f = f.copy(type = f.type or LAST)
        if (f.frame.isEmpty()) {
            nextFrame = null
            return false
        }
        val dt = DecodedText(frame = f.frame, bits = f.type, submode = submode)
        totalTxMessage += dt.message
        txFramesSent += 1
        lastTxMessage = totalTxMessage
        nextFrame = f
        return true
    }

    /** Decide whether to key up now. Returns a request when a frame should
     *  start in the current (or imminent) period. */
    fun pollTransmit(now: Instant): TxRequest? {
        val frame = nextFrame
        if (isTransmitting || frame == null || !txEnabled) return null
        val period = submode.period.toDouble()
        val ms = now.timeIntervalSince1970
        val into = ms % period
        val inTxDelay = period - txDelay <= into
        val timeToSend = (0 <= into && into < submode.txDuration) || inTxDelay
        var lateThreshold = (period - submode.dataDuration) / period - txDelay / period
        if (submode == JS8Submode.fast) lateThreshold *= 0.75
        if (submode == JS8Submode.turbo || submode == JS8Submode.ultra) lateThreshold *= 0.5
        if (!(timeToSend && (into / period < lateThreshold || inTxDelay))) return null

        val periodStart = instantFromTimeIntervalSince1970(if (inTxDelay) ms - into + period else ms - into)
        val tones = JS8Codec.encode(type = frame.type, frame = frame.frame, costas = submode.costas)
        if (tones == null) {
            nextFrame = null
            return null
        }
        val dt = DecodedText(frame = frame.frame, bits = frame.type, submode = submode)
        val isLast = frame.type and LAST != 0
        val isFirst = txFramesSent == 1
        val display = if (isLast) "${dt.message} ${config.eot} " else dt.message
        displayText(display, offset = offset, date = now, isTx = true, isNewLine = if (isLast) false else isFirst, isLast = isLast)
        logBandText(
            dt.message, offset = offset, date = now, snr = null, submode = submode,
            bits = frame.type or (if (isFirst) FIRST else 0), isTx = true,
        )

        val req = TxRequest(
            frame = frame.frame, type = frame.type, tones = tones.toList(), offset = offset, submode = submode,
            periodStart = periodStart, displayText = dt.message,
        )
        nextFrame = null
        txFramesRemaining = txFrameQueue.size
        isTransmitting = true
        currentTx = req
        lastTxStart = now
        onNotify?.invoke(StationNotification.txStarted, dt.message)
        changed()
        return req
    }

    /** Call when the audio for `currentTx` has finished. */
    fun transmissionFinished() {
        if (!isTransmitting) return
        isTransmitting = false
        currentTx = null
        lastTxStop = clock()
        if (!prepareNextMessageFrame()) {
            resetMessageTransmitQueue()
            restoreOffset()
        }
        txFramesRemaining = txFrameQueue.size + (if (nextFrame == null) 0 else 1)
        onNotify?.invoke(StationNotification.txFinished, "")
        changed()
    }

    private fun restoreOffset() {
        if (offsetBeforeAuto != 0) {
            offset = offsetBeforeAuto
            offsetBeforeAuto = 0
        }
    }

    // MARK: - Heartbeat / CQ

    internal fun isFreqOffsetFree(f: Int, bw: Int): Boolean {
        if (offset == f || isDirectedOffset(f).first) return true
        val now = clock()
        for ((off, activity) in _bandActivity) {
            val last = activity.lastOrNull() ?: continue
            if (now.timeIntervalSince(last.utc) >= 30) continue
            if (abs(off - f) < bw) return false
        }
        return true
    }

    internal fun findFreeFreqOffset(fmin: Int, fmax: Int, bw: Int): Int {
        val nslots = (fmax - fmin) / bw
        repeat(nslots) {
            val f = fmin + bw * kotlin.random.Random.nextInt(0, nslots)
            if (isFreqOffsetFree(f, bw)) return f
        }
        repeat(nslots) {
            val f = fmin + kotlin.random.Random.nextInt(0, fmax - fmin)
            if (isFreqOffsetFree(f, bw)) return f
        }
        return fmin
    }

    fun sendHeartbeat() {
        if (!submode.canSendHeartbeat) return
        val message = "$mycall: HEARTBEAT $mygrid4".qTrimmed()
        var f = findFreeFreqOffset(500, 1000, 50)
        if (offset <= 1000) {
            f = offset
        } else if (config.heartbeatAnywhere) {
            f = -1
        }
        enqueue(priority = Priority.low + 1, message = message, offset = f)
        processTxQueue()
    }

    internal fun sendHeartbeatAck(to: String, snr: Int, extra: String) {
        val message = "$to HEARTBEAT SNR ${Varicode.formatSNR(snr)} $extra".qTrimmed()
        val f = if (config.heartbeatAnywhere) -1 else findFreeFreqOffset(500, 1000, 50)
        val m = QueuedMessage(date = clock(), priority = Priority.low + 1, message = message, offset = f, onSent = null)
        if (config.autoReplyConfirmation) {
            _pendingReplies.add(PendingReply(created = clock(), expires = clock().addingTimeInterval(90.0), message = m))
        } else {
            enqueue(m)
            processTxQueue()
        }
    }

    fun sendCQ() {
        selectedCallsign = ""
        send(replaceMacros(config.cqMessage, prune = true))
    }

    fun setHeartbeatLoop(on: Boolean) {
        heartbeatLoopActive = on
        if (on) {
            if (config.heartbeatQSOPause) selectedCallsign = ""
            sendHeartbeat()
            nextHeartbeat = if (config.heartbeatIntervalMinutes > 0) {
                clock().addingTimeInterval(config.heartbeatIntervalMinutes.toDouble() * 60)
            } else {
                null
            }
            if (nextHeartbeat == null) heartbeatLoopActive = false
        } else {
            nextHeartbeat = null
        }
        changed()
    }

    fun setCQLoop(on: Boolean) {
        cqLoopActive = on
        if (on) {
            sendCQ()
            nextCQ = if (config.cqIntervalMinutes > 0) clock().addingTimeInterval(config.cqIntervalMinutes.toDouble() * 60) else null
            if (nextCQ == null) cqLoopActive = false
        } else {
            nextCQ = null
        }
        changed()
    }

    internal fun cancelCQLoop() {
        if (cqLoopActive) {
            cqLoopActive = false
            nextCQ = null
        }
    }

    internal fun processLoops(now: Instant) {
        val nh = nextHeartbeat
        if (heartbeatLoopActive && nh != null && now >= nh) {
            if (!isMessageQueuedForTransmit && !(config.heartbeatQSOPause && selectedCallsign.isNotEmpty())) {
                sendHeartbeat()
            }
            nextHeartbeat = now.addingTimeInterval(max(1, config.heartbeatIntervalMinutes).toDouble() * 60)
        }
        val nc = nextCQ
        if (cqLoopActive && nc != null && now >= nc) {
            if (!isMessageQueuedForTransmit) sendCQ()
            nextCQ = now.addingTimeInterval(max(1, config.cqIntervalMinutes).toDouble() * 60)
        }
    }

    // MARK: - Decoder gating

    /** JS8Call pauses decoding while transmitting and shortly afterwards. */
    fun shouldDecode(now: Instant): Boolean {
        if (isTransmitting) return false
        val stop = lastTxStop
        if (stop != null) {
            val threshold = if (submode == JS8Submode.slow) 4.0 else 2.0
            if (now.timeIntervalSince(stop) < threshold) return false
        }
        return true
    }

    /** Text aggregated per offset for the band activity table. */
    fun bandActivityRows(now: Instant): List<BandActivityRow> {
        val rows = mutableListOf<BandActivityRow>()
        val directedOffsets = rxDirectedCache.filter { now.timeIntervalSince(it.value.date) < 120 && !it.value.isAllcall }.keys.toSet()
        for ((off, itemsIn) in _bandActivity) {
            val items = itemsIn.map { it.copy() }
            for (i in items.indices) {
                var show = true
                if (config.activityAgingMinutes > 0 && now.timeIntervalSince(items[i].utc) / 60 >= config.activityAgingMinutes.toDouble()) {
                    show = false
                }
                if (!config.showHeartbeatsInBand) {
                    if (items[i].text.contains(" @HB ") || items[i].text.contains(" HEARTBEAT ")) {
                        show = false
                        if (i > 0 && items[i - 1].shouldDisplay && items[i - 1].text.endsWith(": ")) items[i - 1].shouldDisplay = false
                    }
                    if ((i == 0 || !items[i - 1].shouldDisplay) && items[i].text.contains(" MSG ID ")) show = false
                }
                if (items[i].text.isEmpty()) show = false
                items[i].shouldDisplay = show
            }
            val texts = mutableListOf<String>()
            var last: ActivityDetail? = null
            for (item in items) {
                if (!item.shouldDisplay) continue
                var t = item.text
                if (item.isLowConfidence) t = "[$t]"
                if (item.bits and LAST != 0) t = "${Varicode.rstrip(t)} ${config.eot} "
                texts.add(t)
                last = item
            }
            val joined = Varicode.rstrip(texts.joinToString(""))
            val l = last ?: continue
            if (joined.isEmpty()) continue
            val highlight = directedOffsets.contains(off / 10 * 10) || isMyCallIncluded(texts.lastOrNull() ?: "")
            rows.add(
                BandActivityRow(
                    offset = off, text = joined, snr = l.snr, utc = l.utc, submode = l.submode,
                    tdrift = l.tdrift, isDirectedToMe = highlight,
                ),
            )
        }
        return rows.sortedBy { it.offset }
    }
}

data class BandActivityRow(
    var offset: Int,
    var text: String,
    var snr: Int,
    var utc: Instant,
    var submode: JS8Submode,
    var tdrift: Float,
    var isDirectedToMe: Boolean,
) {
    val id: Int get() = offset
}
