package com.js8firefly.android

import android.content.Context
import com.js8firefly.android.audio.PTTMethod
import com.js8firefly.android.audio.TxAudioSettings
import com.js8firefly.android.radio.CATProtocol
import com.js8firefly.android.radio.RigConnection
import com.js8firefly.android.radio.RigPreset
import com.js8firefly.android.radio.RigSettings
import com.js8firefly.core.JS8Submode
import com.js8firefly.core.StationConfig
import com.js8firefly.core.Varicode
import com.js8firefly.core.WatchItem
import com.js8firefly.core.WatchMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All persisted app settings. Immutable: change it with
 * `model.updateSettings { it.copy(...) }`. Swift's computed setters are the
 * `with…` functions (e.g. `it.withAutoGrid(true)`).
 */
data class AppSettings(
    val station: StationConfig = StationConfig(),
    val submode: JS8Submode = JS8Submode.normal,
    val offset: Int = 1500,
    val dialFrequency: Long = 14_078_000,
    val txEnabled: Boolean = true,
    val heartbeatNetworking: Boolean = true,
    val decodeAllSpeeds: Boolean = true,
    val tx: TxAudioSettings = TxAudioSettings(),
    val txDelay: Double = 0.2,
    val rig: RigSettings = RigSettings(),
    val inputChannel: Int = 0,
    /** Hardware RX gain (0...1); null until the user sets it. */
    val inputGain: Float? = null,
    val clockOffset: Double = 0.0,
    val macInputDevice: String = "",
    val macOutputDevice: String = "",
    /** `AudioDeviceChoice.key` of the chosen audio devices; "" = automatic. */
    val audioInputDevice: String = "",
    val audioOutputDevice: String = "",
    /** dB from the waterfall's noise floor to full white; null until the user sets it. */
    val waterfallRange: Float? = null,
    val waterfallBrightness: Float = 0f,
    val notificationsEnabled: Boolean = true,
    val hasOnboarded: Boolean = false,
    /** User-saved dial frequencies with optional labels; null until the first is saved. */
    val savedFrequencies: List<SavedFrequency>? = null,
    /** Keep the grid updated from the device location; null (off) until the user turns it on. */
    val gridFromLocation: Boolean? = null,
    /** Callsigns, @GROUPs and words to make stand out; null until the first is added. */
    val watchItems: List<WatchItem>? = null,
    /** Notify when watched traffic is heard in the background; null means on. */
    val watchNotifications: Boolean? = null,
) {
    companion object {
        const val storageKey = "JS8CallSettings.v1"
        private const val prefsName = "settings"

        /** Wide enough that a signal 40 dB over the noise doesn't saturate to white. */
        const val defaultWaterfallRange: Float = 48f

        fun load(context: Context): AppSettings {
            val json = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(storageKey, null)
                ?: return AppSettings()
            return try { fromJson(JSONObject(json)) } catch (e: Exception) { AppSettings() }
        }

        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            return AppSettings(
                station = o.optJSONObject("station")?.let { stationConfigFromJson(it) } ?: d.station,
                submode = o.optIntOrNull("submode")?.let { JS8Submode.fromRaw(it) } ?: d.submode,
                offset = o.optInt("offset", d.offset),
                dialFrequency = o.optLong("dialFrequency", d.dialFrequency),
                txEnabled = o.optBoolean("txEnabled", d.txEnabled),
                heartbeatNetworking = o.optBoolean("heartbeatNetworking", d.heartbeatNetworking),
                decodeAllSpeeds = o.optBoolean("decodeAllSpeeds", d.decodeAllSpeeds),
                tx = o.optJSONObject("tx")?.let { txAudioSettingsFromJson(it) } ?: d.tx,
                txDelay = o.optDouble("txDelay", d.txDelay),
                rig = o.optJSONObject("rig")?.let { rigSettingsFromJson(it) } ?: d.rig,
                inputChannel = o.optInt("inputChannel", d.inputChannel),
                inputGain = o.optDoubleOrNull("inputGain")?.toFloat(),
                clockOffset = o.optDouble("clockOffset", d.clockOffset),
                macInputDevice = o.optString("macInputDevice", d.macInputDevice),
                macOutputDevice = o.optString("macOutputDevice", d.macOutputDevice),
                audioInputDevice = o.optString("audioInputDevice", d.audioInputDevice),
                audioOutputDevice = o.optString("audioOutputDevice", d.audioOutputDevice),
                waterfallRange = o.optDoubleOrNull("waterfallRange")?.toFloat(),
                waterfallBrightness = o.optDouble("waterfallBrightness", d.waterfallBrightness.toDouble()).toFloat(),
                notificationsEnabled = o.optBoolean("notificationsEnabled", d.notificationsEnabled),
                hasOnboarded = o.optBoolean("hasOnboarded", d.hasOnboarded),
                savedFrequencies = o.optJSONArray("savedFrequencies")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { f -> SavedFrequency.fromJson(f) } }
                },
                gridFromLocation = if (o.has("gridFromLocation") && !o.isNull("gridFromLocation")) o.optBoolean("gridFromLocation") else null,
                watchItems = o.optJSONArray("watchItems")?.let { a ->
                    try { WatchItem.decodeList(a.toString()) } catch (e: Exception) { null }
                },
                watchNotifications = if (o.has("watchNotifications") && !o.isNull("watchNotifications")) o.optBoolean("watchNotifications") else null,
            )
        }
    }

    val waterfallRangeDB: Float get() = waterfallRange ?: defaultWaterfallRange

    val autoGrid: Boolean get() = gridFromLocation ?: false
    fun withAutoGrid(on: Boolean): AppSettings = copy(gridFromLocation = if (on) true else null)

    val customFrequencies: List<SavedFrequency> get() = savedFrequencies ?: emptyList()
    fun withCustomFrequencies(list: List<SavedFrequency>): AppSettings =
        copy(savedFrequencies = if (list.isEmpty()) null else list.sortedBy { it.frequency })

    val watchList: List<WatchItem> get() = watchItems ?: emptyList()
    fun withWatchList(list: List<WatchItem>): AppSettings = copy(watchItems = if (list.isEmpty()) null else list)

    val watchNotify: Boolean get() = watchNotifications ?: true
    fun withWatchNotify(on: Boolean): AppSettings = copy(watchNotifications = if (on) null else false)

    /** The first watch item found in a line of decoded text. */
    fun watch(matching: String): WatchItem? {
        val list = watchList
        return WatchMatcher.firstMatch(matching, list.map { it.term })?.let { list[it] }
    }

    /** The label of the saved frequency matching the dial, if any. */
    val dialLabel: String?
        get() = customFrequencies.firstOrNull { it.frequency == dialFrequency && it.label.isNotEmpty() }?.label

    fun save(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString(storageKey, toJson().toString()).apply()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("station", station.toJson())
        put("submode", submode.rawValue)
        put("offset", offset)
        put("dialFrequency", dialFrequency)
        put("txEnabled", txEnabled)
        put("heartbeatNetworking", heartbeatNetworking)
        put("decodeAllSpeeds", decodeAllSpeeds)
        put("tx", tx.toJson())
        put("txDelay", txDelay)
        put("rig", rig.toJson())
        put("inputChannel", inputChannel)
        inputGain?.let { put("inputGain", it.toDouble()) }
        put("clockOffset", clockOffset)
        put("macInputDevice", macInputDevice)
        put("macOutputDevice", macOutputDevice)
        put("audioInputDevice", audioInputDevice)
        put("audioOutputDevice", audioOutputDevice)
        waterfallRange?.let { put("waterfallRange", it.toDouble()) }
        put("waterfallBrightness", waterfallBrightness.toDouble())
        put("notificationsEnabled", notificationsEnabled)
        put("hasOnboarded", hasOnboarded)
        savedFrequencies?.let { l -> put("savedFrequencies", JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }) }
        gridFromLocation?.let { put("gridFromLocation", it) }
        watchItems?.let { put("watchItems", JSONArray(WatchItem.encodeList(it))) }
        watchNotifications?.let { put("watchNotifications", it) }
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
private fun JSONObject.optStringList(key: String, def: List<String>): List<String> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: def
private fun List<String>.toJSONArray() = JSONArray().also { a -> forEach { a.put(it) } }

// MARK: Serializers for core and audio/radio types

fun StationConfig.toJson(): JSONObject = JSONObject().apply {
    put("callsign", callsign); put("grid", grid); put("info", info); put("status", status)
    put("groups", groups.toJSONArray())
    put("cqMessage", cqMessage); put("replyMessage", replyMessage); put("eot", eot); put("mfi", mfi)
    put("autoReply", autoReply); put("autoReplyConfirmation", autoReplyConfirmation)
    put("avoidAllcall", avoidAllcall); put("avoidForcedIdentify", avoidForcedIdentify); put("relayOff", relayOff)
    put("heartbeatAcknowledgements", heartbeatAcknowledgements); put("heartbeatQSOPause", heartbeatQSOPause)
    put("heartbeatAnywhere", heartbeatAnywhere); put("heartbeatIntervalMinutes", heartbeatIntervalMinutes)
    put("cqIntervalMinutes", cqIntervalMinutes); put("callsignAgingMinutes", callsignAgingMinutes)
    put("activityAgingMinutes", activityAgingMinutes)
    put("autoWhitelist", autoWhitelist.toJSONArray()); put("autoBlacklist", autoBlacklist.toJSONArray())
    put("hbBlacklist", hbBlacklist.toJSONArray())
    put("idleWatchdogMinutes", idleWatchdogMinutes); put("showHeartbeatsInBand", showHeartbeatsInBand)
}

fun stationConfigFromJson(o: JSONObject): StationConfig {
    val d = StationConfig()
    return StationConfig(
        callsign = o.optString("callsign", d.callsign),
        grid = o.optString("grid", d.grid),
        info = o.optString("info", d.info),
        status = o.optString("status", d.status),
        groups = o.optStringList("groups", d.groups),
        cqMessage = o.optString("cqMessage", d.cqMessage),
        replyMessage = o.optString("replyMessage", d.replyMessage),
        eot = o.optString("eot", d.eot),
        mfi = o.optString("mfi", d.mfi),
        autoReply = o.optBoolean("autoReply", d.autoReply),
        autoReplyConfirmation = o.optBoolean("autoReplyConfirmation", d.autoReplyConfirmation),
        avoidAllcall = o.optBoolean("avoidAllcall", d.avoidAllcall),
        avoidForcedIdentify = o.optBoolean("avoidForcedIdentify", d.avoidForcedIdentify),
        relayOff = o.optBoolean("relayOff", d.relayOff),
        heartbeatAcknowledgements = o.optBoolean("heartbeatAcknowledgements", d.heartbeatAcknowledgements),
        heartbeatQSOPause = o.optBoolean("heartbeatQSOPause", d.heartbeatQSOPause),
        heartbeatAnywhere = o.optBoolean("heartbeatAnywhere", d.heartbeatAnywhere),
        heartbeatIntervalMinutes = o.optInt("heartbeatIntervalMinutes", d.heartbeatIntervalMinutes),
        cqIntervalMinutes = o.optInt("cqIntervalMinutes", d.cqIntervalMinutes),
        callsignAgingMinutes = o.optInt("callsignAgingMinutes", d.callsignAgingMinutes),
        activityAgingMinutes = o.optInt("activityAgingMinutes", d.activityAgingMinutes),
        autoWhitelist = o.optStringList("autoWhitelist", d.autoWhitelist),
        autoBlacklist = o.optStringList("autoBlacklist", d.autoBlacklist),
        hbBlacklist = o.optStringList("hbBlacklist", d.hbBlacklist),
        idleWatchdogMinutes = o.optInt("idleWatchdogMinutes", d.idleWatchdogMinutes),
        showHeartbeatsInBand = o.optBoolean("showHeartbeatsInBand", d.showHeartbeatsInBand),
    )
}

fun TxAudioSettings.toJson(): JSONObject = JSONObject().apply {
    put("pttMethod", pttMethod.rawValue); put("toneFrequency", toneFrequency); put("toneLevel", toneLevel.toDouble())
    put("leadTime", leadTime); put("tailTime", tailTime); put("outputLevel", outputLevel.toDouble())
    put("swapChannels", swapChannels)
}

fun txAudioSettingsFromJson(o: JSONObject): TxAudioSettings {
    val d = TxAudioSettings()
    return TxAudioSettings(
        pttMethod = PTTMethod.fromRaw(o.optString("pttMethod")) ?: d.pttMethod,
        toneFrequency = o.optDouble("toneFrequency", d.toneFrequency),
        toneLevel = o.optDouble("toneLevel", d.toneLevel.toDouble()).toFloat(),
        leadTime = o.optDouble("leadTime", d.leadTime),
        tailTime = o.optDouble("tailTime", d.tailTime),
        outputLevel = o.optDouble("outputLevel", d.outputLevel.toDouble()).toFloat(),
        swapChannels = o.optBoolean("swapChannels", d.swapChannels),
    )
}

fun RigSettings.toJson(): JSONObject = JSONObject().apply {
    put("connection", connection.rawValue); put("preset", preset.rawValue); put("catProtocol", catProtocol.rawValue)
    put("serialPath", serialPath); put("baud", baud); put("stopBits", stopBits); put("yaesuDigits", yaesuDigits)
    put("icomAddress", icomAddress); put("bluetoothID", bluetoothID); put("host", host); put("port", port)
    put("setDataModeOnConnect", setDataModeOnConnect); put("pollSeconds", pollSeconds)
}

fun rigSettingsFromJson(o: JSONObject): RigSettings {
    val d = RigSettings()
    return RigSettings(
        connection = RigConnection.fromRaw(o.optString("connection")) ?: d.connection,
        preset = RigPreset.fromRaw(o.optString("preset")) ?: d.preset,
        catProtocol = CATProtocol.fromRaw(o.optString("catProtocol")) ?: d.catProtocol,
        serialPath = o.optString("serialPath", d.serialPath),
        baud = o.optInt("baud", d.baud),
        stopBits = o.optInt("stopBits", d.stopBits),
        yaesuDigits = o.optInt("yaesuDigits", d.yaesuDigits),
        icomAddress = o.optInt("icomAddress", d.icomAddress) and 0xFF,
        bluetoothID = o.optString("bluetoothID", d.bluetoothID),
        host = o.optString("host", d.host),
        port = o.optInt("port", d.port) and 0xFFFF,
        setDataModeOnConnect = o.optBoolean("setDataModeOnConnect", d.setDataModeOnConnect),
        pollSeconds = o.optDouble("pollSeconds", d.pollSeconds),
    )
}

/** A user-saved dial frequency, e.g. a local net or event frequency. */
data class SavedFrequency(
    val id: UUID = UUID.randomUUID(),
    val frequency: Long,
    val label: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id.toString().uppercase()); put("frequency", frequency); put("label", label)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedFrequency? {
            if (!o.has("frequency")) return null
            val id = try { UUID.fromString(o.optString("id")) } catch (e: Exception) { UUID.randomUUID() }
            return SavedFrequency(id, o.optLong("frequency"), o.optString("label", ""))
        }
    }
}

/** Standard JS8 calling frequencies (dial, USB). */
data class Band(val name: String, val frequency: Long) {
    val id: String get() = name

    companion object {
        val js8: List<Band> = listOf(
            Band("160m", 1_842_000),
            Band("80m", 3_578_000),
            Band("60m", 5_357_000),
            Band("40m", 7_078_000),
            Band("30m", 10_130_000),
            Band("20m", 14_078_000),
            Band("17m", 18_104_000),
            Band("15m", 21_078_000),
            Band("12m", 24_922_000),
            Band("10m", 28_078_000),
            Band("6m", 50_318_000),
            Band("2m", 144_178_000),
        )

        private val ranges: List<Pair<String, LongRange>> = listOf(
            "160m" to 1_800_000L..2_000_000L, "80m" to 3_500_000L..4_000_000L, "60m" to 5_250_000L..5_450_000L,
            "40m" to 7_000_000L..7_300_000L, "30m" to 10_100_000L..10_150_000L, "20m" to 14_000_000L..14_350_000L,
            "17m" to 18_068_000L..18_168_000L, "15m" to 21_000_000L..21_450_000L, "12m" to 24_890_000L..24_990_000L,
            "10m" to 28_000_000L..29_700_000L, "6m" to 50_000_000L..54_000_000L, "2m" to 144_000_000L..148_000_000L,
        )

        fun name(hz: Long): String = ranges.firstOrNull { hz in it.second }?.first ?: "OOB"
    }
}

object Format {
    fun mhz(hz: Long): String = String.format(Locale.US, "%.3f", hz / 1_000_000.0)

    fun mhzLong(hz: Long): String {
        val mhz = hz / 1_000_000
        val khz = (hz / 1000) % 1000
        val rem = hz % 1000
        return String.format(Locale.US, "%d.%03d.%03d", mhz, khz, rem)
    }

    /** HH:mm:ss in UTC; use `Format.utcTime.format(instant)`. */
    val utcTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)
    /** HH:mm in UTC. */
    val utcShort: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneOffset.UTC)

    fun snr(v: Int): String = Varicode.formatSNR(v).ifEmpty { "–" }
}

/** Great-circle distance and bearing between Maidenhead grids. */
object GridMath {
    data class Square(val lon: Double, val lat: Double, val width: Double, val height: Double)
    data class DistanceBearing(val km: Double, val bearing: Double)

    fun isValid(g: String): Boolean {
        val u = g.uppercase()
        if (u.length < 4) return false
        return u[0] in 'A'..'R' && u[1] in 'A'..'R' && u[2] in '0'..'9' && u[3] in '0'..'9'
    }

    /** The 6-character locator (e.g. "EM73tu") for a position in degrees, east and north positive. */
    fun grid(latitude: Double, longitude: Double): String {
        val lon = min(maxOf(longitude + 180, 0.0), 359.9999)
        val lat = min(maxOf(latitude + 90, 0.0), 179.9999)
        val field = { v: Double -> (65 + v.toInt()).toChar() }
        val sub = { v: Double -> (97 + v.toInt()).toChar() }
        return buildString {
            append(field(lon / 20)); append(field(lat / 10))
            append((48 + ((lon % 20) / 2).toInt()).toChar())
            append((48 + (lat % 10).toInt()).toChar())
            append(sub((lon % 2) * 12))
            append(sub((lat % 1) * 24))
        }
    }

    /** The square a locator covers, in degrees east and north: 2°×1° for 4
     *  characters, or the 5'×2.5' subsquare when a valid 6 are given. */
    fun square(g: String): Square? {
        val t = g.trim()
        if (!isValid(t)) return null
        val u = t.uppercase().map { it.code.toDouble() }
        var lon = (u[0] - 65) * 20 - 180 + (u[2] - 48) * 2
        var lat = (u[1] - 65) * 10 - 90 + (u[3] - 48)
        if (u.size >= 6 && u[4] in 65.0..88.0 && u[5] in 65.0..88.0) {
            lon += (u[4] - 65) * 2 / 24
            lat += (u[5] - 65) / 24
            return Square(lon, lat, 2.0 / 24, 1.0 / 24)
        }
        return Square(lon, lat, 2.0, 1.0)
    }

    /** The 4 or 6 character locator a grid string is placed by, e.g. "EM73" or "EM73TU". */
    fun mapKey(g: String): String? {
        val sq = square(g) ?: return null
        val t = g.trim().uppercase()
        return t.take(if (sq.width < 2) 6 else 4)
    }

    fun distanceBearing(from: String, to: String): DistanceBearing? {
        if (!isValid(from) || !isValid(to)) return null
        val (lon1, lat1) = Varicode.grid2deg(from)
        val (lon2, lat2) = Varicode.grid2deg(to)
        val r = 6371.0
        val p1 = lat1 * Math.PI / 180
        val p2 = lat2 * Math.PI / 180
        val dl = (lon2 - lon1) * Math.PI / 180
        val h = sin((p2 - p1) / 2) * sin((p2 - p1) / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val km = 2 * r * asin(min(1.0, sqrt(h)))
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        var brg = atan2(y, x) * 180 / Math.PI
        if (brg < 0) brg += 360
        return DistanceBearing(km, brg)
    }
}
