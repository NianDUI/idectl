package com.niandui.idectl.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.util.EnvironmentUtil
import java.io.File

/**
 * One-click wiring of this bridge into the local coding agents' MCP config.
 *
 * - **Claude Code** goes through the official `claude mcp add/remove` CLI at **user (global) scope**, so
 *   the surgical edits to `~/.claude.json` are done by Claude itself (that file is large/stateful — never
 *   hand-edit it). We resolve the executable from the login-shell PATH (nvm etc.) and pass it that env.
 * - **Codex** has no working CLI here, and its `config.toml` natively supports streamable-HTTP MCP
 *   (`url` + `http_headers`), so we write a **marker-delimited managed block** into `~/.codex/config.toml`
 *   (idempotent, precisely removable), backing the file up first.
 *
 * Both use the bridge's primary **access token** (admin) and the given port. Re-run after regenerating
 * the token or changing the port.
 */
object AgentConfigurator {

    const val CLAUDE_NAME = "idectl"
    const val CODEX_TABLE = "idectl" // TOML bare key (no quoting needed)
    private const val MARK_BEGIN = "# >>> idectl (managed by IDE Control) >>>"
    private const val MARK_END = "# <<< idectl (managed by IDE Control) <<<"

    data class Result(val ok: Boolean, val message: String)

    fun url(port: Int): String = "http://127.0.0.1:$port/mcp"

    // ---------- Claude Code (via CLI, user scope) ----------

    fun configureClaude(port: Int, token: String): Result {
        // Idempotent: drop any existing entry first, then add fresh (updates url/token).
        runClaude("mcp", "remove", CLAUDE_NAME, "-s", "user")
        val r = runClaude(
            "mcp", "add", "--transport", "http", CLAUDE_NAME, url(port),
            "--header", "Authorization: Bearer $token", "-s", "user",
        )
        return if (r.ok) Result(true, "已配置到 Claude Code（user 全局作用域）\n${url(port)}") else r
    }

    fun unconfigureClaude(): Result {
        val r = runClaude("mcp", "remove", CLAUDE_NAME, "-s", "user")
        return if (r.ok) Result(true, "已从 Claude Code 移除 '$CLAUDE_NAME'") else r
    }

    /**
     * True iff our entry exists in Claude's **user (global)** scope — read straight from ~/.claude.json,
     * not via `claude mcp get` (which also matches project/local entries and would mislead the status).
     */
    fun claudeConfigured(): Boolean = runCatching {
        val f = File(System.getProperty("user.home"), ".claude.json")
        if (!f.exists()) return false
        val root = f.reader().use { com.google.gson.JsonParser.parseReader(it) }.asJsonObject
        root.getAsJsonObject("mcpServers")?.has(CLAUDE_NAME) == true
    }.getOrDefault(false)

    private fun runClaude(vararg args: String): Result {
        val exe = findExecutable("claude")
        val cmd = GeneralCommandLine(listOf(exe) + args).withEnvironment(loginEnv())
        return try {
            val out = ExecUtil.execAndGetOutput(cmd, 30_000)
            when {
                out.isTimeout -> Result(false, "claude 执行超时")
                out.exitCode == 0 -> Result(true, out.stdout.trim().ifBlank { "OK" })
                else -> Result(false, (out.stderr.ifBlank { out.stdout }).trim().ifBlank { "claude 退出码 ${out.exitCode}" })
            }
        } catch (t: Throwable) {
            Result(false, "无法执行 claude：${t.message ?: t.javaClass.simpleName}。请确认已安装 Claude Code CLI。")
        }
    }

    // ---------- Codex (via ~/.codex/config.toml managed block) ----------

    fun configureCodex(port: Int, token: String): Result {
        val f = codexConfigFile()
        return try {
            f.parentFile?.mkdirs()
            val original = if (f.exists()) f.readText() else ""
            backup(f, original)
            val kept = stripBridge(original).trimEnd('\n', ' ', '\t')
            val body = if (kept.isEmpty()) block(port, token) else kept + "\n\n" + block(port, token)
            f.writeText(body + "\n")
            Result(true, "已写入 ${f.path}\n[mcp_servers.$CODEX_TABLE] → ${url(port)}")
        } catch (t: Throwable) {
            Result(false, "写入 config.toml 失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun unconfigureCodex(): Result {
        val f = codexConfigFile()
        if (!f.exists()) return Result(true, "~/.codex/config.toml 不存在，无需移除")
        return try {
            val original = f.readText()
            backup(f, original)
            val kept = stripBridge(original).trimEnd('\n', ' ', '\t')
            f.writeText(if (kept.isEmpty()) "" else kept + "\n")
            Result(true, "已从 ${f.path} 移除 [mcp_servers.$CODEX_TABLE]")
        } catch (t: Throwable) {
            Result(false, "移除失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun codexConfigured(): Boolean =
        codexConfigFile().takeIf { it.exists() }?.readText()?.contains("[mcp_servers.$CODEX_TABLE]") == true

    private fun codexConfigFile(): File = File(System.getProperty("user.home"), ".codex/config.toml")

    private fun backup(f: File, content: String) {
        if (f.exists()) runCatching { File(f.parentFile, "config.toml.ibbak").writeText(content) }
    }

    private fun block(port: Int, token: String): String = buildString {
        appendLine(MARK_BEGIN)
        appendLine("[mcp_servers.$CODEX_TABLE]")
        appendLine("url = \"${url(port)}\"")
        appendLine()
        appendLine("[mcp_servers.$CODEX_TABLE.http_headers]")
        appendLine("Authorization = \"Bearer $token\"")
        append(MARK_END)
    }

    /**
     * Remove our managed marker block AND, defensively, any raw `[mcp_servers.idectl*]` tables,
     * so re-configuring never duplicates and un-configuring is clean even if the markers were disturbed.
     */
    private fun stripBridge(text: String): String {
        if (text.isEmpty()) return ""
        val out = ArrayList<String>()
        var inManaged = false
        var inRawTable = false
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t == MARK_BEGIN) { inManaged = true; continue }
            if (t == MARK_END) { inManaged = false; continue }
            if (inManaged) continue
            if (t.startsWith("[")) {
                inRawTable = t == "[mcp_servers.$CODEX_TABLE]" || t.startsWith("[mcp_servers.$CODEX_TABLE.")
            }
            if (inRawTable) continue
            out.add(line)
        }
        return out.joinToString("\n")
    }

    // ---------- shared: resolve executable against the login-shell PATH ----------

    private fun loginEnv(): Map<String, String> =
        runCatching { EnvironmentUtil.getEnvironmentMap() }.getOrElse { System.getenv() }

    private fun findExecutable(name: String): String {
        val path = loginEnv()["PATH"] ?: System.getenv("PATH") ?: return name
        for (dir in path.split(File.pathSeparatorChar)) {
            val f = File(dir, name)
            if (f.canExecute()) return f.absolutePath
        }
        return name // fall back to bare name; ProcessBuilder may still find it
    }
}
