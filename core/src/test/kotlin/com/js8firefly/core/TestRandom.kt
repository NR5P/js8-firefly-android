package com.js8firefly.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Repeatable noise for tests that build audio (same sequence as the Swift tests). */
class SeededGenerator(seed: Long) {
    private var state: Long = seed + -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

    fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }
}

fun gaussian(g: SeededGenerator): Float {
    val u1 = max((g.next() ushr 11).toDouble() / (1L shl 53).toDouble(), 1e-12)
    val u2 = (g.next() ushr 11).toDouble() / (1L shl 53).toDouble()
    return (sqrt(-2 * ln(u1)) * cos(2 * PI * u2)).toFloat()
}
