package com.niandui.idectl.tools.debug

import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `get_stack` (M4) — the call stack of a paused debug session (frame 0 = current). */
class GetStackTool : Tool {
    override val name = "get_stack"
    override val description =
        "Return the call stack of a paused debug session. Frame index 0 is the current (top) frame; " +
            "use these indices with get_variables / evaluate."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Debug session id (required)."),
        "max_frames" to Schema.integer("Maximum frames to return (default 50).", 50),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val maxFrames = ctx.args.int("max_frames", 50).coerceIn(1, 500)

        val (_, handle) = DebugSupport.handle(sessionId)
        val frames = handle.frames(maxFrames)
        return ToolCallResult.ok(jObj {
            addProperty("sessionId", sessionId)
            add("frames", jArr(frames.map { DebugSupport.frameJson(it) }))
            addProperty("count", frames.size)
        })
    }
}
