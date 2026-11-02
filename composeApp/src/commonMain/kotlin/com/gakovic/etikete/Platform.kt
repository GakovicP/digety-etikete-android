package com.gakovic.etikete

import androidx.compose.runtime.Composable

/** GPS pozicija telefona. */
data class GeoFix(val lat: Double, val lon: Double, val accuracy: Double?)

// ---- funkcije koje svaka platforma (Android / iOS) implementira posebno ----

/** Trajno podesavanje (SharedPreferences / NSUserDefaults). */
expect fun settingsGet(key: String): String?
expect fun settingsPut(key: String, value: String?)

/** Tekstualni fajl u privatnom folderu aplikacije. */
expect fun fileRead(name: String): String?
expect fun fileWrite(name: String, text: String)
expect fun fileDelete(name: String)

/** Skeniranje barkoda kamerom; null ako je otkazano. */
expect suspend fun scanBarcode(): String?

/** Trenutna GPS pozicija (null ako nema dozvole ili signala). */
expect suspend fun currentLocation(): GeoFix?

/** Kratka vibracija (greska = dvostruka). */
expect fun vibrate(error: Boolean)

/** Deljenje fajla (CSV) preko sistemskog menija. */
expect fun shareFile(fileName: String, content: String, mime: String)

/** Verzija aplikacije. */
expect fun appVersion(): String

/** "Android" / "iOS". */
expect fun platformName(): String

/** Periodicna provera alarma u pozadini (Android: WorkManager; iOS: samo dok je aplikacija otvorena). */
expect fun scheduleAlarmChecks()

/** Fotografisanje: vraca funkciju koja otvara kameru; rezultat je JPEG ili null. */
@Composable
expect fun rememberPhotoCapture(onPhoto: (ByteArray?) -> Unit): () -> Unit
