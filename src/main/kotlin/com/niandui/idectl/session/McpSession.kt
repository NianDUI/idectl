package com.niandui.idectl.session

import java.util.concurrent.ConcurrentHashMap

/**
 * One MCP client connection, keyed by Mcp-Session-Id (L3). Holds the negotiated protocol,
 * the bound project (D4), and the authenticated principal. Each session gets an isolated
 * SupervisorJob scope elsewhere so one client's failure never affects another.
 */
class McpSession(
    val id: String,
    val principal: Principal,
) {
    @Volatile var initialized: Boolean = false
    @Volatile var protocolVersion: String? = null
    @Volatile var clientInfo: String? = null

    /** Project this session defaults to (D4). Null until bound or auto-bound. */
    @Volatile var boundProjectPath: String? = null

    @Volatile var lastAccessMs: Long = System.currentTimeMillis()

    fun touch() { lastAccessMs = System.currentTimeMillis() }
}

/** Registry of live MCP sessions across all connected clients. */
class SessionManager {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    fun create(principal: Principal): McpSession {
        val id = java.util.UUID.randomUUID().toString()
        val s = McpSession(id, principal)
        sessions[id] = s
        return s
    }

    fun get(id: String?): McpSession? = id?.let { sessions[it] }?.also { it.touch() }

    fun remove(id: String?): McpSession? = id?.let { sessions.remove(it) }

    fun all(): Collection<McpSession> = sessions.values
}
