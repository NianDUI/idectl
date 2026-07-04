package com.niandui.idectl.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.niandui.idectl.IdeaBridgeService
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One-click "wire this bridge into Claude Code / Codex MCP" panel. Buttons act **immediately** (they
 * shell out / write config files right away — independent of the settings dialog's OK/Apply), using the
 * current running port + primary access token. The outcome + configured-state are shown inline in the
 * status line — no result dialog.
 *
 * Threading note: the settings dialog is a **modal** dialog, so background completions must be posted
 * with [ModalityState.any] or they'd be deferred until the dialog closes (which manifested as a stuck
 * "处理中…" and a success balloon that only popped after closing Settings).
 */
class AgentSetupPanel {

    private val app = ApplicationManager.getApplication()
    private val status = JBLabel(" ")

    fun component(): JComponent {
        val claudeLabel = JBLabel("Claude Code：")
        val claudeRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(claudeLabel)
            add(JButton("一键配置").apply { addActionListener { run("配置到 Claude Code") { p, t -> AgentConfigurator.configureClaude(p, t) } } })
            add(JButton("取消配置").apply { addActionListener { run("从 Claude Code 移除") { _, _ -> AgentConfigurator.unconfigureClaude() } } })
        }
        val codexRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel("Codex：").apply { preferredSize = claudeLabel.preferredSize })
            add(JButton("一键配置").apply { addActionListener { run("配置到 Codex") { p, t -> AgentConfigurator.configureCodex(p, t) } } })
            add(JButton("取消配置").apply { addActionListener { run("从 Codex 移除") { _, _ -> AgentConfigurator.unconfigureCodex() } } })
        }
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(status)
            add(JButton("检测状态").apply { addActionListener { refreshStatus() } })
        }
        refreshStatus()
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(left(claudeRow)); add(left(codexRow)); add(left(statusRow))
        }
    }

    private fun left(c: JComponent): JComponent = c.apply { alignmentX = JComponent.LEFT_ALIGNMENT }

    /** Live port + admin access token, read on the EDT at click time. */
    private fun portAndToken(): Pair<Int, String> {
        val svc = IdeaBridgeService.getInstance()
        val port = svc.server.port.takeIf { it > 0 } ?: svc.settings.portBase
        val token = svc.settings.token ?: svc.tokenStore.ensureToken()
        return port to token
    }

    private fun run(label: String, action: (Int, String) -> AgentConfigurator.Result) {
        val (port, token) = portAndToken()
        status.text = "$label：处理中…"
        app.executeOnPooledThread {
            val r = runCatching { action(port, token) }
                .getOrElse { AgentConfigurator.Result(false, it.message ?: it.javaClass.simpleName) }
            val head = if (r.ok) "✓ ${firstLine(r.message)}" else "✗ $label 失败：${firstLine(r.message)}"
            val line = "$head　｜　${detect()}"
            app.invokeLater({ status.text = line }, ModalityState.any())
        }
    }

    private fun refreshStatus() {
        status.text = "检测中…"
        app.executeOnPooledThread {
            val line = "状态：${detect()}"
            app.invokeLater({ status.text = line }, ModalityState.any())
        }
    }

    /** Compute the "Claude=…  Codex=…" fragment off the EDT (reads config files / runs claude). */
    private fun detect(): String {
        val claude = runCatching { AgentConfigurator.claudeConfigured() }.getOrDefault(false)
        val codex = runCatching { AgentConfigurator.codexConfigured() }.getOrDefault(false)
        return "Claude Code = ${mark(claude)}　Codex = ${mark(codex)}"
    }

    private fun mark(on: Boolean): String = if (on) "已配置 ✓" else "未配置"

    private fun firstLine(s: String): String {
        val f = s.substringBefore('\n').trim()
        return if (f.length <= 110) f else f.take(110) + "…"
    }
}
