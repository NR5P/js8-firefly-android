package com.js8firefly.android.audio

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.js8firefly.android.AppSettings
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Scrolling waterfall image built from spectrum rows. */
class WaterfallRenderer {
    companion object {
        const val minHz = 100.0
        const val maxHz = 3100.0
        const val height = 220

        /** Classic JS8/WSJT style: deep navy → blue → cyan → yellow → red → white. ARGB. */
        fun makePalette(): IntArray {
            val stops = arrayOf(
                floatArrayOf(0.00f, 0.02f, 0.03f, 0.08f),
                floatArrayOf(0.18f, 0.04f, 0.10f, 0.35f),
                floatArrayOf(0.38f, 0.05f, 0.45f, 0.85f),
                floatArrayOf(0.55f, 0.15f, 0.85f, 0.95f),
                floatArrayOf(0.70f, 0.95f, 0.95f, 0.30f),
                floatArrayOf(0.85f, 1.00f, 0.45f, 0.10f),
                floatArrayOf(1.00f, 1.00f, 0.95f, 0.95f),
            )
            val out = IntArray(256)
            for (i in 0 until 256) {
                val x = i / 255f
                var k = 0
                while (k < stops.size - 2 && x > stops[k + 1][0]) k += 1
                val c0 = stops[k]
                val c1 = stops[k + 1]
                val t = max(0f, min(1f, (x - c0[0]) / (c1[0] - c0[0])))
                val r = ((c0[1] + (c1[1] - c0[1]) * t) * 255).toInt()
                val g = ((c0[2] + (c1[2] - c0[2]) * t) * 255).toInt()
                val b = ((c0[3] + (c1[3] - c0[3]) * t) * 255).toInt()
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return out
        }

        /** Blend a pixel halfway toward light grey so the boundary shows without hiding signals. */
        private fun boundaryTint(px: Int): Int {
            val r = (((px shr 16) and 0xFF) + 0xD0) / 2
            val g = (((px shr 8) and 0xFF) + 0xD0) / 2
            val b = ((px and 0xFF) + 0xD0) / 2
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** A period boundary in the latest image, `rowsFromTop` rows below the newest row. */
    data class PeriodMark(val rowsFromTop: Int, val start: Instant)

    private data class Boundary(val row: Int, val start: Double)

    val width: Int = ((maxHz - minHz) / SpectrumAnalyzer.binHz).toInt()
    val height: Int = WaterfallRenderer.height
    private val startBin: Int = (minHz / SpectrumAnalyzer.binHz).toInt()
    private val pixels = IntArray(width * height) { 0xFF060810.toInt() }
    private var row = 0
    private var floorDB = -90f
    private var binFloor = FloatArray(0)
    private val lock = Any()
    private val palette = makePalette()

    /** dB from the noise floor to full white. */
    @Volatile var rangeDB: Float = AppSettings.defaultWaterfallRange
    @Volatile var brightness: Float = 0f
    /** T/R period length; the first row of each period is drawn as a line. */
    @Volatile var periodSeconds: Double = 15.0
    private var latest = FloatArray(0)
    private var rowCount = 0
    private var lastPeriod: Long = -1
    private val boundaries = ArrayList<Boundary>()
    private var snapshotMarks: List<PeriodMark> = emptyList()

    // A few bitmaps in rotation so the one Compose is drawing is never rewritten.
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var bitmapIndex = 0
    private val ordered = IntArray(width * height)

    /** Period boundaries matching the most recent `makeImage()`. */
    val periodMarks: List<PeriodMark>
        get() = synchronized(lock) { snapshotMarks }

    /** Most recent normalised spectrum row (0…1), safe to call from any thread. */
    val latestSpectrum: List<Float>
        get() = synchronized(lock) { latest.toList() }

    fun add(db: FloatArray) {
        if (db.size < startBin + width) return
        val slice = db.copyOfRange(startBin, startBin + width)
        // Flatten the receiver passband: track a per-bin noise floor that
        // falls quickly and rises slowly (signals don't lift it), like
        // JS8Call's flattened waterfall.
        if (binFloor.size != width) binFloor = slice.copyOf()
        for (i in 0 until width) {
            val v = slice[i]
            binFloor[i] = if (v < binFloor[i]) binFloor[i] * 0.7f + v * 0.3f else binFloor[i] + min(0.08f, v - binFloor[i])
        }
        // Smooth the floor across neighbouring bins.
        val smooth = FloatArray(width)
        val k = 12
        for (i in 0 until width) {
            var acc = 0f
            var n = 0f
            for (j in max(0, i - k)..min(width - 1, i + k)) { acc += binFloor[j]; n += 1f }
            smooth[i] = acc / n
        }
        val globalFloor = smooth.sortedArray()[width / 2]
        for (i in 0 until width) slice[i] = slice[i] - smooth[i] + globalFloor
        val sorted = slice.sortedArray()
        val p25 = sorted[sorted.size / 4]
        floorDB = floorDB * 0.9f + p25 * 0.1f
        val p = max(1.0, periodSeconds)
        val period = floor(AppClock.nowSeconds() / p).toLong()
        val isBoundary = lastPeriod >= 0 && period != lastPeriod
        lastPeriod = period
        synchronized(lock) {
            row = (row + height - 1) % height
            rowCount += 1
            val base = row * width
            val lo = floorDB - 4 + brightness
            val span = max(8f, rangeDB)
            val norm = FloatArray(width)
            for (i in 0 until width) {
                val v = (slice[i] - lo) / span
                norm[i] = v
                val idx = (max(0f, min(1f, v)) * 255).toInt()
                pixels[base + i] = if (isBoundary) boundaryTint(palette[idx]) else palette[idx]
            }
            if (isBoundary) boundaries.add(Boundary(rowCount, period * p))
            boundaries.removeAll { rowCount - it.row >= height }
            latest = norm
        }
    }

    /** Newest row first. Call on the main thread. */
    fun makeImage(): ImageBitmap {
        synchronized(lock) {
            snapshotMarks = boundaries.map {
                PeriodMark(rowsFromTop = rowCount - it.row,
                    start = Instant.ofEpochMilli((it.start * 1000).toLong()))
            }
            val first = row * width
            val tail = pixels.size - first
            System.arraycopy(pixels, first, ordered, 0, tail)
            System.arraycopy(pixels, 0, ordered, tail, first)
        }
        bitmapIndex = (bitmapIndex + 1) % bitmaps.size
        val bmp = bitmaps[bitmapIndex] ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { bitmaps[bitmapIndex] = it }
        bmp.setPixels(ordered, 0, width, 0, 0, width, height)
        return bmp.asImageBitmap()
    }
}
