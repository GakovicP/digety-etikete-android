package com.gakovic.etikete

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.gakovic.etikete.ui.AlarmScreen
import com.gakovic.etikete.ui.AppTheme
import com.gakovic.etikete.ui.BrandDark
import com.gakovic.etikete.ui.IssueScreen
import com.gakovic.etikete.ui.LinkScreen
import com.gakovic.etikete.ui.ReportScreen
import com.gakovic.etikete.ui.SettingsScreen

enum class Tab(val title: String, val icon: ImageVector) {
    LINK("Veži", Icons.Filled.Link),
    ALARMS("Alarmi", Icons.Filled.NotificationsActive),
    ISSUES("Prijave", Icons.Filled.ReportProblem),
    REPORT("Izveštaj", Icons.Filled.Assessment),
    SETTINGS("Podešavanja", Icons.Filled.Settings),
}

/** Zajednicko stanje izmedju ekrana. */
object Nav {
    var tab by mutableStateOf(Tab.LINK)
    var issueTag by mutableStateOf<String?>(null)
    var storeVersion by mutableIntStateOf(0)

    fun reportIssue(tag: String) {
        issueTag = tag
        tab = Tab.ISSUES
    }
}

/** Kratke poruke na dnu ekrana (zamena za Android Toast, radi i na iOS-u). */
object Snack {
    var message by mutableStateOf<String?>(null)
    var seq by mutableIntStateOf(0)
}

fun toast(msg: String) {
    Snack.message = msg
    Snack.seq++
}

/** Ulazna tacka - ista za Android i iOS. */
@Composable
fun App() {
    // slike (etikete, fotografije prijava) preko Ktor-a - isto na Androidu i iOS-u
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
    remember {
        if (!Api.loggedIn || Prefs.storeId == null) Nav.tab = Tab.SETTINGS
        scheduleAlarmChecks()
        true
    }
    AppTheme { Root() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root() {
    val tab = Nav.tab
    // citanje verzije osvezava ekran posle prijave / promene prodavnice
    val storeVersion = Nav.storeVersion
    if (storeVersion < 0) return
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Snack.seq) {
        val m = Snack.message
        if (m != null) {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(m)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandDark, titleContentColor = Color.White)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
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
            val needSetup = !Api.loggedIn || Prefs.storeId == null
            when (if (needSetup) Tab.SETTINGS else tab) {
                Tab.LINK -> LinkScreen()
                Tab.ALARMS -> AlarmScreen()
                Tab.ISSUES -> IssueScreen()
                Tab.REPORT -> ReportScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
