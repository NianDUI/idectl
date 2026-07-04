package com.niandui.idectl.tools.project

import com.niandui.idectl.core.util.refreshPaths
import com.niandui.idectl.core.util.refreshProjectVfs
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.strList

/**
 * `refresh_vfs` — sync the IDE's virtual file system with on-disk changes made outside it (e.g. a
 * git checkout/pull or edits via other tools), so a subsequent build/reload_classes sees them.
 */
class RefreshVfsTool : Tool {
    override val name = "refresh_vfs"
    override val description =
        "Refresh the IDE's view of files changed on disk outside it (after git operations or external " +
            "edits) so build/reload_classes don't act on stale content. Refreshes the whole project, or " +
            "just the given paths."
    override val minRole = Role.OPERATOR
    override val readOnly = true
    override val destructive = false
    override val inputSchema = Schema.obj(
        "paths" to Schema.stringArray("Specific files/dirs to refresh (absolute or project-relative); empty = whole project."),
        "project" to Schema.string("Project path or name (optional if bound)."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val paths = ctx.args.strList("paths") ?: emptyList()
        return if (paths.isEmpty()) {
            refreshProjectVfs(project)
            ToolCallResult.ok(jObj {
                addProperty("refreshed", true)
                addProperty("scope", "project")
            })
        } else {
            val n = refreshPaths(project.basePath, paths)
            ToolCallResult.ok(jObj {
                addProperty("refreshed", true)
                addProperty("scope", "paths")
                addProperty("resolved", n)
                addProperty("requested", paths.size)
            })
        }
    }
}
