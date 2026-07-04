package com.niandui.idectl.tools.exec

import com.niandui.idectl.core.exec.ExecState
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `list_sessions` — running & recently-terminated sessions, including user-launched ones (core①). */
class ListSessionsTool : Tool {
    override val name = "list_sessions"
    override val description =
        "List execution sessions for this project, including ones the user started by hand " +
            "(startedBy=user). Terminated sessions remain listed (with logs) for 30 minutes."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name (optional if bound)."),
        "state" to Schema.string("running | terminated | all", listOf("running", "terminated", "all")),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val stateFilter = ctx.args.str("state") ?: "running"
        val executions = IdectlProjectService.getInstance(project).executions

        val all = executions.list(includeTerminated = stateFilter != "running")
        val filtered = when (stateFilter) {
            "terminated" -> all.filter { it.state == ExecState.TERMINATED }
            else -> all // running (already excludes terminated) or all
        }
        return ToolCallResult.ok(jObj {
            add("sessions", jArr(filtered.map { Sessions.toJson(it) }))
            addProperty("total", filtered.size)
        })
    }
}
