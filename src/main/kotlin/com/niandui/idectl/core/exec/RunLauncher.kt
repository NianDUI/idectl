package com.niandui.idectl.core.exec

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiMethodUtil
import com.niandui.idectl.core.util.refreshProjectVfs
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Start / stop / restart orchestration (core①). Restart is stop→relaunch under the target executor. */
class RunLauncher(
    private val project: Project,
    private val registry: ExecutionRegistry,
) {
    class StopResult(val terminated: Boolean, val detached: Boolean, val exitCode: Int?)

    suspend fun listConfigurations(typeId: String?, nameContains: String?): List<RunnerAndConfigurationSettings> =
        readAction {
            RunManager.getInstance(project).allSettings.filter {
                (typeId == null || it.type.id == typeId) &&
                    (nameContains == null || it.name.contains(nameContains, ignoreCase = true))
            }
        }

    /** Launch [name] under run/debug. Returns the created record, or null on timeout/not-started. */
    suspend fun start(
        name: String,
        typeId: String?,
        debug: Boolean,
        owner: String,
        restartOf: String?,
        timeoutMs: Long,
        allowConflict: Boolean = false,
    ): ExecutionRecord? {
        val settings = resolveSettings(name, typeId)
        return launch(settings, debug, owner, restartOf, timeoutMs, allowConflict)
    }

    /** Launch an already-resolved (or dynamically-created) configuration. */
    suspend fun launch(
        settings: RunnerAndConfigurationSettings,
        debug: Boolean,
        owner: String,
        restartOf: String?,
        timeoutMs: Long,
        allowConflict: Boolean = false,
    ): ExecutionRecord? {
        if (!allowConflict) {
            val running = registry.list(includeTerminated = false).firstOrNull {
                it.configName == settings.name && it.typeId == settings.type.id
            }
            if (running != null) {
                throw ToolException(
                    ErrorCodes.CONFLICT,
                    "'${settings.name}' is already running (session ${running.sessionId})",
                    "stop_session or restart_session the existing one, or enable parallel runs",
                )
            }
        }
        val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance()
        else DefaultRunExecutor.getRunExecutorInstance()

        val env = withContext(Dispatchers.EDT) {
            ExecutionEnvironmentBuilder.create(executor, settings).build().also { it.assignNewExecutionId() }
        }
        registry.registerPending(env.executionId, owner, restartOf)
        try {
            withContext(Dispatchers.EDT) { env.runner.execute(env) }
        } catch (t: Throwable) {
            registry.onProcessNotStarted(env)
            throw ToolException(ErrorCodes.INTERNAL, "failed to launch '${settings.name}': ${t.message}")
        }
        return registry.awaitRecord(env.executionId, timeoutMs)
    }

    /** Create a JUnit run configuration for a test class (optionally a single method) on the fly. */
    suspend fun createJUnitTest(className: String, method: String?): RunnerAndConfigurationSettings {
        // Pick up externally-created/edited test files, then resolve in smart mode (waits for indexing).
        refreshProjectVfs(project)
        return smartReadAction(project) {
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
                ?: throw ToolException(
                    ErrorCodes.NOT_FOUND, "test class not found: $className",
                    "use the fully-qualified class name (e.g. com.foo.BarTest)",
                )
            val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
            val name = if (method != null) "$className.$method" else className
            val settings = RunManager.getInstance(project).createConfiguration(name, factory)
            val config = settings.configuration as JUnitConfiguration
            config.beClassConfiguration(psiClass)
            if (!method.isNullOrBlank()) {
                config.persistentData.METHOD_NAME = method
                config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
            }
            settings
        }
    }

    /** Create a Java Application run configuration for a class with a main() method, on the fly. */
    suspend fun createApplication(
        className: String,
        programArgs: String?,
        vmOptions: String?,
    ): RunnerAndConfigurationSettings {
        refreshProjectVfs(project)
        return smartReadAction(project) {
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
                ?: throw ToolException(
                    ErrorCodes.NOT_FOUND, "class not found: $className",
                    "use the fully-qualified class name (e.g. com.foo.Main)",
                )
            if (!PsiMethodUtil.hasMainMethod(psiClass)) {
                throw ToolException(
                    ErrorCodes.INVALID_ARGUMENT, "class $className has no main() method",
                    "point at a class with 'public static void main(String[])'",
                )
            }
            val factory = ApplicationConfigurationType.getInstance().configurationFactories.first()
            val settings = RunManager.getInstance(project).createConfiguration(className.substringAfterLast('.'), factory)
            val config = settings.configuration as ApplicationConfiguration
            config.setMainClass(psiClass) // sets main class name + module from the PsiClass
            if (!programArgs.isNullOrBlank()) config.setProgramParameters(programArgs)
            if (!vmOptions.isNullOrBlank()) config.setVMParameters(vmOptions)
            settings
        }
    }

    suspend fun stop(record: ExecutionRecord, force: Boolean, timeoutMs: Long): StopResult {
        val handler = record.processHandler
        if (handler.isProcessTerminated) {
            return StopResult(terminated = true, detached = false, exitCode = record.exitCode)
        }
        var detached = false
        withContext(Dispatchers.EDT) {
            when {
                handler.detachIsDefault() -> { handler.detachProcess(); detached = true }
                // destroyProcess() is the graceful stop (SIGTERM → shutdown hooks run);
                // KillableProcessHandler.killProcess() is the hard SIGKILL. Don't invert these.
                force && handler is KillableProcessHandler -> handler.killProcess()
                else -> handler.destroyProcess()
            }
        }
        val terminated = withTimeoutOrNull(timeoutMs) {
            while (!handler.isProcessTerminated) delay(100)
            true
        } ?: false
        if (!terminated && !detached) {
            // Graceful stop didn't finish in time → escalate to a hard kill.
            withContext(Dispatchers.EDT) {
                if (handler is KillableProcessHandler) handler.killProcess() else handler.destroyProcess()
            }
            withTimeoutOrNull(3000) { while (!handler.isProcessTerminated) delay(100) }
        }
        return StopResult(handler.isProcessTerminated, detached, record.exitCode)
    }

    suspend fun restart(
        record: ExecutionRecord,
        mode: String, // same | run | debug
        owner: String,
        timeoutMs: Long,
    ): ExecutionRecord? {
        val settings = record.settings ?: throw ToolException(
            ErrorCodes.NOT_FOUND,
            "session ${record.sessionId} has no associated run configuration",
            "use run_configuration with an explicit configuration name instead",
        )
        val targetDebug = when (mode) {
            "run" -> false
            "debug" -> true
            else -> record.isDebug // same
        }
        if (record.state != ExecState.TERMINATED) {
            stop(record, force = false, timeoutMs = timeoutMs)
        }
        return start(
            name = settings.name,
            typeId = settings.type.id,
            debug = targetDebug,
            owner = owner,
            restartOf = record.sessionId,
            timeoutMs = timeoutMs,
            allowConflict = true, // we just stopped the predecessor
        )
    }

    private suspend fun resolveSettings(name: String, typeId: String?): RunnerAndConfigurationSettings {
        val candidates = readAction {
            RunManager.getInstance(project).allSettings.filter {
                it.name == name && (typeId == null || it.type.id == typeId)
            }
        }
        return when {
            candidates.isEmpty() -> throw ToolException(
                ErrorCodes.NOT_FOUND,
                "no run configuration named '$name'" + (typeId?.let { " with type_id '$it'" } ?: ""),
                "call list_run_configurations to see available names/typeIds",
            )
            candidates.size > 1 && typeId == null -> throw ToolException(
                ErrorCodes.INVALID_ARGUMENT,
                "ambiguous configuration '$name' across types ${candidates.map { it.type.id }}",
                "pass type_id to disambiguate",
            )
            else -> candidates.first()
        }
    }
}
