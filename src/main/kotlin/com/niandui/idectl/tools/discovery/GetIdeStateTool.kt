package com.niandui.idectl.tools.discovery

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.niandui.idectl.IdeaBridge
import com.niandui.idectl.gate.ProjectResolver
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.transport.jArr
import com.niandui.idectl.transport.jObj
import java.io.File

/** `get_ide_state` — the Agent's first call: instance self-proof + project discovery + whoami (03 §2.1). */
class GetIdeStateTool : Tool {
    override val name = "get_ide_state"
    override val description =
        "Identify this IDE instance, list its open projects, and report who you are (role, " +
            "allowed projects, quota). Call this first to confirm you connected to the right IDE."
    override val minRole = Role.VIEWER
    override val readOnly = true
    override val destructive = false
    override val requiresProject = false
    override val inputSchema = Schema.obj(
        "project" to Schema.string("Optional project path or name to also report as the bound project."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val appInfo = ApplicationInfo.getInstance()
        val principal = ctx.principal

        // Only surface projects this token is allowed to touch — out-of-scope projects stay invisible (D8).
        val projectsJson = ProjectResolver.openProjects()
            .filter { principal.mayAccessProject(it.basePath ?: "") }
            .map { describeProject(it) }

        val result = jObj {
            addProperty("ideVersion", appInfo.fullVersion)
            addProperty("ideName", appInfo.fullApplicationName)
            addProperty("pluginVersion", IdeaBridge.pluginVersion())
            add("protocolVersions", JsonArray().apply { IdeaBridge.SUPPORTED_PROTOCOLS.forEach { add(it) } })
            addProperty("port", ctx.app.server.port)
            add("openProjects", jArr(projectsJson))
            add("me", jObj {
                addProperty("subject", principal.subject)
                addProperty("role", principal.role.name.lowercase())
                if (principal.allowedProjects == null) add("allowedProjects", com.google.gson.JsonNull.INSTANCE)
                else add("allowedProjects", JsonArray().apply { principal.allowedProjects.forEach { add(it) } })
                addProperty("boundProject", ctx.session.boundProjectPath ?: "")
            })
            addProperty("serverTimeMs", System.currentTimeMillis())
            add("capabilities", jObj {
                addProperty("build", true)
                addProperty("run", true)
                addProperty("debug", true)
                addProperty("maven", mavenAvailable())
            })
        }
        return ToolCallResult.ok(result)
    }

    private fun describeProject(project: Project): JsonObject = jObj {
        addProperty("name", project.name)
        addProperty("path", project.basePath ?: "")
        addProperty("buildSystem", detectBuildSystem(project.basePath))
        addProperty("mavenized", project.basePath?.let { File(it, "pom.xml").exists() } ?: false)
        addProperty("isTrusted", true) // TODO(M3): wire real TrustedProjects state
    }

    private fun detectBuildSystem(basePath: String?): String {
        if (basePath == null) return "unknown"
        val dir = File(basePath)
        return when {
            File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists() -> "gradle"
            File(dir, "pom.xml").exists() -> "maven"
            else -> "unknown"
        }
    }

    private fun mavenAvailable(): Boolean =
        PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.idea.maven")) != null
}
