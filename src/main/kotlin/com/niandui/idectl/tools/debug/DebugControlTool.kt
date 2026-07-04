package com.niandui.idectl.tools.debug

import com.niandui.idectl.core.debug.DebugAction
import com.niandui.idectl.project.BridgeProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.tools.exec.Sessions
import com.niandui.idectl.transport.bool
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/**
 * `debug_control` (M4) — resume/pause/step a paused debug session. With wait=true (default) it blocks
 * until the session pauses again (next breakpoint/step) or the process ends, then reports the location.
 */
class DebugControlTool : Tool {
    override val name = "debug_control"
    override val description =
        "Drive a debug session: resume | pause | step_over | step_into | step_out | run_to_line. " +
            "With wait=true, blocks until it pauses again (or the process exits) and returns the new location."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Debug session id (required)."),
        "action" to Schema.string(
            "resume | pause | step_over | step_into | step_out | run_to_line",
            listOf("resume", "pause", "step_over", "step_into", "step_out", "run_to_line"),
        ),
        "wait" to Schema.bool("Block until the next pause / exit before returning (default true).", true),
        "timeout_sec" to Schema.integer("Max seconds to wait for the next pause (default 30).", 30),
        "file" to Schema.string("For run_to_line: target source file (absolute or project-relative)."),
        "line" to Schema.integer("For run_to_line: 1-based target line."),
        required = listOf("session_id", "action"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val actionStr = ctx.args.str("action")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'action' is required")
        val action = when (actionStr) {
            "resume" -> DebugAction.RESUME
            "pause" -> DebugAction.PAUSE
            "step_over" -> DebugAction.STEP_OVER
            "step_into" -> DebugAction.STEP_INTO
            "step_out" -> DebugAction.STEP_OUT
            "run_to_line" -> DebugAction.RUN_TO_LINE
            else -> throw ToolException(ErrorCodes.INVALID_ARGUMENT, "unknown action '$actionStr'")
        }
        val wait = ctx.args.bool("wait", true)
        val timeoutSec = ctx.args.int("timeout_sec", 30).coerceIn(1, 3600)

        val (project, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")
        val controller = BridgeProjectService.getInstance(project).debug
        val handle = controller.handleFor(record)

        var runToFile: com.intellij.openapi.vfs.VirtualFile? = null
        var runToLine0: Int? = null
        if (action == DebugAction.RUN_TO_LINE) {
            val file = ctx.args.str("file")
                ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "run_to_line requires 'file'")
            val line = ctx.args.int("line", 0)
            if (line < 1) throw ToolException(ErrorCodes.INVALID_ARGUMENT, "run_to_line requires 'line' >= 1")
            runToFile = controller.resolveFile(file)
                ?: throw ToolException(ErrorCodes.NOT_FOUND, "file not found: $file", null)
            runToLine0 = line - 1
        }

        val outcome = handle.control(action, if (wait) timeoutSec * 1000L else 0L, runToFile, runToLine0)
        return ToolCallResult.ok(jObj {
            addProperty("sessionId", sessionId)
            addProperty("state", outcome.state)
            addProperty("paused", outcome.paused)
            DebugSupport.locationJson(outcome.location)?.let { add("location", it) }
        })
    }
}
