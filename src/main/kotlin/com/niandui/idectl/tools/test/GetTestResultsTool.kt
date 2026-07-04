package com.niandui.idectl.tools.test

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.EDT
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.tools.exec.Sessions
import com.niandui.idectl.transport.bool
import com.niandui.idectl.transport.int
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `get_test_results` — structured JUnit results for a test session via the SM runner tree (03 §3.6). */
class GetTestResultsTool : Tool {
    override val name = "get_test_results"
    override val description =
        "Structured test results for a session that ran tests (JUnit etc.): pass/fail counts plus the " +
            "failing tests with their message and stacktrace. Uses the IDE's test event tree, not text parsing."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Session id that ran tests (required)."),
        "failed_only" to Schema.bool("Only return failing tests (default true).", true),
        "max_nodes" to Schema.integer("Max test entries to return (default 200).", 200),
        "stacktrace_max_chars" to Schema.integer("Truncate each stacktrace to this length (default 4000).", 4000),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val failedOnly = ctx.args.bool("failed_only", true)
        val maxNodes = ctx.args.int("max_nodes", 200).coerceIn(1, 2000)
        val stackMax = ctx.args.int("stacktrace_max_chars", 4000).coerceIn(0, 20000)

        val (_, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")
        val root = record.testRoot
            ?: throw ToolException(
                ErrorCodes.NOT_FOUND,
                "session $sessionId has no test results",
                "run a test configuration (e.g. JUnit); results appear once tests start",
            )

        // SM tree is mutated on the EDT while tests run — read it there to avoid concurrent modification.
        return withContext(Dispatchers.EDT) {
            val leaves = ArrayList<SMTestProxy>()
            collectLeaves(root, leaves)
            val passed = leaves.count { it.isPassed }
            val ignored = leaves.count { it.isIgnored }
            val failed = leaves.count { it.isDefect && !it.isIgnored }

            val nodes = leaves.filter { if (failedOnly) it.isDefect && !it.isIgnored else true }
            val shown = nodes.take(maxNodes).map { test ->
                jObj {
                    addProperty("name", test.name)
                    addProperty("locationUrl", test.locationUrl ?: "")
                    addProperty("status", statusOf(test))
                    test.errorMessage?.let { addProperty("message", it) }
                    val stack = test.stacktrace
                    if (!stack.isNullOrEmpty()) {
                        addProperty("stacktrace", if (stack.length > stackMax) stack.substring(0, stackMax) + "…" else stack)
                    }
                }
            }

            ToolCallResult.ok(jObj {
                add("summary", jObj {
                    addProperty("total", leaves.size)
                    addProperty("passed", passed)
                    addProperty("failed", failed)
                    addProperty("ignored", ignored)
                    addProperty("durationMs", root.duration ?: 0L)
                })
                add("tests", jArr(shown))
                addProperty("hasMore", nodes.size > shown.size)
            })
        }
    }

    private fun collectLeaves(node: SMTestProxy, out: MutableList<SMTestProxy>) {
        val children = node.children
        if (children.isEmpty()) {
            if (!node.isSuite) out.add(node) // a test leaf, not an empty suite
            return
        }
        for (child in children) collectLeaves(child, out)
    }

    private fun statusOf(test: SMTestProxy): String = when {
        test.isIgnored -> "ignored"
        test.isDefect -> "failed"
        test.isPassed -> "passed"
        else -> "unknown"
    }
}
