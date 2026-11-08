package com.js8firefly.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.js8firefly.android.audio.AppClock
import com.js8firefly.android.audio.RxRingBuffer
import com.js8firefly.android.audio.SpectrumAnalyzer
import com.js8firefly.core.Inbox
import com.js8firefly.core.JS8Modulator
import com.js8firefly.core.JS8Station
import com.js8firefly.core.JS8Submode
import com.js8firefly.core.StationConfig
import com.js8firefly.core.WatchColor
import com.js8firefly.core.WatchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Screenshot demo (debug builds only): scripted stations "on the air" whose frames
 * are modulated, mixed with noise and written into the receive ring and waterfall
 * in real time, so the real decoder and station engine fill the UI.
 *
 * Turn on with `adb shell am start -n com.js8firefly.app/com.js8firefly.android.MainActivity --ez demo true`
 * (and off with `--ez demo false`); the flag persists and the app restarts to apply it.
 */
class DemoAir(
    private val ring: RxRingBuffer,
    private val spectrum: SpectrumAnalyzer,
    private val onLevel: (Float) -> Unit,
) {
    companion object {
        private const val prefsName = "demo"
        const val mycall = "NR5P"
        const val mygrid = "EM12"
        /** Overall level, putting the receive meter near 33 dB, inside its target zone. */
        private const val gain = 0.16f

        fun isEnabled(context: Context): Boolean =
            BuildConfig.DEBUG && context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean("demo", false)

        /** Applies a `demo` intent extra; returns true when the app is restarting to apply it. */
        fun handleLaunchIntent(activity: Activity, intent: Intent?): Boolean {
            if (!BuildConfig.DEBUG || intent?.hasExtra("demo") != true) return false
            val on = intent.getBooleanExtra("demo", false)
            if (on == isEnabled(activity)) return false
            activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putBoolean("demo", on).commit()
            val relaunch = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(relaunch)
            activity.finish()
            Runtime.getRuntime().exit(0)
            return true
        }

        /** Settings for the demo station, applied in memory only. */
        fun configure(s: AppSettings): AppSettings =
            s.copy(
                station = s.station.copy(callsign = mycall, grid = mygrid),
                hasOnboarded = true,
                offset = 1500,
                dialFrequency = 7_078_000,
            ).withCustomFrequencies(listOf(
                SavedFrequency(frequency = 7_078_000, label = "Sunday net"),
                SavedFrequency(frequency = 3_578_000, label = "Texas ARES"),
                SavedFrequency(frequency = 14_080_000, label = "Sked with Q4LMB"),
            )).withWatchList(listOf(WatchItem(term = "Q4LMB", color = WatchColor.gold),
                WatchItem(term = "ARES", color = WatchColor.magenta)))

        private fun gaussian(): Float {
            val u1 = Random.nextDouble(Math.ulp(1.0), 1.0)
            val u2 = Random.nextDouble(0.0, 1.0)
            return (sqrt(-2 * ln(u1)) * cos(2 * PI * u2)).toFloat()
        }
    }

    private class Station(val engine: JS8Station, val amplitude: Float)
    private class Signal(val start: Long, val wave: FloatArray, val amplitude: Float)
    private class Cue(val period: Int, val call: String, val to: String, val text: String)

    private val stations = LinkedHashMap<String, Station>()
    private val cues: List<Cue>
    private val signals = ArrayList<Signal>()
    private val transmitting = ArrayList<JS8Station>()
    private var nextSample: Long = -1
    private var firstPeriod = 0L
    private var lastPeriod = -1L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timer: Job? = null

    init {
        add("Q7GTK", "DM43", offset = 1650, amplitude = 260f)
        add("Q5QRV", "EM20", offset = 1210, amplitude = 400f)
        add("Q3NXR", "FN03", offset = 760, amplitude = 110f)
        add("Q4LMB", "EM73", offset = 1500, amplitude = 180f)
        add("Q0VBQ", "EN34", offset = 1950, amplitude = 90f)
        add("Q9ZTW", "IO91", offset = 890, amplitude = 70f)
        add("Q7ABP", "DN17", offset = 2350, amplitude = 150f)
        cues = listOf(
            Cue(0, "Q7GTK", "", "CQ"),
            Cue(0, "Q3NXR", "", "HB"),
            Cue(0, "Q9ZTW", "", "HB"),
            Cue(1, "Q5QRV", mycall, "SNR?"),
            Cue(1, "Q0VBQ", "Q7GTK", "TNX CQ. UR -08 IN MINNESOTA. NAME SAM"),
            Cue(2, "Q4LMB", mycall, "GM ALEX. GOOD SIGS INTO GEORGIA ON 40M. QMX AT 5W HERE"),
            Cue(3, "Q7ABP", mycall, "MSG SEE YOU ON THE SUNDAY NET. 73"),
            Cue(4, "Q7GTK", "Q0VBQ", "TNX SAM, UR -12 HERE. 73"),
            Cue(5, "Q5QRV", mycall, "TNX FER THE REPORT. 73"),
            Cue(6, "Q3NXR", "", "HB"),
            Cue(7, "Q9ZTW", "", "HB"),
            Cue(8, "Q0VBQ", "@ARES", "NET TONIGHT 0100Z ON 3578 KHZ"),
            Cue(9, "Q4LMB", "", "HB"),
        )
    }

    private fun add(call: String, grid: String, offset: Int, amplitude: Float) {
        val cfg = StationConfig(callsign = call, grid = grid, autoReply = false)
        val s = JS8Station(cfg, Inbox(null), clock = { AppClock.now() })
        s.offset = offset
        s.txDelay = 0.0
        stations[call] = Station(s, amplitude)
    }

    fun start() {
        val p = JS8Submode.normal.period.toDouble()
        firstPeriod = floor(AppClock.nowSeconds() / p).toLong() + 1
        timer = scope.launch {
            while (isActive) {
                delay(100)
                pump()
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private fun pump() {
        val rate = RxRingBuffer.rate.toDouble()
        val now = AppClock.nowSeconds()
        val p = JS8Submode.normal.period.toDouble()
        val period = floor(now / p).toLong()
        if (period != lastPeriod) {
            lastPeriod = period
            beginPeriod(period, period * p)
        }
        val end = (now * rate).toLong()
        if (nextSample < 0) nextSample = end - (rate / 10).toLong()
        if (end <= nextSample) return
        val count = (end - nextSample).toInt()
        val chunk = FloatArray(count) { 300 * gaussian() }
        for (s in signals) {
            val lo = max(s.start, nextSample)
            val hi = min(s.start + s.wave.size, end)
            if (lo >= hi) continue
            for (idx in lo until hi) chunk[(idx - nextSample).toInt()] += s.amplitude * s.wave[(idx - s.start).toInt()]
        }
        signals.removeAll { it.start + it.wave.size < end }
        for (i in 0 until count) chunk[i] *= gain
        val time = nextSample / rate
        ring.write(chunk, time)
        spectrum.process(chunk)
        var power = 0f
        for (v in chunk) power += (v / 32767f) * (v / 32767f)
        power /= count
        onLevel((10 * log10(max(power, 1e-12f).toDouble())).toFloat())
        nextSample = end
    }

    private fun beginPeriod(period: Long, start: Double) {
        for (s in transmitting) s.transmissionFinished()
        transmitting.clear()
        // Loop the script so the band stays busy while screenshots are taken.
        val index = ((period - firstPeriod) % 12).toInt()
        for (cue in cues) {
            if (cue.period != index || period < firstPeriod) continue
            val s = stations[cue.call]?.engine ?: continue
            when (cue.text) {
                "CQ" -> s.sendCQ()
                "HB" -> s.sendHeartbeat()
                else -> {
                    s.selectedCallsign = cue.to
                    s.send(cue.text)
                }
            }
        }
        val rate = RxRingBuffer.rate.toDouble()
        val at = Instant.ofEpochMilli(((start + 0.05) * 1000).toLong())
        for (st in stations.values) {
            st.engine.tick()
            val req = st.engine.pollTransmit(at) ?: continue
            val wave = JS8Modulator.synthesize(req.tones.toIntArray(), req.offset.toDouble(), req.submode, rate)
            val begin = req.periodStart.epochSecond + req.periodStart.nano / 1e9 + req.submode.startDelayMS / 1000.0
            signals.add(Signal((begin * rate).toLong(), wave, st.amplitude))
            transmitting.add(st.engine)
        }
    }
}
