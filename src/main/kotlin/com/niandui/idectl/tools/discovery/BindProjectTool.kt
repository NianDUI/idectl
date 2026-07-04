package com.niandui.idectl.tools.discovery

import com.niandui.idectl.gate.ProjectResolver
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `bind_project` — pin this MCP session's default project so later tools can omit `project` (03 §2.2). */
class BindProjectTool : Tool {
    override val name = "bind_project"
    override val description =
        "Bind this MCP session to a project (by path or name). Afterwards all tools default to it, " +
            "so you can omit the 'project' argument. The project must be within your token scope."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name to bind (required)."),
        required = listOf("project"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val hint = ctx.args.str("project")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'project' is required")
        val project = ProjectResolver.resolve(hint)
            ?: throw ToolException(
                ErrorCodes.NOT_FOUND, "project not found: $hint",
                "call get_ide_state to see openProjects[].path",
            )
        val path = project.basePath
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "project has no base path")
        if (!ctx.principal.mayAccessProject(path)) {
            throw ToolException(ErrorCodes.PERMISSION_DENIED, "project '${project.name}' is outside your token scope")
        }
        ctx.session.boundProjectPath = path
        return ToolCallResult.ok(jObj {
            addProperty("bound", true)
            add("project", jObj {
                addProperty("name", project.name)
                addProperty("path", path)
            })
        })
    }
}
