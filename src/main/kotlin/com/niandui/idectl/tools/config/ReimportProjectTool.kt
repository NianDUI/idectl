package com.niandui.idectl.tools.config

import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jObj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `reimport_project` — re-import / re-sync the build model (Maven, Gradle) after pom.xml/build.gradle
 * changed on disk (e.g. edited via git/CLI), so dependencies and module structure are refreshed. Uses
 * the platform's external-system auto-reload, so it covers every registered build system generically.
 */
class ReimportProjectTool : Tool {
    override val name = "reimport_project"
    override val description =
        "Re-import the build model (Maven/Gradle) so dependency and module changes in pom.xml/build.gradle " +
            "are picked up. Run after editing build files externally (pair with refresh_vfs). Runs in background."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name (optional if bound)."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        withContext(Dispatchers.EDT) {
            val tracker = ExternalSystemProjectTracker.getInstance(project)
            tracker.markDirtyAllProjects()
            tracker.scheduleProjectRefresh()
        }
        return ToolCallResult.ok(jObj {
            addProperty("reimport", "scheduled")
            addProperty("project", project.basePath ?: "")
            addProperty("note", "the build model re-imports in the background; give it a moment before build/run")
        })
    }
}
