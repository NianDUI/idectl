package com.niandui.idectl

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
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
import com.niandui.idectl.settings.BridgeSettings
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
class IdeaBridgeService(val scope: CoroutineScope) : Disposable {

    val settings: BridgeSettings get() = BridgeSettings.getInstance()
    val audit: AuditService get() = service()

    val tokenStore = TokenStore(settings)
    val sessions = SessionManager()
    val authGate = AuthGate(tokenStore)
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

    private fun updateInstances() {
        val ide = ApplicationInfo.getInstance().fullVersion
        val projects = ProjectResolver.openProjects().mapNotNull { it.basePath }
        instances.update(server.port, ide, projects, tokenStore.currentToken()?.take(6))
    }

    private fun notifyReady(token: String) {
        val cmd = "claude mcp add --transport http idectl " +
            "http://127.0.0.1:${server.port}/mcp --header \"Authorization: Bearer $token\""
        NotificationGroupManager.getInstance()
            .getNotificationGroup("IDE Control")
            .createNotification(
                "IDE Control 服务器已启动",
                "已监听端口 ${server.port}。连接命令：\n$cmd",
                NotificationType.INFORMATION,
            )
            .notify(null)
    }

    override fun dispose() {
        runCatching { server.stop() }
        runCatching { instances.unregister() }
    }

    companion object {
        fun getInstance(): IdeaBridgeService = service()
    }
}
