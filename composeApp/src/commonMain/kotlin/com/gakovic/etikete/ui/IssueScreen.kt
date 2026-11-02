package com.gakovic.etikete.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gakovic.etikete.Api
import com.gakovic.etikete.Nav
import com.gakovic.etikete.currentLocation
import com.gakovic.etikete.fmt
import com.gakovic.etikete.rememberPhotoCapture
import com.gakovic.etikete.Prefs
import com.gakovic.etikete.objects
import com.gakovic.etikete.prettyTime
import com.gakovic.etikete.record
import com.gakovic.etikete.str
import kotlinx.coroutines.launch
import com.gakovic.etikete.json.JSONObject

val ISSUE_CATEGORIES = listOf(
    "WRONG_PRICE_DISPLAYED" to "Pogrešna cena na etiketi",
    "PRICE_IN_SYSTEM_INCORRECT" to "Pogrešna cena u sistemu",
    "DISPLAY_BROKEN" to "Ekran oštećen / ne radi",
    "LABEL_NOT_UPDATING" to "Etiketa se ne osvežava",
    "OTHER" to "Drugo",
)

fun categoryText(c: String?) = ISSUE_CATEGORIES.firstOrNull { it.first == c }?.second ?: (c ?: "")

@Composable
fun IssueScreen() {
    var creating by remember { mutableStateOf(Nav.issueTag != null) }
    if (creating) {
        NewIssue(initialTag = Nav.issueTag ?: "", onDone = { Nav.issueTag = null; creating = false })
    } else {
        IssueList(onNew = { creating = true })
    }
}

@Composable
private fun IssueList(onNew: () -> Unit) {
    val store = Prefs.storeId ?: return
    var status by remember { mutableStateOf<String?>("OPEN") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(status, reload) {
        loading = true; error = null
        try {
            items = Api.issues(store, status).sortedByDescending { it.str("created_at") }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(status == "OPEN", { status = "OPEN" }, label = { Text("Otvorene") })
            FilterChip(status == "RESOLVED", { status = "RESOLVED" }, label = { Text("Rešene") })
            FilterChip(status == null, { status = null }, label = { Text("Sve") })
            Spacer(Modifier.weight(1f))
            Button(onClick = onNew) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Nova")
            }
        }
        error?.let { Column(Modifier.padding(horizontal = 12.dp)) { Message(it, Err) } }
        if (loading) Loading()
        LazyColumn(Modifier.fillMaxSize()) {
            if (!loading && items.isEmpty() && error == null) item { Section { Text("Nema prijava.", color = Muted) } }
            items(items, key = { it.optString("id") }) { issue ->
                IssueItem(
                    issue, expanded = openId == issue.optString("id"),
                    onToggle = { openId = if (openId == issue.optString("id")) null else issue.optString("id") },
                    onChanged = { updated -> items = items.map { if (it.optString("id") == updated.optString("id")) updated else it } },
                )
            }
        }
    }
}

@Composable
private fun IssueItem(issue: JSONObject, expanded: Boolean, onToggle: () -> Unit, onChanged: (JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    val open = issue.optString("status") == "OPEN"
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val tagId = issue.optString("tag_id")

    Section(modifier = Modifier.clickable { onToggle() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (open) "otvorena" else "rešena", if (open) Warn else Ok)
            Spacer(Modifier.width(8.dp))
            Text(categoryText(issue.str("category")), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        Text("$tagId · ${prettyTime(issue.str("created_at"))}" + (issue.str("reporter_name")?.let { " · $it" } ?: ""),
            color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        issue.str("description")?.let { Text(it, fontSize = 14.sp, maxLines = if (expanded) 50 else 2) }

        if (expanded) {
            issue.str("photo_url")?.let { url ->
                AsyncImage(url, "Fotografija", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit)
            }
            issue.optJSONObject("diagnostic")?.let { d ->
                HorizontalDivider()
                Text("Dijagnostika", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                val lines = listOfNotNull(
                    "Etiketa: " + (if (d.optBoolean("tag_online")) "online" else "OFFLINE" + (d.str("tag_offline_time")?.let { " od ${prettyTime(it)}" } ?: "")),
                    d.str("tag_battery")?.let { "Baterija: $it%" },
                    d.str("last_ap_name")?.let { "Pristupna tačka: $it" + if (d.optBoolean("last_ap_online")) " (online)" else " (offline)" },
                    d.str("last_update_time")?.let { "Poslednje osvežavanje: ${prettyTime(it)}" + if (d.optBoolean("last_update_succeeded")) " ✓" else " ✗" },
                    d.str("item_name")?.let { "Proizvod: $it" + (d.str("sku")?.let { s -> " ($s)" } ?: "") },
                    d.str("system_price")?.let { "Cena u sistemu: $it" + if (d.optBoolean("price_mismatch")) " - NE SLAŽE SE" else "" },
                    d.str("advice")?.let { "Savet: $it" },
                )
                lines.forEach { Text(it, fontSize = 13.sp) }
            }
            val comments = issue.optJSONArray("comments")?.objects().orEmpty()
            if (comments.isNotEmpty()) {
                HorizontalDivider()
                comments.forEach { cm ->
                    Text("${cm.str("author_name") ?: "?"} · ${prettyTime(cm.str("created_at"))}", color = Muted, fontSize = 12.sp)
                    Text(cm.optString("text"), fontSize = 14.sp)
                }
            }
            OutlinedTextField(comment, { comment = it }, label = { Text("Komentar") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(enabled = !busy && comment.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onChanged(Api.commentIssue(issue.optString("id"), comment, Prefs.reporterName)); comment = ""
                        } catch (e: Exception) {
                            toast(e.message ?: "Greška")
                        } finally { busy = false }
                    }
                }) { Text("Pošalji") }
                if (open) OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onChanged(Api.resolveIssue(issue.optString("id")))
                        } catch (e: Exception) {
                            toast(e.message ?: "Greška (rešavanje može samo menadžer)")
                        } finally { busy = false }
                    }
                }) { Text("Reši", color = Ok) }
            }
        }
    }
}

@Composable
private fun NewIssue(initialTag: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = Prefs.storeId ?: return
    var tag by remember { mutableStateOf(initialTag) }
    var category by remember { mutableStateOf("WRONG_PRICE_DISPLAYED") }
    var desc by remember { mutableStateOf("") }
    var reporter by remember { mutableStateOf(Prefs.reporterName ?: "") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var addGps by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val takePhoto = rememberPhotoCapture { jpeg -> if (jpeg != null) photo = jpeg }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Nova prijava problema") {
                ScanField("Etiketa (tag ID)", tag, { tag = it },
                    onScanClick = { scope.launch { scan()?.let { tag = it } } }, onDone = {})
                Text("Šta je problem?", fontSize = 14.sp)
                ISSUE_CATEGORIES.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { category = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(category == k, { category = k }); Text(label, fontSize = 14.sp)
                    }
                }
                OutlinedTextField(desc, { desc = it }, label = { Text("Opis") }, modifier = Modifier.fillMaxWidth().height(110.dp))
                OutlinedTextField(reporter, { reporter = it }, label = { Text("Prijavio") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { takePhoto() }) {
                        Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp))
                        Text(if (photo == null) "Fotografiši" else "Nova fotografija")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (photo != null) TextButton(onClick = { photo = null }) { Text("Ukloni", color = Err) }
                }
                photo?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Fit) }
                SwitchRow("Dodaj GPS lokaciju u opis", addGps) { addGps = it }
                error?.let { Message(it, Err) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && tag.isNotBlank(),
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val key = photo?.let { jpeg -> Api.uploadIssuePhoto(store, jpeg) }
                                    var text = desc.trim()
                                    if (addGps) runCatching { currentLocation() }.getOrNull()?.let { l ->
                                        text += (if (text.isEmpty()) "" else "\n") +
                                            "GPS: ${l.lat.fmt(6)}, ${l.lon.fmt(6)}" + (l.accuracy?.let { a -> " (±${a.fmt(0)} m)" } ?: "")
                                    }
                                    if (reporter.isNotBlank()) Prefs.reporterName = reporter
                                    Api.createIssue(store, tag.trim(), category, text, reporter, key)
                                    record("prijava", tag.trim(), null, null, true, categoryText(category))
                                    toast("Prijava poslata")
                                    onDone()
                                } catch (e: Exception) {
                                    error = e.message
                                    record("prijava", tag.trim(), null, null, false, e.message)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text(if (busy) "Šaljem…" else "Pošalji prijavu") }
                    TextButton(onClick = onDone) { Text("Otkaži") }
                }
            }
        }
    }
}
