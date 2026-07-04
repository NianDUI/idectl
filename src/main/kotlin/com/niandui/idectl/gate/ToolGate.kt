package com.niandui.idectl.gate

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.niandui.idectl.IdeaBridgeService
import com.niandui.idectl.session.McpSession
import com.niandui.idectl.session.Principal
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.tools.ToolGroups
import com.niandui.idectl.transport.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single choke point (L2) every tool call passes through: role gate → project routing +
 * authorization → execute → audit. Centralizing this is what keeps authorization from being
 * scattered and forgotten (D4/D8).
 */
class ToolGate(private val app: IdeaBridgeService) {

    suspend fun dispatch(tool: Tool, session: McpSession, args: JsonObject): ToolCallResult {
        val principal = session.principal
        val startNs = System.nanoTime()
        var project: Project? = null
        var resultCode = "OK"
        try {
            if (!principal.role.satisfies(tool.minRole)) {
                throw ToolException(
                    ErrorCodes.PERMISSION_DENIED,
                    "tool '${tool.name}' requires role ${tool.minRole}, you are ${principal.role}",
                    "use a token with a higher role",
                )
            }
            // Owner kill switch (policy panel): a disabled tool is unavailable to everyone, incl. admin.
            if (app.settings.isToolDisabled(tool.name)) {
                throw ToolException(
                    ErrorCodes.UNAVAILABLE,
                    "tool '${tool.name}' is disabled by the IDE owner",
                    "enable it in Settings | Tools | IDE Control",
                )
            }
            project = resolveProject(tool, session, principal, args)

            // Per-tool timeout override: inject as the default when the caller didn't pass one; the
            // waiting tools all read a `timeout_sec` argument, so this transparently caps their wait.
            val overrideSec = app.settings.toolTimeoutSecOverride(tool.name)
            if (overrideSec > 0 && ToolGroups.supportsTimeout(tool.name) && !args.has("timeout_sec")) {
                args.addProperty("timeout_sec", overrideSec)
            }

            // Human-in-the-loop approval (fail-closed): blocks until the owner approves or it times out.
            if (app.settings.toolRequiresApproval(tool.name)) {
                val approved = ApprovalGate.request(
                    project, tool, args, app.settings.approvalTimeoutSec.coerceAtLeast(1) * 1000L,
                )
                if (!approved) {
                    resultCode = ErrorCodes.PERMISSION_DENIED
                    throw ToolException(
                        ErrorCodes.PERMISSION_DENIED,
                        "call to '${tool.name}' was not approved by the IDE owner",
                        null,
                    )
                }
            }

            // L4 threading rule: tool handlers run on Dispatchers.Default; they switch to EDT/readAction
            // internally as needed. Never run tool bodies on the Ktor IO thread.
            val result = withContext(Dispatchers.Default) {
                tool.execute(ToolContext(app, principal, session, project, args))
            }
            resultCode = if (result.isError) codeOf(result) else "OK"
            return result
        } catch (e: CancellationException) {
            resultCode = "CANCELLED"
            throw e
        } catch (e: ToolException) {
            resultCode = e.code
            return ToolCallResult.error(e)
        } catch (t: Throwable) {
            resultCode = ErrorCodes.INTERNAL
            thisLogger().warn("tool '${tool.name}' failed", t)
            return ToolCallResult.error(ErrorCodes.INTERNAL, t.message ?: t.javaClass.simpleName)
        } finally {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            app.audit.record(
                subject = principal.subject,
                role = principal.role.name.lowercase(),
                tool = tool.name,
                project = project?.basePath,
                argsDigest = digest(args),
                resultCode = resultCode,
                durationMs = durationMs,
                mcpSessionId = session.id,
            )
        }
    }

    private fun resolveProject(tool: Tool, session: McpSession, principal: Principal, args: JsonObject): Project? {
        val hint = args.str("project") ?: session.boundProjectPath
        val open = ProjectResolver.openProjects()
        val project: Project? = when {
            hint != null -> ProjectResolver.resolve(hint)
                ?: if (tool.requiresProject) throw ToolException(
                    ErrorCodes.NOT_FOUND, "project not found: $hint",
                    "call get_ide_state to see openProjects[].path",
                ) else null
            open.size == 1 -> open.first() // auto-bind the only open project (D4)
            tool.requiresProject -> throw ToolException(
                ErrorCodes.PROJECT_NOT_BOUND,
                if (open.isEmpty()) "no project open" else "multiple projects open and none bound",
                "call bind_project or pass the 'project' argument",
            )
            else -> null
        }
        if (project != null && !principal.mayAccessProject(project.basePath ?: "")) {
            // Explicitly-targeted (hint) or project-requiring tools must fail; project-agnostic tools
            // that merely auto-bound the sole open project just run unbound (so discovery still works).
            if (hint != null || tool.requiresProject) {
                throw ToolException(
                    ErrorCodes.PERMISSION_DENIED, "project '${project.name}' is outside your token scope", null,
                )
            }
            return null
        }
        return project
    }

    private fun codeOf(result: ToolCallResult): String =
        (result.structured as? JsonObject)?.get("code")?.asString ?: "ERROR"

    private fun digest(args: JsonObject): String {
        val s = args.toString()
        return if (s.length <= 300) s else s.substring(0, 300) + "…"
    }
}
