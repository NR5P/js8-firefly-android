package com.js8firefly.core

/**
 * Interpretation of a decoded 12-character frame. Port of JS8Call's
 * DecodedText: tries fast data, data, heartbeat, compound and directed
 * unpacking in that order. Immutable from outside; equality covers every
 * stored property like the Swift struct's synthesized Hashable.
 */
class DecodedText(
    val frame: String,
    val bits: Int,
    val submode: JS8Submode,
    val isLowConfidence: Boolean = false,
    val frequencyOffset: Int = 0,
    val snr: Int = 0,
    val dt: Float = 0f,
) {
    companion object {
        const val qualityThreshold: Float = 0.17f

        private fun buildCompound(parts: List<String>): String =
            parts.take(2).filter { it.isNotEmpty() }.joinToString("/")
    }

    var frameType: Varicode.FrameType = Varicode.FrameType.unknown
        private set
    var isAlt: Boolean = false
        private set
    var isHeartbeat: Boolean = false
        private set
    var compoundCall: String = ""
        private set
    var directed: List<String> = emptyList()
        private set
    var extra: String = ""
        private set
    var message: String = frame
        private set

    val isCompound: Boolean get() = compoundCall.isNotEmpty()
    val isDirectedMessage: Boolean get() = directed.size > 2
    val isFirst: Boolean get() = bits and Varicode.TransmissionType.first.rawValue != 0
    val isLast: Boolean get() = bits and Varicode.TransmissionType.last.rawValue != 0

    constructor(raw: JS8RawDecode) : this(
        frame = raw.frame,
        bits = raw.type,
        submode = raw.submode,
        isLowConfidence = raw.quality < qualityThreshold,
        frequencyOffset = raw.frequency.toInt(),
        snr = raw.snr,
        dt = raw.dt,
    )

    init {
        val m = frame.qTrimmed()
        if (!(m.u16Count < 12 || m.contains(" "))) {
            if (!tryUnpackFastData(m) && !tryUnpackData(m) && !tryUnpackHeartbeat(m) && !tryUnpackCompound(m)) {
                tryUnpackDirected(m)
            }
        }
    }

    private val isDataBits: Boolean
        get() = bits and Varicode.TransmissionType.data.rawValue == Varicode.TransmissionType.data.rawValue

    private fun tryUnpackFastData(m: String): Boolean {
        if (!isDataBits) return false
        val data = Varicode.unpackFastDataMessage(m)
        if (data.isEmpty()) return false
        message = data
        frameType = Varicode.FrameType.data
        return true
    }

    private fun tryUnpackData(m: String): Boolean {
        if (isDataBits) return false
        val data = Varicode.unpackDataMessage(m)
        if (data.isEmpty()) return false
        message = data
        frameType = Varicode.FrameType.data
        return true
    }

    private fun tryUnpackHeartbeat(m: String): Boolean {
        if (isDataBits) return false
        val hb = Varicode.unpackHeartbeatMessage(m) ?: return false
        if (hb.parts.size < 2) return false
        frameType = Varicode.FrameType.fromRaw(hb.type) ?: Varicode.FrameType.unknown
        isHeartbeat = true
        isAlt = hb.isAlt
        extra = if (hb.parts.size > 2) hb.parts[2] else ""
        compoundCall = buildCompound(hb.parts)
        var msg = "$compoundCall: "
        if (hb.isAlt) {
            msg += "@ALLCALL " + Varicode.cqString(hb.bits3)
        } else {
            val s = Varicode.hbString(hb.bits3)
            msg += "@HB " + (if (s == "HB") "HEARTBEAT" else s)
        }
        msg += " $extra "
        message = msg
        return true
    }

    private fun tryUnpackCompound(m: String): Boolean {
        val c = Varicode.unpackCompoundMessage(m) ?: return false
        if (c.parts.size < 2 || isDataBits) return false
        frameType = Varicode.FrameType.fromRaw(c.type) ?: Varicode.FrameType.unknown
        extra = c.parts.drop(2).joinToString(" ")
        compoundCall = buildCompound(c.parts)
        if (c.type == Varicode.FrameType.compound.rawValue) {
            message = "$compoundCall: "
        } else if (c.type == Varicode.FrameType.compoundDirected.rawValue) {
            message = "$compoundCall$extra "
            directed = listOf("<....>", compoundCall) + c.parts.drop(2)
        }
        return true
    }

    private fun tryUnpackDirected(m: String): Boolean {
        if (isDataBits) return false
        val d = Varicode.unpackDirectedMessage(m) ?: return false
        if (d.parts.isEmpty()) return false
        val parts = d.parts
        message = when (parts.size) {
            3, 4 -> parts[0] + ": " + parts[1] + parts.drop(2).joinToString(" ") + " "
            else -> parts.joinToString("")
        }
        directed = parts
        frameType = Varicode.FrameType.fromRaw(d.type) ?: Varicode.FrameType.unknown
        return true
    }

    /** Whole message followed by its words (mimics a regex capture list). */
    val messageWords: List<String>
        get() = listOf(message) + message.split(" ").filter { it.isNotEmpty() }

    private fun key(): List<Any> = listOf(
        frameType, frame, isAlt, isHeartbeat, isLowConfidence, compoundCall, directed, extra, message,
        bits, submode, frequencyOffset, snr, dt,
    )

    override fun equals(other: Any?): Boolean = other is DecodedText && key() == other.key()

    override fun hashCode(): Int = key().hashCode()

    override fun toString(): String = "DecodedText(frame=$frame, bits=$bits, message=$message)"
}
