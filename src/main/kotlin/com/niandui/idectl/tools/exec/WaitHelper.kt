package com.niandui.idectl.tools.exec

import com.niandui.idectl.core.console.ConsoleLine
import com.niandui.idectl.core.console.ConsoleStore
import com.niandui.idectl.core.exec.ExecutionRecord
import java.util.regex.Pattern

/**
 * Shared wait+tail semantics for run_configuration / restart_session (03 §3.2).
 * `none` = return immediately; `exit` = block until termination; `ready` = block until the
 * ready_pattern appears (or first output if no pattern). Timeouts return current progress, not errors.
 */
object WaitHelper {

    class Outcome(
        val state: String, // running | exited | timeout
        val tail: List<ConsoleLine>,
        val nextOffset: Long,
        val matchedOffset: Long?,
    )

    private const val BIG_BYTES = 8 * 1024 * 1024

    suspend fun awaitAndTail(
        record: ExecutionRecord,
        wait: String,
        readyPattern: String?,
        timeoutMs: Long,
        tailLines: Int,
    ): Outcome {
        val store = record.console
        val pattern = readyPattern?.let { Pattern.compile(it) }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var matched: Long? = null

        when (wait) {
            "exit" -> waitUntil(store, deadline) { store.terminated }
            "ready" -> {
                if (pattern == null) {
                    waitUntil(store, deadline) {
                        store.buffer.currentNextOffset() > store.buffer.firstAvailableOffset() || store.terminated
                    }
                } else {
                    matched = scanForPattern(store, pattern, deadline)
                }
            }
            else -> { /* none */ }
        }

        val tail = tail(store, tailLines)
        val state = when {
            store.terminated -> "exited"
            wait == "none" -> "running"
            wait == "ready" && (pattern == null || matched != null) -> "running"
            else -> "timeout"
        }
        return Outcome(state, tail, store.buffer.currentNextOffset(), matched)
    }

    private suspend fun waitUntil(store: ConsoleStore, deadlineNanos: Long, cond: () -> Boolean) {
        while (!cond()) {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            val v = store.versionNow()
            if (cond()) break
            store.awaitChange(v, remainingMs)
        }
    }

    private suspend fun scanForPattern(store: ConsoleStore, pattern: Pattern, deadlineNanos: Long): Long? {
        var cursor = store.buffer.firstAvailableOffset()
        while (true) {
            val res = store.read(cursor, 10_000, BIG_BYTES, null)
            for (line in res.lines) {
                if (pattern.matcher(line.text).find()) return line.offset
            }
            cursor = res.nextOffset
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return null
            val v = store.versionNow()
            if (store.buffer.currentNextOffset() > cursor) continue // new data already; re-read
            if (store.terminated) return null
            store.awaitChange(v, remainingMs)
        }
    }

    private fun tail(store: ConsoleStore, tailLines: Int): List<ConsoleLine> {
        if (tailLines <= 0) return emptyList()
        val next = store.buffer.currentNextOffset()
        val first = store.buffer.firstAvailableOffset()
        val from = (next - tailLines).coerceAtLeast(first)
        return store.read(from, tailLines, BIG_BYTES, null).lines
    }
}
