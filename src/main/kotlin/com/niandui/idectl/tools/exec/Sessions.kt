package com.niandui.idectl.tools.exec

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.niandui.idectl.core.console.ConsoleLine
import com.niandui.idectl.core.console.Stream
import com.niandui.idectl.core.exec.ExecState
import com.niandui.idectl.core.exec.ExecutionRecord
import com.niandui.idectl.gate.ProjectResolver
import com.niandui.idectl.project.IdectlProjectService
import com.niandui.idectl.transport.jObj

/** Session lookup + JSON rendering shared by the execution/console tools. */
object Sessions {

    /** Find a session across all open projects (session ids are globally unique). */
    fun find(sessionId: String): Pair<Project, ExecutionRecord>? {
        for (p in ProjectResolver.openProjects()) {
            val record = IdectlProjectService.getInstance(p).executions.find(sessionId)
            if (record != null) return p to record
        }
        return null
    }

    fun stateName(record: ExecutionRecord): String =
        if (record.state == ExecState.TERMINATED) "terminated" else "running"

    fun toJson(record: ExecutionRecord): JsonObject = jObj {
        addProperty("sessionId", record.sessionId)
        addProperty("project", record.project.basePath ?: "")
        addProperty("configName", record.configName)
        addProperty("typeId", record.typeId ?: "")
        addProperty("executor", record.executor)
        addProperty("state", stateName(record))
        record.exitCode?.let { addProperty("exitCode", it) }
        addProperty("startedAt", record.startedAt)
        record.endedAt?.let { addProperty("endedAt", it) }
        addProperty("startedBy", record.startedBy)
        addProperty("ownerSubject", record.owner)
        record.restartOf?.let { addProperty("restartOf", it) }
        addProperty("pty", record.pty)
        addProperty("attachedLate", record.attachedLate)
        addProperty("firstAvailableOffset", record.console.buffer.firstAvailableOffset())
        addProperty("nextOffset", record.console.buffer.currentNextOffset())
        addProperty("hasDebug", record.isDebug)
        addProperty("hasTestResults", record.testRoot != null)
    }

    fun lineJson(line: ConsoleLine): JsonObject = jObj {
        addProperty("offset", line.offset)
        addProperty("ts", line.ts)
        addProperty("stream", Stream.name(line.stream))
        addProperty("text", line.text)
        addProperty("truncated", line.truncated)
    }
}
