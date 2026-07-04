# 03 · MCP 工具 API 规范

> 工具面全集（~27 个）。每个工具标注 **最低角色**（viewer ⊂ operator ⊂ admin，见 04）与 **里程碑**（M1~M6，见 09）。
> 核心优先级：`restart_session`（核心①）与 `console_search` / `console_read`（核心②）随 M1 首发；`reload_classes`（核心③热加载）随 M2 首发。

## 1. 全局约定

1. **命名**：工具名 snake_case；参数 snake_case；返回字段 camelCase。
2. **行列号**：对外一律 **1 基**（平台 0 基值在适配层转换）；每个涉及行号的工具 description 明示"1-based"。
3. **项目路由**：所有项目级工具带可选参数 `project:string?`（项目根路径或名称）。解析顺序：显式参数 → 会话绑定项目 → IDE 仅开一个项目时自动绑定；解析结果必须 ∈ token.allowedProjects，否则 `PERMISSION_DENIED`。范围之外的项目在一切列表中**不可见**。
4. **长操作**：默认 `wait=true` 同步等待，带 `timeout_sec`；**超时不是失败**，返回 `operationId` + 当前状态，用 `get_operation` 续接；断线≠取消。
5. **offset 语义**（控制台/构建输出）：offset 为会话内全局单调行号，淘汰不回退；`from_offset` 落入已淘汰区间时不报错，从 `firstAvailableOffset` 起返回并置 `gap=true, droppedLines=n`。
6. **截断与分页**：所有列表类返回带 `total / hasMore / truncated` 元数据；文本行超 16KB 截断并标记 `truncated:true`。
7. **错误形态**：工具级错误返回 `isError:true` + 结构化 `{code, message, remediation}`。错误码字典：
   `PERMISSION_DENIED`（附所需角色）/ `NOT_FOUND` / `PROJECT_NOT_BOUND` / `CONFLICT`（附现存 sessionId）/ `BUSY`（附队列信息）/ `QUOTA_EXCEEDED` / `TIMEOUT`（附 operationId）/ `NOT_SUSPENDED` / `NOT_DEBUG_SESSION`（热加载仅对 debug 会话可用）/ `UNAVAILABLE`（如 Maven 插件缺席）/ `INVALID_ARGUMENT`。`remediation` 用一句话告诉 Agent 下一步怎么做。
8. **annotations**：每个工具声明 `readOnlyHint` / `destructiveHint`，与角色矩阵一致；`tools/list` 按调用者角色过滤（低角色看不见危险工具）。
9. **时间**：全部 epoch millis（UTC）。

---

## 2. 发现与会话（M0/M1）

### 2.1 `get_ide_state` — viewer · M0
Agent 首调工具：实例自证 + 项目发现 + whoami 合一。

- 参数：`project:string?`
- 返回：`{ideVersion, pluginVersion, protocolVersions[], port, openProjects[{name, path, buildSystem, mavenized, isTrusted}], me{subject, role, allowedProjects, boundProject?, quotaUsage}, serverTimeMs, capabilities{build, run, debug, maven}}`

### 2.2 `bind_project` — viewer · M0
- 参数：`project:string`（必填，路径或名称）
- 返回：`{bound:true, project{name, path}}`
- 绑定后本 MCP 会话内所有工具默认路由到该项目；须 ∈ allowedProjects。

---

## 3. 运行配置与执行会话（M1，核心域）

### 3.1 `list_run_configurations` — viewer · M1
- 参数：`project:string?`, `type_id:string?`, `name_contains:string?`
- 返回：`[{name, typeId, typeDisplayName, folder, isTemporary, allowParallel, canDebug, summary{mainClass?, module?, vmOptions?, activeProfiles?}}]`
- 说明：寻址键 = `(project, typeId, name)`（同名不同类型可共存）。Spring Boot / Tomcat 等 Ultimate 闭源类型仅按 typeId 字符串透传，不硬编码白名单（R02）。

### 3.2 `run_configuration` — operator · M1
按名称以 run 或 debug 启动，**一步到位**（start + wait + tail 合一）。

- 参数：`name:string`（必填）, `type_id:string?`（同名歧义时必填）, `project:string?`, `mode:enum(run|debug)=run`, `wait:enum(none|exit|ready)=ready`, `ready_pattern:string?`（ERE；缺省时 wait=ready 等价于 wait=none+首行到达）, `timeout_sec:int=120`, `tail_lines:int=100`, `env:map?`, `program_args:string?`
- 返回：`{sessionId, state:running|exited|not_started|timeout, exitCode?, debugSessionId?, tail[{offset, ts, stream, text}], nextOffset, startupError?{message, buildErrors[{file, line, message}]}}`
- 语义：
  - 同时监听 `processStarted` / `processNotStarted` 防悬挂（R02）；before-launch 构建失败时 `startupError` 直接携带构建诊断；
  - 单实例配置已有实例在跑 → `CONFLICT`（附现存 sessionId），不弹平台模态框；
  - before-launch 构建并入项目构建互斥（D11）。

### 3.3 `list_sessions` — viewer · M1
- 参数：`project:string?`, `state:enum(running|terminated|all)=running`
- 返回：`[{sessionId, project, configName, typeId, executor:run|debug, state, exitCode?, startedAt, endedAt?, startedBy:agent|user, ownerSubject, restartOf?, pty, attachedLate, firstAvailableOffset, nextOffset, hasDebug, hasTestResults}]`
- 说明：**包含用户手动点绿色按钮启动的会话**（`startedBy=user`）——竞品全生态缺失（R06）。`attachedLate=true` 提示历史输出不完整。

### 3.4 `stop_session` — operator（自己的）/ admin（他人或 user 的）· M1
- 参数：`session_id:string`, `force:bool=false`, `wait:bool=true`, `timeout_sec:int=20`
- 返回：`{state, exitCode?, detached?, finalTail[≤20]}`
- 语义：`force=false` 先 soft kill（KillableProcessHandler），超时后 force 兜底；Remote JVM Debug 会话语义为 detach 并在返回注明。

### 3.5 `restart_session` — operator（自己的）/ admin（他人或 user 的）· M1 ★核心①
重新运行一个执行会话，**可切换 run/debug 模式**。

- 参数：`session_id:string`（必填）, `mode:enum(same|run|debug)=same`, `wait:enum(none|exit|ready)=ready`, `ready_pattern:string?`, `timeout_sec:int=120`, `tail_lines:int=100`
- 返回：同 `run_configuration`，另加 `restartOf:string`（旧 sessionId）
- 语义：
  - `mode=same`：`ExecutionUtil.restart(descriptor)`（平台原生重启）；
  - `mode=run|debug` 且与当前不同：编排 stop（soft→wait→force）→ 以原会话的 `RunnerAndConfigurationSettings` 在目标 executor 下重新 launch；
  - 已终止的会话同样可 restart（跳过 stop 步骤）——"把刚退出的进程再跑一遍/换 debug 跑"；
  - 对 `startedBy=user` 的会话执行需 admin（或设置页开启的宽限策略），强制审计。

### 3.6 `get_test_results` — viewer · M2
- 参数：`session_id:string`, `failed_only:bool=true`, `max_nodes:int=200`, `stacktrace_max_chars:int=4000`
- 返回：`{summary{total, passed, failed, ignored, durationMs}, failures[{name, locationUrl, message, stacktrace}], hasMore}`
- 说明：走 `SMTRunnerEventsListener` 结构化通道（覆盖 Gradle 委托测试），非文本嗅探（R03）。

---

## 4. 控制台读取与检索（M1，核心域）

### 4.1 `console_read` — viewer · M1
增量读取 + 长轮询等待合一。

- 参数：`session_id:string`（必填）, `from_offset:long?`（缺省=firstAvailable）, `max_lines:int=500`, `max_bytes:int=65536`, `streams:enum[](stdout|stderr|system)?`, `wait_for_pattern:string?`（ERE）, `wait_for_exit:bool=false`, `wait_timeout_sec:int=0(≤60)`
- 返回：`{lines[{offset, ts, stream, text, truncated}], nextOffset, firstAvailableOffset, gap:bool, droppedLines?, sessionState, exitCode?, matched?{offset, ranges}}`
- 语义：`wait_for_pattern`/`wait_for_exit` 把"轮询直到出现 X 或进程退出"合并为一次调用（等 Spring Boot `Started …` 就绪、等测试跑完）；到达即返回，超时返回当前进度不报错。

### 4.2 `console_search` — viewer · M1 ★核心②
服务端 grep（`grep -E -i -A -B -m` 语义）。

- 参数：`session_id:string`（必填）, `pattern:string`（必填，ERE，长度 ≤512）, `ignore_case:bool=false`, `before:int=0(≤20)`, `after:int=0(≤20)`, `max_matches:int=100(≤1000)`, `from_offset:long?`, `to_offset:long?`, `since_ms:long?`, `until_ms:long?`, `streams:enum[]?`
- 返回：`{hits[{line{offset, ts, stream, text}, before[], after[], matchRanges[[start,end]]}], totalScanned, truncatedByMaxMatches, gap}`
- 语义：单请求 500ms 检索预算（deadline-CharSequence 防 ReDoS），相邻命中的上下文自动合并；元数据让 Agent 判断结果完整性。

---

## 5. 构建（M2）

### 5.1 `build` — operator · M2
单工具覆盖 IDE"构建"菜单四语义（R01：ProjectTaskManager 四方法逐一映射，兼容 delegated build）。

- 参数：`project:string?`, `scope:enum(project|module|files)=project`, `mode:enum(incremental|rebuild)=incremental`, `modules:string[]?`（scope=module 必填）, `files:string[]?`（scope=files 必填）, `wait:bool=true`, `timeout_sec:int=300`, `max_errors:int=50`
- 映射：project+incremental=`buildAllModules()`（⌘F9）；project+rebuild=`rebuildAllModules()`；module+incremental=`build(modules)`；module+rebuild=`rebuild(modules)`；files=`compile(files)`（⇧⌘F9，预校验文件在 source root）
- 返回：`{status:ok|errors|aborted|queued|timeout, buildId, operationId?, errors[{file, line, column, message, severity, module?}], errorsTotal, warningsTotal, durationMs, queuePosition?}`

### 5.2 `get_build_status` — viewer · M2
- 参数：`project:string?`, `build_id:string?`
- 返回：无参=队列全景 `{queue[{buildId, requestedBy, kind, state, queuePosition}], current?, lastResult?}`；有参=该构建状态+诊断摘要。
- 说明：用户手动菜单构建经 `ProjectTaskListener` 感知并上报为 `EXTERNAL_BUILD_RUNNING`。

### 5.3 `get_operation` — viewer · M2
- 参数：`operation_id:string`（必填）, `wait_sec:int=0(≤60)`
- 返回：`{state:queued|running|done|failed|cancelled, progress?{done, total, message}, queuePosition?, result?}`（result 与发起工具返回同构）
- 长操作统一句柄：build / maven_sync / download_sources 超时或 `wait=false` 后续查；`wait_sec>0` 长轮询省往返。

### 5.4 `cancel_operation` — operator（自己发起的）/ admin（他人的）· M2
- 参数：`operation_id:string`
- 返回：`{accepted:bool, bestEffort:true, note}`
- 构建取消无公开 API（R01），JPS 经 ProgressIndicator、Gradle 经 ExternalSystem 任务取消，**声明 best-effort**。

---

## 6. 调试器（M4；其中 `reload_classes` ★核心③ 随 M2 首发）

除断点与 `reload_classes` 外，均以 `debug_session_id`（= list_sessions 里 `hasDebug=true` 会话的 debugSessionId）寻址。

### 6.0 `reload_classes` — operator（属主规则同 stop）· M2 ★核心③
热加载：debug 运行中把"简单修改"重新编译并热替换进目标 JVM，**不重启进程**。等价于用户在 IDEA 里的习惯操作"在修改的类上重新构建 → IDE 自动重载"。

- 参数：`session_id:string`（必填，须为 debug 会话，即普通执行会话 id）, `files:string[]?`（指定=只重编译并重载这些文件；缺省=编译并重载**所有已修改**的类）, `timeout_sec:int=60`
- 返回：`{outcome:reloaded|nothing_to_reload|failed|cancelled, compile{status, errors[{file, line, message}]}, advice?}`
- 语义：
  - 仅方法体内改动可热替换（JDWP `redefineClasses` 标准边界）；**签名变更/增删方法/增删字段/增删类** → `outcome=failed` + `advice`："结构性变更需重启，请用 restart_session(mode=debug)"；
  - 编译失败 → `outcome=failed` 且 `compile.errors` 携带结构化诊断（复用 05 双通道），**不发起重载**；
  - 无改动 → `nothing_to_reload`；非 debug 会话 → `NOT_DEBUG_SESSION`；
  - 底层：`HotSwapUI.reloadChangedClasses(session, compileBeforeHotswap, HotSwapStatusListener)`（详见 06 §7）。

### 6.1 `breakpoints` — 读=viewer，写=operator（含 condition/log_expression 时=admin）· M4
增删列合一，每次返回全量现状。

- 参数：`project:string?`, `set:[{file, line, condition?, log_expression?, suspend:enum(all|thread|none)=all, enabled:bool=true}]?`, `remove:[{id} | {file, line}]?`, `clear_file:string?`
- 返回：`{breakpoints[{id, file, line, condition?, logExpression?, suspendPolicy, enabled, valid}]}`
- 语义：Java 行断点 type id `java-line`；写操作包 WriteAction（兼容 233~最新，R02）；`valid=false` 提示该行不可断；condition/log_expression 是可执行代码 → 升 admin。

### 6.2 `debug_control` — operator（属主规则同 stop）· M4
- 参数：`debug_session_id:string`（必填）, `action:enum(pause|resume|step_over|step_into|step_out|run_to_line|wait_for_pause)`（必填）, `file:string?`, `line:int?`（run_to_line 用）, `ignore_breakpoints:bool=false`, `timeout_sec:int=30`
- 返回：`{state:paused|running|stopped, pausedAt?{file, line, method, reason:breakpoint|step|pause}, topFrames[≤5{index, method, file, line}], consoleNextOffset}`
- 语义：step/resume 后自动等待下一次 `sessionPaused`（或超时返回 running）并附栈顶 5 帧——常见调试循环从 3 次往返压到 1 次。

### 6.3 `get_stack` — viewer · M4
- 参数：`debug_session_id:string`, `thread_id:string?`, `from:int=0`, `count:int=20`
- 返回：`{threads?[{id, name, state}], frames[{index, method, class, file?, line?, libraryFrame}], hasMore}`
- 未暂停 → `NOT_SUSPENDED`。

### 6.4 `get_variables` — viewer · M4
- 参数：`debug_session_id:string`, `frame_index:int=0`, `path:string?`（如 `this.repo.items[3]`）, `max_children:int=100`
- 返回：`{variables[{name, type?, value, hasChildren}], hasMore}`
- `computeChildren` 分页语义透传（平台每批 100，R02）；`path` 下钻免逐层展开。

### 6.5 `evaluate` — admin · M4
- 参数：`debug_session_id:string`, `expression:string`（必填）, `frame_index:int=0`, `timeout_sec:int=10`
- 返回：`{value, type?, hasChildren} | {error}`
- ≈ 目标 JVM 任意代码执行：`destructiveHint=true`；策略三档（off / 只读表达式档 / unrestricted，默认只读档，见 04）；全量审计（含表达式原文）；未暂停 → `NOT_SUSPENDED`；硬超时（回调可能永不到达）。

---

## 7. Maven（M5，optional descriptor；插件缺席时全部返回 `UNAVAILABLE`）

### 7.1 `maven_sync` — operator · M5
- 参数：`project:string?`, `full:bool=true`, `wait:bool=true`, `timeout_sec:int=600`
- 返回：`{status, problems[{pomPath, type:SYNTAX|STRUCTURE|DEPENDENCY|PARENT|SETTINGS_OR_PROFILES|REPOSITORY, message}], operationId?}`
- 语义：前置 `saveAllDocuments`(EDT)；走 suspend `updateAllMavenProjects`（保底 `forceUpdateAllProjectsOrFindAllAvailablePomFiles`，R04）；同步"成功/失败"以汇总 `MavenProject.problems` 判定（syncFinished 无成败标志）；untrusted project 直接返回结构化错误（附 IDE 内操作指引），不触发信任弹窗。

### 7.2 `maven_modules` — viewer · M5
- 参数：`project:string?`
- 返回：`{roots[{groupId, artifactId, version, pomPath, packaging, isAggregator, profiles[], problems[], children[](递归)}]}`
- `getRootProjects()+getModules()` 重建树，与 Maven 工具窗口一致（截图 4）。

### 7.3 `maven_run_goal` — operator（白名单内）/ admin（任意 goal）· M5
- 参数：`project:string?`, `goals:string[]`（必填）, `pom_path:string?=根pom`, `profiles_enable:string[]?`, `profiles_disable:string[]?`, `properties:map?`, `skip_tests:bool?`, `vm_options:string?`, `wait:enum(none|exit)=exit`, `timeout_sec:int=600`, `tail_lines:int=100`
- 返回：同 `run_configuration`（产生标准执行会话，可继续 `console_read` / `console_search`）
- 语义：走 `MavenRunConfigurationType.runConfiguration` 标准执行管线（人机同视 Run tab，控制台捕获零特判，R04）；白名单默认 `clean/validate/compile/test/package/verify`，白名单外（`exec:java`、`install`、`deploy`、任意插件 goal ≈ RCE）需 admin。

### 7.4 `maven_profiles` — 查询=viewer，变更=operator · M5
- 参数：`project:string?`, `enable:string[]?`, `disable:string[]?`, `reset:string[]?`（全缺省=纯查询）
- 返回：`{profiles[{id, state:none|explicit|implicit}], syncTriggered:bool}`
- 变更走 `getExplicitProfiles().clone → setExplicitProfiles`，自动触发增量同步（R04）。

### 7.5 `maven_download_sources` — operator · M5（P2）
- 参数：`project:string?`, `sources:bool=true`, `docs:bool=false`, `wait:bool=false`, `timeout_sec:int=900`
- 返回：`{operationId | result{resolved:int, unresolved[{groupId, artifactId, version}]}}`
- 242/2026 签名断层运行期反射分派，失败回退触发 IDE action，best-effort（R04）。

---

## 8. 治理（M3/M6）

### 8.1 `get_audit_log` — admin · M3
- 参数：`since_ms:long?`, `until_ms:long?`, `subject:string?`, `tool:string?`, `limit:int=200`
- 返回：`{entries[{ts, subject, role, tool, project, argsDigest, resultCode, durationMs, mcpSessionId?}], truncated}`

### 8.2 `events_poll` — viewer · M6（P2）
- 参数：`cursor:long?`, `max_events:int=100`
- 返回：`{events[{type:buildFinished|sessionStarted|sessionTerminated|debugPaused|mavenSyncFinished, ts, payload}], nextCursor}`
- 绑定项目范围内的事件游标轮询；多 Agent 协同感知彼此动作用。

---

## 9. 角色 × 工具速查

| 工具 | viewer | operator | admin |
| --- | :-: | :-: | :-: |
| get_ide_state / bind_project / list_run_configurations / list_sessions / console_read / **console_search** / get_test_results / get_build_status / get_operation / get_stack / get_variables / maven_modules / maven_profiles(查) / breakpoints(列) / events_poll | ✅ | ✅ | ✅ |
| run_configuration / **restart_session**(自己的) / stop_session(自己的) / build / **reload_classes**(自己的) / cancel_operation(自己的) / breakpoints(增删) / debug_control(自己的) / maven_sync / maven_run_goal(白名单) / maven_profiles(改) / maven_download_sources | ❌ | ✅ | ✅ |
| stop/restart/debug_control **他人或 user 的会话** / evaluate / 条件断点·log_expression / maven_run_goal(任意) / cancel_operation(他人) / get_audit_log | ❌ | ❌ | ✅ |

## 10. Token 经济性预算

| 工具 | 典型返回预算 |
| --- | --- |
| console_read | ≤ max_bytes（默认 64KB），行内截断标记 |
| console_search | ≤ max_matches×(1+before+after) 行，默认 100 命中 |
| build | 错误默认前 50 条 + errorsTotal 汇总 |
| get_variables / get_stack | 分页，hasMore 透传 |
| get_test_results | 默认只回失败项 |
