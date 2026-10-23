package com.interlink.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

@Serializable
data class Envelope(
    val v: Int = 1,
    val t: String,
    val id: String,
    val r: String? = null,
    val ok: Boolean? = null,
    val e: String? = null,
    val d: JsonElement = JsonObject(emptyMap()),
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun request(type: String, payload: JsonElement): Pair<String, Envelope> {
            val id = newId()
            return id to Envelope(t = type, id = id, d = payload)
        }

        fun response(reqId: String, type: String, payload: JsonElement): Envelope =
            Envelope(t = type, id = newId(), r = reqId, ok = true, d = payload)

        fun errorResponse(reqId: String, type: String, code: String): Envelope =
            Envelope(t = type, id = newId(), r = reqId, ok = false, e = code, d = JsonPrimitive(""))

        fun push(type: String, payload: JsonElement): Envelope =
            Envelope(t = type, id = newId(), d = payload)
    }
}

fun newId(): String {
    val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
    return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        .trimEnd('=')
}

/** Helpers to build protocol payloads ergonomically. */
fun objOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> {}
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Double -> put(k, v)
            is Float -> put(k, v.toDouble())
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

fun JsonElement.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }

fun JsonElement.long(key: String): Long? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.longOrNull }

fun JsonElement.int(key: String): Int? = long(key)?.toInt()

fun JsonElement.bool(key: String): Boolean? =
    (this as? JsonPrimitive)?.contentOrNull?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    } ?: (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull == "true" }

fun JsonElement.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)
