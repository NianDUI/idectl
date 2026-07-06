# idectl (IDE Control)

**简体中文** · [English](README.en.md)

一个内嵌 **MCP(Model Context Protocol)服务器** 的 IntelliJ IDEA 插件,让 AI 编码 Agent ——
**Claude Code**、**Codex** —— 能够直接驱动 IDE:运行与调试各类配置(原地切换 运行↔调试)、
读取并 `grep` 实时控制台输出、构建、热重载改动的类、打断点并检查暂停中的程序、管理项目 /
运行配置 / SDK,并为不同 Agent 授予按角色受限的作用域访问。

服务器使用 Streamable-HTTP JSON-RPC 协议,仅绑定 `127.0.0.1`,并由 Bearer token 保护。
所有配置都在 **Settings → Tools → IDE Control** 下完成。

- **插件 id / Kotlin 包名**:`com.niandui.idectl` · **MCP 服务器名**:`idectl`
- **目标平台**:仅 IntelliJ IDEA **2026.x**(Ultimate 或 Community)

---

## 安装

一条命令构建并安装到你日常使用的 IDE,然后重启 IDEA:

```bash
./idectl.sh deploy
```

或者构建 zip 后手动安装:

```bash
./idectl.sh build            # -> build/distributions/idectl-<ver>.zip
# Settings → Plugins → ⚙ → Install Plugin from Disk… → 选择 zip → 重启
```

也可以在一次性的沙箱 IDE 里试用,而不动你的正式安装(`./idectl.sh -h` 查看选项):

```bash
./idectl.sh run
```

打开第一个项目时,会弹出气泡提示端口号,以及一条可直接粘贴的连接命令。

---

## 接入 Agent

最简单 —— 在 **Settings → Tools → IDE Control → 一键接入 Agent** 中,为 Claude Code 或 Codex
点击 **一键配置**;它会把当前端口 + access token 写入对应 Agent 的 MCP 配置。

手动配置:

```bash
# Claude Code(user / 全局作用域)
claude mcp add --transport http idectl http://127.0.0.1:48620/mcp \
  --header "Authorization: Bearer <ACCESS_TOKEN>" -s user
```

```toml
# Codex —— ~/.codex/config.toml
[mcp_servers.idectl]
url = "http://127.0.0.1:48620/mcp"

[mcp_servers.idectl.http_headers]
Authorization = "Bearer <ACCESS_TOKEN>"
```

access token 显示在设置页;默认端口为 `48620`(被占用时会向上扫描)。

---

## Agent 能做什么

| 分组 | 工具 |
| --- | --- |
| 发现 | `get_ide_state`、`bind_project` |
| 项目生命周期 | `open_project`、`list_recent_projects`、`close_project`、`refresh_vfs` |
| 构建与运行配置 | `reimport_project`、`create/update/delete_run_configuration`、`set_project_sdk` |
| 运行与会话 | `list_run_configurations`、`run_configuration`、`run_main`、`list_sessions`、`stop_session`、`restart_session` |
| 控制台 | `console_read`、`console_search`(对实时 + 归档输出做 grep) |
| 构建与热重载 | `build`、`reload_classes` |
| 测试 | `run_test`、`get_test_results` |
| 调试器 | `set_breakpoint`、`remove_breakpoint`、`list_breakpoints`、`debug_control`(resume/pause/step/run-to-line)、`get_stack`、`get_variables`、`evaluate` |
| 治理(admin) | `create_token`、`list_tokens`、`revoke_token` |

`restart_session` 会重新启动一个正在运行的会话,并可在 **运行** 与 **调试** 之间原地切换。
控制台输出保存在一个有界的内存环形缓冲区中,并溢出到一个有界的磁盘归档,因此旧日志仍可用
`console_search` 检索。

---

## 治理

两个相互独立的层,都在设置页配置:

- **工具(WHAT)。** 一棵分组的复选框树是所有者的总开关:禁用某个工具 —— 或整个分组 ——
  它就对*每个* Agent(包括 admin)不可见、不可调用。每个工具还可以要求 **人工审批**
  (弹出气泡由你批准/拒绝,超时按失败处理),并覆盖等待 **超时时间**。
- **Token(WHO / WHERE)。** 主 **access token** 始终是对所有项目的 admin。可再签发带角色的
  **作用域 token** —— **viewer**(只读)、**operator**(运行/调试)、**admin**(管理 token)
  —— 外加可选的项目白名单;超出作用域的项目对该 token 不可见,`tools/list` 也按角色过滤。

有效可见性 = *工具已启用* **且** *角色满足* **且** *项目在作用域内*。

---

## 架构

```
IdectlBootstrap (ProjectActivity, 每个项目运行一次)
  └─ IdectlService (@Service APP, 单例 —— 首个项目打开时启动一次)
       ├─ 127.0.0.1:<port> 上的 Ktor CIO 服务器  →  /mcp  (Streamable-HTTP JSON-RPC)
       │      AuthGate (Bearer → Principal) → McpTransportAdapter → ToolGate → Tool
       ├─ ToolGate —— 每次调用都必经的唯一收口点:
       │      角色检查 → 总开关 → 项目路由/作用域 → 注入超时
       │      → 人工审批 → 执行 → 审计
       └─ IdectlProjectService (@Service PROJECT): 运行启动器、控制台存储、调试控制器
```

- MCP 层是在平台**自带的 Ktor 3.x** 之上手写实现的 —— 插件**不**自带任何 Ktor 或
  kotlinx-coroutines(第二份拷贝会割裂 classloader 类图)。
- 实例发现写入 `~/.idectl/instances.json`;审计日志写入 `~/.idectl/audit/`。

---

## 构建与开发

`./idectl.sh -h` 说明了辅助脚本的用法(`build` / `deploy` / `run` / `clean`)。

- **工具链**:JDK 21、Gradle 9、IntelliJ Platform Gradle 插件 2.x、Kotlin 2.3.x。
- **目标**:仅 IntelliJ IDEA 2026.x(`sinceBuild = 261`,无向后兼容回退)。
- **约束**:绝不打包 Ktor 或 kotlinx-coroutines —— 一律声明为 `compileOnly`;若有 coroutines
  jar 混入 zip,`verifyNoCoroutinesInZip` 构建守卫会让构建失败。
- 进度与踩坑记录在 `docs/11-实现进度与问题日志.md`;设计文档在 `docs/01`–`docs/10`。

## 运行期状态存放位置

| 内容 | 路径 |
| --- | --- |
| 插件设置 | `<IDE config>/options/idectl.xml` |
| 实例发现 | `~/.idectl/instances.json` |
| 审计日志 | `~/.idectl/audit/*.jsonl` |
| 控制台归档(临时) | `<temp>/idectl-console/<sessionId>` |
