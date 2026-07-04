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
import com.niandui.idectl.transport.obj
import com.niandui.idectl.transport.str
import com.niandui.idectl.transport.strArr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `update_run_configuration` — edit an existing named configuration's common parameters (program args,
 * VM options, env, working dir). Only the fields you pass are changed. VM options need a Java config.
 */
class UpdateRunConfigurationTool : Tool {
    override val name = "update_run_configuration"
    override val description =
        "Update an existing run configuration by name: program_args, vm_options, env, working_dir. Only " +
            "the fields you pass are changed."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "name" to Schema.string("Name of the configuration to update (required)."),
        "program_args" to Schema.string("Program arguments (replaces the existing value)."),
        "vm_options" to Schema.string("JVM options (replaces the existing value; Java configs only)."),
        "env" to Schema.stringMap("Environment variables (name→value)."),
        "working_dir" to Schema.string("Working directory."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        required = listOf("name"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val name = ctx.args.str("name")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'name' is required")
        val settings = RunManager.getInstance(project).findConfigurationByName(name)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no run configuration named '$name'", "call list_run_configurations")

        if (!ConfigSupport.supportsCommonParams(settings.configuration)) {
            throw ToolException(
                ErrorCodes.UNAVAILABLE,
                "configuration '$name' (${settings.type.id}) does not expose editable program parameters",
                null,
            )
        }
        val env = ctx.args.obj("env")?.let { ConfigSupport.parseEnv(it) }

        val applied = withContext(Dispatchers.EDT) {
            ConfigSupport.applyCommon(
                settings.configuration,
                programArgs = ctx.args.str("program_args"),
                vmOptions = ctx.args.str("vm_options"),
                env = env,
                workingDir = ctx.args.str("working_dir"),
            )
        }

        return ToolCallResult.ok(jObj {
            addProperty("updated", true)
            addProperty("name", name)
            add("appliedFields", strArr(applied))
        })
    }
}
