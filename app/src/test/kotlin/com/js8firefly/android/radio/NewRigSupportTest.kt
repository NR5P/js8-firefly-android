package com.js8firefly.android.radio

import com.js8firefly.android.rigSettingsFromJson
import com.js8firefly.android.toJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The radios added in Nov 2026, checked against Hamlib's rig definitions. */
class NewRigSupportTest {
    private val h = RigHarness()
    private val f get() = h.fake

    @After fun tearDown() = h.close()

    // MARK: Preset table

    @Test fun rawValuesAndTitlesAreUnique() {
        val raws = RigPreset.entries.map { it.rawValue }
        assertEquals(raws.size, raws.toSet().size)
        val titles = RigPreset.entries.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
        for (p in RigPreset.entries) assertEquals(p, RigPreset.fromRaw(p.rawValue))
        for (c in CATProtocol.entries) assertEquals(c, CATProtocol.fromRaw(c.rawValue))
    }

    /**
     * Default CI-V addresses and CAT PTT support from Hamlib (rigs/icom/ *.c:
     * re_civ_addr, ptt_type), typed in separately from the preset table.
     * Every Icom-protocol preset must appear here.
     */
    private val hamlibIcom = mapOf(
        "ic703" to (0x68 to true), "ic705" to (0xA4 to true),
        "ic706" to (0x48 to false), "ic706mk2" to (0x4E to false), "ic706mk2g" to (0x58 to false),
        "ic707" to (0x3E to false), "ic718" to (0x5E to false), "ic725" to (0x28 to false),
        "ic728" to (0x38 to false), "ic729" to (0x3A to false), "ic736" to (0x40 to false),
        "ic737" to (0x3C to false), "ic738" to (0x44 to false), "ic746" to (0x56 to true),
        "ic746pro" to (0x66 to true), "ic756" to (0x50 to false), "ic756pro" to (0x5C to true),
        "ic756pro2" to (0x64 to true), "ic756pro3" to (0x6E to true), "ic765" to (0x2C to false),
        "ic775" to (0x46 to false), "ic78" to (0x62 to false), "ic781" to (0x26 to false),
        "ic7000" to (0x70 to true), "ic7100" to (0x88 to true), "ic7200" to (0x76 to true),
        "ic7300" to (0x94 to true), "ic7300mk2" to (0xB6 to true), "ic7410" to (0x80 to true),
        "ic7600" to (0x7A to true), "ic7610" to (0x98 to true), "ic7700" to (0x74 to true),
        "ic7760" to (0xB2 to true), "ic7800" to (0x6A to true), "ic7851" to (0x8E to true),
        "ic905" to (0xAC to true), "ic910" to (0x60 to true), "ic9100" to (0x7C to true),
        "ic9700" to (0xA2 to true),
        // Hamlib rigs/icom/xiegu.c
        "xieguG90" to (0x88 to true), "xieguX6100" to (0xA4 to true), "xiegu" to (0x70 to true),
    )

    @Test fun icomAddressesMatchHamlib() {
        val icom = RigPreset.entries.filter { it.catProtocol == CATProtocol.icom }
        assertEquals(hamlibIcom.keys, icom.map { it.rawValue }.toSet())
        for (p in icom) {
            val (addr, ptt) = hamlibIcom.getValue(p.rawValue)
            assertEquals("${p.title} CI-V address", addr, p.icomAddress)
            assertEquals("${p.title} CAT PTT", ptt, p.hasCATPTT)
            assertTrue("${p.title} baud", p.baud == 9600 || p.baud == 19200)
        }
    }

    @Test fun everyIcomPresetTalksToItsOwnAddress() {
        val icom = RigPreset.entries.filter { it.catProtocol == CATProtocol.icom }
        // Reading first: a set would hold off readings for 1 s of real time.
        icom.forEachIndexed { i, p ->
            f.clear()
            h.connect(serial(p))
            val a = "%02X".format(p.icomAddress)
            assertEquals(p.title, listOf("FE FE $a E0 04 FD", "FE FE $a E0 03 FD"), f.hex())
            // A different dial frequency per radio, so a stale value can't pass.
            val hz = 14_000_000L + i * 1000
            f.reply(hex("FE FE E0 $a 03 ${RigController.bcd(hz, 5).toHex()} FD"))
            assertEquals(p.title, hz, h.rig.frequency)
        }
        icom.forEachIndexed { i, p ->
            h.connect(serial(p))
            val a = "%02X".format(p.icomAddress)
            val hz = 7_000_000L + i * 1000
            h.rig.setFrequency(hz)
            h.rig.setPTT(true)
            h.rig.setPTT(false)
            assertEquals(p.title, listOf(
                "FE FE $a E0 05 ${RigController.bcd(hz, 5).toHex()} FD",
                "FE FE $a E0 1C 00 01 FD",
                "FE FE $a E0 1C 00 00 FD",
            ), f.hex().takeLast(3))
        }
    }

    @Test fun xieguModelsAreSplit() {
        assertEquals(0x88, RigSettings().apply(RigPreset.xieguG90).icomAddress)
        assertEquals(0xA4, RigSettings().apply(RigPreset.xieguX6100).icomAddress)
        assertEquals(0x70, RigSettings().apply(RigPreset.xiegu).icomAddress)
    }

    @Test fun newYaesuNewCatRigs() {
        for ((p, digits) in listOf(RigPreset.ftdx1200 to 8, RigPreset.ftdx9000 to 8)) {
            assertEquals(CATProtocol.yaesu, p.catProtocol)
            assertEquals(digits, p.yaesuDigits)
        }
        h.connect(serial(RigPreset.ftdx1200))
        f.clear()
        h.rig.setFrequency(14_078_000)
        assertEquals(listOf("FA14078000;"), f.text())
    }

    @Test fun newKenwoodStyleRigs() {
        for (p in listOf(RigPreset.ts570, RigPreset.ts870, RigPreset.tx500, RigPreset.k2)) {
            assertEquals(CATProtocol.kenwood, p.catProtocol)
        }
        val k2 = RigSettings().apply(RigPreset.k2)
        assertEquals(4800, k2.baud)
        assertEquals(2, k2.stopBits)
        h.connect(serial(RigPreset.ts570))
        assertEquals(listOf("MD2;", "FR;FA;"), f.text())
    }

    @Test fun pickingARadioAfterTheFt817ResetsStopBits() {
        val s = RigSettings().apply(RigPreset.ft817)
        assertEquals(2, s.stopBits)
        assertEquals(4800, s.baud)
        assertEquals(1, s.apply(RigPreset.ic7300).stopBits)
    }

    @Test fun everyPresetSurvivesSaveAndLoad() {
        for (p in RigPreset.entries) {
            val s = RigSettings(connection = RigConnection.serial, serialPath = "0403:6001:0").apply(p)
            assertEquals(p.title, s, rigSettingsFromJson(s.toJson()))
        }
    }

    // MARK: FT-817/818/857/897

    /** Everything written so far, as one hex string. */
    private fun wire() = f.writes.joinToString(" ") { it.toHex() }

    @Test fun ft817ConnectSetsDigThenReadsStatus() {
        h.connect(serial(RigPreset.ft817))
        h.advance(1000)
        assertEquals("0A 00 00 00 07 00 00 00 00 03", wire())
        assertTrue("one byte per write", f.writes.all { it.size == 1 })
    }

    @Test fun ft817BytesAreSpacedOut() {
        h.connect(serial(RigPreset.ft857))
        assertEquals(1, f.writes.size)          // first byte goes at once
        h.advance(RigController.LEGACY_BYTE_MS)
        assertEquals(2, f.writes.size)
        h.advance(RigController.LEGACY_BYTE_MS * 3)
        assertEquals(5, f.writes.size)          // frame done, now the gap
        h.advance(RigController.LEGACY_GAP_MS - 1)
        assertEquals(5, f.writes.size)
        h.advance(1)
        assertEquals(6, f.writes.size)
    }

    @Test fun ft817FrequencyIsBigEndianBcdIn10HzSteps() {
        h.connect(serial(RigPreset.ft817).copy(setDataModeOnConnect = false))
        h.advance(1000)
        f.clear()
        h.rig.setFrequency(14_078_000)
        h.advance(200)
        assertEquals("01 40 78 00 01", wire())
        f.clear()
        h.rig.setFrequency(7_078_500)
        h.advance(200)
        assertEquals("00 70 78 50 01", wire())
        assertEquals("01 40 78 00", RigController.bcdBigEndian(1_407_800, 4).toHex())
        assertEquals(1_407_800L, RigController.fromBCDBigEndian(listOf(0x01, 0x40, 0x78, 0x00)))
    }

    @Test fun ft817StatusReplySetsFrequency() {
        h.connect(serial(RigPreset.ft897).copy(setDataModeOnConnect = false))
        h.advance(100)                           // status read sent
        f.reply(hex("01 40 78 00"))
        assertNull(h.rig.frequency)              // waits for all 5 bytes
        f.reply(hex("0A"))
        assertEquals(14_078_000L, h.rig.frequency)
    }

    @Test fun ft817IgnoresAcksAndGarbage() {
        h.connect(serial(RigPreset.ft817).copy(setDataModeOnConnect = false))
        h.advance(100)
        f.reply(hex("FF FF FF FF 0A"))           // not BCD
        assertNull(h.rig.frequency)
        f.reply(hex("00"))                       // ack while nothing is pending
        f.reply(hex("01 40 78 00 0A"))           // unsolicited: no read pending
        assertNull(h.rig.frequency)
        h.advance(1500)                          // next poll
        f.reply(hex("00 70 78 00 0A"))
        assertEquals(7_078_000L, h.rig.frequency)
    }

    @Test fun ft817PttJumpsTheQueueWithoutSplittingAFrame() {
        h.connect(serial(RigPreset.ft817))       // DIG frame, then status read
        assertEquals(1, f.writes.size)           // DIG frame on the wire
        h.rig.setPTT(true)
        h.advance(1000)
        assertEquals("0A 00 00 00 07 00 00 00 00 08 00 00 00 00 03", wire())
        f.clear()
        h.advance(3000)
        assertTrue("no polling while transmitting", f.writes.isEmpty())
        h.rig.setPTT(false)
        h.advance(200)
        assertEquals("00 00 00 00 88", wire())
    }

    @Test fun ft817DisconnectAlwaysUnkeys() {
        h.connect(serial(RigPreset.ft817).copy(setDataModeOnConnect = false))
        h.advance(1000)
        h.rig.setPTT(true)
        h.advance(RigController.LEGACY_BYTE_MS)  // 2 of 5 PTT-on bytes sent
        f.clear()
        h.rig.disconnect()
        // The rest of PTT on, then PTT off, in one write before the port closes.
        assertEquals(listOf("00 00 08 00 00 00 00 88"), f.hex())
        assertTrue(f.closed)
        assertFalse(h.rig.pttActive)
    }

    @Test fun ft817PollsDontPileUp() {
        h.connect(serial(RigPreset.ft817).copy(setDataModeOnConnect = false, pollSeconds = 0.5))
        h.advance(9_900)   // last read (9.5 s) finished, next not started
        val frames = wire().split(" ").chunked(5).map { it.joinToString(" ") }
        assertTrue(frames.all { it == "00 00 00 00 03" })
        assertEquals("one read at connect, then one per 0.5 s", 20, frames.size)
    }

    @Test fun reconnectClearsAHalfSentFrame() {
        h.connect(serial(RigPreset.ft817))
        h.connect(serial(RigPreset.ic7300))
        h.advance(1000)
        assertTrue(f.hex().last().startsWith("FE FE 94 E0"))
    }
}
