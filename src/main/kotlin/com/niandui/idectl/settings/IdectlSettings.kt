package com.niandui.idectl.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** One additional scoped token (M3 governance). `token` is the pre-shared secret; empty projects = all. */
class TokenEntry : BaseState() {
    var token: String? by string()
    var subject: String? by string()
    var role: String? by string() // "viewer" | "operator" | "admin"
    var projects: MutableList<String> by list()
}

/**
 * Per-tool policy override (settings "工具能力与策略" panel). Stored sparsely: only tools that differ
 * from the defaults (enabled, no approval, no timeout override) get an entry, so newly-shipped tools
 * default to enabled and "恢复默认" is just clearing the list.
 */
class ToolPolicy : BaseState() {
    var name: String? by string()
    var disabled: Boolean by property(false)
    var requireApproval: Boolean by property(false)
    var timeoutSec: Int by property(0) // 0 = use the tool's own default
}

/**
 * Persisted server configuration. `token` is the primary admin token (M0). M3 adds a list of
 * additional scoped tokens ([TokenEntry]) so different agents can be handed different roles and
 * project scopes. Tokens live in plain settings XML (localhost-only bearer secrets; local threat model).
 */
@Service(Service.Level.APP)
@State(name = "IdectlSettings", storages = [Storage("idectl.xml")])
class IdectlSettings : SimplePersistentStateComponent<IdectlSettings.State>(State()) {

    class State : BaseState() {
        var enabled: Boolean by property(true)
        var portBase: Int by property(com.niandui.idectl.Idectl.PORT_BASE)
        var token: String? by string()

        // Bind the server to 0.0.0.0 (LAN-reachable) instead of loopback-only. Off by default:
        // the localhost bind is the primary reachability fence. When on, the anti-DNS-rebinding
        // Host/Origin fence is relaxed for private-range peers and the Bearer token is the gate.
        var allowLan: Boolean by property(false)

        // Console buffering (core②). Frugal on RAM: the memory ring stays small and evicted lines
        // spill to a bounded on-disk archive (≈0 heap cost) instead of forcing a bigger RAM buffer
        // to retain history — so archive defaults ON. Disk is bounded + freed on session eviction.
        var consoleMemoryLines: Int by property(10_000)
        var consoleMemoryMb: Int by property(8)
        var consoleArchiveEnabled: Boolean by property(true)
        var consoleArchiveMb: Int by property(64)

        // M3 governance: additional scoped tokens beyond the primary admin token.
        var tokens: MutableList<TokenEntry> by list()

        // Tool policy panel: per-tool overrides (enable/disable, human approval, timeout override)
        // and the shared human-approval timeout. Sparse: absent tool name = all defaults.
        var toolPolicies: MutableList<ToolPolicy> by list()
        var approvalTimeoutSec: Int by property(120)
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var portBase: Int
        get() = state.portBase
        set(value) { state.portBase = value }

    /** Bind on 0.0.0.0 so LAN peers can reach the server; the settings page re-binds live on change. */
    var allowLan: Boolean
        get() = state.allowLan
        set(value) { state.allowLan = value }

    var token: String?
        get() = state.token
        set(value) { state.token = value }

    /** Max console lines kept in memory per session (hot window). */
    var consoleMemoryLines: Int
        get() = state.consoleMemoryLines
        set(value) { state.consoleMemoryLines = value }

    /** Max console bytes kept in memory per session (MB). */
    var consoleMemoryMb: Int
        get() = state.consoleMemoryMb
        set(value) { state.consoleMemoryMb = value }

    /** Spill evicted console lines to a bounded on-disk archive so old logs stay retrievable. */
    var consoleArchiveEnabled: Boolean
        get() = state.consoleArchiveEnabled
        set(value) { state.consoleArchiveEnabled = value }

    /** Max on-disk archive size per session (MB); oldest segment dropped past this. */
    var consoleArchiveMb: Int
        get() = state.consoleArchiveMb
        set(value) { state.consoleArchiveMb = value }

    /** Live-mutable list of additional scoped tokens (M3). Mutations are tracked + persisted. */
    val tokens: MutableList<TokenEntry> get() = state.tokens

    /** Live-mutable list of per-tool policy overrides. Mutations are tracked + persisted. */
    val toolPolicies: MutableList<ToolPolicy> get() = state.toolPolicies

    /** Seconds a human-approval balloon stays actionable before the call is auto-denied (fail-closed). */
    var approvalTimeoutSec: Int
        get() = state.approvalTimeoutSec
        set(value) { state.approvalTimeoutSec = value }

    private fun policyOf(name: String): ToolPolicy? = state.toolPolicies.firstOrNull { it.name == name }

    /** Disabled tools are unavailable to every caller (even admin) — the owner's kill switch. */
    fun isToolDisabled(name: String): Boolean = policyOf(name)?.disabled == true

    fun toolRequiresApproval(name: String): Boolean = policyOf(name)?.requireApproval == true

    /** Per-tool timeout override in seconds, or 0 when none is configured. */
    fun toolTimeoutSecOverride(name: String): Int = policyOf(name)?.timeoutSec ?: 0

    /** Drop all per-tool overrides back to defaults (enabled, no approval, no timeout override). */
    fun resetToolPolicies() { state.toolPolicies.clear() }

    companion object {
        fun getInstance(): IdectlSettings = service()
    }
}
