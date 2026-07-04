package com.niandui.idectl.transport

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

const val JSONRPC_VERSION = "2.0"

/** A parsed inbound JSON-RPC message. */
sealed interface RpcIncoming {
    val method: String
    val params: JsonObject?
}

class RpcRequest(val id: JsonElement, override val method: String, override val params: JsonObject?) : RpcIncoming
class RpcNotification(override val method: String, override val params: JsonObject?) : RpcIncoming

/** JSON-RPC / MCP protocol-level error (distinct from a tool-execution error). */
class RpcException(val code: Int, override val message: String, val data: JsonElement? = null) : RuntimeException(message)

object RpcCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

object JsonRpc {

    /** Parse one JSON object into a request/notification, or null if it is a response/invalid. */
    fun parseMessage(element: JsonElement): RpcIncoming? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val method = obj.get("method")?.takeUnless { it.isJsonNull }?.asString ?: return null
        val params = obj.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
        val idEl = obj.get("id")
        return if (idEl != null && !idEl.isJsonNull) RpcRequest(idEl, method, params)
        else RpcNotification(method, params)
    }

    fun parse(raw: String): JsonElement = JsonParser.parseString(raw)

    fun result(id: JsonElement, result: JsonElement): JsonObject = jObj {
        addProperty("jsonrpc", JSONRPC_VERSION)
        add("id", id)
        add("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject = jObj {
        addProperty("jsonrpc", JSONRPC_VERSION)
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("error", jObj {
            addProperty("code", code)
            addProperty("message", message)
            if (data != null) add("data", data)
        })
    }
}
