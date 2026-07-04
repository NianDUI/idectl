package com.niandui.idectl.tools.console

import com.google.gson.JsonArray
import com.niandui.idectl.core.console.ConsoleLine
import com.niandui.idectl.core.console.Stream
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
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.long
import com.niandui.idectl.transport.str
import com.niandui.idectl.transport.strList
import java.util.regex.Pattern

/** `console_read` — incremental read + optional long-poll until a pattern/exit appears (core②, 03 §4.1). */
class ConsoleReadTool : Tool {
    override val name = "console_read"
    override val description =
        "Read a session's console incrementally from an offset. Optionally block (wait_for_pattern / " +
            "wait_for_exit up to wait_timeout_sec) until the pattern appears or the process exits. " +
            "Offsets are session-global and never rewind; a gap flag signals dropped lines."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Session id (required)."),
        "from_offset" to Schema.integer("Start offset; defaults to the first available line."),
        "max_lines" to Schema.integer("Max lines to return (default 500).", 500),
        "max_bytes" to Schema.integer("Max bytes to return (default 65536).", 65536),
        "streams" to Schema.stringArray("Filter: any of stdout|stderr|system."),
        "wait_for_pattern" to Schema.string("ERE; block until a matching line appears."),
        "wait_for_exit" to Schema.bool("Block until the process exits.", false),
        "wait_timeout_sec" to Schema.integer("Max seconds to block (0 = no wait, max 60).", 0),
        required = listOf("session_id"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val (_, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")

        val fromOffset = ctx.args.long("from_offset")
        val maxLines = ctx.args.int("max_lines", 500).coerceIn(1, 5000)
        val maxBytes = ctx.args.int("max_bytes", 65536).coerceIn(1024, 4 * 1024 * 1024)
        val streams = ctx.args.strList("streams")?.mapNotNull { Stream.parse(it) }?.toSet()?.takeIf { it.isNotEmpty() }
        val waitPattern = ctx.args.str("wait_for_pattern")?.let { Pattern.compile(it) }
        val waitForExit = ctx.args.bool("wait_for_exit", false)
        val waitTimeoutMs = ctx.args.int("wait_timeout_sec", 0).coerceIn(0, 60) * 1000L

        val store = record.console
        val first = store.read(fromOffset, maxLines, maxBytes, streams)
        val lines = ArrayList(first.lines)
        var nextOffset = first.nextOffset
        var matched = scanMatch(first.lines, waitPattern)

        val waitActive = waitTimeoutMs > 0 && (waitPattern != null || waitForExit)
        if (waitActive && matched == null && !(waitForExit && store.terminated)) {
            val deadline = System.nanoTime() + waitTimeoutMs * 1_000_000
            while (lines.size < maxLines) {
                if (System.nanoTime() >= deadline) break
                val version = store.versionNow()
                val more = store.read(nextOffset, maxLines - lines.size, maxBytes, streams)
                if (more.lines.isNotEmpty()) {
                    lines.addAll(more.lines)
                    nextOffset = more.nextOffset
                    matched = matched ?: scanMatch(more.lines, waitPattern)
                    if (matched != null) break
                    continue
                }
                if (waitForExit && store.terminated) break
                if (waitPattern == null && store.terminated) break
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                store.awaitChange(version, remaining)
            }
        }

        return ToolCallResult.ok(jObj {
            add("lines", JsonArray().apply { lines.forEach { add(Sessions.lineJson(it)) } })
            addProperty("nextOffset", nextOffset)
            addProperty("firstAvailableOffset", first.firstAvailableOffset)
            addProperty("gap", first.gap)
            if (first.droppedLines > 0) addProperty("droppedLines", first.droppedLines)
            addProperty("sessionState", Sessions.stateName(record))
            record.exitCode?.let { addProperty("exitCode", it) }
            matched?.let { m ->
                add("matched", jObj {
                    addProperty("offset", m.first)
                    add("ranges", rangesJson(m.second))
                })
            }
        })
    }

    private fun scanMatch(lines: List<ConsoleLine>, pattern: Pattern?): Pair<Long, List<IntArray>>? {
        if (pattern == null) return null
        for (line in lines) {
            val matcher = pattern.matcher(line.text)
            val ranges = ArrayList<IntArray>()
            while (matcher.find()) {
                ranges.add(intArrayOf(matcher.start(), matcher.end()))
                if (matcher.end() == matcher.start()) break
            }
            if (ranges.isNotEmpty()) return line.offset to ranges
        }
        return null
    }

    private fun rangesJson(ranges: List<IntArray>): JsonArray = JsonArray().apply {
        ranges.forEach { r -> add(JsonArray().apply { add(r[0]); add(r[1]) }) }
    }
}
