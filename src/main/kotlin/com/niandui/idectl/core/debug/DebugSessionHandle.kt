package com.niandui.idectl.core.debug

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueContainer
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.ToolException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.resume

/** Hard ceiling on any single debugger async computation (frames/children/presentation) so a
 * never-arriving callback can never hang the HTTP request; partial results are returned instead. */
private const val COMPUTE_TIMEOUT_MS = 10_000L

/** What kind of stepping/flow command debug_control issues. */
enum class DebugAction { RESUME, PAUSE, STEP_OVER, STEP_INTO, STEP_OUT, RUN_TO_LINE }

/** A source location (1-based line for the Agent). */
data class DebugLocation(val file: String?, val line: Int?, val frameLabel: String?)

/** Result of a debug_control command: the session's state after (optionally) waiting for the next pause. */
data class ControlOutcome(val state: String, val paused: Boolean, val location: DebugLocation?)

/** One stack frame. `line` is 1-based; index 0 is the top (current) frame. */
data class FrameInfo(val index: Int, val label: String, val file: String?, val line: Int?)

/** One variable in a frame (or a child of another variable). */
data class VarInfo(val name: String, val type: String?, val value: String, val hasChildren: Boolean)

/** Result of an expression evaluation. */
data class EvalOutcome(
    val ok: Boolean,
    val type: String?,
    val value: String?,
    val hasChildren: Boolean,
    val error: String?,
)

/**
 * Wraps one live [XDebugSession] and bridges its callback-based async API (stack/variables/evaluate,
 * all delivered on the debugger's worker thread) into suspend functions. Long-poll on the next pause
 * is lost-wakeup-free via a monotonically increasing [pauseEpoch] StateFlow (same trick core② uses).
 */
class DebugSessionHandle(val session: XDebugSession) {

    private val pauseEpoch = MutableStateFlow(0L)

    @Volatile
    private var stopped = false

    init {
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                pauseEpoch.value = pauseEpoch.value + 1
            }

            override fun sessionStopped() {
                stopped = true
                pauseEpoch.value = pauseEpoch.value + 1
            }
        })
    }

    fun isStopped(): Boolean = stopped || session.debugProcess.processHandler.isProcessTerminated

    fun isSuspended(): Boolean = session.isSuspended && !isStopped()

    // ---- flow control ----

    suspend fun control(
        action: DebugAction,
        waitMs: Long,
        runToFile: VirtualFile?,
        runToLine0: Int?,
    ): ControlOutcome {
        when (action) {
            DebugAction.RESUME, DebugAction.STEP_OVER, DebugAction.STEP_INTO,
            DebugAction.STEP_OUT, DebugAction.RUN_TO_LINE ->
                if (!isSuspended()) throw ToolException(
                    ErrorCodes.NOT_SUSPENDED,
                    "session is not paused; ${action.name.lowercase()} needs a suspended session",
                    "hit a breakpoint first, or use action=pause",
                )
            DebugAction.PAUSE -> if (isStopped()) throw ToolException(
                ErrorCodes.NOT_SUSPENDED, "session has already terminated", null,
            )
        }
        val epoch = pauseEpoch.value
        withContext(Dispatchers.EDT) {
            when (action) {
                DebugAction.RESUME -> session.resume()
                DebugAction.PAUSE -> session.pause()
                DebugAction.STEP_OVER -> session.stepOver(false)
                DebugAction.STEP_INTO -> session.stepInto()
                DebugAction.STEP_OUT -> session.stepOut()
                DebugAction.RUN_TO_LINE -> {
                    val pos = XDebuggerUtil.getInstance().createPosition(runToFile!!, runToLine0!!)
                        ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "cannot place a run-to target there", null)
                    session.runToPosition(pos, false)
                }
            }
        }
        if (waitMs > 0) {
            withTimeoutOrNull(waitMs) { pauseEpoch.first { it != epoch } }
        }
        return currentOutcome()
    }

    private suspend fun currentOutcome(): ControlOutcome = when {
        isStopped() -> ControlOutcome("terminated", false, null)
        isSuspended() -> ControlOutcome("paused", true, topLocation())
        else -> ControlOutcome("running", false, null)
    }

    /**
     * Build the current location. Reuse the (proven) get_stack path to render the top frame's label —
     * `currentStackFrame.customizePresentation` can return blank right after a step, but the computed
     * top frame always has a full label.
     */
    private suspend fun topLocation(): DebugLocation {
        val top = runCatching { frames(1).firstOrNull() }.getOrNull()
        val pos = session.currentPosition
        return DebugLocation(
            file = top?.file ?: pos?.file?.path,
            line = top?.line ?: pos?.line?.plus(1),
            frameLabel = top?.label,
        )
    }

    // ---- inspection ----

    suspend fun frames(maxFrames: Int): List<FrameInfo> {
        val stack = activeStack()
        return computeFrames(stack, maxFrames).mapIndexed { i, f -> frameInfo(i, f) }
    }

    suspend fun variables(frameIndex: Int, path: List<String>, max: Int): List<VarInfo> {
        val stack = activeStack()
        var container: XValueContainer = frameAt(stack, frameIndex)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no stack frame at index $frameIndex", "call get_stack")
        for (name in path) {
            val kids = computeChildren(container, 1000)
            container = kids.firstOrNull { it.first == name }?.second
                ?: throw ToolException(ErrorCodes.NOT_FOUND, "no variable '$name' to expand", "check the names from a shallower get_variables")
        }
        return computeChildren(container, max).map { (n, v) ->
            val p = present(v)
            VarInfo(n, p.type, p.value, p.hasChildren)
        }
    }

    suspend fun evaluate(expr: String, frameIndex: Int, timeoutMs: Long): EvalOutcome {
        val stack = activeStack()
        val frame = frameAt(stack, frameIndex)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no stack frame at index $frameIndex", "call get_stack")
        val evaluator = frame.evaluator
            ?: throw ToolException(ErrorCodes.UNAVAILABLE, "this frame has no expression evaluator", null)
        val raw = withTimeoutOrNull(timeoutMs) { bridgeEvaluate(evaluator, expr, frame) }
            ?: return EvalOutcome(false, null, null, false, "evaluation timed out")
        return when (raw) {
            is EvalRaw.Ok -> present(raw.value).let { EvalOutcome(true, it.type, it.value, it.hasChildren, null) }
            is EvalRaw.Err -> EvalOutcome(false, null, null, false, raw.message)
        }
    }

    private fun activeStack(): XExecutionStack {
        if (!isSuspended()) throw ToolException(
            ErrorCodes.NOT_SUSPENDED, "session is not paused", "hit a breakpoint, then retry",
        )
        val ctx = session.suspendContext
            ?: throw ToolException(ErrorCodes.NOT_SUSPENDED, "no suspend context", null)
        return ctx.activeExecutionStack
            ?: throw ToolException(ErrorCodes.UNAVAILABLE, "no active execution stack", null)
    }

    private fun frameInfo(index: Int, frame: XStackFrame): FrameInfo {
        val label = CapturingColoredText().also { runCatching { frame.customizePresentation(it) } }.text()
        val pos = frame.sourcePosition
        return FrameInfo(index, label.ifBlank { "<frame $index>" }, pos?.file?.path, pos?.line?.plus(1))
    }

    private suspend fun frameAt(stack: XExecutionStack, index: Int): XStackFrame? {
        if (index <= 0) return stack.topFrame ?: computeFrames(stack, 1).firstOrNull()
        return computeFrames(stack, index + 1).getOrNull(index)
    }

    // ---- async callback bridges ----

    private suspend fun computeFrames(stack: XExecutionStack, max: Int): List<XStackFrame> {
        val out = java.util.Collections.synchronizedList(ArrayList<XStackFrame>())
        withTimeoutOrNull(COMPUTE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                        for (f in stackFrames) {
                            if (out.size >= max) break
                            out.add(f)
                        }
                        if (last || out.size >= max) cont.finish(done)
                    }

                    override fun errorOccurred(errorMessage: String) = cont.finish(done)
                })
            }
        }
        return out.toList()
    }

    private suspend fun computeChildren(container: XValueContainer, max: Int): List<Pair<String, XValue>> {
        val out = java.util.Collections.synchronizedList(ArrayList<Pair<String, XValue>>())
        withTimeoutOrNull(COMPUTE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                container.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            if (out.size >= max) break
                            out.add(children.getName(i) to children.getValue(i))
                        }
                        if (last || out.size >= max) cont.finish(done)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun tooManyChildren(remaining: Int) = cont.finish(done)
                    override fun setAlreadySorted(alreadySorted: Boolean) {}
                    override fun setErrorMessage(errorMessage: String) = cont.finish(done)
                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) =
                        cont.finish(done)

                    override fun setMessage(
                        message: String,
                        icon: Icon?,
                        attributes: SimpleTextAttributes,
                        link: XDebuggerTreeNodeHyperlink?,
                    ) {
                    }
                })
            }
        }
        return out.toList()
    }

    /**
     * Present a value. Java collections/objects render asynchronously: the first callback carries a
     * "Collecting data…" placeholder ([XValuePresentation.isAsync]) and a later one the real value —
     * so we settle on the latest presentation and only resume early on a non-async (final) one.
     */
    private suspend fun present(xval: XValue): Presentation {
        val holder = java.util.concurrent.atomic.AtomicReference<Presentation?>(null)
        withTimeoutOrNull(COMPUTE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                xval.computePresentation(object : XValueNode {
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                        holder.set(Presentation(type, value, hasChildren))
                        cont.finish(done)
                    }

                    override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                        val sb = StringBuilder()
                        runCatching { presentation.renderValue(textRenderer(sb)) }
                        holder.set(Presentation(presentation.type, sb.toString(), hasChildren))
                        if (!presentation.isAsync) cont.finish(done)
                    }

                    override fun setFullValueEvaluator(fullValueEvaluator: com.intellij.xdebugger.frame.XFullValueEvaluator) {}
                }, XValuePlace.TREE)
            }
        }
        return holder.get() ?: Presentation(null, "", false)
    }

    private suspend fun bridgeEvaluate(
        evaluator: com.intellij.xdebugger.evaluation.XDebuggerEvaluator,
        expr: String,
        frame: XStackFrame,
    ): EvalRaw = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        evaluator.evaluate(
            expr,
            object : com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) = cont.tryResume(done, EvalRaw.Ok(result))
                override fun errorOccurred(errorMessage: String) = cont.tryResume(done, EvalRaw.Err(errorMessage))
            },
            frame.sourcePosition,
        )
    }

    private fun <T> CancellableContinuation<T>.tryResume(done: AtomicBoolean, value: T) {
        if (done.compareAndSet(false, true) && isActive) resume(value)
    }

    /** Resume a Unit-typed continuation exactly once (accumulators are read from the outer scope). */
    private fun CancellableContinuation<Unit>.finish(done: AtomicBoolean) {
        if (done.compareAndSet(false, true) && isActive) resume(Unit)
    }

    private data class Presentation(val type: String?, val value: String, val hasChildren: Boolean)

    private sealed interface EvalRaw {
        data class Ok(val value: XValue) : EvalRaw
        data class Err(val message: String) : EvalRaw
    }

    /** Accumulates the fragments an [XStackFrame] appends when asked to render itself. */
    private class CapturingColoredText : ColoredTextContainer {
        private val sb = StringBuilder()
        override fun append(fragment: String, attributes: SimpleTextAttributes) {
            sb.append(fragment)
        }

        fun text(): String = sb.toString()
    }

    /** Flattens an [XValuePresentation] (the rich renderer Java values use) into a plain string. */
    private fun textRenderer(sb: StringBuilder) = object : XValuePresentation.XValueTextRenderer {
        override fun renderValue(value: String) { sb.append(value) }
        override fun renderStringValue(value: String) { sb.append(value) }
        override fun renderNumericValue(value: String) { sb.append(value) }
        override fun renderKeywordValue(value: String) { sb.append(value) }
        override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
        override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
            sb.append(value)
        }
        override fun renderComment(comment: String) { sb.append(comment) }
        override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
        override fun renderError(error: String) { sb.append(error) }
    }
}
