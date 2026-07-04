package com.niandui.idectl.tools.admin

import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.strArr

/** `list_tokens` (M3, admin only) — scoped tokens with metadata (secrets shown only as a prefix). */
class ListTokensTool : Tool {
    override val name = "list_tokens"
    override val description =
        "List issued scoped tokens (subject, role, allowed projects, and a short token prefix). " +
            "Admin only. Full secrets are not returned; they are shown once at creation."
    override val minRole = Role.ADMIN
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj()

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val entries = ctx.app.tokenStore.listTokens()
        return ToolCallResult.ok(jObj {
            add("tokens", jArr(entries.map { e ->
                jObj {
                    addProperty("subject", e.subject ?: "")
                    addProperty("role", Role.from(e.role).name.lowercase())
                    add("projects", strArr(e.projects))
                    addProperty("tokenPrefix", e.token?.take(10) ?: "")
                }
            }))
            addProperty("count", entries.size)
            addProperty("note", "the primary admin token is separate and managed in Settings | Tools | IDE Control")
        })
    }
}
