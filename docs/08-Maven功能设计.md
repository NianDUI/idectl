# 08 · Maven 功能设计

> 详细 API 依据见 [research/04-maven-integration-api.md](research/04-maven-integration-api.md)。工具契约见 [03 规范 §7](03-MCP工具API规范.md)。里程碑 M5。

## 1. 类加载隔离（硬约束，D13）

- `plugin.xml`：`<depends optional="true" config-file="ideabridge-maven.xml">org.jetbrains.idea.maven</depends>`。
- 核心 classloader **零** `org.jetbrains.idea.maven` 引用（含方法签名与 catch 类型，否则 Maven 禁用时 NoClassDefFoundError）；核心只见 `MavenFacade` 接口，实现与 Maven 工具注册全部在 optional descriptor。
- 探测用 `MavenProjectsManager.getInstanceIfCreated(project)` / facade 是否注册；缺席时 maven_* 返回 `UNAVAILABLE`（结构化降级，FR-MVN-6）。

## 2. 同步（maven_sync = 工具窗口"全部刷新"，截图 4）

```
1. withContext(EDT) { FileDocumentManager.saveAllDocuments() }     // 与 IDE 按钮行为一致
2. 前置检查 TrustedProjects：untrusted → 结构化错误（附 IDE 内操作指引），绝不触发信任弹窗挂死
3. 主路径（2023.2+ 协程化 API）：suspend updateAllMavenProjects(MavenSyncSpec)  ← 精确等待"我这次"
   保底路径：forceUpdateAllProjectsOrFindAllAvailablePomFiles()（233~2026 全版本稳定）
            + MavenSyncListener.TOPIC(App 级, 回调带 Project 参数天然支持多项目路由) 等 syncFinished
4. 成败判定：sync 完成后汇总所有 MavenProject.problems
   （MavenProjectProblem.type ∈ SYNTAX|STRUCTURE|DEPENDENCY|PARENT|SETTINGS_OR_PROFILES|REPOSITORY）
   —— syncFinished 本身无成败标志（R04），problems 为空 = 成功
5. 同项目并发 sync 请求折叠为同一次并共享结果（插件内部本就排队合并，MCP 层不另加锁）
```

## 3. 模块树（maven_modules）

`getRootProjects()` + `getModules(aggregator)` 递归重建（`isAggregator` 区分聚合/叶子），输出与 Maven 工具窗口一致的树（截图 4：hotel-facai-parent (root) → hotel-facai-core-* 子模块）；每节点附 `mavenId(groupId:artifactId:version)/pomPath/packaging/profiles/problems`。

## 4. 执行 goal（maven_run_goal）

- 路线：`MavenRunnerParameters` + `MavenRunConfigurationType.runConfiguration(...)`——内部走标准 `ExecutionEnvironment + runner.execute`（R04 已核实），因此：
  - **自动复用** 06 的执行会话注册表与 07 的控制台捕获/检索（人机同视 Run tab）；
  - 返回标准 sessionId，`console_read/search` 直接可用。
- `skipTests/vmOptions` 落在 `MavenRunnerSettings`（**clone 后改**，不污染全局设置）；profiles/properties 落 MavenRunnerParameters。
- 权限：白名单 goal（clean/validate/compile/test/package/verify）=operator；白名单外 ≈ RCE=admin（04 §5）。

## 5. Profiles（maven_profiles）

- 枚举：全部 MavenProject 的 `profilesIds` 聚合 + `getExplicitProfiles()` 得 `state: none|explicit|implicit`；
- 变更：`getExplicitProfiles().clone()` → 改 → `setExplicitProfiles()`，自动触发增量同步并在返回中 `syncTriggered=true`。

## 6. 下载源码（maven_download_sources，P2）

版本断层：242 为 `downloadArtifacts(projects, artifacts, sources, docs)`，2026 master 改为 `MavenDownloadSourcesRequest.builder()`（R04）→ 运行期反射探测双签名分派；均失败回退触发 IDE action；工具标注 best-effort。

## 7. 边界

| 场景 | 行为 |
| --- | --- |
| 非 Maven 项目 | maven_* → `UNAVAILABLE`（附"该项目非 Maven 工程"） |
| Maven 插件被禁用 | 同上（classloader 隔离保证不崩） |
| untrusted project | 结构化错误 + 指引，不弹窗 |
| pom 改坏后 sync | problems 携带 SYNTAX/STRUCTURE 明细，Agent 可直接定位 pom 与原因 |
| 忽略的 pom（ignored） | modules 树中标注，v1 不提供改动工具（P2 观察） |
