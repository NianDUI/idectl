package com.niandui.idectl.tools.debug

import com.niandui.idectl.project.BridgeProjectService
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

/** `remove_breakpoint` (M4) — remove the line breakpoint at file:line, if present. */
class RemoveBreakpointTool : Tool {
    override val name = "remove_breakpoint"
    override val description = "Remove the line breakpoint at file:line. Returns removed=false if none was set there."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = true
    override val inputSchema = Schema.obj(
        "file" to Schema.string("Source file: absolute path, or relative to the project root (required)."),
        "line" to Schema.integer("1-based line number (required)."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        required = listOf("file", "line"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val file = ctx.args.str("file")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'file' is required")
        val line = ctx.args.int("line", 0)
        if (line < 1) throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'line' must be >= 1")

        val removed = BridgeProjectService.getInstance(project).debug.removeBreakpoint(file, line)
        return ToolCallResult.ok(jObj {
            addProperty("removed", removed)
            addProperty("file", file)
            addProperty("line", line)
        })
    }
}
