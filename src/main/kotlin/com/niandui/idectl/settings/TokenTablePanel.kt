package com.niandui.idectl.settings

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.niandui.idectl.session.TokenStore
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/** One staged scoped-token row in the settings table. */
private data class TokenRow(val token: String, val subject: String, val role: String, val projects: List<String>)

/**
 * The "scoped tokens" table for the settings page (M3). Edits are staged in a working list and only
 * written back on apply — and only if the user actually changed something, so tokens issued at runtime
 * via the admin MCP tools are never clobbered by an untouched settings dialog.
 */
class TokenTablePanel {

    private val rows = mutableListOf<TokenRow>()
    private var dirty = false

    private val model = object : AbstractTableModel() {
        private val columns = arrayOf("用途", "角色", "限定项目", "令牌前缀")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.subject
                1 -> r.role
                2 -> if (r.projects.isEmpty()) "（全部）" else r.projects.joinToString(", ")
                else -> r.token.take(10) + "…"
            }
        }
    }

    private val table = JBTable(model).apply { setShowGrid(false) }

    fun component(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { onAdd() }
            .setRemoveAction { onRemove() }
            .disableUpDownActions()
            .createPanel()
        val copyButton = JButton("复制选中令牌").apply { addActionListener { onCopy() } }
        return JPanel(BorderLayout()).apply {
            add(decorated, BorderLayout.CENTER)
            add(copyButton, BorderLayout.SOUTH)
        }
    }

    private fun onAdd() {
        val dialog = CreateTokenDialog()
        if (dialog.showAndGet()) {
            dialog.result()?.let {
                rows.add(it)
                dirty = true
                model.fireTableDataChanged()
            }
        }
    }

    private fun onRemove() {
        val i = table.selectedRow
        if (i in rows.indices) {
            rows.removeAt(i)
            dirty = true
            model.fireTableDataChanged()
        }
    }

    private fun onCopy() {
        val i = table.selectedRow
        if (i in rows.indices) {
            CopyPasteManager.getInstance().setContents(StringSelection(rows[i].token))
            Messages.showInfoMessage("令牌已复制到剪贴板：${rows[i].subject}", "IDE Control")
        }
    }

    fun reset(settings: IdectlSettings) {
        rows.clear()
        settings.tokens.forEach {
            rows.add(TokenRow(it.token ?: "", it.subject ?: "", it.role ?: "viewer", it.projects.toList()))
        }
        dirty = false
        model.fireTableDataChanged()
    }

    /** Only "modified" if the user touched the table — untouched dialogs must not overwrite runtime tokens. */
    fun isModified(): Boolean = dirty

    fun apply(settings: IdectlSettings) {
        if (!dirty) return
        settings.tokens.clear()
        rows.forEach { r ->
            settings.tokens.add(TokenEntry().apply {
                token = r.token
                subject = r.subject
                role = r.role
                projects = r.projects.toMutableList()
            })
        }
        dirty = false
    }

    /** Modal dialog collecting subject / role / projects for a new token. */
    private class CreateTokenDialog : DialogWrapper(true) {
        private val subjectField = JBTextField(24)
        private val roleCombo = ComboBox(arrayOf("viewer", "operator", "admin"))
        private val projectsField = JBTextField(24)

        init {
            title = "新建作用域令牌"
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("用途标识：", subjectField)
            .addLabeledComponent("角色：", roleCombo)
            .addLabeledComponent("限定项目路径（可空，逗号分隔）：", projectsField)
            .panel

        fun result(): TokenRow? {
            val subject = subjectField.text.trim().ifBlank { return null }
            val role = (roleCombo.selectedItem as? String) ?: "viewer"
            val projects = projectsField.text.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotBlank() }
            return TokenRow(TokenStore.generate(), subject, role, projects)
        }
    }
}
