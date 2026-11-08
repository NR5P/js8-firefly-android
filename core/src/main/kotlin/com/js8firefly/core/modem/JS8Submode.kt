// Port of JS8Core/Sources/JS8Core/Modem/JS8Submode.swift.
// Swift [[Int]] costas tables become Array<IntArray>.
package com.js8firefly.core

import kotlin.math.floor

/** The JS8 speeds. Raw values match JS8Call's `Varicode::SubmodeType`. */
enum class JS8Submode(val rawValue: Int) {
    normal(0),
    fast(1),
    turbo(2),
    slow(4),
    ultra(8);

    companion object {
        const val numSymbols = 79
        const val sampleRate = 12000

        /** Swift CaseIterable.allCases (declaration order). */
        val allCases: List<JS8Submode> = entries.toList()

        /** Swift `JS8Submode(rawValue:)`. */
        fun fromRaw(rawValue: Int): JS8Submode? = entries.firstOrNull { it.rawValue == rawValue }
    }

    val id: Int get() = rawValue

    /** Name as displayed by JS8Call. */
    val displayName: String
        get() = when (this) {
            normal -> "Normal"
            fast -> "Fast"
            turbo -> "Turbo"
            slow -> "Slow"
            ultra -> "Ultra"
        }

    /** Single-letter identifier used in JS8Call logs (A, B, C, E, I). */
    val letter: Char
        get() = when (this) {
            normal -> 'A'
            fast -> 'B'
            turbo -> 'C'
            slow -> 'E'
            ultra -> 'I'
        }

    val samplesPerSymbol: Int
        get() = when (this) {
            normal -> 1920
            fast -> 1200
            turbo -> 600
            slow -> 3840
            ultra -> 384
        }

    /** Transmission period, start to start, in seconds. */
    val period: Int
        get() = when (this) {
            normal -> 15
            fast -> 10
            turbo -> 6
            slow -> 30
            ultra -> 4
        }

    val startDelayMS: Int
        get() = when (this) {
            normal -> 500
            fast -> 200
            turbo -> 100
            slow -> 500
            ultra -> 100
        }

    val usesOriginalCostas: Boolean get() = this == normal

    val costas: Array<IntArray> get() = if (usesOriginalCostas) Costas.original else Costas.modified

    val rxSNRThreshold: Int
        get() = when (this) {
            normal -> -24
            fast -> -22
            turbo -> -20
            slow -> -28
            ultra -> -18
        }

    /** Frequency window (Hz) used to associate decodes with an existing offset. */
    val rxThreshold: Int
        get() = when (this) {
            normal -> 10
            fast -> 16
            turbo -> 32
            slow -> 10
            ultra -> 50
        }

    val samplesForSymbols: Int get() = numSymbols * samplesPerSymbol
    val bandwidth: Int get() = 8 * sampleRate / samplesPerSymbol
    val samplesPerPeriod: Int get() = sampleRate * period
    val toneSpacing: Double get() = sampleRate.toDouble() / samplesPerSymbol.toDouble()
    val samplesNeeded: Int
        get() = floor(samplesForSymbols.toDouble() + (0.5 + startDelayMS.toDouble() / 1000.0) * sampleRate.toDouble()).toInt()
    val dataDuration: Double get() = samplesForSymbols.toDouble() / sampleRate.toDouble()
    val txDuration: Double get() = dataDuration + startDelayMS.toDouble() / 1000.0

    /** Heartbeats are only sent in Normal, Fast and Slow (as in JS8Call). */
    val canSendHeartbeat: Boolean get() = this == normal || this == fast || this == slow

    /** JS8Call uses JS8CallData (the fast data frame) for every mode but Normal. */
    val usesFastDataFrames: Boolean get() = this != normal
}

object Costas {
    val original: Array<IntArray> = arrayOf(
        intArrayOf(4, 2, 5, 6, 1, 3, 0),
        intArrayOf(4, 2, 5, 6, 1, 3, 0),
        intArrayOf(4, 2, 5, 6, 1, 3, 0),
    )
    val modified: Array<IntArray> = arrayOf(
        intArrayOf(0, 6, 2, 3, 5, 4, 1),
        intArrayOf(1, 5, 0, 2, 3, 6, 4),
        intArrayOf(2, 5, 0, 6, 4, 1, 3),
    )
}
