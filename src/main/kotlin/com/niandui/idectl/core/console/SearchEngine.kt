package com.niandui.idectl.core.console

import java.util.regex.Pattern

class SearchHit(
    val line: ConsoleLine,
    val before: List<ConsoleLine>,
    val after: List<ConsoleLine>,
    val ranges: List<IntArray>,
)

class SearchResult(
    val hits: List<SearchHit>,
    val totalScanned: Int,
    val truncatedByMaxMatches: Boolean,
    val truncatedByDeadline: Boolean,
    val gap: Boolean,
)

/** Server-side grep (core②): `grep -E -i -A -B -m` semantics over a buffer snapshot (07 §4). */
object SearchEngine {

    private const val MAX_RANGES_PER_LINE = 100

    @Suppress("LongParameterList")
    fun search(
        snapshot: List<ConsoleLine>,
        firstOffset: Long,
        requestedFrom: Long?,
        pattern: String,
        ignoreCase: Boolean,
        before: Int,
        after: Int,
        maxMatches: Int,
        fromOffset: Long?,
        toOffset: Long?,
        sinceMs: Long?,
        untilMs: Long?,
        streams: Set<Byte>?,
        deadlineMs: Long = 500,
    ): SearchResult {
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        val regex = Pattern.compile(pattern, flags)

        // The filtered view we grep over (offset window, time window, stream). Context lines are
        // drawn from this same view by index, grep-style.
        val view = snapshot.filter { line ->
            (fromOffset == null || line.offset >= fromOffset) &&
                (toOffset == null || line.offset <= toOffset) &&
                (sinceMs == null || line.ts >= sinceMs) &&
                (untilMs == null || line.ts <= untilMs) &&
                (streams == null || line.stream in streams)
        }

        val deadlineNanos = System.nanoTime() + deadlineMs * 1_000_000
        val hits = ArrayList<SearchHit>()
        var scanned = 0
        var truncatedByMax = false
        var truncatedByDeadline = false

        try {
            for (i in view.indices) {
                scanned++
                val line = view[i]
                val ranges = matchRanges(regex, line.text, deadlineNanos)
                if (ranges.isEmpty()) continue
                val beforeCtx = if (before > 0) view.subList((i - before).coerceAtLeast(0), i).toList() else emptyList()
                val afterCtx = if (after > 0) view.subList(i + 1, (i + 1 + after).coerceAtMost(view.size)).toList() else emptyList()
                hits.add(SearchHit(line, beforeCtx, afterCtx, ranges))
                if (hits.size >= maxMatches) {
                    truncatedByMax = true
                    break
                }
            }
        } catch (_: SearchTimeoutException) {
            truncatedByDeadline = true
        }

        val gap = requestedFrom != null && requestedFrom < firstOffset
        return SearchResult(hits, scanned, truncatedByMax, truncatedByDeadline, gap)
    }

    private fun matchRanges(regex: Pattern, text: String, deadlineNanos: Long): List<IntArray> {
        val matcher = regex.matcher(DeadlineCharSequence(text, deadlineNanos))
        val ranges = ArrayList<IntArray>()
        var from = 0
        while (from <= text.length && matcher.find(from)) {
            val s = matcher.start()
            val e = matcher.end()
            ranges.add(intArrayOf(s, e))
            if (ranges.size >= MAX_RANGES_PER_LINE) break
            from = if (e > s) e else e + 1 // advance past zero-width matches
        }
        return ranges
    }
}
