package com.niandui.idectl.tools.console

import com.google.gson.JsonArray
import com.niandui.idectl.core.console.SearchEngine
import com.niandui.idectl.core.console.SearchHit
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
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.long
import com.niandui.idectl.transport.str
import com.niandui.idectl.transport.strList
import java.util.regex.PatternSyntaxException

/** `console_search` ★核心② — server-side grep with context lines and ReDoS deadline (03 §4.2). */
class ConsoleSearchTool : Tool {
    override val name = "console_search"
    override val description =
        "grep a session's console (grep -E -i -A -B -m semantics): ERE pattern, optional case-insensitive, " +
            "before/after context lines, and offset/time/stream filters. Bounded by a 500ms search deadline. " +
            "Single-line matching only."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "session_id" to Schema.string("Session id (required)."),
        "pattern" to Schema.string("ERE pattern (required, max 512 chars)."),
        "ignore_case" to Schema.bool("Case-insensitive match.", false),
        "before" to Schema.integer("Context lines before each hit (max 20).", 0),
        "after" to Schema.integer("Context lines after each hit (max 20).", 0),
        "max_matches" to Schema.integer("Max hits to return (max 1000).", 100),
        "from_offset" to Schema.integer("Only search lines at/after this offset."),
        "to_offset" to Schema.integer("Only search lines at/before this offset."),
        "since_ms" to Schema.integer("Only search lines at/after this epoch-ms timestamp."),
        "until_ms" to Schema.integer("Only search lines at/before this epoch-ms timestamp."),
        "streams" to Schema.stringArray("Filter: any of stdout|stderr|system."),
        required = listOf("session_id", "pattern"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val sessionId = ctx.args.str("session_id")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'session_id' is required")
        val pattern = ctx.args.str("pattern")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'pattern' is required")
        if (pattern.length > 512) {
            throw ToolException(ErrorCodes.INVALID_ARGUMENT, "pattern too long (${pattern.length} > 512)")
        }

        val (_, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")

        val ignoreCase = ctx.args.bool("ignore_case", false)
        val before = ctx.args.int("before", 0).coerceIn(0, 20)
        val after = ctx.args.int("after", 0).coerceIn(0, 20)
        val maxMatches = ctx.args.int("max_matches", 100).coerceIn(1, 1000)
        val fromOffset = ctx.args.long("from_offset")
        val toOffset = ctx.args.long("to_offset")
        val sinceMs = ctx.args.long("since_ms")
        val untilMs = ctx.args.long("until_ms")
        val streams = ctx.args.strList("streams")?.mapNotNull { Stream.parse(it) }?.toSet()?.takeIf { it.isNotEmpty() }

        // Include on-disk archived history when the search window reaches below the memory buffer.
        val (snapshot, firstOffset, _) = record.console.searchSnapshot(fromOffset)

        val result = try {
            SearchEngine.search(
                snapshot = snapshot,
                firstOffset = firstOffset,
                requestedFrom = fromOffset,
                pattern = pattern,
                ignoreCase = ignoreCase,
                before = before,
                after = after,
                maxMatches = maxMatches,
                fromOffset = fromOffset,
                toOffset = toOffset,
                sinceMs = sinceMs,
                untilMs = untilMs,
                streams = streams,
            )
        } catch (e: PatternSyntaxException) {
            throw ToolException(ErrorCodes.INVALID_ARGUMENT, "invalid regex: ${e.description}")
        }

        return ToolCallResult.ok(jObj {
            add("hits", jArr(result.hits.map { hitJson(it) }))
            addProperty("totalScanned", result.totalScanned)
            addProperty("truncatedByMaxMatches", result.truncatedByMaxMatches)
            addProperty("truncatedByDeadline", result.truncatedByDeadline)
            addProperty("gap", result.gap)
        })
    }

    private fun hitJson(hit: SearchHit) = jObj {
        add("line", Sessions.lineJson(hit.line))
        add("before", jArr(hit.before.map { Sessions.lineJson(it) }))
        add("after", jArr(hit.after.map { Sessions.lineJson(it) }))
        add("matchRanges", JsonArray().apply {
            hit.ranges.forEach { r -> add(JsonArray().apply { add(r[0]); add(r[1]) }) }
        })
    }
}
