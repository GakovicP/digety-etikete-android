package com.gakovic.etikete

import com.gakovic.etikete.json.JSONArray
import com.gakovic.etikete.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Podesavanja aplikacije (cuvaju se na telefonu). */
object Prefs {
    private fun s(k: String) = settingsGet(k)
    private fun s(k: String, v: String?) = settingsPut(k, v)
    private fun bGet(k: String, def: Boolean) = settingsGet(k)?.toBooleanStrictOrNull() ?: def
    private fun bSet(k: String, v: Boolean) = settingsPut(k, v.toString())

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
    var continuous: Boolean
        get() = bGet("continuous", true)
        set(v) = bSet("continuous", v)
    var alarmNotifications: Boolean
        get() = bGet("alarm_notif", true)
        set(v) = bSet("alarm_notif", v)
    var lastAlarmSeen: String?
        get() = s("last_alarm")
        set(v) = s("last_alarm", v)
    var useGps: Boolean
        get() = bGet("gps", true)
        set(v) = bSet("gps", v)
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
    val accuracy: Double?,
    val user: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("action", action).put("store", store).put("tag", tag)
        .put("product", product).put("mode", mode).put("ok", ok).put("message", message)
        .put("lat", lat).put("lon", lon).put("acc", accuracy).put("user", user)

    val timeText: String get() = dateTimeText(time)
    val day: String get() = dayString(time)

    companion object {
        fun from(o: JSONObject) = HistoryEntry(
            time = o.optLong("time"), action = o.optString("action"), store = o.str("store"),
            tag = o.optString("tag"), product = o.str("product"), mode = o.str("mode"),
            ok = o.optBoolean("ok"), message = o.str("message"),
            lat = if (o.isNull("lat")) null else o.optDouble("lat"),
            lon = if (o.isNull("lon")) null else o.optDouble("lon"),
            accuracy = if (o.isNull("acc")) null else o.optDouble("acc"),
            user = o.str("user"),
        )
    }
}

/** Lokalna istorija akcija na telefonu (izvestaj "sa telefona"), cuva se u fajlu. */
object History {
    private const val FILE = "istorija.json"
    private const val MAX = 50_000
    private val items = mutableListOf<HistoryEntry>()
    private var loaded = false
    private val lock = Mutex()

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val text = fileRead(FILE) ?: return
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(HistoryEntry.from(it)) }
        }
    }

    fun all(): List<HistoryEntry> {
        ensureLoaded()
        return items.toList()
    }

    suspend fun add(e: HistoryEntry) = lock.withLock {
        ensureLoaded()
        items.add(0, e)
        if (items.size > MAX) items.subList(MAX, items.size).clear()
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        fileWrite(FILE, arr.toString())
    }

    fun clear() {
        items.clear()
        fileDelete(FILE)
    }

    /** CSV za deljenje (Excel u srpskom lokalu: separator ;). */
    fun toCsv(list: List<HistoryEntry>): String {
        val sb = StringBuilder("﻿vreme;akcija;prodavnica;tag;proizvod;vezano_po;uspesno;poruka;gps_lat;gps_lon;tacnost_m;korisnik\n")
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        list.forEach {
            sb.append(listOf(
                q(it.timeText), q(it.action), q(it.store), q(it.tag), q(it.product), q(it.mode),
                if (it.ok) "DA" else "NE", q(it.message),
                it.lat?.fmt(6) ?: "", it.lon?.fmt(6) ?: "", it.accuracy?.fmt(0) ?: "",
                q(it.user)
            ).joinToString(";")).append('\n')
        }
        return sb.toString()
    }
}

/** Upis akcije u istoriju, sa GPS-om. */
suspend fun record(action: String, tag: String, product: String?, mode: String?, ok: Boolean, message: String?) {
    val loc = if (Prefs.useGps) runCatching { currentLocation() }.getOrNull() else null
    History.add(
        HistoryEntry(
            time = nowMillis(), action = action, store = Prefs.storeName ?: Prefs.storeId,
            tag = tag, product = product, mode = mode, ok = ok, message = message,
            lat = loc?.lat, lon = loc?.lon, accuracy = loc?.accuracy,
            user = Prefs.reporterName ?: Prefs.username,
        )
    )
}
