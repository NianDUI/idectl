package com.niandui.idectl.tools.test

import com.google.gson.JsonNull
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.tools.exec.RunConfigurationTool
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/**
 * `run_test` — run a JUnit test by class (optionally a single method) WITHOUT a pre-existing run
 * configuration. Creates the config on the fly and launches it. Follow with get_test_results on the
 * returned sessionId for structured pass/fail + stacktraces.
 */
class RunTestTool : Tool {
    override val name = "run_test"
    override val description =
        "Run a JUnit test by fully-qualified class name (and optional method), creating the run " +
            "configuration on the fly — no need for a saved configuration. Use mode=debug to debug it. " +
            "Then call get_test_results with the returned sessionId."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "class" to Schema.string("Fully-qualified test class name, e.g. com.foo.BarTest (required)."),
        "method" to Schema.string("Single test method name (optional; omit to run the whole class)."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        "mode" to Schema.string("run | debug", listOf("run", "debug")),
        "wait" to Schema.string("none | exit | ready (default exit — wait for tests to finish)", listOf("none", "exit", "ready")),
        "timeout_sec" to Schema.integer("Max seconds to wait (default 120).", 120),
        "tail_lines" to Schema.integer("Trailing console lines to return (default 30).", 30),
        required = listOf("class"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val className = ctx.args.str("class")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'class' is required")
        val method = ctx.args.str("method")
        val debug = ctx.args.str("mode") == "debug"
        val wait = ctx.args.str("wait") ?: "exit"
        val timeoutSec = ctx.args.int("timeout_sec", 120).coerceIn(1, 3600)
        val tailLines = ctx.args.int("tail_lines", 30).coerceIn(0, 2000)

        val launcher = IdectlProjectService.getInstance(project).launcher
        val settings = launcher.createJUnitTest(className, method)
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
            RunConfigurationTool.buildResult(record, wait, readyPattern = null, timeoutSec, tailLines, restartOf = null),
        )
    }
}
