package com.niandui.idectl.tools.admin

import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str
import com.niandui.idectl.transport.strArr
import com.niandui.idectl.transport.strList

/**
 * `create_token` (M3, admin only) — mint a scoped Bearer token so a sub-agent can be given a lower
 * role and/or a restricted project set. Returns the full secret once, plus a ready-to-use connect line.
 */
class CreateTokenTool : Tool {
    override val name = "create_token"
    override val description =
        "Issue a new scoped access token with its own role (viewer|operator|admin) and optional project " +
            "allow-list. Admin only. Returns the full token (shown once) and an MCP connect command."
    override val minRole = Role.ADMIN
    override val readOnly = false
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "subject" to Schema.string("A label identifying who/what this token is for (required)."),
        "role" to Schema.string("viewer | operator | admin (default viewer).", listOf("viewer", "operator", "admin")),
        "projects" to Schema.stringArray("Allowed project base paths; empty = all projects."),
        required = listOf("subject"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val subject = ctx.args.str("subject")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'subject' is required")
        val role = Role.from(ctx.args.str("role") ?: "viewer")
        val projects = ctx.args.strList("projects") ?: emptyList()

        val entry = ctx.app.tokenStore.createToken(subject, role, projects)
        val token = entry.token ?: throw ToolException(ErrorCodes.INTERNAL, "token generation failed")
        val port = ctx.app.server.port
        val connect =
            "claude mcp add --transport http idectl-$subject " +
                "http://127.0.0.1:$port/mcp --header \"Authorization: Bearer $token\""
        return ToolCallResult.ok(jObj {
            addProperty("token", token)
            addProperty("subject", subject)
            addProperty("role", role.name.lowercase())
            add("projects", strArr(projects))
            addProperty("connectCommand", connect)
        })
    }
}
