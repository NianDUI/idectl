package com.niandui.idectl.tools.project

import com.intellij.ide.GeneralSettings
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.niandui.idectl.gate.ProjectResolver
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.bool
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str
import java.io.File
import java.nio.file.Paths

/**
 * `open_project` — open (or import) a project by path, whether or not it is in the recent list.
 * Opens in a new window by default so it coexists with already-open projects. The path is marked
 * trusted first so no modal blocks the unattended open; indexing then continues in the background.
 */
class OpenProjectTool : Tool {
    override val name = "open_project"
    override val description =
        "Open or import a project by directory path (recent or brand-new). Opens in a new window by " +
            "default so other projects stay open. Auto-trusts the path; indexing continues after this returns."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "path" to Schema.string("Absolute path to the project root directory (required)."),
        "new_window" to Schema.bool("Open in a new window, keeping others open (default true).", true),
        required = listOf("path"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val pathStr = ctx.args.str("path")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'path' is required")
        val newWindow = ctx.args.bool("new_window", true)

        val dir = File(pathStr)
        if (!dir.isDirectory) throw ToolException(
            ErrorCodes.NOT_FOUND, "not a directory: $pathStr", "pass an absolute path to the project root",
        )
        val canonical = dir.canonicalPath
        if (!ctx.principal.mayAccessProject(canonical)) throw ToolException(
            ErrorCodes.PERMISSION_DENIED, "path '$canonical' is outside your token scope", null,
        )

        // Already open? Just report it (openOrImport would only focus it anyway).
        ProjectResolver.resolve(canonical)?.let { existing ->
            return ToolCallResult.ok(jObj {
                addProperty("state", "already_open")
                add("project", projectJson(existing.name, existing.basePath ?: canonical))
            })
        }

        val nioPath = Paths.get(canonical)
        TrustedProjects.setProjectTrusted(nioPath, true)
        val task = OpenProjectTask.build().withForceOpenInNewFrame(newWindow)

        // When a project is already open, IDEA otherwise pops a modal "this window / new window?"
        // confirmation that would block this unattended open. Pin the choice, then restore the user's.
        val settings = GeneralSettings.getInstance()
        val prevConfirm = settings.confirmOpenNewProject
        settings.confirmOpenNewProject =
            if (newWindow) GeneralSettings.OPEN_PROJECT_NEW_WINDOW else GeneralSettings.OPEN_PROJECT_SAME_WINDOW
        val opened = try {
            ProjectUtil.openOrImportAsync(nioPath, task)
        } finally {
            settings.confirmOpenNewProject = prevConfirm
        }
        val project = opened ?: throw ToolException(
            ErrorCodes.INTERNAL, "failed to open project at $canonical",
            "check that the directory is a valid project (has a build file or .idea)",
        )

        return ToolCallResult.ok(jObj {
            addProperty("state", "opened")
            add("project", projectJson(project.name, project.basePath ?: canonical))
            addProperty("note", "the project opened; indexing continues in the background")
        })
    }

    private fun projectJson(name: String, path: String) = jObj {
        addProperty("name", name)
        addProperty("path", path)
    }
}
