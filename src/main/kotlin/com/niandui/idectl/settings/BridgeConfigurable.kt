package com.niandui.idectl.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.niandui.idectl.IdeaBridgeService
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** 设置 | 工具 | IDE Control —— 端口、启用开关、访问令牌、控制台缓冲。 */
class BridgeConfigurable : Configurable {

    private val settings get() = BridgeSettings.getInstance()

    private val enabledCheck = JBCheckBox("启用 IDE Control 服务器")
    private val portField = JBTextField(8)
    private val tokenField = JBTextField(36).apply { isEditable = false }
    private val regenerateButton = JButton("重新生成")

    // 控制台缓冲（core②）
    private val memoryLinesField = JBTextField(8)
    private val memoryMbField = JBTextField(8)
    private val archiveEnabledCheck = JBCheckBox("启用磁盘归档（超出内存后落盘，老日志仍可检索）")
    private val archiveMbField = JBTextField(8)
    // 一键接入 Agent（把本桥写进 Claude Code / Codex 的 MCP 配置）
    private val agentSetup = AgentSetupPanel()
    // 作用域令牌（M3 治理）
    private val tokenTable = TokenTablePanel()
    // 工具能力与策略（启用/停用 + 人工批准 + 超时覆盖）
    private val toolPolicy = ToolPolicyPanel()
    private val approvalTimeoutField = JBTextField(8)
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "IDE Control"

    /** A wrapping hint label: fixed-width HTML so long text folds instead of overflowing the panel. */
    private fun hint(html: String): JBLabel = JBLabel("<html><body style='width:560px'>$html</body></html>")

    override fun createComponent(): JComponent {
        regenerateButton.addActionListener {
            tokenField.text = IdeaBridgeService.getInstance().tokenStore.regenerate()
        }
        val tokenRow = JPanel(BorderLayout(6, 0)).apply {
            add(tokenField, BorderLayout.CENTER)
            add(regenerateButton, BorderLayout.EAST)
        }
        val form = FormBuilder.createFormBuilder()
            .addComponent(enabledCheck)
            .addLabeledComponent("基础端口：", portField)
            .addLabeledComponent("访问令牌：", tokenRow)
            .addComponent(hint("端口 / 启用状态的修改需重启 IDE 后生效；令牌立即生效。"))
            .addSeparator()
            .addComponent(JBLabel("一键接入 Agent（用当前访问令牌 + 端口写入对方的 MCP 配置，按钮即时生效）"))
            .addComponent(agentSetup.component())
            .addComponent(hint("Claude Code 走官方 <code>claude mcp add</code>（<b>user 全局作用域</b>，写 ~/.claude.json）；" +
                "Codex 直接写 <code>~/.codex/config.toml</code> 的托管块（改前自动备份 config.toml.ibbak）。" +
                "用的是<b>访问令牌</b>；<b>重新生成令牌或改端口后请再点一次「一键配置」</b>刷新。"))
            .addSeparator()
            .addComponent(JBLabel("控制台缓冲"))
            .addLabeledComponent("内存缓冲行数：", memoryLinesField)
            .addLabeledComponent("内存缓冲上限(MB)：", memoryMbField)
            .addComponent(archiveEnabledCheck)
            .addLabeledComponent("磁盘归档上限(MB)：", archiveMbField)
            .addComponent(hint("控制台设置对<b>新启动</b>的会话生效。磁盘归档默认<b>开启</b>：内存环保持精简，" +
                "淘汰的老行落盘(≈0 额外内存)、可被 console_read/console_search 回溯；磁盘有上限、会话结束自动清理。" +
                "若不想占磁盘可关闭。"))
            .addVerticalGap(16)
            .addSeparator()
            .addComponent(JBLabel("作用域令牌（给不同 Agent 不同角色 / 项目范围）"))
            .addComponent(tokenTable.component())
            .addComponent(hint("主令牌（上方“访问令牌”）始终是 <b>admin / 全部项目</b>。这里可再签发受限令牌：" +
                "viewer 只读、operator 可运行/调试、admin 可管理令牌；限定项目后，范围外的项目对该令牌<b>不可见</b>。" +
                "也可让 admin Agent 用 create_token / list_tokens / revoke_token 在运行时签发。"))
            .addVerticalGap(16)
            .addSeparator()
            .addComponent(JBLabel("工具能力与策略（总闸：允许 / 不允许某能力被调用）"))
            .addComponent(toolPolicy.component())
            .addLabeledComponent("人工批准超时(秒)：", approvalTimeoutField)
            .addComponent(hint("左侧勾选=允许 Agent 调用，取消(逐个或整组)=对<b>所有</b> Agent 不可用——独立于令牌角色的总闸。" +
                "右侧可给单个工具开<b>人工批准</b>(调用时 IDE 弹气泡等你批准/拒绝，超时默认拒绝)、或设<b>超时覆盖</b>" +
                "(仅 run/build/test/debug 等等待类工具)。与令牌是两层：这里管<b>能不能用</b>，令牌管<b>谁能用/碰哪些项目</b>。"))
            .panel
        // Anchor the form to the top-left instead of letting it center vertically in the panel.
        val wrapper = JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
        panel = wrapper
        reset()
        return wrapper
    }

    override fun isModified(): Boolean =
        enabledCheck.isSelected != settings.enabled ||
            portField.text.trim() != settings.portBase.toString() ||
            tokenField.text != (settings.token ?: "") ||
            memoryLinesField.text.trim() != settings.consoleMemoryLines.toString() ||
            memoryMbField.text.trim() != settings.consoleMemoryMb.toString() ||
            archiveEnabledCheck.isSelected != settings.consoleArchiveEnabled ||
            archiveMbField.text.trim() != settings.consoleArchiveMb.toString() ||
            approvalTimeoutField.text.trim() != settings.approvalTimeoutSec.toString() ||
            tokenTable.isModified() ||
            toolPolicy.isModified()

    override fun apply() {
        settings.enabled = enabledCheck.isSelected
        portField.text.trim().toIntOrNull()?.let { settings.portBase = it }
        if (tokenField.text.isNotBlank()) settings.token = tokenField.text.trim()
        memoryLinesField.text.trim().toIntOrNull()?.let { settings.consoleMemoryLines = it.coerceAtLeast(100) }
        memoryMbField.text.trim().toIntOrNull()?.let { settings.consoleMemoryMb = it.coerceAtLeast(1) }
        settings.consoleArchiveEnabled = archiveEnabledCheck.isSelected
        archiveMbField.text.trim().toIntOrNull()?.let { settings.consoleArchiveMb = it.coerceAtLeast(1) }
        approvalTimeoutField.text.trim().toIntOrNull()?.let { settings.approvalTimeoutSec = it.coerceIn(5, 3600) }
        tokenTable.apply(settings)
        toolPolicy.apply(settings)
    }

    override fun reset() {
        enabledCheck.isSelected = settings.enabled
        portField.text = settings.portBase.toString()
        tokenField.text = settings.token ?: ""
        memoryLinesField.text = settings.consoleMemoryLines.toString()
        memoryMbField.text = settings.consoleMemoryMb.toString()
        archiveEnabledCheck.isSelected = settings.consoleArchiveEnabled
        archiveMbField.text = settings.consoleArchiveMb.toString()
        approvalTimeoutField.text = settings.approvalTimeoutSec.toString()
        tokenTable.reset(settings)
        toolPolicy.reset(settings)
    }
}
