package com.gakovic.etikete.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.gakovic.etikete.Api
import com.gakovic.etikete.History
import com.gakovic.etikete.Nav
import com.gakovic.etikete.Prefs
import com.gakovic.etikete.objects
import com.gakovic.etikete.str
import com.gakovic.etikete.tagProductName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen() {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            Tab(sub == 0, { sub = 0 }, text = { Text("Prodavnica (etikete)") })
            Tab(sub == 1, { sub = 1 }, text = { Text("GPS (akcije)") })
        }
        if (sub == 0) StoreMap() else GpsMap()
    }
}

// ====================================================================== mapa prodavnice (BlueIoT)

private data class Zone(val name: String, val type: String, val polygons: List<List<Pair<Double, Double>>>)
private data class Pt(val id: String, val x: Double, val y: Double, val online: Boolean)
private data class MapInfo(
    val storyName: String, val imageUrl: String?, val floor: String?,
    val llx: Double, val lly: Double, val urx: Double, val ury: Double, val zones: List<Zone>,
)

/** WKT "POLYGON((x y, x y, ...))" / "MULTIPOLYGON(...)" -> lista prstenova. */
private fun parseWkt(wkt: String?): List<List<Pair<Double, Double>>> {
    if (wkt.isNullOrBlank()) return emptyList()
    return Regex("\\(([^()]+)\\)").findAll(wkt).map { m ->
        m.groupValues[1].split(",").mapNotNull { p ->
            val n = p.trim().split(Regex("\\s+"))
            if (n.size >= 2) (n[0].toDoubleOrNull() ?: return@mapNotNull null) to (n[1].toDoubleOrNull() ?: return@mapNotNull null) else null
        }
    }.filter { it.size >= 3 }.toList()
}

private fun parseMaps(store: JSONObject): List<MapInfo> =
    store.optJSONArray("stories")?.objects().orEmpty().mapNotNull { st ->
        val m = st.optJSONObject("map") ?: return@mapNotNull null
        val zones = m.optJSONArray("zones")?.objects().orEmpty().map {
            Zone(it.optString("name"), it.optString("type"), parseWkt(it.str("geometry")))
        }
        val b = m.optJSONObject("bounds")
        var llx = b?.optJSONObject("lower_left")?.optDouble("x") ?: Double.NaN
        var lly = b?.optJSONObject("lower_left")?.optDouble("y") ?: Double.NaN
        var urx = b?.optJSONObject("upper_right")?.optDouble("x") ?: Double.NaN
        var ury = b?.optJSONObject("upper_right")?.optDouble("y") ?: Double.NaN
        if (llx.isNaN() || urx.isNaN() || urx <= llx || ury <= lly) {
            // bez granica: izracunaj iz zona
            val all = zones.flatMap { z -> z.polygons.flatten() }
            if (all.isEmpty()) { llx = 0.0; lly = 0.0; urx = 3000.0; ury = 2000.0 } else {
                llx = all.minOf { it.first }; urx = all.maxOf { it.first }
                lly = all.minOf { it.second }; ury = all.maxOf { it.second }
                val px = (urx - llx) * 0.05; val py = (ury - lly) * 0.05
                llx -= px; urx += px; lly -= py; ury += py
            }
        }
        MapInfo(st.optString("name", "Sprat"), m.str("image_url"), m.str("floor"), llx, lly, urx, ury, zones)
    }

private data class Pos(val id: String, val x: Double, val y: Double, val floor: String?, val age: Int, val online: Boolean)

@Composable
private fun StoreMap() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = Prefs.storeId ?: return
    var maps by remember { mutableStateOf<List<MapInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableIntStateOf(0) }
    var positions by remember { mutableStateOf<List<Pos>>(emptyList()) }
    var tagCount by remember { mutableIntStateOf(0) }
    var trails by remember { mutableStateOf<Map<String, List<Pos>>>(emptyMap()) }
    var tripInfo by remember { mutableStateOf<String?>(null) }
    var showStale by remember { mutableStateOf(true) }
    var focusId by remember { mutableStateOf(Nav.mapFocusTag) }
    var focusPt by remember { mutableStateOf<Pt?>(null) }
    var focusInfo by remember { mutableStateOf<String?>(null) }
    val alarmPt = Nav.mapFocusPoint
    var search by remember { mutableStateOf("") }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val tstate = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset += pan
    }

    LaunchedEffect(store) {
        try {
            val s = Api.storeDetails(store)
            maps = s?.let { parseMaps(it) } ?: emptyList()
        } catch (e: Exception) {
            error = e.message; maps = emptyList()
        }
    }
    // poslednje pozicije svih etiketa - stalno, na 10 s
    LaunchedEffect(store) {
        while (true) {
            try {
                val tags = Api.tagPositions(store)
                tagCount = tags.size
                positions = tags.mapNotNull { t ->
                    val l = t.optJSONObject("location")?.optJSONObject("latest_location") ?: return@mapNotNull null
                    if (l.isNull("x") || l.isNull("y")) return@mapNotNull null
                    Pos(t.optString("id"), l.optDouble("x"), l.optDouble("y"), l.str("floor"), l.optInt("age_seconds", 99999), t.optBoolean("online"))
                }
                error = null
            } catch (e: Exception) {
                error = "Pozicije: ${e.message}"
            }
            delay(10_000)
        }
    }
    // kretanja u poslednjih 5 min - stalno, na 5 s
    LaunchedEffect(store) {
        while (true) {
            try {
                val trips = Api.recentTrips(store, 5)
                val byTag = mutableMapOf<String, MutableList<Pos>>()
                trips.forEach { tr ->
                    val id = tr.optString("tag_id")
                    tr.optJSONArray("trip")?.objects().orEmpty().forEach { p ->
                        if (!p.isNull("x") && !p.isNull("y")) byTag.getOrPut(id) { mutableListOf() }
                            .add(Pos(id, p.optDouble("x"), p.optDouble("y"), p.str("floor") ?: tr.str("map"), 0, true))
                    }
                }
                trails = byTag.mapValues { it.value.takeLast(30) }
                tripInfo = null
            } catch (e: Exception) {
                tripInfo = "Kretanja: ${e.message?.take(120)}"
            }
            delay(5_000)
        }
    }
    // izabrana etiketa - na 5 s
    LaunchedEffect(focusId) {
        val id = focusId ?: return@LaunchedEffect
        while (true) {
            try {
                val t = Api.tag(id)
                val l = t?.optJSONObject("location")?.optJSONObject("latest_location")
                if (l == null) {
                    focusPt = null; focusInfo = "Etiketa $id nema poznatu poziciju (nije u BlueIoT sistemu)."
                } else {
                    focusPt = Pt(id, l.optDouble("x"), l.optDouble("y"), t?.optBoolean("online") == true)
                    val age = l.optInt("age_seconds", -1)
                    focusInfo = "$id · ${t?.tagProductName() ?: "nije vezana"}" +
                        (if (age >= 0) " · pozicija stara ${if (age < 120) "$age s" else "${age / 60} min"}" else "")
                }
            } catch (e: Exception) {
                focusInfo = e.message
            }
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section {
            ScanField(
                "Nađi etiketu (tag ID ili SKU)", search, { search = it },
                onScanClick = { scope.launch { scan(c)?.let { search = it; focusId = null; focusId = it } } },
                onDone = {
                    val q = search.trim()
                    if (q.isNotEmpty()) scope.launch {
                        val byTag = runCatching { Api.tag(q) }.getOrNull()
                        if (byTag != null) focusId = q else {
                            val tags = runCatching { Api.tagsBySku(q, store) }.getOrDefault(emptyList())
                            if (tags.isNotEmpty()) focusId = tags.first().optString("id")
                            else toast(c, "Nije pronađena etiketa za \"$q\"")
                        }
                    }
                }
            )
            focusInfo?.let { Message(it, if (focusPt != null) BlueLight else Warn) }
            if (focusId != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { Api.blink(focusId!!, 15) }.onSuccess { toast(c, "Etiketa treperi") }
                            .onFailure { toast(c, "LED: ${it.message}") }
                    }
                }) { Text("Trepni LED") }
                OutlinedButton(onClick = { focusId = null; focusPt = null; focusInfo = null; Nav.mapFocusTag = null }) { Text("Ukloni") }
            }
            if (alarmPt != null) Message("Narandžasta tačka = mesto alarma", Warn)
            error?.let { Message(it, Err) }
        }

        val list = maps
        when {
            list == null -> Loading("Učitavam mapu prodavnice…")
            list.isEmpty() && positions.isEmpty() && trails.isEmpty() -> Section {
                Message(
                    "Za ovu prodavnicu nema mape ni pozicija u Digety-ju. Pozicije etiketa rade samo u prodavnicama " +
                        "sa BlueIoT sidrima (lokalizacijom).", Warn
                )
            }
            else -> {
                if (list.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEachIndexed { i, m -> FilterChip(sel == i, { sel = i }, label = { Text(m.storyName) }) }
                }
                val base = list.getOrNull(sel.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
                // tacke za ovaj sprat (ako sprat nije poznat, prikazi sve)
                fun onFloor(f: String?) = base?.floor == null || f == null || f == base.floor || list.size <= 1
                val pos = positions.filter { onFloor(it.floor) && (showStale || it.age <= 300) }
                val moving = trails.filterValues { pts -> pts.isNotEmpty() && onFloor(pts.last().floor) }
                val allPts = pos.map { it.x to it.y } + moving.values.flatten().map { it.x to it.y }

                // granice prikaza: mapa; ako je vecina tacaka van mape - prosiri na tacke
                var llx = base?.llx ?: 0.0; var lly = base?.lly ?: 0.0; var urx = base?.urx ?: 1.0; var ury = base?.ury ?: 1.0
                val inside = allPts.count { (x, y) -> x in llx..urx && y in lly..ury }
                val expanded = allPts.isNotEmpty() && (base == null || inside < allPts.size / 2)
                if (expanded) {
                    llx = minOf(if (base != null) llx else Double.MAX_VALUE, allPts.minOf { it.first })
                    urx = maxOf(if (base != null) urx else -Double.MAX_VALUE, allPts.maxOf { it.first })
                    lly = minOf(if (base != null) lly else Double.MAX_VALUE, allPts.minOf { it.second })
                    ury = maxOf(if (base != null) ury else -Double.MAX_VALUE, allPts.maxOf { it.second })
                    val px = (urx - llx).coerceAtLeast(100.0) * 0.05; val py = (ury - lly).coerceAtLeast(100.0) * 0.05
                    llx -= px; urx += px; lly -= py; ury += py
                }
                val w = (urx - llx).coerceAtLeast(1.0); val h = (ury - lly).coerceAtLeast(1.0)

                Box(
                    Modifier.fillMaxWidth().padding(12.dp)
                        .aspectRatio((w / h).toFloat().coerceIn(0.3f, 4f))
                        .clipToBounds()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFE3E6EA), RoundedCornerShape(8.dp))
                        .transformable(tstate)
                ) {
                    Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
                        base?.imageUrl?.let { url -> if (!expanded) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        Canvas(Modifier.fillMaxSize()) {
                            fun px(x: Double) = ((x - llx) / w * size.width).toFloat()
                            fun py(y: Double) = ((1 - (y - lly) / h) * size.height).toFloat()
                            val label = Paint().apply { textSize = 30f / scale.coerceAtLeast(1f); typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                            base?.zones?.forEach { z ->
                                val col = when (z.type) { "alarm" -> Err; "department" -> BlueLight; else -> Muted }
                                z.polygons.forEach { ring ->
                                    val path = Path()
                                    ring.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y)) }
                                    path.close()
                                    drawPath(path, col.copy(alpha = 0.08f))
                                    drawPath(path, col, style = Stroke(width = 2f / scale))
                                    val cx = ring.map { px(it.first) }.average().toFloat()
                                    val cy = ring.map { py(it.second) }.average().toFloat()
                                    label.color = col.toArgb()
                                    drawContext.canvas.nativeCanvas.drawText(z.name, cx - label.measureText(z.name) / 2, cy, label)
                                }
                            }
                            // sve etikete: plavo = sveza pozicija (do 5 min), sivo = starija
                            pos.forEach { p ->
                                val fresh = p.age <= 300
                                drawCircle(if (fresh) BlueLight else Muted.copy(alpha = 0.6f), radius = (if (fresh) 6f else 4f) / scale, center = Offset(px(p.x), py(p.y)))
                            }
                            // etikete u pokretu: zelena putanja + tacka
                            moving.values.forEach { pts ->
                                for (i in 1 until pts.size) {
                                    drawLine(Ok.copy(alpha = 0.5f), Offset(px(pts[i - 1].x), py(pts[i - 1].y)), Offset(px(pts[i].x), py(pts[i].y)), strokeWidth = 3f / scale)
                                }
                                val last = pts.last()
                                drawCircle(Ok.copy(alpha = 0.25f), radius = 18f / scale, center = Offset(px(last.x), py(last.y)))
                                drawCircle(Ok, radius = 8f / scale, center = Offset(px(last.x), py(last.y)))
                            }
                            alarmPt?.let { (x, y) ->
                                drawCircle(Warn.copy(alpha = 0.3f), radius = 30f / scale, center = Offset(px(x), py(y)))
                                drawCircle(Warn, radius = 10f / scale, center = Offset(px(x), py(y)))
                            }
                            focusPt?.let { p ->
                                drawCircle(Err.copy(alpha = 0.25f), radius = 34f / scale, center = Offset(px(p.x), py(p.y)))
                                drawCircle(Err, radius = 11f / scale, center = Offset(px(p.x), py(p.y)))
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● u pokretu  ", color = Ok, fontSize = 12.sp)
                        Text("● sveža poz.  ", color = BlueLight, fontSize = 12.sp)
                        Text("● starija poz.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Resetuj zum") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Prikaži i starije pozicije", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(showStale, { showStale = it })
                    }
                    Text(
                        "Digety: $tagCount etiketa · ${positions.size} sa pozicijom · ${positions.count { it.age <= 300 }} svežih · " +
                            "${trails.size} u pokretu (5 min)" + (if (expanded) " · pozicije su van granica plana - prikaz proširen" else ""),
                        color = Muted, fontSize = 11.sp
                    )
                    tripInfo?.let { Text(it, color = Warn, fontSize = 11.sp) }
                    Text("Osvežava se automatski: kretanja na 5 s, sve pozicije na 10 s.", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.padding(8.dp))
            }
        }
    }
}

// ====================================================================== GPS mapa (gde je sta radjeno)

@Composable
private fun GpsMap() {
    val c = LocalContext.current
    var days by remember { mutableIntStateOf(1) }
    var storeLoc by remember { mutableStateOf<GeoPoint?>(null) }
    val store = Prefs.storeId

    LaunchedEffect(store) {
        if (store != null) runCatching {
            val l = Api.storeDetails(store)?.optJSONObject("location")
            if (l != null && l.has("lat")) storeLoc = GeoPoint(l.optDouble("lat"), l.optDouble("lon"))
        }
    }
    val since = System.currentTimeMillis() - days * 86_400_000L
    val entries = remember(days) { History.all().filter { it.time >= since && it.lat != null && it.lon != null } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(days == 1, { days = 1 }, label = { Text("Danas") })
            FilterChip(days == 7, { days = 7 }, label = { Text("7 dana") })
            FilterChip(days == 3650, { days = 3650 }, label = { Text("Sve") })
            Spacer(Modifier.width(4.dp))
            Text("${entries.size} akcija", color = Muted, fontSize = 13.sp)
        }
        val mapView = remember {
            MapView(c).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(17.0)
            }
        }
        DisposableEffect(Unit) {
            mapView.onResume()
            onDispose { mapView.onPause(); mapView.onDetach() }
        }
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.overlays.clear()
                if (com.gakovic.etikete.Geo.hasPermission(c)) {
                    val me = MyLocationNewOverlay(GpsMyLocationProvider(c), mv)
                    me.enableMyLocation()
                    mv.overlays.add(me)
                }
                storeLoc?.let { p ->
                    mv.overlays.add(Marker(mv).apply {
                        position = p; title = Prefs.storeName ?: "Prodavnica"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                entries.take(500).forEach { e ->
                    mv.overlays.add(Marker(mv).apply {
                        position = GeoPoint(e.lat!!, e.lon!!)
                        title = "${e.action}: ${e.tag}" + (e.product?.let { " → $it" } ?: "")
                        snippet = e.timeText + (if (e.ok) "" else " · GREŠKA: ${e.message ?: ""}") +
                            (e.user?.let { " · $it" } ?: "") + (e.accuracy?.let { " · ±${it.toInt()} m" } ?: "")
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                val center = entries.firstOrNull()?.let { GeoPoint(it.lat!!, it.lon!!) } ?: storeLoc
                center?.let { mv.controller.setCenter(it) }
                mv.invalidate()
            }
        )
    }
}
