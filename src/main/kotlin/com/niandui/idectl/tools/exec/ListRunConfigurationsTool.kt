package com.niandui.idectl.tools.exec

import com.google.gson.JsonObject
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.readAction
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `list_run_configurations` — enumerate run configurations addressable as (project, typeId, name). */
class ListRunConfigurationsTool : Tool {
    override val name = "list_run_configurations"
    override val description =
        "List this project's run configurations. Address one later by (name, type_id). " +
            "Spring Boot / Tomcat etc. appear by their typeId string."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Project path or name (optional if bound)."),
        "type_id" to Schema.string("Filter by configuration typeId."),
        "name_contains" to Schema.string("Case-insensitive name substring filter."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val typeId = ctx.args.str("type_id")
        val nameContains = ctx.args.str("name_contains")
        val launcher = IdectlProjectService.getInstance(project).launcher

        val settingsList = launcher.listConfigurations(typeId, nameContains)
        val items: List<JsonObject> = readAction {
            settingsList.map { s ->
                val config = s.configuration
                val canDebug = ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, config) != null
                val allowParallel = (config as? RunConfigurationBase<*>)?.isAllowRunningInParallel ?: false
                jObj {
                    addProperty("name", s.name)
                    addProperty("typeId", s.type.id)
                    addProperty("typeDisplayName", s.type.displayName)
                    addProperty("folder", s.folderName ?: "")
                    addProperty("isTemporary", s.isTemporary)
                    addProperty("allowParallel", allowParallel)
                    addProperty("canDebug", canDebug)
                }
            }
        }
        return ToolCallResult.ok(jObj {
            add("configurations", jArr(items))
            addProperty("total", items.size)
        })
    }
}
