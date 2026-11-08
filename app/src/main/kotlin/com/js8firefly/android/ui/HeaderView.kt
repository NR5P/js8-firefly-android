package com.js8firefly.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.Band
import com.js8firefly.android.Format
import com.js8firefly.android.SavedFrequency
import com.js8firefly.core.JS8Submode
import kotlinx.coroutines.delay
import java.util.Locale

/** A clock that ticks every [periodMs], read only by the composable that needs it. */
@Composable
fun rememberNow(periodMs: Long): Long {
    var now by remember { mutableLongStateOf(AppTime.nowMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = AppTime.nowMillis()
            delay(periodMs)
        }
    }
    return now
}

/** Top status console: clock, period ring, band/dial, speed and TX state. */
@Composable
fun HeaderView(model: AppModel, modifier: Modifier = Modifier) {
    var showBands by remember { mutableStateOf(false) }
    var showLevels by remember { mutableStateOf(false) }
    val now = rememberNow(100)
    val settings = model.settings
    val transmitting = model.isTransmitting

    Box(
        modifier
            .fillMaxWidth()
            .background(Theme.bg2.copy(alpha = 0.92f))
            .then(
                if (transmitting) Modifier.background(Brush.horizontalGradient(listOf(Theme.tx.copy(alpha = 0.35f), Color.Transparent)))
                else Modifier,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeriodRing(settings.submode, now, transmitting, Modifier.size(46.dp))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        settings.station.callsign.ifEmpty { "NOCALL" },
                        style = Theme.mono(15, Bold), color = Theme.text, maxLines = 1,
                    )
                    if (settings.station.grid.isNotEmpty()) {
                        Text(settings.station.grid.take(6), style = Theme.mono(11), color = Theme.dim, maxLines = 1)
                    }
                }
                Text(AppTime.utcTime(now) + " UTC", style = Theme.mono(12), color = Theme.dim)
            }

            Spacer(Modifier.weight(1f).width(4.dp))

            Column(
                Modifier.clickable { showBands = true },
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).background(rigColor(model), CircleShape))
                    Text(
                        listOfNotNull(Band.name(settings.dialFrequency), settings.dialLabel).joinToString(" · "),
                        style = Theme.rounded(11, Bold), color = Theme.accent, maxLines = 1,
                    )
                }
                Text(Format.mhzLong(settings.dialFrequency), style = Theme.mono(15, SemiBold), color = Theme.text)
            }

            SpeedMenu(model)
        }
        // Level meter along the bottom edge; tap opens Levels.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(10.dp)
                .clickable { showLevels = true },
            contentAlignment = Alignment.BottomStart,
        ) {
            LevelBar(model.inputLevel, transmitting, Modifier.fillMaxWidth().height(3.dp))
        }
    }

    if (showBands) BandPickerSheet(model) { showBands = false }
    if (showLevels) LevelsSheet(model) { showLevels = false }
}

fun rigColor(model: AppModel): Color = rigStatusColor(model.rig.status)

@Composable
fun SpeedMenu(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val m = model.settings.submode
    Box {
        Text(
            m.displayName.uppercase(),
            style = Theme.mono(11, Bold),
            color = Theme.modeColor(m),
            modifier = Modifier
                .background(Theme.modeColor(m).copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        FireflyMenu(open, { open = false }) {
            JS8Submode.allCases.sortedByDescending { it.period }.forEach { s ->
                MenuItem("${s.displayName} — ${s.period}s, ${s.bandwidth} Hz", checked = s == m) {
                    model.updateSettings { it.copy(submode = s) }
                    open = false
                }
            }
        }
    }
}

/** Ring showing progress through the current transmit period. */
@Composable
fun PeriodRing(submode: JS8Submode, nowMillis: Long, transmitting: Boolean, modifier: Modifier = Modifier) {
    val p = submode.period.toDouble()
    val t = (nowMillis / 1000.0) % p
    val frac = (t / p).toFloat()
    val txFrac = (submode.txDuration / p).toFloat()
    val startFrac = (submode.startDelayMS / 1000.0 / p).toFloat()
    val color = if (transmitting) Theme.tx else Theme.accent
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val sw = 5.dp.toPx()
            val inset = sw / 2
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            val sz = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
            drawArc(Theme.stroke, 0f, 360f, false, tl, sz, style = Stroke(sw))
            drawArc(Theme.faint.copy(alpha = 0.18f), -90f + 360f * startFrac, 360f * (txFrac - startFrac), false, tl, sz, style = Stroke(sw))
            // glow
            drawArc(color.copy(alpha = 0.25f), -90f, 360f * frac, false, tl, sz, style = Stroke(sw + 4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(p - t).toInt()}", style = Theme.mono(14, Bold), color = if (transmitting) Theme.tx else Theme.text)
            Text(
                if (transmitting) "TX" else "RX",
                style = Theme.rounded(8, Heavy).copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                color = if (transmitting) Theme.tx else Theme.rx,
                modifier = Modifier.padding(top = 0.dp),
            )
        }
    }
}

@Composable
fun LevelBar(level: Float, transmitting: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val norm = ((level + 80f) / 70f).coerceIn(0f, 1f)
        drawRect(Theme.stroke)
        val w = size.width * (if (transmitting) 1f else norm)
        drawRect(
            Brush.horizontalGradient(listOf(Theme.rx, Theme.warn, Theme.tx), startX = 0f, endX = size.width),
            size = androidx.compose.ui.geometry.Size(w, size.height),
            alpha = if (transmitting) 0.6f else 1f,
        )
    }
}

fun parseKHz(text: String): Long? {
    val khz = text.replace(",", ".").trim().toDoubleOrNull() ?: return null
    if (khz <= 100) return null
    return Math.round(khz * 1000)
}

private fun khzString(hz: Long) = String.format(Locale.US, "%.3f", hz / 1000.0)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BandPickerSheet(model: AppModel, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    var customLabel by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SavedFrequency?>(null) }
    var editKHz by remember { mutableStateOf("") }
    var editLabel by remember { mutableStateOf("") }
    val settings = model.settings

    FireflySheet("Band", onDismiss, fullHeight = false) {
        LazyColumn(Modifier.fillMaxWidth()) {
            if (settings.customFrequencies.isNotEmpty()) {
                item("mine") {
                    FormSection("My frequencies", footer = "Long-press to edit or delete.") {
                        settings.customFrequencies.forEach { saved ->
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { model.setDial(saved.frequency); onDismiss() },
                                            onLongClick = { menu = true },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            saved.label.ifEmpty { Band.name(saved.frequency) },
                                            style = Theme.rounded(16, SemiBold), color = Theme.text, maxLines = 1,
                                        )
                                        Text(
                                            Format.mhzLong(saved.frequency) + " MHz · " + Band.name(saved.frequency),
                                            style = Theme.mono(13), color = Theme.dim,
                                        )
                                    }
                                    if (settings.dialFrequency == saved.frequency) {
                                        Icon(Icons.Filled.CheckCircle, null, tint = Theme.accent)
                                    }
                                }
                                FireflyMenu(menu, { menu = false }) {
                                    MenuItem("Edit", icon = Icons.Filled.Edit) {
                                        menu = false
                                        editKHz = khzString(saved.frequency)
                                        editLabel = saved.label
                                        editing = saved
                                    }
                                    MenuItem("Delete", icon = Icons.Filled.Delete, color = Theme.tx) {
                                        menu = false
                                        model.deleteFrequency(saved.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item("bands") {
                FormSection(
                    "JS8 calling frequencies (USB dial)",
                    footer = if (model.rig.isConnected) "The radio will be tuned automatically."
                    else "No rig control connected — set the radio to this dial frequency in USB / DATA-U.",
                ) {
                    Band.js8.forEach { band ->
                        FormRow(onClick = { model.setDial(band.frequency); onDismiss() }) {
                            Text(band.name, style = Theme.rounded(16, SemiBold), color = Theme.accent, modifier = Modifier.width(60.dp))
                            Text(Format.mhzLong(band.frequency) + " MHz", style = Theme.mono(15), color = Theme.accent, modifier = Modifier.weight(1f))
                            if (settings.dialFrequency == band.frequency) Icon(Icons.Filled.CheckCircle, null, tint = Theme.accent)
                        }
                    }
                }
            }
            item("custom") {
                FormSection("Custom dial", footer = "Save adds it to My frequencies. Leave the frequency empty to save the current dial.") {
                    FormRow {
                        PlainField(
                            custom, { custom = it }, "kHz, e.g. " + khzString(settings.dialFrequency),
                            Modifier.weight(1f), style = Theme.mono(15), keyboardType = KeyboardType.Decimal,
                        )
                    }
                    FormRow {
                        PlainField(customLabel, { customLabel = it }, "Label (optional), e.g. Local Net", Modifier.weight(1f))
                    }
                    FormRow(divider = false) {
                        val parsed = parseKHz(custom)
                        TextButton(onClick = { parsed?.let { model.setDial(it); onDismiss() } }, enabled = parsed != null) {
                            Text("Tune", style = Theme.rounded(16), color = if (parsed != null) Theme.accent else Theme.faint)
                        }
                        Spacer(Modifier.weight(1f))
                        val canSave = custom.isEmpty() || parsed != null
                        TextButton(
                            onClick = {
                                val hz = if (custom.isEmpty()) settings.dialFrequency else parsed
                                if (hz != null) {
                                    model.saveFrequency(hz, customLabel)
                                    model.setDial(hz)
                                    custom = ""
                                    customLabel = ""
                                }
                            },
                            enabled = canSave,
                        ) { Text("Save", style = Theme.rounded(16), color = if (canSave) Theme.accent else Theme.faint) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editing?.let { saved ->
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Theme.cardHi,
            title = { Text("Edit frequency", color = Theme.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        editKHz, { editKHz = it }, label = { Text("kHz") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors(),
                    )
                    OutlinedTextField(editLabel, { editLabel = it }, label = { Text("Label") }, singleLine = true, colors = fieldColors())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    parseKHz(editKHz)?.let { model.saveFrequency(it, editLabel, saved.id) }
                    editing = null
                }) { Text("Save", color = Theme.accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Theme.accent) } },
        )
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Theme.text,
    unfocusedTextColor = Theme.text,
    focusedBorderColor = Theme.accent,
    unfocusedBorderColor = Theme.stroke,
    cursorColor = Theme.accent,
    focusedLabelColor = Theme.accent,
    unfocusedLabelColor = Theme.dim,
)

fun rigStatusColor(status: com.js8firefly.android.radio.RigController.Status): Color = when (status) {
    com.js8firefly.android.radio.RigController.Status.connected -> Theme.rx
    com.js8firefly.android.radio.RigController.Status.connecting -> Theme.warn
    is com.js8firefly.android.radio.RigController.Status.error -> Theme.tx
    com.js8firefly.android.radio.RigController.Status.disconnected -> Theme.faint
}
