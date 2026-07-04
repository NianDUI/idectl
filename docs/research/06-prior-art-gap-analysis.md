# 06 竞品与先行者能力差距分析（Prior Art & Gap Analysis）

> Idectl 设计调研 · 2026-07 · 数据来源均为一手资料（JetBrains 官方文档、intellij-community 源码、GitHub 仓库/API、VS Code 官方文档），关键结论均附来源 URL。

---

## 1. 概述

截至 2026 年 7 月，"让外部 AI Agent 通过 MCP 控制 JetBrains IDE" 这一赛道有四类玩家：

1. **JetBrains 官方内建 MCP Server**（IDE 2025.2+ 平台自带，`plugins/mcp-server` 模块，Settings → Tools → MCP Server）——当前事实标准，工具面最广（约 60+ 工具，含调试器、数据库），但在**运行会话生命周期管理、控制台增量读取、Maven 专用工具、多客户端权限**上留有明显空白。
2. **@jetbrains/mcp-proxy（npm）+ JetBrains/mcp-server-plugin（marketplace 26071）**——2025.2 之前的官方过渡方案，**双双停止维护**，已被内建 MCP 取代。
3. **开源第三方插件**——以 hechtcarmel 的两个插件（Debugger MCP Server、IDE Index MCP Server）为代表，聚焦单一领域（调试器 / 索引），无一覆盖"构建+运行+控制台+调试+Maven"的全链路，更无多用户权限模型。
4. **对照生态：VS Code Copilot agent mode**——工具粒度更粗（task/terminal 为中心），但其**逐工具确认（per-tool confirmation）+ 权限分级 + 参数可编辑后放行**的交互设计值得借鉴。

**核心结论**：内建 MCP Server 解决了"单 Agent、单人、拉一次结果"的场景；Idectl 的机会在于 **长生命周期运行/调试会话的流式控制台、Maven 深度集成、多 Agent 多角色并发治理（授权/互斥/审计）、以及对用户手动启动会话的统一跟踪**——这些在所有竞品中均为空白或半空白。

---

## 2. 竞品逐个详解

### 2.1 JetBrains 官方内建 MCP Server（2025.2+）

**载体与开关**
- 平台 bundled 插件 `com.intellij.mcpServer`，源码位于 `intellij-community/plugins/mcp-server`（Apache-2.0，可直接阅读）。
- 默认**关闭**：`McpServerSettings.MyState.enableMcpServer: Boolean by property(false)`；brave mode 同样默认关闭（`enableBraveMode by property(false)`）。
  来源：[McpServerSettings.kt](https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/settings/McpServerSettings.kt)

**传输与端口（源码实证）**

```kotlin
// plugins/mcp-server/src/com/intellij/mcpserver/settings/McpServerSettings.kt
private const val BASE_MCP_PORT: Int = 64342
private const val PORT_STEP: Int = 20
@JvmStatic val DEFAULT_MCP_PORT: Int = BASE_MCP_PORT + getPortOffset()
// IDEA=64342, CLion=64362, DataGrip=64402, GoLand=64422, PhpStorm=64442,
// PyCharm=64462, Rider=64482, RubyMine=64502, RustRover=64522, WebStorm=64542
@JvmStatic val DEFAULT_MCP_PRIVATE_PORT: Int = DEFAULT_MCP_PORT + 100
```

- 支持三种客户端接入形态：**SSE**（`http://127.0.0.1:64342/sse`）、**HTTP Stream（streamable HTTP）**、**Stdio**（IDE 内置一个 JVM-based stdio proxy 供只支持 stdio 的客户端使用）。Settings → Tools → MCP Server 里可为 Claude Code / Claude Desktop / Cursor / VS Code / Codex / Windsurf 一键写入配置。
  来源：[MCP Server | IntelliJ IDEA 官方文档](https://www.jetbrains.com/help/idea/mcp-server.html)、[OpenCode 配置实例（URL=http://127.0.0.1:64342/sse）](https://drfits.com/2026/04/20/intellij-idea-mcp-server-configuration-for-opencode/)
- **认证：无 token、无 OAuth**，仅靠绑定 127.0.0.1 做安全边界（实测配置 `headers: {}`）。社区已有 OAuth2 诉求（YouTrack [LLM-25012](https://youtrack.jetbrains.com/projects/LLM/issues/LLM-25012)），端口按项目配置的诉求也未实现（[IJPL-207839](https://youtrack.jetbrains.com/projects/IJPL/issues/IJPL-207839)）。
- **多项目路由**：一个 IDE 进程一个端口；框架给**每个工具自动注入 `projectPath` 参数**（工具作者不声明，`CoroutineContext.project` 解析），客户端靠传 `projectPath` 区分同一 IDE 下的多个项目。多个 IDE 进程 = 多个端口，靠客户端自己记端口，**没有实例发现机制**。
  来源：[plugins/mcp-server/README.md §4.5、§7](https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/README.md)

**工具清单（按 toolset 分组，2026.1 文档 + 源码核对）**

来源：[官方文档工具全表](https://www.jetbrains.com/help/idea/mcp-server.html)；源码目录 `plugins/mcp-server/src/com/intellij/mcpserver/toolsets/general/`（AnalysisToolset / CodeInsightToolset / DiagnosticsToolset / ExecutionToolset / FileToolset / FormattingToolset / PatchToolset / ReadToolset / RefactoringToolset / SearchToolset / UniversalToolset）。

| 分组 | 工具名（参数） |
|---|---|
| Analysis | `build_project(rebuild, filesToRebuild, timeout, projectPath)` — 构建项目/指定文件，**等待完成并返回构建错误**；`get_file_problems(filePath, errorsOnly, timeout)`；`get_project_dependencies`；`get_project_modules` |
| Code Insight | `get_symbol_info(filePath, line, column)`（Quick Documentation 同源） |
| Execution | `get_run_configurations(filePath?)` — 无参列运行配置（含 name/description/commandLine/workingDirectory/environment/supportsDynamicLaunchOverrides），带 filePath 返回 run points（gutter 可运行点）；`execute_run_configuration(configurationName | filePath+line, timeout=10000, waitForExit=true, programArguments, workingDirectory, envs)` — 返回 `exitCode?`、`output`（截断快照，超长追加 `<truncated>`）、`fullOutputPath`（完整输出临时文件，进程存活期间持续增长）、`sessionId`（run 模式下源码显式置 null） |
| Debugger（"Debugger MCP toolset" 插件，**Ultimate 默认 bundled**） | `xdebug_start_debugger_session(configurationName, filePath, line, timeout, graceWaitMs, programArguments, workingDirectory, envs)`；`xdebug_control_session(sessionId, action∈{STEP_INTO, STEP_OVER, STEP_OUT, RESUME, PAUSE, STOP, WAIT_FOR_PAUSE, DRAIN_EVENTS}, timeout, eventsLimit, clearEventsAfterRead)`；`xdebug_set_breakpoint(breakpointId, filePath, line, condition, isLogMessage, isLogStack, temporary, suspendPolicy∈{ALL,THREAD,NONE}, enabled)`；`xdebug_remove_breakpoint`；`xdebug_list_breakpoints(filePath?)`；`xdebug_get_stack(sessionId, threadId, limit, offset)`；`xdebug_get_threads`；`xdebug_get_frame_values(sessionId, frameIndex, depth)`；`xdebug_get_value_by_path`；`xdebug_set_variable`；`xdebug_evaluate_expression(sessionId, frameIndex, expression, depth)`；`xdebug_run_to_line`；`xdebug_get_debugger_status`。附带 `/ij-debugger` skill 文件 |
| File | `create_new_file`、`find_files_by_glob`、`find_files_by_name_keyword`、`get_all_open_file_paths`、`list_directory_tree`、`open_file_in_editor` |
| Read/Text | `read_file(file_path, mode∈{slice,lines,line_columns,offsets,indentation}, start_line, max_lines, …)`（支持 jar!/jrt 反编译读取）；`get_file_text_by_path`；`replace_text_in_file(oldText, newText, replaceAll, caseSensitive)`；`search_in_files_by_regex` / `search_in_files_by_text(searchText, directoryToSearch, fileMask, caseSensitive, maxUsageCount, timeout)` |
| Search | `search_file` / `search_regex` / `search_symbol` / `search_text(q, paths, limit)` |
| Formatting / Refactoring | `reformat_file(path)`；`rename_refactoring(pathInProject, symbolName, newName)` |
| Terminal（optional depends `org.jetbrains.plugins.terminal`） | `execute_terminal_command(command, executeInShell, reuseExistingTerminalWindow, timeout, maxLinesCount, truncateMode)` — 输出上限 2000 行，默认需人工确认，brave mode 免确认 |
| VCS（optional depends Git4Idea） | `get_repositories` |
| Database（需 Database Tools and SQL + AI Assistant 插件） | `list_database_connections`、`test_database_connection`、`list_database_schemas`、`list_schema_objects`、`execute_sql_query(connectionId, queryText)`、`cancel_sql_query`、`preview_table_data`、`list_recent_sql_queries`（付费） 等 |
| DevKit（插件开发） | `find_lock_requirement_usages` / `find_threading_requirements_usages`（分析 RequiresEdt/ReadLock 调用路径） |
| 其他 | `reformat_file`、`validate_inspection_kts` / `run_inspection_kts` / `generate_psi_tree`（Inspection KTS 系列）、`runNotebookCell`、`get_project_status` |

**已核实的能力空白（对照我们的需求）**
1. **无按模块构建、无单文件"重新编译"语义入口**（`build_project` 的 `filesToRebuild` 可指定文件，但没有 module 粒度参数；无 ⇧⌘F9 等价工具暴露 module scope）。
2. **运行会话生命周期残缺**：`ExecutionToolNames` 全部常量只有 `get_run_configurations` 与 `execute_run_configuration` 两个（源码实证）——**没有 stop / restart / list_running_sessions 工具**；run 模式 `sessionId` 被显式置 null（源码注释 "reset sessionId because at the moment it's unnecessary"）。想停一个 Spring Boot 只能靠 debug 模式的 `xdebug_control_session(action=STOP)` 或 terminal kill。
3. **控制台读取是"快照+临时文件"而非会话 API**：`output` 是截断快照，`fullOutputPath` 是磁盘临时文件路径，Agent 只能靠 `execute_terminal_command("tail -n …")` 二次读取；**无 offset/tail 增量协议、无服务端 grep、且完全不跟踪用户手动点绿色按钮启动的会话**。
4. **无任何 Maven 专用工具**：toolsets 目录中不存在 Maven toolset；同步/reimport/goal/profile 全部缺席（只能敲 `./mvnw` 终端命令，丢失 IDE 的 Maven 模型与错误结构化）。
5. **无多客户端治理**：无认证、无角色、无会话-项目绑定、无并发互斥语义、无审计日志；brave mode 是全局单开关（一开全开，含任意 shell 命令）。
6. **调试事件仅 JVM 调试器上报**（官方文档明示），且 Debugger toolset 在 **Community 版不 bundled**（Ultimate 默认带）。
7. 无多 IDE 实例发现；端口冲突时行为未定义（YouTrack 有相关抱怨）。

### 2.2 @jetbrains/mcp-proxy（npm）+ JetBrains/mcp-server-plugin —— 已死的过渡方案

- [JetBrains/mcp-jetbrains](https://github.com/JetBrains/mcp-jetbrains)：960 stars，纯 JS stdio↔HTTP proxy；通过 IDE 内置 web server 的 REST 端点（如 `/api/mcp/list_tools`）转发；`IDE_PORT` / `HOST` / `LOG_ENABLED` 环境变量定位 IDE，未指定 `IDE_PORT` 时在内置 web server 端口段（63342 起）扫描。npm 最后版本 1.8.0。README 首行明示 **"This repository is no longer maintained… integrated into all IntelliJ-based IDEs since 2025.2"**。
- [JetBrains/mcp-server-plugin](https://github.com/JetBrains/mcp-server-plugin)：131 stars，最后 push 2025-08，同样声明停止维护；它定义的旧扩展点（`com.intellij.mcpServer.mcpTool`，`AbstractMcpTool` 基类）被平台内建版的 `com.intellij.mcpServer.mcpToolset` 取代。Marketplace 插件 26071（下载量 1380 万+）保留给 2025.2 之前的 IDE 用户。
- **对 Idectl 的启示**：proxy 架构（stdio 进程 + 端口扫描发现 IDE）是**多 IDE 实例发现**的现成参考实现；官方放弃 proxy 转向 IDE 内嵌 + SSE/streamable HTTP，验证了我们"IDE 内嵌 MCP server"的技术路线。

### 2.3 开源第三方插件盘点（stars 为 2026-07-03 GitHub API 实测）

| 插件 / 仓库 | Stars | 能力 | 传输 |
|---|---|---|---|
| [hechtcarmel/jetbrains-index-mcp-plugin](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin)（marketplace 29174 "IDE Index MCP Server"） | **268**（活跃，push 2026-07-02） | 索引/导航/重构为主；**含 Build Project（结构化错误，默认关闭）、Reload Project（Maven/Gradle reimport，默认关闭）、Import Modules（跨项目导入 Maven 模块）**；独有"项目生命周期管理"（active→background→dormant→closed 自动休眠，`ide_project_status`/`ide_set_project_mode`/`ide_lifecycle_log`）；支持 headless/remote dev | 内嵌 HTTP MCP |
| [hechtcarmel/jetbrains-debugger-mcp-plugin](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin)（marketplace 29233 "Debugger MCP Server"） | **85** | 22 个调试工具：会话启停、多会话并发、断点（条件/tracepoint）、step 全家桶、run to line、帧/线程/变量、改值、表达式求值（**带三档安全策略：unrestricted / 风险操作黑名单 / 只读+自定义 regex 阻断**）、bundled Claude skill；基于 XDebugger 跨语言 | streamable HTTP，`http://127.0.0.1:{port}/debugger-mcp/streamable-http`，IDE 各有默认端口（IDEA=29190），可配 host 绑定 |
| [catatafishen/ide-agent-for-copilot](https://github.com/catatafishen/ide-agent-for-copilot)（marketplace 30415 "AgentBridge"） | **64** | 90+ 工具：Document API 编辑、PSI 导航、**跑测试/构建并把结果回传 tool response**、SonarLint 告警、Git、undo 历史；支持 MCP 和 ACP 双协议；可选 localhost HTTPS Web 聊天 UI（PWA） | 内嵌 MCP（HTTPS localhost） |
| [alexeyleping/intellij-debug-mcp](https://github.com/alexeyleping/intellij-debug-mcp) | 0 | 37 工具/8 组（调试、构建、测试、文件、PSI、inspection），特色是 JSON-RPC over HTTP（port 63820）让 Agent 直接 curl，免 MCP 握手 | HTTP JSON-RPC（非标准 MCP） |
| [brannow/idea-mcp-control](https://github.com/brannow/idea-mcp-control)（marketplace 30817 "MCP Control"） | 3 | 调试器控制（断点/step/变量），主打 PhpStorm | MCP |
| [puddinging/idea-mcp-plugin](https://github.com/puddinging/idea-mcp-plugin) | 1 | 40+ 工具（导航/重构/搜索/inspection/build/Git/Spring），兼容 2023.3+ | SSE + Stdio |
| [FgForrest/mcp-jdwp-java](https://github.com/FgForrest/mcp-jdwp-java)（非 IDE 插件） | — | 绕开 IDE、直接 JDWP/JDI 附加 JVM 的 47 工具调试 server；证明"无 IDE 也能调试"，但拿不到 run configuration / 控制台 / Maven 模型 | stdio |

**共性结论**：第三方全部是**单机单用户**假设，无认证/角色/审计；无一提供运行会话控制台的增量读取/检索；Maven 支持最多到 reimport（index-mcp-plugin），没有 goal 执行与 profile 切换。

### 2.4 对照生态：VS Code Copilot agent mode

- **工具形态**：built-in tools + MCP tools + extension tools 三类，单次请求最多启用 **128 个工具**；`#` 引用、tools picker 管理。构建/运行相关：`runTasks`（受 `github.copilot.chat.agent.runTasks` 设置控制，内部为 `createAndRunTask` + `getTaskOutput`）、`runInTerminal` / `getTerminalOutput`、`problems`（读编辑器编译/lint 错误）、`runTests` / `testFailure`；调试走 `launch.json` 生成 + `copilot-debug` 终端命令包装。
  来源：[Use tools in chat](https://code.visualstudio.com/docs/copilot/agents/agent-tools)、[agent mode 发布博客](https://code.visualstudio.com/blogs/2025/02/24/introducing-copilot-agent-mode)、[Debug with Copilot](https://code.visualstudio.com/docs/agents/guides/debug-with-copilot)
- **确认机制（值得抄）**：每次工具调用 UI 透明展示；终端类工具默认要求批准；确认弹窗里可**展开并编辑工具参数后再 Allow**；可按"本次/本会话/本工作区/永久"分级放行；支持 "Chat: Reset Tool Confirmations"；另有 agent sandbox 限制文件系统/网络。
- **已知痛点（我们要避免）**：`runTasks` 有确认 bug——build 失败仍报 "task succeeded"，Agent 拿不到真实退出码与错误输出（[vscode#274975](https://github.com/microsoft/vscode/issues/274975)）；terminal 输出经常"命令已完成但 Agent 读不到/等到天荒地老"（[community#161238](https://github.com/orgs/community/discussions/161238)）。→ 结构化的退出码/构建结果回传 + 可靠的输出游标协议是真实刚需。

---

## 3. 关键 API 详解（含 Kotlin 代码草图）

### 3.1 官方内建 MCP 框架的扩展点（2025.2+，`com.intellij.mcpServer.mcpToolset`）

平台把"写 MCP 工具"抽象成了注解式 Kotlin suspend 函数（源码：[McpToolset.kt](https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/McpToolset.kt)、[plugins/mcp-server/README.md](https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/README.md)）：

```kotlin
// 平台接口（com.intellij.mcpserver.McpToolset，EP: com.intellij.mcpServer.mcpToolset, dynamic="true"）
interface McpToolset {
  companion object { val EP: ExtensionPointName<McpToolset> = ExtensionPointName("com.intellij.mcpServer.mcpToolset") }
  fun isEnabled(): Boolean = true
  fun isExperimental(): Boolean = true   // 默认 experimental！
  fun alwaysIncluded(): Boolean = false  // tool-router 模式下强制常驻
}

// 注解（com.intellij.mcpserver.annotations）
annotation class McpTool(val name: String = "", val title: String = "")
annotation class McpDescription(val description: String)
annotation class McpToolHints(val readOnlyHint: McpToolHintValue = UNSPECIFIED,
                              val destructiveHint: McpToolHintValue = UNSPECIFIED,
                              val idempotentHint: McpToolHintValue = UNSPECIFIED,
                              val openWorldHint: McpToolHintValue = UNSPECIFIED)

// 第三方注册示例（README §1.1）
class HelloToolset : McpToolset {
  @McpTool @McpDescription("Greets the caller by name.")
  suspend fun say_hi(name: String = "world"): String = "Hello, $name!"
}
// plugin.xml:
// <extensions defaultExtensionNs="com.intellij">
//   <mcpServer.mcpToolset implementation="my.plugin.HelloToolset"/>
// </extensions>
```

要点（全部来自官方 README，是设计 Idectl 工具层的直接参照）：
- 参数支持原始类型 / String / nullable / List / Map / `@Serializable` 类，**kotlinx.serialization 生成 JSON schema，递归类型不支持**；Kotlin 默认值→optional，但**默认值本身不会写进 schema**，须手写进 `@McpDescription`。
- 返回：`Unit`→`[success]`、原始值→toString、`@Serializable`→JSON、或 `McpToolCallResult`；抛 `McpExpectedError`（`mcpFail(...)`）保留错误文本。
- `projectPath` 由框架**自动注入到每个工具**，实现里用 `currentCoroutineContext().project` 取 `Project`。
- 长任务用 `currentCoroutineContext().reportToolActivity("...")` 上报进度到 IDE UI。
- ⚠️ 该 EP **2025.2 才存在**——Idectl sinceBuild=233 无法依赖它，必须自带 MCP server（见 §5）。

### 3.2 竞品用到的、Idectl 同样要用的平台 API（233 可用）

```kotlin
// 运行配置枚举（ExecutionToolset 同款）
val runManager = com.intellij.execution.RunManager.getInstance(project)
val all: List<RunnerAndConfigurationSettings> = runManager.allSettings
val common = settings.configuration as? com.intellij.execution.CommonProgramRunConfigurationParameters
// common?.programParameters / workingDirectory / envs —— 官方就是这么渲染 launch 详情的

// 启动（官方内部 util executeRunConfiguration 的公开等价物）
com.intellij.execution.ProgramRunnerUtil.executeConfiguration(
  settings, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance())   // run
// debug 用 DefaultDebugExecutor.getDebugExecutorInstance()

// 控制台跟踪（官方没做、我们要做的部分）：
// ExecutionManager topic EXECUTION_TOPIC 能捕获包括用户点绿色按钮在内的所有 processStarted/processTerminated；
// ProcessHandler.addProcessListener { onTextAvailable(event, outputType) } 建 per-session ring buffer，
// 即可实现 offset/tail 增量读取 + 服务端 grep —— 这正是内建 MCP 用 fullOutputPath 临时文件绕开的地方。

// 调试器（Debugger MCP toolset / hechtcarmel 插件同款基础）：
val dbg = com.intellij.xdebugger.XDebuggerManager.getInstance(project)
dbg.debugSessions; dbg.breakpointManager   // XBreakpointManager: addLineBreakpoint/removeBreakpoint/allBreakpoints
session.stepOver(false); session.stepInto(); session.stepOut(); session.pause(); session.resume()
frame.evaluator?.evaluate(expr, callback, sourcePosition)   // XDebuggerEvaluator ≈ 任意代码执行 → 必须设权限门槛
                                                            // （hechtcarmel 插件为此内置三档安全策略，先例）

// Maven（org.jetbrains.idea.maven，所有竞品缺席的部分）：
val mm = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
mm.projects; mm.explicitProfiles; mm.forceUpdateAllProjectsOrFindAllAvailablePomFiles()  // ≈ reimport
org.jetbrains.idea.maven.execution.MavenRunConfigurationType.runConfiguration(project, MavenRunnerParameters(...), callback) // 任意 goal

// 构建（内建 build_project 的底层）：
com.intellij.task.ProjectTaskManager.getInstance(project).buildAllModules()   // Promise<Result>
  .rebuildAllModules() / .build(vararg modules) / .compile(vararg files)      // ← module/单文件粒度官方 MCP 没暴露，API 本身齐全
// 错误收集：CompilationStatusListener / CompilerMessage（CompilerTopics.COMPILATION_STATUS）
```

### 3.3 老 proxy 架构（多实例发现参考）

`@jetbrains/mcp-proxy`：stdio MCP server → 轮询 `http://127.0.0.1:{port}/api/mcp/list_tools`（IDE 内置 web server，63342 起的端口段）→ 找到响应的 IDE 即绑定；`IDE_PORT`/`HOST` 环境变量可显式指定。多 IDE 实例 = 起多个 proxy 各配一个 `IDE_PORT`。来源：[mcp-jetbrains README](https://github.com/JetBrains/mcp-jetbrains)。Idectl 可做得更好：固定发现文件（如 `~/.idectl/instances.json`，插件启动时写入 port+项目列表+PID）替代端口扫描。

---

## 4. 线程模型注意事项（EDT / ReadAction / WriteAction / 协程）

官方 mcp-server 插件的实践（README §7–§9 + ExecutionToolset 源码）就是 Idectl 的模板：

1. **工具函数都是 `suspend fun`，跑在后台协程**，绝不在 EDT 上执行工具体；MCP server（Ktor/内置 web server）的 IO 线程只做解码，业务切到 `Dispatchers.Default`。
2. **读 PSI/RunManager/VFS 必须 `readAction { }`**（`com.intellij.openapi.application.readAction`，suspend 版；2024.1 前可用 `ReadAction.nonBlocking().executeSynchronously()` 兜底）。官方 `get_run_configurations` 全程 `readAction { runManager.allSettings.map { ... } }`。
3. **写操作**（改断点、写文档）用 `edtWriteAction { }` / `WriteCommandAction.runWriteCommandAction(project) { }`；断点增删（`XBreakpointManager.addLineBreakpoint`）需要 write action + EDT。
4. **启动 run configuration 必须回 EDT**：`ProgramRunnerUtil.executeConfiguration` 要求 EDT，用 `withContext(Dispatchers.EDT)` 包裹；等待进程结束用 `suspendCancellableCoroutine` + `ProcessListener.processTerminated`，配 `withTimeoutOrNull(timeout)`（官方 execute_run_configuration 的 timeout/waitForExit 语义即如此实现）。
5. **调试器回调线程不确定**：`XDebugSession` 事件在 debugger manager thread 上来，`evaluate` 是异步 callback——转 suspend 时注意不要在 debugger 线程里再取 read lock 造成死锁。
6. **取消传播**：MCP 客户端断连/取消 → 协程 cancel → 必须传导到 `ProgressIndicator`（`coroutineToIndicator`）与子进程。官方框架自动处理 cancellation，自研 server 要自己接。
7. DevKit 甚至提供了 `find_threading_requirements_usages` 工具帮忙静态验证 `@RequiresEdt/@RequiresReadLock` 调用路径——开发期可直接用官方 IDE 的 MCP 来审计 Idectl 自己的线程正确性（吃自己狗粮的捷径）。

---

## 5. 版本兼容性（sinceBuild=233 → 2026.1）

| 版本 | 事实 | 对 Idectl 的影响 |
|---|---|---|
| 233 (2023.3) ~ 2025.1 | 无内建 MCP。官方路线 = marketplace 插件 26071 + npm proxy（均已停更）。 | 这个区间是 Idectl 的**独占市场**；必须自带嵌入式 MCP server（Ktor CIO + MCP Kotlin SDK 或手写 streamable HTTP），不可依赖任何 `com.intellij.mcpserver.*` API |
| 2025.2 (252) | 内建 MCP Server（bundled `com.intellij.mcpServer`，默认关）；`com.intellij.mcpServer.mcpToolset` EP（dynamic）诞生；端口 64342+offset；旧 proxy/插件宣告废弃 | 与内建 server 共存：端口错开（不要用 64342±120 段）；可**可选地**再注册一份 toolset 进官方 server（optional depends，双通道），但权限/会话治理只能在自有通道实现，因为官方通道无认证无会话概念 |
| 2025.2+ Ultimate | Debugger MCP toolset bundled（`xdebug_*` 13 个工具），**Community 不带**；调试事件仅 JVM 调试器 | Community 用户的调试器 MCP 仍是空白 → Idectl 的调试工具对 Community + 233~2025.1 双重差异化 |
| 2026.1（当前文档版本） | 工具面扩到 ~60+（read_file 多模式、patch、universal tool-router 模式、DB 工具、notebook）；仍无 Maven toolset、无 run session 管理、无认证 | 空白至今未填，YouTrack 上相关 issue（LLM-25012 OAuth、IJPL-207839 端口）仍 open |
| Gradle 侧 | intellij-platform-gradle-plugin 2.x + sinceBuild=233、无 untilBuild：注意 233~241 上用到的 API 需逐一核对（如 suspend `readAction` 在 2023.3 已有；`edtWriteAction` 较新，233 上用 `WriteCommandAction` 替代） | CI 矩阵建议至少 233、242、252、261 四档跑 verifier |

---

## 6. 已知坑与限制（来自一手资料）

1. **内建 MCP 的 run `sessionId` 是摆设**：源码注释明写 run 模式 sessionId 置 null；`fullOutputPath` 临时文件"IDE 存活期间可用"但无清理契约、无 offset 协议，长跑 Spring Boot 输出会无限增长。
2. **内建 terminal 工具 2000 行硬上限** + 默认逐次确认；brave mode 是全局开关，一开则 shell 与 run configuration 全免确认——粒度过粗，正是 Idectl 分角色授权要解决的。
3. **官方框架 schema 不含默认值**（bytecode 无元数据），默认值必须写进 description——自研工具层要么同样注意，要么选择手写 JSON schema。
4. VS Code `runTasks` 的"永远成功"bug（vscode#274975）与 terminal 输出丢失（community#161238）说明：**基于终端文本嗅探的构建/运行状态是不可靠的**，必须走 IDE 内部事件（`CompilationStatusListener`、`ProcessListener`）拿结构化结果。
5. 老 proxy 端口扫描在多 IDE 并存时会连错实例（README 要求手工 `IDE_PORT`）；内建版同样无实例发现。Idectl 需要显式的 instance registry。
6. `XDebuggerEvaluator.evaluate` 等价于目标 JVM 内任意代码执行；先例（hechtcarmel 插件）已提供三档安全策略（unrestricted / blocklist / read-only+regex），Idectl 的角色模型应至少达到该水准并叠加审计。
7. `com.intellij.mcpServer.mcpToolset` 的 `isExperimental()` 默认 true——若走"寄生官方 server"路线，工具默认可能被 experimental 过滤影响可见性。
8. 构建互斥：`ProjectTaskManager` 并发触发构建时 JPS 内部排队但无对外队列可见性——Idectl 需要自己维护构建锁与"谁在等"的语义（竞品全部未处理）。
9. 官方 MCP server 默认**关闭**（`enableMcpServer=false`），依赖用户手动开启——意味着"开箱即用的 Agent 控制 IDE"体验尚不存在，安装即用是可打的点。

---

## 7. 能力差距表

图例：✅ 完整支持 ｜ 🟡 部分/绕行 ｜ ❌ 不支持 ｜ 🎯 Idectl 设计目标

| 能力 | JetBrains 内建 MCP (2025.2+) | mcp-proxy(旧官方, 已废弃) | Index MCP (268★) | Debugger MCP (85★) | AgentBridge (64★) | VS Code Copilot agent | **Idectl 🎯** |
|---|---|---|---|---|---|---|---|
| 构建项目 | ✅ `build_project`(等待+返回错误) | 🟡 旧工具集 | 🟡 默认关闭 | ❌ | ✅ | 🟡 `runTasks`(有假成功 bug) | 🎯 |
| 构建模块 | ❌ 无 module 粒度 | ❌ | ❌ | ❌ | ❌ | ❌ | 🎯 |
| 重新编译单文件 (⇧⌘F9) | 🟡 `filesToRebuild` | ❌ | ❌ | ❌ | ❌ | ❌ | 🎯 |
| 构建错误结构化返回 | ✅ | 🟡 | 🟡 | ❌ | ✅ | 🟡 `problems` 面板 | 🎯 |
| 列运行配置(含类型/参数) | ✅ `get_run_configurations` | 🟡 | ❌ | 🟡 仅调试用途 | 🟡 | 🟡 tasks/launch.json | 🎯 |
| run 启动 | ✅ `execute_run_configuration` | 🟡 | ❌ | ❌ | 🟡 | 🟡 task/terminal | 🎯 |
| debug 启动 | ✅ `xdebug_start_debugger_session`(Ultimate) | ❌ | ❌ | ✅ | ❌ | 🟡 `copilot-debug` | 🎯 |
| 停止/重启指定会话 | ❌ run 无 stop；debug STOP ✅ | ❌ | ❌ | ✅ 调试会话 | ❌ | 🟡 kill terminal | 🎯 |
| 控制台增量读取 (offset/tail) | ❌ 快照+临时文件 | ❌ | ❌ | ❌ | 🟡 结果回传 | 🟡 `getTerminalOutput` 不稳定 | 🎯 |
| 控制台服务端 grep | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | 🎯 |
| 跟踪用户手动启动的会话 | ❌ | ❌ | ❌ | 🟡 attach 已有调试会话 | ❌ | ❌ | 🎯 |
| 断点增删列 | ✅ (Ultimate) | ❌ | ❌ | ✅ 含条件/tracepoint | ❌ | 🟡 launch.json 级 | 🎯 |
| 单步执行 | ✅ (Ultimate) | ❌ | ❌ | ✅ | ❌ | ❌ | 🎯 |
| 帧/变量读取 | ✅ (Ultimate) | ❌ | ❌ | ✅ 含改值 | ❌ | ❌ | 🎯 |
| 表达式求值(带安全策略) | 🟡 有工具，无策略分档 | ❌ | ❌ | ✅ 三档策略 | ❌ | ❌ | 🎯 分角色 |
| Maven 同步/reimport | ❌ | ❌ | 🟡 Reload Project | ❌ | ❌ | ❌ | 🎯 |
| Maven 任意 goal | ❌(仅 terminal 绕行) | ❌ | ❌ | ❌ | ❌ | 🟡 terminal | 🎯 |
| Maven profile 查询/切换 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | 🎯 |
| 多客户端并发会话 | 🟡 传输层允许，无会话概念 | ❌ 单连 | 🟡 | ✅ 多调试会话 | 🟡 | ❌ 单编辑器 | 🎯 |
| 角色/权限分级 | ❌ 仅全局 brave mode | ❌ | ❌ | 🟡 求值安全档 | ❌ | 🟡 逐工具确认+分级放行 | 🎯 只读/操作/管理员 |
| 认证 | ❌ 仅 localhost | ❌ | ❌ | ❌ | ❌ | —(进程内) | 🎯 token/角色绑定 |
| 审计日志 | ❌ | ❌ | 🟡 lifecycle log | 🟡 tool window 监控 | ❌ | 🟡 UI 展示调用 | 🎯 |
| 并发互斥语义(构建锁等) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | 🎯 |
| 多 IDE 实例发现 | ❌ 记端口 | 🟡 端口扫描 | ❌ | 🟡 IDE 各默认端口 | ❌ | — | 🎯 instance registry |
| 会话绑定项目路由 | 🟡 每工具 projectPath 参数 | ❌ | ✅ 多项目+休眠管理 | ✅ projectPath | 🟡 | — | 🎯 会话级绑定 |

---

## 8. 结论：Idectl 差异化价值主张

1. **运行/调试会话的一等公民生命周期 + 流式控制台**：唯一提供 list/stop/restart 任意会话、offset/tail 增量读取、服务端 regex grep（含上下文行），并且**统一跟踪用户手动启动的会话**（`ExecutionManager.EXECUTION_TOPIC` + `ProcessListener` ring buffer）——内建 MCP 只有"启动后拿快照/临时文件"，全部竞品为 ❌。
2. **Maven 深度集成**：sync/reimport、结构化模块列表、任意 goal（结构化输出）、profile 查询与切换、下载源码——整个生态目前是零（最强的 index-mcp-plugin 也只有 reimport）。
3. **多 Agent 多角色治理**：token 认证 + 只读/操作者/管理员三级角色 + 会话-项目绑定路由 + 构建互斥等并发语义 + 全量审计日志。内建 MCP 无认证、brave mode 全局一刀切；VS Code 的确认粒度证明了市场对分级授权的接受度，但它是单人产品。
4. **全版本、全 Edition 覆盖**：233 (2023.3)~2025.1 完全没有官方 MCP；2025.2+ 的 Community 版没有调试器 toolset。Idectl 一个插件同时补齐两块，且与内建 server 端口/功能错位共存。
5. **模块/单文件粒度构建 + 可靠的结构化结果**：暴露 build module、⇧⌘F9 单文件重编译，基于 `CompilationStatusListener` 返回结构化错误/警告——规避 VS Code "runTasks 永远成功"式的终端嗅探陷阱，也补上内建 `build_project` 缺失的粒度。

---

## 9. 参考来源列表

**JetBrains 官方文档 / 源码（一手）**
1. MCP Server | IntelliJ IDEA Documentation（工具全表、传输、brave mode）— https://www.jetbrains.com/help/idea/mcp-server.html
2. intellij-community `plugins/mcp-server/README.md`（工具编写框架权威指南）— https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/README.md
3. `McpToolset.kt`（EP `com.intellij.mcpServer.mcpToolset`）— https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/McpToolset.kt
4. `McpServerSettings.kt`（端口 64342+20×offset、默认关闭、brave mode）— https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/settings/McpServerSettings.kt
5. `ExecutionToolset.kt` / `ExecutionToolNames.kt`（run 工具全部实现与空白实证）— https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/toolsets/general/ExecutionToolset.kt
6. Model Context Protocol (MCP) | AI Assistant Documentation — https://www.jetbrains.com/help/ai-assistant/mcp.html
7. JetBrains/mcp-jetbrains（废弃声明、proxy 架构、960★）— https://github.com/JetBrains/mcp-jetbrains ；npm — https://www.npmjs.com/package/@jetbrains/mcp-proxy
8. JetBrains/mcp-server-plugin（旧 EP、131★、停更）— https://github.com/JetBrains/mcp-server-plugin
9. YouTrack：OAuth2 for MCP（LLM-25012）— https://youtrack.jetbrains.com/projects/LLM/issues/LLM-25012 ；Configurable MCP Port Per Project（IJPL-207839）— https://youtrack.jetbrains.com/projects/IJPL/issues/IJPL-207839
10. IntelliJ Platform Explorer（mcpToolset EP 使用者索引）— https://plugins.jetbrains.com/intellij-platform-explorer?extensions=com.intellij.mcpServer.mcpToolset

**第三方插件（stars 为 2026-07-03 GitHub API 实测）**
11. hechtcarmel/jetbrains-index-mcp-plugin（268★）— https://github.com/hechtcarmel/jetbrains-index-mcp-plugin ；marketplace — https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server
12. hechtcarmel/jetbrains-debugger-mcp-plugin（85★，22 工具，streamable HTTP :29190）— https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin ；marketplace — https://plugins.jetbrains.com/plugin/29233
13. catatafishen/ide-agent-for-copilot "AgentBridge"（64★，90+ 工具）— https://github.com/catatafishen/ide-agent-for-copilot ；介绍文 — https://dev.to/catatafishen/i-turned-intellij-into-a-90-tool-mcp-server-for-coding-agents-and-the-agent-built-most-of-it-542g
14. alexeyleping/intellij-debug-mcp（JSON-RPC :63820，37 工具）— https://github.com/alexeyleping/intellij-debug-mcp
15. brannow/idea-mcp-control（3★）— https://github.com/brannow/idea-mcp-control ；puddinging/idea-mcp-plugin（1★，2023.3+）— https://github.com/puddinging/idea-mcp-plugin
16. FgForrest/mcp-jdwp-java（JDWP/JDI 直连，47 工具）— https://github.com/FgForrest/mcp-jdwp-java

**VS Code 生态**
17. Use tools in chat（built-in/MCP/extension 工具、128 上限、确认与参数编辑）— https://code.visualstudio.com/docs/copilot/agents/agent-tools
18. Introducing GitHub Copilot agent mode — https://code.visualstudio.com/blogs/2025/02/24/introducing-copilot-agent-mode
19. Debug with GitHub Copilot（launch.json 生成、copilot-debug）— https://code.visualstudio.com/docs/agents/guides/debug-with-copilot
20. vscode#274975 runTasks 假成功 bug — https://github.com/microsoft/vscode/issues/274975 ；terminal 输出丢失 — https://github.com/orgs/community/discussions/161238

**其他**
21. IntelliJ IDEA MCP Server Configuration for OpenCode（SSE URL :64342 实配）— https://drfits.com/2026/04/20/intellij-idea-mcp-server-configuration-for-opencode/
