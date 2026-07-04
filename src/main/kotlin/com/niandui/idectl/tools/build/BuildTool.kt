package com.niandui.idectl.tools.build

import com.niandui.idectl.core.build.BuildService
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str
import com.niandui.idectl.transport.strList

/**
 * `build` — one tool over the IDE Build menu's four semantics (05). Line/column are 1-based.
 * Maps to ProjectTaskManager: project+incremental=buildAllModules ⌘F9, project+rebuild=rebuildAllModules,
 * module=build/rebuild(modules), files=compile(files) ⇧⌘F9.
 */
class BuildTool : Tool {
    override val name = "build"
    override val description =
        "Build the project, specific modules, or specific files, incrementally or full rebuild. " +
            "Returns structured errors with 1-based file:line. Builds on one project serialize."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name (optional if bound)."),
        "scope" to Schema.string("project | module | files", listOf("project", "module", "files")),
        "mode" to Schema.string("incremental | rebuild", listOf("incremental", "rebuild")),
        "modules" to Schema.stringArray("Module names (required when scope=module)."),
        "files" to Schema.stringArray("Absolute file paths (required when scope=files)."),
        "timeout_sec" to Schema.integer("Max seconds to wait (default 300).", 300),
        "max_errors" to Schema.integer("Max error entries to return (default 50).", 50),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val scope = ctx.args.str("scope") ?: "project"
        val mode = ctx.args.str("mode") ?: "incremental"
        val modules = ctx.args.strList("modules")
        val files = ctx.args.strList("files")
        val timeoutMs = ctx.args.int("timeout_sec", 300).coerceIn(5, 1800) * 1000L
        val maxErrors = ctx.args.int("max_errors", 50).coerceIn(1, 500)

        val service: BuildService = IdectlProjectService.getInstance(project).buildService
        val result = service.build(scope, mode, modules, files, timeoutMs)

        val shown = result.errors.take(maxErrors).map { e ->
            jObj {
                addProperty("file", e.file ?: "")
                e.line?.let { addProperty("line", it) }
                e.column?.let { addProperty("column", it) }
                addProperty("message", e.message)
                addProperty("severity", e.severity)
                e.module?.let { addProperty("module", it) }
            }
        }
        return ToolCallResult.ok(jObj {
            addProperty("status", result.status)
            add("errors", jArr(shown))
            addProperty("errorsTotal", result.errorsTotal)
            addProperty("warningsTotal", result.warningsTotal)
            addProperty("durationMs", result.durationMs)
            if (result.queued) addProperty("queued", true)
        })
    }
}
