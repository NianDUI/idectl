package com.niandui.idectl.tools.build

import com.niandui.idectl.core.exec.ExecState
import com.niandui.idectl.core.hotswap.HotSwapService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.tools.exec.Sessions
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/**
 * `reload_classes` ★核心③ — hot-reload method-body changes into a debugged JVM without restarting.
 * Equivalent to rebuilding the modified class in IDEA and letting it auto-reload. Debug sessions only.
 * Structural changes (signatures / add-remove methods / classes) fail → restart_session(mode=debug).
 *
 * M2 first cut: reloads ALL modified classes (compile-before-hotswap). A per-file filter is a follow-up.
 */
class ReloadClassesTool : Tool {
    override val name = "reload_classes"
    override val description =
        "Hot-reload your latest method-body edits into a running DEBUG session's JVM without restarting " +
            "it (process pid unchanged). This COMPILES the changed sources itself then redefines them — " +
            "just edit the source and call this directly; do NOT run build first (a prior build consumes " +
            "the pending change, leaving this with nothing_to_reload). Structural changes (signature / add " +
            "or remove method / add or remove class) can't hot-swap — restart in debug."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Debug session id to reload into (required)."),
        "timeout_sec" to Schema.integer("Max seconds to wait for the hot-swap (default 60).", 60),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val timeoutMs = ctx.args.int("timeout_sec", 60).coerceIn(1, 300) * 1000L

        val (project, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")

        if (!record.isDebug) {
            throw ToolException(
                ErrorCodes.NOT_DEBUG_SESSION,
                "session $sessionId is a run session, not debug — hot reload needs a debugger",
                "restart_session(mode=debug) then reload_classes",
            )
        }
        if (record.state == ExecState.TERMINATED) {
            throw ToolException(
                ErrorCodes.NOT_DEBUG_SESSION,
                "session $sessionId has terminated — no live JVM to reload into",
                "restart_session(mode=debug) first",
            )
        }

        val service = HotSwapService(project)
        val debuggerSession = service.findDebuggerSession(record.processHandler)
            ?: throw ToolException(
                ErrorCodes.NOT_DEBUG_SESSION,
                "no live Java debugger session bound to $sessionId",
                "ensure the session is running under Debug",
            )

        val outcome = service.reload(debuggerSession, timeoutMs)

        val (outcomeStr, compileStatus, advice) = when (outcome) {
            HotSwapService.Outcome.RELOADED -> Triple("reloaded", "ok", null)
            HotSwapService.Outcome.NOTHING_TO_RELOAD -> Triple(
                "nothing_to_reload", "ok",
                "no changed classes detected. If you ran build before this, that already consumed the " +
                    "pending change — reload_classes compiles for you, so just edit the source and call it " +
                    "directly with no separate build. Otherwise the running bytecode already matches source.",
            )
            HotSwapService.Outcome.CANCELLED -> Triple("cancelled", "unknown", null)
            HotSwapService.Outcome.TIMEOUT -> Triple(
                "failed", "unknown",
                "hot-swap timed out; the target JVM may be busy or paused",
            )
            HotSwapService.Outcome.FAILED -> Triple(
                "failed", "unknown",
                "compile failed, or a structural change (signature / add-remove method / class) that " +
                    "cannot hot-swap — fix the code, or restart_session(mode=debug)",
            )
        }

        return ToolCallResult.ok(jObj {
            addProperty("outcome", outcomeStr)
            add("compile", jObj { addProperty("status", compileStatus) })
            if (advice != null) addProperty("advice", advice)
        })
    }
}
