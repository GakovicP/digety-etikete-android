package com.gakovic.etikete.ui

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
        TabRow(selectedTabIndex = sub) {
            Tab(sub == 0, { sub = 0 }, text = { Text("Vezivanje") })
            Tab(sub == 1, { sub = 1 }, text = { Text("Baterija") })
            Tab(sub == 2, { sub = 2 }, text = { Text("Offline") })
        }
        when (sub) {
            0 -> LinkReport()
            1 -> BatteryReport()
            else -> OfflineReport()
        }
    }
}

private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

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
