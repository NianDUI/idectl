package com.niandui.idectl.tools.debug

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
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `set_breakpoint` (M4) — add or update a Java line breakpoint; idempotent per file:line. */
class SetBreakpointTool : Tool {
    override val name = "set_breakpoint"
    override val description =
        "Set (or update) a line breakpoint at file:line before or during a debug session. Idempotent: " +
            "re-setting the same file:line updates it. Optional condition is a Java boolean expression."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "file" to Schema.string("Source file: absolute path, or relative to the project root (required)."),
        "line" to Schema.integer("1-based line number (required)."),
        "enabled" to Schema.bool("Whether the breakpoint is active (default true).", true),
        "condition" to Schema.string("Optional Java boolean expression; the breakpoint only stops when true."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        required = listOf("file", "line"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val file = ctx.args.str("file")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'file' is required")
        val line = ctx.args.int("line", 0)
        if (line < 1) throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'line' must be >= 1")
        val enabled = ctx.args.bool("enabled", true)
        val condition = ctx.args.str("condition")

        val bp = IdectlProjectService.getInstance(project).debug.setBreakpoint(file, line, enabled, condition)
        return ToolCallResult.ok(jObj { add("breakpoint", DebugSupport.bpJson(bp)) })
    }
}
