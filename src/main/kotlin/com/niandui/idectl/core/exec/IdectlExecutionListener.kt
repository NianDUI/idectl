package com.niandui.idectl.core.exec

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.niandui.idectl.project.IdectlProjectService

/**
 * Declarative (plugin.xml projectListeners) subscriber to ExecutionManager.EXECUTION_TOPIC.
 * Because it is declarative it is live from IDE start — so user-launched sessions are captured too
 * (core①). The zero-loss console attach happens in [onProcessStarting] before startNotify (R03).
 */
class IdectlExecutionListener : ExecutionListener {

    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        registry(env)?.onProcessStarting(executorId, env, handler)
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        registry(env)?.onProcessStarted(executorId, env, handler)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        registry(env)?.onProcessNotStarted(env)
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        registry(env)?.onProcessTerminated(handler, exitCode)
    }

    private fun registry(env: ExecutionEnvironment): ExecutionRegistry? = try {
        val project = env.project
        if (project.isDisposed) null else project.service<IdectlProjectService>().executions
    } catch (t: Throwable) {
        thisLogger().warn("failed to route execution event", t)
        null
    }
}
