package com.niandui.idectl.tools.project

import com.intellij.openapi.project.ex.ProjectManagerEx
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jObj

/** `close_project` — save and close a project window (useful after opening several). */
class CloseProjectTool : Tool {
    override val name = "close_project"
    override val description =
        "Save and close a project window. Resolves the target from the 'project' argument, the bound " +
            "project, or the single open one."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = true
    override val requiresProject = true
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name to close (optional if bound or only one is open)."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val name = project.name
        val path = project.basePath ?: ""
        val closed = ProjectManagerEx.getInstanceEx().forceCloseProjectAsync(project, /* save = */ true)
        return ToolCallResult.ok(jObj {
            addProperty("closed", closed)
            add("project", jObj {
                addProperty("name", name)
                addProperty("path", path)
            })
        })
    }
}
