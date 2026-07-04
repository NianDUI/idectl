package com.niandui.idectl.tools

import com.niandui.idectl.session.Role

/** Ordered registry of tools; `tools/list` is filtered by the caller's role. */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(vararg t: Tool) {
        t.forEach { tools[it.name] = it }
    }

    fun get(name: String): Tool? = tools[name]

    fun visibleTo(role: Role): List<Tool> = tools.values.filter { role.satisfies(it.minRole) }

    fun all(): Collection<Tool> = tools.values
}
