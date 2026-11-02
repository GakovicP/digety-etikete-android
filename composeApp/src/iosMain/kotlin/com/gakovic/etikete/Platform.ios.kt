package com.gakovic.etikete

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.coroutines.resume

private val defaults get() = NSUserDefaults.standardUserDefaults

actual fun settingsGet(key: String): String? = defaults.stringForKey(key)

actual fun settingsPut(key: String, value: String?) {
    if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
}

private fun docPath(name: String): String {
    val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val dir = dirs.firstOrNull() as? String ?: ""
    return "$dir/$name"
}

@OptIn(ExperimentalForeignApi::class)
actual fun fileRead(name: String): String? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(docPath(name))) return null
    @Suppress("CAST_NEVER_SUCCEEDS")
    return NSString.create(contentsOfFile = docPath(name), encoding = NSUTF8StringEncoding, error = null) as String?
}

@OptIn(ExperimentalForeignApi::class)
actual fun fileWrite(name: String, text: String) {
    @Suppress("CAST_NEVER_SUCCEEDS")
    (text as NSString).writeToFile(docPath(name), true, NSUTF8StringEncoding, null)
}

@OptIn(ExperimentalForeignApi::class)
actual fun fileDelete(name: String) {
    NSFileManager.defaultManager.removeItemAtPath(docPath(name), null)
}

actual suspend fun scanBarcode(): String? {
    val impl = IosBridge.impl ?: return null
    return suspendCancellableCoroutine { cont ->
        impl.scanBarcode(object : StringCallback {
            override fun onResult(value: String?) {
                if (cont.isActive) cont.resume(value)
            }
        })
    }
}

actual suspend fun currentLocation(): GeoFix? {
    val impl = IosBridge.impl ?: return null
    return suspendCancellableCoroutine { cont ->
        impl.currentLocation(object : LocationCallback {
            override fun onResult(ok: Boolean, lat: Double, lon: Double, accuracy: Double) {
                if (cont.isActive) cont.resume(if (ok) GeoFix(lat, lon, accuracy) else null)
            }
        })
    }
}

actual fun vibrate(error: Boolean) {
    IosBridge.impl?.vibrate(error)
}

actual fun shareFile(fileName: String, content: String, mime: String) {
    IosBridge.impl?.share(fileName, content)
}

actual fun appVersion(): String =
    (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String) ?: ""

actual fun platformName(): String = "iOS"

/** iOS ne dozvoljava pouzdanu proveru u pozadini - alarmi se osvezavaju dok je aplikacija otvorena. */
actual fun scheduleAlarmChecks() {}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@Composable
actual fun rememberPhotoCapture(onPhoto: (ByteArray?) -> Unit): () -> Unit = remember(onPhoto) {
    {
        val impl = IosBridge.impl
        if (impl == null) onPhoto(null) else impl.takePhoto(object : DataCallback {
            override fun onResult(data: NSData?) {
                onPhoto(data?.toByteArray())
            }
        })
    }
}
