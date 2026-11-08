package com.js8firefly.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.audio.WaterfallRenderer

/**
 * Waterfall with frequency ruler, spectrum trace, decode labels and the
 * operator's transmit passband. Tap to move the offset.
 * All waterfall state is read inside the Canvas draw lambda, so the 10 Hz
 * image updates only redraw this canvas instead of recomposing the screen.
 */
@Composable
fun WaterfallView(model: AppModel, modifier: Modifier = Modifier, showSpectrum: Boolean = true) {
    val minHz = WaterfallRenderer.minHz
    val maxHz = WaterfallRenderer.maxHz
    val measurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, Theme.stroke, shape)
            .background(Color.Black),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        model.tuneNear(minHz + (p.x / size.width) * (maxHz - minHz))
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val rulerH = 18.dp.toPx()
            fun xFor(hz: Double) = ((hz - minHz) / (maxHz - minHz)).toFloat() * w

            model.waterfallImage?.let { img ->
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(0, rulerH.toInt()),
                    dstSize = IntSize(w.toInt(), (h - rulerH).toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }

            // Ruler
            drawRect(Theme.bg2, size = Size(w, rulerH))
            val small = Theme.mono(9).copy(color = Theme.dim)
            var f = 500.0
            while (f < maxHz) {
                val x = xFor(f)
                val major = f.toInt() % 500 == 0
                drawLine(Theme.dim, Offset(x, rulerH - (if (major) 7 else 4).dp.toPx()), Offset(x, rulerH), 1.dp.toPx())
                if (major) {
                    val t = measurer.measure(grouped(f.toInt()), small)
                    drawText(t, topLeft = Offset(x - t.size.width / 2f, 6.dp.toPx() - t.size.height / 2f))
                }
                f += 100
            }

            // Period boundaries (the lines themselves are in the image)
            val imgH = h - rulerH
            val markStyle = Theme.mono(9).copy(color = Color.White.copy(alpha = 0.85f))
            for (mark in model.periodMarks) {
                val y = rulerH + mark.rowsFromTop.toFloat() / WaterfallRenderer.height * imgH
                if (y >= h - 8.dp.toPx()) continue
                val t = measurer.measure(AppTime.utcTime(mark.start), markStyle)
                val th = t.size.height.toFloat()
                val top = y - th - 1.dp.toPx()
                drawRoundRect(
                    Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset(2.dp.toPx(), top),
                    size = Size(t.size.width + 6.dp.toPx(), th),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                drawText(t, topLeft = Offset(5.dp.toPx(), top))
            }

            // Spectrum trace
            val spectrum = model.spectrum
            if (showSpectrum && spectrum.size > 2) {
                val path = Path()
                val n = spectrum.size
                val ampH = (h - rulerH) * 0.35f
                spectrum.forEachIndexed { i, v ->
                    val x = i.toFloat() / (n - 1) * w
                    val y = h - v.toFloat().coerceIn(0f, 1.2f) * ampH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color.White.copy(alpha = 0.45f), style = Stroke(1.dp.toPx()))
            }

            // Operator passband
            val settings = model.settings
            val off = settings.offset.toDouble()
            val bw = settings.submode.bandwidth.toDouble()
            val x0 = xFor(off)
            val x1 = xFor(off + bw)
            val txColor = if (model.isTransmitting) Theme.tx else Theme.warn
            drawRect(txColor.copy(alpha = 0.10f), Offset(x0, rulerH), Size(maxOf(2f, x1 - x0), h - rulerH))
            drawLine(txColor.copy(alpha = 0.9f), Offset(x0, rulerH), Offset(x0, h), 1.2f.dp.toPx())
            drawLine(txColor.copy(alpha = 0.9f), Offset(x1, rulerH), Offset(x1, h), 1.2f.dp.toPx())
            drawRect(txColor, Offset(x0, 0f), Size(x1 - x0, rulerH - 3.dp.toPx()))

            // Decode labels
            val now = System.currentTimeMillis()
            val lanes = ArrayList<Float>()
            val labelStyle = Theme.mono(10, SemiBold).copy(color = Color.White)
            for (m in model.markers.sortedBy { it.frequency }) {
                val age = (now - AppTime.millisOf(m.date)) / 1000.0
                val alpha = maxOf(0.15, 1 - age / 45).toFloat()
                val mx = xFor(m.frequency.toDouble())
                val mw = maxOf(2f, xFor(m.frequency.toDouble() + m.bandwidth) - mx)
                var lane = 0
                while (lane < lanes.size && lanes[lane] > mx) lane++
                if (lane == lanes.size) lanes.add(0f)
                val t = measurer.measure(m.label, labelStyle, maxLines = 1)
                lanes[lane] = mx + t.size.width + 10.dp.toPx()
                val y = rulerH + 4.dp.toPx() + lane * 16.dp.toPx()
                val c = Theme.modeColor(m.submode)
                drawRoundRect(
                    c.copy(alpha = 0.75f * alpha),
                    topLeft = Offset(mx, y),
                    size = Size(t.size.width + 8.dp.toPx(), 14.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawText(t, topLeft = Offset(mx + 4.dp.toPx(), y + 7.dp.toPx() - t.size.height / 2f))
                drawRect(c.copy(alpha = alpha), Offset(mx, y + 14.dp.toPx()), Size(mw, 2.dp.toPx()))
            }
        }
        OffsetBadge(model, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun OffsetBadge(model: AppModel, modifier: Modifier) {
    Text(
        "${grouped(model.settings.offset)} Hz",
        style = Theme.mono(11, Bold),
        color = Theme.warn,
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
