package com.js8firefly.android.radio

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.Process
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.js8firefly.android.FireflyApp
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** A byte pipe to a radio's CAT port. Callbacks arrive on the main thread. */
interface ByteTransport {
    var onData: ((ByteArray) -> Unit)?
    var onState: ((TransportState) -> Unit)?
    fun open()
    fun close()
    fun write(data: ByteArray)
    /** Optional modem control lines (serial only). */
    fun setRTS(on: Boolean) {}
    fun setDTR(on: Boolean) {}
}

sealed class TransportState {
    data object idle : TransportState()
    data object connecting : TransportState()
    data object connected : TransportState()
    data class failed(val message: String) : TransportState()
}

private val mainHandler = Handler(Looper.getMainLooper())
private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

// MARK: - TCP (raw serial bridge, rigctld, ...)

class TCPTransport(private val host: String, private val port: Int) : ByteTransport {
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "js8.rig.tcp.write") }

    override fun open() {
        if (port !in 1..65535) {
            onState?.invoke(TransportState.failed("Invalid port"))
            return
        }
        onState?.invoke(TransportState.connecting)
        Thread({
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                if (!closed) onMain { onState?.invoke(TransportState.failed(e.message ?: "Connection failed")) }
                return@Thread
            }
            if (closed) { try { s.close() } catch (_: Exception) {}; return@Thread }
            socket = s
            out = s.getOutputStream()
            onMain { onState?.invoke(TransportState.connected) }
            receive(s.getInputStream())
        }, "js8.rig.tcp").start()
    }

    private fun receive(input: InputStream) {
        val buf = ByteArray(4096)
        var err: String? = null
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val d = buf.copyOf(n)
                    onMain { onData?.invoke(d) }
                }
            }
        } catch (e: Exception) {
            err = e.message
        }
        if (!closed) onMain { onState?.invoke(TransportState.failed(err ?: "Connection closed")) }
    }

    override fun close() {
        closed = true
        val s = socket
        socket = null
        out = null
        writer.execute { try { s?.close() } catch (_: Exception) {} }
        writer.shutdown()
    }

    override fun write(data: ByteArray) {
        val o = out ?: return
        if (writer.isShutdown) return
        writer.execute { try { o.write(data); o.flush() } catch (_: Exception) {} }
    }
}

// MARK: - Bluetooth LE serial (Nordic UART / HM-10 style modules)

/** A Bluetooth adapter seen while scanning. `identifier` is its Bluetooth address. */
data class BlePeripheral(val identifier: String, val name: String?) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: (identifier.take(8) + "…")
}

@SuppressLint("MissingPermission")
class BLESerialTransport(context: Context, private val targetID: String?) : ByteTransport {
    companion object {
        val nusService: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val nusRX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write
        val nusTX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify
        val hmService: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val hmChar: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val context = context.applicationContext
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null
    var onDiscover: ((BlePeripheral, Int) -> Unit)? = null

    private val adapter: BluetoothAdapter? =
        (this.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanOnly = false
    private var scanning = false
    private var closed = false
    private var mtu = 20
    /** Android allows one GATT operation at a time; queue them. */
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Asks for Bluetooth permission if needed, then runs [then]; fails the state when refused. */
    private fun withPermission(then: () -> Unit) {
        if (hasPermissions()) return then()
        val req = FireflyApp.permissionRequester
        if (req == null) {
            onState?.invoke(TransportState.failed("Bluetooth permission denied"))
            return
        }
        req(requiredPermissions()) { granted ->
            if (closed) return@req
            if (granted && hasPermissions()) then() else onState?.invoke(TransportState.failed("Bluetooth permission denied"))
        }
    }

    /** Scan for adapters (for the settings picker). */
    fun scan() {
        scanOnly = true
        withPermission { startScan() }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    override fun open() {
        scanOnly = false
        onState?.invoke(TransportState.connecting)
        withPermission { startScan() }
    }

    private fun startScan() {
        val a = adapter
        if (a == null) { onState?.invoke(TransportState.failed("Bluetooth is not available")); return }
        if (!a.isEnabled) { onState?.invoke(TransportState.failed("Bluetooth is off")); return }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) { onState?.invoke(TransportState.failed("Bluetooth is off")); return }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(nusService)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(hmService)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            onState?.invoke(TransportState.failed("Bluetooth permission denied"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onMain {
                if (closed) return@onMain
                val d = result.device
                val name = try { result.scanRecord?.deviceName ?: d.name } catch (_: SecurityException) { null }
                onDiscover?.invoke(BlePeripheral(d.address, name), result.rssi)
                if (!scanOnly && gatt == null && (targetID == null || d.address.equals(targetID, ignoreCase = true))) {
                    stopScan()
                    connect(d)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onMain { scanning = false; onState?.invoke(TransportState.failed("Bluetooth scan failed ($errorCode)")) }
        }
    }

    private fun connect(d: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= 23) d.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
               else d.connectGatt(context, false, gattCallback)
    }

    override fun close() {
        closed = true
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        ops.clear()
        opBusy = false
        onState?.invoke(TransportState.idle)
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val chunkSize = maxOf(20, mtu)
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(data.size, offset + chunkSize))
            enqueue { writeCharacteristic(g, c, chunk, type) }
            offset += chunkSize
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        while (!opBusy) {
            val op = ops.removeFirstOrNull() ?: return
            opBusy = try { op() } catch (_: Exception) { false }
        }
    }

    private fun opDone() = onMain { opBusy = false; pump() }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            onMain {
                if (closed || g != gatt) return@onMain
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    writeChar = null
                    if (status != BluetoothGatt.GATT_SUCCESS && !hasEverConnected) {
                        onState?.invoke(TransportState.failed("Bluetooth connection failed"))
                    } else {
                        onState?.invoke(TransportState.failed("Bluetooth adapter disconnected"))
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            onMain {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu - 3
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            onMain {
                if (closed || g != gatt) return@onMain
                for (s in g.services ?: emptyList()) {
                    for (c in s.characteristics ?: emptyList()) {
                        if (c.uuid == nusRX || c.uuid == hmChar) writeChar = c
                        if (c.uuid == nusTX || c.uuid == hmChar) {
                            g.setCharacteristicNotification(c, true)
                            c.getDescriptor(cccd)?.let { d ->
                                enqueue { writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                            }
                        }
                    }
                }
                if (writeChar != null) {
                    hasEverConnected = true
                    onState?.invoke(TransportState.connected)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = opDone()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = opDone()

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: return
            onMain { if (!closed) onData?.invoke(v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            onMain { if (!closed) onData?.invoke(v) }
        }
    }
    private var hasEverConnected = false
}

// MARK: - USB serial (usb-serial-for-android)

/** An attached USB serial port. `id` is what RigSettings.serialPath stores. */
data class UsbSerialPortInfo(val id: String, val name: String)

class SerialTransport(
    context: Context,
    private val path: String,
    private val baud: Int,
    private val stopBits: Int,
) : ByteTransport {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.js8firefly.android.USB_PERMISSION"

        private fun drivers(manager: UsbManager): List<UsbSerialDriver> {
            val found = UsbSerialProber.getDefaultProber().findAllDrivers(manager).toMutableList()
            // CDC-ACM radios (QMX/QDX) whose IDs aren't in the default table.
            for (d in manager.deviceList.values) {
                if (found.none { it.device.deviceId == d.deviceId } && CdcAcmSerialDriver.probe(d)) {
                    found.add(CdcAcmSerialDriver(d))
                }
            }
            return found
        }

        private fun key(d: UsbDevice, port: Int) = "%04X:%04X:%d".format(d.vendorId, d.productId, port)

        /** Attached USB serial ports, for the settings picker. */
        fun availablePorts(context: Context): List<UsbSerialPortInfo> {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return drivers(manager).flatMap { drv ->
                val d = drv.device
                val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { d.deviceName }
                drv.ports.mapIndexed { i, _ ->
                    UsbSerialPortInfo(key(d, i), if (drv.ports.size > 1) "$name (port ${i + 1})" else name)
                }
            }.sortedBy { it.name }
        }
    }

    private val context = context.applicationContext
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null
    private val manager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var receiver: BroadcastReceiver? = null
    private var closed = false
    private val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "js8.rig.usb") }

    override fun open() {
        val match = drivers(manager).flatMap { drv -> drv.ports.indices.map { drv to it } }
            .firstOrNull { (drv, i) -> key(drv.device, i) == path }
        if (match == null) {
            onState?.invoke(TransportState.failed("USB serial adapter not connected"))
            return
        }
        val (drv, index) = match
        if (manager.hasPermission(drv.device)) {
            openPort(drv, index)
            return
        }
        onState?.invoke(TransportState.connecting)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                unregister()
                if (closed) return
                if (manager.hasPermission(drv.device)) openPort(drv, index)
                else onState?.invoke(TransportState.failed("USB permission denied"))
            }
        }
        receiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        manager.requestPermission(drv.device, pi)
    }

    private fun unregister() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    private fun openPort(drv: UsbSerialDriver, index: Int) {
        val connection = manager.openDevice(drv.device)
        if (connection == null) {
            onState?.invoke(TransportState.failed("Cannot open USB serial adapter"))
            return
        }
        val p = drv.ports[index]
        try {
            p.open(connection)
            p.setParameters(baud, UsbSerialPort.DATABITS_8,
                if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Start with RTS and DTR low so interfaces that key on them stay in receive.
            try { p.setRTS(false) } catch (_: Exception) {}
            try { p.setDTR(false) } catch (_: Exception) {}
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            onState?.invoke(TransportState.failed("Cannot open serial port: ${e.message ?: "error"}"))
            return
        }
        port = p
        val m = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val d = data.copyOf()
                onMain { if (!closed) onData?.invoke(d) }
            }

            override fun onRunError(e: Exception) {
                onMain { if (!closed) onState?.invoke(TransportState.failed("USB serial adapter disconnected")) }
            }
        })
        m.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        io = m
        m.start()
        onState?.invoke(TransportState.connected)
    }

    override fun close() {
        closed = true
        unregister()
        val m = io
        val p = port
        io = null
        port = null
        ioExec.execute {
            try { m?.stop() } catch (_: Exception) {}
            try { p?.close() } catch (_: Exception) {}
        }
        ioExec.shutdown()
        onState?.invoke(TransportState.idle)
    }

    override fun write(data: ByteArray) {
        val p = port ?: return
        if (ioExec.isShutdown) return
        ioExec.execute { try { p.write(data, 500) } catch (_: Exception) {} }
    }

    private fun modem(rts: Boolean, on: Boolean) {
        val p = port ?: return
        if (ioExec.isShutdown) return
        ioExec.execute { try { if (rts) p.setRTS(on) else p.setDTR(on) } catch (_: Exception) {} }
    }

    override fun setRTS(on: Boolean) = modem(true, on)
    override fun setDTR(on: Boolean) = modem(false, on)
}
