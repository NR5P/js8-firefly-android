package com.js8firefly.android.radio

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** How the app talks to the radio for frequency and PTT. */
enum class RigConnection(val rawValue: String) {
    none("none"),
    serial("serial"),        // USB serial (CAT) via USB OTG
    bluetooth("bluetooth"),  // BLE serial adapter on the CAT port (CAT)
    tcpSerial("tcpSerial"),  // raw serial-over-TCP bridge (ser2net, WiFi CAT dongles) (CAT)
    rigctld("rigctld"),      // Hamlib rigctld
    flrig("flrig");          // flrig XML-RPC

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            none -> "None (set dial manually)"
            serial -> "USB serial CAT"
            bluetooth -> "Bluetooth LE serial adapter"
            tcpSerial -> "Serial-over-network bridge"
            rigctld -> "Hamlib rigctld (network)"
            flrig -> "flrig (network)"
        }

    val usesCATProtocol: Boolean get() = this == serial || this == bluetooth || this == tcpSerial

    companion object {
        /** Android hosts USB serial adapters over OTG, so every connection is available. */
        val available: List<RigConnection> get() = entries.toList()
        val allCases: List<RigConnection> get() = entries.toList()
        fun fromRaw(raw: String?): RigConnection? = entries.firstOrNull { it.rawValue == raw }
    }
}

enum class CATProtocol(val rawValue: String) {
    yaesu("yaesu"), kenwood("kenwood"), icom("icom");

    val id: String get() = rawValue
    val title: String
        get() = when (this) {
            yaesu -> "Yaesu (new CAT)"
            kenwood -> "Kenwood / Elecraft / QRP Labs"
            icom -> "Icom CI-V (and Xiegu)"
        }

    companion object {
        val allCases: List<CATProtocol> get() = entries.toList()
        fun fromRaw(raw: String?): CATProtocol? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Radio presets with sensible CAT defaults. */
enum class RigPreset(val rawValue: String) {
    custom("custom"),
    qmx("qmx"), qdx("qdx"), truSDX("truSDX"),
    ftdx3000("ftdx3000"), ftdx5000("ftdx5000"), ft950("ft950"), ft991("ft991"), ftdx101("ftdx101"),
    ftdx10("ftdx10"), ft710("ft710"), ft891("ft891"), ft450("ft450"),
    ts480("ts480"), ts590("ts590"), ts2000("ts2000"), k3("k3"), kx2kx3("kx2kx3"), k4("k4"),
    ic7300("ic7300"), ic705("ic705"), ic9700("ic9700"), ic7610("ic7610"), ic7100("ic7100"), xiegu("xiegu");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            custom -> "Custom"
            qmx -> "QRP Labs QMX"
            qdx -> "QRP Labs QDX"
            truSDX -> "(tr)uSDX"
            ftdx3000 -> "Yaesu FTDX3000"
            ftdx5000 -> "Yaesu FTDX5000"
            ft950 -> "Yaesu FT-950 / FT-2000"
            ft991 -> "Yaesu FT-991 / 991A"
            ftdx101 -> "Yaesu FTDX101"
            ftdx10 -> "Yaesu FTDX10"
            ft710 -> "Yaesu FT-710"
            ft891 -> "Yaesu FT-891"
            ft450 -> "Yaesu FT-450"
            ts480 -> "Kenwood TS-480"
            ts590 -> "Kenwood TS-590 / 890"
            ts2000 -> "Kenwood TS-2000"
            k3 -> "Elecraft K3 / K3S"
            kx2kx3 -> "Elecraft KX2 / KX3"
            k4 -> "Elecraft K4"
            ic7300 -> "Icom IC-7300"
            ic705 -> "Icom IC-705"
            ic9700 -> "Icom IC-9700"
            ic7610 -> "Icom IC-7610"
            ic7100 -> "Icom IC-7100"
            xiegu -> "Xiegu G90 / X6100 / X5105"
        }

    val catProtocol: CATProtocol
        get() = when (this) {
            ftdx3000, ftdx5000, ft950, ft991, ftdx101, ftdx10, ft710, ft891, ft450 -> CATProtocol.yaesu
            ic7300, ic705, ic9700, ic7610, ic7100, xiegu -> CATProtocol.icom
            else -> CATProtocol.kenwood
        }

    val baud: Int
        get() = when (this) {
            qmx, qdx, truSDX -> 115200
            ftdx3000, ftdx5000, ft950, ft991, ftdx101, ftdx10, ft710, ft891 -> 38400
            ft450, ts480, ts2000 -> 9600
            ts590, k3, kx2kx3, k4 -> 38400
            ic7300, ic705, ic9700, ic7610, ic7100, xiegu -> 19200
            custom -> 38400
        }

    /** Digits in the Yaesu FA command (older rigs use 8, newer use 9). */
    val yaesuDigits: Int
        get() = when (this) {
            ftdx3000, ftdx5000, ft950, ft450 -> 8
            else -> 9
        }

    val icomAddress: Int
        get() = when (this) {
            ic7300 -> 0x94
            ic705 -> 0xA4
            ic9700 -> 0xA2
            ic7610 -> 0x98
            ic7100 -> 0x88
            xiegu -> 0x70
            else -> 0x94
        }

    companion object {
        val allCases: List<RigPreset> get() = entries.toList()
        fun fromRaw(raw: String?): RigPreset? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Rig connection settings. `serialPath` holds a USB serial port key from
 * [UsbSerialPortInfo.id]; `bluetoothID` holds the adapter's Bluetooth address.
 * `icomAddress` is 0...255 and `port` 0...65535 (Swift UInt8 / UInt16).
 */
data class RigSettings(
    val connection: RigConnection = RigConnection.none,
    val preset: RigPreset = RigPreset.custom,
    val catProtocol: CATProtocol = CATProtocol.kenwood,
    val serialPath: String = "",
    val baud: Int = 38400,
    val stopBits: Int = 1,
    val yaesuDigits: Int = 9,
    val icomAddress: Int = 0x94,
    val bluetoothID: String = "",
    val host: String = "192.168.1.10",
    val port: Int = 4532,
    val setDataModeOnConnect: Boolean = true,
    val pollSeconds: Double = 1.5,
) {
    /** Swift's `mutating func apply(_:)`: returns the settings with the preset's CAT defaults. */
    fun apply(p: RigPreset): RigSettings {
        if (p == RigPreset.custom) return copy(preset = p)
        return copy(preset = p, catProtocol = p.catProtocol, baud = p.baud,
            yaesuDigits = p.yaesuDigits, icomAddress = p.icomAddress)
    }
}

/** Frequency and PTT control for the connected radio. Use on the main thread. */
class RigController(context: Context) {
    private val context = context.applicationContext

    sealed class Status {
        data object disconnected : Status()
        data object connecting : Status()
        data object connected : Status()
        data class error(val message: String) : Status()

        val label: String
            get() = when (this) {
                disconnected -> "Not connected"
                connecting -> "Connecting…"
                connected -> "Connected"
                is error -> message
            }
    }

    var status by mutableStateOf<Status>(Status.disconnected)
        private set
    var frequency by mutableStateOf<Long?>(null)
        private set
    var pttActive by mutableStateOf(false)
        private set
    var onFrequency: ((Long) -> Unit)? = null

    /** USB serial ports seen by the last [refreshSerialPorts] (Swift: SerialTransport.availablePorts()). */
    var serialPorts by mutableStateOf<List<UsbSerialPortInfo>>(emptyList())
        private set
    /** Bluetooth LE serial adapters found by [startBLEScan], in discovery order. */
    var bleDevices by mutableStateOf<List<BlePeripheral>>(emptyList())
        private set
    val isScanning: Boolean get() = scanner != null
    private var scannerState by mutableStateOf<BLESerialTransport?>(null)
    private var scanner: BLESerialTransport?
        get() = scannerState
        set(v) { scannerState = v }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings = RigSettings()
    private var transport: ByteTransport? = null
    private val rxBuffer = ByteArrayOutputStream()
    private var pollJob: Job? = null
    private var lastSetTime = 0L
    private val pendingRigctldReplies = ArrayDeque<(String) -> Unit>()
    /** Waiting on the Icom mode read so data mode keeps the rig's current filter. */
    private var icomDataModePending = false

    val isConnected: Boolean get() = status == Status.connected
    val supportsPTT: Boolean get() = settings.connection != RigConnection.none
    val connection: RigConnection get() = settings.connection

    fun configure(s: RigSettings) {
        if (s == settings && status == Status.connected) return
        disconnect()
        settings = s
        connect()
    }

    fun connect() {
        rxBuffer.reset()
        pendingRigctldReplies.clear()
        icomDataModePending = false
        val t: ByteTransport?
        when (settings.connection) {
            RigConnection.none -> {
                status = Status.disconnected
                return
            }
            RigConnection.serial -> {
                refreshSerialPorts()
                // With nothing chosen, a single attached adapter is the obvious one.
                val key = settings.serialPath.ifEmpty { serialPorts.singleOrNull()?.id ?: "" }
                if (key.isEmpty()) {
                    status = Status.error(if (serialPorts.isEmpty()) "No USB serial adapter found" else "Choose a serial port")
                    return
                }
                t = SerialTransport(context, key, settings.baud, settings.stopBits)
            }
            RigConnection.bluetooth -> t = BLESerialTransport(context, settings.bluetoothID.ifEmpty { null })
            RigConnection.tcpSerial, RigConnection.rigctld -> t = TCPTransport(settings.host, settings.port)
            RigConnection.flrig -> t = null
        }
        status = Status.connecting
        if (t != null) {
            transport = t
            t.onData = { d -> if (transport === t) received(d) }
            t.onState = { st -> if (transport === t) transportState(st) }
            t.open()
        } else {
            // flrig is HTTP; probe it.
            flrigCall("rig.get_vfo", emptyList()) { r ->
                if (settings.connection != RigConnection.flrig || status != Status.connecting) return@flrigCall
                if (r != null) {
                    status = Status.connected
                    didConnect()
                } else {
                    status = Status.error("flrig not reachable at ${settings.host}:${settings.port}")
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        if (pttActive) setPTT(false)
        transport?.close()
        transport = null
        status = Status.disconnected
    }

    /** A USB device was plugged in (MainActivity's USB_DEVICE_ATTACHED): reconnect if serial CAT is waiting on it. */
    fun usbDeviceAttached(device: UsbDevice?) {
        refreshSerialPorts()
        if (settings.connection == RigConnection.serial && status != Status.connected) {
            disconnect()
            connect()
        }
    }

    /** Re-list attached USB serial adapters into [serialPorts]. */
    fun refreshSerialPorts() {
        serialPorts = SerialTransport.availablePorts(context)
    }

    // MARK: Bluetooth scan (settings picker)

    fun startBLEScan() {
        if (scanner != null) return
        bleDevices = emptyList()
        val s = BLESerialTransport(context, null)
        s.onDiscover = { p, _ ->
            if (bleDevices.none { it.identifier == p.identifier }) bleDevices = bleDevices + p
        }
        s.onState = { st -> if (st is TransportState.failed) status = Status.error(st.message) }
        s.scan()
        scanner = s
    }

    fun stopBLEScan() {
        scanner?.stopScan()
        scanner = null
    }

    private fun transportState(st: TransportState) {
        when (st) {
            TransportState.connected -> {
                status = Status.connected
                didConnect()
            }
            TransportState.connecting -> status = Status.connecting
            is TransportState.failed -> {
                pollJob?.cancel()
                status = Status.error(st.message)
            }
            TransportState.idle -> {}
        }
    }

    private fun didConnect() {
        if (settings.setDataModeOnConnect) setDataMode()
        poll()
        pollJob?.cancel()
        val interval = (maxOf(0.5, settings.pollSeconds) * 1000).toLong()
        pollJob = scope.launch {
            while (true) {
                delay(interval)
                poll()
            }
        }
    }

    // MARK: Commands

    fun setFrequency(hz: Long) {
        if (status != Status.connected) return
        lastSetTime = System.currentTimeMillis()
        frequency = hz
        when (settings.connection) {
            RigConnection.rigctld -> rigctld("F $hz") {}
            RigConnection.flrig -> flrigCall("rig.set_vfo", listOf("<double>$hz</double>")) {}
            else -> when (settings.catProtocol) {
                CATProtocol.yaesu -> send(String.format(Locale.US, "FA%0${settings.yaesuDigits}d;", hz))
                CATProtocol.kenwood -> send(String.format(Locale.US, "FA%011d;", hz))
                CATProtocol.icom -> sendCIV(byteArrayOf(0x05) + bcd(hz, 5))
            }
        }
    }

    fun setPTT(on: Boolean) {
        pttActive = on
        if (status != Status.connected) return
        when (settings.connection) {
            RigConnection.rigctld -> rigctld("T ${if (on) 1 else 0}") {}
            RigConnection.flrig -> flrigCall("rig.set_ptt", listOf("<int>${if (on) 1 else 0}</int>")) {}
            else -> when (settings.catProtocol) {
                CATProtocol.yaesu -> send(if (on) "TX1;" else "TX0;")
                CATProtocol.kenwood -> send(if (on) "TX;" else "RX;")
                CATProtocol.icom -> sendCIV(byteArrayOf(0x1C, 0x00, if (on) 0x01 else 0x00))
            }
        }
    }

    /** Serial modem-line PTT (e.g. Digirig Mobile RTS) — USB serial only. */
    fun setRTS(on: Boolean) { transport?.setRTS(on) }
    fun setDTR(on: Boolean) { transport?.setDTR(on) }

    private fun setDataMode() {
        when (settings.connection) {
            // Passband -1 leaves the rig's filter alone; 0 would reset it to Hamlib's default.
            RigConnection.rigctld -> rigctld("M PKTUSB -1") {}
            RigConnection.flrig -> {}
            else -> when (settings.catProtocol) {
                CATProtocol.yaesu -> send("MD0C;")
                // USB; QMX/QDX/Elecraft treat USB with audio as digital.
                CATProtocol.kenwood -> send("MD2;")
                CATProtocol.icom -> {
                    // Both mode commands carry a filter byte; read the current one first.
                    icomDataModePending = true
                    sendCIV(byteArrayOf(0x04))
                }
            }
        }
    }

    private fun poll() {
        if (status != Status.connected || pttActive) return
        when (settings.connection) {
            RigConnection.rigctld -> rigctld("f") { reply ->
                reply.trim().toLongOrNull()?.let { gotFrequency(it) }
            }
            RigConnection.flrig -> flrigCall("rig.get_vfo", emptyList()) { r ->
                if (r != null) {
                    val hz = r.trim().toLongOrNull() ?: r.trim().toDoubleOrNull()?.toLong()
                    if (hz != null) gotFrequency(hz)
                }
            }
            else -> when (settings.catProtocol) {
                CATProtocol.yaesu, CATProtocol.kenwood -> send("FA;")
                CATProtocol.icom -> sendCIV(byteArrayOf(0x03))
            }
        }
    }

    private fun gotFrequency(hz: Long) {
        if (hz <= 100_000) return
        // Ignore stale reads right after we commanded a change.
        if (System.currentTimeMillis() - lastSetTime < 1000) return
        if (frequency != hz) {
            frequency = hz
            onFrequency?.invoke(hz)
        }
    }

    // MARK: Transport I/O

    private fun send(s: String) { transport?.write(s.toByteArray(Charsets.UTF_8)) }

    private fun sendCIV(body: ByteArray) {
        val frame = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), settings.icomAddress.toByte(), 0xE0.toByte()) +
            body + byteArrayOf(0xFD.toByte())
        transport?.write(frame)
    }

    companion object {
        fun bcd(hz: Long, bytes: Int): ByteArray {
            var v = hz
            val out = ByteArray(bytes)
            for (i in 0 until bytes) {
                val lo = (v % 10).toInt(); v /= 10
                val hi = (v % 10).toInt(); v /= 10
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        fun fromBCD(b: List<Int>): Long {
            var v = 0L
            for (byte in b.reversed()) v = v * 100 + (byte shr 4) * 10 + (byte and 0x0F)
            return v
        }
    }

    private fun received(d: ByteArray) {
        rxBuffer.write(d)
        var buf = rxBuffer.toByteArray()
        fun consume(through: Int) {
            buf = buf.copyOfRange(through + 1, buf.size)
        }
        when (settings.connection) {
            RigConnection.rigctld -> {
                while (true) {
                    val nl = buf.indexOf(0x0A.toByte())
                    if (nl < 0) break
                    val line = String(buf, 0, nl, Charsets.UTF_8)
                    consume(nl)
                    pendingRigctldReplies.removeFirstOrNull()?.invoke(line)
                }
            }
            else -> when (settings.catProtocol) {
                CATProtocol.yaesu, CATProtocol.kenwood -> {
                    while (true) {
                        val semi = buf.indexOf(';'.code.toByte())
                        if (semi < 0) break
                        val reply = String(buf, 0, semi, Charsets.UTF_8)
                        consume(semi)
                        if (reply.startsWith("FA")) reply.substring(2).toLongOrNull()?.let { gotFrequency(it) }
                    }
                }
                CATProtocol.icom -> {
                    while (true) {
                        val end = buf.indexOf(0xFD.toByte())
                        if (end < 0) break
                        val frame = buf.copyOfRange(0, end + 1).map { it.toInt() and 0xFF }
                        consume(end)
                        val start = frame.indexOf(0xFE)
                        if (start < 0 || frame.size < start + 6) continue
                        val f = frame.subList(start, frame.size)
                        // FE FE E0 <rig> cmd data FD
                        if (f.size < 6 || f[2] != 0xE0) continue
                        val cmd = f[4]
                        if ((cmd == 0x03 || cmd == 0x00) && f.size >= 11) {
                            gotFrequency(fromBCD(f.subList(5, 10)))
                        }
                        // FE FE E0 <rig> 04 mode filter FD
                        if (cmd == 0x04 && f.size >= 8 && icomDataModePending) {
                            icomDataModePending = false
                            val filter = f[6].toByte()
                            sendCIV(byteArrayOf(0x06, 0x01, filter))          // USB, current filter
                            sendCIV(byteArrayOf(0x1A, 0x06, 0x01, filter))    // data mode on (newer rigs)
                        }
                    }
                }
            }
        }
        rxBuffer.reset()
        if (buf.size <= 4096) rxBuffer.write(buf)
    }

    private fun rigctld(cmd: String, reply: (String) -> Unit) {
        pendingRigctldReplies.addLast(reply)
        send(cmd + "\n")
    }

    private fun flrigCall(method: String, params: List<String>, done: (String?) -> Unit) {
        val host = settings.host
        val port = settings.port
        scope.launch {
            val value = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$host:$port/RPC2")
                    val c = url.openConnection() as HttpURLConnection
                    c.connectTimeout = 2000
                    c.readTimeout = 2000
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "text/xml")
                    val p = params.joinToString("") { "<param><value>$it</value></param>" }
                    val body = "<?xml version=\"1.0\"?><methodCall><methodName>$method</methodName><params>$p</params></methodCall>"
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    c.disconnect()
                    val r = text.indexOf("<value>")
                    if (r >= 0) {
                        val e = text.indexOf("</value>", r + 7)
                        if (e >= 0) text.substring(r + 7, e).replace(Regex("<[^>]+>"), "") else ""
                    } else ""
                } catch (e: Exception) {
                    null
                }
            }
            done(value)
        }
    }
}
