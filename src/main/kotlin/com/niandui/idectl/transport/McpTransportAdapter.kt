package com.niandui.idectl.transport

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.thisLogger
import com.niandui.idectl.IdeaBridge
import com.niandui.idectl.IdeaBridgeService
import com.niandui.idectl.session.McpSession
import com.niandui.idectl.tools.ToolCallResult
import kotlinx.coroutines.CancellationException

/**
 * The MCP protocol boundary (L1). Everything MCP-specific (method names, result shapes,
 * negotiation) lives here so the rest of the plugin never depends on a transport SDK; this is
 * the isolation boundary D1 mandates. We speak Streamable-HTTP JSON-RPC by hand for now.
 */
class McpTransportAdapter(private val app: IdeaBridgeService) {

    /** Dispatch one message. Returns a JSON-RPC response object for requests, null for notifications. */
    suspend fun handle(incoming: RpcIncoming, session: McpSession): JsonObject? = when (incoming) {
        is RpcNotification -> { handleNotification(incoming, session); null }
        is RpcRequest -> handleRequest(incoming, session)
    }

    private suspend fun handleRequest(req: RpcRequest, session: McpSession): JsonObject = try {
        val result = when (req.method) {
            "initialize" -> initialize(req.params, session)
            "ping" -> JsonObject()
            "tools/list" -> toolsList(session)
            "tools/call" -> toolsCall(req.params, session)
            else -> throw RpcException(RpcCodes.METHOD_NOT_FOUND, "unknown method: ${req.method}")
        }
        JsonRpc.result(req.id, result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RpcException) {
        JsonRpc.error(req.id, e.code, e.message, e.data)
    } catch (t: Throwable) {
        thisLogger().warn("MCP request '${req.method}' failed", t)
        JsonRpc.error(req.id, RpcCodes.INTERNAL_ERROR, t.message ?: t.javaClass.simpleName)
    }

    private fun handleNotification(n: RpcNotification, session: McpSession) {
        when (n.method) {
            "notifications/initialized" -> session.initialized = true
            else -> { /* progress/cancelled: no-op for M0 */ }
        }
    }

    private fun initialize(params: JsonObject?, session: McpSession): JsonObject {
        val requested = params.str("protocolVersion")
        val negotiated = if (requested != null && requested in IdeaBridge.SUPPORTED_PROTOCOLS) requested
        else IdeaBridge.PROTOCOL_VERSION
        session.protocolVersion = negotiated
        val client = params.obj("clientInfo")
        session.clientInfo = client?.let { "${it.str("name")}/${it.str("version")}" }
        return jObj {
            addProperty("protocolVersion", negotiated)
            add("capabilities", jObj {
                add("tools", jObj { addProperty("listChanged", false) })
            })
            add("serverInfo", jObj {
                addProperty("name", IdeaBridge.SERVER_NAME)
                addProperty("version", IdeaBridge.pluginVersion())
            })
            addProperty(
                "instructions",
                "IdeaBridge controls this IntelliJ IDEA: run/restart configurations (run↔debug), " +
                    "read & grep live console logs, build, and hot-reload. Call get_ide_state first.",
            )
        }
    }

    private fun toolsList(session: McpSession): JsonObject {
        val tools = app.registry.visibleTo(session.principal.role)
            .filter { !app.settings.isToolDisabled(it.name) } // owner kill switch (policy panel)
            .map { t ->
            jObj {
                addProperty("name", t.name)
                addProperty("description", t.description)
                add("inputSchema", t.inputSchema)
                add("annotations", jObj {
                    addProperty("readOnlyHint", t.readOnly)
                    addProperty("destructiveHint", t.destructive)
                })
            }
        }
        return jObj { add("tools", jArr(tools)) }
    }

    private suspend fun toolsCall(params: JsonObject?, session: McpSession): JsonObject {
        val name = params.str("name")
            ?: throw RpcException(RpcCodes.INVALID_PARAMS, "missing 'name'")
        val tool = app.registry.get(name)
            ?: throw RpcException(RpcCodes.INVALID_PARAMS, "no such tool: $name")
        val args = params.obj("arguments") ?: JsonObject()
        val result = app.gate.dispatch(tool, session, args)
        return renderToolResult(result)
    }

    private fun renderToolResult(result: ToolCallResult): JsonObject = jObj {
        add("content", jArrOf(jObj {
            addProperty("type", "text")
            addProperty("text", result.text())
        }))
        // structuredContent must be an object per spec; our results always are.
        add("structuredContent", result.structured as? JsonObject ?: JsonNull.INSTANCE)
        addProperty("isError", result.isError)
    }
}
