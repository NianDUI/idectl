package com.niandui.idectl.gate

import com.google.gson.JsonObject
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.niandui.idectl.tools.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Human-in-the-loop approval for tools flagged `requireApproval` in the policy panel. The call
 * suspends and a balloon with 批准 / 拒绝 actions is shown in the IDE; it proceeds only if the owner
 * approves before the timeout. Timeout or 拒绝 → denied (fail-closed). Note: with no human at the
 * IDE the call blocks until the timeout, then denies — that is the intended safety behaviour.
 */
object ApprovalGate {

    suspend fun request(project: Project?, tool: Tool, args: JsonObject, timeoutMs: Long): Boolean {
        val decision = CompletableDeferred<Boolean>()
        val body = buildString {
            if (tool.destructive) append("⚠ 破坏性操作。")
            append("角色 ${tool.minRole.name.lowercase()}。")
            summarize(args)?.let { append("\n参数：$it") }
            append("\n未在 ${timeoutMs / 1000}s 内批准将自动拒绝。")
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("IDE Control")
            .createNotification("Agent 请求调用 ${tool.name}", body, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("批准") { decision.complete(true) })
            .addAction(NotificationAction.createSimpleExpiring("拒绝") { decision.complete(false) })
        notification.notify(project)
        val approved = withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
        // On timeout the balloon is still live — retract it so a stale "批准" can't fire after the fact.
        if (!decision.isCompleted) notification.expire()
        return approved
    }

    /** Compact one-line args preview for the balloon (secrets aren't expected in tool args). */
    private fun summarize(args: JsonObject): String? {
        if (args.size() == 0) return null
        val s = args.toString()
        return if (s.length <= 200) s else s.substring(0, 200) + "…"
    }
}
