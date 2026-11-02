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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gakovic.etikete.Nav
import com.gakovic.etikete.Prefs
import com.gakovic.etikete.alarmTypeText
import com.gakovic.etikete.prettyTime
import com.gakovic.etikete.str
import com.gakovic.etikete.tagProductName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AlarmScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = Prefs.storeId ?: return
    var days by remember { mutableIntStateOf(1) }
    var type by remember { mutableStateOf<String?>(null) }   // null | zone | cut | missing
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<String?>(null) }

    suspend fun load(more: Boolean) {
        loading = true; error = null
        try {
            val apiType = if (type == "zone" || type == "cut") type else null
            val res = Api.alarms(store, days, apiType, if (more) cursor else null)
            val filtered = if (type == "missing") res.items.filter { it.optBoolean("tag_missing") } else res.items
            items = if (more) items + filtered else filtered
            cursor = res.next
            info = "Digety: " + res.info
            items.maxOfOrNull { com.gakovic.etikete.parseTime(it.str("start")) ?: 0L }?.let { newest ->
                if (newest > (Prefs.lastAlarmSeen?.toLongOrNull() ?: 0L)) Prefs.lastAlarmSeen = newest.toString()
            }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(days, type, reload) { load(false) }
    LaunchedEffect(live, days, type) {
        while (live) {
            delay(30_000)
            load(false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(days == 1, { days = 1 }, label = { Text("24 h") })
                FilterChip(days == 7, { days = 7 }, label = { Text("7 dana") })
                FilterChip(days == 30, { days = 30 }, label = { Text("30 dana") })
                Spacer(Modifier.width(8.dp))
                FilterChip(type == null, { type = null }, label = { Text("Svi") })
                FilterChip(type == "zone", { type = "zone" }, label = { Text("Zona") })
                FilterChip(type == "cut", { type = "cut" }, label = { Text("Tamper") })
                FilterChip(type == "missing", { type = "missing" }, label = { Text("Nestale") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (loading) "Učitavam…" else "${items.size} alarma${if (cursor != null) "+" else ""}",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                Text("Uživo (30 s)", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(live, { live = it })
            }
            error?.let { Message(it, Err) }
            info?.let { Text(it, color = Muted, fontSize = 11.sp) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (!loading && items.isEmpty() && error == null) item {
                Section {
                    Text("Nema alarma u izabranom periodu.", color = Muted)
                    Text("Ako u Digety dashboard-u vidiš alarme, a ovde ne: proveri da je izabrana ista prodavnica " +
                        "i da tvoj nalog ima pravo da vidi alarme. Red „Digety: …“ iznad pokazuje šta je server vratio.",
                        color = Muted, fontSize = 12.sp)
                }
            }
            items(items) { a -> AlarmItem(a, scope = scope, c = c) }
            if (cursor != null) item {
                OutlinedButton(onClick = { scope.launch { load(true) } }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Učitaj starije")
                }
            }
        }
    }
}

@Composable
private fun AlarmItem(a: JSONObject, scope: kotlinx.coroutines.CoroutineScope, c: android.content.Context) {
    val tag = a.optJSONObject("tag")
    val tagId = tag?.str("id")
    val hasPos = !a.isNull("x") && !a.isNull("y")
    val color = when {
        a.optBoolean("tag_missing") -> Err
        a.str("type") == "cut" -> Err
        else -> Warn
    }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(alarmTypeText(a), color)
            Spacer(Modifier.width(8.dp))
            Text(prettyTime(a.str("start")), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            a.str("zone")?.let { Text(it, color = Muted, fontSize = 13.sp) }
        }
        if (tagId != null) {
            Text(tagId, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            tag?.tagProductName()?.let { Text(it, fontSize = 14.sp) }
        }
        val zones = a.optJSONArray("zones")
        if (zones != null && zones.length() > 1) {
            Text("Zone: " + (0 until zones.length()).joinToString(", ") { zones.optString(it) }, color = Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (hasPos || tagId != null) SmallAction("Mapa", Icons.Filled.Place, Modifier.weight(1f), {
                Nav.showOnMap(tagId, if (hasPos) a.optDouble("x") to a.optDouble("y") else null)
            })
            if (tagId != null) SmallAction("LED", Icons.Filled.FlashOn, Modifier.weight(1f), {
                scope.launch {
                    runCatching { Api.blink(tagId, 15) }.onSuccess { toast(c, "Etiketa treperi") }
                        .onFailure { toast(c, "LED: ${it.message}") }
                }
            })
            if (tagId != null) SmallAction("Prijavi", Icons.Filled.ReportProblem, Modifier.weight(1f), { Nav.reportIssue(tagId) })
        }
    }
}
