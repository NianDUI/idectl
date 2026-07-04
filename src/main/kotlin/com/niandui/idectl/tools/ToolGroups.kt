package com.niandui.idectl.tools

/**
 * Functional grouping used by the settings "工具能力与策略" panel. The group id is derived from each
 * tool's package (`tools.debug` → `debug`) so it stays in sync automatically as tools are added; the
 * panel renders one collapsible node per group in [ORDER]. Also declares which tools honour a
 * per-tool timeout override (those whose wait is driven by a `timeout_sec` argument).
 */
object ToolGroups {

    /** Ordered (group id → display name). Unknown ids sort after these, alphabetically. */
    private val ORDER: LinkedHashMap<String, String> = linkedMapOf(
        "discovery" to "发现与绑定",
        "project" to "项目生命周期",
        "config" to "构建与运行配置",
        "exec" to "运行与会话",
        "console" to "控制台",
        "build" to "构建与热重载",
        "test" to "测试",
        "debug" to "调试",
        "admin" to "令牌治理",
    )

    /**
     * Tools whose blocking wait is governed by a `timeout_sec` argument, so a per-tool timeout
     * override is meaningful. The gate injects the override as that argument's default when the
     * caller did not pass one; the panel only offers the field for these tools.
     */
    val TIMEOUT_TOOLS: Set<String> = setOf(
        "run_configuration", "restart_session", "run_main", "run_test",
        "build", "reload_classes", "debug_control", "evaluate",
    )

    fun groupId(tool: Tool): String = tool.javaClass.packageName.substringAfterLast('.')

    fun displayName(groupId: String): String = ORDER[groupId] ?: groupId

    fun supportsTimeout(name: String): Boolean = name in TIMEOUT_TOOLS

    /** Group tools in the canonical [ORDER]; groups with no tools are omitted, unknowns sort last. */
    fun grouped(tools: Collection<Tool>): List<Pair<String, List<Tool>>> {
        val byGroup = tools.groupBy { groupId(it) }
        val known = ORDER.keys.filter { byGroup.containsKey(it) }
        val extras = byGroup.keys.filter { it !in ORDER.keys }.sorted()
        return (known + extras).map { it to byGroup.getValue(it) }
    }
}
