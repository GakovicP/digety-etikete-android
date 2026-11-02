package com.gakovic.etikete

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun two(n: Int) = n.toString().padStart(2, '0')

private fun local(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
private fun utc(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)

/** "2026-11-02" (lokalni dan). */
fun dayString(ms: Long): String = local(ms).date.toString()

/** "2026-11-02" (UTC dan). */
fun utcDayString(ms: Long): String = utc(ms).date.toString()

/** "02.11.2026 10:15:30" (lokalno). */
fun dateTimeText(ms: Long): String = local(ms).let {
    "${two(it.dayOfMonth)}.${two(it.monthNumber)}.${it.year} ${two(it.hour)}:${two(it.minute)}:${two(it.second)}"
}

/** "2026-11-02T09:15:30Z" (UTC). */
fun isoUtc(ms: Long): String = utc(ms).let {
    "${it.date}T${two(it.hour)}:${two(it.minute)}:${two(it.second)}Z"
}

/** "2026-11-02 09:15:30" (UTC). */
fun utcDateTime(ms: Long): String = utc(ms).let {
    "${it.date} ${two(it.hour)}:${two(it.minute)}:${two(it.second)}"
}

/** "20261102_1015" za imena fajlova. */
fun stampNow(): String = local(nowMillis()).let { "${it.year}${two(it.monthNumber)}${two(it.dayOfMonth)}_${two(it.hour)}${two(it.minute)}" }

/** Pocetak danasnjeg dana (lokalno) u ms. */
fun startOfToday(): Long = local(nowMillis()).date.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

/** Vreme iz Digety-ja (ISO-8601 ili epoch s/ms) -> millis. */
fun parseTime(v: String?): Long? {
    if (v.isNullOrBlank()) return null
    val s = v.trim()
    s.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
    s.toDoubleOrNull()?.let { val l = it.toLong(); return if (l < 100_000_000_000L) l * 1000 else l }
    runCatching { return Instant.parse(s).toEpochMilliseconds() }
    runCatching { return Instant.parse(s.replace(' ', 'T')).toEpochMilliseconds() }
    runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(TimeZone.UTC).toEpochMilliseconds() }
    runCatching { return LocalDate.parse(s.take(10)).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }
    return null
}

/** Vreme iz Digety-ja -> "02.11. 10:15" (lokalno). */
fun prettyTime(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    val t = parseTime(iso) ?: return iso.take(16).replace('T', ' ')
    val l = local(t)
    return "${two(l.dayOfMonth)}.${two(l.monthNumber)}. ${two(l.hour)}:${two(l.minute)}"
}

/** Broj sa fiksnim brojem decimala (zamena za String.format("%.2f")). */
fun Double.fmt(decimals: Int): String {
    if (isNaN()) return ""
    val f = 10.0.pow(decimals)
    val r = (abs(this) * f).roundToLong()
    val whole = r / f.toLong()
    val frac = (r % f.toLong()).toString().padStart(decimals, '0')
    val sign = if (this < 0 && r != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$frac"
}
