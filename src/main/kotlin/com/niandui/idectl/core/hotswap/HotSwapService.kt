package com.niandui.idectl.core.hotswap

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.ui.HotSwapStatusListener
import com.intellij.debugger.ui.HotSwapUI
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.niandui.idectl.core.util.refreshProjectVfs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Core③ hot reload: compile the changed classes and redefine them in the debugged JVM without a
 * restart, via the Java debugger's official HotSwap facility (D16). Method-body-only changes
 * succeed (JDWP redefineClasses); structural changes fail honestly and should restart in debug.
 */
class HotSwapService(private val project: Project) {

    enum class Outcome { RELOADED, NOTHING_TO_RELOAD, FAILED, CANCELLED, TIMEOUT }

    /** Map an execution session's ProcessHandler to its live Java DebuggerSession, if any. */
    fun findDebuggerSession(handler: ProcessHandler): DebuggerSession? =
        DebuggerManagerEx.getInstanceEx(project).sessions.firstOrNull { it.process.processHandler === handler }

    /**
     * Compile changed classes and hot-swap them into the target JVM. Equivalent to the user's habit
     * of "rebuild the modified class → the IDE reloads it". Returns as soon as the HotSwap finishes.
     */
    suspend fun reload(session: DebuggerSession, timeoutMs: Long): Outcome {
        // Sync external on-disk edits into the IDE first, else HotSwap finds "nothing to reload".
        refreshProjectVfs(project)
        val deferred = CompletableDeferred<Outcome>()
        val listener = object : HotSwapStatusListener {
            override fun onSuccess(sessions: MutableList<DebuggerSession>?) { deferred.complete(Outcome.RELOADED) }
            override fun onNothingToReload(sessions: MutableList<DebuggerSession>?) { deferred.complete(Outcome.NOTHING_TO_RELOAD) }
            override fun onFailure(sessions: MutableList<DebuggerSession>?) { deferred.complete(Outcome.FAILED) }
            override fun onCancel(sessions: MutableList<DebuggerSession>?) { deferred.complete(Outcome.CANCELLED) }
        }
        withContext(Dispatchers.EDT) {
            // compileBeforeHotswap = true → compile changed classes first, then redefine.
            HotSwapUI.getInstance(project).reloadChangedClasses(session, true, listener)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: Outcome.TIMEOUT
    }
}
