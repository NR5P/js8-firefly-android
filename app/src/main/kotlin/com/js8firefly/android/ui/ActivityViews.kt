package com.js8firefly.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.Format
import com.js8firefly.android.GridMath
import com.js8firefly.core.BandActivityRow
import com.js8firefly.core.CallDetail
import com.js8firefly.core.Varicode
import com.js8firefly.core.WatchItem
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/** JS8Call's "Band Activity" table. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BandActivityView(model: AppModel, modifier: Modifier = Modifier) {
    val now = rememberNow(5000)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Band Activity", Icons.Filled.GraphicEq) {
            Icon(Icons.Outlined.Delete, "Clear", tint = Theme.faint, modifier = Modifier.size(20.dp).clickable { model.clearBand() })
        }
        val rows = model.bandRows
        if (rows.isEmpty()) {
            EmptyStateView(Icons.Filled.Sensors, "Listening…", "Decodes appear here at the end of each period.")
        } else {
            val settings = model.settings
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { row ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        BandRow(
                            row,
                            selected = abs(row.offset - settings.offset) <= row.submode.rxThreshold,
                            watch = settings.watch(row.text),
                            nowMillis = now,
                            modifier = Modifier.combinedClickable(onClick = { model.setOffset(row.offset) }, onLongClick = { menu = true }),
                        )
                        FireflyMenu(menu, { menu = false }) {
                            MenuItem("Move to ${row.offset} Hz") { menu = false; model.setOffset(row.offset) }
                            Varicode.parseCallsigns(row.text).take(3).forEach { call ->
                                MenuItem("Select $call") { menu = false; model.selectedCallsign = call }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BandRow(row: BandActivityRow, selected: Boolean, watch: WatchItem?, nowMillis: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (watch != null) Modifier.watchGlow(watch, AppTime.millisOf(row.utc), 10.dp, fill = !row.isDirectedToMe) else Modifier)
            .clip(shape)
            .background(
                if (row.isDirectedToMe) Theme.tx.copy(alpha = 0.12f)
                else if (selected) Theme.warn.copy(alpha = 0.08f) else Theme.cardHi.copy(alpha = 0.55f),
            )
            .then(modifier)
            .height(IntrinsicSize.Min)
            .padding(vertical = 7.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(grouped(row.offset), style = Theme.mono(13, Bold), color = if (selected) Theme.warn else Theme.text, maxLines = 1, softWrap = false)
            Text(Format.snr(row.snr), style = Theme.mono(10, SemiBold), color = Theme.snrColor(row.snr))
        }
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(1.5.dp)).background(Theme.modeColor(row.submode)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // SwiftUI truncates the head (keeps the newest text); show the tail when long.
            val text = row.text
            Text(
                if (text.length > 160) "…" + text.takeLast(160) else text,
                style = Theme.mono(13),
                color = if (row.isDirectedToMe) Theme.tx else Theme.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val meta = Theme.mono(9)
                Text(AppTime.since(row.utc, nowMillis), style = meta, color = Theme.faint)
                Text(row.submode.displayName, style = meta, color = Theme.faint)
                Text(String.format(Locale.US, "%+.1fs", row.tdrift.toDouble()), style = meta, color = Theme.faint)
                if (watch != null) WatchTag(watch)
            }
        }
    }
}

/** JS8Call's "Call Activity" (heard stations) list. */
@Composable
fun StationsView(model: AppModel, modifier: Modifier = Modifier, onSelect: (() -> Unit)? = null) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var showMap by rememberBoolPref("stationsShowMap", false)
    val now = rememberNow(5000)
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
    val calls = model.calls
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Stations Heard", Icons.Filled.Groups) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderLink(if (showMap) "List" else "Map", if (showMap) Icons.AutoMirrored.Filled.List else Icons.Outlined.Map, Theme.faint) {
                    showMap = !showMap
                }
                if (calls.isNotEmpty()) {
                    HeaderLink(
                        if (copied) "Copied" else "Copy All",
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        if (copied) Theme.accent else Theme.faint,
                    ) {
                        copyToClipboard(context, calls.joinToString(",") { it.call })
                        copied = true
                    }
                }
                Text("${calls.size}", style = Theme.mono(11), color = Theme.faint)
            }
        }
        if (showMap) {
            StationMapView(model, Modifier.weight(1f), onSelect)
        } else if (calls.isEmpty()) {
            EmptyStateView(Icons.Filled.CellTower, "No stations yet", "Heartbeats, CQs and directed traffic will populate this list.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(calls, key = { it.call }) { cd -> StationListRow(model, cd, now, onSelect) }
            }
        }
    }
}

@Composable
internal fun HeaderLink(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(title, style = Theme.rounded(12), color = color)
    }
}

/** A heard station in a list: tap to select it, long-press for actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationListRow(model: AppModel, cd: CallDetail, nowMillis: Long, onSelect: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    var storing by remember { mutableStateOf(false) }
    Box {
        StationRow(
            cd, model.settings.station.grid,
            selected = model.selectedCallsign == cd.call,
            watch = model.settings.watch(cd.call),
            nowMillis = nowMillis,
            modifier = Modifier.combinedClickable(
                onClick = {
                    model.selectedCallsign = if (model.selectedCallsign == cd.call) "" else cd.call
                    if (model.selectedCallsign == cd.call) onSelect?.invoke()
                },
                onLongClick = { menu = true },
            ),
        )
        FireflyMenu(menu, { menu = false }) {
            MenuItem("Select ${cd.call}") { menu = false; model.selectedCallsign = cd.call }
            MenuItem("Move to ${cd.offset} Hz") { menu = false; model.setOffset(cd.offset) }
            MenuItem("Ask SNR?") { menu = false; model.selectedCallsign = cd.call; model.send("SNR?") }
            MenuItem("Ask GRID?") { menu = false; model.selectedCallsign = cd.call; model.send("GRID?") }
            MenuItem("Store Message for ${cd.call}…") { menu = false; storing = true }
        }
    }
    if (storing) StoreMessageSheet(model, cd.call) { storing = false }
}

@Composable
fun StationRow(cd: CallDetail, myGrid: String, selected: Boolean, watch: WatchItem?, nowMillis: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (watch != null) Modifier.watchGlow(watch, AppTime.millisOf(cd.utc), 12.dp, fill = !selected) else Modifier)
            .clip(shape)
            .background(if (selected) Theme.accent.copy(alpha = 0.12f) else Theme.cardHi.copy(alpha = 0.55f))
            .border(1.dp, if (selected) Theme.accent.copy(alpha = 0.6f) else Color.Transparent, shape)
            .then(modifier)
            .padding(vertical = 6.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp).background(if (selected) Theme.accent else Theme.cardHi, CircleShape), contentAlignment = Alignment.Center) {
            Text(cd.call.take(1), style = Theme.rounded(15, Bold), color = if (selected) Theme.bg else Theme.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(cd.call, style = Theme.mono(14, Bold), color = Theme.text, maxLines = 1)
                if (cd.cqTimestamp != null) Chip("CQ", Theme.rx)
                if (cd.ackTimestamp != null) Chip("ACK", Theme.accent2)
                if (cd.through.isNotEmpty()) Chip("via ${cd.through}", Theme.faint)
                if (watch != null) Icon(Icons.Filled.Visibility, null, tint = watch.color.color, modifier = Modifier.size(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val meta = Theme.mono(10)
                val grid = cd.grid.trim()
                if (grid.isNotEmpty()) Text(grid, style = meta, color = Theme.dim)
                GridMath.distanceBearing(myGrid, grid)?.let { db ->
                    Text("${grouped(db.km.toInt())} km  ${db.bearing.toInt()}°", style = meta, color = Theme.dim)
                }
                Text("${grouped(cd.offset)} Hz", style = meta, color = Theme.dim)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Format.snr(cd.snr) + " dB", style = Theme.mono(12, SemiBold), color = Theme.snrColor(cd.snr))
            Text(AppTime.since(cd.utc, nowMillis), style = Theme.mono(10), color = Theme.faint)
        }
    }
}

