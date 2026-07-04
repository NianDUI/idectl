package com.niandui.idectl.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.niandui.idectl.IdeaBridgeService
import com.niandui.idectl.session.McpSession
import com.niandui.idectl.session.Principal
import com.niandui.idectl.session.Role

/** Everything a tool handler needs. `project` is resolved and authorized by the gate (D4). */
class ToolContext(
    val app: IdeaBridgeService,
    val principal: Principal,
    val session: McpSession,
    /** Resolved target project, or null for project-agnostic tools. */
    val project: Project?,
    val args: JsonObject,
)

/**
 * One MCP tool (03). `minRole` and the readOnly/destructive hints must agree with the role matrix;
 * `tools/list` hides tools whose minRole the caller does not satisfy.
 */
interface Tool {
    val name: String
    val description: String
    val minRole: Role
    val readOnly: Boolean
    val destructive: Boolean
    val inputSchema: JsonObject

    /** Whether this tool needs a resolved project (the gate enforces routing/authorization). */
    val requiresProject: Boolean get() = true

    suspend fun execute(ctx: ToolContext): ToolCallResult
}
