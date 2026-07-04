package com.niandui.idectl.settings

import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.niandui.idectl.IdeaBridgeService
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolGroups
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The "工具能力与策略" panel (settings). A grouped [CheckboxTree] on the left is the owner's kill
 * switch — check = the tool is callable, uncheck (per-tool or per-group) = unavailable to every agent.
 * The right pane shows the selected tool's info and its editable policy: human approval and (for
 * waiting tools) a timeout override. Edits are staged in [working] and only written back on [apply]
 * if the user actually changed something, so an untouched dialog never rewrites settings.
 */
class ToolPolicyPanel {

    /** Staged, per-tool policy while the dialog is open. */
    private class Working(var disabled: Boolean, var requireApproval: Boolean, var timeoutSec: Int)

    /** Group tree-node payload; [toString] is what the renderer shows. */
    private class GroupItem(val id: String, val count: Int) {
        override fun toString() = ToolGroups.displayName(id)
    }

    private val tools: List<Tool> =
        runCatching { IdeaBridgeService.getInstance().registry.all().toList() }.getOrDefault(emptyList())
    private val working = LinkedHashMap<String, Working>()
    private val toolNodes = LinkedHashMap<String, CheckedTreeNode>()
    private val groupNodes = mutableListOf<Pair<CheckedTreeNode, List<String>>>()

    private var dirty = false
    private var suppress = false // guards listeners while we set widget/tree state programmatically
    private var selected: Tool? = null

    private val root = CheckedTreeNode("root")

    // --- right pane widgets ---
    private val infoLabel = JBLabel().apply { verticalAlignment = SwingConstants.TOP }
    private val approvalCheck = JBCheckBox("调用前需人工批准")
    private val timeoutLabel = JBLabel("超时覆盖(秒，0=默认)：")
    private val timeoutField = JBTextField(6)
    private val resetToolButton = JButton("重置此工具")

    private val renderer = object : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree, value: Any, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = value as? CheckedTreeNode ?: return
            when (val obj = node.userObject) {
                is GroupItem -> textRenderer.append("$obj  (${obj.count})", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                is Tool -> {
                    textRenderer.append(obj.name)
                    textRenderer.append("  ${obj.minRole.name.lowercase()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    when {
                        obj.destructive -> textRenderer.append("  ⚠破坏", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        obj.readOnly -> textRenderer.append("  只读", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
    }

    private val tree = CheckboxTree(renderer, root).apply {
        isRootVisible = false
        showsRootHandles = true
        addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (suppress) return
                syncFromTree()
                dirty = true
            }
        })
        addTreeSelectionListener {
            selected = (lastSelectedPathComponent as? CheckedTreeNode)?.userObject as? Tool
            refreshDetail()
        }
    }

    init {
        ToolGroups.grouped(tools).forEach { (gid, gtools) ->
            val gnode = CheckedTreeNode(GroupItem(gid, gtools.size))
            val names = mutableListOf<String>()
            gtools.forEach { t ->
                working[t.name] = Working(false, false, 0)
                val tnode = CheckedTreeNode(t)
                gnode.add(tnode)
                toolNodes[t.name] = tnode
                names += t.name
            }
            root.add(gnode)
            groupNodes += gnode to names
        }
        wireDetailListeners()
    }

    fun component(): JComponent {
        TreeUtil.expandAll(tree)
        val splitter = OnePixelSplitter(false, 0.52f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree)
            secondComponent = ScrollPaneFactory.createScrollPane(detailPane())
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JButton("全部启用").apply { addActionListener { bulk { it.disabled = false } } })
            add(JButton("仅保留只读").apply { addActionListener { bulkReadOnlyOnly() } })
            add(JButton("恢复默认").apply { addActionListener { bulkResetAll() } })
        }
        refreshDetail()
        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            preferredSize = Dimension(700, 340)
        }
    }

    private fun detailPane(): JComponent {
        val timeoutRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(timeoutLabel)
            add(timeoutField)
        }
        val form = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addSeparator()
            .addComponent(JBLabel("策略"))
            .addComponent(approvalCheck)
            .addComponent(timeoutRow)
            .addComponent(resetToolButton)
            .panel
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(form, BorderLayout.NORTH)
        }
    }

    private fun wireDetailListeners() {
        approvalCheck.addItemListener {
            if (suppress) return@addItemListener
            selected?.let { working.getValue(it.name).requireApproval = approvalCheck.isSelected; dirty = true }
        }
        timeoutField.document.addDocumentListener(object : DocumentListener {
            private fun changed() {
                if (suppress) return
                val t = selected ?: return
                working.getValue(t.name).timeoutSec = timeoutField.text.trim().toIntOrNull()?.coerceIn(0, 3600) ?: 0
                dirty = true
            }
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        resetToolButton.addActionListener {
            val t = selected ?: return@addActionListener
            working[t.name] = Working(false, false, 0)
            dirty = true
            syncTreeFromWorking()
            refreshDetail()
        }
    }

    private fun refreshDetail() {
        suppress = true
        val t = selected
        if (t == null) {
            infoLabel.text = "<html><body style='width:320px'>在左侧选择一个工具查看详情并配置策略。<br>" +
                "勾选=允许 Agent 调用；取消勾选(逐个或整组)=对所有 Agent 不可用。</body></html>"
            approvalCheck.isSelected = false
            approvalCheck.isEnabled = false
            timeoutField.text = ""
            timeoutField.isEnabled = false
            timeoutLabel.isEnabled = false
            resetToolButton.isEnabled = false
        } else {
            val w = working.getValue(t.name)
            infoLabel.text = infoHtml(t)
            approvalCheck.isEnabled = true
            approvalCheck.isSelected = w.requireApproval
            val supports = ToolGroups.supportsTimeout(t.name)
            timeoutField.isEnabled = supports
            timeoutLabel.isEnabled = supports
            timeoutField.text = if (supports) w.timeoutSec.toString() else "—"
            resetToolButton.isEnabled = true
        }
        suppress = false
    }

    private fun infoHtml(t: Tool): String {
        val flags = buildList {
            if (t.destructive) add("破坏性")
            if (t.readOnly) add("只读")
            if (!t.requiresProject) add("无需项目")
        }.joinToString(" · ").ifEmpty { "—" }
        return "<html><body style='width:330px'>" +
            "<b>${esc(t.name)}</b><br>" +
            "分组：${esc(ToolGroups.displayName(ToolGroups.groupId(t)))}　角色：${t.minRole.name.lowercase()}<br>" +
            "标志：$flags<br><br>" +
            "${esc(t.description)}<br><br>" +
            "参数：${paramList(t)}" +
            "</body></html>"
    }

    private fun paramList(t: Tool): String {
        val props = t.inputSchema.getAsJsonObject("properties") ?: return "（无）"
        val required = t.inputSchema.getAsJsonArray("required")?.mapNotNull { it.asString }?.toSet() ?: emptySet()
        if (props.size() == 0) return "（无）"
        return props.keySet().joinToString(", ") { if (it in required) "<b>$it*</b>" else it }
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // --- bulk actions ---

    private fun bulk(edit: (Working) -> Unit) {
        working.values.forEach(edit)
        dirty = true
        syncTreeFromWorking()
        refreshDetail()
    }

    private fun bulkReadOnlyOnly() {
        tools.forEach { working.getValue(it.name).disabled = !it.readOnly }
        dirty = true
        syncTreeFromWorking()
        refreshDetail()
    }

    private fun bulkResetAll() {
        tools.forEach { working[it.name] = Working(false, false, 0) }
        dirty = true
        syncTreeFromWorking()
        refreshDetail()
    }

    // --- tree <-> model sync ---

    private fun syncFromTree() {
        toolNodes.forEach { (name, node) -> working.getValue(name).disabled = !node.isChecked }
    }

    private fun syncTreeFromWorking() {
        suppress = true
        toolNodes.forEach { (name, node) -> node.isChecked = !working.getValue(name).disabled }
        groupNodes.forEach { (g, names) -> g.isChecked = names.all { !working.getValue(it).disabled } }
        suppress = false
        tree.repaint()
    }

    // --- Configurable contract ---

    fun isModified(): Boolean = dirty

    fun apply(settings: BridgeSettings) {
        if (!dirty) return
        settings.toolPolicies.clear()
        working.forEach { (name, w) ->
            if (w.disabled || w.requireApproval || w.timeoutSec > 0) {
                settings.toolPolicies.add(ToolPolicy().apply {
                    this.name = name
                    disabled = w.disabled
                    requireApproval = w.requireApproval
                    timeoutSec = w.timeoutSec
                })
            }
        }
        dirty = false
    }

    fun reset(settings: BridgeSettings) {
        tools.forEach {
            working[it.name] = Working(
                disabled = settings.isToolDisabled(it.name),
                requireApproval = settings.toolRequiresApproval(it.name),
                timeoutSec = settings.toolTimeoutSecOverride(it.name),
            )
        }
        syncTreeFromWorking()
        dirty = false
        refreshDetail()
    }
}
