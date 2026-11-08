package com.js8firefly.android.audio

import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/** UTC wall clock with a user-adjustable offset (JS8 needs ±1 s timing). */
object AppClock {
    @Volatile
    var offset: Double = 0.0

    fun now(): Instant {
        val ms = System.currentTimeMillis()
        return Instant.ofEpochMilli(ms).plusNanos((offset * 1e9).toLong())
    }

    fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0 + offset
}

/**
 * Sixty seconds of 12 kHz receive audio, indexed by absolute sample time
 * (seconds since the epoch × 12000) so that decode windows line up with the
 * UTC transmit periods, exactly like JS8Call's dec_data ring buffer.
 */
class RxRingBuffer {
    companion object {
        const val rate = 12000
        const val capacity = 60 * rate
        private const val blockSize = 1200
    }

    private val samples = FloatArray(capacity)
    private val blockStamp = LongArray(capacity / blockSize) { -1L }
    private var nextIndex: Long = -1
    private val lock = Any()

    /** Write `count` samples whose first sample was captured at `time` (UTC seconds). */
    fun write(data: FloatArray, time: Double, count: Int = data.size) {
        synchronized(lock) {
            val expected = (time * rate).roundToLong()
            if (nextIndex < 0 || abs(expected - nextIndex) > 600) {
                nextIndex = expected
            }
            var idx = nextIndex
            for (i in 0 until count) {
                val pos = (idx % capacity).toInt()
                samples[pos] = data[i]
                blockStamp[pos / blockSize] = idx / blockSize
                idx += 1
            }
            nextIndex = idx
        }
    }

    val latestIndex: Long
        get() = synchronized(lock) { nextIndex }

    /** Copy `count` samples starting at absolute index `start`; missing audio is zero. */
    fun read(start: Long, count: Int): FloatArray {
        synchronized(lock) {
            val out = FloatArray(count)
            for (i in 0 until count) {
                val idx = start + i
                if (idx < 0 || idx >= nextIndex) continue
                val pos = (idx % capacity).toInt()
                if (blockStamp[pos / blockSize] == idx / blockSize) {
                    out[i] = samples[pos]
                }
            }
            return out
        }
    }

    fun reset() {
        synchronized(lock) {
            nextIndex = -1
            blockStamp.fill(-1L)
        }
    }
}
