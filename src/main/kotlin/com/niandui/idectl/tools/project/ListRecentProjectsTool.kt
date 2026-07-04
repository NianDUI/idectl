package com.niandui.idectl.tools.project

import com.intellij.ide.RecentProjectsManagerBase
import com.niandui.idectl.gate.ProjectResolver
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import java.io.File

/** `list_recent_projects` — the IDE's recent-projects list, scoped to the caller, with open status. */
class ListRecentProjectsTool : Tool {
    override val name = "list_recent_projects"
    override val description =
        "List the IDE's recent projects (path, name, whether currently open) so you can pick one to " +
            "open_project. Only projects within your token scope are shown."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj()

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val openPaths = ProjectResolver.openProjects().mapNotNull { it.basePath }.toSet()
        val recents = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
            .filter { ctx.principal.mayAccessProject(it) }
            .map { path ->
                jObj {
                    addProperty("path", path)
                    addProperty("name", File(path).name)
                    addProperty("open", path in openPaths)
                    addProperty("exists", File(path).isDirectory)
                }
            }
        return ToolCallResult.ok(jObj {
            add("recentProjects", jArr(recents))
            addProperty("count", recents.size)
        })
    }
}
