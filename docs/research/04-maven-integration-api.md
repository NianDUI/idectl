# 调研报告 04：IntelliJ Maven 集成插件 (org.jetbrains.idea.maven) API

> IdeaBridge 设计阶段调研。调研日期：2026-07-03。
> 一手资料来源：github.com/JetBrains/intellij-community（master 分支 ≈ 2026.x，以及 242 分支 = IDEA 2024.2 用于兼容性对照）、plugins.jetbrains.com IntelliJ Platform SDK 文档。所有类名/签名均直接摘自源码，非凭记忆。

---

## 1. 概述

IDEA 的 Maven 支持由捆绑插件 `org.jetbrains.idea.maven` 提供（Community 源码位于 `plugins/maven/`，共享模型位于 `plugins/maven-server-api/`）。IdeaBridge 需要的能力全部有对应的编程入口：

| IdeaBridge 需求 | 核心 API |
|---|---|
| 判断是否 Maven 工程 / 模块归属 | `MavenProjectsManager.isMavenizedProject()` / `findProject(Module)` |
| 触发同步（Reload All） | `MavenProjectsManager.scheduleUpdateAllMavenProjects(MavenSyncSpec)`（异步）或 `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`（工具窗口按钮同款） |
| 同步完成通知 | `MavenSyncListener.TOPIC`（App 级）/ `MavenImportListener.TOPIC`（Project 级） |
| 同步错误 | `MavenProject.problems: List<MavenProjectProblem>` + `MavenProjectsManager.getSyncConsole()` |
| 模块树 / 聚合关系 | `getRootProjects()` / `getModules(aggregator)` / `findAggregator(p)` |
| Profiles | `getAvailableProfiles()` / `getProfilesWithStates()` / `getExplicitProfiles()` / `setExplicitProfiles()` |
| 执行 goal | `MavenRunnerParameters` + `MavenRunConfigurationType.runConfiguration(...)`（走标准 ExecutionEnvironment，可复用控制台捕获）或 `MavenRunner.run(...)` |
| 下载源码/JavaDoc | `MavenAsyncProjectsManager.downloadArtifacts(...)` / `scheduleDownloadArtifacts(...)` |
| 忽略的 pom | `getIgnoredFilesPaths()` / `setIgnoredState(...)` 等 |
| 增删受管 pom | `addManagedFilesOrUnignore()` / `removeManagedFiles()` |

关键背景：2023.x 起 Maven 插件经历了大规模 **协程化重构**。老一代 API（`scheduleImportAndResolve()`、`forceUpdateProjects(...)`、`scheduleArtifactsDownloading()` 等）陆续 `@Deprecated(forRemoval = true)` 或已删除；新一代入口集中在 Kotlin 接口 `MavenAsyncProjectsManager`（`MavenProjectsManager` 实现它），以 `suspend fun` + `schedule*` 双形态提供。**面向 2024.2+ 开发必须以新 API 为主。**

---

## 2. 关键 API 详解

### 2.1 MavenProjectsManager —— 入口服务

类：`org.jetbrains.idea.maven.project.MavenProjectsManager`（project-level service，抽象类，实现在 `MavenProjectsManagerEx.kt`）。
来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java>

```java
public static MavenProjectsManager getInstance(@NotNull Project project)
public static @Nullable MavenProjectsManager getInstanceIfCreated(@NotNull Project project)

// 状态查询
public boolean isInitialized()
public boolean isMavenizedProject()
public boolean isMavenizedModule(@NotNull Module m)
public boolean hasProjects()
public @NotNull List<MavenProject> getProjects()
public @NotNull List<MavenProject> getRootProjects()        // 聚合根（对应工具窗口顶层节点）
public @NotNull List<MavenProject> getNonIgnoredProjects()
public @NotNull List<VirtualFile> getProjectsFiles()

// 查找
public @Nullable MavenProject findProject(@NotNull VirtualFile f)
public @Nullable MavenProject findProject(@NotNull MavenId id)
public @Nullable MavenProject findProject(@NotNull Module module)
public @Nullable MavenProject findAggregator(@NotNull MavenProject mavenProject)   // 直接父聚合
public @Nullable MavenProject findRootProject(@NotNull MavenProject mavenProject)  // 根聚合
public @NotNull List<MavenProject> getModules(@NotNull MavenProject aggregator)    // 子模块
public @Nullable MavenProject findContainingProject(@NotNull VirtualFile file)
@RequiresReadLock
public @Nullable Module findModule(@NotNull MavenProject project)   // 注意：要求 ReadLock

// 设置对象
public MavenGeneralSettings getGeneralSettings()
public MavenImportingSettings getImportingSettings()
public MavenSyncConsole getSyncConsole()
```

注意 `getInstanceIfCreated()`：IdeaBridge 的只读查询（如 MCP tool `maven.listModules`）应优先用它，避免在非 Maven 工程里无谓地实例化服务。`findModule` 标注 `@RequiresReadLock`，必须在 ReadAction 中调用。

### 2.2 触发同步/重新导入：2024.x → 2026 的 API 演变

#### 现行推荐（2023.2+ 引入，2024.2 与 2026 master 均存在）

接口 `MavenAsyncProjectsManager`（`MavenProjectsManagerEx.kt`，`MavenProjectsManager` 实现之）：

```kotlin
// fire-and-forget，内部投递到协程作用域
fun scheduleUpdateAllMavenProjects(spec: MavenSyncSpec)

// 可等待版本（suspend，同步完成后返回）
suspend fun updateAllMavenProjects(spec: MavenSyncSpec)
```

来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManagerEx.kt>（242 分支同签名，已比对）。

`MavenSyncSpec`（`org.jetbrains.idea.maven.buildtool.MavenSyncSpec`，**注意包名是 buildtool 不是 project**；标注 `@ApiStatus.Experimental` + `@ApiStatus.NonExtendable`）：

```kotlin
interface MavenSyncSpec {
  fun forceReading(): Boolean          // full=true：强制重读所有 pom
  fun resolveIncrementally(): Boolean
  val isExplicit: Boolean              // 用户显式触发（影响 untrusted project 提示等）

  companion object {
    fun incremental(description: String, explicit: Boolean = false): MavenSyncSpec
    fun full(description: String, explicit: Boolean = false): MavenSyncSpec
  }
}
```

`description` 仅用于日志/追踪（IDE 内部用例形如 `MavenSyncSpec.full("MavenProjectsManager.forceUpdateProjects", true)`）。
来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/buildtool/MavenSyncSpec.kt>

#### 工具窗口"Reload All Maven Projects"按钮的等价调用

`ReimportAction`（`org.jetbrains.idea.maven.project.actions.ReimportAction`，继承 `MavenProjectsManagerAction`）的 `perform` 依次做三件事：

1. `confirmLoadingUntrustedProject(manager.project, MavenUtil.SYSTEM_ID)`（Trust 检查）
2. `FileDocumentManager.getInstance().saveAllDocuments()`（先落盘未保存的 pom 编辑）
3. `manager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()`

`forceUpdateAllProjectsOrFindAllAvailablePomFiles()` 是 `MavenProjectsManager` 上的 public 便捷方法：若尚无受管 pom 则先扫描发现所有可用 pom，否则等价于 `scheduleUpdateAllMavenProjects(MavenSyncSpec.full(..., explicit=true))`。IdeaBridge 的 `maven.reimport` 工具直接模仿这三步即可（saveAllDocuments 需在 EDT）。
来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/ReimportAction.kt>

工具窗口另有 `IncrementalSyncAction` → `scheduleUpdateAllMavenProjects(MavenSyncSpec.incremental(...))`，以及 `SyncAllSplitAction`（下拉组合按钮，2025.x 新增）。

#### 已废弃/删除（不要使用）

摘自 master `MavenProjectsManager.java`：

```java
@Deprecated(forRemoval = true) public Promise<List<Module>> scheduleImportAndResolve()
@Deprecated /*third-party 仍在用*/ public AsyncPromise<Void> forceUpdateProjects(@NotNull Collection<@NotNull MavenProject> projects)
@Deprecated public void scheduleFoldersResolveForAllProjects()
@Deprecated(forRemoval = true) protected abstract List<Module> updateAllMavenProjectsSync()
```

- `scheduleArtifactsDownloading(...)` 在 **2023.2 已删除**（JetBrains 官方论坛确认，替代为 `downloadArtifacts`）：<https://intellij-support.jetbrains.com/hc/en-us/community/posts/13744541641106>
- `waitForImportCompletion()` / `importProjects()` 等旧同步等待方法也已移除或内部化。

### 2.3 同步完成通知与结果获取

#### MavenSyncListener（推荐，2023.2+）

`org.jetbrains.idea.maven.project.MavenSyncListener`，**Application 级** Topic（242 与 master 签名一致，已分别核对）：

```kotlin
@Topic.AppLevel
val TOPIC: Topic<MavenSyncListener> =
    Topic.create("Maven sync notifications", MavenSyncListener::class.java)

interface MavenSyncListener {
  fun syncStarted(project: Project) {}
  // "Workspace model is committed, project structure is created.
  //  注意：插件解析、源码下载等关联活动此时可能尚未完成"
  fun syncFinished(project: Project) {}
  fun importStarted(project: Project) {}
  fun importFinished(project: Project,
                     importedProjects: Collection<MavenProject>,
                     newModules: List<Module>) {}
}
```

来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenSyncListener.kt>

因为是 App 级 Topic，IdeaBridge 应订阅 `ApplicationManager.getApplication().messageBus`，回调里用 `project` 参数路由到对应会话——这天然契合"同一 IDE 多项目、路由给不同 Agent"的需求。plugin.xml 声明式注册用 `<applicationListeners>`。

#### MavenImportListener（旧，Project 级，部分方法已建议迁移）

`org.jetbrains.idea.maven.project.MavenImportListener`，`@Topic.ProjectLevel`，`Topic.create("Maven import notifications", ...)`。方法（均有默认空实现）：

```java
void importStarted()   // 已标记建议改用 MavenSyncListener
void importFinished(@NotNull Collection<MavenProject> importedProjects,
                    @NotNull List<@NotNull Module> newModules)   // 同上
void pomReadingStarted();  void pomReadingFinished();
void projectResolutionStarted(@NotNull Collection<@NotNull MavenProject> projects);
void projectResolutionFinished(...);
void pluginResolutionStarted();  void pluginResolutionFinished();
void artifactDownloadingScheduled();  void artifactDownloadingStarted();  void artifactDownloadingFinished();
```

其细粒度阶段事件（pom 读取/依赖解析/插件解析/构件下载）可用于给 Agent 上报同步进度。
来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenImportListener.java>

#### 成功/失败与错误信息

`syncFinished(project)` **不携带成败标志**。获取结果的可行途径：

1. **结构化问题列表（推荐）**：同步后遍历 `MavenProjectsManager.getProjects()`，读 `MavenProject.problems`。`org.jetbrains.idea.maven.model.MavenProjectProblem`（maven-server-api 模块）：
   ```java
   enum ProblemType { SYNTAX, STRUCTURE, DEPENDENCY, PARENT, SETTINGS_OR_PROFILES, REPOSITORY }
   public String getPath();
   public @Nullable String getDescription();
   public ProblemType getType();
   ```
   这正好可以映射成 MCP 工具的结构化返回（type/pomPath/message）。
2. **MavenSyncConsole**（`org.jetbrains.idea.maven.buildtool.MavenSyncConsole`，经 `manager.getSyncConsole()` 获得）：内部有 `hasErrors` 标志、`addException(e: Throwable)`、`showProblem(problem)`、`addBuildIssue(issue, kind)`、`finishImport(...)`、`startTransaction()/finishTransaction(...)`。但多数成员 internal，**不应作为稳定读取通道**，仅知晓其存在即可；错误同样会经 Build 工具窗口的 `BuildProgressListener`（`build` 系统的 `SyncViewManager`）发布，如需完整文本可订阅 build events。
3. 若用 `suspend fun updateAllMavenProjects(spec)`，恢复执行即代表流程结束，随后立刻读 `problems` 即可，无需监听 Topic。

Kotlin 草图（IdeaBridge `maven.sync` 工具）：

```kotlin
suspend fun syncProject(project: Project): MavenSyncResult {
  val manager = MavenProjectsManager.getInstance(project)
  withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
  manager.updateAllMavenProjects(MavenSyncSpec.full("IdeaBridge.mcp.sync", true))
  val problems = manager.projects.flatMap { p ->
    p.problems.map { SyncProblem(p.path, it.type.name, it.description) }
  }
  return MavenSyncResult(success = problems.none { it.type != "DEPENDENCY" || strict }, problems)
}
```

### 2.4 MavenProject 数据模型

类：`org.jetbrains.idea.maven.project.MavenProject`——**master 上已是 Kotlin 文件（MavenProject.kt），2024.x 尚是 Java（MavenProject.java）**，二进制兼容（getter 同名），但按 Kotlin 属性写法在旧版本同样可用。
来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProject.kt>

```kotlin
class MavenProject(val file: VirtualFile)          // 构造参数即 pom 的 VirtualFile

val mavenId: MavenId                                // groupId/artifactId/version
val parentId: MavenId?
val path: String            // = file.path（pom 绝对路径）
val directoryFile: VirtualFile                      // pom 所在目录
val directoryPath: Path
val displayName: String     // <name> 或 artifactId
val packaging: String
val finalName: String
val buildDirectory: String
val properties: Properties
val modulePaths: Set<String>                        // <modules> 声明的子模块 pom 路径
val existingModuleFiles: List<VirtualFile>
val isAggregator: Boolean   // packaging=="pom" || modulePaths.isNotEmpty()
val profilesIds: Collection<String>                 // 本 pom 中声明的 profile id
val activatedProfilesIds: MavenExplicitProfiles     // 解析时实际激活的 profile
val problems: List<MavenProjectProblem>
```

**模块树重建**（对应 Maven 工具窗口树）：用 `manager.getRootProjects()` 得顶层，递归 `manager.getModules(aggregator)` 下钻；反向用 `findAggregator(p)`。注意 `MavenProject` ≠ IDEA `Module`，互查用 `manager.findProject(module)` / `manager.findModule(mavenProject)`（后者要 ReadLock）。

### 2.5 Profiles

- 全工程可用 profile 枚举：`manager.getAvailableProfiles(): Collection<String>`；带状态版本 `getProfilesWithStates(): Collection<Pair<String, MavenProfileKind>>`。
- `org.jetbrains.idea.maven.model.MavenProfileKind`（maven-server-api）：`NONE`（显式禁用）、`EXPLICIT`（显式启用）、`IMPLICIT`（默认激活，如 activeByDefault）。
- 激活/停用：

```java
public @NotNull MavenExplicitProfiles getExplicitProfiles()
public void setExplicitProfiles(MavenExplicitProfiles profiles)   // 触发增量同步
```

`org.jetbrains.idea.maven.model.MavenExplicitProfiles`：

```java
public MavenExplicitProfiles(@NotNull Collection<String> enabledProfiles,
                             @NotNull Collection<String> disabledProfiles)
public MavenExplicitProfiles(Collection<String> enabledProfiles)
public static final MavenExplicitProfiles NONE = new MavenExplicitProfiles(Collections.emptySet());
public @NotNull Collection<String> getEnabledProfiles()
public @NotNull Collection<String> getDisabledProfiles()
```

IDE 自带 `ToggleProfileAction` 的实现方式即：`getExplicitProfiles().clone()` → 修改 enabled/disabled 集合 → `setExplicitProfiles(newProfiles)`；切到 IMPLICIT 状态 = 从两个集合中都移除。IdeaBridge `maven.setProfiles` 照此实现即可，切换后同步会被自动调度。
来源：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/ToggleProfileAction.java>、<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven-server-api/src/main/java/org/jetbrains/idea/maven/model/MavenExplicitProfiles.java>

### 2.6 执行 Maven goal

#### MavenRunnerParameters（`org.jetbrains.idea.maven.execution`）

```java
public MavenRunnerParameters(boolean isPomExecution,
                             @NotNull String workingDirPath,
                             @Nullable String pomFileName,
                             @Nullable List<String> goals,
                             @NotNull MavenExplicitProfiles explicitProfiles)
// 及 explicitEnabledProfiles / +explicitDisabledProfiles 的两个 Collection 重载、拷贝构造

public @NotNull String getWorkingDirPath();  public void setWorkingDirPath(@NotNull String p);
public @Nullable String getPomFileName();    public void setPomFileName(String pomFileName);
public @Unmodifiable List<String> getGoals(); public void setGoals(@Nullable List<String> goals);
public Map<String, Boolean> getProfilesMap(); public void setProfilesMap(@NotNull Map<String, Boolean> m);
public @Nullable String getCmdOptions();      public void setCmdOptions(@Nullable String cmdOptions);  // 额外命令行参数，如 "-T 4 -U"
public boolean isPomExecution();
```

#### 推荐通道：MavenRunConfigurationType（走标准 Run Configuration 管线）

```java
public static MavenRunConfigurationType getInstance()

public static void runConfiguration(Project project, MavenRunnerParameters params,
                                    @Nullable ProgramRunner.Callback callback)
public static void runConfiguration(Project project, @NotNull MavenRunnerParameters params,
                                    @Nullable MavenGeneralSettings settings,
                                    @Nullable MavenRunnerSettings runnerSettings,
                                    @Nullable ProgramRunner.Callback callback)
public static void runConfiguration(..., boolean isDelegateBuild)

public static @NotNull RunnerAndConfigurationSettings createRunnerAndConfigurationSettings(
    @Nullable MavenGeneralSettings generalSettings, @Nullable MavenRunnerSettings runnerSettings,
    @NotNull MavenRunnerParameters params, @NotNull Project project)
public static @NotNull RunnerAndConfigurationSettings createRunnerAndConfigurationSettings(
    ..., @NotNull String name, boolean isDelegate)
```

**已核实的内部实现**：`runConfiguration` 构造 `ExecutionEnvironment(DefaultRunExecutor, DefaultJavaProgramRunner/DelegateBuildRunner, configSettings, project)` 后 `runner.execute(environment)`——即完整走标准执行管线。**结论：会触发 `ExecutionManager.EXECUTION_TOPIC`（processStarting/processStarted）、产生 `RunContentDescriptor` 与 `ProcessHandler`，IdeaBridge 的通用控制台捕获层（调研主题 3 的 ExecutionListener + ProcessListener 方案）无需为 Maven 做特殊处理即可复用。** `ProgramRunner.Callback.processStarted(RunContentDescriptor)` 还能拿到本次执行的 descriptor，便于把"MCP 发起的 goal 执行"与会话精确关联。

另一条更手动的路线：`createRunnerAndConfigurationSettings(...)` 拿到 `RunnerAndConfigurationSettings` 后交给 `ExecutionUtil.runConfiguration(settings, executor)`，还可先 `RunManager.getInstance(project).addConfiguration(settings)` 持久化成用户可见的运行配置（工具窗口"Run Configurations"节点即 `MavenRunConfigurationType` 类型的配置列表）。

#### MavenRunner（批处理/无 UI 通道）

`org.jetbrains.idea.maven.execution.MavenRunner`（project-level service，Java）：

```java
public static MavenRunner getInstance(Project project)
public MavenRunnerSettings getSettings()
public void run(MavenRunnerParameters parameters, MavenRunnerSettings settings, Runnable onComplete)
public boolean runBatch(List<MavenRunnerParameters> commands,
                        @Nullable MavenGeneralSettings coreSettings,
                        @Nullable MavenRunnerSettings runnerSettings,
                        @Nullable String action, @Nullable ProgressIndicator indicator)
// + 重载：@Nullable Consumer<? super ProcessHandler> onAttach、boolean isDelegateBuild
```

`runBatch` 是阻塞式（配 ProgressIndicator），`onAttach` 能拿到 `ProcessHandler` 做输出捕获；但它不产生 Run 工具窗口标签页。**IdeaBridge 建议默认走 `MavenRunConfigurationType.runConfiguration`**，让 Agent 与人类用户看到同样的运行标签页。

#### MavenRunnerSettings（JVM 参数 / skipTests 等）

```java
public boolean isSkipTests();               public void setSkipTests(boolean skipTests);
public @NotNull String getVmOptions();      public void setVmOptions(@Nullable String vmOptions);
public @NotNull String getJreName();        public void setJreName(@Nullable String jreName);
public @NotNull Map<String,String> getEnvironmentProperties(); public void setEnvironmentProperties(Map<String,String> envs);
public Map<String,String> getMavenProperties(); public void setMavenProperties(Map<String,String> mavenProperties);  // -D 属性
public boolean isDelegateBuildToMaven();    public void setDelegateBuildToMaven(boolean b);
```

用法：`MavenRunner.getInstance(project).settings.clone()` 后按 MCP 请求覆写 skipTests/vmOptions，再传入 `runConfiguration`。

#### MavenGeneralSettings（离线/线程数/Maven home）

```java
public boolean isWorkOffline();  public void setWorkOffline(boolean value);   // 工具窗口 Toggle Offline 按钮
public @Nullable String getThreads();  public void setThreads(@Nullable String value);   // -T
public @NotNull MavenHomeType getMavenHomeType();  public void setMavenHomeType(@NotNull MavenHomeType t);
// @Deprecated(forRemoval=true): getMavenHome()/setMavenHome(String) —— 2023.x 起用 MavenHomeType 替代
public @NotNull String getUserSettingsFile();  public boolean isAlwaysUpdateSnapshots();
public @NotNull MavenExecutionOptions.LoggingLevel getOutputLevel();
// @Deprecated(forRemoval=true): getLoggingLevel() → getOutputLevel()
```

### 2.7 下载源码 / JavaDoc

`MavenAsyncProjectsManager` 上，**2024.2 (242 分支) 的签名**：

```kotlin
suspend fun downloadArtifacts(projects: Collection<MavenProject>,
                              artifacts: Collection<MavenArtifact>?,   // null = 全部构件
                              sources: Boolean,
                              docs: Boolean): MavenArtifactDownloader.DownloadResult

fun scheduleDownloadArtifacts(projects: Collection<MavenProject>,
                              artifacts: Collection<MavenArtifact>?,
                              sources: Boolean, docs: Boolean)
```

**2026 master 已改为 request 对象（builder 模式）**：

```kotlin
suspend fun downloadArtifacts(request: MavenDownloadSourcesRequest): ArtifactDownloadResult
fun scheduleDownloadArtifacts(request: MavenDownloadSourcesRequest)

// IDE 自身 DownloadAllSourcesAndDocsAction.kt 的用法：
MavenDownloadSourcesRequest.builder()
    .forProjects(manager.projects)
    .forAllArtifacts()
    .downloadSources(true)
    .downloadDocs(true)
    .build()
```

结果类（master 上为 `ArtifactDownloadResult`，242 上为 `MavenArtifactDownloader.DownloadResult`，字段一致）：

```kotlin
class ArtifactDownloadResult {
  val resolvedSources: MutableSet<MavenId>;  val resolvedDocs: MutableSet<MavenId>
  val unresolvedSources: MutableSet<MavenId>; val unresolvedDocs: MutableSet<MavenId>
}
```

**兼容性警告**：这是本调研发现的最大版本断层——若 IdeaBridge 要求 sinceBuild=233 单一二进制兼容到 2026.x，`downloadArtifacts` 的四参重载与 request 重载不同时存在于所有版本，直接编译期绑定会在某端 `NoSuchMethodError`。缓解方案见 §5。工具窗口对应按钮类：`DownloadAllSourcesAction` / `DownloadAllDocsAction` / `DownloadAllSourcesAndDocsAction`（及 Selected 变体，位于 `org.jetbrains.idea.maven.project.actions`）。
来源：<https://github.com/JetBrains/intellij-community/blob/242/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManagerEx.kt>、<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/DownloadAllSourcesAndDocsAction.kt>

### 2.8 忽略的 pom 与受管 pom 管理

```java
// ignore（对应工具窗口右键 "Ignore Projects"）
public @NotNull List<String> getIgnoredFilesPaths();
public void setIgnoredFilesPaths(@NotNull List<String> paths);
public void removeIgnoredFilesPaths(Collection<String> paths);
public boolean getIgnoredState(@NotNull MavenProject project);
public void setIgnoredState(@NotNull List<MavenProject> projects, boolean ignored);
public @NotNull List<String> getIgnoredFilesPatterns();
public void setIgnoredFilesPatterns(@NotNull List<String> patterns);
public boolean isIgnored(@NotNull MavenProject project);

// 受管 pom（"+"/"-" 按钮）
public void addManagedFiles(@NotNull List<VirtualFile> files);            // 添加并调度增量同步
public void addManagedFilesOrUnignore(@NotNull List<VirtualFile> files);  // AddManagedFilesAction 使用
public boolean isManagedFile(@NotNull VirtualFile f);
public synchronized void removeManagedFiles(@NotNull List<VirtualFile> files);  // RemoveManagedFilesAction
```

### 2.9 Maven 工具窗口按钮 → API 对照（题述"图4"）

| 工具窗口按钮 | Action 类（`org.jetbrains.idea.maven.project.actions`） | 底层 API |
|---|---|---|
| Reload All Maven Projects（全部刷新） | `ReimportAction` | `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`（前置 saveAllDocuments + trust 确认） |
| 增量 Sync（Sync 下拉/分裂按钮） | `IncrementalSyncAction` / `SyncAllSplitAction` | `scheduleUpdateAllMavenProjects(MavenSyncSpec.incremental(...))` |
| 单工程 Reload（右键） | `ReimportProjectAction` | 针对选中 projects 的 full spec 更新 |
| Download Sources/Documentation | `DownloadActionGroup` + `DownloadAll[Selected]Sources/Docs[AndDocs]Action` | `scheduleDownloadArtifacts(...)` / `downloadArtifacts(...)` |
| Execute Maven Goal（m 图标） | `org.jetbrains.idea.maven.execution.MavenExecuteGoalAction`（Run Anything 弹窗） | 最终经 `MavenRunConfigurationType.runConfiguration` |
| + Add Maven Projects | `AddManagedFilesAction` / `AddFileAsMavenProjectAction` | `addManagedFilesOrUnignore(files)` |
| - Remove（Unlink） | `RemoveManagedFilesAction` | `removeManagedFiles(files)` |
| Toggle Offline Mode | `ToggleOfflineAction` | `generalSettings.setWorkOffline(b)` |
| Skip Tests 开关 | `ToggleSkipTestsAction` | `runnerSettings.setSkipTests(b)` |
| Profiles 勾选 | `ToggleProfileAction` / `ResetProfilesAction` | `get/setExplicitProfiles` |
| 生成源码目录更新 | `UpdateFoldersAction` | `updateProjectTargetFolders()` |
| Run Configurations 节点 | —（RunManager 数据） | `RunManager` 中 `MavenRunConfigurationType` 类型的配置 |
| Show Effective POM | `MavenShowEffectivePom` | `MavenEmbeddersManager` 相关（IdeaBridge 可选） |

（Action 类清单经 GitHub API 列目录核实：<https://github.com/JetBrains/intellij-community/tree/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions>）

### 2.10 可选依赖写法与优雅降级

SDK 文档（<https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html>）确认的官方写法：

**plugin.xml：**

```xml
<idea-plugin>
  <id>com.ideabridge</id>
  <depends>com.intellij.modules.platform</depends>
  <depends>com.intellij.java</depends>
  <!-- Maven 可选依赖 -->
  <depends optional="true" config-file="ideabridge-maven.xml">org.jetbrains.idea.maven</depends>
</idea-plugin>
```

**ideabridge-maven.xml**（必须与 plugin.xml 同目录 META-INF/；命名建议 `myPluginId-$NAME$.xml` 以避免 classloader 冲突）：

```xml
<idea-plugin>
  <!-- 所有 import org.jetbrains.idea.maven.* 的类只能注册在这里 -->
  <applicationListeners>
    <listener class="com.ideabridge.maven.IdeaBridgeMavenSyncListener"
              topic="org.jetbrains.idea.maven.project.MavenSyncListener"/>
  </applicationListeners>
  <extensions defaultExtensionNs="com.ideabridge">
    <!-- 自定义 EP：Maven 能力提供者实现 -->
    <mavenFacade implementation="com.ideabridge.maven.MavenFacadeImpl"/>
  </extensions>
</idea-plugin>
```

**build.gradle.kts（intellij-platform-gradle-plugin 2.x）：**

```kotlin
dependencies {
  intellijPlatform {
    intellijIdeaCommunity("2024.2")
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.idea.maven")   // 编译期可见；运行期由 optional depends 控制加载
  }
}
```

**降级模式**：核心模块定义 `interface MavenFacade`（自有 EP 或轻量 ServiceLoader 式注册），MCP 层只依赖该接口；实现类放在 optional descriptor 注册。Maven 插件被禁用/不存在时 EP 无实现，`maven.*` MCP 工具返回 "Maven support unavailable"；Maven 插件在但当前项目非 Maven 时，用 `MavenProjectsManager.getInstanceIfCreated(project)?.isMavenizedProject() != true` 判定并返回业务级错误。**核心 classloader 中绝不能出现 `org.jetbrains.idea.maven.*` 的直接引用（包括方法签名、字段类型、异常捕获），否则 Maven 插件缺席时抛 `NoClassDefFoundError`。**

---

## 3. 线程模型注意事项

1. **suspend API 优先**：`updateAllMavenProjects` / `downloadArtifacts` 是 suspend 函数，IdeaBridge 应在插件级 `CoroutineScope`（构造注入的 project-scope）中调用；**不要在 EDT 上 runBlocking**。IDE 自己的 action 也是 `cs.launch { manager.downloadArtifacts(request) }` 模式。
2. **schedule 版本线程无关**：`scheduleUpdateAllMavenProjects` / `scheduleDownloadArtifacts` 可从任意线程调用（fire-and-forget），适合 MCP "触发后立刻返回 + 事件推送进度"的形态。
3. **EDT 必需**：`FileDocumentManager.saveAllDocuments()`（reimport 前置步骤）、`MavenRunConfigurationType.runConfiguration(...)`（内部创建 `ExecutionEnvironment` 并 `runner.execute`，须 EDT——与所有 RunConfiguration 启动一致）、`ProgramRunner.Callback` 回调在 EDT。
4. **ReadAction 必需**：`findModule(MavenProject)` 标注 `@RequiresReadLock`；凡触碰 `Module`/workspace model 的读取包 `readAction { }`（协程）或 `ReadAction.compute`。`getProjects()`/`findProject(VirtualFile)`/`problems` 等读的是 Maven 自身的 tree 快照（内部锁），无需 IDE read lock，但拿 `VirtualFile`→`Module` 映射时需要。
5. **MavenRunner.runBatch 阻塞**：只能在后台线程 + ProgressIndicator 下用，禁止 EDT。
6. **Topic 回调线程**：MessageBus 回调在发布线程上执行；`MavenSyncListener` 的回调可能来自后台协程线程，处理时不要假设 EDT，转发给 MCP 会话前自行切换上下文。
7. **setExplicitProfiles / setIgnoredState** 会触发后台重新同步调度，本身调用无线程限制，但避免在 read action 内调用（可能触发写状态）。

---

## 4. 版本兼容性（233 ~ 2026.x）

| API | 233 (2023.3) | 242 (2024.2) | 2025.x | 2026 master | 备注 |
|---|---|---|---|---|---|
| `scheduleUpdateAllMavenProjects(MavenSyncSpec)` | ✅（2023.2 引入） | ✅ | ✅ | ✅ | `MavenSyncSpec` 至今仍 `@ApiStatus.Experimental` |
| `updateAllMavenProjects(spec)` (suspend) | ✅ | ✅ | ✅ | ✅ | |
| `forceUpdateAllProjectsOrFindAllAvailablePomFiles()` | ✅ | ✅ | ✅ | ✅ | 最稳妥的"全部刷新"入口 |
| `forceUpdateProjects(Collection)` | ✅ | ✅ @Deprecated | ✅ @Deprecated | ✅ @Deprecated | 尚未删除（"third-party 在用"），勿新增使用 |
| `scheduleImportAndResolve()` | @Deprecated(forRemoval) | 同左 | 同左 | 同左 | 禁用 |
| `MavenSyncListener` | ✅（2023.2+，App 级） | ✅ | ✅ | ✅ | 4 方法签名 242↔master 一致（已比对） |
| `MavenImportListener` 细粒度阶段方法 | 部分 | ✅ | ✅ | ✅ | `importStarted/importFinished` 建议迁往 SyncListener |
| `downloadArtifacts(projects, artifacts, sources, docs)` | ✅ | ✅ | ⚠️ 过渡期 | ❌ 已替换 | **断层点** |
| `downloadArtifacts(MavenDownloadSourcesRequest)` | ❌ | ❌ | ⚠️ 新增 | ✅ | request+builder 形态 |
| `MavenProject` | Java 类 | Java 类 | Kotlin 化 | Kotlin | getter 二进制兼容（`getMavenId()` 等） |
| `getMavenHome()/setMavenHome(String)` | @Deprecated | forRemoval | forRemoval | forRemoval | 用 `MavenHomeType` |
| `scheduleArtifactsDownloading` | ❌（2023.2 删除） | ❌ | ❌ | ❌ | |
| `MavenRunConfigurationType.runConfiguration` / `MavenRunner` / `MavenRunnerParameters` | ✅ | ✅ | ✅ | ✅ | 长期稳定，无变更迹象 |

**结论**：除"下载源码"外，本报告推荐的 API 集在 233→2026 全程可用。下载源码需运行期分派（见下）。`MavenDownloadSourcesRequest` 引入的精确版本（2025.1 或 2025.2）需在实现阶段用对应 releases 分支确认。

---

## 5. 已知坑与限制

1. **downloadArtifacts 签名断层**：单 jar 兼容 233~2026 时，建议：(a) 反射探测两种签名择一调用；或 (b) 用 Gradle `pluginVersion` 分渠道构建；或 (c) 退而求其次触发 IDE action（`ActionManager.getAction("Maven.DownloadAllSourcesAndDocs")` 之类，需核实 action id）以规避二进制绑定。
2. **`MavenSyncSpec` 是 Experimental API**：包名 `org.jetbrains.idea.maven.buildtool`（易误写成 project 包）；JetBrains 保留改动权。保底路径是 `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`（非 experimental、跨版本存在）。
3. **`syncFinished` 无成败标志**：必须自行汇总 `MavenProject.problems`；且 `MavenSyncConsole` 的错误状态多为 internal 成员，不能直接读。注意 `syncFinished` 时"插件解析与源码下载可能尚未完成"（源码注释原话），MCP 上报"同步完成"应说明该语义。
4. **App 级 Topic 的多项目路由**：`MavenSyncListener.TOPIC` 是 `@Topic.AppLevel`，一个订阅收到所有 project 的事件，必须用回调参数 `project` 过滤——这对 IdeaBridge 是特性而非坑，但漏过滤会把 A 项目的同步事件推给绑定 B 项目的 Agent。
5. **同步的并发语义**：Maven 插件内部对同步做了排队/合并（连续 schedule 会合并为一次），MCP 层不必自己做互斥，但"我触发的这次"与"IDE 自动触发的一次"不可区分——`MavenSyncSpec` 的 description 不会出现在监听器回调中。如需精确关联，建议用 suspend 版 `updateAllMavenProjects` 串行等待而非 Topic。
6. **Untrusted project**：Reload 前 IDE 会做 trust 确认（`confirmLoadingUntrustedProject`）。Headless/MCP 触发时若项目未受信任，同步可能被拒或弹窗挂起；IdeaBridge 应先检查 `TrustedProjects` 状态并向 Agent 返回明确错误。
7. **`MavenProjectsManager.getInstance()` 类加载**：在核心（非 optional）classloader 中引用会直接 `ClassNotFoundException`（JetBrains 论坛多例）。所有 Maven 类型必须隔离在 optional descriptor 注册的类里，包括方法签名与 catch 子句。
8. **`findModule` 需 ReadLock、`getProjects()` 是快照**：同步进行中读到的树可能是旧的；`isInitialized()` 为 false 时（启动早期）多数查询返回空。
9. **runBatch 无 Run 窗口**：`MavenRunner.run/runBatch` 走 descriptor-less 执行，不产生 Run 内容标签，用户不可见；与"人机共视"目标冲突，仅适合内部静默任务。
10. **`MavenRunnerParameters.cmdOptions` 是整串**：额外参数（`-T`、`-U`、`-X`）以空格拼接字符串传入，含空格路径需自行引号处理；`-D` 属性建议走 `MavenRunnerSettings.setMavenProperties` 而非 cmdOptions。
11. **skipTests 的两个层面**：`MavenRunnerSettings.setSkipTests(true)` 是 runner 级持久设置；若只想影响单次执行，应 clone settings 后传给 `runConfiguration`，不要改全局单例（`MavenRunner.getSettings()` 返回的是活对象）。
12. **旧教程失效**：网上大量 `MavenProjectsManager.waitForImportCompletion()`、`importProjects()`、`scheduleArtifactsDownloading()` 示例均基于 ≤2023.1，已删除，不要参考。

---

## 6. 参考来源

- MavenProjectsManager.java（master）：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java>
- MavenProjectsManagerEx.kt（master 与 242 分支，`MavenAsyncProjectsManager` 接口）：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManagerEx.kt> / <https://github.com/JetBrains/intellij-community/blob/242/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManagerEx.kt>
- MavenSyncSpec.kt：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/buildtool/MavenSyncSpec.kt>
- MavenSyncListener.kt（master 与 242）：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenSyncListener.kt>
- MavenImportListener.java：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenImportListener.java>
- MavenProject.kt：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProject.kt>
- MavenProjectProblem.java / MavenExplicitProfiles.java / MavenProfileKind.java（maven-server-api）：<https://github.com/JetBrains/intellij-community/tree/master/plugins/maven-server-api/src/main/java/org/jetbrains/idea/maven/model>
- MavenRunnerParameters.java / MavenRunner.java / MavenRunnerSettings.java / MavenRunConfigurationType.java：<https://github.com/JetBrains/intellij-community/tree/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/execution>
- MavenGeneralSettings.java：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenGeneralSettings.java>
- 工具窗口 Actions（ReimportAction、IncrementalSyncAction、ToggleProfileAction、Download*、AddManagedFilesAction、RemoveManagedFilesAction 等）：<https://github.com/JetBrains/intellij-community/tree/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions>
- MavenArtifactDownloader.kt：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenArtifactDownloader.kt>
- MavenSyncConsole.kt：<https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/buildtool/MavenSyncConsole.kt>
- SDK 文档 Plugin Dependencies（optional depends + config-file + bundledPlugin）：<https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html>
- JetBrains 论坛：scheduleArtifactsDownloading 于 2023.2 删除、downloadArtifacts 替代：<https://intellij-support.jetbrains.com/hc/en-us/community/posts/13744541641106-org-jetbrains-idea-maven-project-MavenProjectsManager-scheduleArtifactsDownloading-is-gone>
- JetBrains 论坛：MavenImportListener 用法（官方答复）：<https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000245084-get-notified-when-a-maven-project-is-imported>
- IntelliJ Platform Explorer（listener topic 索引）：<https://plugins.jetbrains.com/intellij-platform-explorer/listeners?topics=org.jetbrains.idea.maven.project.MavenImportListener>
