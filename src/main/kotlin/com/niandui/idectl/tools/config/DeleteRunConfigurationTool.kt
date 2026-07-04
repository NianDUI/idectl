package com.niandui.idectl.tools.config

import com.intellij.execution.RunManager
import com.intellij.openapi.application.EDT
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `delete_run_configuration` — remove a named run configuration. */
class DeleteRunConfigurationTool : Tool {
    override val name = "delete_run_configuration"
    override val description = "Delete a run configuration by name. Returns deleted=false if none matched."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = true
    override val inputSchema = Schema.obj(
        "name" to Schema.string("Name of the configuration to delete (required)."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        required = listOf("name"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val name = ctx.args.str("name")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'name' is required")
        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(name)
        val deleted = settings != null
        if (settings != null) {
            withContext(Dispatchers.EDT) { runManager.removeConfiguration(settings) }
        }
        return ToolCallResult.ok(jObj {
            addProperty("deleted", deleted)
            addProperty("name", name)
        })
    }
}
