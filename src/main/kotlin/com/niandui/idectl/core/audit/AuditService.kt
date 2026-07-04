package com.niandui.idectl.core.audit

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Append-only audit log (D8), one JSONL file per day under `~/.idectl/audit/`.
 * Best-effort: an IO failure here must never break a tool call.
 */
@Service(Service.Level.APP)
class AuditService {
    private val dir: Path = Path.of(System.getProperty("user.home"), ".idectl", "audit")

    @Synchronized
    fun record(
        subject: String,
        role: String,
        tool: String,
        project: String?,
        argsDigest: String,
        resultCode: String,
        durationMs: Long,
        mcpSessionId: String?,
    ) {
        try {
            Files.createDirectories(dir)
            val entry = JsonObject().apply {
                addProperty("ts", System.currentTimeMillis())
                addProperty("subject", subject)
                addProperty("role", role)
                addProperty("tool", tool)
                addProperty("project", project ?: "")
                addProperty("argsDigest", argsDigest)
                addProperty("resultCode", resultCode)
                addProperty("durationMs", durationMs)
                addProperty("mcpSessionId", mcpSessionId ?: "")
            }
            val today = LocalDate.now(ZoneOffset.UTC)
            val f = dir.resolve("audit-$today.jsonl")
            Files.writeString(
                f, entry.toString() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (t: Throwable) {
            thisLogger().warn("audit write failed for tool=$tool", t)
        }
    }
}
