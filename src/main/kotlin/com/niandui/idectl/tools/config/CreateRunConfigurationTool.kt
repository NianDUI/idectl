package com.niandui.idectl.tools.config

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.EDT
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.bool
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.obj
import com.niandui.idectl.transport.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `create_run_configuration` — create and save a named run configuration (Application or JUnit) with
 * program args / VM options / env, so it persists in the Run dropdown and can be launched by name.
 */
class CreateRunConfigurationTool : Tool {
    override val name = "create_run_configuration"
    override val description =
        "Create and save a named run configuration. type=application needs main_class; type=junit needs " +
            "test_class (and optional method). Supports program_args, vm_options, env, working_dir."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "name" to Schema.string("Name for the configuration (defaults to the class name)."),
        "type" to Schema.string("application | junit (default application).", listOf("application", "junit")),
        "main_class" to Schema.string("Fully-qualified main class (for type=application)."),
        "test_class" to Schema.string("Fully-qualified test class (for type=junit)."),
        "method" to Schema.string("Test method name (optional, for type=junit)."),
        "program_args" to Schema.string("Program arguments."),
        "vm_options" to Schema.string("JVM options, e.g. -Xmx512m -Dk=v."),
        "env" to Schema.stringMap("Environment variables (name→value)."),
        "working_dir" to Schema.string("Working directory."),
        "select" to Schema.bool("Make it the selected configuration (default false).", false),
        "project" to Schema.string("Project path or name (optional if bound)."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val type = ctx.args.str("type") ?: "application"
        val launcher = IdectlProjectService.getInstance(project).launcher

        val settings: RunnerAndConfigurationSettings = when (type) {
            "application" -> {
                val mainClass = ctx.args.str("main_class")
                    ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "type=application requires 'main_class'")
                launcher.createApplication(mainClass, ctx.args.str("program_args"), ctx.args.str("vm_options"))
            }
            "junit" -> {
                val testClass = ctx.args.str("test_class")
                    ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "type=junit requires 'test_class'")
                launcher.createJUnitTest(testClass, ctx.args.str("method"))
            }
            else -> throw ToolException(ErrorCodes.INVALID_ARGUMENT, "unknown type '$type'")
        }

        ctx.args.str("name")?.takeIf { it.isNotBlank() }?.let { settings.name = it }
        val env = ConfigSupport.parseEnv(ctx.args.obj("env"))
        // program_args/vm_options already set for application; apply env/working_dir (+ args for junit).
        ConfigSupport.applyCommon(
            settings.configuration,
            programArgs = if (type == "junit") ctx.args.str("program_args") else null,
            vmOptions = if (type == "junit") ctx.args.str("vm_options") else null,
            env = env.ifEmpty { null },
            workingDir = ctx.args.str("working_dir"),
        )
        val select = ctx.args.bool("select", false)

        withContext(Dispatchers.EDT) {
            val runManager = RunManager.getInstance(project)
            runManager.addConfiguration(settings)
            if (select) runManager.selectedConfiguration = settings
        }

        return ToolCallResult.ok(jObj {
            addProperty("created", true)
            addProperty("name", settings.name)
            addProperty("typeId", settings.type.id)
        })
    }
}
