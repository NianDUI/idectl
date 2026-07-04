# IdeaBridge 设计文档

一个 IntelliJ IDEA 插件：内嵌 MCP 服务器，把 IDE 的**构建 / 运行调试 / 控制台日志 / Maven** 能力开放给 AI Agent（Claude Code、Codex 等），支持多 Agent 客户端并发接入与角色分级授权。

> **当前状态**：设计定稿，未开始实现（仓库中的 v0 代码是早期原型，不作为实现基线）。
> **核心优先级**：① 重新运行正在运行的程序（run/debug 可切换）② 检索正在运行程序的控制台日志——随 M1 首发；③ 热加载（debug 运行中把方法体级"简单修改"免重启换进 JVM）——随 M2 首发。

## 阅读顺序

| 文档 | 内容 | 何时读 |
| --- | --- | --- |
| [01-需求与范围](01-需求与范围.md) | FR/NFR 全集、多用户多角色需求、Non-Goals、验收场景 | 了解"做什么" |
| [02-总体架构](02-总体架构.md) | 六层架构图、15 条关键决策记录（ADR）、线程模型、核心数据流 | 了解"怎么做"，**入口文档** |
| [03-MCP工具API规范](03-MCP工具API规范.md) | ~27 个工具的完整契约（参数/返回/角色/里程碑/错误码） | 实现任何工具前 |
| [04-多用户多角色与安全设计](04-多用户多角色与安全设计.md) | token/RBAC/属主/审计/配额/威胁模型 | 实现网关与治理前 |
| [05-构建功能设计](05-构建功能设计.md) | ProjectTaskManager 映射、双通道诊断、构建队列 | M2 |
| [06-运行与调试功能设计](06-运行与调试功能设计.md) | 会话注册表、启动/停止/**重启(run↔debug)**、**热加载(HotSwap)**、XDebugger 桥接 | **M1**/M2/M4 |
| [07-控制台日志读取与检索设计](07-控制台日志读取与检索设计.md) | 环形缓冲、offset/gap、**deadline-grep**、长轮询 | **M1** |
| [08-Maven功能设计](08-Maven功能设计.md) | 同步/模块树/goal/profiles、optional descriptor 隔离 | M5 |
| [09-实现路线图与里程碑](09-实现路线图与里程碑.md) | M0~M6 排期与可验收 exit criteria | 排期与验收 |
| [10-风险与测试策略](10-风险与测试策略.md) | Top10 风险对策、分层测试与兼容矩阵 | 贯穿 |

## 调研报告（一手 API 依据，均已对照 intellij-community 源码/官方文档核实）

| 报告 | 主题 |
| --- | --- |
| [research/01](research/01-intellij-build-api.md) | 构建/编译 API（ProjectTaskManager、双通道诊断、并发/取消） |
| [research/02](research/02-intellij-run-debug-api.md) | 运行/调试 API（RunManager、ExecutionManager、XDebugger） |
| [research/03](research/03-console-capture-and-search.md) | 控制台捕获与检索（processStarting 零丢失、缓冲设计、SM 测试事件） |
| [research/04](research/04-maven-integration-api.md) | Maven 集成 API（MavenProjectsManager、sync、goal、profiles） |
| [research/05](research/05-mcp-embedding.md) | MCP 协议现状与插件内嵌方案（Ktor/SDK/传输/多实例） |
| [research/06](research/06-prior-art-gap-analysis.md) | 竞品差距分析（JetBrains 内建 MCP 等，20 项能力差距表） |

## 技术栈速览

Kotlin · intellij-platform-gradle-plugin 2.x · sinceBuild=233（无上限）· Ktor CIO + MCP Kotlin SDK（Streamable HTTP，端口 48620+）· depends `com.intellij.java`，optional `org.jetbrains.idea.maven`
