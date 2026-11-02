package com.gakovic.etikete

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        History.init(this)
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Alarmi prodavnice", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Novi Digety alarmi (geofence / tamper)" }
        )
        AlarmWorker.schedule(this)
    }

    companion object {
        const val ALARM_CHANNEL = "alarmi"
        lateinit var instance: App
            private set
    }
}

/** Podesavanja aplikacije. */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.getSharedPreferences("etikete", Context.MODE_PRIVATE)
    }

    private fun s(k: String) = sp.getString(k, null)
    private fun s(k: String, v: String?) = sp.edit().putString(k, v).apply()

    var username: String?
        get() = s("username")
        set(v) = s("username", v)
    var refreshToken: String?
        get() = s("refresh")
        set(v) = s("refresh", v)
    var storeId: String?
        get() = s("store_id")
        set(v) = s("store_id", v)
    var storeName: String?
        get() = s("store_name")
        set(v) = s("store_name", v)
    var reporterName: String?
        get() = s("reporter")
        set(v) = s("reporter", v)
    var linkMode: String
        get() = s("link_mode") ?: "barcode"
        set(v) = s("link_mode", v)
    var continuous: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).apply()
    var alarmNotifications: Boolean
        get() = sp.getBoolean("alarm_notif", true)
        set(v) = sp.edit().putBoolean("alarm_notif", v).apply()
    var lastAlarmSeen: String?
        get() = s("last_alarm")
        set(v) = s("last_alarm", v)
    var useGps: Boolean
        get() = sp.getBoolean("gps", true)
        set(v) = sp.edit().putBoolean("gps", v).apply()
}

/** GPS pozicija telefona (ako je dozvoljena). */
object Geo {
    @SuppressLint("MissingPermission")
    suspend fun current(c: Context): Location? {
        if (!Prefs.useGps || !hasPermission(c)) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(c)
            withTimeoutOrNull(8000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            } ?: client.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    fun hasPermission(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Jedan zapis u lokalnoj istoriji (vezivanje, odvezivanje, prijava...). */
data class HistoryEntry(
    val time: Long,
    val action: String,          // vezivanje | odvezivanje | prijava | led
    val store: String?,
    val tag: String,
    val product: String?,
    val mode: String?,
    val ok: Boolean,
    val message: String?,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
    val user: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("action", action).put("store", store).put("tag", tag)
        .put("product", product).put("mode", mode).put("ok", ok).put("message", message)
        .put("lat", lat).put("lon", lon).put("acc", accuracy?.toDouble()).put("user", user)

    val timeText: String get() = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(time))
    val day: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    companion object {
        fun from(o: JSONObject) = HistoryEntry(
            time = o.optLong("time"), action = o.optString("action"), store = o.str("store"),
            tag = o.optString("tag"), product = o.str("product"), mode = o.str("mode"),
            ok = o.optBoolean("ok"), message = o.str("message"),
            lat = if (o.isNull("lat")) null else o.optDouble("lat"),
            lon = if (o.isNull("lon")) null else o.optDouble("lon"),
            accuracy = if (o.isNull("acc")) null else o.optDouble("acc").toFloat(),
            user = o.str("user"),
        )
    }
}

/** Lokalna istorija akcija na telefonu (izvestaj vezivanja), cuva se u fajlu. */
object History {
    private lateinit var file: File
    private val items = mutableListOf<HistoryEntry>()
    private const val MAX = 50_000

    fun init(c: Context) {
        file = File(c.filesDir, "istorija.json")
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) items.add(HistoryEntry.from(arr.getJSONObject(i)))
            }
        }
    }

    @Synchronized
    fun all(): List<HistoryEntry> = items.toList()

    suspend fun add(e: HistoryEntry) = withContext(Dispatchers.IO) {
        synchronized(this@History) {
            items.add(0, e)
            if (items.size > MAX) items.subList(MAX, items.size).clear()
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        }
    }

    @Synchronized
    fun clear() {
        items.clear()
        file.delete()
    }

    /** CSV za deljenje (Excel u srpskom lokalu: separator ;). */
    fun toCsv(list: List<HistoryEntry>): String {
        val sb = StringBuilder("﻿vreme;akcija;prodavnica;tag;proizvod;vezano_po;uspesno;poruka;gps_lat;gps_lon;tacnost_m;korisnik\n")
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        list.forEach {
            sb.append(listOf(
                q(it.timeText), q(it.action), q(it.store), q(it.tag), q(it.product), q(it.mode),
                if (it.ok) "DA" else "NE", q(it.message),
                it.lat?.toString() ?: "", it.lon?.toString() ?: "", it.accuracy?.toInt()?.toString() ?: "",
                q(it.user)
            ).joinToString(";")).append('\n')
        }
        return sb.toString()
    }
}

/** Upis akcije u istoriju, sa GPS-om. */
suspend fun record(
    c: Context, action: String, tag: String, product: String?, mode: String?, ok: Boolean, message: String?,
) {
    val loc = Geo.current(c)
    History.add(
        HistoryEntry(
            time = System.currentTimeMillis(), action = action, store = Prefs.storeName ?: Prefs.storeId,
            tag = tag, product = product, mode = mode, ok = ok, message = message,
            lat = loc?.latitude, lon = loc?.longitude, accuracy = loc?.accuracy,
            user = Prefs.reporterName ?: Prefs.username,
        )
    )
}

object BuildConfigInfo {
    fun version(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" }.getOrDefault("")
}
