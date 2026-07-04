# idectl (IDE Control)

An IntelliJ IDEA plugin that embeds an MCP server so AI agents (Claude Code, Codex) can **drive the
IDE**: run/debug configurations (run↔debug), read & grep live console logs, build, hot-reload,
breakpoint debugging, project / run-config / SDK management, and scoped multi-token governance.

- **Plugin id / Kotlin package**: `com.niandui.idectl`
- **Display name** (Settings / Plugins list): “IDE Control”
- **MCP server name** (what agents register): `idectl`
- History: was “Agent MCP Bridge / idea-bridge”. Kotlin **class names** (`IdeaBridge*`, `Bridge*`) are
  kept as-is intentionally — not a leftover to “fix”.

## Build & run

Use the helper script (a thin wrapper over Gradle):

- `./idectl.sh build` — build the plugin zip → `build/distributions/idectl-<ver>.zip`
- `./idectl.sh deploy` — build + install/replace into everyday IntelliJ IDEA (**restart IDEA to load**)
- `./idectl.sh run --token <t> --project <path>` — sandbox IDE for debugging (`runIde`)
- `./idectl.sh -h` — full usage

Toolchain: **JDK 21**, Gradle 9, IntelliJ Platform Gradle plugin 2.x, Kotlin 2.3.x. Target is
**IntelliJ IDEA 2026.x only** (`sinceBuild = 261`, no upper bound).

## Hard constraints — do not violate

- **Target IntelliJ IDEA 2026 only.** No legacy / backward-compat code paths.
- **Never bundle Ktor or kotlinx-coroutines.** The platform provides them; a second copy in the plugin
  classloader splits the class graph → `ClassCastException` at runtime. Declare them `compileOnly`.
  A build guard (`verifyNoCoroutinesInZip`) fails the build if a coroutines jar sneaks into the zip.
- **Record progress AND problems in `docs/11-实现进度与问题日志.md` at every step** — other sessions
  pick up work from it. Read it first.
- **Respond in Chinese.**

## Where state lives at runtime

- Plugin settings: `<IDE config>/options/idectl.xml` (component `IdectlSettings`)
- Instance discovery: `~/.idectl/instances.json`
- Audit log: `~/.idectl/audit/*.jsonl` (one JSONL per day)
- Console archive (temp): `<temp>/idectl-console/<sessionId>`
- Agent MCP config written by the settings page: Claude → `~/.claude.json`; Codex → `~/.codex/config.toml`

## Docs

`docs/01–10` are the design; **`docs/11-实现进度与问题日志.md`** is the running progress + pitfalls log
(read first when resuming work).
