package com.niandui.idectl

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.niandui.idectl.core.audit.AuditService
import com.niandui.idectl.discovery.InstancesRegistry
import com.niandui.idectl.gate.ProjectResolver
import com.niandui.idectl.gate.ToolGate
import com.niandui.idectl.session.SessionManager
import com.niandui.idectl.session.TokenStore
import com.niandui.idectl.settings.IdectlSettings
import com.niandui.idectl.tools.ToolRegistry
import com.niandui.idectl.tools.admin.CreateTokenTool
import com.niandui.idectl.tools.admin.ListTokensTool
import com.niandui.idectl.tools.admin.RevokeTokenTool
import com.niandui.idectl.tools.build.BuildTool
import com.niandui.idectl.tools.build.ReloadClassesTool
import com.niandui.idectl.tools.config.CreateRunConfigurationTool
import com.niandui.idectl.tools.config.DeleteRunConfigurationTool
import com.niandui.idectl.tools.config.ReimportProjectTool
import com.niandui.idectl.tools.config.SetProjectSdkTool
import com.niandui.idectl.tools.config.UpdateRunConfigurationTool
import com.niandui.idectl.tools.console.ConsoleReadTool
import com.niandui.idectl.tools.console.ConsoleSearchTool
import com.niandui.idectl.tools.debug.DebugControlTool
import com.niandui.idectl.tools.debug.EvaluateTool
import com.niandui.idectl.tools.debug.GetStackTool
import com.niandui.idectl.tools.debug.GetVariablesTool
import com.niandui.idectl.tools.debug.ListBreakpointsTool
import com.niandui.idectl.tools.debug.RemoveBreakpointTool
import com.niandui.idectl.tools.debug.SetBreakpointTool
import com.niandui.idectl.tools.discovery.BindProjectTool
import com.niandui.idectl.tools.discovery.GetIdeStateTool
import com.niandui.idectl.tools.exec.ListRunConfigurationsTool
import com.niandui.idectl.tools.exec.ListSessionsTool
import com.niandui.idectl.tools.project.CloseProjectTool
import com.niandui.idectl.tools.project.ListRecentProjectsTool
import com.niandui.idectl.tools.project.OpenProjectTool
import com.niandui.idectl.tools.project.RefreshVfsTool
import com.niandui.idectl.tools.exec.RestartSessionTool
import com.niandui.idectl.tools.exec.RunConfigurationTool
import com.niandui.idectl.tools.exec.RunMainTool
import com.niandui.idectl.tools.exec.StopSessionTool
import com.niandui.idectl.tools.test.GetTestResultsTool
import com.niandui.idectl.tools.test.RunTestTool
import com.niandui.idectl.transport.AuthGate
import com.niandui.idectl.transport.McpServer
import com.niandui.idectl.transport.McpTransportAdapter
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The application-level composition root (one per IDE). Owns the Ktor server, session manager,
 * tool registry and gate, and the instance-discovery registry. Started lazily on first project open.
 */
@Service(Service.Level.APP)
class IdectlService(val scope: CoroutineScope) : Disposable {

    val settings: IdectlSettings get() = IdectlSettings.getInstance()
    val audit: AuditService get() = service()

    val tokenStore = TokenStore(settings)
    val sessions = SessionManager()
    val authGate = AuthGate(tokenStore) { settings.allowLan }
    val registry = ToolRegistry()
    val gate = ToolGate(this)
    val adapter = McpTransportAdapter(this)
    val server = McpServer(this)

    private val instances = InstancesRegistry()
    private val started = AtomicBoolean(false)

    init {
        registry.register(
            // M0 discovery
            GetIdeStateTool(),
            BindProjectTool(),
            // Project lifecycle: open (recent or new), list recent, close, sync VFS
            OpenProjectTool(),
            ListRecentProjectsTool(),
            CloseProjectTool(),
            RefreshVfsTool(),
            // Project/build config: reimport build model, run-config CRUD, project SDK/language level
            ReimportProjectTool(),
            CreateRunConfigurationTool(),
            UpdateRunConfigurationTool(),
            DeleteRunConfigurationTool(),
            SetProjectSdkTool(),
            // M1 execution (core①)
            ListRunConfigurationsTool(),
            RunConfigurationTool(),
            ListSessionsTool(),
            StopSessionTool(),
            RestartSessionTool(),
            // M1 console (core②)
            ConsoleReadTool(),
            ConsoleSearchTool(),
            // M2 build + hot reload (core③) + test results
            BuildTool(),
            ReloadClassesTool(),
            GetTestResultsTool(),
            RunTestTool(),
            RunMainTool(),
            // M4 debugger: breakpoints + flow control + inspection
            SetBreakpointTool(),
            RemoveBreakpointTool(),
            ListBreakpointsTool(),
            DebugControlTool(),
            GetStackTool(),
            GetVariablesTool(),
            EvaluateTool(),
            // M3 governance: scoped multi-token management (admin only)
            CreateTokenTool(),
            ListTokensTool(),
            RevokeTokenTool(),
        )
    }

    /** Idempotent: start the server once, on first project open. */
    fun ensureStarted() {
        if (!settings.enabled) return
        if (!started.compareAndSet(false, true)) return
        try {
            val token = tokenStore.ensureToken()
            server.start(settings.portBase)
            updateInstances()
            notifyReady(token)
        } catch (t: Throwable) {
            started.set(false)
            thisLogger().error("failed to start idectl server", t)
        }
    }

    fun onProjectOpened(@Suppress("UNUSED_PARAMETER") project: Project) {
        ensureStarted()
        if (started.get()) updateInstances()
    }

    /**
     * Re-bind the server so a changed `allowLan` / `portBase` / `enabled` takes effect WITHOUT an IDE
     * restart. The bind host is decided in [McpServer.start], so we stop the old socket and start a
     * fresh one against the current settings. Sessions live in [sessions] (independent of the socket),
     * so already-connected agents keep their session and simply reconnect on their next POST.
     *
     * Called from the settings page after [IdectlSettings] is written. Runs the stop/start off the EDT
     * because [McpServer.stop] blocks on Ktor's graceful-shutdown window.
     */
    fun restartServer() {
        // Disabled now → tear the running server down and stop advertising it.
        if (!settings.enabled) {
            if (started.getAndSet(false)) {
                runCatching { server.stop() }
                runCatching { instances.unregister() }
            }
            return
        }
        // Never started yet (e.g. no project opened) → the normal first-start path reads new settings.
        if (!started.get()) {
            ensureStarted()
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { server.stop() }
            try {
                val token = tokenStore.ensureToken()
                server.start(settings.portBase)
                updateInstances()
                notifyReady(token)
            } catch (t: Throwable) {
                started.set(false)
                thisLogger().error("failed to rebind idectl server", t)
            }
        }
    }

    private fun updateInstances() {
        val ide = ApplicationInfo.getInstance().fullVersion
        val projects = ProjectResolver.openProjects().mapNotNull { it.basePath }
        instances.update(server.port, ide, projects, tokenStore.currentToken()?.take(6))
    }

    private fun notifyReady(token: String) {
        val cmd = "claude mcp add --transport http idectl " +
            "http://127.0.0.1:${server.port}/mcp --header \"Authorization: Bearer $token\""
        val body = buildString {
            append("已监听端口 ${server.port}。连接命令：\n$cmd")
            if (settings.allowLan) {
                append("\n\n已开启局域网访问（绑定 0.0.0.0）。局域网内其他机器可用：")
                val lan = lanUrls()
                if (lan.isEmpty()) append("\nhttp://<本机局域网IP>:${server.port}/mcp")
                else lan.forEach { append("\n$it") }
            }
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("IDE Control")
            .createNotification("IDE Control 服务器已启动", body, NotificationType.INFORMATION)
            .notify(null)
    }

    /** Best-effort list of this machine's private-range IPv4 `/mcp` URLs, for LAN peers to connect. */
    private fun lanUrls(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { "http://${it.hostAddress}:${server.port}/mcp" }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    override fun dispose() {
        runCatching { server.stop() }
        runCatching { instances.unregister() }
    }

    companion object {
        fun getInstance(): IdectlService = service()
    }
}
