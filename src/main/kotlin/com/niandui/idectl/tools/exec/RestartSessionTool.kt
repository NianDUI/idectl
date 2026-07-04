package com.niandui.idectl.tools.exec

import com.google.gson.JsonNull
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/**
 * `restart_session` ★核心① — re-run a session, optionally switching Run↔Debug in one call.
 * `mode=same` reruns as-is; `mode=run|debug` stops the old process and relaunches under the target
 * executor. Terminated sessions can be restarted too (the stop step is skipped).
 */
class RestartSessionTool : Tool {
    override val name = "restart_session"
    override val description =
        "Re-run an execution session, optionally switching between Run and Debug (mode=run|debug), or " +
            "rerun as-is (mode=same). Works on running or already-exited sessions. Returns the new session " +
            "id (with restartOf) plus a ready/exit tail."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = true
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Session id to restart (required)."),
        "mode" to Schema.string("same | run | debug", listOf("same", "run", "debug")),
        "wait" to Schema.string("none | exit | ready", listOf("none", "exit", "ready")),
        "ready_pattern" to Schema.string("ERE matched against console output to detect readiness."),
        "timeout_sec" to Schema.integer("Max seconds to wait (default 120).", 120),
        "tail_lines" to Schema.integer("Trailing console lines to return (default 100).", 100),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val mode = ctx.args.str("mode") ?: "same"
        val wait = ctx.args.str("wait") ?: "ready"
        val readyPattern = ctx.args.str("ready_pattern")
        val timeoutSec = ctx.args.int("timeout_sec", 120).coerceIn(1, 3600)
        val tailLines = ctx.args.int("tail_lines", 100).coerceIn(0, 2000)

        val (project, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")
        val launcher = IdectlProjectService.getInstance(project).launcher

        val newRecord = launcher.restart(
            record = record,
            mode = mode,
            owner = ctx.principal.subject,
            timeoutMs = minOf(timeoutSec * 1000L, 30_000L),
        ) ?: return ToolCallResult.ok(jObj {
            add("sessionId", JsonNull.INSTANCE)
            addProperty("restartOf", sessionId)
            addProperty("state", "not_started")
        })

        return ToolCallResult.ok(
            RunConfigurationTool.buildResult(newRecord, wait, readyPattern, timeoutSec, tailLines, restartOf = sessionId),
        )
    }
}
