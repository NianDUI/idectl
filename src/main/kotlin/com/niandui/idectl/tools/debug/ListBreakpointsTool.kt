package com.niandui.idectl.tools.debug

import com.niandui.idectl.project.BridgeProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj

/** `list_breakpoints` (M4) — every line breakpoint currently set in the project. */
class ListBreakpointsTool : Tool {
    override val name = "list_breakpoints"
    override val description = "List all line breakpoints set in the project (file, line, enabled, condition)."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name (optional if bound)."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val bps = BridgeProjectService.getInstance(project).debug.listBreakpoints()
        return ToolCallResult.ok(jObj {
            add("breakpoints", jArr(bps.map { DebugSupport.bpJson(it) }))
            addProperty("count", bps.size)
        })
    }
}
