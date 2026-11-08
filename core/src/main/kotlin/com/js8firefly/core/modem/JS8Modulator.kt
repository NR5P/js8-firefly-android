// Port of JS8Core/Sources/JS8Core/Modem/JS8Modulator.swift ([Int] tones -> IntArray, [Float] -> FloatArray).
package com.js8firefly.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.truncate

/**
 * Continuous-phase 8-FSK waveform generator, equivalent to JS8Call's
 * Modulator (no pulse shaping, with the same short fade at the end).
 */
object JS8Modulator {
    /**
     * Generate the audio for one frame (symbols only, no start delay).
     * @param tones 79 tone indices from `JS8Codec.encode`.
     * @param frequency audio frequency of tone 0, in Hz.
     * @param sampleRate output sample rate; must be a multiple of 12000 or 12000.
     * @param amplitude peak amplitude (0...1).
     */
    fun synthesize(
        tones: IntArray, frequency: Double, submode: JS8Submode,
        sampleRate: Double = 48000.0, amplitude: Float = 1f,
    ): FloatArray {
        val ratio = sampleRate / JS8Submode.sampleRate.toDouble()
        val nsps = submode.samplesPerSymbol.toDouble() * ratio
        val total = roundedAway(tones.size.toDouble() * nsps).toInt()
        val spacing = submode.toneSpacing
        val tau = 2.0 * Math.PI
        val i0 = ((JS8Submode.numSymbols.toDouble() - 0.017) * nsps).toInt()

        val out = FloatArray(total)
        var phi = 0.0
        var amp = amplitude.toDouble()
        var lastSym = -1
        var dphi = 0.0
        for (i in 0 until total) {
            val sym = min((i.toDouble() / nsps).toInt(), tones.size - 1)
            if (sym != lastSym) {
                dphi = tau * (frequency + tones[sym].toDouble() * spacing) / sampleRate
                lastSym = sym
            }
            phi += dphi
            if (phi > tau) phi -= tau
            if (i > i0) amp *= 0.98
            out[i] = (amp * sin(phi)).toFloat()
        }
        return out
    }

    /** Swift `Double.rounded()` (to nearest, ties away from zero). */
    private fun roundedAway(x: Double): Double {
        val t = truncate(x)
        return if (abs(x - t) >= 0.5) t + (if (x < 0) -1.0 else 1.0) else t
    }
}
