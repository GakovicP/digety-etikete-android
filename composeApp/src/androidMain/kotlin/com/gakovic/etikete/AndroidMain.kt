package com.gakovic.etikete

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.ref.WeakReference

/** Android Application - inicijalizacija zajednickog koda. */
class EtiketeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.appContext = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Alarmi prodavnice", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Novi Digety alarmi (geofence / tamper)" }
        )
    }

    companion object {
        const val ALARM_CHANNEL = "alarmi"
    }
}

/** Reference koje Android implementacije trebaju. */
object AndroidPlatform {
    lateinit var appContext: Application
    private var activityRef: WeakReference<Activity>? = null
    var activity: Activity?
        get() = activityRef?.get()
        set(v) {
            activityRef = v?.let { WeakReference(it) }
        }
}

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidPlatform.activity = this
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
        handleIntent(intent)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        AndroidPlatform.activity = this
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("tab") == "alarmi" && Api.loggedIn) Nav.tab = Tab.ALARMS
    }
}
