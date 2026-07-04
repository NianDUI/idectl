package com.niandui.idectl.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Tiny JSON-Schema builder for tool inputSchema declarations. */
object Schema {

    fun obj(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
            if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } })
            addProperty("additionalProperties", false)
        }

    fun string(description: String, enum: List<String>? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
            if (enum != null) add("enum", JsonArray().apply { enum.forEach { add(it) } })
        }

    fun integer(description: String, default: Int? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "integer")
            addProperty("description", description)
            if (default != null) addProperty("default", default)
        }

    fun bool(description: String, default: Boolean? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "boolean")
            addProperty("description", description)
            if (default != null) addProperty("default", default)
        }

    fun stringArray(description: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "array")
            addProperty("description", description)
            add("items", JsonObject().apply { addProperty("type", "string") })
        }

    /** A free-form object of string→string entries (e.g. environment variables). */
    fun stringMap(description: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            addProperty("description", description)
            add("additionalProperties", JsonObject().apply { addProperty("type", "string") })
        }
}
