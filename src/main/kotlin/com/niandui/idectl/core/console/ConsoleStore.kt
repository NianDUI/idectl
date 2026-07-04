package com.niandui.idectl.core.console

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Tuning for one session's console buffers. [archiveDir] null → memory-only (no disk spill).
 * Built from [com.niandui.idectl.settings.BridgeSettings] per launch, so config changes apply to
 * newly started sessions without touching already-running ones.
 */
class ConsoleConfig(
    val memoryLines: Int = 10_000,
    val memoryBytes: Long = 8L * 1024 * 1024,
    val archiveDir: Path? = null,
    val archiveMaxBytes: Long = 64L * 1024 * 1024,
)

/**
 * All console state for one execution session (core②): the ring buffer, an optional on-disk overflow
 * archive, per-stream line assembly, ANSI stripping, and a lost-wakeup-free change signal for
 * long-polling readers.
 *
 * The output pump (ProcessListener.onTextAvailable) calls the append* methods on the fast path;
 * they must stay O(1) (archive spill is buffered disk I/O, still cheap). Readers use
 * [versionNow]+[awaitChange] to block until new data or exit. [dispose] must run on session
 * eviction to delete the archive's temp files.
 */
class ConsoleStore(config: ConsoleConfig = ConsoleConfig()) {
    private val archive: ConsoleArchive? =
        config.archiveDir?.let { ConsoleArchive(it, maxBytes = config.archiveMaxBytes) }
    val buffer = ConsoleRingBuffer(
        maxLines = config.memoryLines,
        maxBytes = config.memoryBytes,
        archive = archive,
    )

    // Monotonic version bumped on every append and on termination; StateFlow replays the latest
    // value to new collectors, so a change between "read" and "await" is never missed.
    private val counter = AtomicLong(0)
    private val version = MutableStateFlow(0L)

    @Volatile var terminated: Boolean = false
        private set
    @Volatile var exitCode: Int? = null
        private set

    private val ansi = AnsiEscapeDecoder()
    private val stdout = LineAssembler(Stream.STDOUT) { ts, s, t -> commit(ts, s, t) }
    private val stderr = LineAssembler(Stream.STDERR) { ts, s, t -> commit(ts, s, t) }
    private val system = LineAssembler(Stream.SYSTEM) { ts, s, t -> commit(ts, s, t) }

    /** Feed a raw process chunk; ANSI is decoded and split into complete lines per stream. */
    fun onText(rawText: String, outputType: Key<*>) {
        val ts = System.currentTimeMillis()
        ansi.escapeText(rawText, outputType) { decoded, type ->
            assemblerFor(streamOf(type)).feed(ts, decoded)
        }
        bump()
    }

    /** Import already-clean text (attachedLate rescue, system notes) with an explicit stream. */
    fun onCleanText(text: String, stream: Byte) {
        assemblerFor(stream).feed(System.currentTimeMillis(), text)
        bump()
    }

    fun onTerminated(code: Int?) {
        val ts = System.currentTimeMillis()
        stdout.flush(ts); stderr.flush(ts); system.flush(ts)
        exitCode = code
        terminated = true
        bump()
    }

    private fun commit(ts: Long, stream: Byte, text: String) = buffer.append(ts, stream, text)

    private fun bump() {
        version.value = counter.incrementAndGet()
    }

    fun read(from: Long?, maxLines: Int, maxBytes: Int, streams: Set<Byte>?): ReadResult =
        buffer.read(from, maxLines, maxBytes, streams)

    /** Snapshot for search: includes archived history when [fromOffset] reaches below the memory window. */
    fun searchSnapshot(fromOffset: Long?): Triple<List<ConsoleLine>, Long, Long> =
        buffer.snapshot(includeArchiveFrom = fromOffset)

    /** Release disk resources (delete the archive's temp files). Idempotent; call on session eviction. */
    fun dispose() {
        archive?.close()
    }

    fun versionNow(): Long = version.value

    /** Suspend until the version differs from [since] (new data / termination) or the timeout elapses. */
    suspend fun awaitChange(since: Long, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { version.first { it != since } }
    }

    private fun assemblerFor(stream: Byte): LineAssembler = when (stream) {
        Stream.STDERR -> stderr
        Stream.SYSTEM -> system
        else -> stdout
    }

    private fun streamOf(key: Key<*>): Byte = when {
        ProcessOutputType.isStderr(key) -> Stream.STDERR
        ProcessOutputType.isStdout(key) -> Stream.STDOUT
        else -> Stream.SYSTEM
    }
}

/** Assembles complete lines from arbitrary chunks; strips trailing '\r'; force-flushes very long partials. */
private class LineAssembler(
    private val stream: Byte,
    private val emit: (ts: Long, stream: Byte, text: String) -> Unit,
) {
    private val pending = StringBuilder()
    private val forceFlushAt = 16 * 1024

    fun feed(ts: Long, text: String) {
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                pending.append(text, start, i)
                emitPending(ts)
                start = i + 1
            }
        }
        if (start < text.length) pending.append(text, start, text.length)
        if (pending.length >= forceFlushAt) emitPending(ts)
    }

    fun flush(ts: Long) {
        if (pending.isNotEmpty()) emitPending(ts)
    }

    private fun emitPending(ts: Long) {
        val line = pending.toString().removeSuffix("\r")
        pending.setLength(0)
        emit(ts, stream, line)
    }
}
