package com.niandui.idectl.core.console

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-disk overflow archive for one session's console — tier 2 behind [ConsoleRingBuffer]'s memory
 * ring. Lines evicted from the ring are appended here (length-prefixed records across rolling
 * segment files) so `console_read` / `console_search` can backfill history the ring already dropped.
 *
 * Deliberately frugal (the plugin must stay light):
 *  - NO in-memory per-line index. To read from an offset we open a short-lived [RandomAccessFile]
 *    and walk record headers within the containing segment; segments are capped at [segmentBytes]
 *    so the walk is bounded, and reads are rare (agent-driven).
 *  - Exactly ONE open write handle at a time (the current segment); sealed segments are closed and
 *    only re-opened read-only on demand.
 *  - Total bytes bounded by [maxBytes]: when exceeded the OLDEST sealed segment is deleted and
 *    [archiveFirstOffset] advances, so reads below it get an honest Kafka-style gap.
 *
 * All access is synchronized; disk I/O runs off the EDT (output-pump thread on append, tool threads
 * on read). Record layout: [ts:8][stream:1][trunc:1][len:4][utf8 payload].
 */
class ConsoleArchive(
    private val dir: Path,
    private val segmentBytes: Long = 8L * 1024 * 1024,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    /** Read-only metadata for one segment file; the last one in [segments] is the write target. */
    private class Segment(val path: Path, val startOffset: Long) {
        var endOffset: Long = startOffset // exclusive
        var bytes: Long = 0
    }

    private val segments = ArrayDeque<Segment>()
    private var out: DataOutputStream? = null // append stream for the last segment only
    private var nextSegmentId = 0
    private var archiveFirstOffset = 0L
    private var nextArchiveOffset = 0L
    private var dirCreated = false
    private var closed = false

    /** Lowest offset still retrievable from disk. */
    @Synchronized
    fun firstOffset(): Long = archiveFirstOffset

    /** Append a line evicted from the memory ring. Offsets arrive contiguous + increasing. */
    @Synchronized
    fun append(line: ConsoleLine) {
        if (closed) return
        if (segments.isEmpty()) {
            archiveFirstOffset = line.offset
            nextArchiveOffset = line.offset
        }
        val payload = line.text.toByteArray(Charsets.UTF_8)
        val stream = ensureSegment(line.offset)
        stream.writeLong(line.ts)
        stream.writeByte(line.stream.toInt())
        stream.writeByte(if (line.truncated) 1 else 0)
        stream.writeInt(payload.size)
        stream.write(payload)
        val seg = segments.last()
        seg.bytes += RECORD_HEADER + payload.size
        seg.endOffset = line.offset + 1
        nextArchiveOffset = line.offset + 1
        rotateIfNeeded()
    }

    /**
     * Read archived lines starting at [from] (clamped up to [archiveFirstOffset]), up to [maxLines] /
     * [maxBytes] after stream filtering. Returned lines are contiguous-by-scan (filtered lines skipped).
     */
    @Synchronized
    fun read(from: Long, maxLines: Int, maxBytes: Long, streams: Set<Byte>?): List<ConsoleLine> {
        if (closed || segments.isEmpty()) return emptyList()
        runCatching { out?.flush() } // make the current segment's tail visible to the RAF reader
        val result = ArrayList<ConsoleLine>()
        var bytesAcc = 0L
        val start = from.coerceAtLeast(archiveFirstOffset)
        for (seg in segments) {
            if (start >= seg.endOffset) continue
            RandomAccessFile(seg.path.toFile(), "r").use { raf ->
                val segLen = seg.bytes
                var idx = seg.startOffset // offset of the record at the current file pointer
                while (idx < start && raf.filePointer < segLen) { // skip records before `start`
                    raf.seek(raf.filePointer + (RECORD_HEADER - 4)) // past ts+stream+trunc
                    val len = raf.readInt()
                    raf.seek(raf.filePointer + len)
                    idx++
                }
                while (raf.filePointer < segLen) {
                    val ts = raf.readLong()
                    val st = raf.readByte()
                    val truncated = raf.readByte().toInt() == 1
                    val len = raf.readInt()
                    val payload = ByteArray(len)
                    raf.readFully(payload)
                    val offset = idx
                    idx++
                    if (streams != null && st !in streams) continue
                    val text = String(payload, Charsets.UTF_8)
                    val lineBytes = text.length + 1L
                    if (result.isNotEmpty() && (result.size >= maxLines || bytesAcc + lineBytes > maxBytes)) return result
                    result.add(ConsoleLine(offset, ts, st, text, truncated))
                    bytesAcc += lineBytes
                }
            }
        }
        return result
    }

    /** Close the write handle and delete the session's temp directory. Idempotent; call on eviction. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        out?.let { runCatching { it.close() } }
        out = null
        segments.clear()
        if (dirCreated) runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun ensureSegment(startOffsetIfNew: Long): DataOutputStream {
        val last = segments.lastOrNull()
        val cur = out
        if (last != null && cur != null && last.bytes < segmentBytes) return cur
        out?.let { runCatching { it.flush(); it.close() } } // seal the previous segment
        if (!dirCreated) { Files.createDirectories(dir); dirCreated = true }
        val seg = Segment(dir.resolve("seg-${nextSegmentId++}.log"), startOffsetIfNew)
        val stream = DataOutputStream(BufferedOutputStream(Files.newOutputStream(seg.path)))
        segments.addLast(seg)
        out = stream
        return stream
    }

    private fun rotateIfNeeded() {
        var total = 0L
        for (s in segments) total += s.bytes
        while (total > maxBytes && segments.size > 1) { // never drop the current (last) segment
            val oldest = segments.removeFirst()
            runCatching { Files.deleteIfExists(oldest.path) }
            archiveFirstOffset = oldest.endOffset
            total -= oldest.bytes
        }
    }

    companion object {
        private const val RECORD_HEADER = 8 + 1 + 1 + 4 // ts + stream + truncated + len
    }
}
