package com.niandui.idectl.tools.admin

import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str

/** `revoke_token` (M3, admin only) — remove scoped tokens by subject, exact secret, or secret prefix. */
class RevokeTokenTool : Tool {
    override val name = "revoke_token"
    override val description =
        "Revoke scoped token(s) matching a subject label, an exact token, or a token prefix (>= 6 chars). " +
            "Admin only. Returns how many were removed."
    override val minRole = Role.ADMIN
    override val readOnly = false
    override val destructive = true
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "match" to Schema.string("Subject label, exact token, or token prefix to revoke (required)."),
        required = listOf("match"),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val match = ctx.args.str("match")
            ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "'match' is required")
        val removed = ctx.app.tokenStore.revokeToken(match)
        return ToolCallResult.ok(jObj {
            addProperty("revoked", removed)
            addProperty("match", match)
        })
    }
}
