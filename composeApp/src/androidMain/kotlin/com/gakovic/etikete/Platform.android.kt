package com.gakovic.etikete

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

private val ctx: Context get() = AndroidPlatform.appContext
private val prefs by lazy { ctx.getSharedPreferences("etikete", Context.MODE_PRIVATE) }

actual fun settingsGet(key: String): String? =
    // stare verzije su neke vrednosti cuvale kao boolean
    runCatching { prefs.getString(key, null) }.getOrElse { runCatching { prefs.getBoolean(key, false).toString() }.getOrNull() }

actual fun settingsPut(key: String, value: String?) {
    prefs.edit().remove(key).apply {
        if (value != null) putString(key, value)
    }.apply()
}

actual fun fileRead(name: String): String? = File(ctx.filesDir, name).takeIf { it.exists() }?.readText()
actual fun fileWrite(name: String, text: String) = File(ctx.filesDir, name).writeText(text)
actual fun fileDelete(name: String) {
    File(ctx.filesDir, name).delete()
}

actual suspend fun scanBarcode(): String? {
    val opts = GmsBarcodeScannerOptions.Builder().enableAutoZoom().build()
    val c: Context = AndroidPlatform.activity ?: ctx
    return GmsBarcodeScanning.getClient(c, opts).startScan().await()?.rawValue
}

private fun hasLocationPermission() =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
actual suspend fun currentLocation(): GeoFix? {
    if (!hasLocationPermission()) return null
    return try {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val loc = withTimeoutOrNull(8000) {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
        } ?: client.lastLocation.await()
        loc?.let { GeoFix(it.latitude, it.longitude, it.accuracy.toDouble()) }
    } catch (e: Exception) {
        null
    }
}

actual fun vibrate(error: Boolean) {
    val v = ctx.getSystemService(Vibrator::class.java) ?: return
    val effect = if (error) VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1)
    else VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
    v.vibrate(effect)
}

actual fun shareFile(fileName: String, content: String, mime: String) {
    val f = File(ctx.cacheDir, fileName)
    f.writeText(content)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(i, "Pošalji izveštaj")
    val a = AndroidPlatform.activity
    if (a != null) a.startActivity(chooser) else ctx.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual fun appVersion(): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

actual fun platformName(): String = "Android"

actual fun scheduleAlarmChecks() = AlarmWorker.schedule(ctx)

@Composable
actual fun rememberPhotoCapture(onPhoto: (ByteArray?) -> Unit): () -> Unit {
    val c = LocalContext.current
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        if (bmp == null) onPhoto(null) else {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            onPhoto(out.toByteArray())
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camera.launch(null) else toast("Bez dozvole za kameru nema fotografije")
    }
    return {
        if (c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null)
        else permission.launch(Manifest.permission.CAMERA)
    }
}
