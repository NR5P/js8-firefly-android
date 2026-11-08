package com.js8firefly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.js8firefly.core.JS8Submode
import com.js8firefly.core.WatchColor
import com.js8firefly.core.WatchItem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Firefly palette, matching Theme.swift. */
object Theme {
    fun rgb(r: Double, g: Double, b: Double, a: Double = 1.0) =
        Color(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())

    val bg = rgb(0.035, 0.045, 0.075)
    val bg2 = rgb(0.06, 0.075, 0.12)
    val card = rgb(0.08, 0.10, 0.16)
    val cardHi = rgb(0.11, 0.14, 0.22)
    val stroke = Color.White.copy(alpha = 0.08f)
    val accent = rgb(0.20, 0.88, 1.0)
    val accent2 = rgb(0.45, 0.55, 1.0)
    val rx = rgb(0.35, 0.95, 0.55)
    val tx = rgb(1.0, 0.36, 0.30)
    val warn = rgb(1.0, 0.75, 0.25)
    val text = Color.White.copy(alpha = 0.92f)
    val dim = Color.White.copy(alpha = 0.55f)
    val faint = Color.White.copy(alpha = 0.30f)

    val gradTop = rgb(0.03, 0.04, 0.08)
    val gradBottom = rgb(0.05, 0.07, 0.13)
    val backgroundGradient = Brush.verticalGradient(listOf(gradTop, gradBottom))

    fun mono(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = weight)

    /** SF Rounded has no Android counterpart; the default sans is the closest. */
    fun rounded(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, fontWeight = weight)

    fun snrColor(snr: Int): Color = when {
        snr < -20 -> rgb(1.0, 0.45, 0.35)
        snr < -10 -> warn
        else -> rx
    }

    fun modeColor(m: JS8Submode): Color = when (m) {
        JS8Submode.slow -> rgb(0.6, 0.5, 1.0)
        JS8Submode.normal -> accent
        JS8Submode.fast -> rx
        JS8Submode.turbo -> warn
        JS8Submode.ultra -> tx
    }
}

val Bold = FontWeight.Bold
val SemiBold = FontWeight.SemiBold
val Heavy = FontWeight.ExtraBold

val WatchColor.color: Color
    get() {
        val c = rgb
        return Color(c.red.toFloat(), c.green.toFloat(), c.blue.toFloat())
    }

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 12.dp, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .background(Theme.card.copy(alpha = 0.85f), shape)
            .border(1.dp, Theme.stroke, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Chip(text: String, color: Color = Theme.accent, filled: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = Theme.mono(11, SemiBold),
        color = if (filled) Theme.bg else color,
        maxLines = 1,
        modifier = Modifier
            .background(if (filled) color else color.copy(alpha = 0.14f), shape)
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.35f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionHeader(title: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, tint = Theme.accent, modifier = Modifier.size(18.dp))
        Text(
            title.uppercase(),
            style = Theme.rounded(12, Bold).copy(letterSpacing = 1.2.sp),
            color = Theme.dim,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun EmptyStateView(icon: ImageVector, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = Theme.accent.copy(alpha = 0.7f), modifier = Modifier.size(34.dp))
        Text(title, style = Theme.rounded(15, SemiBold), color = Theme.text)
        Text(subtitle, style = Theme.rounded(12), color = Theme.dim, textAlign = TextAlign.Center)
    }
}

/** The watch term that made a line stand out. */
@Composable
fun WatchTag(item: WatchItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Filled.Visibility, null, tint = item.color.color, modifier = Modifier.size(11.dp))
        Text(item.term, style = Theme.mono(9, Bold), color = item.color.color)
    }
}

private const val PULSE_SECONDS = 45.0
private const val BREATH_SECONDS = 1.8
private const val NUDGE_SECONDS = 0.6

private fun envelope(age: Double): Pair<Double, Double> {
    if (age < 0) return 0.0 to 0.0
    val fade = max(0.0, 1 - age / PULSE_SECONDS).pow(1.5)
    val breath = 0.5 - 0.5 * cos(2 * PI * age / BREATH_SECONDS)
    val nudge = if (age < NUDGE_SECONDS) sin(2 * PI * age * 5) * (1 - age / NUDGE_SECONDS) else 0.0
    return breath * fade to nudge
}

/**
 * Watched traffic: a glowing outline that breathes for a while after the traffic
 * is heard, with a small nudge on arrival, then settles to a steady glow.
 * [sinceMillis] is the epoch-millisecond time the traffic was heard.
 */
fun Modifier.watchGlow(item: WatchItem?, sinceMillis: Long, cornerRadius: Dp = 10.dp, fill: Boolean = true): Modifier =
    if (item == null) this else composed {
        val color = item.color.color
        var now by remember { mutableLongStateOf(AppTime.nowMillis()) }
        val live = (now - sinceMillis) / 1000.0 in -1.0..PULSE_SECONDS
        LaunchedEffect(sinceMillis, live) {
            while (live) {
                withFrameMillis { }
                now = AppTime.nowMillis()
                if ((now - sinceMillis) / 1000.0 >= PULSE_SECONDS) break
                kotlinx.coroutines.delay(33)
            }
        }
        val (glow, nudge) = if (live) envelope((now - sinceMillis) / 1000.0) else 0.0 to 0.0
        val g = glow.toFloat()
        this
            .offset(x = (2.5 * nudge).dp)
            .drawBehind {
                val r = cornerRadius.toPx()
                // Soft halo: a few widening, fading strokes stand in for SwiftUI's shadow.
                val radius = (3 + 9 * g).dp.toPx()
                val steps = 4
                for (i in steps downTo 1) {
                    val w = radius * i / steps
                    drawRoundRect(
                        color.copy(alpha = (0.3f + 0.4f * g) * 0.35f / i),
                        topLeft = Offset(-w / 2, -w / 2),
                        size = Size(size.width + w, size.height + w),
                        cornerRadius = CornerRadius(r + w / 2),
                        style = Stroke(width = w),
                    )
                }
                if (fill) drawRoundRect(color.copy(alpha = 0.12f + 0.08f * g), cornerRadius = CornerRadius(r))
            }
            .border((1.2f + 0.6f * g).dp, color.copy(alpha = (0.7f + 0.3f * g).coerceAtMost(1f)), RoundedCornerShape(cornerRadius))
    }

fun Modifier.dimIf(disabled: Boolean) = if (disabled) alpha(0.35f) else this
