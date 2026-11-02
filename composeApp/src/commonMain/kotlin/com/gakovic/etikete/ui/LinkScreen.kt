package com.gakovic.etikete.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gakovic.etikete.Api
import com.gakovic.etikete.ApiException
import com.gakovic.etikete.Nav
import com.gakovic.etikete.Prefs
import com.gakovic.etikete.record
import com.gakovic.etikete.str
import com.gakovic.etikete.tagPrice
import com.gakovic.etikete.tagProductName
import com.gakovic.etikete.tagSku
import kotlinx.coroutines.launch
import com.gakovic.etikete.json.JSONObject

private data class SessionLink(val tag: String, val product: String, val ok: Boolean, val msg: String)

@Composable
fun LinkScreen() {
    val scope = rememberCoroutineScope()
    var tag by remember { mutableStateOf("") }
    var product by remember { mutableStateOf("") }
    var match by remember { mutableStateOf<Api.ProductMatch?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Api.ProductDetails?>(null) }
    var detailsFor by remember { mutableStateOf<String?>(null) }
    var tagInfo by remember { mutableStateOf<JSONObject?>(null) }
    var tagError by remember { mutableStateOf<String?>(null) }
    var loadingTag by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean?, String>?>(null) }
    var confirmDetach by remember { mutableStateOf(false) }
    var usedCamera by remember { mutableStateOf(false) }
    val session = remember { mutableStateListOf<SessionLink>() }
    val tagFocus = remember { FocusRequester() }
    val productFocus = remember { FocusRequester() }

    /** Proverava da li etiketa postoji u Digety-ju; puni tagInfo / tagError. */
    suspend fun checkTag(id: String): Boolean {
        loadingTag = true; tagError = null; tagInfo = null
        try {
            // bez obzira na velika/mala slova; u polje upisi tacan ID iz Digety-ja
            val t = Api.findTag(id)
            if (t == null) tagError = "Etiketa $id NE POSTOJI u Digety-ju." else {
                tagInfo = t
                t.str("id")?.let { real -> if (tag.trim().equals(real, ignoreCase = true)) tag = real }
            }
        } catch (e: ApiException) {
            tagError = if (e.notFound) "Etiketa $id NE POSTOJI u Digety-ju (nije registrovana na vaš nalog)." else e.message
        } catch (e: Exception) {
            tagError = e.message
        } finally {
            loadingTag = false
        }
        if (tagInfo == null) buzz(error = true) else buzz()
        return tagInfo != null
    }

    fun loadTag(then: (() -> Unit)? = null) {
        val id = tag.trim()
        if (id.isEmpty()) return
        result = null
        scope.launch { if (checkTag(id)) then?.invoke() }
    }

    suspend fun resolve(p: String): Api.ProductMatch {
        match?.takeIf { it.query == p }?.let { return it }
        resolving = true
        try {
            val m = Api.resolveProduct(p)
            match = m
            // kartica proizvoda (podaci + slika etikete)
            if (m.mode == "sku" && detailsFor != m.key) {
                details = null
                detailsFor = m.key
                details = runCatching { Api.productDetails(m.key, Prefs.storeId, tagInfo?.str("id")) }.getOrNull()
            } else if (m.mode != "sku") {
                details = null; detailsFor = null
            }
            return m
        } finally {
            resolving = false
        }
    }

    fun lookupProduct() {
        val p = product.trim()
        if (p.isEmpty()) return
        scope.launch {
            runCatching { resolve(p) }
            if (match?.mode == "sku") buzz() else buzz(error = true)
        }
    }

    fun scanProduct() {
        scope.launch {
            val v = scan() ?: return@launch
            product = v; usedCamera = true; match = null
            lookupProduct()
        }
    }

    fun scanTag() {
        scope.launch {
            val v = scan() ?: return@launch
            tag = v; usedCamera = true
            // bez automatskog otvaranja kamere za proizvod - korisnik sam klikne skeniranje
            loadTag { runCatching { productFocus.requestFocus() } }
        }
    }

    fun link() {
        val t = tag.trim(); val p = product.trim()
        if (t.isEmpty() || p.isEmpty()) return
        busy = true; result = null
        scope.launch {
            // 1) etiketa mora da postoji
            if (tagInfo?.optString("id")?.equals(t, ignoreCase = true) != true && !checkTag(t)) {
                result = false to (tagError ?: "Etiketa $t ne postoji.")
                busy = false
                return@launch
            }
            if (tagInfo == null) {
                result = false to (tagError ?: "Etiketa $t ne postoji.")
                busy = false
                return@launch
            }
            // 2) proizvod: SKU -> ID artikla -> barkod
            var ok = false
            var uncertain = false
            var msg = ""
            var mode = "barcode"
            var key = p
            val realTag = tagInfo?.str("id") ?: t
            try {
                val m = resolve(p)
                mode = m.mode; key = m.key
                Api.attach(realTag, key, mode)
                ok = true
                msg = "Povezano: $realTag → " + (m.name ?: p) + (if (m.via != "barkod") " (${m.via})" else "")
            } catch (e: ApiException) {
                msg = when {
                    e.uncertain -> e.message ?: "Server nije odgovorio."
                    e.notFound -> "Proizvod $p nije pronađen ni kao barkod, SKU ni ID artikla."
                    else -> e.message ?: "Greška"
                }
                uncertain = e.uncertain
            } catch (e: Exception) {
                msg = e.message ?: "Greška"
            }
            record("vezivanje", realTag, key, mode, ok, if (ok) null else msg)
            session.add(0, SessionLink(realTag, if (key == p) p else "$p → $key", ok, msg))
            if (session.size > 30) session.removeAt(session.size - 1)
            result = (if (ok) true else if (uncertain) null else false) to msg
            if (ok) buzz() else buzz(error = true)
            busy = false
            if (ok) {
                // osvezi prikaz etikete
                runCatching { tagInfo = Api.tag(realTag) }
                if (Prefs.continuous) {
                    tag = ""; product = ""; tagInfo = null; match = null; details = null; detailsFor = null
                    runCatching { tagFocus.requestFocus() }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { tagFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section {
            ScanField(
                label = "1. Etiketa (tag ID)", value = tag,
                onValue = { tag = it; tagInfo = null; tagError = null },
                onScanClick = { scanTag() },
                onDone = { loadTag { runCatching { productFocus.requestFocus() } } },
                focus = tagFocus,
            )
            // status etikete odmah ispod polja
            when {
                loadingTag -> Text("Proveravam etiketu u Digety-ju…", color = Muted, fontSize = 13.sp)
                tagError != null -> Message("✗ " + tagError!!, Err)
                tagInfo != null -> Message(
                    "✓ Etiketa postoji" + (tagInfo!!.tagProductName()?.let { " · trenutno: $it" } ?: " · nije vezana"),
                    Ok
                )
            }
            ScanField(
                label = "2. Proizvod (barkod, SKU ili ID)", value = product,
                onValue = { product = it; if (match?.query != it.trim()) { match = null; details = null; detailsFor = null } },
                onScanClick = { scanProduct() },
                // Enter / rucni skener: prvo prikazi proizvod, vezivanje ide na dugme "Poveži"
                onDone = { lookupProduct() },
                focus = productFocus,
            )
            when {
                resolving -> Text("Tražim proizvod…", color = Muted, fontSize = 13.sp)
                match != null && match!!.mode == "sku" && details != null -> ProductCard(details!!, match!!.via)
                match != null && match!!.mode == "sku" -> Message(
                    "✓ " + (match!!.name ?: match!!.key) + " · SKU ${match!!.key}" +
                        (match!!.price?.let { " · $it" } ?: "") + " (pronađen kao ${match!!.via})", Ok
                )
                match != null -> Message(
                    "Proizvod nije pronađen kao SKU ni ID artikla. Ako je ${match!!.key} barkod, Digety ga proverava tek " +
                        "pri vezivanju (Digety nema pretragu po barkodu) - posle vezivanja proizvod se vidi na kartici etikete.", Warn
                )
            }
            Button(
                onClick = { link() },
                enabled = !busy && tag.isNotBlank() && product.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlueLight)
            ) { Text(if (busy) "Povezujem…" else "Poveži", fontSize = 17.sp) }

            result?.let { (ok, msg) ->
                Message(msg, when (ok) { true -> Ok; false -> Err; null -> Warn })
            }
        }

        tagInfo?.let { t ->
            TagCard(
                t,
                onBlink = {
                    scope.launch {
                        try {
                            Api.blink(t.optString("id"), 10)
                            record("led", t.optString("id"), null, null, true, null)
                            toast("Etiketa treperi 10 s")
                        } catch (e: Exception) {
                            toast("LED: ${e.message}")
                        }
                    }
                },
                onDetach = { confirmDetach = true },
                onIssue = { Nav.reportIssue(t.optString("id")) },
            )
        }

        if (session.isNotEmpty()) {
            Section("Ova smena (${session.count { it.ok }} ✓ / ${session.count { !it.ok }} ✗)") {
                session.take(15).forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (s.ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null,
                            tint = if (s.ok) Ok else Err, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${s.tag} → ${s.product}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            if (!s.ok) Text(s.msg, color = Err, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (confirmDetach) {
        val id = tagInfo?.optString("id") ?: tag
        AlertDialog(
            onDismissRequest = { confirmDetach = false },
            title = { Text("Odveži etiketu?") },
            text = { Text("Sa etikete $id se skida proizvod u Digety sistemu.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDetach = false
                    scope.launch {
                        try {
                            Api.detach(id)
                            record("odvezivanje", id, tagInfo?.tagSku(), null, true, null)
                            result = true to "Odvezano: $id"
                            runCatching { tagInfo = Api.tag(id) }
                        } catch (e: Exception) {
                            record("odvezivanje", id, null, null, false, e.message)
                            result = false to (e.message ?: "Greška")
                        }
                    }
                }) { Text("Odveži", color = Err) }
            },
            dismissButton = { TextButton(onClick = { confirmDetach = false }) { Text("Otkaži") } }
        )
    }
}

@Composable
fun TagCard(t: JSONObject, onBlink: () -> Unit, onDetach: () -> Unit, onIssue: () -> Unit) {
    val online = t.optBoolean("online")
    val battery = if (t.isNull("battery")) null else t.optInt("battery")
    val hasLed = t.optJSONObject("supported_features")?.optJSONObject("led")?.optBoolean("enabled") == true
    val loc = t.optJSONObject("location")?.optJSONObject("latest_location")
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.optString("id"), fontWeight = FontWeight.Bold, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Pill(if (online) "online" else "offline", if (online) Ok else Err)
            Spacer(Modifier.width(6.dp))
            Pill(battery?.let { "baterija $it%" } ?: "baterija ?", batteryColor(battery))
        }
        t.str("current_image")?.let { url ->
            Box(Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFFE3E6EA), RoundedCornerShape(8.dp)).padding(6.dp)) {
                AsyncImage(
                    model = url, contentDescription = "Trenutni prikaz etikete",
                    modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Fit
                )
            }
        }
        val name = t.tagProductName()
        if (name == null) {
            Text("Etiketa trenutno nije vezana ni za jedan proizvod.", color = Muted, fontSize = 14.sp)
        } else {
            Text("Vezano: $name", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(t.tagSku()?.let { "SKU $it" }, t.tagPrice()).joinToString(" · "), color = Muted, fontSize = 13.sp)
        }
        if (t.isNull("offline_time").not() && !online) {
            Text("Offline od: ${com.gakovic.etikete.prettyTime(t.str("offline_time"))}", color = Err, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (hasLed) SmallAction("LED", Icons.Filled.FlashOn, Modifier.weight(1f), onBlink)
            SmallAction("Prijavi", Icons.Filled.ReportProblem, Modifier.weight(1f), onIssue)
            if (name != null) SmallAction("Odveži", Icons.Filled.LinkOff, Modifier.weight(1f), onDetach, Err)
        }
    }
}

@Composable
fun SmallAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit, color: Color = BlueLight) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun ProductCard(d: Api.ProductDetails, via: String) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Ok.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .background(Ok.copy(alpha = 0.04f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("✓ Pronađen kao $via", color = Ok, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val img: Any? = d.imageBytes ?: d.imageUrl
        if (img != null) {
            Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFFE3E6EA), RoundedCornerShape(8.dp)).padding(6.dp)) {
                AsyncImage(img, d.name, Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Fit)
            }
        }
        Text(d.imageNote, color = Muted, fontSize = 11.sp)
        Text(d.name ?: d.sku, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            listOfNotNull("SKU ${d.sku}", d.itemId?.takeIf { it != d.sku }?.let { "ID $it" }, d.size?.let { "vel. $it" }, d.color, d.amount)
                .joinToString(" · "),
            fontSize = 13.sp, fontFamily = FontFamily.Monospace
        )
        if (d.prices.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                d.prices.forEach { (type, value) ->
                    Column {
                        Text(type, color = Muted, fontSize = 11.sp)
                        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
                if (d.sale) Pill("akcija", Err)
            }
        }
        if (d.barcodes.isNotEmpty()) Text("Barkod: " + d.barcodes.take(4).joinToString(", ") + if (d.barcodes.size > 4) " …" else "", fontSize = 12.sp, color = Muted)
        val extra = listOfNotNull(d.department?.let { "Odeljenje: $it" }, d.stock?.let { "Zaliha: $it" })
        if (extra.isNotEmpty()) Text(extra.joinToString(" · "), fontSize = 12.sp, color = Muted)
        d.texts.forEach { (k, v) -> Text("$k: $v", fontSize = 12.sp, maxLines = 2) }
        if (d.tags.isEmpty()) Text("Još nije ni na jednoj etiketi u ovoj prodavnici.", fontSize = 12.sp, color = Muted)
        else Text(
            "Već na ${d.tags.size} etiket" + (if (d.tags.size == 1) "i" else "a") + ": " +
                d.tags.take(4).joinToString(", ") { it.optString("id") } + if (d.tags.size > 4) " …" else "",
            fontSize = 12.sp, color = Warn
        )
    }
}
