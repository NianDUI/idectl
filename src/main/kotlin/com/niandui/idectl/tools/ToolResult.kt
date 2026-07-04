package com.niandui.idectl.tools

import com.google.gson.JsonElement
import com.niandui.idectl.transport.GSON
import com.niandui.idectl.transport.jObj

/**
 * Transport-agnostic result of a tool call. Always carries a structured payload; the transport
 * renders it as both `structuredContent` and a text content block (for text-only clients).
 */
class ToolCallResult(
    val structured: JsonElement,
    val isError: Boolean = false,
) {
    fun text(): String = GSON.toJson(structured)

    companion object {
        fun ok(structured: JsonElement): ToolCallResult = ToolCallResult(structured, isError = false)

        fun error(code: String, message: String, remediation: String? = null): ToolCallResult =
            ToolCallResult(
                jObj {
                    addProperty("code", code)
                    addProperty("message", message)
                    if (remediation != null) addProperty("remediation", remediation)
                },
                isError = true,
            )

        fun error(e: ToolException): ToolCallResult = error(e.code, e.message, e.remediation)
    }
}
