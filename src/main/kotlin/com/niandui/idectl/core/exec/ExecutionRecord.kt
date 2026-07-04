package com.niandui.idectl.core.exec

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import com.niandui.idectl.core.console.ConsoleStore

enum class ExecState { STARTING, RUNNING, TERMINATED }

/** One tracked execution — Agent-launched or user-launched (the manual-session tracking is core①). */
class ExecutionRecord(
    val sessionId: String,
    val project: Project,
    val configName: String,
    val typeId: String?,
    val executorId: String,
    val settings: RunnerAndConfigurationSettings?,
    val environment: ExecutionEnvironment,
    val processHandler: ProcessHandler,
    /** token.subject that launched it, or "user" for manual launches. */
    val owner: String,
    val startedBy: String, // "agent" | "user"
    val startedAt: Long,
    val restartOf: String?,
    val console: ConsoleStore,
    val attachedLate: Boolean = false,
) {
    @Volatile var state: ExecState = ExecState.STARTING
    @Volatile var exitCode: Int? = null
    @Volatile var endedAt: Long? = null

    /** SM test runner tree root, captured when this session is a test run (set by TestResultCollector). */
    @Volatile var testRoot: SMTestProxy.SMRootTestProxy? = null

    val isDebug: Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID
    val executor: String = if (isDebug) "debug" else "run"
    val pty: Boolean = processHandler.javaClass.name.contains("Pty", ignoreCase = true)
}
