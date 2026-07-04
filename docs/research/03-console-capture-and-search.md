# 调研报告 03：以编程方式捕获与检索运行/调试控制台输出

> Idectl 设计调研 · 2026-07 · 目标平台：IntelliJ IDEA 2024.2+（plugin.xml sinceBuild=233，无 untilBuild）
> 本报告所有类名/方法签名均对照 `github.com/JetBrains/intellij-community` master 分支源码与 IntelliJ Platform SDK 官方文档核实，来源见文末。

---

## 1. 概述

Idectl 需要把 IDE 内每个运行/调试会话（含用户手动点绿色按钮启动的会话）的控制台输出，变成 MCP 工具可增量读取（offset/tail）、可服务端检索（正则 grep + 上下文）的数据源。核心结论先行：

1. **不要从 `ConsoleView` 读回文本**。`ConsoleViewImpl` 位于 `platform/lang-impl` 的 impl 包（非稳定 API，且 master 已改写为 Kotlin），其文本先进入延迟 `TokenBuffer`（默认 200ms 才 flush 到 `Document`），`Document` 又受 cycle buffer（默认 1024KB）裁剪，Editor 懒创建（工具窗未展开时可能为 null），读取还受 EDT/ReadAction 约束。**正确方案是自建缓冲**：在进程输出的源头 `ProcessHandler` 上挂 `ProcessListener`，把文本写入插件自己的环形缓冲。
2. **挂监听的时机是 `ExecutionListener.processStarting(executorId, env, handler)`**。源码 javadoc 明确它 "Called before `ProcessHandler#startNotify()`"；而 `OSProcessHandler` 从 `startNotify()` 才开始泵读子进程 stdout/stderr，所以在 `processStarting` 挂监听保证零丢失。`processStartScheduled` 更早但此时还拿不到 `ProcessHandler`。该 Topic（`ExecutionManager.EXECUTION_TOPIC`）覆盖**一切**经 Run Configuration 启动的会话——包括用户手点绿色按钮——因为它们都走 `ExecutionManager`。
3. ANSI 处理：`ColoredProcessHandler` 内部用 `AnsiEscapeDecoder` 把带转义序列的文本切成 `(纯文本, ProcessOutputType-颜色子类型)` 片段；我们的监听器收到的 `outputType` 可能是颜色子类型，用 `ProcessOutputType.getBaseOutputType()` / 静态 `isStdout(Key)` 归一到 stdout/stderr/system。对非 Colored 的 handler，原始文本可能仍含 ESC 序列，需自持一个 `AnsiEscapeDecoder` 剥离。
4. 结构化测试结果：订阅 project 级 Topic `SMTRunnerEventsListener.TEST_STATUS`（名为 `"test status"`），从 `SMTestProxy` 读通过/失败/耗时/失败堆栈，无需解析控制台文本。
5. 构建与 Maven 同步输出：`BuildViewManager` / `SyncViewManager` 都继承 `AbstractViewManager`（实现 `BuildProgressListener` 与 `BuildProgressObservable`），通过 `addListener(BuildProgressListener, Disposable)`（`@ApiStatus.Experimental`）可订阅 `StartBuildEvent / OutputBuildEvent / MessageEvent / FinishBuildEvent` 事件流，可复用同一套环形缓冲与检索设施。

---

## 2. 关键 API 详解

### 2.1 捕获源头：`ProcessHandler` + `ProcessListener`

`com.intellij.execution.process.ProcessListener`（`platform/util`，接口方法全部为 `default` 空实现）：

```java
public interface ProcessListener extends EventListener {
  default void startNotified(@NotNull ProcessEvent event) {}
  default void processTerminated(@NotNull ProcessEvent event) {}
  default void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {}
  default void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {}
  default void processNotStarted() {}
}
```

注意：`ProcessAdapter` 在 master 中已标记 `@Deprecated`，javadoc 写明 "use `ProcessListener` directly"（其方法早已 default 化）。新代码直接 `object : ProcessListener {}`。

`ProcessHandler`（`platform/util/src/com/intellij/execution/process/ProcessHandler.java`）关键点：

```java
public void addProcessListener(@NotNull ProcessListener listener)
public void addProcessListener(@NotNull ProcessListener listener, @NotNull Disposable parentDisposable) // 自动随 Disposable 注销
public void notifyTextAvailable(@NotNull String text, @NotNull Key outputType) // 立即分发，无排队
public void startNotify() // state: INITIAL -> RUNNING，触发 startNotified 事件
```

- `startNotify()` 语义：`myState.compareAndSet(State.INITIAL, State.RUNNING)` 成功后广播 `startNotified`。`notifyTextAvailable` **不做任何缓冲**，来一条立刻同步分发给所有已注册监听器。
- 对最常用的 `OSProcessHandler`（基于 `BaseOutputReader`），子进程流的读取线程在 `startNotify()` 时才启动，SDK 文档也写 "call the `startNotify()` method to capture its output"。因此**在 startNotify 之前注册的监听器不会丢输出**。
- `destroyProcess()` / `detachProcess()` 若在 startNotify 之前调用会被挂起排队（`TasksRunner`），startNotify 后执行。

### 2.2 挂监听时机：`ExecutionListener` / `EXECUTION_TOPIC`

`com.intellij.execution.ExecutionManager`（`platform/execution/src/.../ExecutionManager.kt`）：

```kotlin
@Topic.ProjectLevel
val EXECUTION_TOPIC: Topic<ExecutionListener> =
  Topic("configuration executed", ExecutionListener::class.java, Topic.BroadcastDirection.TO_PARENT)

abstract fun getRunningProcesses(): Array<ProcessHandler>   // 当前所有 run/debug tab 管理的进程
@ApiStatus.Internal
abstract fun getRunningDescriptors(condition: Condition<in RunnerAndConfigurationSettings>): List<RunContentDescriptor>
```

`com.intellij.execution.ExecutionListener` 回调（含源码 javadoc 时序）：

| 回调 | 签名 | 时机 |
|---|---|---|
| `processStartScheduled` | `(executorId: String, env: ExecutionEnvironment)` | 执行被调度，**尚无 ProcessHandler** |
| `processStarting` | `(executorId, env)` | 启动流程开始（旧重载，无 handler） |
| `processStarting` | `(executorId, env, handler: ProcessHandler)` | **"Called before `ProcessHandler#startNotify()`"** ← 在此挂 `addProcessListener` |
| `processStarted` | `(executorId, env, handler)` | "Called after `ProcessHandler#startNotify()`"，此时挂监听理论上可能已丢首批输出 |
| `processNotStarted` | `(executorId, env)` / `(executorId, env, cause: Throwable)` | 启动失败 |
| `processTerminating` / `processTerminated` | `(executorId, env, handler[, exitCode: Int])` | 结束前/后 |

**结论**：
- 在 `processStarting(executorId, env, handler)` 中挂监听是官方语义保证的"不丢早期输出"点；`processStartScheduled` 只用于提前建立会话记录（分配 sessionId、记录 `env.runProfile.name`、`env.executor.id`、`env.project`）。
- 该 Topic 是 project 级 + `TO_PARENT` 广播；每个打开的项目各自订阅即可天然实现"不同项目路由不同 Agent"。用 `plugin.xml` 的 `<projectListeners>` 声明式注册最稳（IDE 启动即生效，不依赖插件代码先跑）：

```xml
<projectListeners>
  <listener class="com.niandui.idectl.execution.IdectlExecutionListener"
            topic="com.intellij.execution.ExecutionListener"/>
</projectListeners>
```

- **用户手点绿色按钮的会话同样触发这些回调**（绿色按钮 = `ProgramRunnerUtil.executeConfiguration` → `ExecutionManager`），无需特判。
- 插件初始化时 IDE 里可能已有在跑的会话：用 `ExecutionManager.getInstance(project).getRunningProcesses()` 兜底补挂（这些会话的历史输出已不可考，标记 `attachedLate=true`，`firstOffset>0` 语义见 §4）。
- 枚举会话/关联 UI tab：`com.intellij.execution.ui.RunContentManager`：

```java
static @NotNull RunContentManager getInstance(@NotNull Project project);
@NotNull List<RunContentDescriptor> getAllDescriptors();
@Nullable RunContentDescriptor findContentDescriptor(Executor executor, ProcessHandler handler);
Topic<RunContentWithExecutorListener> TOPIC = Topic.create("Run Content", RunContentWithExecutorListener.class);
```

`RunContentDescriptor.getProcessHandler()` / `getDisplayName()` 可拼出会话清单；`ExecutionEnvironment.getExecutionId()`（long）适合做内部会话主键的一部分。

### 2.3 stdout/stderr/system 判别：`ProcessOutputType`

`com.intellij.execution.process.ProcessOutputType`（`platform/util`，本身是 `Key<Object>` 的子类）：

```java
public static final ProcessOutputType STDOUT; // 进程标准输出
public static final ProcessOutputType STDERR; // 进程标准错误
public static final ProcessOutputType SYSTEM; // IDE 自身产生的系统消息（命令行回显、退出码行等）

public ProcessOutputType(@NotNull String name, @NotNull ProcessOutputType streamType); // 颜色子类型
public @NotNull ProcessOutputType getBaseOutputType();
public boolean isStdout(); public boolean isStderr(); public boolean isSystem();
public @Nullable String getEscapeSequence();
public static boolean isStdout(@NotNull Key<?> key);   // 静态版直接对 Key 判别
public static boolean isStderr(@NotNull Key<?> key);
public static boolean isSystem(@NotNull Key<?> key);
public static ProcessOutputType fromKey(@NotNull Key<?> key); // 非 ProcessOutputType 抛 IllegalArgumentException
```

要点：ANSI 上色后，`onTextAvailable` 收到的 `outputType` 是**颜色子类型**（如 "stdout:36" 之类，`getBaseOutputType()==STDOUT`），所以判别必须用静态 `ProcessOutputType.isStdout(key)` 而非 `key == ProcessOutputType.STDOUT` 的引用比较。历史遗留代码还可能传 `ProcessOutputTypes.STDOUT/STDERR/SYSTEM`（普通 `Key`，与 `ProcessOutputType` 常量是同一批实例，兼容）。

### 2.4 ANSI 转义处理：`ColoredProcessHandler` 与 `AnsiEscapeDecoder`

`com.intellij.execution.process.ColoredProcessHandler`（`platform/platform-util-io`）：

```java
public class ColoredProcessHandler extends KillableProcessHandler
    implements AnsiEscapeDecoder.ColoredTextAcceptor {
  private final AnsiEscapeDecoder myAnsiEscapeDecoder = new AnsiEscapeDecoder();
  @Override public final void notifyTextAvailable(@NotNull String text, @NotNull Key outputType) {
    myAnsiEscapeDecoder.escapeText(text, outputType, this); // 解码后再走 super.notifyTextAvailable
  }
  @Override public void coloredTextAvailable(@NotNull String text, @NotNull Key attributes) { ... }
}
```

`com.intellij.execution.process.AnsiEscapeDecoder`（同包）：

```java
public void escapeText(@NotNull String text, @NotNull Key outputType, @NotNull ColoredTextAcceptor textAcceptor)

@FunctionalInterface
public interface ColoredTextAcceptor {
  void coloredTextAvailable(@NotNull String text, @NotNull Key attributes);
}
```

内部：按 stdout/stderr 各持一个 `AnsiStreamingLexer`（**有跨 chunk 状态**，转义序列可能被 chunk 边界劈开），把 SGR 序列喂给 `AnsiTerminalEmulator`，再经 `ColoredOutputTypeRegistry.getOutputType()` 映射为带颜色的 `ProcessOutputType` 子类型；非 SGR 控制序列被剥离丢弃。

对 Idectl 的含义：

- 若目标 handler 是 `ColoredProcessHandler`（Spring Boot/Gradle/Maven 运行配置基本都是），我们在 `onTextAvailable` 收到的**已是剥好转义的纯文本** + 颜色子类型 Key，直接入缓冲即可（可选：把颜色 key 名一并存为元数据）。
- 若是普通 `OSProcessHandler`，文本可能含原始 `[...m`。方案：**每个会话自持一个 `AnsiEscapeDecoder`**（切勿多会话共享——lexer 有状态），在监听器里 `decoder.escapeText(text, outputType) { plain, key -> buffer.append(plain, key) }`。
- 简单粗暴的 `text.replace(Regex("\\[[0-9;]*m"), "")` 会漏掉被 chunk 劈开的序列与非 SGR 序列，不推荐作为主方案。

### 2.5 从 `ConsoleView` 读回文本：可行但不建议（对比自建缓冲）

`com.intellij.execution.impl.ConsoleViewImpl`（`platform/lang-impl`，master 已是 Kotlin）：

```kotlin
val text: String get() = editor!!.document.text     // editor 为懒创建, 可能为 null!
open val editor: Editor? get() = synchronized(LOCK) { myEditor }
```

事实链（均已源码核实）：

- 打印路径是 `print()` → 延迟 `TokenBuffer`（"the text from .print goes there and stays there until .flushDeferredText is called"）→ 定时/超限 flush 到 `Document`。flush 周期 `console.flush.delay.ms` 系统属性，**默认 200ms**。
- `Document` 内容受 cycle buffer 裁剪：`ConsoleBuffer.useCycleBuffer()`（系统属性 `idea.cycle.buffer.size` 设为 `"disabled"` 时关闭）、`ConsoleBuffer.getCycleBufferSize()`（`UISettings.overrideConsoleCycleBufferSize` 优先，否则 `idea.cycle.buffer.size` **默认 1024，单位 KB，即约 1MB**）。超限时头部被删除且**无任何回调通知**。
- `ConsoleViewImpl` 在 `com.intellij.execution.impl` 包，属 impl/非稳定 API；master 从 Java 改写为 Kotlin，字段与部分方法签名在 233→252 间已有漂移，若强依赖需按版本反射适配，维护成本高。
- 拿到 `ConsoleView` 实例本身也别扭：`RunContentDescriptor.getExecutionConsole()` 返回 `ExecutionConsole`，可能是 `ConsoleViewImpl`，也可能是包装层（测试会话是 `SMTRunnerConsoleView`，Build 输出是 `BuildView`），需要层层 instanceof/unwrap。

| 维度 | 读 ConsoleView/Document | 自建缓冲（推荐） |
|---|---|---|
| 完整性 | 受 1MB cycle buffer 静默截断；200ms 延迟 | 上限自定，截断有明确 offset 语义 |
| 实时性 | 落后真实输出 ≤200ms+EDT 排队 | onTextAvailable 即时 |
| stdout/stderr 区分 | Document 是纯文本，需从高亮区间反推 | 原生带 `ProcessOutputType` |
| 线程 | Document 读取需 ReadAction（EDT 或 BGT+RA） | 自己的锁/协程，无平台线程约束 |
| API 稳定性 | impl 包，Kotlin 化后签名漂移 | 仅依赖 `ProcessListener`（util 层，极稳定） |
| 时间戳/行号索引 | 无 | 写入时打点，天然支持 |
| 内存 | 与 Document 双份?（否，只读） | 需要自己付一份内存，上限可控 |

唯一值得考虑 ConsoleView 读回的场景：插件晚于会话启动而 attach（丢失的历史只有 Document 里还留着一份）。可作为 `attachedLate` 会话的一次性"抢救导入"（EDT + `ReadAction.compute`），导入后仍走自建缓冲。

### 2.6 自建环形缓冲设计（按行 + 字节双上限）

业界参数参考：IDEA 自身 console cycle buffer 默认 **1MB**（`idea.cycle.buffer.size=1024` KB）；VS Code 集成终端 scrollback 默认 **1000 行**（`terminal.integrated.scrollback`，xterm.js 环形 buffer）；GitHub Actions 流式日志约 **4MB** 即截断、Web UI 按"至少 5 万行"设计渲染、完整日志转离线下载。对 AI Agent 消费场景，建议默认 **每会话 10_000 行 且 8MB，先到为准**，可配置；单行超长（如 1MB 的 JSON 一行）按 16KB 截断存储并标记 `lineTruncated`。

核心设计（Kotlin 草图）：

```kotlin
class ConsoleRingBuffer(
  private val maxLines: Int = 10_000,
  private val maxBytes: Long = 8L * 1024 * 1024,
  private val maxLineBytes: Int = 16 * 1024,
) {
  data class Line(
    val offset: Long,          // 全局单调递增行号, 从 0 起, 淘汰后永不复用
    val timestamp: Long,       // epoch millis, 写入时打点
    val stream: Stream,        // STDOUT / STDERR / SYSTEM
    val text: String,          // 已剥 ANSI、不含行尾 \n
    val truncated: Boolean,
  )
  enum class Stream { STDOUT, STDERR, SYSTEM }

  private val deque = ArrayDeque<Line>()
  private var bytes = 0L
  private var nextOffset = 0L          // 下一行将获得的 offset
  private var firstOffset = 0L         // 当前仍保留的最老行 offset
  private var evictedLines = 0L        // 累计淘汰行数(审计/truncated 标记用)
  private val lock = ReentrantReadWriteLock()
  private var pending = StringBuilder() // ProcessHandler 的 chunk 不保证按行到达, 需拼行
  // append(chunk, streamType): 按 \n 切行, 尾部残段留在 pending; 每行入 deque 并驱逐超限头部
  // read(fromOffset, maxLines): 返回 lines + nextOffset + firstAvailableOffset + evicted 标志
  // tail(n): 等价 read(max(firstOffset, nextOffset - n), n)
}
```

关键语义决策：

- **offset = 全局逻辑行号，单调递增，淘汰不回退**。`console_read(sessionId, fromOffset, maxLines)` 返回 `{lines, nextOffset, firstAvailableOffset}`；Agent 下次带 `nextOffset` 来即为增量读取。
- **溢出语义**：若 `fromOffset < firstAvailableOffset`，不报错，从 `firstAvailableOffset` 起返回并置 `gap=true`（含丢失行数），让 Agent 知道中间被淘汰——对齐 Kafka consumer 落后于 log retention 的处理方式。
- **tail 语义**：`tail(n)` 取最后 n 行；`follow` 型需求用"读到 nextOffset 后轮询/长轮询"实现，MCP 工具层不做真流式（一次调用一次响应）。
- **行拼装**：`onTextAvailable` 的 chunk 与行边界无关（一个 chunk 可含多行/半行；stdout 与 stderr 是两条独立流）。**按 stream 分别持有 pending 半行**，进程终止时 flush 残段为最后一行。
- **时间戳按行打点**（写入时 `System.currentTimeMillis()`），供检索的时间范围过滤；注意这是"IDE 收到时间"而非进程打印时间。
- 内存估算：10k 行 × 平均 200B ≈ 2MB/会话，Java String 双份开销后 <8MB 上限可控；多会话总量再加一层全局上限（如 128MB，LRU 淘汰已终止最久的会话缓冲）。

### 2.7 服务端检索（grep）设计

对齐 `grep -E -i -A -B -m` 语义的 MCP 工具参数：

```kotlin
data class ConsoleSearchRequest(
  val sessionId: String,
  val pattern: String,             // java.util.regex.Pattern, 语法近似 grep -E
  val ignoreCase: Boolean = false, // Pattern.CASE_INSENSITIVE or CASE_INSENSITIVE+UNICODE_CASE
  val beforeContext: Int = 0,      // -B, 上限如 20
  val afterContext: Int = 0,       // -A
  val maxMatches: Int = 100,       // -m, 硬上限如 1000
  val fromOffset: Long? = null,    // 行号范围下界(含)
  val toOffset: Long? = null,      // 上界(不含)
  val sinceMillis: Long? = null,   // 时间范围
  val untilMillis: Long? = null,
  val streams: Set<Stream>? = null // 只搜 stderr 等
)
data class SearchHit(val line: Line, val before: List<Line>, val after: List<Line>,
                     val matchRanges: List<IntRange>)
```

实现要点：

- 在读锁下对 deque 做**快照式线性扫描**（10k 行毫秒级，无需倒排索引）；命中行前后取上下文时对相邻命中做区间合并（同 grep 的 `--` 分隔组语义）。
- **ReDoS 防护**：`java.util.regex` 无原生超时，用"包装 CharSequence 在 charAt 里检查 deadline 并抛异常"的经典手法（或限制 pattern 长度 + 禁嵌套量词的预检），单次搜索预算如 500ms。
- 返回体附 `totalScanned / truncatedByMaxMatches / bufferGap` 元数据，让 Agent 明确知道结果是否完整。
- 权限：检索是只读操作，Reader 角色即可；但要按"会话所属项目"过滤可见性（会话绑定 project，Agent 会话未绑定该 project 则不可见）。

### 2.8 结构化测试结果：`SMTRunnerEventsListener` + `SMTestProxy`

`com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener`（`platform/smRunner`）：

```java
@Topic.ProjectLevel
Topic<SMTRunnerEventsListener> TEST_STATUS = new Topic<>("test status", SMTRunnerEventsListener.class);

void onTestingStarted(@NotNull SMTestProxy.SMRootTestProxy testsRoot);
void onTestingFinished(@NotNull SMTestProxy.SMRootTestProxy testsRoot);
default void onBeforeTestingFinished(@NotNull SMTestProxy.SMRootTestProxy testsRoot);
void onTestStarted(@NotNull SMTestProxy test);      // 另有带 nodeId/parentNodeId 的重载
void onTestFinished(@NotNull SMTestProxy test);
void onTestFailed(@NotNull SMTestProxy test);
void onTestIgnored(@NotNull SMTestProxy test);
void onSuiteStarted(@NotNull SMTestProxy suite);
void onSuiteFinished(@NotNull SMTestProxy suite);
void onSuiteTreeStarted / onSuiteTreeNodeAdded(...); // 构树阶段事件
```

`SMTestProxy` 可读字段（全部核实自 master 源码）：

```java
@Nullable Long getDuration();                 // 毫秒
@Nullable @NlsSafe String getErrorMessage();  // 失败消息
@Nullable @NlsSafe String getStacktrace();    // 失败堆栈(字符串)
boolean isPassed(); boolean isDefect(); boolean isIgnored(); boolean isFinal();
boolean isInProgress(); boolean isSuite();
TestStateInfo.Magnitude getMagnitudeInfo(); int getMagnitude();
List<? extends SMTestProxy> getChildren(); List<SMTestProxy> getAllTests();
SMTestProxy getParent(); SMRootTestProxy getRoot();
String getName(); @Nullable String getPresentableName();
@Nullable String getLocationUrl(); @Nullable String getMetainfo();
// SMRootTestProxy 补充:
ProcessHandler getHandler(); long getExecutionId(); boolean isTestsReporterAttached();
```

设计结论：

- **能满足需求**：订阅 `TEST_STATUS`，在 `onTestingStarted` 用 `root.getHandler()` / `root.getExecutionId()` 与 §2.2 建立的会话记录做关联，把测试树增量事件序列化进会话的"结构化通道"（与文本缓冲并列），`console_get_test_results(sessionId)` 直接返回树 + 汇总（passed/failed/ignored/duration）。
- 覆盖范围：所有基于 SM (Service Messages) 框架的 runner——IDEA 内建 JUnit/TestNG runner，以及**委托给 Gradle 跑的测试**（Gradle 侧有 `GradleSMTestProxy` 桥接，事件同样进该 Topic）。Maven Surefire 直跑（`mvn test` 作为普通 goal）则只有文本输出，无 SM 树。
- `getLocationUrl()` 形如 `java:test://com.foo.BarTest/testBaz`，可回链到 PSI（解析需 ReadAction，序列化给 Agent 时只传字符串即可）。
- 注意 `getDuration()` 可能为 null（未结束/框架未报告）；失败细节在 `onTestFailed` 时刻已可读。

### 2.9 构建与 Maven 同步输出：`BuildViewManager` / `SyncViewManager`

```java
// com.intellij.build.BuildProgressListener (platform/lang-api)
public interface BuildProgressListener {
  void onEvent(@NotNull Object buildId, @NotNull BuildEvent event);
}

// com.intellij.build.AbstractViewManager (platform/lang-impl)
public abstract class AbstractViewManager
    implements ViewManager, BuildProgressListener, BuildProgressObservable, Disposable {
  @ApiStatus.Experimental
  public void addListener(@NotNull BuildProgressListener listener, @NotNull Disposable disposable);
}

public class BuildViewManager extends AbstractViewManager { ... } // Build 工具窗 "Build Output" tab, project service
public class SyncViewManager  extends AbstractViewManager { ... } // Build 工具窗 "Sync" tab, project service
```

事件类型（`com.intellij.build.events.*`，lang-api）：

- `StartBuildEvent` / `FinishBuildEvent`（含 `EventResult`：Success/Failure/Skipped）
- `OutputBuildEvent`：`@NotNull String getMessage()`；`@NotNull ProcessOutputType getOutputType()`（**较新 API**；旧版为 `@Deprecated boolean isStdOut()`，233 兼容层建议先调 `isStdOut()` 或反射探测 `getOutputType`）
- `ProgressBuildEvent`：进度
- `MessageEvent`：`Kind getKind()`（`ERROR, WARNING, INFO, STATISTICS, SIMPLE`）、`String getGroup()`、`@Nullable Navigatable getNavigatable(Project)`、`MessageEventResult getResult()`——**结构化编译错误/警告的直接来源**（与调研 01 的 `CompilationStatusListener` 互补，覆盖委托给 Maven/Gradle 的构建）
- 事件有 `getId()/getParentId()` 构成树（buildId → task → message）

用法（JetBrains 官方支持渠道确认的模式）：

```kotlin
project.service<BuildViewManager>().addListener(bridgeBuildListener, disposable)  // JPS/委托构建输出
project.service<SyncViewManager>().addListener(bridgeSyncListener, disposable)    // Maven/Gradle reimport 输出
```

- Maven 的 import/reimport 走 `MavenSyncConsole` → `SyncViewManager`，因此 **Maven 同步的完整日志与 MessageEvent(ERROR/WARNING) 都能被订到**，可复用 §2.6 的环形缓冲（以 `buildId.toString()` 为会话键，会话类型 `BUILD`/`SYNC`）。
- Maven **goal 执行**（`MavenRunConfigurationType`）是普通运行配置 → 走 §2.2 的 `EXECUTION_TOPIC` + `ProcessHandler` 路径，天然被控制台缓冲覆盖。
- `addListener` 标注 `@ApiStatus.Experimental`：语义为"签名可能变化"，但该 API 自 2019 起存在且被社区广泛使用，风险可接受；封装在单独适配层，便于版本升级时集中修改。

### 2.10 端到端 Kotlin 草图

```kotlin
class IdectlExecutionListener(private val project: Project) : ExecutionListener {

  override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
    SessionRegistry.getInstance(project).preRegister(env) // 分配 sessionId, 记录配置名/executor
  }

  override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
    val session = SessionRegistry.getInstance(project).bind(env, handler)
    val decoder = AnsiEscapeDecoder() // 每会话一个, 有状态
    handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        // 注意: 该回调在输出泵线程(pooled)上, 不可做重活/不可碰 PSI
        if (handler is ColoredProcessHandler) {
          // 已剥 ANSI; outputType 可能是颜色子类型
          session.buffer.append(event.text, streamOf(outputType), System.currentTimeMillis())
        } else {
          decoder.escapeText(event.text, outputType) { plain, key ->
            session.buffer.append(plain, streamOf(key), System.currentTimeMillis())
          }
        }
      }
      override fun processTerminated(event: ProcessEvent) {
        session.markTerminated(event.exitCode)
      }
    }, session.disposable)
  }

  private fun streamOf(key: Key<*>): Stream = when {
    ProcessOutputType.isStderr(key) -> Stream.STDERR
    ProcessOutputType.isSystem(key) -> Stream.SYSTEM
    else -> Stream.STDOUT
  }
}
```

---

## 3. 线程模型注意事项

| 场景 | 线程 | 约束 |
|---|---|---|
| `ProcessListener.onTextAvailable` | `BaseOutputReader` 输出泵线程（pooled BGT），stdout/stderr 可来自不同线程 | 监听器被**同步**调用——阻塞它会拖慢整个输出管道乃至其他监听器（含 ConsoleView 本身）。缓冲写入必须 O(1) 快路径；重活（如通知在等待的 MCP 长轮询）投递到协程 Channel |
| `ExecutionListener.*`（MessageBus） | 事件由发布方线程同步分发，`ExecutionManagerImpl` 基本在 EDT 上发布 | 回调内不要做 IO/慢操作；只做注册与登记 |
| `SMTRunnerEventsListener.*` | 测试事件处理线程/EDT 混合 | 同上，只抄录数据（`SMTestProxy` 的 getter 是内存读取，安全），不要在回调里遍历超大树做重计算 |
| `BuildProgressListener.onEvent` | 构建事件分发线程（非 EDT 为主） | 同上 |
| 读 `Document`（若做 ConsoleView 抢救导入） | 任意 BGT | 必须 `ReadAction.compute { document.text }`；2024.2+ 推荐 `readAction {}` 挂起版（`com.intellij.openapi.application.readAction`）或 `ReadAction.nonBlocking` |
| MCP 请求处理（read/search/tail） | Ktor/自建 server 的 IO 协程 | 只碰自建缓冲的锁，**完全不需要** EDT/ReadAction——这是自建缓冲方案的最大红利 |
| 会话清理 | — | `addProcessListener(listener, parentDisposable)` + 会话级 `Disposable`（父挂到插件级 Disposable/project），防泄漏；进程终止后缓冲保留一段时间（如 30 分钟或直到全局内存上限）供 Agent 事后检索 |

其他：环形缓冲用 `ReentrantReadWriteLock`（写多读少时可换 `synchronized` + 快照）；不要在缓冲实现里用协程 Mutex 混合阻塞调用方（onTextAvailable 是普通线程）。MCP 侧 tail/follow 用 `kotlinx.coroutines` 的 `Flow`/长轮询包装缓冲的"新行到达"信号（`Condition` 或 `Channel(CONFLATED)`）。

---

## 4. 版本兼容性（233 ~ 2026 当前）

| API | 233 (2023.3) 可用性 | 备注 |
|---|---|---|
| `ProcessListener`（default 方法） | 可用 | default 化早于 233；`ProcessAdapter` 在新版本已 `@Deprecated`（仍存在，不会编译失败） |
| `ExecutionListener.processStarting(executorId, env, handler)` 三参重载 | 可用（2019.3+ 即有） | javadoc "before startNotify" 语义稳定 |
| `ExecutionManager.EXECUTION_TOPIC` / `getRunningProcesses()` | 可用 | `getRunningDescriptors` 为 `@ApiStatus.Internal`，避免依赖 |
| `ProcessOutputType` 静态 `isStdout(Key)` 等 | 可用 | |
| `AnsiEscapeDecoder.escapeText` / `ColoredProcessHandler` | 可用 | 位于 `platform-util-io` 模块 |
| `ConsoleViewImpl` | 存在但 233 是 Java 版，master 为 Kotlin，内部结构漂移大 | **不要依赖**；`ConsoleBuffer` 同为 impl 包 |
| `SMTRunnerEventsListener.TEST_STATUS` / `SMTestProxy` | 可用（存在超过十年） | 依赖 `TestRunner` 相关模块，Java 插件已捆绑 |
| `AbstractViewManager.addListener(BuildProgressListener, Disposable)` | 可用（2019.1+） | `@ApiStatus.Experimental`，做薄适配层隔离 |
| `OutputBuildEvent.getOutputType(): ProcessOutputType` | **需验证 233 是否存在**；旧接口是 `isStdOut()`（现已 @Deprecated） | 兼容策略：优先反射探测 `getOutputType`，回退 `isStdOut()` |
| `Topic.ProjectLevel` / `<projectListeners>` | 可用（2020.1+） | |
| 挂起版 `readAction {}` | 可用（2022.2+ 逐步稳定） | 233 目标下可放心用 |

平台大趋势提醒：2024.2+ 推 Remote Dev / Frontend 拆分（源码树里已出现 `platform/buildView/frontend/FrontendBuildViewManager.kt`），本报告方案全部挂在**后端进程**语义上（ProcessHandler/MessageBus），在纯本地 IDE 下无影响；若未来要支持远程开发模式，Build 事件的前后端分流需要重新评估。

---

## 5. 已知坑与限制

1. **`processStarted` 挂监听可能丢首行**：与 `startNotify()` 存在竞态（读取线程已启动）。必须用 `processStarting(…, handler)`。
2. **chunk ≠ 行**：`onTextAvailable` 的 text 可以是半行、多行、甚至 `\r` 进度条刷新（Maven 下载进度、Gradle rich console）。缓冲层要处理 `\r`（策略：`\r` 不产生新行而是覆盖当前 pending 行，或原样保留并在检索时归一），并按 stream 分开拼行。
3. **stdout/stderr 交错顺序不保证**：两条流由不同线程泵出，缓冲中的相对顺序是"IDE 收到序"，与进程真实打印序可能有微小偏差（这是 OS 管道固有问题，任何方案都一样）。
4. **`ColoredProcessHandler.notifyTextAvailable` 是 final**：不能靠子类拦截；但作为旁观者挂 listener 不受影响。注意收到的颜色子类型 Key 的 `toString()` 因主题/registry 而异，不要持久化解析它，只取 `getBaseOutputType()`。
5. **`AnsiEscapeDecoder` 有状态**：跨 chunk 劈开的转义序列靠内部 lexer 缓存衔接；每会话独立实例，且同一会话的调用需串行（onTextAvailable 对单个 handler 的分发本身是串行的，但 stdout/stderr 两条流内部已由 decoder 分 lexer 处理）。
6. **PTY 模式**：若运行配置启用 PTY（`PtyCommandLine`，terminal 模式的 run），stderr 会并入 stdout，且输出含大量光标控制序列；剥 ANSI 后文本可用，但 stream 判别会退化为全 STDOUT——工具响应里应标注 `pty=true`。
7. **Debug 会话**同样走 `EXECUTION_TOPIC`（executorId = `"Debug"`），控制台捕获逻辑与 Run 完全一致；但 Debug 的 "Console" 与 "Debugger" 是同一 `RunContentDescriptor`。
8. **附加到已运行会话无历史**：`getRunningProcesses()` 兜底路径只能拿到 attach 之后的输出；抢救导入 Document 文本受 1MB cycle buffer + 无 stream 标记的限制，且 `getExecutionConsole()` 拿不到时（tab 已关）彻底无解。响应里必须诚实返回 `firstAvailableOffset`。
9. **用户关闭 Run tab ≠ 进程结束**：tab 关闭可能 destroy 进程（默认）也可能 detach；靠 `processTerminated` 收尾而非 `RunContentManager.TOPIC` 的 contentRemoved。
10. **测试输出双通道重复**：测试会话中，stdout 文本既进控制台缓冲又以 service message（`##teamcity[...]`）形式驱动 SM 树。IDEA 的 SM runner 会从控制台文本里滤掉 service messages，但我们直接挂在 ProcessHandler 上会**看到原始 `##teamcity[...]` 行**——缓冲层应识别并过滤/降级存储（否则 Agent grep 时会命中噪音）。
11. **`MessageEvent.getNavigatable(project)` 需要 ReadAction/EDT**（内部可能解析 PSI/文件），序列化给 Agent 时优先用 `FileMessageEvent.getFilePosition()`（纯数据：file/line/column），不要调 getNavigatable。
12. **ReDoS**：`java.util.regex` 无超时，必须用 deadline-CharSequence 包装（见 §2.7），否则一个恶意 pattern 能卡死搜索线程。
13. **内存**：缓冲是纯 JVM 堆占用，多项目多会话叠加要有全局上限与会话 TTL；审计日志记录每次 read/search 的 sessionId + 参数即可，不要把输出内容写进审计（体积失控）。
14. **`AbstractViewManager.addListener` 收到的是所有构建的事件**（按 buildId 区分），Maven/Gradle/JPS 委托构建混在一起；用 `StartBuildEvent` 的 `BuildDescriptor`（workingDir/title）归属到项目与构建类型。

---

## 6. MCP 工具面建议（供总体设计引用）

- `run_sessions_list(project?)` → 会话清单（sessionId、configName、executorId、state、startedAt、pty、attachedLate）
- `console_read(sessionId, fromOffset?, maxLines=500)` → `{lines[], nextOffset, firstAvailableOffset, gap, sessionState}`（Reader 权限）
- `console_tail(sessionId, lines=100)`（Reader）
- `console_search(sessionId, pattern, ignoreCase?, before?, after?, maxMatches?, fromOffset?, toOffset?, sinceMillis?, untilMillis?, streams?)`（Reader）
- `test_results_get(sessionId)` → SM 树快照 + 汇总（Reader）
- `build_output_read / build_output_search(buildId, …)` → 复用同一缓冲结构（Reader）
- 每行返回结构：`{offset, ts, stream, text, truncated}`；所有读接口按会话所属 project 做可见性过滤。

---

## 7. 参考来源

平台源码（github.com/JetBrains/intellij-community，master，2026-07 抓取）：

1. `ProcessListener` — https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessListener.java
2. `ProcessHandler`（startNotify/addProcessListener/notifyTextAvailable） — https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessHandler.java
3. `ProcessAdapter`（@Deprecated, "use ProcessListener directly"） — https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessAdapter.java
4. `ProcessOutputType` — https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessOutputType.java
5. `ExecutionListener`（processStarting "before startNotify" / processStarted "after startNotify"） — https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/ExecutionListener.java
6. `ExecutionManager.kt`（EXECUTION_TOPIC = Topic("configuration executed", TO_PARENT)、getRunningProcesses） — https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/ExecutionManager.kt
7. `RunContentManager`（TOPIC "Run Content"、getAllDescriptors、findContentDescriptor） — https://github.com/JetBrains/intellij-community/blob/master/platform/execution/src/com/intellij/execution/ui/RunContentManager.java
8. `AnsiEscapeDecoder` / `ColoredTextAcceptor` — https://github.com/JetBrains/intellij-community/blob/master/platform/platform-util-io/src/com/intellij/execution/process/AnsiEscapeDecoder.java
9. `ColoredProcessHandler` — https://github.com/JetBrains/intellij-community/blob/master/platform/platform-util-io/src/com/intellij/execution/process/ColoredProcessHandler.java
10. `ConsoleViewImpl.kt`（TokenBuffer、console.flush.delay.ms=200、text getter） — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/execution/impl/ConsoleViewImpl.kt
11. `ConsoleBuffer`（idea.cycle.buffer.size 默认 1024KB、"disabled"、UISettings override） — https://github.com/JetBrains/intellij-community/blob/master/platform/execution-impl/src/com/intellij/execution/impl/ConsoleBuffer.java
12. `SMTRunnerEventsListener`（Topic "test status"） — https://github.com/JetBrains/intellij-community/blob/master/platform/smRunner/src/com/intellij/execution/testframework/sm/runner/SMTRunnerEventsListener.java
13. `SMTestProxy` — https://github.com/JetBrains/intellij-community/blob/master/platform/smRunner/src/com/intellij/execution/testframework/sm/runner/SMTestProxy.java
14. `BuildProgressListener` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/build/BuildProgressListener.java
15. `AbstractViewManager`（addListener @Experimental） — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/build/AbstractViewManager.java
16. `BuildViewManager` / `SyncViewManager` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/build/BuildViewManager.java 、 https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/build/SyncViewManager.java
17. `MessageEvent`（Kind: ERROR/WARNING/INFO/STATISTICS/SIMPLE） — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/build/events/MessageEvent.java
18. `OutputBuildEvent`（getOutputType；isStdOut @Deprecated） — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/build/events/OutputBuildEvent.java

官方文档与其他：

19. IntelliJ Platform SDK — Execution（startNotify 捕获输出、EXECUTION_TOPIC、ColoredProcessHandler 建议） — https://plugins.jetbrains.com/docs/intellij/execution.html
20. JetBrains 支持渠道确认的 BuildViewManager.addListener 用法（"Listen to Maven/Gradle Build Events"） — https://intellij-support.jetbrains.com/hc/en-us/community/posts/206756615-Listen-to-Maven-Build-Events 、 https://intellij-support.jetbrains.com/hc/en-us/community/posts/360008019860-Listen-to-Gradle-build-event
21. VS Code Terminal Basics（terminal.integrated.scrollback 默认 1000 行） — https://code.visualstudio.com/docs/terminal/basics
22. GitHub Actions 日志渲染工程博客（大日志 UI 截断、≥50k 行渲染设计） — https://github.blog/engineering/architecture-optimization/how-github-actions-renders-large-scale-logs/ ；流式日志 ~4MB 截断社区讨论 — https://github.com/orgs/community/discussions/127903
