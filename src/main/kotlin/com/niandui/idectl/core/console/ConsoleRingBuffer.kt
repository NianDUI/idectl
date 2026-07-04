package com.niandui.idectl.core.console

/** Output stream tag for a captured line. */
object Stream {
    const val STDOUT: Byte = 0
    const val STDERR: Byte = 1
    const val SYSTEM: Byte = 2

    fun name(b: Byte): String = when (b) {
        STDOUT -> "stdout"
        STDERR -> "stderr"
        else -> "system"
    }

    fun parse(s: String): Byte? = when (s.lowercase()) {
        "stdout" -> STDOUT
        "stderr" -> STDERR
        "system" -> SYSTEM
        else -> null
    }
}

class ConsoleLine(
    val offset: Long,
    val ts: Long,
    val stream: Byte,
    val text: String,
    val truncated: Boolean,
)

class ReadResult(
    val lines: List<ConsoleLine>,
    val nextOffset: Long,
    val firstAvailableOffset: Long,
    val gap: Boolean,
    val droppedLines: Long,
    val sessionNextOffset: Long,
)

/**
 * Per-session ring buffer (D6/D7). Line-based, monotonically increasing global offsets that
 * never rewind; overflow drops from the head and advances `firstOffset`. Reads outside the live
 * window return a Kafka-style gap rather than an error, so the Agent always gets a deterministic
 * answer. All mutation/read is under the intrinsic lock; the read path touches no platform lock.
 *
 * When an [archive] is supplied, lines evicted from the memory ring are spilled to disk first, so
 * reads/searches can transparently backfill history that no longer fits in memory. The effective
 * "first available offset" then becomes the archive's first offset, not the ring's.
 */
class ConsoleRingBuffer(
    private val maxLines: Int = 10_000,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxLineLen: Int = 16 * 1024,
    private val archive: ConsoleArchive? = null,
) {
    private val lines = ArrayDeque<ConsoleLine>()
    private var firstOffset = 0L
    private var nextOffset = 0L
    private var byteSize = 0L

    /** Hard cap on how many archived lines a single search snapshot pulls into memory (heap guard). */
    private val maxSearchArchiveLines = 1_000_000L

    @Synchronized
    fun append(ts: Long, stream: Byte, rawText: String) {
        val truncated = rawText.length > maxLineLen
        val text = if (truncated) rawText.substring(0, maxLineLen) else rawText
        val line = ConsoleLine(nextOffset, ts, stream, text, truncated)
        lines.addLast(line)
        nextOffset++
        byteSize += text.length + 1
        evict()
    }

    private fun evict() {
        while (lines.size > maxLines || (byteSize > maxBytes && lines.size > 1)) {
            val removed = lines.removeFirst()
            byteSize -= removed.text.length + 1
            firstOffset++
            archive?.append(removed) // spill to disk before dropping from memory
        }
    }

    @Synchronized
    fun read(from: Long?, maxLines: Int, maxBytes: Int, streams: Set<Byte>?): ReadResult {
        val memFirst = firstOffset
        val no = nextOffset
        val earliest = archive?.firstOffset() ?: memFirst
        val requested = from ?: earliest
        var gap = false
        var dropped = 0L
        var cursor = requested
        if (requested < earliest) {
            gap = true
            dropped = earliest - requested
            cursor = earliest
        }

        val out = ArrayList<ConsoleLine>()
        var bytesAcc = 0
        // Tier 2: backfill the archived range [cursor, memFirst) from disk.
        if (archive != null && cursor < memFirst) {
            for (line in archive.read(cursor, maxLines, maxBytes.toLong(), streams)) {
                val lineBytes = line.text.length + 1
                if (out.isNotEmpty() && (out.size >= maxLines || bytesAcc + lineBytes > maxBytes)) break
                out.add(line)
                bytesAcc += lineBytes
            }
            cursor = if (out.isNotEmpty()) out.last().offset + 1 else memFirst
        }
        // Tier 1: continue from the in-memory ring [max(cursor,memFirst), no). Only reached once the
        // archived range is exhausted (otherwise the budget check below breaks on the first line).
        if (cursor >= memFirst && cursor < no) {
            var idx = (cursor - memFirst).toInt()
            while (idx in 0 until lines.size) {
                val line = lines[idx]
                idx++
                if (streams != null && line.stream !in streams) continue
                val lineBytes = line.text.length + 1
                if (out.isNotEmpty() && (out.size >= maxLines || bytesAcc + lineBytes > maxBytes)) break
                out.add(line)
                bytesAcc += lineBytes
            }
        }

        val nextOut = if (out.isNotEmpty()) out.last().offset + 1 else no
        return ReadResult(out, nextOut, earliest, gap, dropped, no)
    }

    /**
     * Consistent snapshot for search: (lines copy, firstOffset, nextOffset). When an archive exists
     * and [includeArchiveFrom] reaches below the memory window (or is null = search everything), the
     * archived range is prepended so grep covers evicted history too — capped at the most recent
     * [maxSearchArchiveLines] archived lines to bound heap.
     */
    @Synchronized
    fun snapshot(includeArchiveFrom: Long? = null): Triple<List<ConsoleLine>, Long, Long> {
        val memFirst = firstOffset
        val no = nextOffset
        val arch = archive
        if (arch == null) return Triple(ArrayList(lines), memFirst, no)
        val archFirst = arch.firstOffset()
        val wantFrom = when {
            includeArchiveFrom == null -> archFirst // search all → include full archive
            includeArchiveFrom < memFirst -> includeArchiveFrom.coerceAtLeast(archFirst)
            else -> return Triple(ArrayList(lines), memFirst, no) // caller only wants the memory range
        }
        if (wantFrom >= memFirst) return Triple(ArrayList(lines), memFirst, no)
        val from = maxOf(wantFrom, memFirst - maxSearchArchiveLines)
        val archived = arch.read(from, Int.MAX_VALUE, Long.MAX_VALUE, null)
        val combined = ArrayList<ConsoleLine>(archived.size + lines.size)
        combined.addAll(archived)
        combined.addAll(lines)
        val first = combined.firstOrNull()?.offset ?: memFirst
        return Triple(combined, first, no)
    }

    @Synchronized
    fun firstAvailableOffset(): Long = archive?.firstOffset() ?: firstOffset

    @Synchronized
    fun currentNextOffset(): Long = nextOffset
}
