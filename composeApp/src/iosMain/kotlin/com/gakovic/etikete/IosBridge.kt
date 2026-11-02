package com.gakovic.etikete

import platform.Foundation.NSData

/*
 * Most prema Swift kodu (iosApp/iosApp/NativeImpl.swift): kamera/skener, GPS, fotografisanje,
 * deljenje i vibracija se rade u Swift-u, sa Apple bibliotekama.
 */

interface StringCallback {
    fun onResult(value: String?)
}

interface LocationCallback {
    fun onResult(ok: Boolean, lat: Double, lon: Double, accuracy: Double)
}

interface DataCallback {
    fun onResult(data: NSData?)
}

interface IosNative {
    fun scanBarcode(callback: StringCallback)
    fun currentLocation(callback: LocationCallback)
    fun takePhoto(callback: DataCallback)
    fun share(fileName: String, content: String)
    fun vibrate(error: Boolean)
}

/** Swift postavlja: IosBridge.shared.impl = NativeImpl() */
object IosBridge {
    var impl: IosNative? = null
}
