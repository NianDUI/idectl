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
import com.niandui.idectl.transport.strList

/**
 * `get_variables` (M4) — variables visible in a frame. Pass `path` (a chain of variable names) to
 * drill into an object/collection one level at a time, e.g. path=["this","items"].
 */
class GetVariablesTool : Tool {
    override val name = "get_variables"
    override val description =
        "List variables in a stack frame of a paused debug session. Give 'path' (names) to expand a " +
            "variable's children, e.g. [\"this\",\"buffer\"]. Each entry reports name, type, value, hasChildren. " +
            "For java.util collections (their contents render asynchronously) use evaluate instead, " +
            "e.g. evaluate(\"items.size()\") / evaluate(\"items.get(0)\")."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Debug session id (required)."),
        "frame_index" to Schema.integer("Which stack frame (0 = current/top, default 0).", 0),
        "path" to Schema.stringArray("Optional chain of variable names to expand into children."),
        "max" to Schema.integer("Maximum variables to return (default 100).", 100),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val frameIndex = ctx.args.int("frame_index", 0).coerceAtLeast(0)
        val path = ctx.args.strList("path") ?: emptyList()
        val max = ctx.args.int("max", 100).coerceIn(1, 1000)

        val (_, handle) = DebugSupport.handle(sessionId)
        val vars = handle.variables(frameIndex, path, max)
        return ToolCallResult.ok(jObj {
            addProperty("sessionId", sessionId)
            addProperty("frameIndex", frameIndex)
            add("path", jArr(path.map { com.google.gson.JsonPrimitive(it) }))
            add("variables", jArr(vars.map { DebugSupport.varJson(it) }))
            addProperty("count", vars.size)
        })
    }
}
