package com.niandui.idectl.gate

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/** Resolves a project hint (base path or name) to an open [Project]. Central to routing (D4). */
object ProjectResolver {

    fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed && it.isInitialized }

    fun resolve(hint: String?): Project? {
        if (hint.isNullOrBlank()) return null
        val open = openProjects()
        val norm = normalize(hint)
        return open.firstOrNull { it.name == hint }
            ?: open.firstOrNull { normalize(it.basePath) == norm }
    }

    private fun normalize(path: String?): String? =
        path?.trim()?.removeSuffix("/")?.let { if (it.isEmpty()) "/" else it }
}
