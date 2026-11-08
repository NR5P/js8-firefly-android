package com.js8firefly.android.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import java.util.Locale
import kotlin.math.roundToInt

/** Quick RX / TX level adjustment, opened from the Levels button or the header meter. */
@Composable
fun LevelsSheet(model: AppModel, onDismiss: () -> Unit) {
    DisposableEffect(Unit) { onDispose { model.stopTune() } }
    FireflySheet("Levels", onDismiss, fullHeight = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val settings = model.settings
            val gain = model.inputGain
            FormSection(
                "Receive", Icons.Filled.ArrowCircleDown,
                footer = if (gain == null)
                    "On a quiet part of the band, aim for about 30 dB. This interface has no adjustable input gain, so set it with the radio's audio-out level (often a USB / DATA audio menu item)."
                else "On a quiet part of the band, aim for about 30 dB. If RX gain runs out, adjust the radio's audio-out level too.",
            ) {
                val now = rememberNow(250)
                FormRow {
                    RxMeter(
                        level = model.inputLevel,
                        clipping = now - AppTime.millisOf(model.lastClip) < 2000,
                        transmitting = model.isTransmitting,
                        modifier = Modifier.weight(1f),
                    )
                }
                FormPicker("Input channel", settings.inputChannel, listOf(0 to "Left", 1 to "Right", -1 to "Mix")) { v ->
                    model.updateSettings { it.copy(inputChannel = v) }
                }
                if (gain != null) {
                    FormRow {
                        LevelSlider("RX gain", gain, 0f..1f, Modifier.weight(1f)) { v ->
                            model.updateSettings { it.copy(inputGain = v) }
                        }
                    }
                }
            }

            FormSection(
                "Transmit", Icons.Filled.ArrowCircleUp,
                footer = "Press Tune, then raise TX level until the radio's ALC just starts to move, and back off a little. Leave the phone's media volume at a fixed setting (it multiplies TX level). Tune stops when you close this panel.",
            ) {
                FormRow {
                    LevelSlider("TX level", settings.tx.outputLevel, 0.01f..1f, Modifier.weight(1f)) { v ->
                        model.updateSettings { it.copy(tx = it.tx.copy(outputLevel = v)) }
                    }
                }
                FormRow {
                    val tuning = model.isTuning
                    val enabled = settings.txEnabled
                    Row(
                        Modifier
                            .weight(1f)
                            .dimIf(!enabled)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (tuning) Theme.tx else Theme.warn.copy(alpha = 0.14f))
                            .clickable(enabled = enabled) { if (tuning) model.stopTune() else model.startTune() }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val c = if (tuning) Theme.bg else Theme.warn
                        Icon(if (tuning) Icons.Filled.Stop else Icons.Filled.Tune, null, tint = c, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (tuning) "Stop tune" else "Tune (key a steady carrier)", style = Theme.rounded(15, Bold), color = c)
                    }
                }
                if (!settings.txEnabled) FormText("TX is off — turn it on to tune.", color = Theme.warn)
                MediaVolumeRow()
            }
        }
    }
}

@Composable
private fun MediaVolumeRow() {
    val context = LocalContext.current
    val now = rememberNow(500)
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val pct = if (now >= 0) (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt() else 0
    LabeledValue("Media volume", "$pct%")
}

/** Slider with fine −/+ steps and a percentage readout. */
@Composable
private fun LevelSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    fun step(delta: Float) = onChange((((value + delta) * 100).roundToInt() / 100f).coerceIn(range.start, range.endInclusive))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(title, style = Theme.rounded(16), color = Theme.text)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = Theme.mono(14, SemiBold), color = Theme.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton(Icons.Filled.Remove) { step(-0.01f) }
            FireflySlider(value, onChange, range, Modifier.weight(1f))
            StepButton(Icons.Filled.Add) { step(0.01f) }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(Theme.accent.copy(alpha = 0.14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Theme.accent, modifier = Modifier.size(16.dp)) }
}

/** Receive level on JS8Call's scale (dB above one 16-bit step), with the target zone shaded. */
@Composable
fun RxMeter(level: Float, clipping: Boolean, transmitting: Boolean, modifier: Modifier = Modifier) {
    val db = level + 90.3f
    val (statusText, statusColor) = when {
        transmitting -> "Transmitting" to Theme.dim
        clipping -> "Clipping — turn down" to Theme.tx
        db < 5 -> "No audio — check cable / channel" to Theme.tx
        db < 15 -> "Too low" to Theme.tx
        db < 25 -> "A little low" to Theme.warn
        db <= 45 -> "Good" to Theme.rx
        db < 60 -> "A little hot" to Theme.warn
        else -> "Too hot — turn down" to Theme.tx
    }
    val shown by animateFloatAsState(db, label = "rx")
    fun frac(v: Float) = (v.coerceIn(0f, 90f)) / 90f
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (db > 0) "${db.roundToInt()} dB" else "– dB", style = Theme.mono(28, Bold), color = statusColor)
            Text(String.format(Locale.US, "%.0f dBFS", level), style = Theme.mono(12), color = Theme.dim, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Text(statusText, style = Theme.rounded(13, Bold), color = statusColor, textAlign = TextAlign.End, modifier = Modifier.padding(bottom = 5.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(5.dp))) {
            val w = size.width
            drawRoundRect(Theme.stroke, cornerRadius = CornerRadius(5.dp.toPx()))
            drawRect(Theme.rx.copy(alpha = 0.22f), Offset(w * frac(25f), 0f), Size(w * (frac(45f) - frac(25f)), size.height))
            drawRoundRect(statusColor, size = Size(maxOf(4.dp.toPx(), w * frac(shown)), size.height), cornerRadius = CornerRadius(5.dp.toPx()))
            drawRect(Theme.text, Offset(w * frac(30f) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
        Row {
            listOf(0, 30, 60, 90).forEachIndexed { i, v ->
                Text("$v", style = Theme.mono(10), color = Theme.faint)
                if (i < 3) Spacer(Modifier.weight(1f))
            }
        }
    }
}
