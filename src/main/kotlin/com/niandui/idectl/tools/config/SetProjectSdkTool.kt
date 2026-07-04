package com.niandui.idectl.tools.config

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.pom.java.LanguageLevel
import com.niandui.idectl.session.Role
import com.niandui.idectl.tools.ErrorCodes
import com.niandui.idectl.tools.Schema
import com.niandui.idectl.tools.Tool
import com.niandui.idectl.tools.ToolCallResult
import com.niandui.idectl.tools.ToolContext
import com.niandui.idectl.tools.ToolException
import com.niandui.idectl.transport.jObj
import com.niandui.idectl.transport.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `set_project_sdk` — set the project JDK and/or Java language level. The JDK must already be
 * registered in the IDE (its name); an unknown name returns the list of available JDKs.
 */
class SetProjectSdkTool : Tool {
    override val name = "set_project_sdk"
    override val description =
        "Set the project SDK (by registered JDK name) and/or Java language level (e.g. \"17\", \"21\"). " +
            "Pass either or both. An unknown SDK name returns the available JDK names."
    override val minRole = Role.OPERATOR
    override val readOnly = false
    override val destructive = false
    override val inputSchema = Schema.obj(
        "sdk" to Schema.string("Registered JDK name to set as the project SDK."),
        "language_level" to Schema.string("Java language level, e.g. \"1.8\", \"17\", \"21\"."),
        "project" to Schema.string("Project path or name (optional if bound)."),
    )

    override suspend fun execute(ctx: ToolContext): ToolCallResult {
        val project = ctx.project!!
        val sdkName = ctx.args.str("sdk")
        val levelStr = ctx.args.str("language_level")
        if (sdkName == null && levelStr == null) {
            throw ToolException(ErrorCodes.INVALID_ARGUMENT, "pass 'sdk' and/or 'language_level'")
        }

        val jdk = sdkName?.let {
            ProjectJdkTable.getInstance().findJdk(it) ?: run {
                val names = ProjectJdkTable.getInstance().allJdks.map { j -> j.name }
                throw ToolException(
                    ErrorCodes.NOT_FOUND, "no registered JDK named '$it'",
                    "available JDKs: ${names.joinToString(", ").ifEmpty { "(none registered)" }}",
                )
            }
        }
        val level = levelStr?.let {
            LanguageLevel.parse(it)
                ?: throw ToolException(ErrorCodes.INVALID_ARGUMENT, "unrecognized language level '$it'", "try \"17\", \"21\", \"1.8\"")
        }

        withContext(Dispatchers.EDT) {
            runWriteAction {
                if (jdk != null) ProjectRootManager.getInstance(project).projectSdk = jdk
                if (level != null) LanguageLevelProjectExtension.getInstance(project).languageLevel = level
            }
        }

        val rootManager = ProjectRootManager.getInstance(project)
        val ext = LanguageLevelProjectExtension.getInstance(project)
        return ToolCallResult.ok(jObj {
            addProperty("projectSdk", rootManager.projectSdkName ?: "")
            addProperty("languageLevel", ext.languageLevel.name)
        })
    }
}
