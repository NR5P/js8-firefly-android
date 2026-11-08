package com.js8firefly.android.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.Format
import com.js8firefly.core.ConversationLine
import com.js8firefly.core.InboxMessage
import com.js8firefly.core.Varicode
import com.js8firefly.core.WatchItem
import java.util.Locale
import kotlin.math.abs

/** A tap anywhere in this view also puts the keyboard away, without taking the tap from what's underneath. */
fun Modifier.dismissesKeyboardOnTap(): Modifier = composed {
    val fm = LocalFocusManager.current
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial)
            if (up != null) fm.clearFocus()
        }
    }
}

/** A small persisted UI preference (SwiftUI @AppStorage). */
@Composable
fun rememberBoolPref(key: String, default: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getBoolean(key, default)) }
    return remember {
        object : MutableState<Boolean> by state {
            override var value: Boolean
                get() = state.value
                set(v) { state.value = v; prefs.edit().putBoolean(key, v).apply() }
        }
    }
}

@Composable
fun rememberStringPref(key: String, default: String): MutableState<String> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getString(key, default) ?: default) }
    return remember {
        object : MutableState<String> by state {
            override var value: String
                get() = state.value
                set(v) { state.value = v; prefs.edit().putString(key, v).apply() }
        }
    }
}

/** The RX/TX conversation pane, styled as a chat. */
@Composable
fun ConversationView(model: AppModel, filterCallsign: String?, modifier: Modifier = Modifier) {
    val settings = model.settings
    val lines = model.conversation.filter { filterCallsign.isNullOrEmpty() || it.text.contains(filterCallsign) }
    val state = rememberLazyListState()
    LaunchedEffect(model.conversation.lastOrNull()?.text, lines.size) {
        if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lines.isEmpty()) {
            item("empty") {
                EmptyStateView(Icons.Outlined.Forum, "No traffic yet", "Messages on your offset and anything directed to you show up here.")
            }
        }
        items(lines, key = { it.id }) { line ->
            MessageBubble(line, settings.station.eot, settings.station.callsign, watch = settings.watch(line.text))
        }
    }
}

/** Everything decoded across the passband, one bubble per transmission. Tap a bubble to move there and select the sender. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BandConversationView(model: AppModel, filterCallsign: String?, showHeartbeats: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = model.settings
    val lines = model.bandConversation.filter { line ->
        if (!showHeartbeats && (line.text.contains("@HB") || line.text.contains(" HEARTBEAT"))) false
        else filterCallsign.isNullOrEmpty() || line.text.contains(filterCallsign)
    }
    val state = rememberLazyListState()
    LaunchedEffect(model.bandConversation.lastOrNull()?.text, lines.size) {
        if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1)
    }
    fun join(line: ConversationLine) {
        model.join(line)
        val who = AppModel.sender(line)?.let { " with $it selected" } ?: ""
        model.banner = "Listening on ${line.offset} Hz$who"
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lines.isEmpty()) {
            item("empty") {
                EmptyStateView(
                    Icons.Filled.Sensors, "Nothing heard yet",
                    "Every conversation decoded anywhere in the passband shows up here. Tap one to join it.",
                )
            }
        }
        items(lines, key = { it.id }) { line ->
            val onMyOffset = abs(line.offset - settings.offset) <= (line.submode ?: settings.submode).rxThreshold
            var menu by remember { mutableStateOf(false) }
            Box {
                MessageBubble(
                    line, settings.station.eot, settings.station.callsign,
                    showSignal = true, onMyOffset = onMyOffset, watch = settings.watch(line.text),
                    modifier = Modifier.combinedClickable(
                        onClick = { if (!line.isTx) join(line) },
                        onLongClick = { menu = true },
                    ),
                )
                FireflyMenu(menu, { menu = false }) {
                    if (!line.isTx) {
                        MenuItem("Listen on ${line.offset} Hz") { menu = false; model.setOffset(line.offset) }
                        AppModel.sender(line)?.let { call ->
                            MenuItem("Join $call on ${line.offset} Hz") { menu = false; join(line) }
                        }
                    }
                    MenuItem("Copy text") { menu = false; copyToClipboard(context, line.text) }
                }
            }
        }
    }
}

/** Conversation header with the All band / My offset switch, used on every layout. */
@Composable
fun ChatPane(model: AppModel, modifier: Modifier = Modifier) {
    var scope by rememberStringPref("chatScope", "All band")
    var showHeartbeats by rememberBoolPref("chatShowHeartbeats", false)
    var onlySelected by remember { mutableStateOf(false) }
    val band = scope != "My offset"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedPicker(listOf("All band", "My offset"), if (band) "All band" else "My offset", Modifier.widthIn(max = 240.dp).weight(1f, fill = false)) { scope = it }
            Spacer(Modifier.weight(1f))
            if (band) {
                ToggleIconButton(showHeartbeats, if (showHeartbeats) Icons.Filled.Favorite else Icons.Filled.HeartBroken, "Show heartbeats") {
                    showHeartbeats = !showHeartbeats
                }
            }
            if (model.selectedCallsign.isNotEmpty()) {
                val on = onlySelected
                Text(
                    "Only ${model.selectedCallsign}",
                    style = Theme.rounded(12, SemiBold),
                    color = if (on) Theme.bg else Theme.accent,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Theme.accent else Theme.accent.copy(alpha = 0.14f))
                        .clickable { onlySelected = !onlySelected }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
            Icon(
                Icons.Outlined.Delete, "Clear",
                tint = Theme.faint,
                modifier = Modifier.size(24.dp).clickable { if (band) model.clearBandConversation() else model.clearConversation() },
            )
        }
        val filter = if (onlySelected) model.selectedCallsign else null
        if (band) BandConversationView(model, filter, showHeartbeats, Modifier.weight(1f))
        else ConversationView(model, filter, Modifier.weight(1f))
    }
}

@Composable
fun SegmentedPicker(options: List<String>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = Theme.rounded(14, if (on) SemiBold else androidx.compose.ui.text.font.FontWeight.Normal), color = Theme.text, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToggleIconButton(on: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Theme.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(5.dp),
    ) { Icon(icon, label, tint = if (on) Theme.bg else Theme.accent, modifier = Modifier.size(18.dp)) }
}

@Composable
fun MessageBubble(
    line: ConversationLine,
    eot: String,
    mycall: String,
    showSignal: Boolean = false,
    onMyOffset: Boolean = false,
    watch: WatchItem? = null,
    modifier: Modifier = Modifier,
) {
    val toMe = !line.isTx && mycall.isNotEmpty() && line.text.contains(" $mycall")
    val w = if (line.isTx) null else watch
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        if (line.isTx) Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
        Column(
            Modifier.weight(1f, fill = false),
            horizontalAlignment = if (line.isTx) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val meta = Theme.mono(9)
                Text(AppTime.utcTime(line.date), style = meta, color = Theme.faint)
                Text("${grouped(line.offset)} Hz", style = meta, color = if (onMyOffset) Theme.warn else Theme.faint)
                val snr = line.snr
                if (showSignal && !line.isTx && snr != null) Text(Format.snr(snr) + " dB", style = meta, color = Theme.snrColor(snr))
                val m = line.submode
                if (showSignal && m != null) Text(m.displayName, style = meta, color = Theme.modeColor(m))
                if (line.isTx) Icon(Icons.Filled.CellTower, null, tint = Theme.faint, modifier = Modifier.size(10.dp))
                if (showSignal && onMyOffset && !line.isTx) Icon(Icons.Filled.Hearing, null, tint = Theme.warn, modifier = Modifier.size(10.dp))
                if (w != null) WatchTag(w)
            }
            val body = line.text.replace(" $eot ", " $eot").trim()
            Box(
                modifier
                    .then(if (w != null) Modifier.watchGlow(w, AppTime.millisOf(line.date), 16.dp, fill = !toMe) else Modifier)
                    .clip(shape)
                    .then(
                        if (line.isTx) Modifier.background(Brush.linearGradient(listOf(Theme.accent, Theme.accent2)))
                        else Modifier.background(if (toMe) Theme.tx.copy(alpha = 0.22f) else Theme.cardHi),
                    )
                    .then(if (toMe) Modifier.border(1.dp, Theme.tx.copy(alpha = 0.7f), shape) else Modifier)
                    .alpha(if (line.isComplete || line.isTx) 1f else 0.9f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(body, style = Theme.mono(14), color = if (line.isTx) Theme.bg else Theme.text)
            }
        }
        if (!line.isTx) Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
    }
}

/** Directed command shortcuts, as in JS8Call's query menu. */
@Composable
fun QueryCommandMenu(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    val primary = listOf("SNR?", "GRID?", "HW CPY?", "AGN?", "QSL?", "73")
    val questions = listOf("INFO?", "STATUS?", "HEARING?", "QUERY MSGS")
    val replies = listOf("ACK", "NACK", "RR", "QSL", "FB", "YES", "NO", "SK")
    val target = model.selectedCallsign
    val noTarget = target.isEmpty()
    val station = model.settings.station
    fun close() { open = false; sub = null }
    Box {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Theme.accent).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.KeyboardCommandKey, "Commands", tint = Theme.bg, modifier = Modifier.size(20.dp)) }
        FireflyMenu(open, { close() }) {
            when (sub) {
                null -> {
                    val report = model.calls.firstOrNull { it.call == target }?.let { Varicode.formatSNR(it.snr) } ?: ""
                    MenuHeader(if (noTarget) "Pick a station first" else "Send to $target")
                    MenuItem(if (report.isEmpty()) "Send SNR (not heard)" else "Send SNR $report", enabled = !noTarget && report.isNotEmpty()) {
                        close(); model.send("SNR $report")
                    }
                    primary.forEach { c -> MenuItem(c, enabled = !noTarget) { close(); model.send(c) } }
                    MenuItem("More questions  ›") { sub = "questions" }
                    MenuItem("Replies  ›") { sub = "replies" }
                    MenuItem("Insert into message  ›") { sub = "insert" }
                    MenuItem("Talk to a group  ›") { sub = "groups" }
                }
                "questions" -> {
                    MenuItem("‹  Back") { sub = null }
                    questions.forEach { c -> MenuItem(c, enabled = !noTarget) { close(); model.send(c) } }
                }
                "replies" -> {
                    MenuItem("‹  Back") { sub = null }
                    replies.forEach { c -> MenuItem(c, enabled = !noTarget) { close(); model.send(c) } }
                }
                "insert" -> {
                    MenuItem("‹  Back") { sub = null }
                    MenuItem("MSG (store in their inbox)") { close(); model.draft = "MSG " }
                    MenuItem("MSG TO: (relay via station)") { close(); model.draft = "MSG TO:" }
                    MenuItem("QUERY MSG <id>") { close(); model.draft = "QUERY MSG " }
                    MenuItem("QUERY CALL <call>") { close(); model.draft = "QUERY CALL " }
                    MenuItem("Relay  CALL>…") { close(); model.draft = ">" }
                    MenuItem("GRID ${station.grid.take(4)}") { close(); model.draft = "GRID ${station.grid}" }
                    MenuItem("Reply: ${station.replyMessage}") { close(); model.draft = station.replyMessage }
                }
                "groups" -> {
                    MenuItem("‹  Back") { sub = null }
                    (listOf("@ALLCALL", "@HB") + station.groups).forEach { g ->
                        MenuItem(g) { close(); model.selectedCallsign = g }
                    }
                }
            }
        }
    }
}

val ComposeBarBg = Color(0xF01B1F2B)

/** Message composer with selected-callsign pill, live frame preview and controls. */
@Composable
fun ComposeBar(model: AppModel, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val text = model.draft
    val settings = model.settings
    fun send() {
        val t = model.draft.trim()
        if (t.isEmpty()) return
        model.draft = ""
        if (!model.send(t)) model.draft = t
    }
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ComposeBarBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        model.pendingReplies.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Theme.warn.copy(alpha = 0.12f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.QuestionAnswer, null, tint = Theme.warn, modifier = Modifier.size(18.dp))
                Text("Auto-reply: ${r.message.message}", style = Theme.mono(12), color = Theme.text, maxLines = 2, modifier = Modifier.weight(1f))
                PillButton("Send", Theme.rx, filled = true) { model.approve(r) }
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Theme.accent.copy(alpha = 0.14f)).clickable { model.reject(r) }.padding(6.dp),
                ) { Icon(Icons.Filled.Close, "Reject", tint = Theme.accent, modifier = Modifier.size(16.dp)) }
            }
        }
        if (model.isTransmitting || model.queuedFrames > 0) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Theme.tx.copy(alpha = 0.12f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Sensors, null, tint = Theme.tx, modifier = Modifier.size(18.dp))
                Text(
                    if (model.isTuning) "TUNING" else model.txText.ifEmpty { "Waiting for next period…" },
                    style = Theme.mono(12, SemiBold), color = Theme.tx, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (model.queuedFrames > 0) Chip("+${model.queuedFrames} frames", Theme.tx)
                Spacer(Modifier.weight(1f))
                PillButton("Halt", Theme.tx, filled = true, icon = Icons.Filled.Stop) { model.halt() }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryCommandMenu(model)
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Theme.cardHi)
                    .border(1.dp, if (focused) Theme.accent.copy(alpha = 0.6f) else Theme.stroke, RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (model.selectedCallsign.isNotEmpty()) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Theme.accent).clickable { model.selectedCallsign = "" }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(model.selectedCallsign, style = Theme.mono(12, Bold), color = Theme.bg)
                        Icon(Icons.Filled.Cancel, "Clear", tint = Theme.bg, modifier = Modifier.size(12.dp))
                    }
                }
                val placeholder = if (model.selectedCallsign.isEmpty()) "Message (CQ, free text…)" else "Message to ${model.selectedCallsign}"
                BasicTextField(
                    value = text,
                    onValueChange = { model.draft = it.uppercase(Locale.ROOT) },
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                    textStyle = Theme.mono(15).copy(color = Theme.text),
                    maxLines = 4,
                    cursorBrush = SolidColor(Theme.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(placeholder, style = Theme.mono(15), color = Theme.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
            }
            val canSend = text.isNotEmpty() && settings.txEnabled
            Icon(
                Icons.Filled.ArrowCircleUp, "Send",
                tint = if (canSend) Theme.accent else Theme.faint,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = canSend) { send() },
            )
            if (focused) {
                Text(
                    "Done", style = Theme.rounded(14, SemiBold), color = Theme.accent,
                    modifier = Modifier.clickable { focusManager.clearFocus() },
                )
            }
        }

        if (text.isNotEmpty()) {
            val frames = remember(text, settings.submode) { model.preview(text) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Layers, null, tint = Theme.dim, modifier = Modifier.size(12.dp))
                val n = frames.size
                Text(
                    "$n frame${if (n == 1) "" else "s"} • ${n * settings.submode.period} s on air",
                    style = Theme.mono(10), color = Theme.dim, maxLines = 1,
                )
                Text(
                    "→ " + frames.joinToString("") { it.message },
                    style = Theme.mono(10), color = Theme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ControlStrip(model)
    }
}

@Composable
fun PillButton(title: String, color: Color, filled: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val fg = if (filled) Color.White else color
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(title, style = Theme.rounded(12, Bold), color = fg)
    }
}

/** CQ / Heartbeat / Tune / Levels / Auto / TX-enable buttons. */
@Composable
fun ControlStrip(model: AppModel) {
    var showLevels by remember { mutableStateOf(false) }
    val settings = model.settings
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlButton("CQ", Icons.Filled.Campaign, model.cqLoop, Theme.rx, Modifier.weight(1f)) {
            if (settings.station.cqIntervalMinutes > 0) model.toggleCQLoop() else model.sendCQ()
        }
        ControlButton("HB", Icons.Filled.Favorite, model.heartbeatLoop, Theme.tx, Modifier.weight(1f), disabled = !settings.submode.canSendHeartbeat) {
            model.toggleHeartbeat()
        }
        ControlButton(if (model.isTuning) "Stop" else "Tune", Icons.Filled.Waves, model.isTuning, Theme.warn, Modifier.weight(1f)) {
            if (model.isTuning) model.stopTune() else model.startTune()
        }
        ControlButton("Levels", Icons.Filled.Tune, false, Theme.accent, Modifier.weight(1f)) { showLevels = true }
        ControlButton("Auto", Icons.AutoMirrored.Filled.ReplyAll, settings.station.autoReply, Theme.accent2, Modifier.weight(1f)) {
            model.updateSettings { it.copy(station = it.station.copy(autoReply = !it.station.autoReply)) }
        }
        ControlButton(
            if (settings.txEnabled) "TX On" else "TX Off", Icons.Filled.PowerSettingsNew, settings.txEnabled,
            if (settings.txEnabled) Theme.rx else Theme.tx, Modifier.weight(1f),
        ) {
            val on = !settings.txEnabled
            model.updateSettings { it.copy(txEnabled = on) }
            if (!on) model.halt()
        }
    }
    if (showLevels) LevelsSheet(model) { showLevels = false }
}

@Composable
fun ControlButton(
    title: String,
    icon: ImageVector,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    action: () -> Unit,
) {
    Column(
        modifier
            .dimIf(disabled)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) color else color.copy(alpha = 0.12f))
            .clickable(enabled = !disabled, onClick = action)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val fg = if (active) Theme.bg else color
        Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
        Text(title, style = Theme.rounded(10, Bold), color = fg, maxLines = 1)
    }
}

/** Store-and-forward inbox. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxView(model: AppModel, modifier: Modifier = Modifier, showHeader: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader) SectionHeader("Inbox", Icons.Outlined.Inbox)
        if (model.inbox.isEmpty()) {
            EmptyStateView(
                Icons.Outlined.Inbox, "No messages",
                "Messages sent to you with MSG, and messages you hold for others (MSG TO:), appear here.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(model.inbox, key = { it.id }) { m ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        InboxRow(m, Modifier.combinedClickable(onClick = { model.markRead(m) }, onLongClick = { menu = true }))
                        FireflyMenu(menu, { menu = false }) {
                            MenuItem("Reply to ${m.from}") {
                                menu = false
                                model.selectedCallsign = m.from
                                model.draft = "MSG "
                            }
                            MenuItem("Delete", color = Theme.tx) { menu = false; model.deleteMessage(m) }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun InboxRow(message: InboxMessage, modifier: Modifier = Modifier) {
    val (label, color) = when (message.type) {
        "UNREAD" -> "NEW" to Theme.accent
        "STORE" -> "HOLDING" to Theme.warn
        "DELIVERED" -> "DELIVERED" to Theme.rx
        else -> "READ" to Theme.dim
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.cardHi.copy(alpha = 0.7f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label, color, filled = message.type == "UNREAD")
            Text(
                if (message.type == "STORE") "${message.from} → ${message.to}" else message.from,
                style = Theme.mono(13, Bold), color = Theme.text, maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text("#${message.id}  " + AppTime.utcShort(message.utc) + "Z", style = Theme.mono(10), color = Theme.faint)
        }
        SelectionContainer { Text(message.text, style = Theme.mono(14), color = Theme.text) }
        if (message.path.isNotEmpty() && message.path.contains(">")) {
            Text("via ${message.path}", style = Theme.mono(10), color = Theme.dim)
        }
    }
}
