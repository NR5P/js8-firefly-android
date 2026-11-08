package com.js8firefly.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.js8firefly.core.JS8Modulator
import com.js8firefly.core.TxRequest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** UTC seconds since 1970 as a Double (Swift's `timeIntervalSince1970`). */
internal val Instant.utcSeconds: Double get() = epochSecond + nano / 1e9

/** How the transmitter is keyed. */
enum class PTTMethod(val rawValue: String) {
    /** Digirig Lite "PTT by tone": JS8 audio on the left channel, a keying
     *  tone on the right channel which drives the Digirig's PTT circuit. */
    digirigTone("digirigTone"),
    /** Audio on both channels; the interface or radio keys on audio (VOX). */
    vox("vox"),
    /** PTT command through the configured rig control (CAT / rigctld / flrig). */
    cat("cat"),
    /** RTS line of a USB serial port (e.g. Digirig Mobile). */
    serialRTS("serialRTS"),
    /** DTR line of a USB serial port. */
    serialDTR("serialDTR"),
    /** No keying (receive-only or external PTT). */
    none("none");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            digirigTone -> "Digirig Lite – PTT tone (right ch.)"
            vox -> "VOX – audio on both channels"
            cat -> "CAT / rig control command"
            serialRTS -> "Serial RTS (Digirig Mobile, USB serial)"
            serialDTR -> "Serial DTR (USB serial)"
            none -> "None (receive only)"
        }

    companion object {
        /** Android can open USB serial ports, so every method is available (as on the Mac). */
        val available: List<PTTMethod> get() = entries.toList()
        val allCases: List<PTTMethod> get() = entries.toList()
        fun fromRaw(raw: String?): PTTMethod? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Audio settings that the render thread reads. */
data class TxAudioSettings(
    val pttMethod: PTTMethod = PTTMethod.digirigTone,
    val toneFrequency: Double = 1000.0,
    val toneLevel: Float = 0.7f,
    val leadTime: Double = 0.15,
    val tailTime: Double = 0.10,
    val outputLevel: Float = 0.5f,
    val swapChannels: Boolean = false,
)

/** One transmission prepared for the render thread. */
class TxPlayback(
    val payload: FloatArray,
    val payloadStart: Double,      // UTC seconds of payload sample 0
    val settings: TxAudioSettings,
    val rate: Double,
    val isTune: Boolean = false,
) {
    var cursor: Long? = null        // render-thread state
    var tonePhase: Double = 0.0
    @Volatile var finished = false
}

/**
 * Audio input (receive, AudioRecord) and output (transmit, AudioTrack).
 * USB audio interfaces are preferred automatically and followed across hot-plug.
 * Receive audio is resampled to 12 kHz, scaled to int16 units and written into
 * [ring] by absolute sample time, as the iPhone app does.
 */
class AudioIO(context: Context) {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    val ring = RxRingBuffer()
    val spectrum = SpectrumAnalyzer()

    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "js8.audio.control") }
    private val txRef = AtomicReference<TxPlayback?>(null)
    @Volatile private var outputRate: Double = 48000.0

    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var inputThread: Thread? = null
    @Volatile private var outputThread: Thread? = null
    @Volatile private var running = false

    @Volatile var inputRunning = false
        private set
    @Volatile var outputRunning = false
        private set
    /** Set once audio has been started with microphone permission; from then on the
     *  watchdog keeps retrying while input isn't running. */
    @Volatile private var wantsAudio = false
    @Volatile private var reportedNoInput = false

    /** Input channel: 0 = left, 1 = right, -1 = mix. */
    @Volatile var inputChannel = 0
    /** dBFS of the most recent input block. */
    @Volatile var inputLevel: Float = -120f
        private set
    /** RMS level (dBFS) and peak sample (0...1) of each input block (audio thread). */
    @Volatile var onInputLevel: ((Float, Float) -> Unit)? = null
    /** Hardware input gain (0...1) to apply when the interface exposes one (never on Android). */
    var preferredInputGain: Float? = null

    /** TX audio level, applied immediately, including to a transmission in progress. */
    @Volatile var outputLevel: Float = 0.5f
    /** Called (any thread) with an error message. */
    @Volatile var onError: ((String) -> Unit)? = null
    /** Called on the main thread when an engine died on its own (device removed, server died). */
    var onConfigurationChange: (() -> Unit)? = null
    /** Called on the main thread when audio devices are added or removed (USB hot-plug). */
    var onRouteChange: (() -> Unit)? = null
    /** Called on the main thread after each start / stop / restart has completed. */
    var onStateChange: (() -> Unit)? = null

    private var deviceSignature: String? = null
    private var observingDevices = false

    companion object {
        private const val TAG = "JS8Audio"
        private const val inRate = 48000
        private const val blockFrames = 4096

        fun hasRecordPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        private fun isUSB(d: AudioDeviceInfo) =
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET

        fun deviceName(d: AudioDeviceInfo?): String {
            d ?: return "–"
            return when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microphone"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Receiver"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
                else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
            }
        }
    }

    private val hasRecordPermission get() = hasRecordPermission(context)

    /** True when input should be delivering audio but no samples have arrived for a few
     *  seconds, or either engine has stopped on its own. */
    val isStalled: Boolean
        get() {
            if (!wantsAudio) return false
            if (!inputRunning || inputThread?.isAlive != true || (outputRunning && outputThread?.isAlive != true)) return true
            val latest = ring.latestIndex
            return latest < 0 || AppClock.nowSeconds() * RxRingBuffer.rate - latest > 3.0 * RxRingBuffer.rate
        }

    // MARK: Devices

    private fun usbInput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isUSB(it) && it.isSource }

    private fun usbOutput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isUSB(it) && it.isSink }

    /** Human readable description of the current route. */
    val routeDescription: String
        get() {
            val inDev = (if (Build.VERSION.SDK_INT >= 24) record?.routedDevice else null)
                ?: usbInput()
                ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val outDev = (if (Build.VERSION.SDK_INT >= 24) track?.routedDevice else null)
                ?: usbOutput()
                ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val ins = if (inDev == null) "–" else deviceName(inDev)
            val outCh = outDev?.channelCounts?.maxOrNull()?.let { min(it, 2) } ?: 2
            val outs = if (outDev == null) "–" else "${deviceName(outDev)} ($outCh ch)"
            return "In: $ins  •  Out: $outs"
        }

    val isUSBInterfaceConnected: Boolean get() = usbInput() != null

    /** Name of the connected USB audio interface, if any (e.g. "QMX Transceiver"). */
    val usbInterfaceName: String? get() = usbInput()?.productName?.toString()

    /** Current hardware input gain; Android exposes none for USB or built-in inputs. */
    val inputGain: Float? get() = null

    fun applyInputGain() { /* No supported API for hardware input gain on Android. */ }

    private fun observeDevices() {
        if (observingDevices) return
        observingDevices = true
        deviceSignature = currentSignature()
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = devicesChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = devicesChanged()
        }, main)
    }

    private fun currentSignature(): String =
        audioManager.getDevices(AudioManager.GET_DEVICES_ALL).map { "${it.id}:${it.type}:${it.isSource}" }.sorted().joinToString(",")

    private fun devicesChanged() {
        // Registration replays the current devices; only act on real changes.
        val sig = currentSignature()
        if (sig == deviceSignature) return
        deviceSignature = sig
        onRouteChange?.invoke()
    }

    // MARK: Start / stop

    /** Starts both engines on the audio control thread; [onStateChange] follows on main. */
    fun start() {
        main.post { observeDevices() }
        control.execute {
            startInternal()
            main.post { onStateChange?.invoke() }
        }
    }

    fun stop() {
        control.execute {
            stopInternal()
            main.post { onStateChange?.invoke() }
        }
    }

    fun restart() {
        control.execute {
            stopInternal()
            startInternal()
            main.post { onStateChange?.invoke() }
        }
    }

    private fun startInternal() {
        if (hasRecordPermission) wantsAudio = true
        running = true
        applyInputGain()
        startInput()
        startOutput()
    }

    private fun stopInternal() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        inputThread?.join(1000)
        outputThread?.join(1000)
        inputThread = null
        outputThread = null
        try { record?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        record = null
        track = null
        inputRunning = false
        outputRunning = false
    }

    private class RecordConfig(val source: Int, val channels: Int, val encoding: Int)

    private fun buildRecord(): Pair<AudioRecord, RecordConfig>? {
        val unprocessed = Build.VERSION.SDK_INT >= 24 &&
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        val usb = usbInput()
        for (enc in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            for (src in sources) {
                for (ch in listOf(2, 1)) {
                    val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                    val minBuf = AudioRecord.getMinBufferSize(inRate, mask, enc)
                    if (minBuf <= 0) continue
                    val bytesPer = if (enc == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val size = max(minBuf, inRate / 2 * ch * bytesPer)
                    val r = try {
                        AudioRecord.Builder()
                            .setAudioSource(src)
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(inRate).setChannelMask(mask).setEncoding(enc).build())
                            .setBufferSizeInBytes(size)
                            .build()
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                    if (usb != null) r.setPreferredDevice(usb)
                    return r to RecordConfig(src, ch, enc)
                }
            }
        }
        return null
    }

    private fun startInput() {
        if (!hasRecordPermission) return
        val built = try { buildRecord() } catch (e: SecurityException) { null }
        if (built == null) {
            Log.w(TAG, "audio input not started: no usable AudioRecord configuration")
            if (!reportedNoInput) {
                reportedNoInput = true
                onError?.invoke("No audio input available")
            }
            return
        }
        reportedNoInput = false
        val (rec, cfg) = built
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("the microphone is in use by another app")
        } catch (e: Exception) {
            rec.release()
            onError?.invoke("Input start: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        record = rec
        inputRunning = true
        wantsAudio = true
        val t = Thread({ inputLoop(rec, cfg) }, "js8.audio.rx")
        inputThread = t
        t.start()
    }

    // 48 kHz → 12 kHz decimator: windowed-sinc low-pass, cutoff 5 kHz.
    private val firTaps: FloatArray = run {
        val n = 97
        val fc = 5000.0 / inRate
        val mid = (n - 1) / 2
        val h = DoubleArray(n) { i ->
            val m = i - mid
            val sinc = if (m == 0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
            sinc * w
        }
        val sum = h.sum()
        FloatArray(n) { (h[it] / sum).toFloat() }
    }

    private fun inputLoop(rec: AudioRecord, cfg: RecordConfig) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val channels = cfg.channels
        val isFloat = cfg.encoding == AudioFormat.ENCODING_PCM_FLOAT
        val fbuf = FloatArray(blockFrames * channels)
        val sbuf = ShortArray(if (isFloat) 0 else blockFrames * channels)
        val taps = firTaps
        val nt = taps.size
        val hist = FloatArray(nt + blockFrames)   // previous nt-1 inputs then this block
        var phase = 0                             // input index (in this block) of the next output
        val out = FloatArray(blockFrames / 4 + 2)
        var framesRead = 0L
        val ts = AudioTimestamp()
        val groupDelay = (nt - 1) / 2.0 / inRate
        while (running) {
            var got = 0
            val want = blockFrames * channels
            while (got < want && running) {
                val n = if (isFloat) rec.read(fbuf, got, want - got, AudioRecord.READ_BLOCKING)
                        else rec.read(sbuf, got, want - got)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read failed: $n")
                    inputRunning = false
                    if (running) main.post { onConfigurationChange?.invoke() }
                    return
                }
                if (n == 0) break
                got += n
            }
            if (!running) break
            val frames = got / channels
            if (frames == 0) continue
            if (!isFloat) for (i in 0 until got) fbuf[i] = sbuf[i] / 32768f

            // Mono mix / channel select into the history buffer.
            val ch = inputChannel
            var sumsq = 0f
            var peak = 0f
            val base = nt - 1
            for (i in 0 until frames) {
                val v: Float = if (ch < 0 || channels == 1) {
                    var s = 0f
                    for (c in 0 until channels) s += fbuf[i * channels + c]
                    s / channels
                } else {
                    fbuf[i * channels + min(ch, channels - 1)]
                }
                hist[base + i] = v
                sumsq += v * v
                peak = max(peak, abs(v))
            }
            val level = 10 * log10(max(sumsq / max(frames, 1), 1e-12f))
            inputLevel = level

            // Capture time of the first sample of this block in UTC.
            var time = AppClock.nowSeconds() - frames.toDouble() / inRate
            if (Build.VERSION.SDK_INT >= 24 &&
                rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                val firstNs = ts.nanoTime + ((framesRead - ts.framePosition) * 1e9 / inRate).roundToLong()
                time = AppClock.nowSeconds() - (System.nanoTime() - firstNs) / 1e9
            }
            framesRead += frames

            // Decimate by 4. Output k uses input (phase + 4k) of this block.
            var n = 0
            val firstOutTime = time + phase.toDouble() / inRate - groupDelay
            var i = phase
            while (i < frames) {
                var acc = 0f
                val end = base + i
                for (k in 0 until nt) acc += taps[k] * hist[end - k]
                // Scale to 16-bit sample units like JS8Call's int16 buffer.
                out[n++] = acc * 32767f
                i += 4
            }
            phase = i - frames
            // Keep the last nt-1 inputs for the next block.
            System.arraycopy(hist, frames, hist, 0, nt - 1)

            if (n > 0) {
                ring.write(out, firstOutTime, n)
                spectrum.process(out, n)
            }
            onInputLevel?.invoke(level, peak)
        }
    }

    private fun startOutput() {
        val rate = 48000
        val mask = AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            val b = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, rate / 10 * 2 * 4))
            if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            b.build()
        } catch (e: Exception) {
            onError?.invoke("Output start: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            onError?.invoke("Output start: audio output unavailable")
            return
        }
        usbOutput()?.let { t.setPreferredDevice(it) }
        try {
            t.play()
        } catch (e: Exception) {
            t.release()
            onError?.invoke("Output start: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        outputRate = t.sampleRate.toDouble()
        track = t
        outputRunning = true
        val th = Thread({ outputLoop(t) }, "js8.audio.tx")
        outputThread = th
        th.start()
    }

    private fun outputLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = 480
        val buf = FloatArray(frames * 2)
        var written = 0L
        val ts = AudioTimestamp()
        while (running) {
            render(t, buf, frames, written, ts)
            var off = 0
            while (off < buf.size && running) {
                val n = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "AudioTrack.write failed: $n")
                    outputRunning = false
                    if (running) main.post { onConfigurationChange?.invoke() }
                    return
                }
                off += n
            }
            written += frames
        }
    }

    // MARK: Transmit

    val outputSampleRate: Double get() = outputRate

    /** Schedule a frame. The payload starts at `periodStart + startDelay`. */
    fun transmit(req: TxRequest, settings: TxAudioSettings) {
        val payload = JS8Modulator.synthesize(tones = req.tones.toIntArray(), frequency = req.offset.toDouble(),
            submode = req.submode, sampleRate = outputRate, amplitude = 1f)
        val start = req.periodStart.utcSeconds + req.submode.startDelayMS / 1000.0
        txRef.set(TxPlayback(payload, start, settings, outputRate))
    }

    /** Continuous carrier at `frequency` for tuning, until `stopTransmit()`. */
    fun tune(frequency: Double, settings: TxAudioSettings) {
        val seconds = 1.0
        val n = (outputRate * seconds).toInt()
        // Integer number of cycles so the loop is seamless.
        val cycles = Math.rint(frequency * seconds)
        val tone = FloatArray(n) { i -> sin(2 * PI * cycles * i / n).toFloat() }
        txRef.set(TxPlayback(tone, AppClock.nowSeconds() + settings.leadTime, settings, outputRate, isTune = true))
    }

    fun stopTransmit() { txRef.set(null) }

    /** True while audio (including PTT tail) is still being produced. */
    val isPlaying: Boolean get() = txRef.get()?.let { !it.finished } ?: false

    /** UTC seconds when the current transmission's audio ends (incl. tail). */
    val playbackEnd: Double?
        get() {
            val p = txRef.get() ?: return null
            if (p.isTune) return null
            return p.payloadStart + p.payload.size / p.rate + p.settings.tailTime
        }

    private fun render(t: AudioTrack, buf: FloatArray, frames: Int, written: Long, ts: AudioTimestamp) {
        buf.fill(0f)
        val playback = txRef.get() ?: return
        if (playback.finished) return
        val rate = playback.rate
        val s = playback.settings
        val level = outputLevel
        if (playback.cursor == null) {
            // Wall-clock time at which the first frame of this block is heard.
            var wall = AppClock.nowSeconds() + (written - t.playbackHeadPosition.toLong()) / rate
            if (t.getTimestamp(ts)) {
                val playNs = ts.nanoTime + ((written - ts.framePosition) * 1e9 / rate).roundToLong()
                wall = AppClock.nowSeconds() + (playNs - System.nanoTime()) / 1e9
            }
            playback.cursor = ((wall - playback.payloadStart) * rate).roundToLong()
        }
        var cursor = playback.cursor!!
        val leadFrames = (s.leadTime * rate).toLong()
        val tailFrames = (s.tailTime * rate).toLong()
        val count = playback.payload.size.toLong()
        val endFrame = if (playback.isTune) Long.MAX_VALUE else count + tailFrames
        val ramp = max(1, (0.004 * rate).toInt())
        val dphi = 2 * PI * s.toneFrequency / rate
        for (i in 0 until frames) {
            var payload = 0f
            if (cursor in 0 until count) {
                payload = playback.payload[cursor.toInt()] * level
            } else if (playback.isTune && cursor >= 0) {
                payload = playback.payload[(cursor % count).toInt()] * level
            }
            var tone = 0f
            if (s.pttMethod == PTTMethod.digirigTone && cursor >= -leadFrames && cursor < endFrame) {
                val fromStart = cursor + leadFrames
                val toEnd = if (playback.isTune) Long.MAX_VALUE else endFrame - cursor
                val env = min(1.0, min(fromStart, toEnd).toDouble() / ramp).toFloat()
                tone = sin(playback.tonePhase).toFloat() * s.toneLevel * env
                playback.tonePhase += dphi
                if (playback.tonePhase > 2 * PI) playback.tonePhase -= 2 * PI
            }
            if (s.pttMethod == PTTMethod.digirigTone) {
                if (s.swapChannels) { buf[2 * i] = tone; buf[2 * i + 1] = payload }
                else { buf[2 * i] = payload; buf[2 * i + 1] = tone }
            } else {
                buf[2 * i] = payload
                buf[2 * i + 1] = payload
            }
            cursor += 1
        }
        playback.cursor = cursor
        if (cursor >= endFrame) playback.finished = true
    }
}
