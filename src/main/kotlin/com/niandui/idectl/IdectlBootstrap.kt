package com.niandui.idectl

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the MCP server (idempotently) and registers the project on first open. */
class IdectlBootstrap : ProjectActivity {
    override suspend fun execute(project: Project) {
        IdectlService.getInstance().onProjectOpened(project)
    }
}
