package com.niandui.idectl.core.debug

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.niandui.idectl.core.exec.ExecutionRecord
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One line breakpoint, as the Agent sees it. `line` is 1-based. */
data class BpInfo(
    val file: String,
    val line: Int,
    val enabled: Boolean,
    val condition: String?,
    val typeId: String,
    val resolved: Boolean,
)

/**
 * Per-project debugger facade (M4). Two responsibilities:
 *  1. correlate a run/debug [ExecutionRecord] to its live [XDebugSession] (matched by process handler)
 *     and hand back a cached [DebugSessionHandle];
 *  2. manage project-level line breakpoints, which exist independently of any running session.
 */
class DebugController(private val project: Project) {

    private val handles = ConcurrentHashMap<String, DebugSessionHandle>()

    /** Resolve (and cache) the debug handle for a session, or throw a structured error the Agent can act on. */
    fun handleFor(record: ExecutionRecord): DebugSessionHandle {
        if (!record.isDebug) throw ToolException(
            ErrorCodes.NOT_DEBUG_SESSION,
            "session ${record.sessionId} is a Run session, not a Debug session",
            "restart_session with mode=debug, then retry",
        )
        handles[record.sessionId]?.let { if (!it.isStopped()) return it }
        val session = XDebuggerManager.getInstance(project).debugSessions.firstOrNull {
            it.debugProcess.processHandler === record.processHandler
        } ?: throw ToolException(
            ErrorCodes.UNAVAILABLE,
            "the debugger has not attached to session ${record.sessionId} yet (or has detached)",
            "wait until the process is running under Debug, then retry",
        )
        return DebugSessionHandle(session).also { handles[record.sessionId] = it }
    }

    // ---- breakpoints (project-scoped) ----

    @Suppress("DEPRECATION") // findBreakpointAtLine: the async replacement is not needed for a one-shot lookup
    suspend fun setBreakpoint(path: String, line1: Int, enabled: Boolean, condition: String?): BpInfo {
        val file = resolveFile(path)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "file not found: $path", "pass an absolute path or one relative to the project root")
        val line0 = (line1 - 1).coerceAtLeast(0)
        return withContext(Dispatchers.EDT) {
            runWriteAction {
                val manager = breakpointManager()
                val type = javaLineType()
                val bp = manager.findBreakpointAtLine(type, file, line0) ?: addLine(manager, type, file, line0)
                bp.isEnabled = enabled
                if (condition != null) bp.setCondition(condition.ifBlank { null })
                info(bp)
            }
        }
    }

    @Suppress("DEPRECATION") // findBreakpointAtLine: the async replacement is not needed for a one-shot lookup
    suspend fun removeBreakpoint(path: String, line1: Int): Boolean {
        val file = resolveFile(path) ?: return false
        val line0 = (line1 - 1).coerceAtLeast(0)
        return withContext(Dispatchers.EDT) {
            runWriteAction {
                val manager = breakpointManager()
                val bp = manager.findBreakpointAtLine(javaLineType(), file, line0) ?: return@runWriteAction false
                manager.removeBreakpoint(bp)
                true
            }
        }
    }

    suspend fun listBreakpoints(): List<BpInfo> = readAction {
        breakpointManager().allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .map { info(it) }
    }

    private fun breakpointManager(): XBreakpointManager =
        XDebuggerManager.getInstance(project).breakpointManager

    private fun javaLineType(): JavaLineBreakpointType =
        XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
            ?: throw ToolException(ErrorCodes.UNAVAILABLE, "Java line breakpoints are not available in this IDE", null)

    /** Explicit type parameter keeps the [XLineBreakpointType]/properties generics aligned. */
    private fun <P : XBreakpointProperties<*>> addLine(
        manager: XBreakpointManager,
        type: XLineBreakpointType<P>,
        file: VirtualFile,
        line0: Int,
    ): XLineBreakpoint<P> = manager.addLineBreakpoint(type, file.url, line0, type.createBreakpointProperties(file, line0))

    private fun info(bp: XLineBreakpoint<*>): BpInfo {
        val pos = bp.sourcePosition
        val path = pos?.file?.path ?: VfsUtilCore.urlToPath(bp.fileUrl)
        val line = (pos?.line ?: bp.line) + 1
        return BpInfo(path, line, bp.isEnabled, bp.conditionExpression?.expression, bp.type.id, pos != null)
    }

    /** Refresh + resolve, trying the path as-is then relative to the project root (picks up external edits). */
    fun resolveFile(path: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        lfs.refreshAndFindFileByPath(path)?.let { return it }
        val base = project.basePath ?: return null
        val joined = if (path.startsWith("/")) path else "$base/$path"
        return lfs.refreshAndFindFileByPath(joined)
    }
}
