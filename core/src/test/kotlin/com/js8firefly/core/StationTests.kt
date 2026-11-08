package com.js8firefly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

/** End-to-end station tests: frames are modulated, mixed with noise, decoded
 *  and fed to the other stations, exercising the full on-air path. */
class StationTests {
    class SimClock(var now: Instant)

    private lateinit var clock: SimClock
    private val noise = SeededGenerator(seed = 99)
    private val decoders = mutableMapOf<JS8Submode, JS8ModeDecoder>()

    @Before
    fun setUp() {
        // Start at a period boundary.
        clock = SimClock(instantFromTimeIntervalSince1970(1_800_000_000.0 - 1_800_000_000.0 % 30))
    }

    private fun makeStation(call: String, grid: String): JS8Station {
        val cfg = StationConfig()
        cfg.callsign = call
        cfg.grid = grid
        cfg.info = "QRP 5W IPHONE"
        cfg.status = "PORTABLE"
        val s = JS8Station(config = cfg, inbox = Inbox(url = null), clock = { clock.now })
        s.txDelay = 0.0
        return s
    }

    /** Run the air for up to `periods` periods. */
    private fun run(stations: List<JS8Station>, periods: Int, submode: JS8Submode = JS8Submode.normal) {
        val p = submode.period.toDouble()
        val into = clock.now.timeIntervalSince1970 % p
        if (into != 0.0) clock.now = clock.now.addingTimeInterval(p - into)
        repeat(periods) {
            for (s in stations) s.tick()
            val audio = FloatArray(submode.samplesPerPeriod)
            val transmitters = mutableListOf<JS8Station>()
            for (s in stations) {
                val req = s.pollTransmit(now = clock.now)
                if (req != null) {
                    val wave = JS8Modulator.synthesize(
                        tones = req.tones.toIntArray(), frequency = req.offset.toDouble(), submode = req.submode, sampleRate = 12000.0,
                    )
                    val start = submode.startDelayMS * 12
                    for ((i, v) in wave.withIndex()) if (start + i < audio.size) audio[start + i] += 400 * v
                    transmitters.add(s)
                }
            }
            for (i in audio.indices) audio[i] += 300 * gaussian(noise)
            val decoder = decoders.getOrPut(submode) { JS8ModeDecoder(submode = submode) }
            val decodes = mutableListOf<JS8RawDecode>()
            decoder.decode(samples = audio, nfqso = 1500) { decodes.add(it) }
            clock.now = clock.now.addingTimeInterval(submode.txDuration + 0.2)
            for (s in transmitters) s.transmissionFinished()
            for (s in stations) {
                if (transmitters.any { it === s }) continue
                for (d in decodes) s.handleDecode(d)
            }
            clock.now = clock.now.addingTimeInterval(p - submode.txDuration - 0.2)
            for (s in stations) s.tick()
        }
    }

    private fun conversationText(s: JS8Station): String = s.conversation.joinToString("\n") { it.text }

    @Test
    fun testSNRQueryAutoReply() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        a.selectedCallsign = "J1Y"
        assertTrue(a.send("SNR?"))
        run(listOf(a, b), periods = 3)
        assertTrue(conversationText(b), conversationText(b).contains("KN4CRD: J1Y SNR?"))
        assertTrue(conversationText(a), conversationText(a).contains("J1Y: KN4CRD SNR"))
        assertNotNull(a.callActivity["J1Y"])
    }

    @Test
    fun testGridInfoStatusHearing() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        a.selectedCallsign = "J1Y"
        a.send("GRID?")
        run(listOf(a, b), periods = 3)
        assertTrue(conversationText(a), conversationText(a).contains("J1Y: KN4CRD GRID PM95"))
        a.send("INFO?")
        run(listOf(a, b), periods = 5)
        assertTrue(conversationText(a), conversationText(a).contains("KN4CRD INFO QRP 5W IPHONE"))
        a.send("STATUS?")
        run(listOf(a, b), periods = 4)
        assertTrue(conversationText(a), conversationText(a).contains("KN4CRD STATUS PORTABLE"))
    }

    @Test
    fun testDirectedMessageGoesToInboxAndAcks() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        a.selectedCallsign = "J1Y"
        a.send("MSG HELLO FROM MY IPHONE ON THE TRAIL")
        run(listOf(a, b), periods = 8)
        assertEquals(1, b.inbox.messages.size)
        assertEquals("HELLO FROM MY IPHONE ON THE TRAIL", b.inbox.messages.firstOrNull()?.text)
        assertEquals("KN4CRD", b.inbox.messages.firstOrNull()?.from)
        assertTrue(conversationText(a), conversationText(a).contains("J1Y: KN4CRD ACK"))
    }

    @Test
    fun testStoreAndForward() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        val c = makeStation("W1AW", "FN31")
        a.selectedCallsign = "J1Y"
        a.send("MSG TO:W1AW MEET ON 40M AT 2000Z")
        run(listOf(a, b, c), periods = 8)
        assertEquals("STORE", b.inbox.messages.firstOrNull()?.type)
        assertEquals("W1AW", b.inbox.messages.firstOrNull()?.to)

        c.selectedCallsign = "J1Y"
        c.send("QUERY MSGS")
        run(listOf(a, b, c), periods = 6)
        assertTrue(conversationText(c), conversationText(c).contains("J1Y: W1AW YES MSG ID 1"))

        c.send("QUERY MSG 1")
        run(listOf(a, b, c), periods = 10)
        assertTrue(conversationText(c), conversationText(c).contains("MEET ON 40M AT 2000Z FROM KN4CRD"))
        assertEquals("DELIVERED", b.inbox.messages.firstOrNull()?.type)
    }

    @Test
    fun testHeartbeatAndAck() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        b.sendHeartbeat()
        run(listOf(a, b), periods = 4)
        assertNotNull(a.callActivity["J1Y"])
        assertEquals("PM95", a.callActivity["J1Y"]?.grid)
        // A acknowledges B's heartbeat.
        assertNotNull("heartbeat ack not heard", b.callActivity["KN4CRD"])
        val rows = b.bandActivityRows(now = clock.now)
        assertTrue("$rows", rows.any { it.text.contains("J1Y HEARTBEAT SNR") })
    }

    @Test
    fun testHeartbeatKeepsFinerGrid() {
        val a = makeStation("KN4CRD", "EM73")
        val cd = CallDetail()
        cd.call = "J1Y"
        cd.grid = "PM95SQ"
        a.logCallActivity(cd)
        cd.grid = "PM95"
        a.logCallActivity(cd)
        assertEquals("PM95SQ", a.callActivity["J1Y"]?.grid)
        cd.grid = ""
        a.logCallActivity(cd)
        assertEquals("PM95SQ", a.callActivity["J1Y"]?.grid)
        // Moving to another square replaces it.
        cd.grid = "PM96"
        a.logCallActivity(cd)
        assertEquals("PM96", a.callActivity["J1Y"]?.grid)
    }

    @Test
    fun testCQAndCompoundCallsign() {
        val a = makeStation("VE3/KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        a.sendCQ()
        run(listOf(a, b), periods = 3)
        assertNotNull("${b.callActivity}", b.callActivity["VE3/KN4CRD"]?.cqTimestamp)

        a.selectedCallsign = "J1Y"
        a.send("SNR?")
        run(listOf(a, b), periods = 5)
        assertTrue(
            conversationText(a) + "\n--\n" + conversationText(b),
            conversationText(a).contains("VE3/KN4CRD SNR"),
        )
    }

    @Test
    fun testFreeTextChat() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        b.offset = 1500
        a.offset = 1500
        a.selectedCallsign = "J1Y"
        a.send("GOOD MORNING, NAME IS JORDAN. WX IS SUNNY 72F")
        run(listOf(a, b), periods = 8)
        assertTrue(conversationText(b), conversationText(b).contains("GOOD MORNING, NAME IS JORDAN. WX IS SUNNY 72F"))
    }

    @Test
    fun testBandConversationHearsOtherOffsets() {
        val a = makeStation("KN4CRD", "EM73")
        val b = makeStation("J1Y", "PM95")
        val c = makeStation("W1AW", "FN31")
        a.offset = 1500
        b.offset = 1500
        c.offset = 900
        a.selectedCallsign = "J1Y"
        a.send("GOOD MORNING, NAME IS JORDAN. WX IS SUNNY 72F")
        run(listOf(a, b, c), periods = 8)
        val band = c.bandConversation.joinToString("\n") { it.text }
        assertFalse(conversationText(c), conversationText(c).contains("SUNNY 72F"))
        val line = c.bandConversation.firstOrNull { it.text.contains("GOOD MORNING") }
        assertNotNull(band, line)
        assertTrue(band, line?.text?.contains("SUNNY 72F") == true)
        assertTrue(band, line?.isComplete == true)
        assertEquals(band, true, line?.let { abs(it.offset - 1500) <= 10 })
        assertTrue(
            a.bandConversation.joinToString("\n") { it.text },
            a.bandConversation.any { it.isTx && it.text.contains("SUNNY 72F") },
        )
    }

    /** Frames packed for one speed still decode after switching speeds: the
     *  data-frame format is carried in the frame's type bits, not the speed. */
    @Test
    fun testSpeedChangeMidMessage() {
        val text = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789"
        for ((from, to) in listOf(
            Pair(JS8Submode.normal, JS8Submode.fast), Pair(JS8Submode.turbo, JS8Submode.normal), Pair(JS8Submode.normal, JS8Submode.slow),
        )) {
            for (sentBefore in listOf(0, 1)) {
                val a = makeStation("KN4CRD", "EM73")
                val b = makeStation("J1Y", "PM95")
                a.submode = from
                b.submode = from
                assertTrue(a.send(text))
                run(listOf(a, b), periods = sentBefore, submode = from)
                a.submode = to
                b.submode = to
                run(listOf(a, b), periods = 12, submode = to)
                // A longer new period can trip the missed-frame marker; the text must still arrive whole.
                val heard = conversationText(b).replace(b.config.mfi, "")
                assertTrue("$from->$to after $sentBefore: " + conversationText(b), heard.contains(text))
            }
        }
    }

    @Test
    fun testFastModes() {
        for (mode in listOf(JS8Submode.fast, JS8Submode.turbo, JS8Submode.slow, JS8Submode.ultra)) {
            val a = makeStation("KN4CRD", "EM73")
            val b = makeStation("J1Y", "PM95")
            a.submode = mode
            b.submode = mode
            a.selectedCallsign = "J1Y"
            a.send("SNR?")
            run(listOf(a, b), periods = 4, submode = mode)
            assertTrue("$mode: " + conversationText(a), conversationText(a).contains("J1Y: KN4CRD SNR"))
        }
    }
}
