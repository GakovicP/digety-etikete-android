package com.gakovic.etikete.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakovic.etikete.Api
import com.gakovic.etikete.appVersion
import com.gakovic.etikete.platformName
import com.gakovic.etikete.Nav
import com.gakovic.etikete.Prefs
import com.gakovic.etikete.Tab
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(Api.loggedIn) }
    var user by remember { mutableStateOf(Prefs.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stores by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var storeFilter by remember { mutableStateOf("") }
    var storeId by remember { mutableStateOf(Prefs.storeId) }
    var reporter by remember { mutableStateOf(Prefs.reporterName ?: "") }
    var continuous by remember { mutableStateOf(Prefs.continuous) }
    var gps by remember { mutableStateOf(Prefs.useGps) }
    var notif by remember { mutableStateOf(Prefs.alarmNotifications) }

    suspend fun loadStores() {
        try {
            val me = Api.me()
            val own = me?.optJSONObject("store")
            val list = runCatching { Api.stores() }.getOrDefault(emptyList()).toMutableList()
            if (own != null && list.none { it.first == own.optString("id") }) {
                list.add(0, own.optString("id") to own.optString("name"))
            }
            stores = list
            // ako korisnik ima samo jednu prodavnicu, izaberi je odmah
            if (Prefs.storeId == null && list.size == 1) {
                Prefs.storeId = list[0].first; Prefs.storeName = list[0].second
                storeId = list[0].first; Nav.storeVersion++
            } else if (Prefs.storeId == null && own != null) {
                Prefs.storeId = own.optString("id"); Prefs.storeName = own.optString("name")
                storeId = Prefs.storeId; Nav.storeVersion++
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(loggedIn) { if (loggedIn) loadStores() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        if (!loggedIn) {
            Section("Prijava na Digety") {
                Text("Isti nalog kao za Digety web dashboard.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(user, { user = it.trim() }, label = { Text("Korisničko ime / e-mail") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(pass, { pass = it }, label = { Text("Lozinka") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                error?.let { Message(it, Err) }
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                Api.login(user, pass)
                                pass = ""; loggedIn = true; Nav.storeVersion++
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Prijavljujem…" else "Prijavi se") }
            }
            return@Column
        }

        Section("Nalog") {
            Text("Prijavljen: ${Prefs.username}", fontSize = 14.sp)
            OutlinedButton(onClick = {
                Api.logout(); loggedIn = false; storeId = null; stores = emptyList(); Nav.storeVersion++
            }) { Text("Odjavi se") }
        }

        Section("Prodavnica") {
            error?.let { Message(it, Err) }
            if (stores.isEmpty()) Loading("Učitavam prodavnice…")
            if (stores.size > 8) {
                OutlinedTextField(storeFilter, { storeFilter = it }, label = { Text("Pretraga") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(stores.filter { storeFilter.isBlank() || it.second.contains(storeFilter, true) || it.first.contains(storeFilter) }) { (id, name) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            Prefs.storeId = id; Prefs.storeName = name; Prefs.lastAlarmSeen = null
                            storeId = id; Nav.storeVersion++
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = storeId == id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(name, fontSize = 15.sp)
                            Text("ID $id", fontSize = 12.sp, color = Muted)
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (storeId != null) {
                Button(onClick = { Nav.tab = Tab.LINK }, modifier = Modifier.fillMaxWidth()) { Text("Kreni na vezivanje") }
            }
        }

        Section("Rad") {
            OutlinedTextField(reporter, { reporter = it; Prefs.reporterName = it.ifBlank { null } },
                label = { Text("Moje ime (za prijave i izveštaj)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow("Posle uspešnog vezivanja obriši polja za sledeću etiketu", continuous) {
                continuous = it; Prefs.continuous = it
            }
            SwitchRow("Beleži GPS lokaciju uz svaku akciju", gps) { gps = it; Prefs.useGps = it }
            SwitchRow("Obaveštenja o novim alarmima (provera na 15 min)", notif) { notif = it; Prefs.alarmNotifications = it }
        }

        Text(
            "Verzija ${appVersion()} (${platformName()})",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
