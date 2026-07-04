# 调研报告 01：IntelliJ 平台"构建/编译"控制 API

> IdeaBridge 设计调研 · 2026-07-03 · 目标平台：IDEA 2024.2+（sinceBuild=233，无 untilBuild）
> 所有签名均核对自 JetBrains/intellij-community master 分支源码（一手资料），来源见文末。

---

## 1. 概述

IntelliJ 平台的"构建"能力分为三层：

1. **`com.intellij.task.ProjectTaskManager`（推荐入口，platform/lang-api）**
   现代统一的构建门面。IDE"Build"菜单的四个动作最终都调用它。它把 `ProjectTask` 分发给
   `ProjectTaskRunner` 扩展点（EP `com.intellij.projectTaskRunner`）——JPS 内建 runner、
   `GradleProjectTaskRunner`、`MavenProjectTaskRunner` 等——因此**无论构建是否委托给
   Gradle/Maven，调用方式与返回的 `Promise<Result>` 都一致**。这正是 IdeaBridge 的
   `build_project` / `build_module` / `recompile_files` MCP 工具应当依赖的抽象。

2. **`com.intellij.openapi.compiler.CompilerManager`（java/compiler/openapi，仅 JPS）**
   较老的 Java 编译门面（`make`/`compile`/`rebuild` + `CompileStatusNotification` 回调）。
   它的价值在于**回调携带 `CompileContext`，能拿到结构化编译消息**
   （`getMessages(CompilerMessageCategory)`），这是 `ProjectTaskManager.Result` 做不到的。
   但它只覆盖 JPS 构建；构建委托给 Gradle 时不会触发。

3. **`com.intellij.build.*` 构建事件/视图层（Build 工具窗口背后）**
   `BuildViewManager`（继承 `AbstractViewManager`，实现 `BuildProgressListener`）接收所有
   进入 Build 工具窗口的 `BuildEvent`。通过 `addListener` 订阅可获得**跨构建系统统一的结构化
   事件流**：`StartBuildEvent`/`FinishBuildEvent`/`OutputBuildEvent`/`ProgressBuildEvent`/
   `MessageEvent`/`FileMessageEvent`（含 `FilePosition` 文件+行+列）。这是**同时兼容 JPS 与
   Gradle/Maven 委托构建的错误收集通道**，推荐作为 IdeaBridge 结构化诊断的主通道。

**IdeaBridge 推荐组合**：`ProjectTaskManager` 发起构建并等待 `Promise<Result>`（成败/中止判定）
+ 同一时间窗内订阅 `BuildViewManager` 的 `BuildProgressListener` 收集 `FileMessageEvent`
（文件/行/列/severity）；JPS 场景可叠加 `CompilerTopics.COMPILATION_STATUS` 取
`CompileContext.getMessages(...)` 交叉校验。

---

## 2. 关键 API 详解

### 2.1 ProjectTaskManager（`com.intellij.task.ProjectTaskManager`，abstract class）

源码：`platform/lang-api/src/com/intellij/task/ProjectTaskManager.java`。
类 Javadoc："Provides services to build project, modules, files or artifacts and execute Run Configuration."

```java
public static ProjectTaskManager getInstance(Project project)   // project.getService(...)

// 全部返回 org.jetbrains.concurrency.Promise<ProjectTaskManager.Result>
public abstract Promise<Result> run(@NotNull ProjectTask projectTask);
public abstract Promise<Result> run(@NotNull ProjectTaskContext context, @NotNull ProjectTask projectTask);

public abstract Promise<Result> buildAllModules();
//  "Build all modules with modified files and all modules with files that depend on them all over the project."
public abstract Promise<Result> rebuildAllModules();
//  "Rebuild the whole project modules from scratch."
public abstract Promise<Result> build(Module @NotNull ... modules);
//  "Build modules and all modules these modules depend on recursively."
public abstract Promise<Result> rebuild(Module @NotNull ... modules);
public abstract Promise<Result> compile(VirtualFile @NotNull ... files);
//  "Compile a set of files."（若 VirtualFile 是目录，则处理其中所有文件）
public abstract Promise<Result> build(ProjectModelBuildableElement @NotNull ... buildableElements);   // 如 Artifact
public abstract Promise<Result> rebuild(ProjectModelBuildableElement @NotNull ... buildableElements);

// 组装自定义 ProjectTask（可与 run(context, task) 搭配，精细控制增量/依赖/测试范围）
public abstract ProjectTask createAllModulesBuildTask(boolean isIncrementalBuild, Project project);
public ProjectTask createModulesBuildTask(Module module, boolean isIncrementalBuild,
                                          boolean includeDependentModules, boolean includeRuntimeDependencies);
public abstract ProjectTask createModulesBuildTask(Module[] modules, boolean isIncrementalBuild,
                                          boolean includeDependentModules, boolean includeRuntimeDependencies,
                                          boolean includeTests);
public abstract ProjectTask createBuildTask(boolean isIncrementalBuild, ProjectModelBuildableElement... artifacts);

public abstract @Nullable ExecutionEnvironment createProjectTaskExecutionEnvironment(@NotNull ProjectTask projectTask);
```

**Result 接口**（`ProjectTaskManager.Result` 内部接口）：

```java
interface Result {
  @NotNull ProjectTaskContext getContext();
  boolean isAborted();
  boolean hasErrors();
  @ApiStatus.Experimental
  boolean anyTaskMatches(@NotNull BiPredicate<? super ProjectTask, ? super ProjectTaskState> predicate);
}
```

- **注意：`Result` 只回答"是否有错/是否中止"，不携带错误内容清单**（JetBrains 支持论坛也确认
  此限制）。错误清单必须另行通过 §2.3 / §2.6 的通道收集。
- 旧的回调式 API `buildAllModules(ProjectTaskNotification callback)` 等已被 Promise 版取代并
  **在 2021.1（IDEA-262168）后删除**；233+ 目标下只有 Promise 版，无兼容包袱。

**ProjectTaskContext**（`platform/lang-api/src/com/intellij/task/ProjectTaskContext.kt`，已 Kotlin 化）：

```kotlin
class ProjectTaskContext(
  val sessionId: Any? = null,                 // 会话标识——IdeaBridge 可放入自己的关联 ID
  val runConfiguration: RunConfiguration? = null,
  val isAutoRun: Boolean = false,             // true 表示任务由自动构建触发
  val dataContext: DataContext? = null,
) : UserDataHolderBase() {
  constructor(autoRun: Boolean)
  var isCollectionOfGeneratedFilesEnabled: Boolean          // @Experimental
  val generatedFilesRoots: Collection<String>
  fun getGeneratedFilesRelativePaths(root: String): Collection<String?>
  fun fileGenerated(root: String, relativePath: String)
  fun addDirtyOutputPathsProvider(outputPathsProvider: () -> Collection<String>)
  val dirtyOutputPaths: Optional<List<String>>
  fun <T> withUserData(key: Key<T>, value: T?): ProjectTaskContext
}
```

`sessionId` + `withUserData` 是 IdeaBridge 把"哪个 Agent 发起的构建"贯穿到监听器的天然载体：
`run(ProjectTaskContext(sessionId = mcpRequestId), createAllModulesBuildTask(true, project))`。

**ProjectTaskListener**（`com.intellij.task.ProjectTaskListener`，项目级消息总线 Topic）：

```java
@Topic.ProjectLevel
Topic<ProjectTaskListener> TOPIC = new Topic<>("project task events", ProjectTaskListener.class);
default void started(@NotNull ProjectTaskContext context) {}
default void finished(@NotNull ProjectTaskManager.Result result) {}
```

订阅它可捕获**所有**构建任务（包括用户手点菜单触发的），配合 `context.sessionId` 区分来源。

### 2.2 CompilerManager（`com.intellij.openapi.compiler.CompilerManager`，仅 JPS）

源码：`java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerManager.java`。

```java
public static CompilerManager getInstance(@NotNull Project project);

public abstract void compile(VirtualFile @NotNull [] files, @Nullable CompileStatusNotification callback);
public abstract void compile(@NotNull Module module, @Nullable CompileStatusNotification callback);
public abstract void compile(@NotNull CompileScope scope, @Nullable CompileStatusNotification callback);

public abstract void make(@Nullable CompileStatusNotification callback);                       // 全项目增量
public abstract void make(@NotNull Module module, @Nullable CompileStatusNotification callback);
public abstract void make(@NotNull Project project, Module @NotNull [] modules, @Nullable CompileStatusNotification callback);
public abstract void make(@NotNull CompileScope scope, @Nullable CompileStatusNotification callback);
public abstract void makeWithModalProgress(@NotNull CompileScope scope, @Nullable CompileStatusNotification callback);

public abstract void rebuild(@Nullable CompileStatusNotification callback);
public void rebuildClean(@Nullable CompileStatusNotification callback);

public abstract boolean isUpToDate(@NotNull CompileScope scope);
public abstract boolean isUpToDate(@NotNull CompileScope scope, @NotNull ProgressIndicator progress);
public abstract boolean isCompilationActive();

@Deprecated  // "Use CompilerTopics#COMPILATION_STATUS instead"
public abstract void addCompilationStatusListener(@NotNull CompilationStatusListener listener);
```

`CompileStatusNotification.finished(boolean aborted, int errors, int warnings, CompileContext compileContext)`
——**`CompileContext` 就在这里到手**，可立即 `getMessages(...)`。

### 2.3 CompilationStatusListener / CompilerTopics / CompileContext / CompilerMessage

**CompilerTopics**（`java/compiler/openapi/.../CompilerTopics.java`）：

```java
public static final Topic<CompilationStatusListener> COMPILATION_STATUS =
    new Topic<>(CompilationStatusListener.class, Topic.BroadcastDirection.NONE);
```

项目级订阅：`project.messageBus.connect(disposable).subscribe(CompilerTopics.COMPILATION_STATUS, listener)`。

**CompilationStatusListener**（三个 default 方法，可选择性覆写）：

```java
// Javadoc: "Invoked in a Swing dispatch thread after the compilation is finished"（即 EDT）
default void compilationFinished(boolean aborted, int errors, int warnings, @NotNull CompileContext compileContext) {}
default void automakeCompilationFinished(int errors, int warnings, @NotNull CompileContext compileContext) {}
default void fileGenerated(@NotNull String outputRoot, @NotNull String relativePath) {}
```

注意实现细节：`CompilerManagerImpl` 用 `syncPublisher(CompilerTopics.COMPILATION_STATUS)` 同步
发布，实际线程 = `CompileDriver` 调用 `finished()` 的线程；`compilationFinished` 的 Javadoc 契约
是 EDT（CompileDriver 在 EDT 上发通知），`automakeCompilationFinished` 来自后台自动构建，**不要
假定在 EDT**——监听器内一律做线程转发最稳妥。

**CompileContext**（`java/compiler/openapi/.../CompileContext.java`，extends UserDataHolder）：

```java
void addMessage(@NotNull CompilerMessageCategory category, @Nls String message,
                @Nullable String url, int lineNum, int columnNum);            // line/column "-1 if not available"
void addMessage(...同上, @Nullable Navigatable navigatable);
void addMessage(...同上, @Nullable Navigatable navigatable, Collection<String> moduleNames);
default void addMessage(@NotNull CompilerMessage message);

CompilerMessage @NotNull [] getMessages(@NotNull CompilerMessageCategory category);
int getMessageCount(@Nullable CompilerMessageCategory category);   // null = 全部
@NotNull ProgressIndicator getProgressIndicator();
CompileScope getCompileScope();
CompileScope getProjectCompileScope();
Module getModuleByFile(@NotNull VirtualFile file);
@Nullable VirtualFile getModuleOutputDirectory(@NotNull Module module);
@Nullable VirtualFile getModuleOutputDirectoryForTests(Module module);
boolean isMake();
boolean isAutomake();
boolean isRebuild();
boolean isAnnotationProcessorsEnabled();
@NotNull Project getProject();
```

`CompilerMessageCategory` 枚举：`ERROR / WARNING / INFORMATION / STATISTICS`。

**CompilerMessage 接口**（重要陷阱）：

```java
CompilerMessageCategory getCategory();
@Nls String getMessage();
Navigatable getNavigatable();      // 定位消息源（通常是 OpenFileDescriptor，内含 line/column）
VirtualFile getVirtualFile();
String getExportTextPrefix();      // 导出文本时的位置前缀，如 "line: N"
String getRenderTextPrefix();
default Collection<String> getModuleNames();   // 模块名（或循环依赖中的多个模块名）
```

**接口本身没有 `getLine()/getColumn()`**。行列号获取途径：
1. 将 `getNavigatable()` 强转为 `OpenFileDescriptor`，取 `getLine()/getColumn()`（0 基）；
2. 强转实现类 `com.intellij.compiler.CompilerMessageImpl`（`java/compiler/impl/src/com/intellij/compiler/CompilerMessageImpl.java`），
   其有 `getLine()/getColumn()`（1 基，缺省 -1）——但这是 impl 包，非稳定 API；
3. **首选 §2.6 的 `FileMessageEvent.getFilePosition()`**，跨构建系统且行列语义明确。

**与 ProblemsView 的关系**：JPS/automake 的错误经内部类 `com.intellij.compiler.ProblemsView`
（impl，非 SDK 公开）推入 Problems 工具窗口的 "Project Errors" 标签（2020.3 引入该 UI）。
现代公开 API 是 `com.intellij.analysis.problemsView.ProblemsCollector` / `ProblemsProvider`（用于
*贡献*问题）；**Problems 视图不提供读取已有问题列表的公开 API**（官方答复：只能参考
`ProjectErrorsCollector`/`HighlightingWatcher` 自行实现）。IdeaBridge 不应依赖 ProblemsView 读数，
而应在构建回调/事件中自行收集。另注意 ProblemsView/编译子系统与 Java 插件强绑定（WebStorm 等
轻量 IDE 无此能力）——与我们 `bundledPlugin("com.intellij.java")` 的约束一致。

### 2.4 与 Build 菜单四个动作的对应关系（核对自动作源码）

| 菜单动作（快捷键） | Action 类（java/compiler/impl/.../actions） | 实际调用 |
|---|---|---|
| Build Project ⌘F9 | `CompileDirtyAction` | `ProjectTaskManager.getInstance(project).buildAllModules()` |
| Build Module(s) | `MakeModuleAction` | `ProjectTaskManager.getInstance(project).build(modules)` |
| Recompile 单文件 ⇧⌘F9 | `CompileAction` | 文件：`...compile(files)`；选中模块时：`...rebuild(module)` |
| Rebuild Project | `CompileProjectAction` | `ProjectTaskManager.getInstance(project).rebuildAllModules()` |

即：IdeaBridge 直接调用同名 `ProjectTaskManager` 方法即可获得与 IDE 菜单**逐字节一致**的语义
（包括 delegated build 路由），无需 `ActionManager.tryToExecute` 模拟按键。

### 2.5 推荐编程模式：发起构建、等待完成、收集错误

```kotlin
// 服务级单例，随 MCP 会话生命周期注册
class BuildController(private val project: Project, parentDisposable: Disposable) {

  fun buildProject(sessionId: String): CompletableFuture<BuildOutcome> {
    val future = CompletableFuture<BuildOutcome>()
    val messages = ConcurrentLinkedQueue<Diag>()

    // 通道 A（跨构建系统）：订阅 Build 工具窗口事件流
    val connectionDisposable = Disposer.newDisposable(parentDisposable)
    project.service<BuildViewManager>().addListener(
      BuildProgressListener { buildId, event ->
        when (event) {
          is FileMessageEvent -> messages += Diag(
            kind = event.kind,                             // ERROR/WARNING/INFO/STATISTICS/SIMPLE
            file = event.filePosition.file.path,
            line = event.filePosition.startLine,           // 0 基
            column = event.filePosition.startColumn,
            text = event.message)
          is FinishBuildEvent -> { /* event.result: SuccessResult/FailureResult/SkippedResult */ }
          else -> {}
        }
      }, connectionDisposable)

    // 通道 B（仅 JPS 时命中）：结构化 CompilerMessage
    project.messageBus.connect(connectionDisposable).subscribe(
      CompilerTopics.COMPILATION_STATUS, object : CompilationStatusListener {
        override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, ctx: CompileContext) {
          ctx.getMessages(CompilerMessageCategory.ERROR).forEach { messages += it.toDiag() }
          ctx.getMessages(CompilerMessageCategory.WARNING).forEach { messages += it.toDiag() }
        }
      })

    // 发起构建；context.sessionId 让监听端能按发起方路由
    val context = ProjectTaskContext(sessionId, null, false, null)
    val task = ProjectTaskManager.getInstance(project).createAllModulesBuildTask(/*incremental=*/true, project)
    ProjectTaskManager.getInstance(project).run(context, task)
      .onSuccess { result ->            // 在 EDT 上回调（见下）
        future.complete(BuildOutcome(result.isAborted, result.hasErrors(), messages.toList()))
        Disposer.dispose(connectionDisposable)
      }
      .onError { t ->
        future.completeExceptionally(t); Disposer.dispose(connectionDisposable)
      }
    return future
  }
}
```

要点（核对自 `ProjectTaskManagerImpl.java`，platform/lang-impl）：

- **回调线程**：Promise 在 EDT 上 resolve——
  `ModalityUiUtil.invokeLaterIfNeeded(ModalityState.defaultModalityState(), () -> { myEventPublisher.finished(result); myPromise.setResult(result); })`。
  即 `onSuccess` 与 `ProjectTaskListener.finished` 都在 EDT；内部 `ProjectTaskManagerListener.afterRun`
  则在 pooled 线程。**不要在 onSuccess 里做耗时/阻塞工作**，立刻转交后台协程。
- **发起线程**：`ProjectTaskManagerImpl.run` 若发现自己在 EDT，会把实际工作
  `executeOnPooledThread`；从任意线程调用都安全（但从 MCP 服务器线程发起时无需 ReadAction）。
- **能否并发发起两次构建**：`ProjectTaskManagerImpl` 层面**不排队不互斥**（每次调用独立走
  runner + `ProjectTaskResultsAggregator`）。真正的互斥在下层：JPS 的 `CompilerManagerImpl` 持
  `private final Semaphore myCompilationSemaphore = new Semaphore(1, true)`（公平信号量，单许可），
  `isCompilationActive()` 即 `availablePermits()==0`——**同一项目的 JPS 编译串行化**，第二个请求
  阻塞等待而非失败。Gradle 委托构建则由 Gradle 侧排队。IdeaBridge 建议自建"项目级构建互斥/队列 +
  排队状态上报"，不要依赖底层隐式阻塞（这也是多 Agent 并发语义的需求点）。
- **取消构建**：`Promise` 是 `CancellablePromise`（AsyncPromise），但 **cancel 该 Promise 并不保证
  中止底层构建进程**——平台未公开"取消进行中构建"的稳定 API（UI 上的停止按钮走内部
  ProgressIndicator）。JPS 场景可尝试通过 `CompileContext.getProgressIndicator().cancel()`（需先拿到
  context，即只能在自定义 CompileTask/监听器中）。设计上应把 `build_cancel` 标记为 best-effort，
  实现期验证（见 open questions）。
- **同步等待**：`ProjectTaskManagerImpl.waitForPromise` 内部以 `promise.blockingGet(10, MILLISECONDS)`
  轮询 + `ProgressManager.checkCanceled()`。插件侧若需同步等待，务必在后台线程做同样的
  轮询式 blockingGet，**严禁在 EDT 上 blockingGet**（死锁：resolve 本身要 invokeLater 回 EDT）。

### 2.6 构建进度/事件：BuildViewManager 与 BuildProgressListener

- `com.intellij.build.BuildViewManager`（`platform/lang-impl/src/com/intellij/build/BuildViewManager.java`）
  `public class BuildViewManager extends AbstractViewManager`，项目级服务；另有实验性静态工厂
  `public static BuildProgress<BuildProgressDescriptor> createBuildProgress(@NotNull Project project)`
  （供自己*发布*进度）。
- 父类 `AbstractViewManager implements ViewManager, BuildProgressListener, BuildProgressObservable, Disposable`
  提供订阅入口：
  `public void addListener(@NotNull BuildProgressListener listener, @NotNull Disposable disposable)`
  与事件入口 `public void onEvent(@NotNull Object buildId, @NotNull BuildEvent event)`。
- `BuildProgressListener`（函数式接口）：`void onEvent(@NotNull Object buildId, @NotNull BuildEvent event)`。
- 事件类型（`com.intellij.build.events.*`）：`StartBuildEvent`、`FinishBuildEvent`（`getResult()`:
  SuccessResult/FailureResult/DerivedResult/SkippedResult）、`ProgressBuildEvent`、`OutputBuildEvent`
  （原始输出行）、`MessageEvent`（`Kind` 枚举：**ERROR, WARNING, INFO, STATISTICS, SIMPLE**；
  `getKind()/getGroup()/getNavigatable(Project)/getResult()`）、`FileMessageEvent extends MessageEvent`
  （`@NotNull FilePosition getFilePosition()`——`FilePosition(File, startLine, startColumn, endLine, endColumn)`，
  行列 0 基）。
- **JPS 构建输出与 Gradle/Maven 委托构建输出都会流经各自项目的 BuildViewManager**（Build 工具
  窗口即由它驱动），故这是唯一"构建系统无关"的结构化错误/进度订阅点。事件回调线程不保证是
  EDT，监听器须线程安全。
- 注意 `buildId` 区分并发构建；`StartBuildEvent` 可拿到 `BuildDescriptor`（标题、工作目录、
  开始时间）用于把事件流关联回发起它的 MCP 会话。
- 2025.x 起平台出现 `platform/buildView/frontend/.../FrontendBuildViewManager.kt`（Remote Dev/前后端
  拆分产物），本地插件场景仍用 `BuildViewManager`，但提示该区域在演进中，避免依赖其 impl 细节。

### 2.7 Delegated build（Gradle/Maven）与 JPS、自动构建

- **路由机制**：`ProjectTaskRunner.EP_NAME.getExtensions()` 按序取第一个 `canRun(project, task, context)`
  为 true 的 runner。
  - `GradleProjectTaskRunner`（`plugins/gradle/java/src/execution/build/GradleProjectTaskRunner.kt`，已 Kotlin 化）：
    `override fun canRun(project, projectTask, context): Boolean` 对 `ModuleBuildTask` 检查该模块
    是否启用了 Gradle 委托构建（Settings → Build Tools → Gradle → "Build and run using: Gradle"，
    对应 `GradleProjectSettings.delegatedBuild`，**可按 linked project 粒度设置**）；
    `override fun run(project, context, vararg tasks): Promise<Result>` 内部用
    `project.gradleCoroutineScope.async<Result>` 执行 Gradle 任务，异常→FAILURE。
  - `MavenProjectTaskRunner`（`plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/build/MavenProjectTaskRunner.java`）：
    `public final class MavenProjectTaskRunner extends ProjectTaskRunner`，`canRun` 取决于
    `MavenRunner.getInstance(project).getSettings().isDelegateBuildToMaven()`（默认 false，即 Maven
    项目默认仍走 JPS）；`public Promise<Result> run(@NotNull Project, @NotNull ProjectTaskContext, ProjectTask @NotNull ...)`。
- **行为差异**：
  1. `Promise<Result>` 契约不变（这是 ProjectTaskManager 的价值），`isAborted/hasErrors` 依旧可用；
  2. 但 **`CompilerTopics.COMPILATION_STATUS` 不触发、`CompileContext.getMessages` 无数据**
     （没有 JPS 编译发生）——错误只能从 BuildViewManager 的 `FileMessageEvent`（Gradle 输出被
     平台解析为带 FilePosition 的消息）或原始 `OutputBuildEvent` 获取；
  3. `compile(VirtualFile...)`（单文件重编译）在 Gradle 委托下由 Gradle 任务近似实现，粒度和
     速度不同于 JPS 的单文件编译；
  4. `CompilerManager.isCompilationActive()` 只反映 JPS，探测"是否正在构建"应以
     BuildViewManager 的 Start/Finish 事件或自建互斥为准。
- **JPS 构建 vs 后台自动构建（automake）**：automake（Settings → Build project automatically）
  由 JPS 在后台增量执行，完成回调是 `automakeCompilationFinished(errors, warnings, context)`，
  且 `CompileContext.isAutomake()==true`、`ProjectTaskContext.isAutoRun==true`。automake **不写
  Build 工具窗口**，错误进 Problems 视图；IdeaBridge 若把 automake 结果也上报给 Agent，需单独
  订阅该回调并打上 auto 标记，避免与显式构建请求混淆。
- **External Builder（JPS）架构**：显式构建在**独立的外部进程**中执行（SDK 文档 "External Builder
  API and Plugins"）：before-compile task（IDE 进程）→ `BuildTargetScopeProvider` 计算范围 → 启动/
  复用外部构建进程加载 `JpsModel` → 消息经协议回传 IDE。因此编译错误消息是进程间回传的产物，
  行为上偶有延迟；也解释了为何取消要走 ProgressIndicator/进程终止。

---

## 3. 线程模型注意事项

| 场景 | 线程要求 |
|---|---|
| `ProjectTaskManager.getInstance(project).buildAllModules()` 等发起调用 | 任意线程可调（实现会自动切 pooled）；无需 ReadAction/WriteAction |
| `Promise.onSuccess/onError`、`ProjectTaskListener.started/finished` | **EDT**（`invokeLaterIfNeeded` + defaultModalityState）；勿做耗时工作，立即切回 MCP 后台线程/协程（`withContext(Dispatchers.Default)` 或 `AppExecutorUtil`） |
| `CompilationStatusListener.compilationFinished` | Javadoc 契约 EDT；`automakeCompilationFinished` 不保证 EDT |
| `BuildProgressListener.onEvent` | 不保证 EDT，高频调用，须无锁/低开销、线程安全收集 |
| `CompilerManager.compile/make/rebuild` 发起 | 历史上要求从 EDT 发起（内部 CompileDriver 假设）；如用它，包一层 `invokeLater`。优先用 ProjectTaskManager 规避 |
| `promise.blockingGet(...)` | **绝不能在 EDT**（resolve 需回 EDT → 死锁）；后台线程用短超时轮询 + checkCanceled |
| 解析 `VirtualFile`/`Module`（组装参数） | 需要 ReadAction（如 `ReadAction.compute { ProjectFileIndex...getModuleForFile(vf) }`）；`LocalFileSystem.findFileByPath` 建议也在 ReadAction 内 |
| Kotlin 协程桥接 | `Promise.await()`（`org.jetbrains.concurrency.await`，platform 已提供 suspend 扩展）可把 Promise 融入 IdeaBridge 的协程模型；JetBrains 官方方向是新代码用协程替代 Promise |
| MCP 服务器线程 → IDE | 一律通过 `invokeLater` / ReadAction / 协程调度器进入平台线程模型，禁止直接触碰 PSI/VFS |

---

## 4. 版本兼容性（233 ~ 2026 当前）

- `ProjectTaskManager` Promise API：2019.3 定型，233（2023.3）~ 当前 master 签名未变；旧回调重载
  2021.1 已删除（IDEA-262168）——**233 基线完全安全**。
- `Result.anyTaskMatches`：`@ApiStatus.Experimental`，谨慎使用。
- `ProjectTaskContext`：已从 Java 迁移为 Kotlin（master 为 `.kt`），二进制兼容，Kotlin 调用可用
  默认参数构造；`isCollectionOfGeneratedFilesEnabled`/generated-files API 为 Experimental。
- `CompilerManager.addCompilationStatusListener`：@Deprecated（改用 `CompilerTopics.COMPILATION_STATUS`）；
  Topic 机制自远早于 233 即存在，稳定。
- `BuildViewManager/AbstractViewManager/BuildEvent`：位于 `platform/lang-impl`（**impl 模块**，非
  lang-api；`BuildProgressListener`、`events.*` 在 lang-api）。`addListener` 自 2019.x 存在且稳定，但
  impl 类理论上不受 API 兼容承诺覆盖；2025.x 引入 FrontendBuildViewManager（Remote Dev 拆分），
  本地插件不受影响，需在每个目标大版本跑 Plugin Verifier。
- `GradleProjectTaskRunner`：2024.2~2025 期间由 Java 重写为 Kotlin + 协程（行为不变，勿依赖其内部）。
- 编译子系统整体依赖 Java 插件（`com.intellij.java`）——与项目 `bundledPlugin("com.intellij.java")`
  约束一致；Maven runner 属 `org.jetbrains.idea.maven`（我们的可选依赖）。
- SDK 文档明示：新异步代码建议 Kotlin 协程；`Promise` 继续支持但属遗留方向。

## 5. 已知坑与限制

1. **`Result` 无错误明细**——必须双通道收集（BuildViewManager 事件 + JPS CompileContext）。
2. **`CompilerMessage` 接口无行列号**——经 `getNavigatable()`→`OpenFileDescriptor`（0 基）或
   impl 类 `CompilerMessageImpl.getLine()/getColumn()`（1 基，-1 缺省）；跨构建系统请用
   `FileMessageEvent.getFilePosition()`（0 基）。上报 MCP 前统一行列基准并注明。
3. **委托构建下 JPS 监听全部静默**——功能测试必须覆盖 "Build and run using: Gradle/IDEA" 两种
   设置；每模块可不同（Gradle 设置按 linked project 存储）。
4. **并发构建语义模糊**：ProjectTaskManager 不互斥，JPS 信号量隐式阻塞第二个请求（无排队反馈）。
   IdeaBridge 必须自建每项目构建队列，向 Agent 显式返回 queued/running 状态。
5. **无公开"取消构建"API**：Promise.cancel 不停底层进程；只能 best-effort（JPS 经
   ProgressIndicator；Gradle 委托经取消对应 ExternalSystem 任务）。实现期专项验证。
6. **EDT 死锁**：在 EDT 上 blockingGet 构建 Promise 必死锁；`onSuccess` 中做重活会卡 UI。
7. **automake 不进 Build 窗口**、错误走 Problems 视图；且 Problems 视图**无公开读取 API**
   （官方确认，只能自行用 `HighlightingWatcher`/`ProjectErrorsCollector` 思路重建）——IdeaBridge 的
   "错误清单"以构建事件为准，不做 Problems 视图抓取。
8. **BuildViewManager 在 lang-impl**：属内部实现区，Remote Dev 前后端拆分在推进，升级目标平台
   时优先回归此处。
9. Kotlin 文件经 `CompilerManager.compile` 单编曾有兼容问题（社区报告），单文件重编译工具对
   Kotlin 源要走 `ProjectTaskManager.compile` 并验证。
10. `compile(VirtualFile...)` 要求文件在 source content 内且"可编译"（CompileAction 用
    `getCompilableFiles()` 过滤）；对资源文件/非源根文件行为不同，MCP 工具应先校验并给出明确错误。
11. `syncPublisher` 意味着监听器异常会传播回构建流程——监听器内必须 try/catch。

## 6. 参考来源

- ProjectTaskManager.java（签名/Javadoc）：https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/task/ProjectTaskManager.java
- ProjectTaskContext.kt：https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/task/ProjectTaskContext.kt
- ProjectTaskListener.java：https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/task/ProjectTaskListener.java
- ProjectTaskManagerImpl.java（线程/分发/等待）：https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/task/impl/ProjectTaskManagerImpl.java
- CompilerManager.java：https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerManager.java
- CompilationStatusListener.java：https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompilationStatusListener.java
- CompilerTopics.java：https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerTopics.java
- CompileContext.java：https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompileContext.java
- CompilerMessage.java：https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerMessage.java
- CompilerManagerImpl.java（信号量/发布线程）：https://github.com/JetBrains/intellij-community/blob/master/java/compiler/impl/src/com/intellij/compiler/CompilerManagerImpl.java
- 动作映射：CompileDirtyAction / MakeModuleAction / CompileAction / CompileProjectAction：https://github.com/JetBrains/intellij-community/tree/master/java/compiler/impl/src/com/intellij/compiler/actions
- BuildViewManager.java：https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/build/BuildViewManager.java
- AbstractViewManager.java（addListener/onEvent）：https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/build/AbstractViewManager.java
- MessageEvent.java / FileMessageEvent.java：https://github.com/JetBrains/intellij-community/tree/master/platform/lang-api/src/com/intellij/build/events
- GradleProjectTaskRunner.kt：https://github.com/JetBrains/intellij-community/blob/master/plugins/gradle/java/src/execution/build/GradleProjectTaskRunner.kt
- MavenProjectTaskRunner.java：https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution/build/MavenProjectTaskRunner.java
- SDK 文档 External Builder API and Plugins：https://plugins.jetbrains.com/docs/intellij/external-builder-api.html
- JetBrains 支持帖（Result 无错误明细 / BuildViewManager.addListener 收错误）：https://intellij-support.jetbrains.com/hc/en-us/community/posts/20340253075602-How-to-collect-errors-after-triggering-the-build-action-through-the-SDK-in-plugin
- JetBrains 支持帖（Problems 视图无读取 API、ProblemsCollector）：https://intellij-support.jetbrains.com/hc/en-us/community/posts/360010641899-Show-custom-errors-Project-Errors-tab
- JetBrains 博客（2020.3 Problems 工具窗口）：https://blog.jetbrains.com/idea/2020/08/working-with-code-problems-in-intellij-idea/
- Promise.java：https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/org/jetbrains/concurrency/Promise.java
