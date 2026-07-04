# IDEA Bridge

An IntelliJ IDEA plugin that exposes the IDE's **Build** and **Run/Debug** capabilities over a
local **HTTP API** and an embedded **MCP (Model Context Protocol) server**, so external tools such
as **Claude Code** can drive the IDE: trigger builds, launch run/test configurations, stream console
logs, and control debug sessions.

The three screenshots this was built from map to the three capability groups:

| IDE feature                                   | Capability                                   | HTTP / MCP                          |
| --------------------------------------------- | -------------------------------------------- | ----------------------------------- |
| **Build** menu (project / module / file)      | make / rebuild / build module / recompile    | `POST /api/build` · tool `build`    |
| **Run/Debug** config selector                 | list & launch run/test configs               | `POST /api/run` · tool `run`        |
| **Debug session** toolbar + console           | read logs, stop, resume/pause/step           | `/api/sessions/...` · session tools |

The server binds to `127.0.0.1` only. Port + optional token: **Settings → Tools → IDEA Bridge**.

---

## Build & install

Requires JDK 17 or 21 available on the machine (both are auto-detected by Gradle).

```bash
cd idectl
./gradlew buildPlugin          # produces build/distributions/idectl-1.0.0.zip
```

Install the zip in IDEA: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, then restart.

Try it live in a sandbox IDE without installing:

```bash
./gradlew runIde
```

> The `platformVersion` in `gradle.properties` (default `2024.2.4`, Community) is only the **compile**
> target — the built plugin also runs on IntelliJ IDEA Ultimate and newer builds (no upper bound).
> Bump it to match your IDE if you prefer.

Once a project is open you'll see a balloon: `IDEA Bridge running at http://127.0.0.1:63344 (MCP: /mcp)`.

---

## Register with Claude Code (MCP)

```bash
claude mcp add --transport http idectl http://127.0.0.1:63344/mcp
```

If you set an access token in settings, add it as a header:

```bash
claude mcp add --transport http idectl http://127.0.0.1:63344/mcp \
  --header "Authorization: Bearer YOUR_TOKEN"
```

MCP tools exposed: `list_projects`, `build`, `list_run_configs`, `run`, `list_sessions`,
`get_session_output`, `stop_session`, `debug_step`.

> "启动的日志也可以通过 MCP 检索": the plugin subscribes to IDE execution events at startup, so a
> session you start **by hand** (green Run button) is tracked too. Use `list_sessions` to find it,
> then `get_session_output` to read its console incrementally.

---

## HTTP API

Base: `http://127.0.0.1:63344`

### Build — `POST /api/build`
```jsonc
{ "type": "project" }                              // make whole project (⌘F9)
{ "type": "rebuild" }                              // rebuild project
{ "type": "module", "module": "hotel-model" }      // build one module
{ "type": "files", "files": ["/abs/path/HotelChannelGroup.java"] }  // recompile file(s)
```
Response: `{ success, aborted, errors, warnings, durationMs, messages:[{category,message,file}] }`

### List run configs — `GET /api/run-configs`
`[{ "name": "OlympiaApiTest.test_static_getCountry", "type": "JUnit", "folder": null }, ...]`

### Run / debug — `POST /api/run`
```jsonc
{ "name": "OlympiaApiTest.test_static_getCountry" }                 // run, returns immediately
{ "name": "OlympiaApiTest.test_static_getCountry", "waitForTerminate": true }  // wait for result
{ "name": "OlympiaApiTest.test_booking_hotelAvail_direct", "executor": "debug" }
```
Returns `{ sessionId, name, executor, alive, terminated, exitCode, nextOffset, outputTail? }`.

### Sessions
```
GET  /api/sessions
GET  /api/sessions/{id}/output?since=0      -> { text, nextOffset, alive, terminated, exitCode }
POST /api/sessions/{id}/stop
POST /api/sessions/{id}/resume|pause|stepOver|stepInto|stepOut|stop   (debug only)
```
Read logs incrementally by feeding the previous `nextOffset` back as `since`.

---

## curl examples

```bash
# whole-project build
curl -s localhost:63344/api/build -d '{"type":"project"}'

# build one module
curl -s localhost:63344/api/build -d '{"type":"module","module":"hotel-model"}'

# run a test and wait for the result
curl -s localhost:63344/api/run -d '{"name":"OlympiaApiTest.test_static_getCountry","waitForTerminate":true}'

# start a long-running app (returns immediately with a sessionId)
SID=$(curl -s localhost:63344/api/run -d '{"name":"Application"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["sessionId"])')

# tail its console
curl -s "localhost:63344/api/sessions/$SID/output?since=0"

# stop it
curl -s -X POST "localhost:63344/api/sessions/$SID/stop"
```

---

## Architecture

```
IdeaBridgeStartup (ProjectActivity)
  ├─ subscribes each project to ExecutionManager.EXECUTION_TOPIC  ──▶  BridgeExecutionListener
  │      every started run/debug process becomes a tracked BridgeSession (buffered console)
  └─ starts IdeaBridgeService (@Service APP)
         └─ HttpServer (127.0.0.1) ─▶ HttpRouter ─┬─ /mcp   ─▶ McpApi   ┐
                                                  └─ /api/* ─▶ RestApi  ┴─▶ BridgeController ─▶ IntelliJ APIs
                                                                              (CompilerManager, RunManager,
                                                                               ProgramRunnerUtil, XDebuggerManager)
```

`BridgeController` is the single implementation; REST and MCP are thin adapters over it.
