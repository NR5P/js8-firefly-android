package com.js8firefly.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.js8firefly.android.audio.AppClock
import com.js8firefly.android.audio.AudioIO
import com.js8firefly.android.audio.PTTMethod
import com.js8firefly.android.audio.WaterfallRenderer
import com.js8firefly.android.audio.utcSeconds
import com.js8firefly.android.radio.RigController
import com.js8firefly.core.BandActivityRow
import com.js8firefly.core.CallDetail
import com.js8firefly.core.ConversationLine
import com.js8firefly.core.DecodedText
import com.js8firefly.core.Inbox
import com.js8firefly.core.InboxMessage
import com.js8firefly.core.JS8ModeDecoder
import com.js8firefly.core.JS8RawDecode
import com.js8firefly.core.JS8Station
import com.js8firefly.core.JS8Submode
import com.js8firefly.core.PendingReply
import com.js8firefly.core.StationNotification
import com.js8firefly.core.Varicode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Marker drawn on the waterfall for each decode. */
data class DecodeMarker(
    val id: UUID = UUID.randomUUID(),
    val frequency: Float,
    val bandwidth: Int,
    val label: String,
    val date: Instant,
    val submode: JS8Submode,
)

/**
 * Central coordinator: audio, decoding, transmit timing, PTT, rig control
 * and the JS8 station engine. Every property the UI reads is Compose snapshot
 * state. Use from the main thread only.
 */
class AppModel(context: Context) {
    private val context: Context = context.applicationContext

    private var _settings by mutableStateOf(AppSettings())
    /** Assigning runs the same side effects as Swift's `didSet`. */
    var settings: AppSettings
        get() = _settings
        set(value) {
            val old = _settings
            _settings = value
            settingsChanged(old)
        }

    /** Kotlin can't mutate nested data-class fields in place: `updateSettings { it.copy(...) }`. */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
    }

    val station: JS8Station
    val audio = AudioIO(this.context)
    val rig = RigController(this.context)
    val locator = GridLocator(this.context)
    private val waterfall = WaterfallRenderer()

    // UI snapshots
    var bandRows by mutableStateOf<List<BandActivityRow>>(emptyList()); private set
    var calls by mutableStateOf<List<CallDetail>>(emptyList()); private set
    var conversation by mutableStateOf<List<ConversationLine>>(emptyList()); private set
    var bandConversation by mutableStateOf<List<ConversationLine>>(emptyList()); private set
    var inbox by mutableStateOf<List<InboxMessage>>(emptyList()); private set
    var pendingReplies by mutableStateOf<List<PendingReply>>(emptyList()); private set
    var waterfallImage by mutableStateOf<ImageBitmap?>(null); private set
    var spectrum by mutableStateOf<List<Float>>(emptyList()); private set
    var periodMarks by mutableStateOf<List<WaterfallRenderer.PeriodMark>>(emptyList()); private set
    var markers by mutableStateOf<List<DecodeMarker>>(emptyList()); private set
    var inputLevel by mutableStateOf(-120f); private set
    /** When the input last came within 0.5 dB of full scale. */
    var lastClip by mutableStateOf(Instant.EPOCH); private set
    /** Hardware RX gain, or null when the audio interface doesn't expose one (always on Android). */
    var inputGain by mutableStateOf<Float?>(null); private set
    var isTransmitting by mutableStateOf(false); private set
    var isTuning by mutableStateOf(false); private set
    var txText by mutableStateOf(""); private set
    var queuedFrames by mutableStateOf(0); private set
    var heartbeatLoop by mutableStateOf(false); private set
    var cqLoop by mutableStateOf(false); private set
    var averageDT by mutableStateOf(0f); private set
    var decodeCount by mutableStateOf(0); private set
    var lastDecodeInfo by mutableStateOf(""); private set
    var lastError by mutableStateOf<String?>(null); private set
    /** The error on screen came from the audio engine, so it clears once input runs again. */
    private var lastErrorIsAudio = false
    var routeDescription by mutableStateOf(""); private set
    var usbConnected by mutableStateOf(false); private set
    var audioRunning by mutableStateOf(false); private set
    var banner by mutableStateOf<String?>(null)
    /** True between start() and stop(). */
    var isStarted by mutableStateOf(false); private set

    private var _selectedCallsign by mutableStateOf("")
    var selectedCallsign: String
        get() = _selectedCallsign
        set(value) {
            station.selectedCallsign = value.uppercase()
            _selectedCallsign = station.selectedCallsign
            refresh()
        }

    private var _draft by mutableStateOf("")
    /** Outgoing message box. Non-empty text pauses auto-replies, as in JS8Call. */
    var draft: String
        get() = _draft
        set(value) {
            _draft = value
            if (station.draftText != value) station.draftText = value
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoders = HashMap<JS8Submode, JS8ModeDecoder>()
    private val decodeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "js8.decode").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()
    private val lastDecodedPeriod = HashMap<JS8Submode, Double>()
    private var decodesInFlight = 0
    private val recentDT = ArrayList<Float>()
    private val timers = ArrayList<Job>()
    private var catPTTOn = false
    private var lastRefresh = 0L
    private var started = false
    /** Band chat lines already checked against the watch list, and when each term last notified. */
    private val watchSeenLines = HashSet<UUID>()
    private val watchLastAlert = HashMap<String, Long>()
    private var lastAudioRestart = 0L
    private var demo: DemoAir? = null

    /** True when scripted demo stations stand in for the radio (screenshots, debug builds). */
    private val isDemo: Boolean get() = demo != null
    /** Demo settings live in memory only. */
    private val demoMode = DemoAir.isEnabled(this.context)

    /** Set by MainActivity: shows the system notification permission prompt. */
    var notificationPermissionRequester: (() -> Unit)? = null

    init {
        var s = AppSettings.load(this.context)
        // Every launch starts on Normal, the speed most stations are listening on.
        s = s.copy(submode = JS8Submode.normal)
        val demoOn = DemoAir.isEnabled(this.context)
        if (demoOn) s = DemoAir.configure(s)
        _settings = s
        AppClock.offset = s.clockOffset
        val support = File(this.context.filesDir, "JS8Call").apply { mkdirs() }
        // Each demo run starts with an empty inbox.
        val inboxFile: File? = if (demoOn) null else File(support, "inbox.json")
        station = JS8Station(s.station, Inbox(inboxFile), clock = { AppClock.now() })
        applyStationSettings()
        station.onNotify = { kind, text -> notify(kind, text) }
        station.inbox.onChange = {
            scope.launch(Dispatchers.Main) { inbox = station.inbox.messages.reversed() }
        }
        inbox = station.inbox.messages.reversed()
        rig.onFrequency = { hz ->
            if (settings.dialFrequency != hz) settings = settings.copy(dialFrequency = hz)
        }
        waterfall.rangeDB = s.waterfallRangeDB
        waterfall.brightness = s.waterfallBrightness
        locator.onGrid = { grid ->
            if (settings.station.grid != grid) settings = settings.copy(station = settings.station.copy(grid = grid))
        }
        audio.onError = { msg ->
            scope.launch(Dispatchers.Main) {
                lastError = msg
                lastErrorIsAudio = true
            }
        }
        audio.onInputLevel = { l, peak ->
            scope.launch(Dispatchers.Main) {
                inputLevel = l
                if (peak >= 0.944f) lastClip = Instant.now()
            }
        }
        audio.onStateChange = { updateAudioState() }
        audio.onRouteChange = {
            restartAudio()
            banner = if (audio.isUSBInterfaceConnected) "USB audio interface connected" else "Audio route changed"
            adaptPTTToInterface()
            updateAudioState()
        }
        audio.onConfigurationChange = {
            // Restarting can itself post a change; skip those (the watchdog covers the rest).
            if (SystemClock.elapsedRealtime() - lastAudioRestart > 2000) restartAudio()
        }
    }

    // MARK: Lifecycle

    /** Starts audio, timers and rig control. MainActivity calls it after the permission prompt. */
    fun start() {
        if (started) return
        started = true
        isStarted = true
        if (DemoAir.isEnabled(context)) {
            audio.spectrum.onRow = { row -> waterfall.add(row) }
            demo = DemoAir(audio.ring, audio.spectrum) { level -> inputLevel = level }
            demo?.start()
            requestNotificationPermission()
            audioRunning = true
            startTimers()
            return
        }
        audio.outputLevel = settings.tx.outputLevel
        audio.preferredInputGain = settings.inputGain
        audio.spectrum.onRow = { row -> waterfall.add(row) }
        audio.inputChannel = settings.inputChannel

        if (!AudioIO.hasRecordPermission(context)) {
            lastError = micMessage
            lastErrorIsAudio = false
        }
        audio.start()
        adaptPTTToInterface()
        updateAudioState()

        if (settings.hasOnboarded) requestNotificationPermission()

        rig.configure(settings.rig)
        if (settings.autoGrid && !isDemo) locator.startTracking()
        startTimers()
    }

    /** Stops everything (the notification's Stop action). start() brings it back. */
    fun stop() {
        if (!started) return
        halt()
        started = false
        isStarted = false
        timers.forEach { it.cancel() }
        timers.clear()
        demo?.stop()
        demo = null
        audio.stop()
        rig.disconnect()
        locator.stopTracking()
        audioRunning = false
    }

    /** MainActivity.onResume: Swift's didBecomeActive handling, plus a late microphone grant. */
    fun onResume() {
        if (!started || isDemo) return
        if (AudioIO.hasRecordPermission(context) && lastError == micMessage) lastError = null
        if (audio.isStalled || (!audio.inputRunning && AudioIO.hasRecordPermission(context))) restartAudio()
    }

    private fun startTimers() {
        fun every(ms: Long, block: () -> Unit) = scope.launch {
            while (isActive) {
                delay(ms)
                block()
            }
        }
        timers += every(50) { txLoop() }
        timers += every(200) { decodeLoop() }
        timers += every(1000) { secondLoop() }
        timers += every(100) { waterfallLoop() }
    }

    private fun updateAudioState() {
        if (isDemo) return
        audioRunning = audio.inputRunning
        clearAudioErrorIfRecovered()
        routeDescription = audio.routeDescription
        usbConnected = audio.isUSBInterfaceConnected
        inputGain = audio.inputGain
    }

    // MARK: Settings

    /** QMX/QDX carry TX audio over their own USB sound card and key on that audio
     *  (their Digi VOX setting), so the Digirig keying tone would only corrupt the signal. */
    private fun adaptPTTToInterface() {
        val name = audio.usbInterfaceName?.uppercase() ?: return
        if (!(name.contains("QMX") || name.contains("QDX")) || settings.tx.pttMethod != PTTMethod.digirigTone) return
        settings = settings.copy(tx = settings.tx.copy(pttMethod = PTTMethod.vox))
        banner = "QMX/QDX detected: PTT set to VOX. Set Digi › VOX to ON on the radio."
    }

    private fun applyStationSettings() {
        val s = _settings
        station.config = s.station
        station.submode = s.submode
        waterfall.periodSeconds = s.submode.period.toDouble()
        station.offset = s.offset
        station.dialFrequency = s.dialFrequency
        station.txEnabled = s.txEnabled
        station.heartbeatNetworking = s.heartbeatNetworking
        station.txDelay = s.txDelay
    }

    private fun settingsChanged(old: AppSettings) {
        applyStationSettings()
        val s = settings
        AppClock.offset = s.clockOffset
        audio.inputChannel = s.inputChannel
        audio.outputLevel = s.tx.outputLevel
        if (s.inputGain != old.inputGain) {
            audio.preferredInputGain = s.inputGain
            audio.applyInputGain()
            inputGain = audio.inputGain
        }
        waterfall.rangeDB = s.waterfallRangeDB
        waterfall.brightness = s.waterfallBrightness
        if (s.rig != old.rig && started) rig.configure(s.rig)
        if (s.autoGrid != old.autoGrid && started) {
            if (s.autoGrid) locator.startTracking() else locator.stopTracking()
        }
        if (s.dialFrequency != old.dialFrequency && rig.isConnected && rig.frequency != s.dialFrequency) {
            rig.setFrequency(s.dialFrequency)
        }
        if (!demoMode) s.save(context)
        refresh(force = true)
    }

    /** Set the audio offset (Hz) where we transmit and listen. */
    fun setOffset(hz: Int) {
        val bw = settings.submode.bandwidth
        settings = settings.copy(offset = max(100, min(3000 - bw, hz)))
    }

    /** Move to a tapped waterfall position, snapping onto a recently decoded signal under the finger. */
    fun tuneNear(hz: Double) {
        val bw = settings.submode.bandwidth
        val slop = 25.0
        val signals = bandRows.map { it.offset to it.submode.bandwidth } +
            markers.map { it.frequency.roundToInt() to it.bandwidth }
        val hit = signals
            .filter { hz >= it.first - slop && hz <= (it.first + it.second) + slop }
            .minByOrNull { abs(it.first + it.second / 2 - hz) }
        setOffset(hit?.first ?: Math.round(hz - bw / 2.0).toInt())
    }

    /** Listen on a band line's offset and select its sender, so the rest of
     *  that QSO lands in the conversation pane. */
    fun join(line: ConversationLine) {
        setOffset(line.offset)
        sender(line)?.let { selectedCallsign = it }
    }

    companion object {
        private const val TAG = "JS8Model"
        private const val micMessage = "Microphone access is needed to receive. Enable it in Settings."
        /** At most one notification per watch term in this long. */
        private const val watchAlertInterval = 600_000L

        fun sender(of: ConversationLine): String? {
            val colon = of.text.indexOf(':')
            if (colon < 0) return null
            val call = of.text.substring(0, colon).trim()
            return if (Varicode.parseCallsigns(call).firstOrNull() == call) call else null
        }
    }

    fun setDial(hz: Long) {
        settings = settings.copy(dialFrequency = hz)
    }

    /** Save a dial frequency under a label, replacing any entry already on it. */
    fun saveFrequency(hz: Long, label: String, replacing: UUID? = null) {
        val l = label.trim()
        val list = settings.customFrequencies.filter { it.id != replacing && it.frequency != hz } +
            SavedFrequency(replacing ?: UUID.randomUUID(), hz, l)
        settings = settings.withCustomFrequencies(list)
    }

    fun deleteFrequency(id: UUID) {
        settings = settings.withCustomFrequencies(settings.customFrequencies.filter { it.id != id })
    }

    // MARK: Transmit

    fun send(text: String): Boolean {
        val ok = station.send(text.uppercase())
        if (!ok && settings.station.callsign.isEmpty()) {
            lastError = "Set your callsign in Settings first."
            lastErrorIsAudio = false
        }
        refresh(force = true)
        return ok
    }

    fun sendCQ() {
        station.sendCQ()
        refresh(force = true)
    }

    fun toggleHeartbeat() {
        station.setHeartbeatLoop(!station.heartbeatLoopActive)
        refresh(force = true)
    }

    fun toggleCQLoop() {
        station.setCQLoop(!station.cqLoopActive)
        refresh(force = true)
    }

    fun halt() {
        station.haltTransmit()
        audio.stopTransmit()
        stopTune()
        if (catPTTOn) keyPTT(false)
        if (station.isTransmitting) station.transmissionFinished()
        refresh(force = true)
    }

    fun startTune() {
        if (!settings.txEnabled) return
        isTuning = true
        audio.tune(settings.offset.toDouble(), settings.tx)
        keyPTT(true)
    }

    fun stopTune() {
        if (!isTuning) return
        isTuning = false
        audio.stopTransmit()
        keyPTT(false)
    }

    fun approve(r: PendingReply) {
        station.approvePendingReply(r.id)
        refresh(force = true)
    }

    fun reject(r: PendingReply) {
        station.rejectPendingReply(r.id)
        refresh(force = true)
    }

    fun markRead(m: InboxMessage) {
        if (m.type == "UNREAD") station.inbox.setType(m.id, "READ")
    }

    fun deleteMessage(m: InboxMessage) {
        station.inbox.delete(m.id)
    }

    fun preview(text: String): List<DecodedText> = station.preview(text.uppercase())

    private fun keyPTT(on: Boolean) {
        catPTTOn = on
        when (settings.tx.pttMethod) {
            PTTMethod.cat -> rig.setPTT(on)
            PTTMethod.serialRTS -> rig.setRTS(on)
            PTTMethod.serialDTR -> rig.setDTR(on)
            else -> {}
        }
    }

    private fun txLoop() {
        val now = AppClock.now()
        if (!isTuning) {
            station.pollTransmit(now)?.let { req ->
                if (!isDemo) audio.transmit(req, settings.tx)
                txText = req.displayText
                refresh(force = true)
            }
        }
        station.currentTx?.let { tx ->
            val payloadStart = tx.periodStart.utcSeconds + tx.submode.startDelayMS / 1000.0
            val t = now.utcSeconds
            if (!catPTTOn && t >= payloadStart - settings.tx.leadTime) {
                keyPTT(true)
            }
            val end = payloadStart + tx.submode.dataDuration + settings.tx.tailTime
            if (!audio.isPlaying && t >= payloadStart || t > end + 1.0) {
                if (catPTTOn) keyPTT(false)
                audio.stopTransmit()
                station.transmissionFinished()
                txText = ""
                refresh(force = true)
            }
        }
        val tx = station.isTransmitting || isTuning
        if (tx != isTransmitting) isTransmitting = tx
    }

    // MARK: Receive

    private fun decodeLoop() {
        val now = AppClock.nowSeconds()
        val modes = if (settings.decodeAllSpeeds) JS8Submode.allCases else listOf(settings.submode)
        for (mode in modes) {
            val p = mode.period.toDouble()
            val periodStart = floor(now / p) * p
            val readyAt = periodStart + mode.samplesNeeded / 12000.0 + 0.05
            if (now < readyAt || lastDecodedPeriod[mode] == periodStart) continue
            lastDecodedPeriod[mode] = periodStart
            if (!station.shouldDecode(AppClock.now())) continue
            if (!isDemo) {
                val s = station.lastTxStart?.utcSeconds
                if (s != null && s >= periodStart - 1 && s <= readyAt) continue
            }
            if (decodesInFlight > 4) continue
            val samples = audio.ring.read((periodStart * 12000).toLong(), mode.samplesNeeded)
            val nfqso = settings.offset
            val dial = settings.dialFrequency
            val decoder = decoders.getOrPut(mode) { JS8ModeDecoder(mode) }
            decodesInFlight += 1
            scope.launch {
                var ms = 0L
                var rms = 0f
                val results = withContext(decodeDispatcher) {
                    var sum = 0f
                    for (v in samples) sum += v * v
                    rms = sqrt(sum / max(samples.size, 1))
                    val t0 = SystemClock.elapsedRealtime()
                    val out = ArrayList<JS8RawDecode>()
                    try {
                        decoder.decode(samples, nfqso) { out.add(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "decode failed", e)
                    }
                    ms = SystemClock.elapsedRealtime() - t0
                    out
                }
                Log.d(TAG, String.format(Locale.US, "JS8 decode %s: %d frame(s) in %d ms (rms %.0f)",
                    mode.displayName, results.size, ms, rms))
                decodesInFlight -= 1
                lastDecodeInfo = "${mode.displayName}: ${results.size} in $ms ms"
                handleDecodes(results, dial)
            }
        }
    }

    private fun handleDecodes(results: List<JS8RawDecode>, dial: Long) {
        if (results.isEmpty()) return
        val newMarkers = markers.toMutableList()
        for (r in results) {
            station.handleDecode(r, dial)
            val text = DecodedText(r)
            val label = if (text.isCompound) text.compoundCall else (text.directed.firstOrNull() ?: text.message.take(10))
            newMarkers.add(DecodeMarker(frequency = r.frequency, bandwidth = r.submode.bandwidth, label = label,
                date = Instant.now(), submode = r.submode))
            recentDT.add(r.dt)
        }
        decodeCount += results.size
        if (recentDT.size > 40) recentDT.subList(0, recentDT.size - 40).clear()
        averageDT = if (recentDT.isEmpty()) 0f else recentDT.sorted()[recentDT.size / 2]
        val cutoff = Instant.now().minusSeconds(45)
        markers = newMarkers.filter { it.date.isAfter(cutoff) }
        station.processActivity()
        refresh(force = true)
    }

    /** Shift the clock by the median decode time offset. */
    fun syncClockToDecodes() {
        if (recentDT.isEmpty()) return
        settings = settings.copy(clockOffset = settings.clockOffset - averageDT.toDouble())
        recentDT.clear()
        averageDT = 0f
        banner = String.format(Locale.US, "Clock adjusted to %+.2f s", settings.clockOffset)
    }

    /** Restart both audio engines; route and running state update when it completes. */
    private fun restartAudio() {
        if (isDemo) return
        lastAudioRestart = SystemClock.elapsedRealtime()
        audio.restart()
    }

    private fun secondLoop() {
        // Watchdog: restart engines that stopped delivering audio.
        if (!isDemo && audio.isStalled && SystemClock.elapsedRealtime() - lastAudioRestart > 10_000) {
            Log.w(TAG, "JS8 audio stalled; restarting engines")
            restartAudio()
        }
        station.tick()
        val cutoff = Instant.now().minusSeconds(45)
        if (markers.any { !it.date.isAfter(cutoff) }) markers = markers.filter { it.date.isAfter(cutoff) }
        refresh()
    }

    private fun waterfallLoop() {
        waterfallImage = waterfall.makeImage()
        spectrum = waterfall.latestSpectrum
        periodMarks = waterfall.periodMarks
    }

    fun refresh(force: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastRefresh < 500) return
        lastRefresh = nowMs
        bandRows = station.bandActivityRows(AppClock.now())
        calls = station.callActivity.values.sortedByDescending { it.utc }
        conversation = station.conversation
        bandConversation = station.bandConversation
        checkWatchList()
        pendingReplies = station.pendingReplies
        queuedFrames = station.txFramesRemaining
        heartbeatLoop = station.heartbeatLoopActive
        if (station.draftText != _draft) _draft = station.draftText
        if (station.selectedCallsign != _selectedCallsign) _selectedCallsign = station.selectedCallsign
        cqLoop = station.cqLoopActive
        // Keep settings mirrors in sync with automatic offset changes.
        if (station.offset != settings.offset && !station.isMessageQueuedForTransmit) {
            settings = settings.copy(offset = station.offset)
        }
    }

    fun dismissError() { lastError = null }

    /** Drops an audio error from the banner once input is running again. */
    private fun clearAudioErrorIfRecovered() {
        if (!audio.inputRunning || !lastErrorIsAudio) return
        lastError = null
        lastErrorIsAudio = false
    }

    fun clearConversation() {
        station.clearConversation()
        refresh(force = true)
    }

    fun clearBandConversation() {
        station.clearBandConversation()
        refresh(force = true)
    }

    fun clearBand() {
        station.clearBandActivity()
        refresh(force = true)
    }

    // MARK: Notifications

    fun requestNotificationPermission() {
        if (!settings.notificationsEnabled) return
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return
        notificationPermissionRequester?.invoke()
    }

    private fun notify(kind: StationNotification, text: String) {
        when (kind) {
            StationNotification.directed, StationNotification.inbox ->
                postNotification(if (kind == StationNotification.inbox) "New JS8 message" else "JS8 directed message", text)
            else -> {}
        }
    }

    /** Notifies (in the background) when a new band chat line matches the watch list.
     *  Lines addressed to us are left to the directed-message notification. */
    private fun checkWatchList() {
        val mycall = settings.station.callsign
        val lines = bandConversation
        for (line in lines) {
            if (line.isTx || watchSeenLines.contains(line.id)) continue
            val item = settings.watch(line.text) ?: continue
            watchSeenLines.add(line.id)
            if (mycall.isNotEmpty() && line.text.contains(" $mycall")) continue
            if (!settings.watchNotify) continue
            val now = SystemClock.elapsedRealtime()
            val last = watchLastAlert[item.term]
            if (last != null && now - last < watchAlertInterval) continue
            if (postNotification("Heard ${item.term}", line.text)) watchLastAlert[item.term] = now
        }
        if (watchSeenLines.size > lines.size) {
            watchSeenLines.retainAll(lines.map { it.id }.toSet())
        }
    }

    private var notificationId = 100

    /** Posts a notification if they're on and the app isn't in front. */
    @SuppressLint("MissingPermission")
    private fun postNotification(title: String, body: String): Boolean {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!settings.notificationsEnabled || foreground) return false
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, FireflyApp.messagesChannel)
            .setSmallIcon(R.drawable.ic_stat_firefly)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(notificationId++, n)
        } catch (e: SecurityException) {
            return false
        }
        return true
    }
}
