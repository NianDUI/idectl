package com.niandui.idectl.tools.exec

import com.niandui.idectl.core.console.Stream
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.bool
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `stop_session` — soft-kill (graceful) then force-fallback; Remote debug detaches (03 §3.4). */
class StopSessionTool : Tool {
    override val name = "stop_session"
    override val description =
        "Stop a running session by id. Soft-kills first (graceful shutdown) then force-kills on timeout; " +
            "Remote JVM Debug sessions detach instead."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = true
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Session id to stop (required)."),
        "force" to Schema.bool("Skip graceful shutdown and destroy immediately.", false),
        "timeout_sec" to Schema.integer("Seconds to await termination before force fallback (default 20).", 20),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val force = ctx.args.bool("force", false)
        val timeoutSec = ctx.args.int("timeout_sec", 20).coerceIn(1, 120)

        val (project, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")
        val launcher = IdectlProjectService.getInstance(project).launcher

        val result = launcher.stop(record, force, timeoutSec * 1000L)

        val store = record.console
        val next = store.buffer.currentNextOffset()
        val from = (next - 20).coerceAtLeast(store.buffer.firstAvailableOffset())
        val finalTail = store.read(from, 20, 64 * 1024, null).lines

        return ToolCallResult.ok(jObj {
            addProperty("state", Sessions.stateName(record))
            result.exitCode?.let { addProperty("exitCode", it) }
            addProperty("detached", result.detached)
            add("finalTail", jArr(finalTail.map { Sessions.lineJson(it) }))
        })
    }
}
