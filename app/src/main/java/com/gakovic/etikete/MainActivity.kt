package com.gakovic.etikete

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.gakovic.etikete.ui.AlarmScreen
import com.gakovic.etikete.ui.AppTheme
import com.gakovic.etikete.ui.IssueScreen
import com.gakovic.etikete.ui.LinkScreen
import com.gakovic.etikete.ui.MapScreen
import com.gakovic.etikete.ui.ReportScreen
import com.gakovic.etikete.ui.SettingsScreen

enum class Tab(val title: String, val icon: ImageVector) {
    LINK("Veži", Icons.Filled.Link),
    MAP("Mapa", Icons.Filled.Map),
    ALARMS("Alarmi", Icons.Filled.NotificationsActive),
    ISSUES("Prijave", Icons.Filled.ReportProblem),
    REPORT("Izveštaj", Icons.Filled.Assessment),
    SETTINGS("Podešavanja", Icons.Filled.Settings),
}

/** Zajednicko stanje izmedju ekrana (npr. "prikazi ovaj tag na mapi"). */
object Nav {
    var tab by mutableStateOf(Tab.LINK)
    var mapFocusTag by mutableStateOf<String?>(null)
    var mapFocusPoint by mutableStateOf<Pair<Double, Double>?>(null)
    var issueTag by mutableStateOf<String?>(null)
    var storeVersion by mutableStateOf(0)

    fun showOnMap(tag: String?, point: Pair<Double, Double>? = null) {
        mapFocusTag = tag
        mapFocusPoint = point
        tab = Tab.MAP
    }

    fun reportIssue(tag: String) {
        issueTag = tag
        tab = Tab.ISSUES
    }
}

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())

        if (!Api.loggedIn || Prefs.storeId == null) Nav.tab = Tab.SETTINGS
        handleIntent(intent)
        setContent { AppTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("tab") == "alarmi" && Api.loggedIn) Nav.tab = Tab.ALARMS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root() {
    val tab = Nav.tab
    // citanje verzije osvezava ekran posle prijave / promene prodavnice
    val storeVersion = Nav.storeVersion
    if (storeVersion < 0) return
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tab.title, fontSize = 18.sp)
                        Text(
                            Prefs.storeName ?: "nije izabrana prodavnica",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { Nav.tab = Tab.SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Podešavanja", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E3A8A), titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.filter { it != Tab.SETTINGS }.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { Nav.tab = t },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // bez prodavnice nema smisla ostalo
            val needSetup = !Api.loggedIn || Prefs.storeId == null
            when (if (needSetup) Tab.SETTINGS else tab) {
                Tab.LINK -> LinkScreen()
                Tab.MAP -> MapScreen()
                Tab.ALARMS -> AlarmScreen()
                Tab.ISSUES -> IssueScreen()
                Tab.REPORT -> ReportScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
