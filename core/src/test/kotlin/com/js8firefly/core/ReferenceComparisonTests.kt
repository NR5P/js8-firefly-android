package com.js8firefly.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Cross-checks the Kotlin implementation against the original JS8Call code.
 * Like XCTAssertEqual, mismatches are collected and every iteration runs;
 * each test fails at the end if any comparison differed.
 */
class ReferenceComparisonTests {
    private lateinit var ref: RefHarness

    @Before
    fun setUp() {
        Assume.assumeTrue("reference harness not built", RefHarness.isAvailable)
        ref = RefHarness()
    }

    @After
    fun tearDown() {
        if (::ref.isInitialized) ref.close()
    }

    private fun randomFrame(g: SeededGenerator): String {
        val sb = StringBuilder()
        repeat(12) { sb.append(JS8Codec.alphabet[swiftRandomInt(g, 0, 64)]) }
        return sb.toString()
    }

    private val failures = mutableListOf<String>()

    private fun check(expected: String, got: String, message: String) {
        if (expected != got) failures.add("$message\n expected: $expected\n got:      $got")
    }

    private fun assertNoFailures() {
        if (failures.isNotEmpty()) {
            fail("${failures.size} mismatches:\n" + failures.take(15).joinToString("\n"))
        }
    }

    @Test
    fun testEncodeMatchesReference() {
        val g = SeededGenerator(1)
        for (submode in JS8Submode.entries) {
            repeat(200) {
                val frame = randomFrame(g)
                val type = swiftRandomInt(g, 0, 8)
                val tones = JS8Codec.encode(type = type, frame = frame, costas = submode.costas)!!
                val expect = ref.call(listOf("ENC", "$type", "${submode.rawValue}", frame))
                check(expect, tones.joinToString(""), "$frame type $type $submode")
            }
        }
        assertNoFailures()
    }

    @Test
    fun testDecodedTextMatchesReference() {
        val g = SeededGenerator(2)
        var mismatches = 0
        for (i in 0 until 4000) {
            val frame = randomFrame(g)
            val type = i % 8
            val submode = if (i % 3 == 0) JS8Submode.normal else JS8Submode.fast
            val dt = DecodedText(frame = frame, bits = type, submode = submode)
            val expect = ref.call(listOf("DT", frame, "$type", "${submode.rawValue}"))
            val got = listOf(
                dt.frameType.rawValue.toString(), esc(dt.message), esc(dt.directed.joinToString("|")),
                esc(dt.compoundCall), esc(dt.extra), "${if (dt.isHeartbeat) 1 else 0}${if (dt.isAlt) 1 else 0}",
            ).joinToString("\t")
            if (got != expect) {
                mismatches += 1
                if (mismatches < 15) failures.add("DT $frame $type:\n got: $got\n ref: $expect")
            }
        }
        if (failures.isNotEmpty()) System.err.println(failures.joinToString("\n"))
        assertEquals(0, mismatches)
    }

    companion object {
        val messages: List<String> = listOf(
            "HELLO WORLD HOW ARE YOU TODAY",
            "CQ CQ CQ EM73", "CQ DX FN42", "CQ", "HB EM73", "HEARTBEAT EM73",
            "@ALLCALL CQ CQ CQ EM73",
            "J1Y SNR?", "J1Y SNR -12", "J1Y SNR +05", "J1Y HEARTBEAT SNR -20", "J1Y GRID?", "J1Y INFO?", "J1Y STATUS?",
            "J1Y HEARING?", "J1Y ACK", "J1Y NACK", "J1Y 73", "J1Y AGN?", "J1Y QSL?", "J1Y QSL", "J1Y HW CPY?", "J1Y FB",
            "J1Y RR", "J1Y SK", "J1Y YES", "J1Y NO", "J1Y DIT DIT", "J1Y?",
            "J1Y MSG HELLO THERE THIS IS A STORED MESSAGE",
            "J1Y MSG TO:KN4CRD HELLO FROM THE FIELD",
            "J1Y QUERY MSGS", "J1Y QUERY MSGS?", "J1Y QUERY CALL W1AW", "J1Y QUERY MSG 12",
            "J1Y>KN4CRD HELLO VIA RELAY", "J1Y GRID EM73", "J1Y INFO QRP 5W WIRE ANTENNA",
            "J1Y STATUS IN THE FIELD", "J1Y CMD TEST", "J1Y HEARING W1AW K1JT",
            "@APRSIS GRID EM73", "@APRSIS CMD :EMAIL-2  :KN4CRD@EXAMPLE.COM TEST{01}",
            "@JS8NET HELLO", "@HB HEARTBEAT EM73",
            "VE3/W1AW: HELLO", "W1AW/P SNR?", "KN4CRD/P GRID?", "W1AW/MM HELLO",
            "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890",
            "WEATHER IS 72F SUNNY, WIND 5MPH. 73!",
            "TESTING lowercase text and punctuation ?!.-+/",
            "ÀÉÎÕÜ ACCENTED",
            "  LEADING AND TRAILING SPACES  ",
            "A", "AB CD", "E E E E E E E E E E E E E E E E E E",
            "CALLING K1JT W1AW N0CALL KN4CRD/P",
        )
    }

    private fun compareFrames(
        mycall: String, grid: String, selected: String, text: String, submode: JS8Submode,
        forceIdentify: Boolean, forceData: Boolean,
    ) {
        val frames = Varicode.buildMessageFrames(
            mycall = mycall, mygrid = grid, selectedCall = selected, text = text,
            forceIdentify = forceIdentify, forceData = forceData, submode = submode,
        )
        val got = frames.joinToString("\t") { f ->
            val dt = DecodedText(frame = f.frame, bits = f.type, submode = submode)
            esc("${f.frame}:${f.type}:${dt.message}")
        }
        val expect = ref.call(
            listOf(
                "FRAMES", "${submode.rawValue}", mycall, grid, selected,
                if (forceIdentify) "1" else "0", if (forceData) "1" else "0", text,
            ),
        )
        check(expect, got, "text=\"$text\" mycall=$mycall selected=$selected submode=$submode")
    }

    @Test
    fun testMessageFramesMatchReference() {
        val calls = listOf("KN4CRD" to "EM73", "W1AW/P" to "FN31", "VE3/W1AW" to "FN03", "K1JT" to "FN20")
        for (submode in listOf(JS8Submode.normal, JS8Submode.fast, JS8Submode.turbo, JS8Submode.slow)) {
            for ((mycall, grid) in calls) {
                for (text in messages) {
                    for (selected in listOf("", "J1Y", "W1AW/MM", "@ALLCALL")) {
                        compareFrames(mycall, grid, selected, text, submode, forceIdentify = true, forceData = false)
                    }
                    compareFrames(mycall, grid, "", text, submode, forceIdentify = false, forceData = true)
                }
            }
        }
        assertNoFailures()
    }

    @Test
    fun testRandomTextFramesMatchReference() {
        val g = SeededGenerator(7)
        val words = listOf(
            "THE", "AND", "RADIO", "ANTENNA", "73", "QTH", "NAME", "IS", "JOHN", "RIG", "QRP", "5W",
            "WX", "SUNNY", "COPY", "TNX", "FER", "QSO", "CQ", "DE", "K", "BK", "HELLO", "WORLD",
            "ZEBRA", "XYLOPHONE", "QUIZZICAL", "?", "!", ".", ",", "-", "+", "/", "@", "#", "12345",
            "EM73", "FN42", "J1Y", "KN4CRD", "SNR", "MSG", "ACK",
        )
        for (i in 0 until 600) {
            val n = swiftRandomInt(g, 1, 15) // 1...14
            val text = (0 until n).joinToString(" ") { words[swiftRandomInt(g, 0, words.size)] }
            val submode = JS8Submode.entries[i % 5]
            val selected = if (i % 4 == 0) "J1Y" else ""
            compareFrames("KN4CRD", "EM73", selected, text, submode, forceIdentify = i % 2 == 0, forceData = i % 7 == 0)
        }
        assertNoFailures()
    }
}

/**
 * Swift's `Int.random(in: lower..<upper, using:)` (Lemire's nearly divisionless
 * method over UInt64), so the tests draw the same values as the Swift suite.
 */
internal fun swiftRandomInt(g: SeededGenerator, lower: Int, upper: Int): Int {
    val bound = (upper - lower).toLong()
    var random = g.next()
    var low = random * bound
    if (java.lang.Long.compareUnsigned(low, bound) < 0) {
        val t = java.lang.Long.remainderUnsigned(-bound, bound)
        while (java.lang.Long.compareUnsigned(low, t) < 0) {
            random = g.next()
            low = random * bound
        }
    }
    val high = Math.multiplyHigh(random, bound) + ((random shr 63) and bound) + ((bound shr 63) and random)
    return lower + high.toInt()
}

fun esc(s: String): String {
    val out = StringBuilder()
    for (c in s) {
        when (c) {
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\t' -> out.append("\\t")
            '\r' -> out.append("\\r")
            else -> out.append(c)
        }
    }
    return out.toString()
}
