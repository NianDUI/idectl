package com.niandui.idectl.core.exec

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.niandui.idectl.core.console.ConsoleConfig
import com.niandui.idectl.core.console.ConsoleStore
import com.niandui.idectl.settings.IdectlSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project registry of execution sessions (core① geodata). Fed by the declarative
 * [IdectlExecutionListener] so it captures user-launched sessions too. The console listener is
 * attached in `processStarting` — before `startNotify()` — for zero-loss output (D6/R03).
 */
class ExecutionRegistry(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private data class PendingLaunch(val owner: String, val restartOf: String?)

    private val records = ConcurrentHashMap<String, ExecutionRecord>()
    private val byHandler = ConcurrentHashMap<ProcessHandler, ExecutionRecord>()
    private val pending = ConcurrentHashMap<Long, PendingLaunch>()
    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<ExecutionRecord?>>()

    private val ttlMs = 30 * 60 * 1000L

    fun newSessionId(): String = UUID.randomUUID().toString()

    /** Called by the launcher right before executing, so the created record can be correlated + awaited. */
    fun registerPending(executionId: Long, owner: String, restartOf: String?) {
        pending[executionId] = PendingLaunch(owner, restartOf)
        waiters[executionId] = CompletableDeferred()
    }

    /** processStarting(executorId, env, handler) — the zero-loss attach point. */
    fun onProcessStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (byHandler.containsKey(handler)) return // already attached (defensive)
        val execId = env.executionId
        val p = pending.remove(execId)
        val settings = env.runnerAndConfigurationSettings
        val sessionId = newSessionId()
        val record = ExecutionRecord(
            sessionId = sessionId,
            project = project,
            configName = settings?.name ?: env.runProfile.name,
            typeId = settings?.type?.id,
            executorId = executorId,
            settings = settings,
            environment = env,
            processHandler = handler,
            owner = p?.owner ?: "user",
            startedBy = if (p != null) "agent" else "user",
            startedAt = System.currentTimeMillis(),
            restartOf = p?.restartOf,
            console = ConsoleStore(consoleConfig(sessionId)),
        )
        attach(record)
        records[record.sessionId] = record
        byHandler[handler] = record
        waiters.remove(execId)?.complete(record)
    }

    /** processStarted — mark running; fallback-attach if we somehow missed processStarting. */
    fun onProcessStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val existing = byHandler[handler]
        if (existing == null) {
            onProcessStarting(executorId, env, handler)
        }
        byHandler[handler]?.let { if (it.state == ExecState.STARTING) it.state = ExecState.RUNNING }
    }

    /** processNotStarted — unblock the launcher waiter so run_configuration never hangs (R02). */
    fun onProcessNotStarted(env: ExecutionEnvironment) {
        val execId = env.executionId
        pending.remove(execId)
        waiters.remove(execId)?.complete(null)
    }

    fun onProcessTerminated(handler: ProcessHandler, exitCode: Int) {
        val record = byHandler[handler] ?: return
        record.state = ExecState.TERMINATED
        record.exitCode = exitCode
        record.endedAt = System.currentTimeMillis()
        record.console.onTerminated(exitCode)
        scheduleEviction(record)
    }

    private fun attach(record: ExecutionRecord) {
        record.processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                try {
                    record.console.onText(event.text, outputType)
                } catch (t: Throwable) {
                    thisLogger().warn("console append failed", t)
                }
            }

            override fun startNotified(event: ProcessEvent) {
                if (record.state == ExecState.STARTING) record.state = ExecState.RUNNING
            }

            override fun processTerminated(event: ProcessEvent) {
                record.state = ExecState.TERMINATED
                record.exitCode = event.exitCode
                record.endedAt = System.currentTimeMillis()
                record.console.onTerminated(event.exitCode)
                scheduleEviction(record)
            }
        })
    }

    /** Build per-session console tuning from current settings; archive dir lives under the IDE temp path. */
    private fun consoleConfig(sessionId: String): ConsoleConfig {
        val s = IdectlSettings.getInstance()
        val archiveDir = if (s.consoleArchiveEnabled) {
            Paths.get(PathManager.getTempPath(), "idectl-console", sessionId)
        } else {
            null
        }
        return ConsoleConfig(
            memoryLines = s.consoleMemoryLines.coerceAtLeast(100),
            memoryBytes = s.consoleMemoryMb.coerceAtLeast(1).toLong() * 1024 * 1024,
            archiveDir = archiveDir,
            archiveMaxBytes = s.consoleArchiveMb.coerceAtLeast(1).toLong() * 1024 * 1024,
        )
    }

    private fun scheduleEviction(record: ExecutionRecord) {
        scope.launch {
            delay(ttlMs)
            records.remove(record.sessionId)
            byHandler.remove(record.processHandler)
            runCatching { record.console.dispose() } // delete the session's on-disk archive
        }
    }

    /** Close every session's console (delete temp archives). Called when the project is disposed. */
    fun disposeAll() {
        records.values.forEach { runCatching { it.console.dispose() } }
        records.clear()
        byHandler.clear()
    }

    /** Await the record created for a launch we initiated (by executionId), or null on timeout/failure. */
    suspend fun awaitRecord(executionId: Long, timeoutMs: Long): ExecutionRecord? {
        val waiter = waiters[executionId] ?: return null
        return withTimeoutOrNull(timeoutMs) { waiter.await() }
    }

    fun find(sessionId: String): ExecutionRecord? = records[sessionId]

    fun findByHandler(handler: ProcessHandler): ExecutionRecord? = byHandler[handler]

    fun list(includeTerminated: Boolean): List<ExecutionRecord> =
        records.values
            .filter { includeTerminated || it.state != ExecState.TERMINATED }
            .sortedByDescending { it.startedAt }

    fun all(): List<ExecutionRecord> = records.values.sortedByDescending { it.startedAt }
}
