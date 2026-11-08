package com.js8firefly.android.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.GridMath
import com.js8firefly.android.R
import com.js8firefly.core.CallDetail
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Offline base map drawn from worldmap.bin (Natural Earth 1:50m, public domain).
 * Format ("FFMP"): magic, version byte, layer count byte; each layer is a kind
 * byte (0 land, 1 lakes, 2 borders, 3 states), a u32 ring count, then per ring a
 * u32 point count and that many (lon, lat) little-endian Int16 pairs in 1/100°.
 * Paths are in degrees, x = longitude and y = -latitude.
 */
class WorldMap {
    var land = android.graphics.Path()
    var lakes = android.graphics.Path()
    var borders = android.graphics.Path()
    var states = android.graphics.Path()

    companion object {
        @Volatile private var cached: WorldMap? = null

        fun shared(context: Context): WorldMap =
            cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

        private fun load(context: Context): WorldMap {
            val map = WorldMap()
            val b = try {
                context.resources.openRawResource(R.raw.worldmap).use { it.readBytes() }
            } catch (e: Exception) { return map }
            if (b.size <= 6 || b[0] != 70.toByte() || b[1] != 70.toByte() || b[2] != 77.toByte() || b[3] != 80.toByte()) return map
            var i = 6
            fun u8(k: Int) = b[k].toInt() and 0xFF
            fun u32(): Int? {
                if (i + 4 > b.size) return null
                val v = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
                i += 4
                return v
            }
            fun deg(): Float {
                val v = (u8(i) or (u8(i + 1) shl 8)).toShort()
                i += 2
                return v / 100f
            }
            repeat(u8(5)) {
                if (i >= b.size) return map
                val kind = u8(i)
                i += 1
                val rings = u32() ?: return map
                val path = android.graphics.Path()
                for (r in 0 until rings) {
                    val n = u32() ?: return map
                    if (n <= 0 || i + n * 4 > b.size) return map
                    for (k in 0 until n) {
                        val lon = deg()
                        val lat = deg()
                        if (k == 0) path.moveTo(lon, -lat) else path.lineTo(lon, -lat)
                    }
                    if (kind < 2) path.close()
                }
                when (kind) {
                    0 -> map.land = path
                    1 -> map.lakes = path
                    2 -> map.borders = path
                    else -> map.states = path
                }
            }
            return map
        }
    }
}

private fun roundHalfAway(v: Double): Double = if (v >= 0) floor(v + 0.5) else -floor(-v + 0.5)

/**
 * What part of the world is on screen: a plate carrée projection with longitude
 * squeezed by [aspect] (the cosine of a reference latitude).
 */
data class MapCamera(val lon: Double, val lat: Double, val scale: Double, val aspect: Double) {
    val xScale: Double get() = scale * aspect

    fun point(l0: Double, t: Double, size: Size): Offset {
        val l = l0 + 360 * roundHalfAway((lon - l0) / 360)
        return Offset(((l - lon) * xScale + size.width / 2).toFloat(), ((lat - t) * scale + size.height / 2).toFloat())
    }

    fun matrix(size: Size, copy: Int): android.graphics.Matrix = android.graphics.Matrix().apply {
        setValues(
            floatArrayOf(
                xScale.toFloat(), 0f, (size.width / 2 + xScale * (copy * 360.0 - lon)).toFloat(),
                0f, scale.toFloat(), (size.height / 2 + scale * lat).toFloat(),
                0f, 0f, 1f,
            ),
        )
    }

    data class Visible(val lon0: Double, val lon1: Double, val lat0: Double, val lat1: Double)

    fun visible(size: Size): Visible {
        val dx = size.width / 2 / xScale
        val dy = size.height / 2 / scale
        return Visible(lon - dx, lon + dx, lat - dy, lat + dy)
    }

    fun clamped(size: Size): MapCamera {
        val s = min(max(scale, minScale(size, aspect)), MAX_SCALE)
        val t = min(max(lat, -80.0), 85.0)
        var l = (lon + 180) % 360
        if (l < 0) l += 360
        return copy(lon = l - 180, lat = t, scale = s)
    }

    fun panned(dx: Float, dy: Float, size: Size) = copy(lon = lon - dx / xScale, lat = lat + dy / scale).clamped(size)

    /** Zoom by [factor], keeping the spot under [anchor] where it is. */
    fun zoomed(factor: Double, anchor: Offset, size: Size): MapCamera {
        val ax = anchor.x - size.width / 2.0
        val ay = anchor.y - size.height / 2.0
        val l = lon + ax / xScale
        val t = lat - ay / scale
        val c = copy(scale = scale * factor).clamped(size)
        return c.copy(lon = l - ax / c.xScale, lat = t + ay / c.scale).clamped(size)
    }

    companion object {
        const val MAX_SCALE = 700.0
        fun minScale(size: Size, aspect: Double) = max(0.3, min(size.width / (360 * aspect), size.height / 170.0))
    }
}

/** Heard stations that sent a grid, grouped by the square they're in. */
data class MapMarker(val id: String, val calls: List<CallDetail>, val lon: Double, val lat: Double) {
    val latest: Long get() = calls.maxOfOrNull { AppTime.millisOf(it.utc) } ?: Long.MIN_VALUE
}

private val Ocean = Theme.rgb(0.045, 0.06, 0.10)
private val Land = Theme.rgb(0.11, 0.145, 0.22)

private fun ageColor(ageSeconds: Double): Color = when {
    ageSeconds < 15 * 60 -> Theme.rx
    ageSeconds < 60 * 60 -> Theme.accent
    else -> Theme.dim
}

private fun squareCenter(g: String): Pair<Double, Double>? =
    GridMath.square(g)?.let { sq -> (sq.lon + sq.width / 2) to (sq.lat + sq.height / 2) }

/**
 * Stations Heard on an offline map, placed by the grids they sent on air
 * (heartbeats, CQs and GRID replies), so it shows where they say they are.
 */
@Composable
fun StationMapView(model: AppModel, modifier: Modifier = Modifier, onSelect: (() -> Unit)? = null) {
    val context = LocalContext.current
    val map = remember { WorldMap.shared(context) }
    var camera by remember { mutableStateOf<MapCamera?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    val measurer = rememberTextMeasurer()
    val myGrid = model.settings.station.grid
    val calls = model.calls

    val markers = remember(calls) {
        calls.filter { GridMath.mapKey(it.grid) != null }
            .groupBy { GridMath.mapKey(it.grid)!! }
            .mapNotNull { (key, cs) ->
                val sq = GridMath.square(key) ?: return@mapNotNull null
                MapMarker(key, cs.sortedByDescending { AppTime.millisOf(it.utc) }, sq.lon + sq.width / 2, sq.lat + sq.height / 2)
            }
            .sortedBy { it.latest } // freshest drawn last, on top
    }
    LaunchedEffect(markers.map { it.id }) {
        if (picked != null && markers.none { it.id == picked }) picked = null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(12.dp)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .border(1.dp, Theme.stroke, shape),
        ) {
            val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val cam = camera?.clamped(size) ?: fitted(markers, myGrid, size, LocalDensity.current.density)
            val camState = rememberUpdatedState(cam)
            val sizeState = rememberUpdatedState(size)
            val markersState = rememberUpdatedState(markers)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            var c = camState.value ?: return@detectTransformGestures
                            val s = sizeState.value
                            if (zoom != 1f) c = c.zoomed(zoom.toDouble(), centroid, s)
                            c = c.panned(pan.x, pan.y, s)
                            camera = c
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            val c = camState.value ?: return@detectTapGestures
                            val s = sizeState.value
                            val hit = markersState.value
                                .map { m -> m to c.point(m.lon, m.lat, s).let { hypot(it.x - p.x, it.y - p.y) } }
                                .filter { it.second < 24.dp.toPx() }
                                .minByOrNull { it.second }?.first
                            picked = hit?.id
                        }
                    },
            ) {
                drawRect(Ocean)
                if (cam == null || size.width <= 0) return@Canvas
                drawBaseMap(map, cam)
                drawGrid(cam, measurer)
                drawMe(cam, myGrid)
                drawMarkers(cam, markers, myGrid, model, picked, measurer)
            }
            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                MapButton(Icons.Filled.Add, "Zoom in") { cam?.let { camera = it.zoomed(2.0, center, size) } }
                MapButton(Icons.Filled.Remove, "Zoom out") { cam?.let { camera = it.zoomed(0.5, center, size) } }
                MapButton(Icons.Filled.FilterCenterFocus, "Show all stations") { camera = null }
            }
        }

        val p = picked?.let { id -> markers.firstOrNull { it.id == id } }
        if (p != null) PickedPanel(model, p, myGrid, onSelect) { picked = null }
        else Summary(model, markers)
    }
}

private fun fitted(markers: List<MapMarker>, myGrid: String, size: Size, density: Float): MapCamera? {
    if (size.width <= 0 || size.height <= 0) return null
    val me = squareCenter(myGrid)
    val points = markers.map { it.lon to it.lat }.toMutableList()
    if (me != null) points.add(me)
    val ref = me ?: points.firstOrNull()
        ?: return MapCamera(0.0, 20.0, 0.0, cos(Math.PI / 6)).clamped(size)
    val lons = points.map { it.first + 360 * roundHalfAway((ref.first - it.first) / 360) }
    val lats = points.map { it.second }
    val minLon = lons.min(); val maxLon = lons.max(); val minLat = lats.min(); val maxLat = lats.max()
    val midLat = (minLat + maxLat) / 2
    val aspect = max(0.3, cos(midLat * Math.PI / 180))
    val spanLon = max(maxLon - minLon, 14.0)
    val spanLat = max(maxLat - minLat, 8.0)
    val left = 30.0 * density; val right = 90.0 * density
    val scale = max(0.3, min((size.width - left - right) / (spanLon * aspect), (size.height - 70 * density) / spanLat))
    val shift = (right - left) / 2 / (scale * aspect)
    return MapCamera((minLon + maxLon) / 2 + shift, midLat, scale, aspect).clamped(size)
}

private fun DrawScope.drawBaseMap(map: WorldMap, cam: MapCamera) {
    val vis = cam.visible(size)
    val dst = android.graphics.Path()
    val fill = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL }
    val stroke = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        for (copy in -1..1) {
            val lo = copy * 360.0 - 180
            val hi = copy * 360.0 + 180
            if (vis.lon1 < lo || vis.lon0 > hi) continue
            val m = cam.matrix(size, copy)
            fill.color = Land.toArgb()
            map.land.transform(m, dst); nc.drawPath(dst, fill)
            fill.color = Ocean.toArgb()
            map.lakes.transform(m, dst); nc.drawPath(dst, fill)
            stroke.color = Color.White.copy(alpha = 0.07f).toArgb(); stroke.strokeWidth = 0.6f * density
            map.states.transform(m, dst); nc.drawPath(dst, stroke)
            stroke.color = Color.White.copy(alpha = 0.17f).toArgb(); stroke.strokeWidth = 0.8f * density
            map.borders.transform(m, dst); nc.drawPath(dst, stroke)
        }
    }
}

/** Maidenhead field lines, and square lines with labels once they're far enough apart. */
private fun DrawScope.drawGrid(cam: MapCamera, measurer: TextMeasurer) {
    val vis = cam.visible(size)
    val squares = cam.xScale * 2 >= 26 * density
    val stepLon = if (squares) 2.0 else 20.0
    val stepLat = if (squares) 1.0 else 10.0
    val lines = Path()
    var l = floor(vis.lon0 / stepLon) * stepLon
    while (l <= vis.lon1) {
        val x = cam.point(l, 0.0, size).x
        lines.moveTo(x, 0f); lines.lineTo(x, size.height)
        l += stepLon
    }
    var t = max(-90.0, floor(vis.lat0 / stepLat) * stepLat)
    while (t <= min(90.0, vis.lat1)) {
        val y = cam.point(cam.lon, t, size).y
        lines.moveTo(0f, y); lines.lineTo(size.width, y)
        t += stepLat
    }
    drawPath(lines, Theme.accent.copy(alpha = 0.09f), style = Stroke(0.6f * density))

    val cellWidth = cam.xScale * stepLon
    if (cellWidth < (if (squares) 46 else 34) * density || cam.scale * stepLat < 22 * density) return
    val style = Theme.mono(9, SemiBold).copy(color = Theme.accent.copy(alpha = 0.28f))
    var lon = floor(vis.lon0 / stepLon) * stepLon
    while (lon <= vis.lon1) {
        var lat = max(-90.0, floor(vis.lat0 / stepLat) * stepLat)
        while (lat < min(90.0, vis.lat1)) {
            val wrapped = lon - 360 * floor((lon + 180) / 360)
            val name = GridMath.grid(lat + stepLat / 2, wrapped + stepLon / 2)
            val p = cam.point(lon, lat + stepLat, size)
            drawText(measurer.measure(name.take(if (squares) 4 else 2), style), topLeft = Offset(p.x + 3 * density, p.y + 2 * density))
            lat += stepLat
        }
        lon += stepLon
    }
}

private fun DrawScope.drawMe(cam: MapCamera, myGrid: String) {
    val sq = GridMath.square(myGrid) ?: return
    val a = cam.point(sq.lon, sq.lat + sq.height, size)
    val b = cam.point(sq.lon + sq.width, sq.lat, size)
    val c = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    if (b.x - a.x >= 8 * density) {
        drawRect(Theme.warn.copy(alpha = 0.12f), a, Size(b.x - a.x, b.y - a.y))
        drawRect(Theme.warn.copy(alpha = 0.7f), a, Size(b.x - a.x, b.y - a.y), style = Stroke(1 * density))
    }
    val r = 6 * density
    val diamond = Path().apply {
        moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
    }
    drawPath(diamond, Theme.warn)
    drawPath(diamond, Theme.bg, style = Stroke(1 * density))
}

private fun DrawScope.drawMarkers(
    cam: MapCamera,
    markers: List<MapMarker>,
    myGrid: String,
    model: AppModel,
    picked: String?,
    measurer: TextMeasurer,
) {
    val now = AppTime.nowMillis()
    val selected = model.selectedCallsign
    val taken = ArrayList<Rect>()
    val d = density
    squareCenter(myGrid)?.let { (lo, la) ->
        val me = cam.point(lo, la, size)
        taken.add(Rect(me.x - 7 * d, me.y - 7 * d, me.x + 7 * d, me.y + 7 * d))
    }
    val labels = ArrayList<Pair<MapMarker, Offset>>()
    val settings = model.settings
    for (m in markers) {
        val p = cam.point(m.lon, m.lat, size)
        if (p.x < -20 * d || p.x > size.width + 20 * d || p.y < -20 * d || p.y > size.height + 20 * d) continue
        val color = ageColor((now - m.latest) / 1000.0)
        val many = m.calls.size > 1
        val r = (if (many) 8f else 5.5f) * d
        drawCircle(color.copy(alpha = 0.18f), r + 4 * d, p)
        drawCircle(color, r, p)
        m.calls.firstNotNullOfOrNull { settings.watch(it.call) }?.let { w ->
            drawCircle(w.color.color, r, p, style = Stroke(2 * d))
        }
        if (m.calls.any { it.call == selected } || m.id == picked) {
            drawCircle(if (m.id == picked) Theme.text else Theme.accent, r + 3 * d, p, style = Stroke(1.6f * d))
        }
        if (many) {
            val t = measurer.measure("${m.calls.size}", Theme.mono(9, Bold).copy(color = Theme.bg))
            drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
        }
        taken.add(Rect(p.x - r, p.y - r, p.x + r, p.y + r))
        labels.add(m to p)
    }

    fun prominent(m: MapMarker) = m.id == picked || m.calls.any { it.call == selected }
    val order = labels.sortedWith(compareByDescending<Pair<MapMarker, Offset>> { prominent(it.first) }.thenByDescending { it.first.latest })
    val placed = ArrayList<Rect>()
    val bounds = Rect(4 * d, 2 * d, size.width - 4 * d, size.height - 2 * d)
    val style = Theme.mono(10, SemiBold).copy(color = Theme.text)
    for ((m, p) in order) {
        val name = if (m.calls.size > 1) "${m.calls[0].call} +${m.calls.size - 1}" else m.calls[0].call
        val t = measurer.measure(name, style, maxLines = 1)
        val w = t.size.width.toFloat()
        val h = t.size.height.toFloat()
        val r = (if (m.calls.size > 1) 8f else 5.5f) * d
        val spots = listOf(
            Rect(Offset(p.x + r + 4 * d, p.y - h / 2), Size(w, h)),
            Rect(Offset(p.x - r - 4 * d - w, p.y - h / 2), Size(w, h)),
            Rect(Offset(p.x - w / 2, p.y + r + 3 * d), Size(w, h)),
            Rect(Offset(p.x - w / 2, p.y - r - 3 * d - h), Size(w, h)),
        )
        val rect = spots.firstOrNull { s ->
            s.left >= bounds.left && s.top >= bounds.top && s.right <= bounds.right && s.bottom <= bounds.bottom &&
                placed.none { it.inflate(2 * d).overlaps(s) } && taken.none { it.overlaps(s) }
        } ?: continue
        placed.add(rect)
        drawRoundRect(
            Theme.bg.copy(alpha = 0.7f),
            topLeft = Offset(rect.left - 3 * d, rect.top - 1 * d),
            size = Size(rect.width + 6 * d, rect.height + 2 * d),
            cornerRadius = CornerRadius(4 * d),
        )
        drawText(t, topLeft = rect.topLeft)
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Theme.card.copy(alpha = 0.92f))
            .border(1.dp, Theme.stroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Theme.accent, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun Summary(model: AppModel, markers: List<MapMarker>) {
    val onMap = markers.sumOf { it.calls.size }
    val calls = model.calls
    val missing = calls.filter { GridMath.mapKey(it.grid) == null }
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val style = Theme.mono(10)
        if (calls.isEmpty()) {
            Text("Stations appear here when they send a grid.", style = style, color = Theme.dim, maxLines = 1)
        } else {
            Text("$onMap on map", style = style, color = Theme.dim, maxLines = 1)
            if (missing.isNotEmpty()) {
                Box {
                    Row(
                        Modifier.clickable { menu = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = Theme.accent, modifier = Modifier.size(12.dp))
                        Text("${missing.size} without grid", style = style, color = Theme.accent, maxLines = 1)
                    }
                    FireflyMenu(menu, { menu = false }) {
                        MenuHeader("Ask for a grid")
                        missing.take(30).forEach { cd ->
                            MenuItem("${cd.call} GRID?") {
                                menu = false
                                model.selectedCallsign = cd.call
                                model.send("GRID?")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (calls.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("15m" to Theme.rx, "1h" to Theme.accent, "older" to Theme.dim).forEach { (name, color) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(color, CircleShape))
                        Text(name, style = style, color = Theme.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickedPanel(model: AppModel, m: MapMarker, myGrid: String, onSelect: (() -> Unit)?, onClose: () -> Unit) {
    val now = rememberNow(5000)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.id, style = Theme.mono(12, Bold), color = Theme.text)
            GridMath.distanceBearing(myGrid, m.id)?.let { db ->
                Text("${db.km.toInt()} km  ${db.bearing.toInt()}°", style = Theme.mono(11), color = Theme.dim)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Cancel, "Close", tint = Theme.faint, modifier = Modifier.size(20.dp).clickable(onClick = onClose))
        }
        Column(
            Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            m.calls.forEach { cd -> StationListRow(model, cd, now, onSelect) }
        }
    }
}

