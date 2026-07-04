package com.niandui.idectl.transport

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Shared Gson (serializes nulls so optional fields are explicit for the LLM). */
internal val GSON: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

internal inline fun jObj(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

internal fun jArr(elements: Iterable<JsonElement>): JsonArray =
    JsonArray().apply { elements.forEach { add(it) } }

internal fun jArrOf(vararg elements: JsonElement): JsonArray =
    JsonArray().apply { elements.forEach { add(it) } }

internal fun strArr(values: Iterable<String>): JsonArray =
    JsonArray().apply { values.forEach { add(it) } }

// ---- typed getters over a params/args object (tolerant of missing/null) ----

internal fun JsonObject?.str(key: String): String? =
    this?.get(key)?.takeUnless { it.isJsonNull }?.asString

internal fun JsonObject?.int(key: String, default: Int): Int =
    this?.get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default

internal fun JsonObject?.long(key: String): Long? =
    this?.get(key)?.takeUnless { it.isJsonNull }?.asLong

internal fun JsonObject?.bool(key: String, default: Boolean): Boolean =
    this?.get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

internal fun JsonObject?.obj(key: String): JsonObject? =
    this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject?.strList(key: String): List<String>? =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
        it.takeUnless { e -> e.isJsonNull }?.asString
    }

internal fun prim(value: String?): JsonElement =
    if (value == null) JsonNull.INSTANCE else JsonPrimitive(value)

internal fun prim(value: Number?): JsonElement =
    if (value == null) JsonNull.INSTANCE else JsonPrimitive(value)

internal fun prim(value: Boolean?): JsonElement =
    if (value == null) JsonNull.INSTANCE else JsonPrimitive(value)
