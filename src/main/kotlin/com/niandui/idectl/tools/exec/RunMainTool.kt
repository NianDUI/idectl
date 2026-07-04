package com.niandui.idectl.tools.exec

import com.google.gson.JsonNull
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/**
 * `run_main` — run any class with a `main()` method as a Java Application, creating the run
 * configuration on the fly (no saved configuration needed). Supports program args, VM options,
 * and run/debug. Like run_test, but for plain main classes.
 */
class RunMainTool : Tool {
    override val name = "run_main"
    override val description =
        "Run a class's main() method as a Java Application, creating the run configuration on the fly — " +
            "no saved configuration needed. Supports program_args, vm_options, and mode=debug. " +
            "Use wait=ready with a ready_pattern for long-running apps."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "class" to Schema.string("Fully-qualified class name with a main() method (required)."),
        "program_args" to Schema.string("Program arguments (the String[] args)."),
        "vm_options" to Schema.string("JVM options, e.g. -Xmx512m -Dkey=value."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        "mode" to Schema.string("run | debug", listOf("run", "debug")),
        "wait" to Schema.string("none | exit | ready", listOf("none", "exit", "ready")),
        "ready_pattern" to Schema.string("ERE matched against console output to detect readiness."),
        "timeout_sec" to Schema.integer("Max seconds to wait (default 120).", 120),
        "tail_lines" to Schema.integer("Trailing console lines to return (default 30).", 30),
        required = listOf("class"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val className = ctx.args.str("class")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'class' is required")
        val programArgs = ctx.args.str("program_args")
        val vmOptions = ctx.args.str("vm_options")
        val debug = ctx.args.str("mode") == "debug"
        val wait = ctx.args.str("wait") ?: "ready"
        val readyPattern = ctx.args.str("ready_pattern")
        val timeoutSec = ctx.args.int("timeout_sec", 120).coerceIn(1, 3600)
        val tailLines = ctx.args.int("tail_lines", 30).coerceIn(0, 2000)

        val launcher = IdectlProjectService.getInstance(project).launcher
        val settings = launcher.createApplication(className, programArgs, vmOptions)
        val record = launcher.launch(
            settings = settings,
            debug = debug,
            owner = ctx.principal.subject,
            restartOf = null,
            timeoutMs = minOf(timeoutSec * 1000L, 20_000L),
            allowConflict = true,
        ) ?: return ToolCallResult.ok(jObj {
            add("sessionId", JsonNull.INSTANCE)
            addProperty("state", "not_started")
        })

        return ToolCallResult.ok(
            RunConfigurationTool.buildResult(record, wait, readyPattern, timeoutSec, tailLines, restartOf = null),
        )
    }
}
