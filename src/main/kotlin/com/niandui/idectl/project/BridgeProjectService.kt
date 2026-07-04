package com.niandui.idectl.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.niandui.idectl.core.build.BuildService
import com.niandui.idectl.core.debug.DebugController
import com.niandui.idectl.core.exec.ExecutionRegistry
import com.niandui.idectl.core.exec.RunLauncher
import com.niandui.idectl.core.test.TestResultCollector
import kotlinx.coroutines.CoroutineScope

/** Per-project domain services: the execution registry, launcher, build service, and debugger (L5). */
@Service(Service.Level.PROJECT)
class BridgeProjectService(val project: Project, val scope: CoroutineScope) : Disposable {

    val executions = ExecutionRegistry(project, scope)
    val launcher = RunLauncher(project, executions)
    val buildService = BuildService(project)
    val debug = DebugController(project)

    // Live from service creation (created on the first processStarting), so test roots are captured.
    private val testCollector = TestResultCollector(project, executions).also { it.install() }

    /** Disposed with the project: release every session's console archive temp files. */
    override fun dispose() {
        runCatching { executions.disposeAll() }
    }

    companion object {
        fun getInstance(project: Project): BridgeProjectService = project.service()
    }
}
