# idectl (IDE Control)

**English** · [简体中文](README.md)

An IntelliJ IDEA plugin that embeds an **MCP (Model Context Protocol) server** so AI coding agents —
**Claude Code**, **Codex** — can drive the IDE: run & debug configurations (switch run↔debug in place),
read and `grep` live console output, build, hot-reload changed classes, set breakpoints and inspect a
paused program, manage projects / run configs / SDKs, and hand different agents scoped, role-limited
access.

The server speaks Streamable-HTTP JSON-RPC, binds to `127.0.0.1` only, and is guarded by a Bearer
token. Everything is configured under **Settings → Tools → IDE Control**.

- **Plugin id / Kotlin package**: `com.niandui.idectl` · **MCP server name**: `idectl`
- **Target**: IntelliJ IDEA **2026.x** only (Ultimate or Community)

---

## Install

Build and install into your everyday IDE in one command, then restart IDEA:

```bash
./idectl.sh deploy
```

Or build the zip and install it by hand:

```bash
./idectl.sh build            # -> build/distributions/idectl-<ver>.zip
# Settings → Plugins → ⚙ → Install Plugin from Disk… → pick the zip → Restart
```

Try it in a throwaway sandbox IDE without touching your install (`./idectl.sh -h` for options):

```bash
./idectl.sh run
```

When you open the first project, a balloon reports the port and a ready-to-paste connect command.

---

## Connect an agent

Easiest — in **Settings → Tools → IDE Control → 一键接入 Agent**, click **一键配置** for Claude Code
or Codex; it writes the current port + access token into that agent's MCP config for you.

Manually:

```bash
# Claude Code (user / global scope)
claude mcp add --transport http idectl http://127.0.0.1:48620/mcp \
  --header "Authorization: Bearer <ACCESS_TOKEN>" -s user
```

```toml
# Codex — ~/.codex/config.toml
[mcp_servers.idectl]
url = "http://127.0.0.1:48620/mcp"

[mcp_servers.idectl.http_headers]
Authorization = "Bearer <ACCESS_TOKEN>"
```

The access token is shown on the settings page; the default port is `48620` (it scans upward if busy).

---

## What the agent can do

| Group | Tools |
| --- | --- |
| Discovery | `get_ide_state`, `bind_project` |
| Project lifecycle | `open_project`, `list_recent_projects`, `close_project`, `refresh_vfs` |
| Build & run config | `reimport_project`, `create/update/delete_run_configuration`, `set_project_sdk` |
| Run & sessions | `list_run_configurations`, `run_configuration`, `run_main`, `list_sessions`, `stop_session`, `restart_session` |
| Console | `console_read`, `console_search` (grep over live + archived output) |
| Build & hot-reload | `build`, `reload_classes` |
| Test | `run_test`, `get_test_results` |
| Debugger | `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `debug_control` (resume/pause/step/run-to-line), `get_stack`, `get_variables`, `evaluate` |
| Governance (admin) | `create_token`, `list_tokens`, `revoke_token` |

`restart_session` relaunches a running session and can switch it between **run** and **debug** in
place. Console output is kept in a bounded in-memory ring and spilled to a bounded on-disk archive, so
old logs stay searchable with `console_search`.

---

## Governance

Two independent layers, both configured on the settings page:

- **Tools (WHAT).** A grouped checkbox tree is the owner's kill switch: disable a tool — or a whole
  group — and it becomes invisible and uncallable to *every* agent, admin included. Per tool you can
  also require **human approval** (a balloon you approve/deny, fail-closed on timeout) and override the
  wait **timeout**.
- **Tokens (WHO / WHERE).** The primary **access token** is always admin over all projects. Issue
  additional **scoped tokens** with a role — **viewer** (read-only), **operator** (run/debug),
  **admin** (manage tokens) — plus an optional project allow-list; out-of-scope projects are invisible
  to that token, and `tools/list` is filtered by role.

Effective visibility = *tool enabled* **AND** *role satisfied* **AND** *project in scope*.

---

## Architecture

```
IdectlBootstrap (ProjectActivity, runs per project)
  └─ IdectlService (@Service APP, singleton — started once on first project open)
       ├─ Ktor CIO server on 127.0.0.1:<port>  →  /mcp  (Streamable-HTTP JSON-RPC)
       │      AuthGate (Bearer → Principal) → McpTransportAdapter → ToolGate → Tool
       ├─ ToolGate — the single choke point every call passes through:
       │      role check → kill-switch → project routing/scope → timeout inject
       │      → human approval → execute → audit
       └─ IdectlProjectService (@Service PROJECT): run launcher, console store, debug controller
```

- The MCP layer is hand-rolled over the platform's **bundled Ktor 3.x** — the plugin bundles **no**
  Ktor or kotlinx-coroutines of its own (a second copy would split the classloader graph).
- Instance discovery is written to `~/.idectl/instances.json`; the audit log to `~/.idectl/audit/`.

---

## Build & develop

`./idectl.sh -h` documents the helper script (`build` / `deploy` / `run` / `clean`).

- **Toolchain**: JDK 21, Gradle 9, IntelliJ Platform Gradle plugin 2.x, Kotlin 2.3.x.
- **Target**: IntelliJ IDEA 2026.x only (`sinceBuild = 261`, no legacy fallbacks).
- **Constraint**: never bundle Ktor or kotlinx-coroutines — declare them `compileOnly`; the
  `verifyNoCoroutinesInZip` build guard fails the build if a coroutines jar sneaks into the zip.
- Progress & pitfalls are logged in `docs/11-实现进度与问题日志.md`; design lives in `docs/01`–`docs/10`.

## Where state lives

| What | Path |
| --- | --- |
| Plugin settings | `<IDE config>/options/idectl.xml` |
| Instance discovery | `~/.idectl/instances.json` |
| Audit log | `~/.idectl/audit/*.jsonl` |
| Console archive (temp) | `<temp>/idectl-console/<sessionId>` |

---

## License

[MIT](LICENSE) © 2026 lyd. This plugin is original code; all platform dependencies (IntelliJ
Platform, Ktor, kotlinx-coroutines, …) are `compileOnly`, provided by the IDE at runtime, and are
not distributed with the plugin.
