package com.gakovic.etikete

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Na svakih 15 minuta proverava nove alarme prodavnice i salje notifikaciju. */
class AlarmWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Prefs.alarmNotifications || !Api.loggedIn) return Result.success()
        val store = Prefs.storeId ?: return Result.success()
        return try {
            checkNewAlarms(applicationContext, store)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<AlarmWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("alarmi", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Vraca broj novih alarma (i salje notifikaciju za njih). */
        suspend fun checkNewAlarms(c: Context, store: String): Int {
            val res = Api.alarms(store, 1, null, null, 50)
            val last = Prefs.lastAlarmSeen?.toLongOrNull()
            val newer = res.items.filter { a -> last == null || (parseTime(a.str("start")) ?: 0L) > last }
            val newest = res.items.maxOfOrNull { parseTime(it.str("start")) ?: 0L }
            if (newest != null && newest > 0 && (last == null || newest > last)) Prefs.lastAlarmSeen = newest.toString()
            // prvi put samo zapamti stanje, bez gomile notifikacija
            if (last == null) return 0
            if (newer.isNotEmpty()) notify(c, newer)
            return newer.size
        }

        private fun notify(c: Context, alarms: List<JSONObject>) {
            if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                android.os.Build.VERSION.SDK_INT >= 33
            ) return
            val first = alarms.first()
            val title = if (alarms.size == 1) "Alarm: ${alarmTypeText(first)}" else "${alarms.size} nova alarma"
            val text = alarms.take(5).joinToString("\n") { a ->
                val tag = a.optJSONObject("tag")
                "${alarmTypeText(a)} · ${a.str("zone") ?: "-"} · ${tag?.tagProductName() ?: tag?.str("id") ?: ""}"
            }
            val intent = Intent(c, MainActivity::class.java)
                .putExtra("tab", "alarmi")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(c, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(c, App.ALARM_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(c).notify(1001, n)
            } catch (_: SecurityException) {
            }
        }
    }
}

fun alarmTypeText(a: JSONObject): String = when {
    a.optBoolean("tag_missing") -> "etiketa nestala"
    a.str("type") == "cut" -> "sečenje / tamper"
    a.str("type") == "zone" -> "izlazak iz zone"
    else -> a.str("type") ?: "alarm"
}

fun isoDaysAgo(days: Int): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(System.currentTimeMillis() - days * 86_400_000L))
}

/** Vreme iz Digety-ja -> "02.11. 10:15" (lokalno vreme). */
fun prettyTime(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    val t = parseTime(iso) ?: return iso.take(16).replace('T', ' ')
    return SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(t))
}
