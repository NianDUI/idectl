package com.niandui.idectl.core.build

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.task.ProjectTaskManager
import com.niandui.idectl.core.util.refreshProjectVfs
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Build域 (D10/D11). Maps the four IDE build actions to ProjectTaskManager and collects structured
 * diagnostics. M2 first cut uses the CompilerTopics (JPS) channel — accurate for JPS/Maven builds;
 * the BuildViewManager channel (delegated Gradle) is a follow-up. Builds serialize per project.
 */
class BuildService(private val project: Project) {

    private val mutex = Mutex()

    data class BuildError(
        val file: String?, val line: Int?, val column: Int?,
        val message: String, val severity: String, val module: String?,
    )

    class Result(
        val status: String, // ok | errors | aborted | timeout
        val errors: List<BuildError>,
        val errorsTotal: Int,
        val warningsTotal: Int,
        val durationMs: Long,
        val queued: Boolean,
    )

    suspend fun build(
        scope: String,
        mode: String,
        moduleNames: List<String>?,
        filePaths: List<String>?,
        timeoutMs: Long,
    ): Result {
        val wasBusy = mutex.isLocked
        return mutex.withLock { doBuild(scope, mode, moduleNames, filePaths, timeoutMs, wasBusy) }
    }

    private suspend fun doBuild(
        scope: String, mode: String, moduleNames: List<String>?, filePaths: List<String>?,
        timeoutMs: Long, wasBusy: Boolean,
    ): Result {
        val start = System.currentTimeMillis()
        // Pick up external on-disk edits before compiling, else JPS sees files as unchanged.
        refreshProjectVfs(project)
        val collector = Collector()
        val connection = project.messageBus.connect()
        connection.subscribe(CompilerTopics.COMPILATION_STATUS, collector)
        try {
            val ptm = ProjectTaskManager.getInstance(project)
            val promise = withContext(Dispatchers.EDT) {
                when (scope) {
                    "files" -> ptm.compile(*resolveFiles(filePaths))
                    "module" -> {
                        val mods = resolveModules(moduleNames)
                        if (mode == "rebuild") ptm.rebuild(*mods) else ptm.build(*mods)
                    }
                    else -> if (mode == "rebuild") ptm.rebuildAllModules() else ptm.buildAllModules()
                }
            }
            val result = withTimeoutOrNull(timeoutMs) { promise.await() }
            val errs = collector.errors.toList()
            val errorCount = errs.count { it.severity == "error" }
            val status = when {
                result == null -> "timeout"
                result.isAborted -> "aborted"
                result.hasErrors() || errorCount > 0 -> "errors"
                else -> "ok"
            }
            return Result(
                status = status,
                errors = errs,
                errorsTotal = errorCount,
                warningsTotal = errs.count { it.severity == "warning" },
                durationMs = System.currentTimeMillis() - start,
                queued = wasBusy,
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun resolveModules(names: List<String>?): Array<Module> {
        if (names.isNullOrEmpty()) {
            throw ToolException(ErrorCodes.INVALID_ARGUMENT, "scope=module requires a non-empty modules[]")
        }
        return readAction {
            val all = ModuleManager.getInstance(project).modules
            names.map { name ->
                all.firstOrNull { it.name == name }
                    ?: throw ToolException(ErrorCodes.NOT_FOUND, "module not found: $name")
            }.toTypedArray()
        }
    }

    private suspend fun resolveFiles(paths: List<String>?): Array<VirtualFile> {
        if (paths.isNullOrEmpty()) {
            throw ToolException(ErrorCodes.INVALID_ARGUMENT, "scope=files requires a non-empty files[]")
        }
        return readAction {
            paths.map { path ->
                LocalFileSystem.getInstance().findFileByPath(path)
                    ?: throw ToolException(ErrorCodes.NOT_FOUND, "file not found: $path")
            }.toTypedArray()
        }
    }

    /** CompilerTopics channel: structured file:line diagnostics (1-based on the wire). */
    private inner class Collector : CompilationStatusListener {
        val errors = CopyOnWriteArrayList<BuildError>()

        override fun compilationFinished(aborted: Boolean, errorsCount: Int, warningsCount: Int, context: CompileContext) {
            try {
                collect(context, CompilerMessageCategory.ERROR, "error")
                collect(context, CompilerMessageCategory.WARNING, "warning")
            } catch (t: Throwable) {
                thisLogger().warn("diagnostics collection failed", t)
            }
        }

        private fun collect(context: CompileContext, category: CompilerMessageCategory, severity: String) {
            for (msg in context.getMessages(category)) {
                val vf = msg.virtualFile
                val nav = msg.navigatable as? OpenFileDescriptor
                val module = try {
                    vf?.let { context.getModuleByFile(it)?.name }
                } catch (_: Throwable) {
                    null
                }
                errors.add(
                    BuildError(
                        file = vf?.path,
                        line = nav?.line?.let { it + 1 },   // OpenFileDescriptor is 0-based → 1-based
                        column = nav?.column?.let { it + 1 },
                        message = msg.message,
                        severity = severity,
                        module = module,
                    ),
                )
            }
        }
    }
}
