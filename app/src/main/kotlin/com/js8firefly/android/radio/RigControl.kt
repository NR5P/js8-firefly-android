package com.js8firefly.android.radio

import com.js8firefly.android.BuildConfig
import android.util.Log
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
    yaesu("yaesu"), kenwood("kenwood"), icom("icom"), yaesuLegacy("yaesuLegacy");

    val id: String get() = rawValue
    val title: String
        get() = when (this) {
            yaesu -> "Yaesu (new CAT)"
            kenwood -> "Kenwood / Elecraft / QRP Labs"
            icom -> "Icom CI-V (and Xiegu)"
            yaesuLegacy -> "Yaesu FT-817/818/857/897"
        }

    companion object {
        val allCases: List<CATProtocol> get() = entries.toList()
        fun fromRaw(raw: String?): CATProtocol? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Radio presets with sensible CAT defaults. `rawValue` is what settings store,
 * so never rename one; entry order is the picker order.
 *
 * Values come from Hamlib's rig definitions (CI-V default address, serial
 * limits, whether CAT PTT exists), cross-checked against flrig and the
 * manufacturers' CAT manuals. Icom radios that use the old 4-byte frequency
 * format (IC-735, IC-820H, IC-821H) are left out: Custom can't drive them either.
 */
enum class RigPreset(
    val rawValue: String,
    val title: String,
    val catProtocol: CATProtocol,
    val baud: Int,
    /** Digits in the Yaesu FA command (older rigs use 8, newer use 9). */
    val yaesuDigits: Int = 9,
    val icomAddress: Int = 0x94,
    val stopBits: Int = 1,
    /** False: the radio has no CAT transmit command, so PTT must be RTS/DTR or VOX. */
    val hasCATPTT: Boolean = true,
) {
    custom("custom", "Custom", CATProtocol.kenwood, 38400),

    // QRP Labs and kits
    qmx("qmx", "QRP Labs QMX / QMX+", CATProtocol.kenwood, 115200),
    qdx("qdx", "QRP Labs QDX", CATProtocol.kenwood, 115200),
    truSDX("truSDX", "(tr)uSDX", CATProtocol.kenwood, 115200),

    // Elecraft (Kenwood-style CAT)
    k2("k2", "Elecraft K2", CATProtocol.kenwood, 4800, stopBits = 2),
    k3("k3", "Elecraft K3 / K3S", CATProtocol.kenwood, 38400),
    kx2kx3("kx2kx3", "Elecraft KX2 / KX3", CATProtocol.kenwood, 38400),
    k4("k4", "Elecraft K4", CATProtocol.kenwood, 38400),

    // Icom CI-V
    ic703("ic703", "Icom IC-703", CATProtocol.icom, 19200, icomAddress = 0x68),
    ic705("ic705", "Icom IC-705", CATProtocol.icom, 19200, icomAddress = 0xA4),
    ic706("ic706", "Icom IC-706", CATProtocol.icom, 19200, icomAddress = 0x48, hasCATPTT = false),
    ic706mk2("ic706mk2", "Icom IC-706MKII", CATProtocol.icom, 19200, icomAddress = 0x4E, hasCATPTT = false),
    ic706mk2g("ic706mk2g", "Icom IC-706MKIIG", CATProtocol.icom, 19200, icomAddress = 0x58, hasCATPTT = false),
    ic707("ic707", "Icom IC-707", CATProtocol.icom, 9600, icomAddress = 0x3E, hasCATPTT = false),
    ic718("ic718", "Icom IC-718", CATProtocol.icom, 19200, icomAddress = 0x5E, hasCATPTT = false),
    ic725("ic725", "Icom IC-725", CATProtocol.icom, 9600, icomAddress = 0x28, hasCATPTT = false),
    ic728("ic728", "Icom IC-728", CATProtocol.icom, 9600, icomAddress = 0x38, hasCATPTT = false),
    ic729("ic729", "Icom IC-729", CATProtocol.icom, 9600, icomAddress = 0x3A, hasCATPTT = false),
    ic736("ic736", "Icom IC-736", CATProtocol.icom, 19200, icomAddress = 0x40, hasCATPTT = false),
    ic737("ic737", "Icom IC-737", CATProtocol.icom, 19200, icomAddress = 0x3C, hasCATPTT = false),
    ic738("ic738", "Icom IC-738", CATProtocol.icom, 19200, icomAddress = 0x44, hasCATPTT = false),
    ic746("ic746", "Icom IC-746", CATProtocol.icom, 19200, icomAddress = 0x56),
    ic746pro("ic746pro", "Icom IC-746PRO / IC-7400", CATProtocol.icom, 19200, icomAddress = 0x66),
    ic756("ic756", "Icom IC-756", CATProtocol.icom, 19200, icomAddress = 0x50, hasCATPTT = false),
    ic756pro("ic756pro", "Icom IC-756PRO", CATProtocol.icom, 19200, icomAddress = 0x5C),
    ic756pro2("ic756pro2", "Icom IC-756PROII", CATProtocol.icom, 19200, icomAddress = 0x64),
    ic756pro3("ic756pro3", "Icom IC-756PROIII", CATProtocol.icom, 19200, icomAddress = 0x6E),
    ic765("ic765", "Icom IC-765", CATProtocol.icom, 9600, icomAddress = 0x2C, hasCATPTT = false),
    ic775("ic775", "Icom IC-775", CATProtocol.icom, 19200, icomAddress = 0x46, hasCATPTT = false),
    ic78("ic78", "Icom IC-78", CATProtocol.icom, 19200, icomAddress = 0x62, hasCATPTT = false),
    ic781("ic781", "Icom IC-781", CATProtocol.icom, 9600, icomAddress = 0x26, hasCATPTT = false),
    ic7000("ic7000", "Icom IC-7000", CATProtocol.icom, 19200, icomAddress = 0x70),
    ic7100("ic7100", "Icom IC-7100", CATProtocol.icom, 19200, icomAddress = 0x88),
    ic7200("ic7200", "Icom IC-7200", CATProtocol.icom, 19200, icomAddress = 0x76),
    ic7300("ic7300", "Icom IC-7300", CATProtocol.icom, 19200, icomAddress = 0x94),
    ic7300mk2("ic7300mk2", "Icom IC-7300MK2", CATProtocol.icom, 19200, icomAddress = 0xB6),
    ic7410("ic7410", "Icom IC-7410", CATProtocol.icom, 19200, icomAddress = 0x80),
    ic7600("ic7600", "Icom IC-7600", CATProtocol.icom, 19200, icomAddress = 0x7A),
    ic7610("ic7610", "Icom IC-7610", CATProtocol.icom, 19200, icomAddress = 0x98),
    ic7700("ic7700", "Icom IC-7700", CATProtocol.icom, 19200, icomAddress = 0x74),
    ic7760("ic7760", "Icom IC-7760", CATProtocol.icom, 19200, icomAddress = 0xB2),
    ic7800("ic7800", "Icom IC-7800", CATProtocol.icom, 19200, icomAddress = 0x6A),
    ic7851("ic7851", "Icom IC-7850 / IC-7851", CATProtocol.icom, 19200, icomAddress = 0x8E),
    ic905("ic905", "Icom IC-905", CATProtocol.icom, 19200, icomAddress = 0xAC),
    ic910("ic910", "Icom IC-910H", CATProtocol.icom, 19200, icomAddress = 0x60),
    ic9100("ic9100", "Icom IC-9100", CATProtocol.icom, 19200, icomAddress = 0x7C),
    ic9700("ic9700", "Icom IC-9700", CATProtocol.icom, 19200, icomAddress = 0xA2),

    // Kenwood
    ts480("ts480", "Kenwood TS-480", CATProtocol.kenwood, 9600),
    ts570("ts570", "Kenwood TS-570", CATProtocol.kenwood, 9600),
    ts590("ts590", "Kenwood TS-590 / 890", CATProtocol.kenwood, 38400),
    ts870("ts870", "Kenwood TS-870", CATProtocol.kenwood, 9600),
    ts2000("ts2000", "Kenwood TS-2000", CATProtocol.kenwood, 9600),

    // Lab599 (Kenwood TS-2000 style CAT)
    tx500("tx500", "Lab599 Discovery TX-500", CATProtocol.kenwood, 9600),

    // Xiegu (Icom CI-V, each model with its own address)
    xieguG90("xieguG90", "Xiegu G90", CATProtocol.icom, 19200, icomAddress = 0x88),
    xieguX6100("xieguX6100", "Xiegu X6100 / X6200", CATProtocol.icom, 19200, icomAddress = 0xA4),
    xiegu("xiegu", "Xiegu X5105 / X108G", CATProtocol.icom, 19200, icomAddress = 0x70),

    // Yaesu new CAT (text commands ending in ';')
    ft450("ft450", "Yaesu FT-450 / 450D", CATProtocol.yaesu, 9600, yaesuDigits = 8),
    ft710("ft710", "Yaesu FT-710", CATProtocol.yaesu, 38400),
    ft891("ft891", "Yaesu FT-891", CATProtocol.yaesu, 38400),
    ft950("ft950", "Yaesu FT-950 / FT-2000", CATProtocol.yaesu, 38400, yaesuDigits = 8),
    ft991("ft991", "Yaesu FT-991 / 991A", CATProtocol.yaesu, 38400),
    ftdx10("ftdx10", "Yaesu FTDX10", CATProtocol.yaesu, 38400),
    ftdx101("ftdx101", "Yaesu FTDX101", CATProtocol.yaesu, 38400),
    ftdx1200("ftdx1200", "Yaesu FTDX1200", CATProtocol.yaesu, 38400, yaesuDigits = 8),
    ftdx3000("ftdx3000", "Yaesu FTDX3000", CATProtocol.yaesu, 38400, yaesuDigits = 8),
    ftdx5000("ftdx5000", "Yaesu FTDX5000", CATProtocol.yaesu, 38400, yaesuDigits = 8),
    ftdx9000("ftdx9000", "Yaesu FTDX9000", CATProtocol.yaesu, 38400, yaesuDigits = 8),

    // Yaesu 5-byte binary CAT; the radios' factory CAT RATE is 4800, 2 stop bits
    ft817("ft817", "Yaesu FT-817 / 818", CATProtocol.yaesuLegacy, 4800, stopBits = 2),
    ft857("ft857", "Yaesu FT-857 / 857D", CATProtocol.yaesuLegacy, 4800, stopBits = 2),
    ft897("ft897", "Yaesu FT-897 / 897D", CATProtocol.yaesuLegacy, 4800, stopBits = 2);

    val id: String get() = rawValue

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
            yaesuDigits = p.yaesuDigits, icomAddress = p.icomAddress, stopBits = p.stopBits)
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

    // FT-817/818/857/897 5-byte CAT: frames go out byte by byte through this queue
    // (Hamlib spaces them the same way); legacyPos is how far into the head frame we are.
    private val legacyQueue = ArrayDeque<ByteArray>()
    private var legacyPos = 0
    private var legacyJob: Job? = null
    /** A status read (opcode 03) went out; the next 5 bytes are frequency + mode. */
    private var legacyStatusPending = false

    /** Unit tests: supplies the byte transport instead of USB/Bluetooth/TCP. */
    internal var transportFactory: ((RigSettings) -> ByteTransport?)? = null

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
        kenwoodVFO = 'A'
        rxBuffer.reset()
        pendingRigctldReplies.clear()
        icomDataModePending = false
        resetLegacyQueue()
        val injected = if (settings.connection == RigConnection.none || settings.connection == RigConnection.flrig) null
            else transportFactory?.invoke(settings)
        val t: ByteTransport?
        if (injected != null) t = injected else when (settings.connection) {
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
                connectedPortName = serialPorts.firstOrNull { it.id == key }?.name ?: ""
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
        reconnectJob?.cancel()
        reconnectJob = null
        pollJob?.cancel()
        pollJob = null
        if (pttActive) setPTT(false)
        flushLegacyQueue()
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
                scheduleReconnect()
            }
            TransportState.idle -> {}
        }
    }

    private var reconnectJob: Job? = null

    /**
     * USB serial drops when the radio is unplugged or power-cycled; keep trying
     * every few seconds so CAT comes back on its own (a fresh plug-in also
     * reconnects at once through usbDeviceAttached).
     */
    private fun scheduleReconnect() {
        if (settings.connection != RigConnection.serial || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (settings.connection == RigConnection.serial && status != Status.connected) {
                delay(3000)
                if (status == Status.connecting || status == Status.connected) continue
                transport?.close()
                transport = null
                connect()
            }
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
                CATProtocol.kenwood -> send(String.format(Locale.US, "F%c%011d;", kenwoodVFO, hz))
                CATProtocol.icom -> sendCIV(byteArrayOf(0x05) + bcd(hz, 5))
                // 4 BCD bytes, most significant first, in 10 Hz steps; opcode 01.
                CATProtocol.yaesuLegacy -> sendLegacy(bcdBigEndian((hz + 5) / 10, 4) + byteArrayOf(0x01))
            }
        }
    }

    /** A QRP Labs QMX/QDX: by preset, or by the USB serial device's name with a Custom preset. */
    private val isQRPLabs: Boolean
        get() = settings.preset == RigPreset.qmx || settings.preset == RigPreset.qdx ||
            (settings.connection == RigConnection.serial && connectedPortName.uppercase().let { "QMX" in it || "QDX" in it })

    /** Kenwood-protocol VFO the rig receives on ('A' or 'B'), from its FR reply. */
    private var kenwoodVFO = 'A'

    /** Name of the USB serial port in use, e.g. "QRP Labs QMX Transceiver". */
    private var connectedPortName = ""

    fun setPTT(on: Boolean) {
        Log.i(TAG, "PTT ${if (on) "on" else "off"} (status ${status.label}, ${settings.connection.rawValue}/${settings.catProtocol.rawValue})")
        pttActive = on
        if (status != Status.connected) return
        when (settings.connection) {
            RigConnection.rigctld -> rigctld("T ${if (on) 1 else 0}") {}
            RigConnection.flrig -> flrigCall("rig.set_ptt", listOf("<int>${if (on) 1 else 0}</int>")) {}
            else -> when (settings.catProtocol) {
                CATProtocol.yaesu -> send(if (on) "TX1;" else "TX0;")
                CATProtocol.kenwood -> send(if (on) "TX;" else "RX;")
                CATProtocol.icom -> sendCIV(byteArrayOf(0x1C, 0x00, if (on) 0x01 else 0x00))
                CATProtocol.yaesuLegacy -> sendLegacy(byteArrayOf(0, 0, 0, 0, if (on) 0x08 else 0x88.toByte()), urgent = true)
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
                // QMX/QDX: Digi (MD6). Their newer firmware has SSB, where MD2 is
                // voice USB and the radio then ignores CAT TX. Others: USB.
                CATProtocol.kenwood -> send(if (isQRPLabs) "MD6;" else "MD2;")
                CATProtocol.icom -> {
                    // Both mode commands carry a filter byte; read the current one first.
                    icomDataModePending = true
                    sendCIV(byteArrayOf(0x04))
                }
                // DIG (0A): the rear DATA jack, sideband set by the radio's DIG MODE menu (USER-U).
                CATProtocol.yaesuLegacy -> sendLegacy(byteArrayOf(0x0A, 0, 0, 0, 0x07))
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
                CATProtocol.yaesu -> send("FA;")
                // Kenwood-style rigs (QMX, TS-480…) may be on VFO B: ask which, then read it.
                CATProtocol.kenwood -> send("FR;F$kenwoodVFO;")
                CATProtocol.icom -> sendCIV(byteArrayOf(0x03))
                // Read frequency and mode (opcode 03); skip if one is already waiting to go out.
                CATProtocol.yaesuLegacy ->
                    if (legacyQueue.none { it[4] == LEGACY_READ_STATUS }) sendLegacy(byteArrayOf(0, 0, 0, 0, LEGACY_READ_STATUS))
            }
        }
    }

    // MARK: FT-817/818/857/897 queue

    /**
     * Queues a 5-byte command. PTT is [urgent]: it goes next, after the command
     * already on the wire, so it is never stuck behind polls and never splits a frame.
     */
    private fun sendLegacy(frame: ByteArray, urgent: Boolean = false) {
        if (urgent) legacyQueue.add(if (legacyPos > 0) 1 else 0, frame) else legacyQueue.addLast(frame)
        if (legacyJob?.isActive == true) return
        legacyJob = scope.launch {
            while (legacyQueue.isNotEmpty()) {
                val frame = legacyQueue.first()
                if (legacyPos == 0 && frame[4] == LEGACY_READ_STATUS) {
                    // Drop stray acknowledgement bytes so the reply starts the buffer.
                    rxBuffer.reset()
                    legacyStatusPending = true
                }
                transport?.write(byteArrayOf(frame[legacyPos]))
                legacyPos++
                if (legacyPos == frame.size) {
                    legacyQueue.removeFirst()
                    legacyPos = 0
                    delay(if (frame[4] == 0x01.toByte()) LEGACY_AFTER_FREQ_MS else LEGACY_GAP_MS)
                } else {
                    delay(LEGACY_BYTE_MS)
                }
            }
        }
    }

    /** Disconnecting: finish the frame on the wire, then send the rest (PTT off) at once. */
    private fun flushLegacyQueue() {
        legacyJob?.cancel()
        legacyJob = null
        if (legacyQueue.isEmpty()) return
        val out = ByteArrayOutputStream()
        val head = legacyQueue.removeFirst()
        out.write(head, legacyPos, head.size - legacyPos)
        legacyPos = 0
        while (legacyQueue.isNotEmpty()) out.write(legacyQueue.removeFirst())
        transport?.write(out.toByteArray())
    }

    private fun resetLegacyQueue() {
        legacyJob?.cancel()
        legacyJob = null
        legacyQueue.clear()
        legacyPos = 0
        legacyStatusPending = false
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

    /** Debug builds: raw CAT from adb (see FireflyApp.registerDebugCAT). */
    fun debugSend(s: String) { if (BuildConfig.DEBUG) send(s) }

    private fun send(s: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "CAT > $s")
        transport?.write(s.toByteArray(Charsets.UTF_8))
    }

    private fun sendCIV(body: ByteArray) {
        val frame = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), settings.icomAddress.toByte(), 0xE0.toByte()) +
            body + byteArrayOf(0xFD.toByte())
        transport?.write(frame)
    }

    companion object {
        private const val TAG = "JS8Rig"

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

        /** FT-817 family: opcode of the read frequency/mode command. */
        const val LEGACY_READ_STATUS: Byte = 0x03
        /** Pause between bytes and after each command (Hamlib uses up to 5 ms and 50–100 ms). */
        const val LEGACY_BYTE_MS = 5L
        const val LEGACY_GAP_MS = 50L
        const val LEGACY_AFTER_FREQ_MS = 100L

        /** BCD, most significant byte first (Yaesu 5-byte CAT). */
        fun bcdBigEndian(value: Long, bytes: Int): ByteArray {
            var v = value
            val out = ByteArray(bytes)
            for (i in bytes - 1 downTo 0) {
                val lo = (v % 10).toInt(); v /= 10
                val hi = (v % 10).toInt(); v /= 10
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        fun fromBCDBigEndian(b: List<Int>): Long {
            var v = 0L
            for (byte in b) v = v * 100 + (byte shr 4) * 10 + (byte and 0x0F)
            return v
        }

        fun fromBCD(b: List<Int>): Long {
            var v = 0L
            for (byte in b.reversed()) v = v * 100 + (byte shr 4) * 10 + (byte and 0x0F)
            return v
        }
    }

    private fun received(d: ByteArray) {
        if (BuildConfig.DEBUG) Log.d(TAG, "CAT < ${String(d, Charsets.ISO_8859_1).replace("\r", "\\r")}")
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
                        if (settings.catProtocol == CATProtocol.kenwood) {
                            when {
                                reply == "FR0" -> kenwoodVFO = 'A'
                                reply == "FR1" -> kenwoodVFO = 'B'
                                reply.startsWith("F$kenwoodVFO") -> reply.substring(2).toLongOrNull()?.let { gotFrequency(it) }
                            }
                        } else if (reply.startsWith("FA")) {
                            reply.substring(2).toLongOrNull()?.let { gotFrequency(it) }
                        }
                    }
                }
                CATProtocol.yaesuLegacy -> {
                    // Commands answer with one ack byte; only a status read returns 4 BCD
                    // frequency bytes (10 Hz steps, most significant first) and a mode byte.
                    if (legacyStatusPending && buf.size >= 5) {
                        legacyStatusPending = false
                        val digits = buf.copyOfRange(0, 4).map { it.toInt() and 0xFF }
                        if (digits.all { (it shr 4) <= 9 && (it and 0x0F) <= 9 }) {
                            gotFrequency(fromBCDBigEndian(digits) * 10)
                        }
                        buf = ByteArray(0)
                    } else if (!legacyStatusPending) {
                        buf = ByteArray(0)
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
