package com.js8firefly.android.radio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in what the app sent to every radio before the radio list grew
 * (Nov 2026). These expectations were written against that code and must keep
 * passing: a failure here means a radio that used to work now gets different bytes.
 */
class ExistingRigBehaviorTest {
    private val h = RigHarness()
    private val f get() = h.fake

    @After fun tearDown() = h.close()

    // MARK: Preset table (values a user gets when picking the radio)

    private data class P(val protocol: CATProtocol, val baud: Int, val digits: Int, val civ: Int)

    private val before = mapOf(
        "qmx" to P(CATProtocol.kenwood, 115200, 9, 0x94),
        "qdx" to P(CATProtocol.kenwood, 115200, 9, 0x94),
        "truSDX" to P(CATProtocol.kenwood, 115200, 9, 0x94),
        "ftdx3000" to P(CATProtocol.yaesu, 38400, 8, 0x94),
        "ftdx5000" to P(CATProtocol.yaesu, 38400, 8, 0x94),
        "ft950" to P(CATProtocol.yaesu, 38400, 8, 0x94),
        "ft991" to P(CATProtocol.yaesu, 38400, 9, 0x94),
        "ftdx101" to P(CATProtocol.yaesu, 38400, 9, 0x94),
        "ftdx10" to P(CATProtocol.yaesu, 38400, 9, 0x94),
        "ft710" to P(CATProtocol.yaesu, 38400, 9, 0x94),
        "ft891" to P(CATProtocol.yaesu, 38400, 9, 0x94),
        "ft450" to P(CATProtocol.yaesu, 9600, 8, 0x94),
        "ts480" to P(CATProtocol.kenwood, 9600, 9, 0x94),
        "ts590" to P(CATProtocol.kenwood, 38400, 9, 0x94),
        "ts2000" to P(CATProtocol.kenwood, 9600, 9, 0x94),
        "k3" to P(CATProtocol.kenwood, 38400, 9, 0x94),
        "kx2kx3" to P(CATProtocol.kenwood, 38400, 9, 0x94),
        "k4" to P(CATProtocol.kenwood, 38400, 9, 0x94),
        "ic7300" to P(CATProtocol.icom, 19200, 9, 0x94),
        "ic705" to P(CATProtocol.icom, 19200, 9, 0xA4),
        "ic9700" to P(CATProtocol.icom, 19200, 9, 0xA2),
        "ic7610" to P(CATProtocol.icom, 19200, 9, 0x98),
        "ic7100" to P(CATProtocol.icom, 19200, 9, 0x88),
        "xiegu" to P(CATProtocol.icom, 19200, 9, 0x70),
    )

    @Test fun existingPresetsKeepTheirSettings() {
        for ((raw, p) in before) {
            val preset = RigPreset.fromRaw(raw)
            requireNotNull(preset) { "preset $raw disappeared; saved settings would fall back to Custom" }
            val s = RigSettings().apply(preset)
            assertEquals("$raw protocol", p.protocol, s.catProtocol)
            assertEquals("$raw baud", p.baud, s.baud)
            assertEquals("$raw Yaesu digits", p.digits, s.yaesuDigits)
            assertEquals("$raw CI-V address", p.civ, s.icomAddress)
            assertEquals("$raw stop bits", 1, s.stopBits)
        }
        assertEquals(CATProtocol.kenwood, RigSettings().catProtocol)
        assertEquals("custom", RigPreset.custom.rawValue)
    }

    @Test fun customKeepsHandEnteredValues() {
        val s = RigSettings(catProtocol = CATProtocol.icom, baud = 4800, icomAddress = 0x5E, stopBits = 2)
        assertEquals(s.copy(preset = RigPreset.custom), s.apply(RigPreset.custom))
    }

    // MARK: Icom CI-V

    @Test fun icomConnectReadsModeThenPolls() {
        h.connect(serial(RigPreset.ic7300))
        assertEquals(listOf("FE FE 94 E0 04 FD", "FE FE 94 E0 03 FD"), f.hex())
    }

    @Test fun icomDataModeKeepsTheRigFilter() {
        h.connect(serial(RigPreset.ic7300))
        f.clear()
        f.reply(hex("FE FE E0 94 04 01 02 FD"))   // USB, FIL2
        assertEquals(listOf("FE FE 94 E0 06 01 02 FD", "FE FE 94 E0 1A 06 01 02 FD"), f.hex())
        f.clear()
        f.reply(hex("FE FE E0 94 04 01 03 FD"))   // a second mode reply is not acted on
        assertTrue(f.writes.isEmpty())
    }

    @Test fun icomFrequencyPttAndPolling() {
        h.connect(serial(RigPreset.ic7100))
        f.clear()
        h.rig.setFrequency(14_078_000)
        h.rig.setPTT(true)
        h.rig.setPTT(false)
        assertEquals(listOf(
            "FE FE 88 E0 05 00 80 07 14 00 FD",
            "FE FE 88 E0 1C 00 01 FD",
            "FE FE 88 E0 1C 00 00 FD",
        ), f.hex())
        f.clear()
        h.advance(1500)
        assertEquals(listOf("FE FE 88 E0 03 FD"), f.hex())
    }

    @Test fun icomFrequencyReplyIgnoresEchoAndBroadcast() {
        h.connect(serial(RigPreset.ic7300))
        f.reply(hex("FE FE 94 E0 03 FD"))                       // our own echo
        f.reply(hex("FE FE 00 94 00 00 00 07 14 00 FD"))       // transceive broadcast
        assertNull(h.rig.frequency)
        f.reply(hex("FE FE E0 94 03 00 80 07 14 00 FD"))
        assertEquals(14_078_000L, h.rig.frequency)
        f.reply(hex("FE FE E0 94 03 00 40 07"))                 // split across reads
        f.reply(hex("14 00 FD"))
        assertEquals(14_074_000L, h.rig.frequency)
    }

    @Test fun icomBcdHelpers() {
        assertEquals("00 80 07 14 00", RigController.bcd(14_078_000, 5).toHex())
        assertEquals("00 00 25 50 00", RigController.bcd(50_250_000, 5).toHex())
        assertEquals(7_078_000L, RigController.fromBCD(listOf(0x00, 0x80, 0x07, 0x07, 0x00)))
    }

    // MARK: Yaesu new CAT

    @Test fun yaesu9DigitRig() {
        h.connect(serial(RigPreset.ft991))
        assertEquals(listOf("MD0C;", "FA;"), f.text())
        f.clear()
        h.rig.setFrequency(14_078_000)
        h.rig.setPTT(true)
        h.rig.setPTT(false)
        assertEquals(listOf("FA014078000;", "TX1;", "TX0;"), f.text())
        f.reply("FA007078000;")
        assertEquals(14_078_000L, h.rig.frequency)   // ignored: within 1 s of our own set
    }

    @Test fun yaesuFrequencyReply() {
        h.connect(serial(RigPreset.ft891))
        f.reply("FA007078000;")
        assertEquals(7_078_000L, h.rig.frequency)
    }

    @Test fun yaesu8DigitRig() {
        h.connect(serial(RigPreset.ftdx3000))
        f.clear()
        h.rig.setFrequency(7_078_000)
        assertEquals(listOf("FA07078000;"), f.text())
    }

    // MARK: Kenwood / Elecraft / QRP Labs

    @Test fun kenwoodFollowsActiveVfo() {
        h.connect(serial(RigPreset.ts480))
        assertEquals(listOf("MD2;", "FR;FA;"), f.text())
        f.clear()
        f.reply("FR1;")
        h.advance(1500)
        assertEquals(listOf("FR;FB;"), f.text())
        f.reply("FB00007078000;")
        assertEquals(7_078_000L, h.rig.frequency)
        f.clear()
        h.rig.setFrequency(14_078_000)
        h.rig.setPTT(true)
        h.rig.setPTT(false)
        assertEquals(listOf("FB00014078000;", "TX;", "RX;"), f.text())
    }

    @Test fun kenwoodSetsVfoA() {
        h.connect(serial(RigPreset.ts590))
        f.clear()
        h.rig.setFrequency(14_078_000)
        assertEquals(listOf("FA00014078000;"), f.text())
    }

    @Test fun qmxUsesDigiMode() {
        h.connect(serial(RigPreset.qmx))
        assertEquals("MD6;", f.text().first())
    }

    @Test fun elecraftUsesUsb() {
        h.connect(serial(RigPreset.k3))
        assertEquals("MD2;", f.text().first())
    }

    // MARK: Network and line PTT

    @Test fun rigctld() {
        h.connect(RigSettings(connection = RigConnection.rigctld))
        assertEquals(listOf("M PKTUSB -1\n", "f\n"), f.text())
        f.clear()
        h.rig.setFrequency(14_078_000)
        h.rig.setPTT(true)
        assertEquals(listOf("F 14078000\n", "T 1\n"), f.text())
    }

    @Test fun rtsAndDtrGoToTheSerialPort() {
        h.connect(serial(RigPreset.ic7300))
        h.rig.setRTS(true); h.rig.setRTS(false); h.rig.setDTR(true)
        assertEquals(listOf(true, false), f.rts)
        assertEquals(listOf(true), f.dtr)
    }

    @Test fun noDataModeWhenTurnedOff() {
        h.connect(serial(RigPreset.ft991).copy(setDataModeOnConnect = false))
        assertEquals(listOf("FA;"), f.text())
    }

    @Test fun disconnectReleasesPtt() {
        h.connect(serial(RigPreset.ft991))
        h.rig.setPTT(true)
        f.clear()
        h.rig.disconnect()
        assertEquals(listOf("TX0;"), f.text())
        assertTrue(f.closed)
    }
}
