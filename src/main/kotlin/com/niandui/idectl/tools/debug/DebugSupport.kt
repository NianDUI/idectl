package com.niandui.idectl.tools.debug

import com.google.gson.JsonObject
import com.niandui.idectl.core.debug.BpInfo
import com.niandui.idectl.core.debug.DebugLocation
import com.niandui.idectl.core.debug.DebugSessionHandle
import com.niandui.idectl.core.debug.FrameInfo
import com.niandui.idectl.core.debug.VarInfo
import com.niandui.idectl.core.exec.ExecutionRecord
import com.niandui.idectl.project.BridgeProjectService
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.tools.exec.Sessions
import com.niandui.idectl.transport.jObj

/** Shared session→handle resolution and JSON rendering for the M4 debugger tools. */
object DebugSupport {

    /** Resolve a session id to its record + live debug handle (throws structured errors otherwise). */
    fun handle(sessionId: String): Pair<ExecutionRecord, DebugSessionHandle> {
        val (project, record) = Sessions.find(sessionId)
            ?: throw ToolException(ErrorCodes.NOT_FOUND, "no session $sessionId", "call list_sessions")
        val handle = BridgeProjectService.getInstance(project).debug.handleFor(record)
        return record to handle
    }

    fun frameJson(f: FrameInfo): JsonObject = jObj {
        addProperty("index", f.index)
        addProperty("label", f.label)
        addProperty("file", f.file ?: "")
        f.line?.let { addProperty("line", it) }
    }

    fun varJson(v: VarInfo): JsonObject = jObj {
        addProperty("name", v.name)
        // Primitives have no separate type (their value is self-describing); only objects carry one.
        v.type?.takeIf { it.isNotBlank() }?.let { addProperty("type", it) }
        addProperty("value", v.value)
        addProperty("hasChildren", v.hasChildren)
    }

    fun locationJson(l: DebugLocation?): JsonObject? = l?.let {
        jObj {
            addProperty("file", it.file ?: "")
            it.line?.let { line -> addProperty("line", line) }
            it.frameLabel?.let { label -> addProperty("frame", label) }
        }
    }

    fun bpJson(b: BpInfo): JsonObject = jObj {
        addProperty("file", b.file)
        addProperty("line", b.line)
        addProperty("enabled", b.enabled)
        b.condition?.let { addProperty("condition", it) }
        addProperty("typeId", b.typeId)
        addProperty("resolved", b.resolved)
    }
}
