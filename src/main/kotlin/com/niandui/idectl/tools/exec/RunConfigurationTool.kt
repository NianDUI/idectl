package com.niandui.idectl.tools.exec

import com.google.gson.JsonNull
import com.niandui.idectl.core.exec.ExecutionRecord
import com.niandui.idectl.project.BridgeProjectService
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `run_configuration` — start a configuration under run/debug, then wait+tail in one call (03 §3.2). */
class RunConfigurationTool : Tool {
    override val name = "run_configuration"
    override val description =
        "Start a run configuration under Run or Debug and (optionally) wait until it is ready or exits, " +
            "returning the tail of its console. Line numbers/offsets are session-global."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "name" to Schema.string("Configuration name (required)."),
        "type_id" to Schema.string("Configuration typeId (required only if the name is ambiguous)."),
        "project" to Schema.string("Project path or name (optional if bound)."),
        "mode" to Schema.string("run | debug", listOf("run", "debug")),
        "wait" to Schema.string("none | exit | ready", listOf("none", "exit", "ready")),
        "ready_pattern" to Schema.string("ERE matched against console output to detect readiness."),
        "timeout_sec" to Schema.integer("Max seconds to wait (default 120).", 120),
        "tail_lines" to Schema.integer("How many trailing console lines to return (default 100).", 100),
        required = listOf("name"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val name = ctx.args.str("name")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'name' is required")
        val typeId = ctx.args.str("type_id")
        val debug = ctx.args.str("mode") == "debug"
        val wait = ctx.args.str("wait") ?: "ready"
        val readyPattern = ctx.args.str("ready_pattern")
        val timeoutSec = ctx.args.int("timeout_sec", 120).coerceIn(1, 3600)
        val tailLines = ctx.args.int("tail_lines", 100).coerceIn(0, 2000)

        val launcher = BridgeProjectService.getInstance(project).launcher
        val record = launcher.start(
            name = name,
            typeId = typeId,
            debug = debug,
            owner = ctx.principal.subject,
            restartOf = null,
            timeoutMs = minOf(timeoutSec * 1000L, 20_000L),
        ) ?: return ToolCallResult.ok(jObj {
            add("sessionId", JsonNull.INSTANCE)
            addProperty("state", "not_started")
            add("startupError", jObj {
                addProperty("message", "process did not start (before-launch build failed or runner refused)")
            })
        })

        return ToolCallResult.ok(buildResult(record, wait, readyPattern, timeoutSec, tailLines, restartOf = null))
    }

    companion object {
        /** Shared response builder for run_configuration and restart_session. */
        suspend fun buildResult(
            record: ExecutionRecord,
            wait: String,
            readyPattern: String?,
            timeoutSec: Int,
            tailLines: Int,
            restartOf: String?,
        ) = jObj {
            val outcome = WaitHelper.awaitAndTail(record, wait, readyPattern, timeoutSec * 1000L, tailLines)
            addProperty("sessionId", record.sessionId)
            if (restartOf != null) addProperty("restartOf", restartOf)
            addProperty("state", outcome.state)
            record.exitCode?.let { addProperty("exitCode", it) }
            // M4: a debug session shares its run session id — use it with set_breakpoint / debug_control.
            addProperty("debugSessionId", if (record.isDebug) record.sessionId else "")
            add("tail", jArr(outcome.tail.map { Sessions.lineJson(it) }))
            addProperty("nextOffset", outcome.nextOffset)
            outcome.matchedOffset?.let { addProperty("readyMatchedOffset", it) }
        }
    }
}
