package com.niandui.idectl.tools.debug

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
 * `evaluate` (M4) — evaluate a Java expression in a paused frame. OPERATOR because expressions can call
 * methods with side effects (mutating program state), not just read values.
 */
class EvaluateTool : Tool {
    override val name = "evaluate"
    override val description =
        "Evaluate a Java expression in a stack frame of a paused debug session and return its value. " +
            "May have side effects (method calls), so it needs operator rights."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Debug session id (required)."),
        "expression" to Schema.string("Java expression to evaluate (required)."),
        "frame_index" to Schema.integer("Which stack frame to evaluate in (0 = current/top, default 0).", 0),
        "timeout_sec" to Schema.integer("Max seconds to wait for the result (default 10).", 10),
        required = listOf("session_id", "expression"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val expression = ctx.args.str("expression")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'expression' is required")
        val frameIndex = ctx.args.int("frame_index", 0).coerceAtLeast(0)
        val timeoutSec = ctx.args.int("timeout_sec", 10).coerceIn(1, 120)

        val (_, handle) = DebugSupport.handle(sessionId)
        val r = handle.evaluate(expression, frameIndex, timeoutSec * 1000L)
        return ToolCallResult.ok(jObj {
            addProperty("sessionId", sessionId)
            addProperty("expression", expression)
            addProperty("ok", r.ok)
            if (r.ok) {
                r.type?.takeIf { it.isNotBlank() }?.let { addProperty("type", it) }
                addProperty("value", r.value ?: "")
                addProperty("hasChildren", r.hasChildren)
            } else {
                addProperty("error", r.error ?: "evaluation failed")
            }
        })
    }
}
