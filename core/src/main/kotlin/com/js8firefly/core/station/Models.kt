package com.js8firefly.core

import java.time.Instant
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToLong

// Port of JS8Core/Station/Models.swift.
//
// Platform mapping used by the whole station package:
//  - Swift `Date`   -> java.time.Instant (nanosecond precision; helpers below give
//                      Date-like Double-seconds arithmetic).
//  - Swift `UUID`   -> java.util.UUID.
//  - Swift `UInt64` dial frequencies -> Long (Hz).
//  - Swift structs  -> data classes with `var` properties. Swift structs have value
//                      semantics; the station copies (`copy()`) wherever Swift's
//                      implicit copy matters, so callers may keep and mutate their own
//                      instances freely.

/** Seconds from `other` to this instant (Swift `Date.timeIntervalSince`). */
internal fun Instant.timeIntervalSince(other: Instant): Double =
    (epochSecond - other.epochSecond).toDouble() + (nano - other.nano).toDouble() / 1e9

/** Swift `Date.addingTimeInterval`. */
internal fun Instant.addingTimeInterval(seconds: Double): Instant = plusNanos((seconds * 1e9).roundToLong())

/** Swift `Date.timeIntervalSince1970`. */
internal val Instant.timeIntervalSince1970: Double get() = epochSecond.toDouble() + nano.toDouble() / 1e9

/** Swift `Date(timeIntervalSince1970:)`. */
internal fun instantFromTimeIntervalSince1970(seconds: Double): Instant {
    val s = floor(seconds)
    return Instant.ofEpochSecond(s.toLong(), ((seconds - s) * 1e9).roundToLong())
}

/** One decoded frame as shown in band activity (JS8Call's ActivityDetail). */
data class ActivityDetail(
    var id: UUID = UUID.randomUUID(),
    var isLowConfidence: Boolean = false,
    var isCompound: Boolean = false,
    var isDirected: Boolean = false,
    var isBuffered: Boolean = false,
    var shouldDisplay: Boolean = true,
    var bits: Int = 0,
    var dial: Long = 0,
    var offset: Int = 0,
    var snr: Int = 0,
    var text: String = "",
    var utc: Instant = Instant.now(),
    var submode: JS8Submode = JS8Submode.normal,
    var tdrift: Float = 0f,
)

/** A station heard on the air (JS8Call's CallDetail). */
data class CallDetail(
    var call: String = "",
    var grid: String = "",
    var through: String = "",
    var snr: Int = 0,
    var dial: Long = 0,
    var offset: Int = 0,
    var utc: Instant = Instant.now(),
    var ackTimestamp: Instant? = null,
    var cqTimestamp: Instant? = null,
    var bits: Int = 0,
    var submode: JS8Submode = JS8Submode.normal,
    var tdrift: Float = 0f,
) {
    val id: String get() = call
}

/** A directed command addressed to someone (JS8Call's CommandDetail). */
data class CommandDetail(
    var isCompound: Boolean = false,
    var isBuffered: Boolean = false,
    var from: String = "",
    var to: String = "",
    var cmd: String = "",
    var grid: String = "",
    var extra: String = "",
    var text: String = "",
    var relayPath: String = "",
    var snr: Int = 0,
    var dial: Long = 0,
    var offset: Int = 0,
    var utc: Instant? = null,
    var bits: Int = 0,
    var submode: JS8Submode = JS8Submode.normal,
    var tdrift: Float = 0f,
)

internal data class MessageBuffer(
    var cmd: CommandDetail = CommandDetail(),
    var compound: MutableList<CallDetail> = mutableListOf(),
    var msgs: MutableList<ActivityDetail> = mutableListOf(),
) {
    /** A deep copy, matching Swift's value semantics. */
    fun deepCopy(): MessageBuffer =
        MessageBuffer(cmd.copy(), compound.mapTo(mutableListOf()) { it.copy() }, msgs.mapTo(mutableListOf()) { it.copy() })
}

/** A line in the conversation window (JS8Call's RX/TX text pane). */
data class ConversationLine(
    var id: UUID = UUID.randomUUID(),
    var date: Instant,
    var offset: Int,
    var text: String,
    var isTx: Boolean,
    var isComplete: Boolean,
    var snr: Int? = null,
    var submode: JS8Submode? = null,
)

object Priority {
    const val low = 10
    const val normal = 100
    const val high = 1000
}

/** A message waiting to be transmitted. */
data class QueuedMessage(
    var id: UUID = UUID.randomUUID(),
    var date: Instant,
    var priority: Int,
    var message: String,
    var offset: Int,          // -1 = current offset
    var onSent: (() -> Unit)? = null,
)

/** An auto-reply awaiting operator confirmation. */
data class PendingReply(
    var id: UUID = UUID.randomUUID(),
    var created: Instant,
    var expires: Instant,
    var message: QueuedMessage,
)

enum class StationNotification(val rawValue: String) {
    directed("directed"), inbox("inbox"), cq("cq"), hb("hb"), ack("ack"),
    newCall("newCall"), txStarted("txStarted"), txFinished("txFinished");

    companion object {
        fun fromRaw(raw: String): StationNotification? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Everything the station needs to know about the operator. */
data class StationConfig(
    var callsign: String = "",
    var grid: String = "",
    var info: String = "",
    var status: String = "",
    var groups: List<String> = emptyList(),
    var cqMessage: String = "CQ CQ CQ <MYGRID4>",
    var replyMessage: String = "HW CPY?",
    var eot: String = "♢",
    var mfi: String = "……",
    var autoReply: Boolean = true,
    var autoReplyConfirmation: Boolean = false,
    var avoidAllcall: Boolean = false,
    var avoidForcedIdentify: Boolean = false,
    var relayOff: Boolean = false,
    var heartbeatAcknowledgements: Boolean = true,
    var heartbeatQSOPause: Boolean = true,
    var heartbeatAnywhere: Boolean = false,
    var heartbeatIntervalMinutes: Int = 10,
    var cqIntervalMinutes: Int = 0,
    var callsignAgingMinutes: Int = 0,
    var activityAgingMinutes: Int = 2,
    var autoWhitelist: List<String> = emptyList(),
    var autoBlacklist: List<String> = emptyList(),
    var hbBlacklist: List<String> = emptyList(),
    var idleWatchdogMinutes: Int = 0,
    var showHeartbeatsInBand: Boolean = true,
)

/** A frame the station wants put on the air. */
data class TxRequest(
    var frame: String,
    var type: Int,
    var tones: List<Int>,
    var offset: Int,
    var submode: JS8Submode,
    var periodStart: Instant,
    var displayText: String,
)
