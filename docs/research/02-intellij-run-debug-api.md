# 调研报告 02：IntelliJ 平台"运行/调试"控制 API 与 XDebugger

> 项目：Idectl（IDE 内嵌 MCP Server，供外部 AI Agent 控制 IDEA）
> 调研日期：2026-07-03
> 目标平台：IDEA 2024.2+（sinceBuild=233，无 untilBuild）
> 资料来源：IntelliJ Platform SDK 官方文档（plugins.jetbrains.com/docs/intellij）与 JetBrains/intellij-community master 及 233 分支源码（均为一手查证，非记忆）。

---

## 1. 概述

IntelliJ 平台的运行/调试体系分为三层：

1. **配置层（RunManager / RunnerAndConfigurationSettings / ConfigurationType）**：管理项目内所有 Run/Debug Configuration 的持久化模型。`RunManager` 是 project-level service。
2. **执行层（ExecutionManager / ExecutionEnvironment / ProgramRunner / Executor / ProcessHandler / RunContentDescriptor）**：把一个 RunProfile 用某个 Executor（Run/Debug/Coverage）真正跑起来，并管理 Run 工具窗口的内容 Tab。生命周期事件走 message bus 的 `ExecutionManager.EXECUTION_TOPIC`。
3. **调试层（XDebugger：XDebuggerManager / XDebugSession / XBreakpointManager / XSuspendContext...）**：语言无关的调试器框架。Java 调试的具体实现（JavaLineBreakpointType、JavaDebugProcess）在 `com.intellij.java` 插件的 debugger 模块中。

对 Idectl 的关键结论（详见后文）：

- **列举/查找配置**：`RunManager.getInstance(project).allSettings` 等 API 完备且稳定，可满足"多项目多配置路由"。
- **编程启动**：首选 `ExecutionUtil.runConfiguration(settings, executor)` 或 `ExecutionEnvironmentBuilder.create(executor, settings).buildAndExecute()`；拿 `ProcessHandler` 有两条路：`ProgramRunner.Callback`（只对本次启动）或订阅 `EXECUTION_TOPIC`（能覆盖**用户手动点绿色按钮**启动的会话——这是 Idectl 控制台跟踪的关键）。
- **停止/重启/枚举**：`ExecutionManager.getRunningProcesses()`、`RunContentManager.getAllDescriptors()`、`ProcessHandler.destroyProcess()`、`ExecutionUtil.restart(descriptor)`。
- **调试控制**：`XDebuggerManager.getDebugSessions()` + `XDebugSession` 的 pause/resume/step 系列 API 完整；断点用 `XBreakpointManager.addLineBreakpoint(type, fileUrl, line, properties)`；帧/变量/求值全部是**异步回调模型**，需要自行桥接成 MCP 的同步/流式响应（建议 suspend + CompletableFuture/Channel 包装 + 超时）。
- **临时/程序化建配置**：`RunManager.createConfiguration(name, factory)` + 强类型 mutate（如 `ApplicationConfiguration.setMainClassName`）完全可行；不想污染用户配置列表可用 `setTemporaryConfiguration()` 或干脆不 `addConfiguration` 直接执行。
- **prior art**：intellij-community 仓库中已存在 JetBrains 官方内置 MCP server 插件（`plugins/mcp-server`，含 `com.intellij.mcpserver` 包与 ExecutionUtilTest 等），设计时强烈建议通读其源码作对照。

---

## 2. 关键 API 详解

### 2.1 RunManager：配置的枚举、查找与创建

来源：`platform/execution/src/com/intellij/execution/RunManager.kt`（master 已查证）；SDK 文档 [Run Configurations](https://plugins.jetbrains.com/docs/intellij/run-configurations.html)。

核心成员（Kotlin，abstract class RunManager，project service）：

```kotlin
companion object {
    fun getInstance(project: Project): RunManager
    suspend fun getInstanceAsync(project: Project): RunManager
    fun getInstanceIfCreated(project: Project): RunManager?
}

// 枚举
abstract val allSettings: List<RunnerAndConfigurationSettings>       // 全部配置（含临时）
abstract val allConfigurationsList: List<RunConfiguration>
abstract val tempConfigurationsList: List<RunnerAndConfigurationSettings>
abstract var selectedConfiguration: RunnerAndConfigurationSettings?  // 可读可写

// 按类型/名字查找
fun getConfigurationsList(type: ConfigurationType): List<RunConfiguration>
fun getConfigurationSettingsList(type: ConfigurationType): List<RunnerAndConfigurationSettings>
abstract fun findConfigurationByName(name: String?): RunnerAndConfigurationSettings?
abstract fun findConfigurationByTypeAndName(typeId: String, name: String): RunnerAndConfigurationSettings?
fun findConfigurationByTypeAndName(type: ConfigurationType, name: String): RunnerAndConfigurationSettings?
abstract fun findSettings(configuration: RunConfiguration): RunnerAndConfigurationSettings?

// 创建/增删
abstract fun createConfiguration(name: String, factory: ConfigurationFactory): RunnerAndConfigurationSettings
fun createConfiguration(name: String, typeClass: Class<out ConfigurationType>): RunnerAndConfigurationSettings
abstract fun getConfigurationTemplate(factory: ConfigurationFactory): RunnerAndConfigurationSettings
abstract fun addConfiguration(settings: RunnerAndConfigurationSettings)
abstract fun removeConfiguration(settings: RunnerAndConfigurationSettings?)
abstract fun setTemporaryConfiguration(tempConfiguration: RunnerAndConfigurationSettings?)
abstract fun makeStable(settings: RunnerAndConfigurationSettings)
abstract fun setUniqueNameIfNeeded(settings: RunnerAndConfigurationSettings): Boolean
fun suggestUniqueName(name: String?, type: ConfigurationType?): String
abstract fun hasSettings(settings: RunnerAndConfigurationSettings): Boolean
```

注意：`findConfigurationByName` 只按名字，**同名不同类型会撞**；Idectl 的 MCP 工具应以 `(projectName, typeId, name)` 三元组做寻址，落到 `findConfigurationByTypeAndName(typeId, name)`。

#### RunnerAndConfigurationSettings 结构

接口 `com.intellij.execution.RunnerAndConfigurationSettings`，关键属性（供 MCP list 工具序列化）：

- `getName(): String` / `setName(String)`
- `getType(): ConfigurationType`（`type.id`、`type.displayName`）
- `getConfiguration(): RunConfiguration`（强类型实体，可 instanceof 判定并读取 main class、模块等）
- `getFolderName(): String?` / `setFolderName(String?)`（Run 配置 UI 中的文件夹分组）
- `isTemporary(): Boolean`（临时配置，UI 里灰色显示，超出数量上限自动清除）
- `isShared()` / `storeInDotIdeaFolder()` 等存储方式相关
- `checkSettings(executor: Executor?)`：抛 `RuntimeConfigurationException` 表示配置不合法（可用于启动前校验并把错误结构化返回给 Agent）
- `getUniqueID(): String`：内部唯一 ID，可作为 MCP 侧稳定句柄的一部分

#### 常见 ConfigurationType 判别

区分方式：**用 `settings.type.id` 字符串**（避免对 Ultimate/闭源插件产生编译期依赖），或对 OSS 类型 instanceof。已查证/待验证的 id：

| 配置类型 | type id | 来源与说明 |
|---|---|---|
| Java Application | `"Application"` | `ApplicationConfigurationType.getId()`，intellij-community `java/execution/impl/.../application/ApplicationConfigurationType.java`（已查证） |
| Remote JVM Debug | `"Remote"` | `RemoteConfigurationType` 构造器第一参数（已查证，`java/execution/impl/.../remote/RemoteConfigurationType.java`） |
| JUnit | `"JUnit"` | `JUnitConfigurationType`（intellij-community `plugins/junit`；id 为 "JUnit"，实现阶段跑一遍确认） |
| Maven | `"MavenRunConfiguration"` | `MavenRunConfigurationType`（`plugins/maven`，可选依赖 org.jetbrains.idea.maven 时才可 instanceof；用 id 判别则无需依赖） |
| Spring Boot | `"SpringBootApplicationConfigurationType"` | Ultimate 闭源插件 `com.intellij.spring.boot.run.SpringBootApplicationConfigurationType`；**只能用 id 字符串判别，不能编译期依赖**（id 需实现阶段在真实 IDE 里 dump 验证） |
| Tomcat Server | 待验证 | Jakarta EE/应用服务器插件（Ultimate 闭源）；本地/远程 Tomcat 是两个 factory。实现阶段用 `allSettings.map { it.type.id }` dump 确认 |

建议 MCP `list_run_configurations` 工具直接返回 `{name, typeId, typeDisplayName, folderName, isTemporary, projectName}`，把类型判别的知识放在 Agent 侧提示词而非硬编码白名单。

### 2.2 编程启动配置

来源：`platform/execution/src/com/intellij/execution/runners/ExecutionEnvironmentBuilder.kt`、`platform/execution-impl/src/com/intellij/execution/ProgramRunnerUtil.java`、`platform/execution/src/com/intellij/execution/runners/ExecutionUtil.java`（均 master 已查证）。

#### 三条启动路径

**路径 A（最简，推荐默认）**：`ExecutionUtil.runConfiguration`

```java
// ExecutionUtil（com.intellij.execution.runners.ExecutionUtil）
public static void runConfiguration(@NotNull RunnerAndConfigurationSettings configuration, @NotNull Executor executor)
public static void runConfiguration(@NotNull RunnerAndConfigurationSettings configuration, @NotNull Executor executor, @NotNull ExecutionTarget target)
public static void runConfiguration(@NotNull RunnerAndConfigurationSettings configuration, @NotNull Executor executor, long executionId)
```

**路径 B（中层）**：`ProgramRunnerUtil`

```java
public static void executeConfiguration(@NotNull RunnerAndConfigurationSettings configuration, @NotNull Executor executor)
public static void executeConfiguration(@NotNull ExecutionEnvironment environment, boolean showSettings, boolean assignNewId)
public static void executeConfigurationAsync(@NotNull ExecutionEnvironment environment, boolean showSettings, boolean assignNewId,
                                             @Nullable ProgramRunner.Callback callback)
```

`ProgramRunner.Callback` 是拿"本次启动"产物的正道：`void processStarted(RunContentDescriptor descriptor)`（descriptor 里有 `getProcessHandler()`）。

**路径 C（底层、最灵活）**：`ExecutionEnvironmentBuilder`（Kotlin 类）

```kotlin
companion object {
    @JvmStatic @Throws(ExecutionException::class)
    fun create(project: Project, executor: Executor, runProfile: RunProfile): ExecutionEnvironmentBuilder
    @JvmStatic @Throws(ExecutionException::class)
    fun create(executor: Executor, settings: RunnerAndConfigurationSettings): ExecutionEnvironmentBuilder
    @JvmStatic fun create(executor: Executor, configuration: RunConfiguration): ExecutionEnvironmentBuilder
    @JvmStatic fun createOrNull(executor: Executor, settings: RunnerAndConfigurationSettings): ExecutionEnvironmentBuilder?
    // + createOrNull(project, executor, runProfile) / createOrNull(executor, configuration)
}
fun runner(runner: ProgramRunner<*>): ExecutionEnvironmentBuilder
fun target(target: ExecutionTarget?): ExecutionEnvironmentBuilder
fun activeTarget(): ExecutionEnvironmentBuilder
fun contentToReuse(contentToReuse: RunContentDescriptor?): ExecutionEnvironmentBuilder
fun dataContext(dataContext: DataContext?): ExecutionEnvironmentBuilder
fun runProfile(runProfile: RunProfile): ExecutionEnvironmentBuilder

@JvmOverloads fun build(callback: ProgramRunner.Callback? = null): ExecutionEnvironment
@Throws(ExecutionException::class) fun buildAndExecute()
```

`create(...)` 找不到匹配 ProgramRunner 时抛 `ExecutionException`（例如对不支持 debug 的配置请求 Debug executor）——MCP 工具应捕获并结构化返回。

#### Executor：run vs debug

```java
com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID   // = "Run"
com.intellij.execution.executors.DefaultDebugExecutor.EXECUTOR_ID // = "Debug"
DefaultRunExecutor.getRunExecutorInstance()
DefaultDebugExecutor.getDebugExecutorInstance()
// 或 ExecutorRegistry.getInstance().getExecutorById(id)
```

（SDK 文档 [Execution](https://plugins.jetbrains.com/docs/intellij/execution.html) 列出三个内置 executor：DefaultRunExecutor / DefaultDebugExecutor / CoverageExecutor。）

#### 启动线程要求

- `ExecutionUtil.doRunConfiguration` 内部逻辑（master 源码已查证）："如果当前在 EDT，则通过 ProgressManager 以 blocking read action 同步执行 builder；否则直接执行"——即 **`ExecutionUtil.runConfiguration` 可以在 EDT 或后台线程调用**，它自己处理线程切换；错误通知用 `UIUtil.invokeLaterIfNeeded` 回 EDT。
- 但 `ProgramRunnerUtil.executeConfiguration` 及很多 ProgramRunner 实现历史上假定 EDT。**Idectl 稳妥策略：MCP 请求线程（Ktor/协程）→ `withContext(Dispatchers.EDT)`（或 `ApplicationManager.getApplication().invokeLater`）→ 调启动 API**，随后通过监听器异步等待 processStarted。
- 已废弃提示：`XDebuggerManager.startSession` 标注 `@RequiresEdt`，印证"启动动作在 EDT"是平台默认约定。

#### 如何拿到启动后的 ProcessHandler / RunContentDescriptor / executionId

三种方式，Idectl 应组合使用：

1. **`ProgramRunner.Callback.processStarted(RunContentDescriptor)`**：随 `build(callback)` 或 `executeConfigurationAsync(..., callback)` 传入；只覆盖自己发起的启动。
2. **`ExecutionManager.EXECUTION_TOPIC`**（见 2.3）：覆盖**所有**启动，包括用户点绿色按钮的——这是构建"会话注册表 + 控制台增量缓冲"的正解。回调里 `env.executionId`（`long`，`ExecutionEnvironment.getExecutionId()`，每次执行分配的全局自增 id，`assignNewExecutionId(): long` 可重分配）可作为 MCP 侧 session handle。
3. **事后枚举**：`RunContentManager.getInstance(project).allDescriptors` → `RunContentDescriptor.getProcessHandler()`。

`ExecutionEnvironment`（master 已查证）关键成员：`getExecutionId(): long`、`setExecutionId(long)`、`assignNewExecutionId(): long`、`getRunnerAndConfigurationSettings(): RunnerAndConfigurationSettings?`、`getRunProfile(): RunProfile`、`getExecutor(): Executor`、`getProject(): Project`、`getContentToReuse(): RunContentDescriptor?`、`getCallback(): ProgramRunner.Callback?`；它 `implements Disposable`。

### 2.3 生命周期事件：EXECUTION_TOPIC / ExecutionListener

来源：`platform/execution/src/com/intellij/execution/ExecutionListener.java` 与 `ExecutionManager.kt`（master 已查证）。

```kotlin
// ExecutionManager.kt
@JvmField @Topic.ProjectLevel
val EXECUTION_TOPIC: Topic<ExecutionListener>  // project-level topic
```

`ExecutionListener` 全部回调（default 方法，按发生顺序）：

```java
default void processStartScheduled(@NotNull String executorId, @NotNull ExecutionEnvironment env)
default void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env)
default void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler)
        // javadoc: "Called before ProcessHandler#startNotify()"
default void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env)
default void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, Throwable cause)
default void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler)
        // javadoc: "Called after ProcessHandler#startNotify()"
default void processTerminating(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler)
default void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode)
```

要点：

- **project-level topic**：`project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, listener)`；多项目场景每个 open project 各订阅一次（配合 `ProjectManagerListener`/`ProjectActivity` 处理项目开关）。
- **exitCode 直接由 `processTerminated` 给出**（int 参数），比轮询 `ProcessHandler.getExitCode()` 可靠。
- 三参 `processStarting(…, handler)` 在 `startNotify()` **之前**触发——这是**在首行输出产生前挂 `ProcessListener` 抓全量控制台输出**的唯一保险时机（`processStarted` 时早期输出理论上已可能开始流动，但 startNotify 语义保证 text 事件在 startNotify 后才 fire，因此二者都可用；用 processStarting 更稳）。
- 线程：topic 事件在发布者线程同步分发，**不保证 EDT**；listener 内不要做慢操作，转发到自己的协程/队列。
- 用户手动点 Run 按钮同样走这条 topic → Idectl 的会话注册表天然覆盖手动会话。

### 2.4 枚举运行中进程、停止、重启、exitCode

来源：`ExecutionManager.kt`、`RunContentManager.java`、`ProcessHandler.java`（platform/util，master 已查证）。

```kotlin
// ExecutionManager（project service）
abstract fun getRunningProcesses(): Array<ProcessHandler>   // “所有 run/debug tab 管理的进程”
@ApiStatus.Internal
abstract fun getRunningDescriptors(condition: Condition<in RunnerAndConfigurationSettings>): List<RunContentDescriptor>
abstract fun restartRunProfile(project: Project, executor: Executor, target: ExecutionTarget,
                               configuration: RunnerAndConfigurationSettings?, processHandler: ProcessHandler?,
                               environmentCustomization: Consumer<in ExecutionEnvironment>?)
abstract fun restartRunProfile(environment: ExecutionEnvironment)
```

注意 `getRunningDescriptors` 标注 `@ApiStatus.Internal`（新版本），**不要依赖**；公开替代是：

```java
// RunContentManager（com.intellij.execution.ui，project service，master 已查证）
static @NotNull RunContentManager getInstance(@NotNull Project project)
@NotNull List<RunContentDescriptor> getAllDescriptors()
@Nullable RunContentDescriptor getSelectedContent()
@Nullable RunContentDescriptor findContentDescriptor(Executor requestor, ProcessHandler handler)
boolean removeRunContent(@NotNull Executor executor, @NotNull RunContentDescriptor descriptor)
@Nullable ToolWindow getToolWindowByDescriptor(@NotNull RunContentDescriptor descriptor)
Topic<RunContentWithExecutorListener> TOPIC
```

`RunContentDescriptor` 常用：`getProcessHandler(): ProcessHandler?`、`getDisplayName(): String`、`getExecutionConsole(): ExecutionConsole?`（可据此 `XDebuggerManager.getDebugSession(console)` 反查调试会话）。

停止（`com.intellij.execution.process.ProcessHandler`，master 已查证）：

```java
public void destroyProcess()   // 强制终止（Java 进程通常 = Process.destroy）
public void detachProcess()    // 只断开 IDE 与进程的连接，进程继续跑（对应 UI 上的 detach）
public boolean isProcessTerminated()
public boolean isProcessTerminating()
public @Nullable Integer getExitCode()   // 进程未结束时为 null
public boolean waitFor() / waitFor(long timeoutMs)
public void startNotify()
public void addProcessListener(ProcessListener l)
public void addProcessListener(ProcessListener l, Disposable parentDisposable)
```

`destroyProcess`/`detachProcess` **立即返回**，实际终止通知异步到达（`processTerminated` 事件）。MCP `stop_session` 工具应实现为"发起 destroy → 挂 listener/轮询等待 terminated（带超时）→ 返回 exitCode"。

对 Spring Boot 等支持优雅退出的进程：`destroyProcess` 在 `KillableProcessHandler` 上默认先尝试 soft kill（Windows 走 GenerateConsoleCtrlEvent / *nix 走 SIGINT 类机制），`KillableProcessHandler.killProcess()` 强杀；可将 MCP stop 工具设计成 `force: Boolean` 参数。

重启：

- 面向 descriptor：`ExecutionUtil.restart(descriptor: RunContentDescriptor)` / `restartIfActive(descriptor)` / `restart(environment: ExecutionEnvironment)`（master 已查证）。
- 面向配置：`ExecutionManager.restartRunProfile(environment)`——平台内部"Rerun"按钮走的就是它，会先停旧进程（single-instance 策略）再启动。

单实例语义：`RunConfiguration.isAllowRunningInParallel()`（对应 UI "Allow multiple instances"）。false 时重复启动会触发平台停止旧实例的确认流程——headless/MCP 场景要注意这可能弹对话框，见"坑"章节。

### 2.5 XDebugger：会话枚举与控制

来源：`platform/xdebugger-api/src/com/intellij/xdebugger/XDebuggerManager.java`、`XDebugSession.java`、`XDebugSessionListener.java`（master 已查证）。

```java
// XDebuggerManager（project service）
public static XDebuggerManager getInstance(@NotNull Project project)
public abstract @NotNull XBreakpointManager getBreakpointManager()
public abstract XDebugSession @NotNull [] getDebugSessions()
public abstract @Nullable XDebugSession getCurrentSession()
public abstract @Nullable XDebugSession getDebugSession(@NotNull ExecutionConsole executionConsole)
public abstract @NotNull <T extends XDebugProcess> List<? extends T> getDebugProcesses(Class<T> processClass)
@Topic.ProjectLevel
public static final Topic<XDebuggerManagerListener> TOPIC   // processStarted/processStopped/currentSessionChanged
// startSession(environment, processStarter) 已 @Deprecated + @RequiresEdt，新代码用 newSessionBuilder()（仅当自己实现调试器时才需要）
```

```java
// XDebugSession
void pause()
void resume()
void stepOver(boolean ignoreBreakpoints)
void stepInto()
void stepOut()
void runToPosition(@NotNull XSourcePosition position, boolean ignoreBreakpoints)
void stop()
boolean isSuspended()
boolean isPaused()
boolean isStopped()
@Nullable XSuspendContext getSuspendContext()
@Nullable XStackFrame getCurrentStackFrame()
@Nullable XSourcePosition getCurrentPosition()
void setCurrentStackFrame(@NotNull XExecutionStack executionStack, @NotNull XStackFrame frame)
void addSessionListener(@NotNull XDebugSessionListener listener, @NotNull Disposable parentDisposable)
@NotNull XDebugProcess getDebugProcess()
@NotNull String getSessionName()
@NotNull RunContentDescriptor getRunContentDescriptor()
Project getProject()
```

`XDebugSessionListener`（default 方法）：

```java
default void sessionPaused()
default void sessionResumed()
default void sessionStopped()
default void stackFrameChanged()
default void beforeSessionResume()
default void settingsChanged()
default void breakpointsMuted(boolean muted)
// @ApiStatus.Experimental: stackFrameChanged(boolean changedByUser)
// @ApiStatus.Internal: settingsChangedFromFrontend()
```

要点：

- 会话与进程的关联：`XDebugSession.getRunContentDescriptor().getProcessHandler()`，或反向 `XDebuggerManager.getDebugSession(descriptor.executionConsole)`。Idectl 会话注册表应存 `(executionId, processHandler, descriptor, xDebugSession?)`。
- pause/resume/step 从任意线程调用通常是安全的（内部转给 debug process 的命令队列），但平台大量调用点在 EDT；**统一在 EDT 调用最稳**。
- `sessionPaused` 触发时 `getSuspendContext()` 才非空；MCP 的 `wait_for_breakpoint` 工具可实现为"挂 listener + suspend 直到 sessionPaused（带超时）"。

### 2.6 断点管理：XBreakpointManager

来源：`platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/XBreakpointManager.java` 与 `platform/xdebugger-impl/.../XBreakpointManagerImpl.java`（master 与 233 分支均已查证）。

```java
<T extends XBreakpointProperties> XBreakpoint<T> addBreakpoint(XBreakpointType<XBreakpoint<T>, T> type, @Nullable T properties)

<T extends XBreakpointProperties> XLineBreakpoint<T> addLineBreakpoint(
    XLineBreakpointType<T> type, @NotNull String fileUrl, int line, @Nullable T properties)
// 还有带 temporary 参数与带 XLineBreakpointAdditionalInfo 的重载

void removeBreakpoint(@NotNull XBreakpoint<?> breakpoint)
XBreakpoint<?> @NotNull [] getAllBreakpoints()
<B> Collection<? extends B> getBreakpoints(@NotNull XBreakpointType<B, ?> type)
<B> Collection<? extends B> getBreakpoints(@NotNull Class<? extends XBreakpointType<B, ?>> typeClass)
<B, P> Collection<B> findBreakpointsAtLine(@NotNull XLineBreakpointType<P> type, @NotNull VirtualFile file, int line)
```

**WriteAction 问题（已实测源码）**：master 与 233 分支的 `XBreakpointManagerImpl` 的 add/remove **没有** `assertWriteAccessAllowed` 断言，用内部 `ReentrantLock`（`withLockMaybeCancellable`）做同步——即 2023.3+ 理论上可从任意线程增删断点。但平台自身入口（`XDebuggerUtilImpl.toggleAndReturnLineBreakpoint`）仍包在 `WriteAction` 中，且历史版本曾要求 write action。**设计建议：统一 `WriteAction.runAndWait`（EDT）包裹增删断点，兼容 233~最新且零风险。**

**找 XLineBreakpointType**：

```kotlin
// 通用：按 type id 找
val type = XBreakpointUtil.findType("java-line") // com.intellij.xdebugger.breakpoints.XBreakpointUtil（impl 模块）
// 或遍历 XBreakpointType.EXTENSION_POINT_NAME.extensionList 过滤 id
// Java 强类型（需依赖 com.intellij.java）：
val javaLineType = XDebuggerUtil.getInstance()
    .findBreakpointType(JavaLineBreakpointType::class.java)  // com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
```

**Java 行断点 type id = `"java-line"`**（已查证：`JavaLineBreakpointType` 构造器 `super("java-line", ...)`，类声明 `extends JavaLineBreakpointTypeBase<JavaLineBreakpointProperties>`，位于 `java/debugger/impl/src/com/intellij/debugger/ui/breakpoints/JavaLineBreakpointType.java`）。其余相关 id（方法断点 `java-method`、异常断点 `java-exception`、字段观察 `java-field` 等）实现阶段 dump `XBreakpointType` EP 确认。

**条件断点/日志断点**：这些是 `XBreakpoint` 的通用属性，不在 properties 里：

```java
breakpoint.setCondition(String)                       // 旧 API
breakpoint.setConditionExpression(XExpression)        // 推荐：XExpressionImpl.fromText(text)
breakpoint.setLogMessage(boolean)                     // 命中时打印 "breakpoint hit" 消息
breakpoint.setLogExpressionObject(XExpression)        // 命中时求值并打印表达式（日志断点）
breakpoint.setSuspendPolicy(SuspendPolicy.NONE/THREAD/ALL)  // NONE + logExpression = 纯日志断点
breakpoint.setEnabled(boolean)
// XLineBreakpoint: getFileUrl() / getLine() / getType()
```

fileUrl 格式是 VFS URL（如 `file:///Users/x/src/Main.java`）：`VirtualFileManager.getInstance().findFileByNioPath(...)` → `virtualFile.url`。行号 **0-based**（编辑器内部行号），MCP 接口对外应使用 1-based 并在边界转换——务必在文档中写死这个约定。

### 2.7 暂停后读取状态：帧、变量、求值（异步回调模型）

来源：`platform/xdebugger-api/src/com/intellij/xdebugger/frame/XExecutionStack.java`、`XStackFrame.java`、`XValueContainer.java`、`XValue.java`、`evaluation/XDebuggerEvaluator.java`（master 已查证）。

调用链：`XDebugSession.getSuspendContext()` → `XSuspendContext.getActiveExecutionStack()` / `computeExecutionStacks(XExecutionStackContainer)`（多线程列表）→ `XExecutionStack` → 帧 → 变量。

```java
// XExecutionStack
public abstract @Nullable XStackFrame getTopFrame()
public abstract void computeStackFrames(int firstFrameIndex, XStackFrameContainer container)
// javadoc（已查证）：computeStackFrames "is called from the Event Dispatch Thread so it should return quickly"
//（实现应立即把重活丢到后台，结果通过 container 异步回调）
interface XStackFrameContainer extends XValueCallback {
    void addStackFrames(@NotNull List<? extends XStackFrame> stackFrames, boolean last)
    void errorOccurred(@NotNull String error)   // 继承自 XValueCallback
}

// XStackFrame extends XValueContainer
public @Nullable XDebuggerEvaluator getEvaluator()
public @Nullable XSourcePosition getSourcePosition()
public @Nullable Object getEqualityObject()
public void customizePresentation(@NotNull ColoredTextContainer component)
// 继承：void computeChildren(@NotNull XCompositeNode node)   // 变量列表，异步
```

变量（`XValue extends XValueContainer`）：

- `computeChildren(XCompositeNode node)` → `node.addChildren(XValueChildrenList, last)` 异步返回子节点（对象字段/数组元素，注意平台会分页，`XCompositeNode.tooManyChildren(remaining)`）。
- **XValue 转文本**：`xValue.computePresentation(XValueNode node, XValuePlace place)`；实现一个哑 `XValueNode`，在 `setPresentation(icon, type, value, hasChildren)` 回调中截获 `type`/`value` 字符串。更简单的通道：若 value 是 `XNamedValue` 取 `getName()`；对 Java 调试器，value 呈现最终来自 JDI，字符串化结果与 Variables 视图一致。
- 另有 `XValue.calculateEvaluationExpression(): Promise<XExpression>` 可反查表达式。

表达式求值（已查证签名）：

```java
// XDebuggerEvaluator
public abstract void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback,
                              @Nullable XSourcePosition expressionPosition)
public void evaluate(@NotNull XExpression expression, @NotNull XEvaluationCallback callback,
                     @Nullable XSourcePosition expressionPosition)

interface XEvaluationCallback extends XValueCallback {
    void evaluated(@NotNull XValue result);
    void errorOccurred(@NotNull String error);          // 来自 XValueCallback
    default void invalidExpression(@NotNull String error)
}
```

evaluator 从 `session.getCurrentStackFrame()?.getEvaluator()` 获取（未暂停时通常为 null → MCP 工具应返回 "session not suspended" 错误）。Java 调试器的求值在 debugger manager thread 上执行，回调线程不确定。

**Kotlin 桥接草图（异步→suspend）**：

```kotlin
suspend fun evaluate(session: XDebugSession, expr: String, timeoutMs: Long = 10_000): String {
    val frame = session.currentStackFrame ?: error("not suspended")
    val evaluator = frame.evaluator ?: error("no evaluator")
    return withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            evaluator.evaluate(expr, object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    result.computePresentation(TextCapturingValueNode { text -> cont.resume(text) },
                                               XValuePlace.TREE)
                }
                override fun errorOccurred(error: String) {
                    cont.resumeWithException(EvaluationException(error))
                }
            }, frame.sourcePosition)
        }
    }
}
```

**安全提示**：表达式求值 ≈ 在被调试 JVM 内执行任意代码（可调用任意方法），必须限定为 Idectl 的"管理员"角色权限。

### 2.8 临时/程序化创建 RunConfiguration

完全可行，两种粒度：

**a) 强类型创建（Application 为例，依赖 com.intellij.java）**：

```kotlin
val runManager = RunManager.getInstance(project)
val settings = runManager.createConfiguration("Idectl: Main", ApplicationConfigurationType::class.java)
(settings.configuration as ApplicationConfiguration).apply {
    mainClassName = "com.example.Main"
    setModule(module)                 // 或 configurationModule.module = ...
    programParameters = "--foo"
}
settings.isTemporary = true           // 走临时配置通道，不污染用户列表
runManager.addConfiguration(settings) // 或 runManager.setTemporaryConfiguration(settings)
ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

JUnit 方法级配置：`JUnitConfiguration`（plugins/junit）有 `beginMethod/setMainClass` 类的 data 设置接口，但字段较绕；更省事的路线是走 **RunConfigurationProducer / ConfigurationContext**（`ConfigurationContext.getConfigurationsFromContext()`），从 PsiMethod 的 context 自动产出配置——与用户右键 Run 同源，兼容 JUnit4/5/Gradle runner 差异。

**b) 不落库直接执行**：`createConfiguration` 后不 `addConfiguration`，直接 `ExecutionEnvironmentBuilder.create(executor, settings).buildAndExecute()`。配置不会出现在 UI 下拉框（但 Run tab 会出现）。

**c) Remote JVM Debug 程序化创建**：`RemoteConfigurationType`（id "Remote"）是 OSS 的，`RemoteConfiguration` 有 `HOST/PORT/USE_SOCKET_TRANSPORT/SERVER_MODE` 公有字段，可编程创建后用 Debug executor 启动——这是"让 Agent attach 到任意 JVM"的通道。

---

## 3. 线程模型注意事项

| 操作 | 要求 | 说明 |
|---|---|---|
| 读 RunManager（allSettings 等） | 任意线程 | 内部有锁；但涉及 PSI 的配置校验需 ReadAction |
| createConfiguration / addConfiguration | 任意线程（建议 EDT） | 无硬断言；写配置伴随 UI 刷新，EDT 最稳 |
| 启动配置（ExecutionUtil / ProgramRunnerUtil / buildAndExecute） | **EDT**（ExecutionUtil 可后台，内部自己切） | 统一 `withContext(Dispatchers.EDT)` 调用；2024.2+ 有 `Dispatchers.EDT`（IntelliJ 协程），或 `invokeLater` |
| EXECUTION_TOPIC / ProcessListener 回调 | 发布者线程，**不保证 EDT** | ProcessListener.onTextAvailable 在进程输出 pump 线程；回调内禁止阻塞、禁止直接做 PSI/VFS 写 |
| ProcessHandler.destroyProcess / detachProcess | 任意线程 | 立即返回，异步终止 |
| XDebugSession.pause/resume/step | 建议 EDT | 平台调用点均在 EDT；命令本身异步派发到 debugger manager thread |
| XBreakpointManager 增删断点 | 233+ 无 write-action 断言（ReentrantLock） | **仍建议 `WriteAction.runAndWait`（EDT）**，与平台自身入口一致、向后兼容 |
| computeStackFrames / computeChildren 调用侧 | 从 EDT 调用（javadoc 要求实现快速返回） | 结果异步回调；回调线程不定（Java 调试器为 debugger manager thread） |
| XDebuggerEvaluator.evaluate | 任意线程发起 | 回调线程不定；必须加超时（死锁的 JVM 会永不回调） |
| MCP server 请求处理 | 后台协程 | 模式：suspend fun → `withContext(Dispatchers.EDT)` 做启动/断点 → `suspendCancellableCoroutine` 桥接回调 → `withTimeout` |

补充：2024.2+ 平台协程规范（[Coroutine Execution Contexts](https://plugins.jetbrains.com/docs/intellij/execution-contexts.html)）推荐 service scope + `Dispatchers.EDT`/`readAction {}`；避免 `runBlocking` 在 EDT 上。

## 4. 版本兼容性（233 ~ 2026 最新）

- 本报告列出的 RunManager / ExecutionListener / ExecutionEnvironment(Builder) / ProgramRunnerUtil / ExecutionUtil / ProcessHandler / RunContentManager / XDebuggerManager / XDebugSession / XBreakpointManager 核心签名在 233 与 master 之间**均稳定存在**（ExecutionListener 各回调、EXECUTION_TOPIC、getRunningProcesses、addLineBreakpoint 等已分别在 master 源码验证，XBreakpointManagerImpl 另在 233 分支验证）。
- 变化点：
  - `ExecutionManager.getRunningDescriptors` 在新版本标注 `@ApiStatus.Internal` → 用 `RunContentManager.getAllDescriptors()` 替代。
  - `XDebuggerManager.startSession/startSessionAndShowTab` 已 `@Deprecated`，新 API 为 `newSessionBuilder()`（仅自实现 debug process 才用得到，Idectl 不需要）。
  - `XDebugSessionListener.stackFrameChanged(boolean)` 为 `@ApiStatus.Experimental`（较新版本新增）；`settingsChangedFromFrontend` 为 Internal（Remote Dev/前后端分离架构产物）。
  - `XBreakpointManager.findBreakpointsAtLine` 带 `XLineBreakpointVerticalPlacement` 的重载是新加的（inline breakpoints，2024.x+）；基础重载 233 可用。
  - 2024.3+ XDebugger 内部大量向 Remote Dev "split" 架构迁移（XDebugSessionProxy 等 Internal 类出现），**只用 xdebugger-api 公开接口即可免疫**。
  - `RunManager.addConfiguration(settings, storeInDotIdeaFolder)` 已 deprecated，用单参版本 + `settings.storeInDotIdeaFolder()`。
- Spring Boot / Tomcat 配置类型属 Ultimate 闭源插件，跨版本 id 稳定性无法从 OSS 源码确认（Spring Boot 的 id 多年为 `SpringBootApplicationConfigurationType`），实现时用 id 字符串 + 运行时探测，不做编译期依赖。

## 5. 已知坑与限制

1. **单实例确认对话框**：对 `isAllowRunningInParallel()==false` 的配置重复启动，平台可能弹 "Process is running, stop and restart?" 模态框，MCP 场景会卡死请求。规避：启动前自查该配置是否有 running descriptor，主动 stop-and-wait 或返回冲突错误（并发语义：把它做成 Idectl 的显式冲突码）。
2. **`processNotStarted`**：启动失败（如 runner 抛 ExecutionException、before-launch task 失败/被取消）走 `processNotStarted` 而非 exception 抛给调用方——MCP start 工具必须同时监听 started 与 notStarted，否则请求悬挂。
3. **before-launch tasks（默认含 Build）**：executeConfiguration 会先跑配置挂的 Build 任务，构建失败则 processNotStarted。这与 Idectl 的"构建互斥"设计相关：Agent 触发的 run 隐式触发构建，需与显式 build 工具共享同一互斥锁语义。
4. **控制台历史不可回读**：`ConsoleView` 不提供可靠的"读回全部已打印文本"公开 API。Idectl 必须**自建 ring buffer**：在 `processStarting(handler)` 挂 `ProcessListener.onTextAvailable(event, outputType)` 自己缓存（区分 stdout/stderr/system），offset/tail/grep 都在自建缓冲上做。对 MCP 连接之前就已在跑的会话，历史输出**拿不到**（只能从挂上 listener 那刻起），插件应在 IDE 启动即订阅而不是首个 MCP 连接时才订阅。
5. **行号基准不一致**：XLineBreakpoint/Document 是 0-based，编辑器 UI 与用户直觉是 1-based。协议里必须写清并统一转换。
6. **断点不等于可命中**：`addLineBreakpoint` 对任何行都会成功；无效行的断点在会话中显示为灰/叉。可用 `JavaLineBreakpointType.canPutAt(file, line, project)`（需 ReadAction，PSI）先校验。
7. **求值超时与副作用**：evaluate 回调可能永不到达（目标 JVM 全线程挂起于非断点状态、死锁）；且求值可触发副作用/类加载。必须 `withTimeout` + 权限门槛 + 审计日志。
8. **computeChildren 分页**：大集合平台默认每批 100 个子节点（`XCompositeNode.tooManyChildren`），MCP 变量工具要处理 "has more" 语义，避免一次拉爆。
9. **detach vs destroy 对 Remote Debug**：Remote JVM Debug 会话 stop 的默认语义是 detach（目标进程继续跑）；本地进程是 destroy。MCP stop 工具的文案与行为要按 descriptor 类型区分。
10. **多项目路由**：所有 service（RunManager/ExecutionManager/XDebuggerManager/RunContentManager）与两个 Topic 全是 project-level。Idectl 必须为每个 open project 建立独立订阅与注册表，`ProjectManager.getInstance().openProjects` 枚举 + `ProjectManagerListener` 跟踪开关；MCP 会话绑定 project 后所有句柄查找都限定在该 project 的注册表内。
11. **executionId 复用**：Rerun 时若未 `assignNewExecutionId`，id 可能沿用；Idectl 最好用自己的 UUID 做 MCP session handle，内部映射到 `(project, executionId, processHandler)`，以 processHandler 身份为准。
12. **JetBrains 内置 MCP server 撞车**：intellij-community 已内置 `plugins/mcp-server`（`com.intellij.mcpserver`，含运行配置执行工具与 ExecutionUtilTest）。设计阶段应：a) 通读其工具集避免语义冲突；b) 考虑端口/命名隔离；c) 评估"扩展官方 MCP server 的 EP"替代自建 server 的可行性。

## 6. 参考来源

1. IntelliJ Platform SDK – Execution: https://plugins.jetbrains.com/docs/intellij/execution.html
2. IntelliJ Platform SDK – Run Configurations: https://plugins.jetbrains.com/docs/intellij/run-configurations.html
3. RunManager.kt（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/RunManager.kt
4. ExecutionListener.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/ExecutionListener.java
5. ExecutionManager.kt（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/ExecutionManager.kt
6. ExecutionEnvironmentBuilder.kt（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/runners/ExecutionEnvironmentBuilder.kt
7. ExecutionEnvironment.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/runners/ExecutionEnvironment.java
8. ProgramRunnerUtil.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution-impl/src/com/intellij/execution/ProgramRunnerUtil.java
9. ExecutionUtil.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/runners/ExecutionUtil.java
10. RunContentManager.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/ui/RunContentManager.java
11. ProcessHandler.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessHandler.java
12. XDebuggerManager.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebuggerManager.java
13. XDebugSession.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSession.java
14. XDebugSessionListener.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSessionListener.java
15. XBreakpointManager.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/breakpoints/XBreakpointManager.java
16. XBreakpointManagerImpl.java（master 与 233 分支）: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-impl/src/com/intellij/xdebugger/impl/breakpoints/XBreakpointManagerImpl.java
17. JavaLineBreakpointType.java（master，type id "java-line"）: https://github.com/JetBrains/intellij-community/blob/master/java/debugger/impl/src/com/intellij/debugger/ui/breakpoints/JavaLineBreakpointType.java
18. XDebuggerEvaluator.java（master）: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/evaluation/XDebuggerEvaluator.java
19. XExecutionStack.java / XStackFrame.java（master）: https://github.com/JetBrains/intellij-community/tree/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame
20. ApplicationConfigurationType.java（id "Application"）: https://github.com/JetBrains/intellij-community/blob/master/java/execution/impl/src/com/intellij/execution/application/ApplicationConfigurationType.java
21. RemoteConfigurationType.java（id "Remote"）: https://github.com/JetBrains/intellij-community/blob/master/java/execution/impl/src/com/intellij/execution/remote/RemoteConfigurationType.java
22. JetBrains 内置 MCP server 插件（prior art）: https://github.com/JetBrains/intellij-community/tree/master/plugins/mcp-server

---

## 附录 A · HotSwap（热加载）API 核实（2026-07-04 主会话补充）

来源：intellij-community master 一手源码（raw.githubusercontent.com 直接核对）。

- `java/debugger/impl/src/com/intellij/debugger/ui/HotSwapUI.java`（包 `com.intellij.debugger.ui`，**java debugger impl 模块，非 openapi**）：
  - `public static HotSwapUI getInstance(Project project)`
  - `public abstract void reloadChangedClasses(@NotNull DebuggerSession session, boolean compileBeforeHotswap)`
  - `public abstract void reloadChangedClasses(@NotNull DebuggerSession session, boolean compileBeforeHotswap, @Nullable HotSwapStatusListener callback)`
  - `public void reloadChangedClasses(@NotNull DebuggerSession session, boolean compileBeforeHotswap, @Nullable HotSwapStatusListener callback, @Nullable HotSwapSource source)`
  - `public abstract void compileAndReload(@NotNull DebuggerSession session, VirtualFile @NotNull ... files)`（注意：**无 listener 重载**——需要结构化结果时应组合 `ProjectTaskManager.compile(files).await()` + `reloadChangedClasses(session, false, listener)`）
  - `addListener/removeListener(HotSwapVetoableListener)`
- `java/debugger/impl/src/com/intellij/debugger/ui/HotSwapStatusListener.java`：
  `onSuccess / onNothingToReload / onFailure / onCancel`，均为 `default` 方法、参数 `List<DebuggerSession>`。
- `DebuggerSession` 获取：`DebuggerManagerEx.getInstanceEx(project)` 的 sessions，与执行会话经 `process.processHandler` 关联。
- 能力边界：标准 JDWP `redefineClasses`——仅方法体内改动；签名/增删方法/字段/类的变更会失败（DCEVM / JBR `-XX:+AllowEnhancedClassRedefinition` 可放宽，列观察项）。
- 待实测：`DebuggerSettings.RUN_HOTSWAP`（ask/always/never）对程序化 `reloadChangedClasses` 路径是否仍可能触发确认弹窗；`onFailure` 无逐类失败明细（详情走 HotSwapProgress/IDE 通知）。
