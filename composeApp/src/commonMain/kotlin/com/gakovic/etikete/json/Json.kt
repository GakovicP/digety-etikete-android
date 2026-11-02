package com.gakovic.etikete.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Mali JSON sloj sa istim API-jem kao Android-ov org.json (optString, optJSONObject...),
 * da bi isti kod radio i na Androidu i na iOS-u.
 */
class JSONException(message: String) : RuntimeException(message)

private object JsonNullMarker

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val el = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JSONException("Neispravan JSON: ${e.message}")
        }
        if (el !is JsonObject) throw JSONException("Očekivan JSON objekat")
        el.forEach { (k, v) -> map[k] = fromElement(v) }
    }

    internal constructor(m: Map<String, Any?>) : this() {
        map.putAll(m)
    }

    fun put(key: String, value: Any?): JSONObject {
        map[key] = value ?: JsonNullMarker
        return this
    }

    fun has(key: String) = map.containsKey(key)
    fun isNull(key: String): Boolean = map[key].let { it == null || it === JsonNullMarker }
    fun opt(key: String): Any? = map[key].takeUnless { it === JsonNullMarker }
    fun keys(): Iterator<String> = map.keys.iterator()
    fun length() = map.size

    fun optJSONObject(key: String): JSONObject? = opt(key) as? JSONObject
    fun optJSONArray(key: String): JSONArray? = opt(key) as? JSONArray
    fun getJSONObject(key: String): JSONObject = optJSONObject(key) ?: throw JSONException("Nema objekta '$key'")
    fun getJSONArray(key: String): JSONArray = optJSONArray(key) ?: throw JSONException("Nema niza '$key'")
    fun getString(key: String): String = opt(key)?.let { valueToString(it) } ?: throw JSONException("Nema vrednosti '$key'")

    fun optString(key: String, fallback: String = ""): String = opt(key)?.let { valueToString(it) } ?: fallback
    fun optInt(key: String, fallback: Int = 0): Int = toNumber(opt(key))?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = toNumber(opt(key))?.toLong() ?: fallback
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = toNumber(opt(key))?.toDouble() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> fallback
    }

    internal fun toElement(): JsonElement = JsonObject(map.mapValues { toElementAny(it.value) })
    override fun toString(): String = toElement().toString()
}

class JSONArray() {
    private val list = ArrayList<Any?>()

    constructor(text: String) : this() {
        val el = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JSONException("Neispravan JSON: ${e.message}")
        }
        if (el !is JsonArray) throw JSONException("Očekivan JSON niz")
        el.forEach { list.add(fromElement(it)) }
    }

    internal constructor(l: List<Any?>) : this() {
        list.addAll(l)
    }

    fun put(value: Any?): JSONArray {
        list.add(value ?: JsonNullMarker)
        return this
    }

    fun length() = list.size
    fun opt(i: Int): Any? = list.getOrNull(i).takeUnless { it === JsonNullMarker }
    fun optJSONObject(i: Int): JSONObject? = opt(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = opt(i) as? JSONArray
    fun optString(i: Int, fallback: String = ""): String = opt(i)?.let { valueToString(it) } ?: fallback
    fun getJSONObject(i: Int): JSONObject = optJSONObject(i) ?: throw JSONException("Nema objekta na $i")

    internal fun toElement(): JsonElement = JsonArray(list.map { toElementAny(it) })
    override fun toString(): String = toElement().toString()
}

private fun fromElement(e: JsonElement): Any? = when (e) {
    is JsonNull -> JsonNullMarker
    is JsonObject -> JSONObject(e.mapValues { fromElement(it.value) })
    is JsonArray -> JSONArray(e.map { fromElement(it) })
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull
        e.doubleOrNull != null -> e.doubleOrNull
        else -> e.content
    }
}

private fun toElementAny(v: Any?): JsonElement = when (v) {
    null, JsonNullMarker -> JsonNull
    is JSONObject -> v.toElement()
    is JSONArray -> v.toElement()
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Float -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
    is Double -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

private fun valueToString(v: Any): String = when (v) {
    is String -> v
    is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() + ".0" else v.toString()
    is JSONObject, is JSONArray -> v.toString()
    else -> v.toString()
}

private fun toNumber(v: Any?): Number? = when (v) {
    is Number -> v
    is String -> v.toLongOrNull() ?: v.toDoubleOrNull()
    is Boolean -> null
    else -> null
}
