package com.js8firefly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.AppSettings
import com.js8firefly.android.GridMath
import com.js8firefly.android.Format
import com.js8firefly.android.audio.AudioDeviceChoice
import com.js8firefly.android.audio.AudioIO
import com.js8firefly.android.audio.PTTMethod
import com.js8firefly.android.radio.BLESerialTransport
import com.js8firefly.android.radio.BlePeripheral
import com.js8firefly.android.radio.CATProtocol
import com.js8firefly.android.radio.RigConnection
import com.js8firefly.android.radio.RigPreset
import com.js8firefly.android.radio.RigSettings
import com.js8firefly.android.radio.SerialTransport
import com.js8firefly.core.JS8Submode
import com.js8firefly.core.WatchColor
import com.js8firefly.core.WatchItem
import com.js8firefly.core.WatchMatcher
import java.util.Locale

@Composable
fun SettingsView(model: AppModel, modifier: Modifier = Modifier, title: Boolean = true) {
    LazyColumn(modifier.fillMaxWidth()) {
        if (title) {
            item("title") {
                Text("Settings", style = Theme.rounded(34, Bold), color = Theme.text, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            }
        }
        item("station") { StationSection(model) }
        item("watch") { WatchSection(model) }
        item("audio") { AudioSection(model) }
        item("rig") { RigSection(model) }
        item("js8") { JS8Section(model) }
        item("clock") { ClockSection(model) }
        item("about") { AboutSection() }
        item("end") { Spacer(Modifier.height(32.dp)) }
    }
}

private fun AppModel.edit(f: (AppSettings) -> AppSettings) = updateSettings(f)
private fun AppModel.editStation(f: (com.js8firefly.core.StationConfig) -> com.js8firefly.core.StationConfig) =
    updateSettings { it.copy(station = f(it.station)) }
private fun AppModel.editRig(f: (RigSettings) -> RigSettings) = updateSettings { it.copy(rig = f(it.rig)) }
private fun AppModel.editTx(f: (com.js8firefly.android.audio.TxAudioSettings) -> com.js8firefly.android.audio.TxAudioSettings) =
    updateSettings { it.copy(tx = f(it.tx)) }

@Composable
private fun StationSection(model: AppModel) {
    val s = model.settings
    var groupText by remember { mutableStateOf("") }
    FormSection("Station", Icons.Filled.AccountCircle) {
        LabeledField("Callsign", s.station.callsign, "N0CALL", { v -> model.editStation { it.copy(callsign = v.uppercase().trim()) } },
            style = Theme.mono(15, Bold), uppercase = true)
        LabeledField("Grid", s.station.grid, "EM73tu", { v -> model.editStation { it.copy(grid = v) } },
            style = Theme.mono(15), color = if (s.autoGrid) Theme.dim else Theme.text, enabled = !s.autoGrid)
        if (s.station.grid.isNotEmpty() && !GridMath.isValid(s.station.grid)) {
            FormText("Enter a 4 or 6 character Maidenhead locator", color = Theme.warn)
        }
        FormToggle("Update grid from GPS", s.autoGrid) { v -> model.edit { it.copy(gridFromLocation = if (v) true else null) } }
        if (s.autoGrid) {
            FormText("Your grid follows the device location. Turn this off to enter it manually.")
        } else {
            val locating = model.locator.isLocating
            FormRow(onClick = if (locating) null else ({ model.locator.locateOnce() })) {
                Icon(Icons.Filled.MyLocation, null, tint = Theme.accent, modifier = Modifier.size(18.dp))
                Text("Set grid from current location", style = Theme.rounded(16), color = Theme.accent, modifier = Modifier.weight(1f))
                if (locating) CircularProgressIndicator(Modifier.size(16.dp), color = Theme.dim, strokeWidth = 2.dp)
            }
        }
        model.locator.lastError?.let { FormText(it, color = Theme.warn) }
        LabeledField("Info", s.station.info, "Station info (sent on INFO?)", { v -> model.editStation { it.copy(info = v) } }, uppercase = true)
        LabeledField("Status", s.station.status, "Status (sent on STATUS?)", { v -> model.editStation { it.copy(status = v) } }, uppercase = true)
        LabeledField("CQ message", s.station.cqMessage, "CQ CQ CQ <MYGRID4>", { v -> model.editStation { it.copy(cqMessage = v) } }, uppercase = true)
        LabeledField("Reply message", s.station.replyMessage, "HW CPY?", { v -> model.editStation { it.copy(replyMessage = v) } }, uppercase = true)
        FormRow(divider = s.station.groups.isNotEmpty()) {
            Text("Groups", style = Theme.rounded(16), color = Theme.text)
            PlainField(groupText, { groupText = it }, "@GROUP", Modifier.weight(1f), align = TextAlign.End, uppercase = true)
            TextButton(onClick = {
                var g = groupText.uppercase().trim()
                if (!g.startsWith("@")) g = "@$g"
                if (g.length > 1 && g !in s.station.groups) model.editStation { it.copy(groups = it.groups + g) }
                groupText = ""
            }) { Text("Add", color = Theme.accent) }
        }
        s.station.groups.forEachIndexed { i, g ->
            FormRow(divider = i < s.station.groups.size - 1) {
                Text(g, style = Theme.mono(14), color = Theme.text, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.RemoveCircleOutline, "Remove", tint = Theme.tx,
                    modifier = Modifier.size(22.dp).clickable { model.editStation { st -> st.copy(groups = st.groups.filter { it != g }) } },
                )
            }
        }
    }
}

/** JS8Call's highlight words: stations, groups and words that stand out when heard. */
@Composable
private fun WatchSection(model: AppModel) {
    val s = model.settings
    var term by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(WatchColor.gold) }
    val list = s.watchList
    fun setList(l: List<WatchItem>) = model.edit { it.copy(watchItems = l.ifEmpty { null }) }
    fun add() {
        val t = WatchMatcher.normalize(term) ?: return
        val i = list.indexOfFirst { it.term == t }
        setList(if (i >= 0) list.mapIndexed { k, w -> if (k == i) w.copy(color = color) else w } else list + WatchItem(term = t, color = color))
        term = ""
    }
    FormSection(
        "Watch List", Icons.Outlined.Visibility,
        footer = "Traffic from these stations, to these groups or containing these words glows in its color in Band Activity, Chat and Stations. A word like ARES also matches @ARES. If more than one matches, the higher one in the list wins.",
    ) {
        FormRow {
            PlainField(term, { term = it.uppercase() }, "NR5P, @ARES or a word", Modifier.weight(1f), style = Theme.mono(15), uppercase = true, onSubmit = { add() })
            val ok = WatchMatcher.normalize(term) != null
            TextButton(onClick = { add() }, enabled = ok) { Text("Add", color = if (ok) Theme.accent else Theme.faint) }
        }
        FormRow {
            WatchColor.allCases.forEach { c ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .then(if (color == c) Modifier.border(2.dp, Theme.text, CircleShape) else Modifier)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(c.color)
                            .clickable { color = c },
                    )
                }
            }
        }
        list.forEachIndexed { idx, item ->
            var menu by remember { mutableStateOf(false) }
            FormRow {
                Box {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(item.color.color).clickable { menu = true })
                    FireflyMenu(menu, { menu = false }) {
                        MenuHeader("Color")
                        WatchColor.allCases.forEach { c ->
                            MenuItem(c.name, checked = c == item.color) {
                                menu = false
                                setList(list.map { if (it.id == item.id) it.copy(color = c) else it })
                            }
                        }
                    }
                }
                Text(item.term, style = Theme.mono(14, Bold), color = item.color.color, modifier = Modifier.weight(1f))
                if (idx > 0) {
                    Text("▲", style = Theme.rounded(12), color = Theme.faint, modifier = Modifier.clickable {
                        val m = list.toMutableList(); val x = m.removeAt(idx); m.add(idx - 1, x); setList(m)
                    }.padding(4.dp))
                }
                Text(item.kind, style = Theme.rounded(12), color = Theme.dim)
                Icon(Icons.Filled.RemoveCircleOutline, "Remove", tint = Theme.tx,
                    modifier = Modifier.size(22.dp).clickable { setList(list.filter { it.id != item.id }) })
            }
        }
        FormToggle("Notify when heard in the background", s.watchNotify) { v ->
            model.edit { it.copy(watchNotifications = if (v) null else false) }
        }
    }
}

@Composable
private fun AudioSection(model: AppModel) {
    val s = model.settings
    FormSection("Audio & PTT", Icons.Filled.GraphicEq) {
        FormRow {
            Text("Route", style = Theme.rounded(16), color = Theme.text)
            Text(model.routeDescription, style = Theme.rounded(12), color = Theme.dim, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        FormRow {
            Icon(if (model.usbConnected) Icons.Filled.Usb else Icons.Filled.UsbOff, null,
                tint = if (model.usbConnected) Theme.rx else Theme.warn, modifier = Modifier.size(18.dp))
            Text(
                if (model.usbConnected) "USB audio interface detected" else "No USB audio interface — using built-in audio",
                style = Theme.rounded(15), color = Theme.text,
            )
        }
        AudioDevicePicker("Input device", s.audioInputDevice, model.inputChoices) { v -> model.edit { it.copy(audioInputDevice = v) } }
        AudioDevicePicker("Output device", s.audioOutputDevice, model.outputChoices) { v -> model.edit { it.copy(audioOutputDevice = v) } }
        FormText("Automatic uses the USB audio interface when one is plugged in. Pick a device if the radio shows up as more than one.")
        FormPicker("Input channel", s.inputChannel, listOf(0 to "Left", 1 to "Right", -1 to "Mix")) { v -> model.edit { it.copy(inputChannel = v) } }
        FormPicker("PTT method", s.tx.pttMethod, PTTMethod.available.map { it to it.title }) { v -> model.editTx { it.copy(pttMethod = v) } }
        if (s.tx.pttMethod == PTTMethod.digirigTone) {
            FormText("Digirig Lite: JS8 audio goes to the left channel and a keying tone to the right channel, which drives the Digirig's PTT circuit.")
            DoubleStepper("PTT tone", s.tx.toneFrequency, 300.0..3000.0, 100.0, "${s.tx.toneFrequency.toInt()} Hz") { v -> model.editTx { it.copy(toneFrequency = v) } }
            LabeledSlider("Tone level", s.tx.toneLevel, 0.1f..1f) { v -> model.editTx { it.copy(toneLevel = v) } }
            FormToggle("Swap channels (tone on left)", s.tx.swapChannels) { v -> model.editTx { it.copy(swapChannels = v) } }
        }
        if (s.tx.pttMethod == PTTMethod.cat && s.rig.connection == RigConnection.none) {
            FormText("CAT PTT needs a rig connection below.", color = Theme.warn)
        }
        if (s.tx.pttMethod == PTTMethod.serialRTS || s.tx.pttMethod == PTTMethod.serialDTR) {
            FormText("Uses the serial port configured under Rig Control (USB serial CAT).")
        }
        DoubleStepper("PTT lead", s.tx.leadTime, 0.0..1.0, 0.05, "${(s.tx.leadTime * 1000).toInt()} ms") { v -> model.editTx { it.copy(leadTime = v) } }
        DoubleStepper("PTT tail", s.tx.tailTime, 0.0..1.0, 0.05, "${(s.tx.tailTime * 1000).toInt()} ms") { v -> model.editTx { it.copy(tailTime = v) } }
        LabeledSlider("TX audio level", s.tx.outputLevel, 0.01f..1f) { v -> model.editTx { it.copy(outputLevel = v) } }
        FormText("Set TX level so the radio's ALC barely moves. Also keep the phone's media volume fixed.")
        // Right = more contrast = a narrower dB range from floor to white.
        LabeledSlider("Waterfall contrast", -s.waterfallRangeDB, -72f..-16f) { v -> model.edit { it.copy(waterfallRange = -v) } }
        LabeledSlider("Waterfall brightness", s.waterfallBrightness, -20f..20f) { v -> model.edit { it.copy(waterfallBrightness = v) } }
        ActionRow("Restart audio", divider = false) { model.audio.restart() }
    }
}

/** Automatic, the connected devices, and the saved choice even while it's unplugged. */
@Composable
private fun AudioDevicePicker(label: String, selection: String, choices: List<AudioDeviceChoice>, onSelect: (String) -> Unit) {
    val options = buildList {
        add("" to "Automatic")
        choices.forEach { add(it.key to it.name) }
        if (selection.isNotEmpty() && choices.none { it.key == selection }) {
            add(selection to "${AudioIO.nameFromKey(selection)} (not connected)")
        }
    }
    FormPicker(label, selection, options, onSelect)
}

@Composable
private fun ActionRow(title: String, divider: Boolean, onClick: () -> Unit) {
    FormRow(onClick = onClick, divider = divider) { Text(title, style = Theme.rounded(16), color = Theme.accent) }
}

@Composable
private fun RigSection(model: AppModel) {
    val context = LocalContext.current
    val s = model.settings
    val rig = s.rig
    FormSection("Rig Control", Icons.Filled.SettingsInputComponent) {
        FormPicker("Connection", rig.connection, RigConnection.available.map { it to it.title }) { v -> model.editRig { it.copy(connection = v) } }
        if (rig.connection != RigConnection.none) {
            FormRow {
                Box(Modifier.size(8.dp).background(rigColor(model), CircleShape))
                Text(model.rig.status.label, style = Theme.rounded(15), color = Theme.text, modifier = Modifier.weight(1f))
                model.rig.frequency?.let { Text(Format.mhzLong(it), style = Theme.mono(13), color = Theme.accent) }
                TextButton(onClick = { model.rig.disconnect(); model.rig.connect() }) { Text("Reconnect", color = Theme.accent) }
            }
        }
        if (rig.connection.usesCATProtocol) {
            FormPicker("Radio", rig.preset, RigPreset.allCases.map { it to it.title }) { p -> model.editRig { it.apply(p) } }
            if (rig.preset == RigPreset.truSDX) {
                FormText("The (tr)uSDX USB port is serial only, not a sound card like the QMX. Connect its audio jacks through an interface such as a Digirig Lite with Digirig's uSDX cable. Not fully tested yet: check keying into a dummy load first.")
            }
            FormPicker("CAT protocol", rig.catProtocol, CATProtocol.entries.map { it to it.title }) { v -> model.editRig { it.copy(catProtocol = v) } }
            if (rig.catProtocol == CATProtocol.yaesu) {
                FormPicker("Frequency digits", rig.yaesuDigits, listOf(8 to "8 (FTDX3000/5000, FT-950, FT-450)", 9 to "9 (FT-991, FTDX101/10, FT-710, FT-891)")) { v ->
                    model.editRig { it.copy(yaesuDigits = v) }
                }
            }
            if (rig.catProtocol == CATProtocol.icom) {
                var addr by remember(rig.icomAddress) { mutableStateOf(String.format(Locale.US, "%02X", rig.icomAddress)) }
                LabeledField("CI-V address", addr, "94", { v ->
                    addr = v.uppercase()
                    v.toIntOrNull(16)?.takeIf { it in 0..255 }?.let { a -> model.editRig { it.copy(icomAddress = a) } }
                }, style = Theme.mono(14), uppercase = true)
            }
            FormToggle("Set USB/DATA mode on connect", rig.setDataModeOnConnect) { v -> model.editRig { it.copy(setDataModeOnConnect = v) } }
        }
        when (rig.connection) {
            RigConnection.serial -> {
                var ports by remember { mutableStateOf(SerialTransport.availablePorts(context)) }
                FormPicker("Serial port", rig.serialPath, listOf("" to "Choose…") + ports.map { it.id to it.name }) { v -> model.editRig { it.copy(serialPath = v) } }
                ActionRow("Refresh ports", divider = true) { ports = SerialTransport.availablePorts(context) }
                FormPicker("Baud rate", rig.baud, listOf(4800, 9600, 19200, 38400, 57600, 115200).map { it to "$it" }) { v -> model.editRig { it.copy(baud = v) } }
                FormPicker("Stop bits", rig.stopBits, listOf(1 to "1", 2 to "2")) { v -> model.editRig { it.copy(stopBits = v) } }
                FormText("Connect the radio's USB port with a USB-C OTG adapter. Match the radio's CAT RATE menu. QMX/QDX: the QMX serial port.")
            }
            RigConnection.bluetooth -> BluetoothRows(model)
            RigConnection.tcpSerial, RigConnection.rigctld, RigConnection.flrig -> {
                LabeledField("Host", rig.host, "192.168.1.10", { v -> model.editRig { it.copy(host = v.trim()) } }, style = Theme.mono(14), keyboardType = KeyboardType.Uri)
                var portText by remember(rig.port) { mutableStateOf(rig.port.toString()) }
                LabeledField("Port", portText, "4532", { v ->
                    portText = v.filter { it.isDigit() }
                    portText.toIntOrNull()?.takeIf { it in 0..65535 }?.let { p -> model.editRig { it.copy(port = p) } }
                }, style = Theme.mono(14), keyboardType = KeyboardType.Number)
                FormText(
                    when (rig.connection) {
                        RigConnection.rigctld -> "Run `rigctld -m <model> -r <port>` on a computer or Raspberry Pi connected to the radio (default port 4532)."
                        RigConnection.flrig -> "flrig XML-RPC server (default port 12345)."
                        else -> "Raw CAT bytes over TCP (ser2net, WiFi serial bridges). Choose the radio's protocol above."
                    },
                )
            }
            RigConnection.none -> FormText("Without rig control, pick the band in the header and set the radio's dial to match (USB or DATA-U).")
        }
    }
}

@Composable
private fun BluetoothRows(model: AppModel) {
    val context = LocalContext.current
    var scanner by remember { mutableStateOf<BLESerialTransport?>(null) }
    val devices = remember { mutableStateListOf<BlePeripheral>() }
    DisposableEffect(Unit) { onDispose { scanner?.stopScan() } }
    val rig = model.settings.rig
    ActionRow(if (scanner == null) "Scan for Bluetooth adapters" else "Stop scan", divider = true) {
        val s = scanner
        if (s != null) {
            s.stopScan(); scanner = null
        } else {
            val t = BLESerialTransport(context, null)
            t.onDiscover = { p, _ -> if (devices.none { it.identifier == p.identifier }) devices.add(p) }
            t.scan()
            scanner = t
        }
    }
    devices.forEach { d ->
        FormRow(onClick = {
            model.editRig { it.copy(bluetoothID = d.identifier) }
            scanner?.stopScan(); scanner = null
        }) {
            Text(d.displayName, style = Theme.rounded(16), color = Theme.text, modifier = Modifier.weight(1f))
            if (rig.bluetoothID == d.identifier) Icon(Icons.Filled.Check, null, tint = Theme.accent)
        }
    }
    FormText("Works with BLE serial modules (Nordic UART or HM-10 style) wired to the radio's CAT port. Set the module's baud rate to the radio's CAT rate.")
}

@Composable
private fun JS8Section(model: AppModel) {
    val s = model.settings
    val st = s.station
    FormSection("JS8", Icons.Filled.CellTower) {
        FormPicker("Speed", s.submode, JS8Submode.allCases.sortedByDescending { it.period }.map { it to "${it.displayName} (${it.period}s)" }) { v ->
            model.edit { it.copy(submode = v) }
        }
        FormToggle("Decode all speeds", s.decodeAllSpeeds) { v -> model.edit { it.copy(decodeAllSpeeds = v) } }
        FormToggle("Auto-reply to queries", st.autoReply) { v -> model.editStation { it.copy(autoReply = v) } }
        FormToggle("Confirm auto-replies", st.autoReplyConfirmation) { v -> model.editStation { it.copy(autoReplyConfirmation = v) } }
        FormToggle("Heartbeat networking", s.heartbeatNetworking) { v -> model.edit { it.copy(heartbeatNetworking = v) } }
        FormToggle("Acknowledge heartbeats", st.heartbeatAcknowledgements) { v -> model.editStation { it.copy(heartbeatAcknowledgements = v) } }
        FormToggle("Pause HB while a station is selected", st.heartbeatQSOPause) { v -> model.editStation { it.copy(heartbeatQSOPause = v) } }
        FormToggle("HB anywhere (not just 500–1000 Hz)", st.heartbeatAnywhere) { v -> model.editStation { it.copy(heartbeatAnywhere = v) } }
        IntStepper("Heartbeat every", st.heartbeatIntervalMinutes, 0..60, 5,
            if (st.heartbeatIntervalMinutes == 0) "once" else "${st.heartbeatIntervalMinutes} min") { v -> model.editStation { it.copy(heartbeatIntervalMinutes = v) } }
        IntStepper("Repeat CQ every", st.cqIntervalMinutes, 0..60, 1,
            if (st.cqIntervalMinutes == 0) "once" else "${st.cqIntervalMinutes} min") { v -> model.editStation { it.copy(cqIntervalMinutes = v) } }
        FormToggle("Never reply to @ALLCALL", st.avoidAllcall) { v -> model.editStation { it.copy(avoidAllcall = v) } }
        FormToggle("Disable relaying", st.relayOff) { v -> model.editStation { it.copy(relayOff = v) } }
        FormToggle("Show heartbeats in band activity", st.showHeartbeatsInBand) { v -> model.editStation { it.copy(showHeartbeatsInBand = v) } }
        IntStepper("Band activity aging", st.activityAgingMinutes, 0..60, 1,
            if (st.activityAgingMinutes == 0) "off" else "${st.activityAgingMinutes} min") { v -> model.editStation { it.copy(activityAgingMinutes = v) } }
        DoubleStepper("TX delay", s.txDelay, 0.0..1.0, 0.05, "${(s.txDelay * 1000).toInt()} ms") { v -> model.edit { it.copy(txDelay = v) } }
        FormToggle("Notifications", s.notificationsEnabled) { v -> model.edit { it.copy(notificationsEnabled = v) } }
    }
}

@Composable
private fun ClockSection(model: AppModel) {
    val s = model.settings
    FormSection("Time", Icons.Filled.AccessTime) {
        val now = rememberNow(500)
        LabeledValue("UTC (corrected)", AppTime.utcTime(now))
        LabeledValue("Median decode Δt", String.format(Locale.US, "%+.2f s", model.averageDT.toDouble()))
        LabeledValue("Last decode", model.lastDecodeInfo.ifEmpty { "–" })
        LabeledValue("Frames decoded", "${model.decodeCount}")
        DoubleStepper("Clock offset", s.clockOffset, -30.0..30.0, 0.1, String.format(Locale.US, "%+.1f s", s.clockOffset)) { v ->
            model.edit { it.copy(clockOffset = v) }
        }
        val canSync = model.averageDT != 0f
        FormRow(onClick = if (canSync) ({ model.syncClockToDecodes() }) else null) {
            Text("Sync clock to received signals", style = Theme.rounded(16), color = if (canSync) Theme.accent else Theme.faint)
        }
        FormText("JS8 needs your clock within about ±1 second of UTC. The phone's network time is usually fine; use sync if decodes show large Δt.")
    }
}

@Composable
private fun AboutSection() {
    FormSection("About", Icons.Outlined.Info) {
        FormText("JS8 Firefly 1.0. JS8 protocol, modem and message handling ported from JS8Call / JS8Call-improved (GPLv3). Compatible with JS8Call stations on all speeds: Slow, Normal, Fast, Turbo and Ultra.")
    }
}

