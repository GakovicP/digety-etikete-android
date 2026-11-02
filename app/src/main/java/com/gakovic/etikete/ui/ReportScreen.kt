package com.gakovic.etikete.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakovic.etikete.Api
import com.gakovic.etikete.History
import com.gakovic.etikete.Nav
import com.gakovic.etikete.Prefs
import com.gakovic.etikete.prettyTime
import com.gakovic.etikete.str
import com.gakovic.etikete.tagProductName
import com.gakovic.etikete.tagSku
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportScreen() {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = sub, edgePadding = 8.dp) {
            Tab(sub == 0, { sub = 0 }, text = { Text("Vezano (sve)") })
            Tab(sub == 1, { sub = 1 }, text = { Text("Sa telefona") })
            Tab(sub == 2, { sub = 2 }, text = { Text("Baterija") })
            Tab(sub == 3, { sub = 3 }, text = { Text("Offline") })
        }
        when (sub) {
            0 -> DigetyLinkReport()
            1 -> LinkReport()
            2 -> BatteryReport()
            else -> OfflineReport()
        }
    }
}

private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

// ---------------------------------------------------------------------- sve vezano u Digety-ju (svi izvori)

@Composable
private fun DigetyLinkReport() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = Prefs.storeId ?: return
    var counts by remember { mutableStateOf<JSONObject?>(null) }
    var perDay by remember { mutableStateOf<List<Pair<String, Int?>>?>(null) }
    var dayOpen by remember { mutableStateOf<String?>(null) }
    var dayItems by remember { mutableStateOf<List<JSONObject>?>(null) }
    var dayLoading by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf<List<JSONObject>?>(null) }
    var tagsLoading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(store) {
        try { counts = Api.inventoryCounts(store) } catch (e: Exception) { error = e.message }
        try { perDay = Api.linkCountsPerDay(store, 14) } catch (e: Exception) { perDay = emptyList(); error = error ?: e.message }
    }

    val linked = tags.orEmpty().filter { (it.optJSONArray("skus")?.length() ?: 0) > 0 }
    fun department(t: JSONObject): String =
        t.optJSONArray("items")?.optJSONObject(0)?.str("department") ?: "(bez odeljenja)"
    val q = search.trim()
    val shown = if (q.isEmpty()) linked else linked.filter { t ->
        t.optString("id").contains(q, true) || (t.tagSku()?.contains(q, true) == true) ||
            (t.tagProductName()?.contains(q, true) == true) ||
            (t.optJSONArray("skus")?.optJSONObject(0)?.optJSONObject("sku")?.optJSONArray("barcodes")?.toString()?.contains(q) == true)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Stanje u Digety-ju (sve, bez obzira odakle je vezano)") {
                error?.let { Message(it, Err) }
                val cnt = counts
                if (cnt == null) Loading() else {
                    val v = cnt.optJSONObject("vezane"); val n = cnt.optJSONObject("nevezane")
                    val vOn = v?.optInt("online") ?: 0; val vOff = v?.optInt("offline") ?: 0
                    val nOn = n?.optInt("online") ?: 0; val nOff = n?.optInt("offline") ?: 0
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("${vOn + vOff}", "vezanih etiketa", Ok, Modifier.weight(1f))
                        Stat("${nOn + nOff}", "nevezanih", Muted, Modifier.weight(1f))
                    }
                    Text("Vezane: $vOn online / $vOff offline · nevezane: $nOn online / $nOff offline", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Section("Vezivanja po danima (Digety, svi izvori)") {
                val pd = perDay
                when {
                    pd == null -> Loading()
                    pd.all { it.second == null } -> Text("Digety nije vratio brojeve po danima za ovaj nalog.", color = Muted, fontSize = 13.sp)
                    else -> pd.forEach { (d, n) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (dayOpen == d) { dayOpen = null; return@clickable }
                                dayOpen = d; dayItems = null; dayLoading = true
                                scope.launch {
                                    try { dayItems = Api.linkUpdates(store, d) } catch (e: Exception) { toast(c, e.message ?: "Greška"); dayItems = emptyList() }
                                    finally { dayLoading = false }
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(d, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text(n?.toString() ?: "-", fontWeight = FontWeight.SemiBold, color = if ((n ?: 0) > 0) Ok else Muted)
                            Text(if (dayOpen == d) "  ▲" else "  ▼", color = Muted)
                        }
                        if (dayOpen == d) {
                            if (dayLoading) Loading()
                            dayItems?.let { list ->
                                if (list.isEmpty()) Text("Nema detalja za ovaj dan.", color = Muted, fontSize = 12.sp)
                                else {
                                    OutlinedButton(onClick = { shareText(c, "vezivanja_$d.csv", updatesCsv(list)) }) { Text("CSV za $d (${list.size})") }
                                    list.take(200).forEach { u ->
                                        val t = u.optJSONObject("tag")
                                        Text("${prettyTime(u.str("end_time") ?: u.str("start_time"))} · ${u.optString("tag_id")}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                        Text(t?.tagProductName() ?: "(trenutno nevezana)", fontSize = 12.sp,
                                            color = if (u.optInt("status_code") == 200) androidx.compose.ui.graphics.Color.Unspecified else Err)
                                    }
                                }
                            }
                        }
                    }
                }
                Text("Broji uspešne promene „etiketa → proizvod“ (vezivanja i odvezivanja) iz svih izvora: telefon, računar, Digety dashboard.", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Section("Sve vezane etikete") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !tagsLoading, onClick = {
                        tagsLoading = true; error = null
                        scope.launch {
                            try { tags = Api.linkedTags(store) } catch (e: Exception) { error = e.message } finally { tagsLoading = false }
                        }
                    }) { Text(if (tags == null) "Učitaj listu" else "Osveži") }
                    if (linked.isNotEmpty()) OutlinedButton(onClick = { shareText(c, "vezane_etikete_${stamp()}.csv", linkedCsv(linked)) }) { Text("CSV (${linked.size})") }
                }
                if (tagsLoading) Loading("Učitavam sve etikete prodavnice…")
                if (tags != null) {
                    Text("${linked.size} vezanih od ${tags!!.size} etiketa", color = Muted, fontSize = 13.sp)
                    val byDep = linked.groupBy { department(it) }.toList().sortedByDescending { it.second.size }
                    if (byDep.size > 1) byDep.take(15).forEach { (d, l) ->
                        Row { Text(d, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("${l.size}", fontSize = 13.sp) }
                    }
                    androidx.compose.material3.OutlinedTextField(search, { search = it }, label = { Text("Pretraga: tag, SKU, barkod, naziv") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        items(shown.take(300)) { t ->
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.optString("id"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Pill(if (t.optBoolean("online")) "online" else "offline", if (t.optBoolean("online")) Ok else Err)
                }
                Text(t.tagProductName() ?: "", fontSize = 13.sp)
                Text(listOfNotNull(t.tagSku()?.let { "SKU $it" }, department(t)).joinToString(" · "), color = Muted, fontSize = 12.sp)
            }
        }
        if (shown.size > 300) item { Text("Prikazano prvih 300 od ${shown.size} - suzi pretragu ili izvezi CSV.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
    }
}

private fun linkedCsv(list: List<JSONObject>): String {
    val sb = StringBuilder("\uFEFFtag;sku;barkod;proizvod;odeljenje;online;baterija\n")
    list.forEach { t ->
        val sku = t.optJSONArray("skus")?.optJSONObject(0)?.optJSONObject("sku")
        val bc = sku?.optJSONArray("barcodes")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } } ?: ""
        sb.append(listOf(
            t.optString("id"), t.tagSku() ?: "", bc,
            "\"" + (t.tagProductName() ?: "").replace("\"", "\"\"") + "\"",
            t.optJSONArray("items")?.optJSONObject(0)?.str("department") ?: "",
            if (t.optBoolean("online")) "DA" else "NE", if (t.isNull("battery")) "" else t.optInt("battery").toString()
        ).joinToString(";")).append('\n')
    }
    return sb.toString()
}

private fun updatesCsv(list: List<JSONObject>): String {
    val sb = StringBuilder("\uFEFFvreme;tag;status;trenutni_proizvod;sku\n")
    list.forEach { u ->
        val t = u.optJSONObject("tag")
        sb.append(listOf(
            prettyTime(u.str("end_time") ?: u.str("start_time")), u.optString("tag_id"), u.optInt("status_code").toString(),
            "\"" + (t?.tagProductName() ?: "").replace("\"", "\"\"") + "\"", t?.tagSku() ?: ""
        ).joinToString(";")).append('\n')
    }
    return sb.toString()
}

// ---------------------------------------------------------------------- izvestaj o vezivanju (sa telefona)

@Composable
private fun LinkReport() {
    val c = LocalContext.current
    var days by remember { mutableIntStateOf(1) }
    var version by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val since = if (days >= 3650) 0L else {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
        cal.timeInMillis - (days - 1) * 86_400_000L
    }
    val list = remember(days, version) { History.all().filter { it.time >= since && (Prefs.storeName == null || it.store == null || it.store == Prefs.storeName) } }
    val links = list.filter { it.action == "vezivanje" }
    val ok = links.count { it.ok }
    val fail = links.size - ok
    val unlinks = list.count { it.action == "odvezivanje" && it.ok }
    val issues = list.count { it.action == "prijava" && it.ok }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(days == 1, { days = 1 }, label = { Text("Danas") })
                FilterChip(days == 7, { days = 7 }, label = { Text("7 dana") })
                FilterChip(days == 30, { days = 30 }, label = { Text("30 dana") })
                FilterChip(days == 3650, { days = 3650 }, label = { Text("Sve") })
            }
        }
        item {
            Section("Pregled") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("$ok", "povezano", Ok, Modifier.weight(1f))
                    Stat("$fail", "grešaka", if (fail > 0) Err else Muted, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("$unlinks", "odvezano", modifier = Modifier.weight(1f))
                    Stat("$issues", "prijava", modifier = Modifier.weight(1f))
                }
                val withGps = list.count { it.lat != null }
                Text("Sa GPS lokacijom: $withGps od ${list.size} akcija", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareText(c, "izvestaj_${stamp()}.csv", History.toCsv(list)) }, enabled = list.isNotEmpty()) { Text("Izvezi CSV") }
                    OutlinedButton(onClick = { Nav.tab = com.gakovic.etikete.Tab.MAP }) { Text("GPS mapa") }
                }
            }
        }
        // po danima
        val byDay = links.groupBy { it.day }.toSortedMap(compareByDescending { it })
        if (byDay.size > 1) item {
            Section("Po danima") {
                byDay.forEach { (d, l) ->
                    Row { Text(d, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)); Text("${l.count { it.ok }} ✓   ${l.count { !it.ok }} ✗") }
                }
            }
        }
        // po korisniku
        val byUser = links.groupBy { it.user ?: "?" }
        if (byUser.size > 1) item {
            Section("Po radniku") {
                byUser.forEach { (u, l) -> Row { Text(u, modifier = Modifier.weight(1f)); Text("${l.count { it.ok }} ✓   ${l.count { !it.ok }} ✗") } }
            }
        }
        val errors = links.filter { !it.ok }
        if (errors.isNotEmpty()) item {
            Section("Greške (${errors.size})") {
                errors.take(100).forEach { e ->
                    Text("${e.timeText} · ${e.tag} → ${e.product ?: ""}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(e.message ?: "", color = Err, fontSize = 12.sp)
                }
            }
        }
        item {
            Section("Poslednje akcije") {
                if (list.isEmpty()) Text("Nema akcija u ovom periodu.", color = Muted)
                list.take(50).forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(e.action, if (e.ok) Ok else Err)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("${e.tag}${e.product?.let { " → $it" } ?: ""}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(e.timeText + (e.user?.let { " · $it" } ?: "") + (if (e.lat != null) " · GPS" else ""), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(12.dp)) { Text("Obriši istoriju na telefonu", color = Err) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Obrisati istoriju?") },
        text = { Text("Briše se samo lokalni izveštaj na ovom telefonu. Vezivanja u Digety-ju ostaju.") },
        confirmButton = { TextButton(onClick = { History.clear(); version++; confirmClear = false }) { Text("Obriši", color = Err) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Otkaži") } },
    )
}

// ---------------------------------------------------------------------- slaba baterija

@Composable
private fun BatteryReport() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = Prefs.storeId ?: return
    var threshold by remember { mutableIntStateOf(25) }
    var tags by remember { mutableStateOf<List<JSONObject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                tags = Api.lowBattery(store, threshold).sortedBy { it.optInt("battery", 100) }
            } catch (e: Exception) {
                error = e.message
            } finally { loading = false }
        }
    }
    androidx.compose.runtime.LaunchedEffect(threshold) { load() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Baterija ≤", fontSize = 14.sp)
                listOf(10, 25, 40).forEach { t -> FilterChip(threshold == t, { threshold = t }, label = { Text("$t%") }) }
                Spacer(Modifier.weight(1f))
                val list = tags
                if (list != null && list.isNotEmpty()) OutlinedButton(onClick = {
                    shareText(c, "baterija_${stamp()}.csv", tagCsv(list))
                }) { Text("CSV") }
            }
            error?.let { Column(Modifier.padding(horizontal = 12.dp)) { Message(it, Err) } }
            if (loading) Loading()
            tags?.let { Text("${it.size} etiketa", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        }
        items(tags.orEmpty()) { t -> TagRow(t) }
    }
}

// ---------------------------------------------------------------------- offline etikete

@Composable
private fun OfflineReport() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = Prefs.storeId ?: return
    var all by remember { mutableStateOf<List<JSONObject>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val offline = all.orEmpty().filter { !it.optBoolean("online") }.sortedBy { it.str("offline_time") ?: "" }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section {
                Text("Učitava status svih etiketa prodavnice - kod velikog broja može da potraje.", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !loading, onClick = {
                        loading = true; error = null
                        scope.launch {
                            try { all = Api.tagStatus(store) } catch (e: Exception) { error = e.message } finally { loading = false }
                        }
                    }) { Text(if (all == null) "Učitaj" else "Osveži") }
                    if (offline.isNotEmpty()) OutlinedButton(onClick = { shareText(c, "offline_${stamp()}.csv", tagCsv(offline)) }) { Text("CSV") }
                }
                if (loading) Loading()
                error?.let { Message(it, Err) }
                all?.let { list ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("${list.size - offline.size}", "online", Ok, Modifier.weight(1f))
                        Stat("${offline.size}", "offline", if (offline.isEmpty()) Muted else Err, Modifier.weight(1f))
                    }
                }
            }
        }
        items(offline.take(1000)) { t -> TagRow(t) }
    }
}

@Composable
private fun TagRow(t: JSONObject) {
    val battery = if (t.isNull("battery")) null else t.optInt("battery")
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.optString("id"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill(battery?.let { "$it%" } ?: "?", batteryColor(battery))
            Spacer(Modifier.width(6.dp))
            Pill(if (t.optBoolean("online")) "online" else "offline", if (t.optBoolean("online")) Ok else Err)
        }
        val name = t.tagProductName()
        Text(name ?: "nije vezana", color = if (name == null) Muted else androidx.compose.ui.graphics.Color.Unspecified, fontSize = 13.sp)
        t.str("offline_time")?.let { if (!t.optBoolean("online")) Text("offline od ${prettyTime(it)}", color = Err, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { Nav.showOnMap(t.optString("id")) }) { Text("Mapa") }
            TextButton(onClick = { Nav.reportIssue(t.optString("id")) }) { Text("Prijavi") }
        }
    }
}

private fun tagCsv(list: List<JSONObject>): String {
    val sb = StringBuilder("﻿tag;baterija;online;offline_od;sku;proizvod\n")
    list.forEach { t ->
        sb.append(listOf(
            t.optString("id"), if (t.isNull("battery")) "" else t.optInt("battery").toString(),
            if (t.optBoolean("online")) "DA" else "NE", t.str("offline_time") ?: "",
            t.tagSku() ?: "", "\"" + (t.tagProductName() ?: "").replace("\"", "\"\"") + "\""
        ).joinToString(";")).append('\n')
    }
    return sb.toString()
}
