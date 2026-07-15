package com.niandui.idectl.transport

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.thisLogger
import com.niandui.idectl.Idectl
import com.niandui.idectl.IdectlService
import com.niandui.idectl.session.McpSession
import com.niandui.idectl.session.Principal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Ktor CIO embedded server (L1). Single `/mcp` endpoint, Streamable-HTTP:
 * POST carries JSON-RPC; GET is unsupported (405, no server-initiated SSE yet); DELETE ends a session.
 *
 * Bind host is loopback (`127.0.0.1`) by default; when the "allow LAN" setting is on it binds the
 * wildcard (`0.0.0.0`) so peers on the local network can reach it — the Bearer token stays the gate.
 */
class McpServer(private val app: IdectlService) {

    @Volatile var port: Int = -1
        private set

    /** Host the server is bound to: `127.0.0.1` (loopback) or `0.0.0.0` (LAN). */
    @Volatile var bindHost: String = LOOPBACK
        private set

    private var stopFn: (() -> Unit)? = null

    fun start(portBase: Int) {
        val host = if (app.settings.allowLan) WILDCARD else LOOPBACK
        val chosen = findFreePort(host, portBase, Idectl.PORT_SCAN_LIMIT)
        val server = embeddedServer(CIO, host = host, port = chosen) { installRouting() }
        server.start(wait = false)
        port = chosen
        bindHost = host
        stopFn = { runCatching { server.stop(500, 1500) } }
        thisLogger().info("IDE Control server listening on http://$host:$chosen/mcp")
    }

    fun stop() {
        stopFn?.invoke()
        stopFn = null
        port = -1
    }

    private fun Application.installRouting() {
        routing { mcpRoutes() }
    }

    private fun Routing.mcpRoutes() {
        post("/mcp") {
            val principal = when (val auth = app.authGate.check(
                host = call.request.headers[HttpHeaders.Host],
                origin = call.request.headers[HttpHeaders.Origin],
                authorization = call.request.headers[HttpHeaders.Authorization],
            )) {
                is AuthGate.Result.Rejected -> {
                    call.respondText(auth.reason, status = HttpStatusCode.fromValue(auth.status))
                    return@post
                }
                is AuthGate.Result.Ok -> auth.principal
            }

            val body = call.receiveText()
            val root = try {
                JsonRpc.parse(body)
            } catch (t: Throwable) {
                respondJson(call, JsonRpc.error(null, RpcCodes.PARSE_ERROR, "invalid JSON"))
                return@post
            }

            val sessionHeader = call.request.headers["Mcp-Session-Id"]

            if (root.isJsonArray) {
                val session = requireSession(call, sessionHeader, principal) ?: return@post
                val out = JsonArray()
                for (el in root.asJsonArray) {
                    val incoming = JsonRpc.parseMessage(el) ?: continue
                    app.adapter.handle(incoming, session)?.let { out.add(it) }
                }
                if (out.isEmpty) call.respond(HttpStatusCode.Accepted)
                else call.respondText(out.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                return@post
            }

            val incoming = JsonRpc.parseMessage(root)
            if (incoming == null) {
                respondJson(call, JsonRpc.error(null, RpcCodes.INVALID_REQUEST, "not a JSON-RPC request"))
                return@post
            }

            val session: McpSession = if (incoming is RpcRequest && incoming.method == "initialize") {
                app.sessions.create(principal).also {
                    call.response.headers.append("Mcp-Session-Id", it.id)
                }
            } else {
                requireSession(call, sessionHeader, principal) ?: return@post
            }

            val response = app.adapter.handle(incoming, session)
            if (response == null) call.respond(HttpStatusCode.Accepted)
            else call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/mcp") {
            // No server-initiated SSE stream in M0/M1; clients must use POST.
            call.respond(HttpStatusCode.MethodNotAllowed)
        }

        delete("/mcp") {
            app.sessions.remove(call.request.headers["Mcp-Session-Id"])
            call.respond(HttpStatusCode.OK)
        }
    }

    /** Look up a session by header, verifying it belongs to this token; responds + returns null on failure. */
    private suspend fun requireSession(
        call: ApplicationCall,
        sessionHeader: String?,
        principal: Principal,
    ): McpSession? {
        val session = app.sessions.get(sessionHeader)
        if (session == null) {
            // 404 → client must re-initialize (Streamable HTTP session-expiry semantics).
            respondJson(call, JsonRpc.error(null, RpcCodes.INVALID_REQUEST, "unknown or expired session"), HttpStatusCode.NotFound)
            return null
        }
        if (session.principal != principal) {
            call.respond(HttpStatusCode.Forbidden)
            return null
        }
        return session
    }

    private suspend fun respondJson(
        call: ApplicationCall,
        obj: JsonObject,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        call.respondText(obj.toString(), ContentType.Application.Json, status)
    }

    private fun findFreePort(host: String, base: Int, limit: Int): Int {
        for (p in base until base + limit) {
            try {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(host, p))
                }
                return p
            } catch (_: Exception) {
                // in use — try next
            }
        }
        error("no free port in [$base, ${base + limit})")
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val WILDCARD = "0.0.0.0"
    }
}
