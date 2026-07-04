# 05 — MCP 协议现状与"在 IntelliJ 插件里内嵌 MCP 服务器"的工程方案

> 调研日期：2026-07-03。所有结论均以当日可查证的一手资料为准（modelcontextprotocol.io 规范、
> modelcontextprotocol/kotlin-sdk 仓库、JetBrains/intellij-community 源码、IntelliJ Platform SDK 文档）。
> 引用的类名/方法签名均来自源码或官方文档原文。

---

## 1. 概述

### 1.1 MCP 规范版本现状（2026-07）

版本序列：`2024-11-05` → `2025-03-26` → `2025-06-18` → **`2025-11-25`（当前 stable，latest）** → `2026-07-28`（Release Candidate，2026-05-21 已锁定，计划 2026-07-28 正式发布）。

**结论：Idectl 应以 `2025-11-25` 为设计基线协议版本**，同时在设计上预留对 `2026-07-28` 的适配空间（见 1.3）。`2025-06-18` 已落后两个版本。

`2025-11-25` 相对 `2025-06-18` 的关键变化（[官方 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)）：

- 授权：支持 OpenID Connect Discovery 1.0 发现（PR #797）；`WWW-Authenticate` 增量 scope 申请（SEP-835）；推荐 OAuth Client ID Metadata Documents 作为客户端注册机制（SEP-991）。
- Elicitation：`ElicitResult`/`EnumSchema` 标准化，支持单选/多选枚举（SEP-1330）；新增 **URL mode elicitation**（SEP-1036）；elicitation schema 各原始类型支持 `default` 值（SEP-1034）。
- 新增 tools/resources/prompts 的 **icons** 元数据（SEP-973）。
- **实验性 tasks**：跟踪 durable request，支持轮询与延迟取结果（SEP-1686）——对"构建/跑测试"这类长操作非常相关。
- Streamable HTTP：无效 `Origin` 必须返回 403（PR #1439）；允许服务端随时断开 SSE 连接、客户端轮询式重连（SEP-1699）。
- 工具输入校验错误应作为 Tool Execution Error（`isError: true`）返回而非协议错误，便于模型自我纠正（SEP-1303）。
- JSON Schema 方言默认 2020-12（SEP-1613）。

### 1.2 2026-07-28 RC 要点（前瞻）

来源：[官方博客 RC 公告](https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/)。

- **协议层无状态化（stateless core）**：移除 initialize 握手与协议级 session 概念，服务器可用普通轮询负载均衡水平扩展（对我们的单机 IDE 场景影响小，但 SDK API 会变）。
- 三个一级原语被 **deprecated**（未删除）：Roots、Sampling、Logging；正式的弃用政策保证弃用到最早删除之间至少 12 个月。
- 资源不存在错误码从自定义 `-32002` 改为 JSON-RPC 标准 `-32602`（SEP-2164）。
- Tasks 从实验特性升级为扩展（extension）；新增 MCP Apps 扩展。
- Tier 1 SDK（Python/TypeScript/Go/C#）已有 beta；**Kotlin SDK 不在 Tier 1 首批 beta 列表中**，跟进节奏需持续观察。

### 1.3 对 Idectl 的总体建议（先给结论）

1. **协议**：实现 Streamable HTTP（`POST/GET/DELETE /mcp`），协议版本协商 `2025-11-25`，同时兼容旧客户端的 `2025-03-26`/`2025-06-18`。
2. **SDK**：选 **MCP Kotlin SDK（`io.modelcontextprotocol:kotlin-sdk-server`）**——JetBrains 自家 IDE 内建 MCP server 就是用它 + Ktor CIO（见 §5），等于官方替我们蹚过坑。
3. **HTTP 服务器**：**方案 A（插件自带 Ktor CIO embeddedServer）**，绑定 `127.0.0.1`，端口从固定基准递增探测；这正是 JetBrains 内建实现的路线。IDE 内建 Web 服务器（63342/`RestService`）不适合承载 SSE 长流（Netty `FullHttpRequest` 缓冲模型 + 平台超时/信任策略掣肘），仅可用作"实例发现"辅助端点。
4. **认证**：localhost 场景采用 **Bearer token（预共享 token）+ 强制 Origin/Host 校验 + 只绑 127.0.0.1**；OAuth 2.1 全套（RFC 9728 元数据、PKCE）作为可选增强，不是本地 IDE 插件的必需品（规范中 authorization 整体是 OPTIONAL）。
5. **stdio 桥**：仍需提供（部分企业环境/老客户端只支持 stdio），可参考 JetBrains 的做法：一个极薄的 stdio→HTTP 转发进程（其 `mcpserver.stdio` 子模块）。

---

## 2. 关键 API 详解

### 2.1 Streamable HTTP 传输（规范细节，2025-11-25）

来源：[Transports 章节](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。要点（MUST/SHOULD 均为规范原文措辞）：

- 服务器提供**单一 MCP endpoint 路径**（如 `/mcp`），同时支持 POST 与 GET。
- **POST**：客户端每条 JSON-RPC 消息都是一次新的 POST；`Accept: application/json, text/event-stream`。
  - body 是 *request* → 服务器返回 `Content-Type: application/json`（单个响应对象）**或** `text/event-stream`（SSE 流，流内可先发相关的服务端 request/notification，最终包含该 request 的 response，发完 SHOULD 关闭流）。
  - body 是 *notification/response* → 服务器返回 **202 Accepted 无 body**；不能接受则 400。
- **GET**：打开服务端→客户端的独立 SSE 流（server-initiated requests/notifications 用）；不支持则 405。
- **会话**：服务器 MAY 在 `InitializeResult` 的 HTTP 响应头返回 `Mcp-Session-Id`（可见 ASCII 0x21–0x7E，需密码学安全）；之后客户端所有请求 MUST 带该头；缺头（除 initialize 外）→ 400；会话被服务器终止后 → 404，客户端收到 404 MUST 重新 initialize；客户端主动结束会话 SHOULD 发 `DELETE /mcp`（服务器可 405 拒绝）。
- **断线恢复**：SSE 事件可带 `id`（会话内全局唯一、按流编码）；客户端重连用 `GET + Last-Event-ID`，服务器只能在原流上重放。服务器 MAY 主动断开连接（发 `retry` 字段）让客户端轮询——SEP-1699。
- **协议版本头**：initialize 之后所有 HTTP 请求 MUST 带 `MCP-Protocol-Version: 2025-11-25`；无此头时服务器 SHOULD 假定 `2025-03-26`；无效版本 → 400。
- **安全**：MUST 校验 `Origin`（无效 → 403，防 DNS rebinding）；本地运行 SHOULD 只绑 `127.0.0.1`；SHOULD 对所有连接做认证。

### 2.2 tools 能力

- `tools/list`（分页 cursor）、`tools/call`；能力声明 `capabilities.tools.listChanged`，变更时发 `notifications/tools/list_changed`。
- `Tool` 字段：`name`（建议 `^[a-zA-Z0-9_-]{1,128}$`，SEP-986）、`title`、`description`、`inputSchema`（JSON Schema 2020-12 object）、**`outputSchema`**（2025-06-18 引入）、`icons`（2025-11-25）、`annotations`（`readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint`——与我们的角色分级授权天然对应）。
- `CallToolResult`：`content: [TextContent|ImageContent|AudioContent|ResourceLink|EmbeddedResource]`、**`structuredContent`**（与 `outputSchema` 匹配的 JSON 对象；声明了 outputSchema 的工具 SHOULD 返回 structuredContent，并同时在 content 里放序列化文本以兼容旧客户端）、`isError`。
- 编译错误/警告这类结构化数据 → 用 `outputSchema` + `structuredContent` 是标准做法。

### 2.3 authorization（OAuth 2.1）与 localhost 实践豁免

来源：[Authorization 章节](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)。

- **Authorization 对 MCP 实现是 OPTIONAL**；HTTP 传输"SHOULD conform"，stdio 传输"SHOULD NOT follow"（从环境取凭据）。
- 若实现：MCP server 作为 OAuth 2.1 resource server，**MUST 实现 RFC 9728 Protected Resource Metadata**（`/.well-known/oauth-protected-resource`），401 响应带 `WWW-Authenticate: Bearer resource_metadata="…"`；客户端 MUST 支持头与 well-known 两种发现；MUST 用 `Authorization: Bearer <token>`（每个请求都要带，token 不得进 query string）；invalid/expired token → 401，scope 不足 → 403 + `error="insufficient_scope"`；客户端 MUST 实现 PKCE(S256) 与 RFC 8707 `resource` 参数。
- **localhost 实践**：规范未给"官方豁免"，但业界（含 JetBrains 内建 MCP server、Claude Code `--header "Authorization: Bearer $KEY"`、Codex `bearer_token_env_var`）的通行做法是：**只绑 127.0.0.1 + 静态 Bearer token / 自定义 header token + Origin/Host 校验**，不上完整 OAuth。JetBrains 的 `authorizedSession` 私有服务器用自定义头 `IJ_MCP_AUTH_TOKEN: <uuid>` 做逐会话 token（见 §5）。Idectl 采用相同思路：为每客户端签发 token，token 映射到角色（只读/操作者/管理员），校验放在 Ktor pipeline 拦截器里，未带/无效 → 401 + `WWW-Authenticate: Bearer`。

### 2.4 elicitation / progress / cancellation / tasks（长操作支撑）

- **progress**（[utilities/progress](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress)）：客户端请求在 `params._meta.progressToken` 带令牌；服务器发 `notifications/progress { progressToken, progress, total?, message? }`；`progress` 必须单调递增。构建/测试进度即用此机制（在 Streamable HTTP 下走该 POST 响应的 SSE 流）。
- **cancellation**（[utilities/cancellation](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)）：任一方发 `notifications/cancelled { requestId, reason? }`；`initialize` 请求不可被取消；接收方 SHOULD 停止处理、不再发响应。映射到 IDEA：取消 `tools/call(build)` → 取消对应 `ProgressIndicator`/协程 Job。注意 Streamable HTTP 下**连接断开不代表取消**，必须显式发 cancelled 通知。
- **elicitation**（[client/elicitation](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation)）：服务器→客户端 `elicitation/create`，`mode: "form"`（`requestedSchema` 限扁平对象+原始类型/枚举）或 `mode: "url"`（2025-11-25 新增，敏感交互出带外）；响应 `action: accept|decline|cancel`。用途：危险操作二次确认（如"停止他人进程？"）可用 form elicitation 让 Agent 侧用户确认；但注意客户端须声明 `elicitation` capability（Claude Code 已支持，矩阵见 §7）。
- **tasks**（实验性，SEP-1686）：`tasks/create`→轮询 `tasks/get`→`tasks/result`，适合超长构建；2026-07-28 将升级为正式扩展。当前建议：主路径用 progress+SSE，tasks 作为观察项不押注。

### 2.5 MCP Kotlin SDK（推荐选型）

仓库：[modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)（官方，"Maintained in collaboration with JetBrains"）。文档站：[kotlin.sdk.modelcontextprotocol.io](https://kotlin.sdk.modelcontextprotocol.io/)。

- 坐标：`io.modelcontextprotocol:kotlin-sdk`（伞包）/ `kotlin-sdk-server`（仅服务端）/ `kotlin-sdk-client` / `kotlin-sdk-testing`（0.9.0 起，进程内 transport 测试）。
- 版本：截至 2026-07 releases 页最新为 **0.13.0**（文档站已引用 0.14.0，即 0.x 快速迭代期）。**尚未 1.0，API 会破坏性变化**（0.8.0 把所有类型移到 `io.modelcontextprotocol.kotlin.sdk.types` 包、最低 JVM 11；0.9.0 把 Ktor 扩展从 `Routing` 移到 `Route`；0.13.0 中 transport handler 跑在 `Dispatchers.Default`、HTTP transport 默认开启 DNS rebinding protection）。锁定版本 + 隔离封装是必须的。
- 关键类（0.13.x，包 `io.modelcontextprotocol.kotlin.sdk.server` / `...sdk.types`）：
  - `Server(serverInfo: Implementation, options: ServerOptions)`；`ServerOptions(capabilities: ServerCapabilities)`；`Implementation(name, version)`。
  - `Server.addTool(name, description, inputSchema, ...) { request -> CallToolResult(...) }`（handler 为 suspend lambda，返回 `CallToolResult(content = listOf(TextContent(...)), structuredContent = ..., isError = ...)`）。
  - Transports：`StdioServerTransport`、`SseServerTransport`（legacy）、**`StreamableHttpServerTransport`**、`ServerSession`。
  - Ktor 服务端扩展：**`mcpStreamableHttp(path = "/mcp") { server }`**、`mcpStatelessStreamableHttp(...)`（无会话、仅 POST）、`Application.mcp {}` / `Route.mcp {}`（legacy SSE，需先 `install(SSE)`）。Streamable HTTP helper 会**自动 install ContentNegotiation(McpJson)，不要重复安装**。
  - 注意：SDK 依赖 Ktor 但**不传递引擎依赖**，需自行加 `ktor-server-cio`（或 netty）。
- 对比 **MCP Java SDK**（[modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk)）：最新 **2.0.0 GA**（跟踪 2025-11-25 规范），核心 `io.modelcontextprotocol.sdk:mcp` 内置 STDIO/SSE/Streamable HTTP 服务端传输、不依赖 web 框架；`McpSyncServer server = McpServer.sync(transportProvider).build()`；内部用 Project Reactor + Jackson。**更成熟（已 2.0 GA）但引入 Reactor+Jackson 两套重依赖**，且与 IDEA 平台 Kotlin 协程世界割裂（EDT/ReadAction 的协程适配都在 kotlinx.coroutines 侧）。
- **结论：在 IDEA 插件里用 Kotlin SDK 更稳**——JetBrains 官方 mcp-server 插件在生产中用的就是它（`import io.modelcontextprotocol.kotlin.sdk.server.Server` 见 intellij-community 源码），协程模型与平台一致；代价是 0.x API 漂移，用适配层封住。

### 2.6 Kotlin 代码草图（Idectl 服务器骨架）

```kotlin
// Application 级服务，构造器注入插件级 CoroutineScope（平台特性，2023.x+）
@Service
class IdectlServerService(private val cs: CoroutineScope) : Disposable {

  private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

  fun start(desiredPort: Int = DEFAULT_PORT) {
    val port = findFirstFreePort(desiredPort)          // 端口递增探测
    server = cs.embeddedServer(CIO, host = "127.0.0.1", port = port) {
      // 1) 安全拦截：Host/Origin 校验 + Bearer token -> 角色
      intercept(ApplicationCallPipeline.Plugins) {
        validateHostAndOrigin(call) ?: run { call.respond(HttpStatusCode.Forbidden); finish() }
        val principal = authenticate(call.request.headers["Authorization"])
          ?: run { call.respond(HttpStatusCode.Unauthorized); finish(); return@intercept }
        call.attributes.put(PrincipalKey, principal)   // 后续 tool handler 读取角色/绑定项目
      }
      // 2) MCP endpoint（SDK 0.13：每会话工厂，可按会话返回带不同 tool 集的 Server）
      mcpStreamableHttp(path = "/mcp") {
        buildMcpServer()                                // -> io.modelcontextprotocol.kotlin.sdk.server.Server
      }
    }.also { it.start(wait = false) }
    InstanceRegistry.publish(port)                      // ~/.idectl/instances.json
  }

  private fun buildMcpServer(): Server =
    Server(
      serverInfo = Implementation("idectl", pluginVersion),
      options = ServerOptions(
        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
      ),
    ).apply {
      addTool(
        name = "build_project",
        description = "Build project and return structured compiler errors/warnings",
        inputSchema = /* Tool schema: { projectPath: string, rebuild?: boolean } */
      ) { request ->
        // 工具实现：切到平台协程上下文，禁止阻塞 transport 协程
        val problems = withBackgroundProgress(project, "MCP build") {
          buildAndCollectProblems(request)              // ProjectTaskManager 封装
        }
        CallToolResult(
          content = listOf(TextContent(problems.toDisplayText())),
          structuredContent = McpJson.encodeToJsonElement(problems).jsonObject,
        )
      }
    }

  override fun dispose() {
    // 动态卸载要求：同步收敛。cs 由平台在插件卸载时 cancel，
    // 但 socket 必须显式关（不能只依赖协程取消）
    server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
    InstanceRegistry.unpublish()
  }
}
```

`plugin.xml`：

```xml
<applicationService serviceImplementation="....IdectlServerService"/>
<postStartupActivity implementation="....StartBridgeActivity"/> <!-- 或 ProjectActivity -->
```

---

## 3. 内嵌 HTTP 服务器方案对比

### 方案 A：插件自带 Ktor（CIO）— ✅ 推荐

- **先例即结论**：JetBrains 内建 MCP server（`plugins/mcp-server`）就是 `cs.embeddedServer(CIO, host = "127.0.0.1", port = resolvedPort) { ... }`（源码 `McpServerService.kt`）。CIO 是纯协程引擎，无 Netty 依赖，体积小（ktor-server-cio + ktor-server-sse + ktor-server-content-negotiation ≈ 数 MB）。
- **类加载/依赖冲突分析**（关键坑区）：
  - IDEA 平台**捆绑 ktor client**（intellij-community 中存在平台库模块 `intellij.libraries.ktor.client`、`intellij.libraries.ktor.client.cio`、`intellij.libraries.ktor.network.tls`、`intellij.libraries.ktor.io`；mcp-server 插件的 .iml 直接引用它们），但 **ktor *server* 工件（`ktor-server-sse-jvm`、`ktor-server-content-negotiation`、`ktor.server.cio`）不属于对第三方插件公开的平台 API**——它们随 mcp-server 插件/平台内部打包，版本随 IDE 变动，第三方插件不应依赖其存在。
  - **kotlinx.coroutines 绝对不能自带**：平台捆绑 JetBrains 补丁版（`org.jetbrains.intellij.deps.kotlinx` 系，[intellij-deps-kotlinx.coroutines](https://github.com/JetBrains/intellij-deps-kotlinx.coroutines)），SDK 文档明确要求用捆绑版；intellij-platform-gradle-plugin 2.x 会自动从插件依赖里剔除 coroutines 与 kotlin-stdlib（`kotlin.stdlib.default.dependency=false` 配合）。引入 Ktor 时必须 `exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")` 等，确认最终 zip 里没有任何 kotlinx-coroutines jar。
  - kotlinx-serialization-json 平台也有捆绑；PluginClassLoader 是插件优先（plugin-first）加载自身 jar，自带 serialization 通常可行，但若与平台版本行为差异会出现隐蔽 bug；建议对齐 IDE 捆绑版本编译、运行期让平台版本兜底或整体自带并冒烟测试。
  - 最保险的隔离手段：不在乎体积时可用 Shadow/relocate 把 `io.ktor` 重定位（Ktor 大量用 ServiceLoader 与 reflection，relocate 有风险，需验证）；实践上多数插件直接自带未重定位的 Ktor server 也能工作（plugin-first 类加载），JetBrains Toolbox/AI 系插件亦如此。
- **SSE/Streamable HTTP**：Ktor `install(SSE)` + kotlin-sdk 的 `mcpStreamableHttp` 原生支持，含心跳（JetBrains 用 5s heartbeat `ServerSentEvent(comments = "heartbeat")` 防代理断连）。

### 方案 B：IDE 内建 Web 服务器（`org.jetbrains.ide.RestService` / `HttpRequestHandler`，端口 63342 起）— ❌ 不做主通道

- 机制：扩展点 `com.intellij.httpRequestHandler`，继承 `org.jetbrains.ide.RestService`，覆写 `getServiceName(): String` 与 `execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String?`；服务暴露在 `http://127.0.0.1:63342/api/<serviceName>`；端口占用时用 63342+n（n<20），再不行任意空闲端口（[SDK 论坛/文档确认](https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000335710)）。
- 不适合的原因：
  1. Netty 管道以 `FullHttpRequest`（整包缓冲）为模型，`RestService` 面向"短请求-短响应"；SSE 长流要绕过其响应封装直接向 `ChannelHandlerContext` 写 chunked 数据并自行管理 keep-alive/超时，等于手写一遍传输层，且是内部 API，跨版本易碎。
  2. 内建服务器有自己的信任模型（`isHostTrusted` 校验 referrer/origin，未知来源会弹确认框），与 MCP 客户端的编程访问冲突。
  3. 63342 端口被浏览器/其它工具共享，审计与隔离性差。
- 有用之处：可注册一个极轻量的 `GET /api/idectl/discovery` 返回本实例的 MCP 端口/项目列表（无需长连接），作为**实例发现的备用通道**。JetBrains 旧版 stdio proxy 就是扫描 63342–63352 找 IDE 的。

### 方案 C：JDK `com.sun.net.httpserver.HttpServer` — 备选

- 零依赖、无类加载冲突；`HttpExchange.getResponseBody()` 用 `sendResponseHeaders(200, 0)` 即 chunked，可手写 SSE。
- 缺点：没有现成 MCP 绑定——kotlin-sdk 的服务端 Ktor 扩展用不上，要自己实现 `StreamableHttpServerTransport` 的 HTTP 装配（会话表、SSE 心跳、Last-Event-ID 重放、DELETE 终止）；HTTP/1.1-only、默认单线程 executor 需自配；长 SSE 连接管理原始。
- 若极端追求零依赖可选 Java SDK 2.0（其核心自带不依赖框架的 Streamable HTTP transport provider），但引入 Reactor/Jackson，得不偿失。

**推荐：A（Ktor CIO + kotlin-sdk），与 JetBrains 官方实现同构；C 作为降级预案；B 仅作发现辅助。**

---

## 4. 插件生命周期与线程模型

### 4.1 生命周期

- **Application 级 `@Service` + 构造器注入 `CoroutineScope`**：平台为每个 service 实例注入独立 scope（上下文含 `Dispatchers.Default` + `CoroutineName(serviceClass)`），该 scope 是插件 intersection scope 的子级，**插件卸载/应用退出时自动 cancel**（[Coroutine Scopes 文档](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html)）。JetBrains 的 `McpServerService(val cs: CoroutineScope)` 即此模式，且用 `cs.embeddedServer(...)`（Ktor 3 的 CoroutineScope 扩展）把服务器生命周期挂到该 scope。
- **不要用 `GlobalScope`/自建线程池**；不要在字段里缓存其它 service 实例（SDK 明令禁止）。
- **动态插件（unload 不重启）要求**（[Dynamic Plugins 文档](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html)）：无 components；只用 dynamic 扩展点；service 实现 `Disposable` 并在 `dispose()` 里**同步**关掉 ServerSocket、注销 instances.json；注册 `DynamicPluginListener`（`beforePluginUnload`）终止长任务；不得残留任何持有插件类加载器的线程/回调（Ktor CIO 的 accept 循环跑在注入 scope 里，scope cancel + `server.stop()` 双保险）。JetBrains 源码里 stop 用 `withContext(NonCancellable) { withTimeout(2000.ms) { server.stopSuspend(gracePeriodMillis=500, timeoutMillis=1000) } }`，防止取消传播吞掉停机逻辑——值得照抄。
- 启动时机：`ProjectActivity`（postStartupActivity）或首个项目打开时懒启动；JetBrains 内建实现是 app 级服务在设置开启时启动，并有 `MyProjectListener : ProjectActivity` 做提示。

### 4.2 线程模型（EDT / ReadAction / WriteAction / 协程）

- **MCP transport/tool handler 的协程跑在 `Dispatchers.Default`（kotlin-sdk 0.13.0 起明确）**——绝不能在其中直接触碰 PSI/VFS/Swing。
- 规则：
  - 读模型（列 RunConfiguration、查 Maven 模块、读断点列表）：`readAction { ... }` / `smartReadAction(project) { ... }`（挂起式，2023.1+ 平台协程 API，包 `com.intellij.openapi.application`）。
  - 写模型（增删断点、改配置）：`edtWriteAction { ... }` 或 `withContext(Dispatchers.EDT) { WriteAction.run(...) }`；`Dispatchers.EDT` 为平台提供的 EDT 派发器。
  - 长操作（构建、goal 执行）：`withBackgroundProgress(project, title) { ... }`（`com.intellij.platform.ide.progress`，2023.3+）获得可取消的进度上下文，把 `ProgressReporter` 的分数转成 MCP `notifications/progress`。
  - 回调式平台 API（`ProjectTaskManager.run(...): Promise<Result>`、`ProgramRunnerUtil.executeConfiguration`、Maven reimport）→ `suspendCancellableCoroutine` 桥接，取消时调用平台侧取消句柄。
  - 控制台增量读取：`ProcessListener.onTextAvailable` 回调在任意线程，写入自己的 lock-free 环形缓冲；MCP 读取端零平台锁。
  - 不要在协程里 `runBlocking` 等 EDT 结果（死锁向）；不要 `limitedParallelism(1)` 当锁用（官方 Tips 明确，改用 `Mutex`）——构建互斥语义用应用级 `Mutex` + 冲突时快速失败返回结构化"BUSY"。
- Ktor CIO 的 IO 与我们注入 scope 的 `Dispatchers.Default` 共享平台协程池：**tool handler 中一切阻塞调用（等待构建完成等）必须走 `Dispatchers.IO` 或挂起桥接**，否则饿死平台默认派发器。

---

## 5. JetBrains 官方 IDE 内建 MCP server（2025.2+）技术路线（可借鉴/规避）

来源：[IDEA 官方帮助](https://www.jetbrains.com/help/idea/mcp-server.html) 与 [intellij-community `plugins/mcp-server` 源码](https://github.com/JetBrains/intellij-community/tree/master/plugins/mcp-server)（模块 `intellij.mcpserver`，插件 ID `com.intellij.mcpServer`，2025.2 起随 IDE 捆绑，60+ 工具）。

实测源码要点（`com.intellij.mcpserver.impl.McpServerService`、`impl/util/network/mcp.sdk.util.kt`、`settings/McpServerSettings.kt`）：

- **技术栈**：Ktor `embeddedServer(CIO, host = "127.0.0.1", port)` + 官方 **kotlin-sdk**（`io.modelcontextprotocol.kotlin.sdk.server.Server/ServerOptions/SseServerTransport/StreamableHttpServerTransport`）。
- **端口**：`BASE_MCP_PORT = 64342`，各产品偏移 `PORT_STEP = 20`（IDEA +0、CLion +20、DataGrip +60、GoLand +80、PhpStorm +100、PyCharm +120、Rider +140、RubyMine +160、RustRover +180、WebStorm +200）；`findFirstFreePort(desiredPort)` 向上递增；实际端口持久化在 `options/mcpServer.xml`（`McpServerSettings.MyState.mcpServerPort`）；可用系统属性强制端口/强制开启（`IJ_MCP_FORCE_PORT_PROPERTY`/`IJ_MCP_FORCE_ENABLE_PROPERTY`）。**多实例同产品会各自递增抢端口且覆写设置——用户抱怨点（YouTrack IJPL-207839），Idectl 要规避：端口注册表而非单值设置。**
- **传输**：同一服务器同时挂三套路由——`GET /sse` + `POST /message`（legacy HTTP+SSE，向后兼容）、`/stream`（Streamable HTTP：POST initialize 建 pending transport，`mcp-session-id` 头关联，GET 升级 SSE；自写 `mcpPatched` 胶水而非直接用 SDK 的 `mcpStreamableHttp`，为的是插入 prePhase 拦截）；SSE 心跳 5s；`installHostValidation()` 防 DNS rebinding；`installHttpRequestPropagation()` 把 HTTP 请求上下文传进 tool 调用。
- **认证**：主全局服务器（用户手动开启）**无 token**，靠 127.0.0.1 + Host 校验 + 工具级确认（brave mode 开关）；另有 `authorizedSession(...)` 启动的**私有服务器**（`DEFAULT_MCP_PRIVATE_PORT = 主端口+100`，供 IDE 内 Junie/AI Assistant 等本地 agent 用）：每会话生成 UUID token，客户端须带自定义头 `IJ_MCP_AUTH_TOKEN`，未知 token → 401；会话选项含 `McpSessionOptions(commandExecutionMode, toolFilter, localAgentId, invocationMode)`——**这就是"按会话过滤工具+分级执行确认"的官方样板，Idectl 的角色模型可直接借鉴其形状，但要更进一步用标准 `Authorization: Bearer` 头**（自定义头对通用客户端不友好）。
- **stdio 桥**：子模块 `mcpserver.stdio`（JVM 进程），环境变量 `IJ_MCP_SERVER_PROJECT_PATH`（把会话绑定到指定项目路径！）与 `IJ_MCP_ALLOWED_TOOLS`；IDE 帮助页给三种客户端配置：SSE / Stdio / HTTP Stream。**项目路由思路可借鉴：连接参数（header 或 stdio env）声明项目路径，服务端据此路由。**
- **elicitation**：有 `McpElicitationKind.IDE/CLI` 之分——IDE 弹窗确认或走客户端 elicitation。
- 可规避点小结：端口设置覆写问题；主服务器无 token（我们必须默认有 token）；SSE 与 Stream 双栈维护成本（我们可只做 Streamable HTTP + stdio 桥，SSE legacy 视客户端矩阵决定）。

---

## 6. 多 IDE 实例：端口与实例发现

- **端口分配**：固定基准端口（建议避开 63342/64342±240 的 JetBrains 段，选如 **48620** 起），`findFirstFreePort` 向上递增；绑定成功后的实际端口写注册表。
- **注册表模式（推荐，`~/.idectl/instances.json`）**：无跨平台 mDNS 依赖、可离线读取。每实例启动写入条目：
  ```json
  {
    "instances": [{
      "port": 48620,
      "pid": 12345,
      "ide": "IntelliJ IDEA 2026.1",
      "startedAt": "2026-07-03T10:00:00Z",
      "projects": [{"name": "shop", "path": "/Users/me/ws/shop"}],
      "tokenHint": "idectl-****"
    }]
  }
  ```
  要点：文件锁/原子替换（临时文件+rename）防并发写；启动时清理"pid 不存活或端口探活失败"的僵尸条目；项目 open/close 时更新 projects 数组（`ProjectManagerListener`）。
- **客户端选实例**：客户端（或我们提供的 `idectl` CLI/stdio 桥）读 instances.json，按"目标项目路径是该实例已打开项目路径的前缀/相等"匹配；命中多个→取最长路径匹配；零命中→列出实例让 Agent 选或报错。也提供 MCP 工具 `list_projects` 让已连接客户端自查路由。
- mDNS（`_mcp._tcp`）无客户端生态支持，不做。JetBrains 自己也没做实例发现（每实例独立端口+设置文件），这是我们相对官方的增强点。

---

## 7. 主流客户端传输支持矩阵（2026-07）

| 客户端 | Streamable HTTP | legacy SSE | stdio | 备注 |
|---|---|---|---|---|
| Claude Code | ✅ 推荐（`claude mcp add --transport http name URL`；`.mcp.json` 中 `"type": "http"`，`streamable-http` 为别名） | ⚠️ 已弃用（仍可用） | ✅ | `--header "Authorization: Bearer $KEY"` 传 token；断线自动指数退避重连（5 次，1s 起翻倍）（[docs](https://code.claude.com/docs/en/mcp)） |
| OpenAI Codex（CLI/IDE 扩展） | ✅（`~/.codex/config.toml` `[mcp_servers.x]`；`bearer_token_env_var`/`http_headers` 传认证） | ❌ **明确不支持**（维护者：仅 streamable http，SSE deprecated） | ✅ | [官方文档](https://developers.openai.com/codex/mcp)；`codex mcp` 命令组仍标 experimental |
| Cursor | ✅（`~/.cursor/mcp.json`，`"url"` 即 streamable HTTP） | ⚠️ 兼容 | ✅ | JSON 配置，无 CLI |
| VS Code (Copilot) | ✅（`.vscode/mcp.json`，type `http`） | ⚠️ | ✅ | JetBrains 内建 MCP 的自动配置目标之一 |

**结论：Streamable HTTP 为唯一必做的 HTTP 传输；legacy SSE 可不做（Codex 都不支持了）；stdio 桥仍要提供**——用于：只允许 stdio 的客户端/托管环境、以及作为实例发现入口（桥进程按项目路径挑实例后转发到对应端口）。桥可以是随插件分发的独立 thin JAR（复用 IDE 的 JBR 启动，参考 JetBrains `mcpserver.stdio` 模式）。

---

## 8. 版本兼容性（sinceBuild=233 → 2026.x）

| 关注点 | 233 (2023.3) | 241/242 (2024.1/2) | 251+ (2025.x) | 影响 |
|---|---|---|---|---|
| Service 构造器注入 `CoroutineScope` | ✅ 已支持 | ✅ | ✅ | 基线可用 |
| `withBackgroundProgress` / `reportProgress` | ✅（2023.3 引入 `com.intellij.platform.ide.progress`） | ✅ | ✅ | 233 下用时验证包路径（早期在 `com.intellij.openapi.progress`，有迁移） |
| `readAction {}` 挂起式 | ✅ | ✅ | ✅（`smartReadAction` 等增强） | — |
| Kotlin 版本要求 | K1 可 | 2024.2 起建议 Kotlin 2.x | 2025.1+ 要求 Kotlin 2.x | 编译目标 2024.2 + K2 即满足 |
| kotlin-sdk 最低 JVM 11 | IDE 运行时 JBR17 | JBR17/21 | JBR21 | 无冲突；但**编译目标字节码 17**（2024.2+ 平台要求），sinceBuild=233 需确认 233 运行时（JBR17）可跑 |
| 平台捆绑 ktor client 版本 | 有（较旧） | 有 | 有（3.x） | 自带 ktor server 时避免依赖平台 ktor 的任何传递假设 |
| IDE 内建 MCP server | ❌ | ❌ | 2025.2+ 捆绑 `com.intellij.mcpServer` | 端口 64342 段被占；**Idectl 与其共存**：不同端口段，不声明对其依赖 |
| MCP 协议版本 | n/a | n/a | n/a | 服务器同时接受 `2025-03-26`/`2025-06-18`/`2025-11-25` 协商 |

注意：题目约束"编译目标 IDEA 2024.2+、sinceBuild=233"意味着**编译用 242 SDK、运行下探 233**——所有 242 新 API（如 `currentThreadCoroutineScope`）必须避免或反射降级；建议 CI 用 Plugin Verifier 对 233.x、242.x、251.x、当前最新各跑一遍。

---

## 9. 已知坑与限制

1. **kotlin-sdk 0.x API 漂移**：0.8→0.13 连续破坏性变更（types 包迁移、Route 迁移、TransportSendOptions、capability 结构变更、Dispatchers.Default、DNS rebinding protection 默认开启）。对策：版本锁定 + 独立适配层 + 升级回归。
2. **绝不自带 kotlinx-coroutines**：平台补丁版与社区版行为不同（调度、调试探针），混入会出现 `CoroutineScope` 类不匹配或诡异挂起 bug；intellij-platform-gradle-plugin 2.x 自动剔除，但要检查 Ktor 传递依赖后最终产物。
3. **kotlinx-serialization 双版本**：kotlin-sdk 的 `McpJson` 基于 kotlinx.serialization；平台亦捆绑。plugin-first 类加载一般安全，但如果我们的类实现平台接口且签名涉及 serialization 类型会 LinkageError——保持 MCP 层类型不跨插件边界。
4. **DNS rebinding**：必须校验 Host/Origin（127.0.0.1/localhost 白名单）；kotlin-sdk 0.13 默认开启保护，注意其默认白名单是否包含带端口的 `localhost:PORT` 形式，JetBrains 另写了 `installHostValidation()`。
5. **SSE 空闲断连**：中间层（安全软件/代理）会掐长连接；照抄 JetBrains 的 5s comment 心跳；同时实现 SEP-1699 轮询语义（服务器可主动断，客户端 GET+Last-Event-ID 重连）。
6. **断开 ≠ 取消**：客户端断线不代表取消构建；显式 `notifications/cancelled` 才取消。反向：长构建期间响应流断了，要靠事件 ID 重放或让结果可通过后续工具查询（提供 `get_last_build_result` 兜底）。
7. **表达式求值 = 任意代码执行**：MCP 规范安全章节明确要求 human-in-the-loop 与最小权限；用 tool annotations（`destructiveHint`）+ 角色门槛 + 可选 elicitation 确认；审计日志记录 token 主体、工具、参数摘要、结果码。
8. **动态卸载残留**：Ktor CIO 停机若超时会留 accept 线程 → 类加载器泄漏 → 卸载失败要求重启。`dispose()` 中同步 stop（带 timeout），并在 `DynamicPluginListener.beforePluginUnload` 提前停。
9. **多实例端口漂移**：不要学 JetBrains 把"上次端口"写全局设置（多实例互相覆写）；用 instances.json 每实例一条。
10. **RestService 诱惑**：63342 看似免费午餐，但 FullHttpRequest 缓冲 + 信任弹窗 + 内部 API 变动，做 MCP 主通道必翻车；只做发现端点。
11. **2026-07-28 协议无状态化**：`Mcp-Session-Id`/initialize 语义将变；把"会话概念"封装在自己的 `SessionManager` 后面，别让业务层直接依赖 SDK session 对象。
12. **Codex 不支持 SSE**、Claude Code SSE 弃用：别为 legacy SSE 投入。
13. **kotlin-sdk 不带 Ktor 引擎**：忘加 `ktor-server-cio` 会在运行时才报 NoClassDefFound。
14. **审计与并发**：构建互斥用应用级 `Mutex`+忙时快速失败；不要把 MCP 请求串行化在 EDT 上排队（会卡 UI 又卡协议）。

---

## 10. 参考来源

- MCP 规范 2025-11-25 changelog：https://modelcontextprotocol.io/specification/2025-11-25/changelog
- MCP Transports（Streamable HTTP 全文）：https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- MCP Authorization：https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- MCP Elicitation：https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation
- 2026-07-28 RC 公告：https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/ ；SDK betas：https://blog.modelcontextprotocol.io/posts/sdk-betas-2026-07-28/
- MCP Kotlin SDK：https://github.com/modelcontextprotocol/kotlin-sdk （Releases：https://github.com/modelcontextprotocol/kotlin-sdk/releases ；文档：https://kotlin.sdk.modelcontextprotocol.io/）
- MCP Java SDK（2.0.0 GA）：https://github.com/modelcontextprotocol/java-sdk ；https://java.sdk.modelcontextprotocol.io/
- JetBrains 内建 MCP server 源码：
  - https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/impl/McpServerService.kt
  - https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/impl/util/network/mcp.sdk.util.kt
  - https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/src/com/intellij/mcpserver/settings/McpServerSettings.kt
- IDEA MCP Server 帮助页（2025.2+）：https://www.jetbrains.com/help/idea/mcp-server.html ；Marketplace：https://plugins.jetbrains.com/plugin/26071-mcp-server ；端口问题 YouTrack：https://youtrack.jetbrains.com/issue/IJPL-207839
- IntelliJ Platform SDK：Coroutine Scopes https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html ；Launching Coroutines https://plugins.jetbrains.com/docs/intellij/launching-coroutines.html ；Services https://plugins.jetbrains.com/docs/intellij/plugin-services.html ；Dynamic Plugins https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html ；Kotlin Coroutines https://plugins.jetbrains.com/docs/intellij/kotlin-coroutines.html
- 平台补丁版 coroutines：https://github.com/JetBrains/intellij-deps-kotlinx.coroutines
- 内建 Web 服务器/RestService：https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000335710 ；BuiltInWebServer.kt：https://github.com/JetBrains/intellij-community/blob/master/platform/built-in-server/src/org/jetbrains/builtInWebServer/BuiltInWebServer.kt ；OAuth via RestService 博客：https://blog.jetbrains.com/platform/2026/06/stop-pasting-tokens-oauth2-login-for-jetbrains-ide-plugins/
- Claude Code MCP 文档：https://code.claude.com/docs/en/mcp ；Codex MCP 文档：https://developers.openai.com/codex/mcp ；JetBrains 旧版 stdio proxy：https://github.com/JetBrains/mcp-jetbrains
