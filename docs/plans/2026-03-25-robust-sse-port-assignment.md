# Robust SSE Port Assignment Implementation Plan

Created: 2026-03-25
Status: VERIFIED
Approved: Yes
Iterations: 1
Worktree: No
Type: Feature

## Summary

**Goal:** Make the SSE listener port assignment robust against conflicts and expose the assigned port to end users.
**Architecture:** Add a port resolution layer in `McpServerApplication` that pre-checks port availability before Spring Boot startup, with fallback scanning. After startup, log the URL to Ghidra's console and write PID-based port files for multi-instance discovery.
**Tech Stack:** Java (Spring Boot 3.3.x, Jetty, Spring AI MCP WebFlux starter), Ghidra Plugin API

## Scope

### In Scope

- Port availability pre-check with fallback scanning (up to 10 ports from default)
- User override via `-Dghidra.mcp.port=NNNN` system property or `GHIDRA_MCP_PORT` env var (explicit overrides fail hard — no scan)
- Port file management at `~/.ghidra-mcp/port.<pid>` with stale cleanup
- Console/log output of the SSE URL after successful startup
- Port file cleanup on last plugin removal and via JVM shutdown hook
- Update README with new configuration options
- Unit tests for port resolution logic

### Out of Scope

- GUI dialog/popup for port display (console log is sufficient)
- Auto-discovery protocol for MCP clients (clients still manually configure the URL)
- Changes to the MCP tool definitions in `GhidraService`
- Moving `startServer()` off the EDT (pre-existing design; added overhead is negligible)

## Approach

**Chosen:** Pre-check with ServerSocket + PID-based port files
**Why:** Pre-checking ports before Spring Boot starts avoids partial server lifecycle failures and retry loops. PID-based files are simple, lock-free, and naturally handle multi-instance scenarios.
**Alternatives considered:**
- *Catch PortInUseException + retry* — rejected because it requires handling partial Spring Boot startup/shutdown cycles, adding complexity
- *Port 0 as fallback* — rejected because OS-assigned random ports are unpredictable and harder for users to configure clients against

## Context for Implementer

> Write for an implementer who has never seen the codebase.

- **Patterns to follow:** The server is a Spring Boot app (`McpServerApplication.java:17-28`) started from a Ghidra plugin (`GhidraMCPPlugin.java:48-54`). The plugin registers itself, and the first registration triggers `SpringApplication.run()`. Multiple CodeBrowser windows register as additional plugins without restarting the server.
- **Conventions:** The project has 3 Java files in `org.suidpit`. No logging framework is currently used — Ghidra provides `ghidra.util.Msg` for console output. Spring Boot uses `application.yml` for configuration.
- **Key files:**
  - `src/main/java/org/suidpit/McpServerApplication.java` — server lifecycle, plugin management
  - `src/main/java/org/suidpit/GhidraMCPPlugin.java` — Ghidra plugin entry point
  - `src/main/resources/application.yml` — Spring Boot config with hardcoded port 8888
  - `README.md` — user-facing docs referencing port 8888
- **Gotchas:**
  - `server.port` and `spring.ai.mcp.server.port` in `application.yml` are both set to 8888 — both must stay in sync or be set programmatically
  - The server runs inside Ghidra's JVM, so system properties set before `SpringApplication.run()` are visible to Spring Boot
  - `McpServerApplication.startServer()` is called from the Swing EDT via `GhidraMCPPlugin.init()` — Spring Boot startup already blocks the EDT; the added port-check and file I/O overhead is negligible (a few ms). This is an accepted trade-off, not a new concern.
  - Port file directory (`~/.ghidra-mcp/`) may not exist on first run
  - `stopServer()` is never called from plugin code — `GhidraMCPPlugin.dispose()` calls `removePlugin()` only. Port file cleanup must happen in `removePlugin()` when the last plugin is removed.
  - `SpringApplication.setDefaultProperties()` has LOWER priority than `application.yml`. Use command-line args (`--server.port=NNNN`) passed to `SpringApplication.run()` instead — command-line args have highest priority in Spring Boot's property resolution order.
- **Domain context:** This is an MCP (Model Context Protocol) server that exposes Ghidra reverse engineering tools to LLM clients via SSE transport. Clients like Claude Code and Cursor connect to `http://localhost:<port>/sse`.

## Assumptions

- Command-line args passed to `SpringApplication.run()` override `application.yml` values — supported by Spring Boot externalized configuration docs (command-line args are highest priority) — Tasks 1, 3 depend on this
- `java.net.ServerSocket` on port with immediate close reliably indicates port availability — standard Java networking — Task 1 depends on this
- `ProcessHandle.of(pid).isPresent()` reliably detects if a JVM process is still alive — Java 9+ API — Task 2 depends on this
- Ghidra's `Msg.info()` outputs to the Ghidra console visible to users — standard Ghidra plugin API — Task 3 depends on this

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| TOCTOU race: port free during check but taken before Spring binds | Low | Medium | Catch `PortInUseException` / `BindException` from `SpringApplication.run()` and log a clear message: "Port NNNN was available during pre-check but is now in use. Set GHIDRA_MCP_PORT to a different port." |
| Port file not cleaned up (crash/kill -9) | Medium | Low | Stale file cleanup checks if PID is alive on every startup; JVM shutdown hook as additional safety net |
| `spring.ai.mcp.server.port` property may be required by the MCP starter | Low | Medium | Set both `server.port` and `spring.ai.mcp.server.port` via command-line args |
| `~/.ghidra-mcp/` directory creation fails (permissions) | Low | Low | Log warning and continue without port file; server still works |

## Goal Verification

### Truths

1. When port 8888 is occupied, the server starts successfully on the next available port
2. The SSE URL (including actual port) is printed to the Ghidra console
3. A port file exists at `~/.ghidra-mcp/port.<pid>` containing the active port
4. Setting `GHIDRA_MCP_PORT=9999` causes the server to use port 9999 exactly (fails if 9999 is occupied — no scan for explicit overrides)
5. Setting `-Dghidra.mcp.port=9999` has the same effect
6. When the last plugin is disposed, the port file is cleaned up
7. Stale port files from dead processes are removed on startup

### Artifacts

- `McpServerApplication.java` — port resolution, file management, logging
- `GhidraMCPPlugin.java` — error handling for startup failures
- `application.yml` — retains 8888 as documented default
- `README.md` — updated documentation
- `src/test/java/org/suidpit/PortResolutionTest.java` — unit tests for port logic

## Progress Tracking

- [x] Task 1: Port resolution logic
- [x] Task 2: Port file management
- [x] Task 3: Console logging, error handling, and lifecycle integration
- [x] Task 4: Update documentation
- [x] Task 5: Unit tests for port resolution

**Total Tasks:** 5 | **Completed:** 5 | **Remaining:** 0

## Implementation Tasks

### Task 1: Port Resolution Logic

**Objective:** Add port availability checking and fallback scanning to `McpServerApplication`. Read port from system property (`ghidra.mcp.port`), then env var (`GHIDRA_MCP_PORT`), then default (8888). When using the default port, scan up to 10 ports to find an available one. When the user explicitly sets a port, use that exact port (fail if unavailable — explicit overrides don't scan).

**Dependencies:** None

**Files:**

- Modify: `src/main/java/org/suidpit/McpServerApplication.java`

**Key Decisions / Notes:**

- Add a `resolvePort()` static method that checks system property → env var → default 8888. Returns a record/pair indicating the port AND whether it was explicitly set by the user.
- Add an `isPortAvailable(int port)` static method using `java.net.ServerSocket` try-with-resources
- Add a `findAvailablePort(int basePort, boolean isExplicit)` method:
  - If `isExplicit` is true: check only the specified port, throw `RuntimeException("Port NNNN is not available. Check if another application is using it.")` if unavailable
  - If `isExplicit` is false: scan basePort through basePort+9, throw if none available in range
- Modify `startServer()` to call `findAvailablePort()` and pass the result to Spring Boot via command-line args: `SpringApplication.run(McpServerApplication.class, "--server.port=" + port, "--spring.ai.mcp.server.port=" + port)` — command-line args have highest priority in Spring Boot, overriding `application.yml`
- Store the resolved port in a `static volatile int activePort` field for use by Task 2 and Task 3
- Wrap `SpringApplication.run()` in try-catch for `PortInUseException` / `BindException` — if caught, log via `Msg.error()`: "Port NNNN was available during pre-check but is now in use (TOCTOU race). Set GHIDRA_MCP_PORT to a different port."

**Definition of Done:**

- [ ] `resolvePort()` reads system property, then env var, then defaults to 8888
- [ ] `isPortAvailable()` correctly detects occupied ports
- [ ] `findAvailablePort()` scans for default port, fails hard for explicit overrides
- [ ] `startServer()` passes port via command-line args to `SpringApplication.run()`
- [ ] TOCTOU race condition caught with clear error message
- [ ] All tests pass
- [ ] No diagnostics errors

**Verify:**

- Manual: occupy port 8888 with `python3 -m http.server 8888` and verify server starts on 8889

### Task 2: Port File Management

**Objective:** Write the assigned port to `~/.ghidra-mcp/port.<pid>` after server startup, clean up stale files from dead processes, and remove the port file on shutdown/last plugin removal.

**Dependencies:** Task 1

**Files:**

- Modify: `src/main/java/org/suidpit/McpServerApplication.java`

**Key Decisions / Notes:**

- Add a `writePortFile(int port)` method:
  - Create `~/.ghidra-mcp/` directory if it doesn't exist
  - Write port number as plain text to `~/.ghidra-mcp/port.<current_pid>`
  - Use `ProcessHandle.current().pid()` to get the PID
- Add a `cleanStalePortFiles()` method:
  - List all `port.*` files in `~/.ghidra-mcp/`
  - Parse PID from filename
  - Use `ProcessHandle.of(pid).isPresent()` to check if process is alive
  - Delete files for dead processes
- Add a `deletePortFile()` method
- Call `cleanStalePortFiles()` before `writePortFile()` in `startServer()`
- Port file cleanup triggers:
  1. In `removePlugin()`: when `plugins.isEmpty()` after removal, call `stopServer()` which calls `deletePortFile()`
  2. In `stopServer()`: call `deletePortFile()` before closing the context
  3. JVM shutdown hook: `Runtime.getRuntime().addShutdownHook(new Thread(() -> deletePortFile()))` — safety net for crashes or Ghidra exits without explicit dispose
- Wrap all file operations in try-catch — file I/O failures must not prevent server startup

**Definition of Done:**

- [ ] Port file written to `~/.ghidra-mcp/port.<pid>` after startup
- [ ] Stale port files from dead PIDs are cleaned on startup
- [ ] Port file deleted when last plugin is removed (via `removePlugin()` → `stopServer()`)
- [ ] JVM shutdown hook registered as safety net for port file cleanup
- [ ] File I/O errors logged but don't prevent server startup
- [ ] All tests pass
- [ ] No diagnostics errors

**Verify:**

- Check `~/.ghidra-mcp/` directory for port file after server start
- Verify file is removed after server stop

### Task 3: Console Logging, Error Handling, and Lifecycle Integration

**Objective:** Log the SSE URL to Ghidra's console after the server starts successfully, handle startup failures gracefully, and integrate the lifecycle changes.

**Dependencies:** Task 1, Task 2

**Files:**

- Modify: `src/main/java/org/suidpit/McpServerApplication.java`
- Modify: `src/main/java/org/suidpit/GhidraMCPPlugin.java`

**Key Decisions / Notes:**

- After `SpringApplication.run()` succeeds in `startServer()`:
  - Use `Msg.info(McpServerApplication.class, "GhidraMCP server started at http://localhost:" + port + "/sse")` (use class as originator since method is static)
  - Also log port file location: `Msg.info(McpServerApplication.class, "Port file: " + portFilePath)`
- Exception handling chain in `startServer()`:
  - `findAvailablePort()` throws `RuntimeException` with clear message if no port available
  - `SpringApplication.run()` may throw `PortInUseException` (TOCTOU race)
  - Catch both in `startServer()`, log via `Msg.error()`, and return gracefully — the server stays down but doesn't crash the Ghidra plugin
- In `GhidraMCPPlugin.init()`: wrap `startMcpServer()` call — if server fails to start, the plugin still loads (just without MCP functionality)
- Add a `getPort()` static method to `McpServerApplication` returning `activePort` (0 if not running)

**Definition of Done:**

- [ ] SSE URL logged to Ghidra console on successful startup
- [ ] Port file path logged to Ghidra console
- [ ] Startup failures (port unavailable, bind errors) produce clear error message via `Msg.error()`
- [ ] Plugin loads gracefully even if server fails to start
- [ ] `getPort()` returns the active port
- [ ] All tests pass
- [ ] No diagnostics errors

**Verify:**

- Check Ghidra console output for URL message after server start

### Task 4: Update Documentation

**Objective:** Update README with the new port configuration options. Keep `application.yml` at 8888 as the documented default (it serves as fallback and documentation).

**Dependencies:** Task 1, Task 2, Task 3

**Files:**

- Modify: `src/main/resources/application.yml` (add comments only — keep port 8888)
- Modify: `README.md`

**Key Decisions / Notes:**

- In `application.yml`: keep `server.port: 8888` and `spring.ai.mcp.server.port: 8888`. Add a comment explaining these are overridden programmatically at runtime but serve as the default and documentation. Do NOT change to port 0 — if the programmatic override fails, 8888 with a clear bind error is better UX than a random ephemeral port.
- In `README.md`:
  - Update the "Installation" section: the server defaults to port 8888 but auto-selects the next available port if 8888 is occupied
  - Update the "Usage" section to explain checking the Ghidra console for the actual port
  - Add a "Configuration" section documenting:
    - `GHIDRA_MCP_PORT` env var
    - `-Dghidra.mcp.port` system property
    - Port file location `~/.ghidra-mcp/port.<pid>`
  - Update the `.mcp.json` example to note users should check the Ghidra console for the actual port
  - Update the Cursor/STDIO `mcp-proxy` example to note users must use the actual port shown in the console

**Definition of Done:**

- [ ] `application.yml` has comments explaining programmatic override
- [ ] README documents default port behavior (8888 with fallback scanning)
- [ ] README documents env var and system property override (explicit = no scan)
- [ ] README documents port file location
- [ ] README `.mcp.json` and `mcp-proxy` examples updated
- [ ] No diagnostics errors

**Verify:**

- Read updated files and verify accuracy

### Task 5: Unit Tests for Port Resolution

**Objective:** Add JUnit tests for the port resolution utility methods (`resolvePort()`, `isPortAvailable()`, `findAvailablePort()`). These are pure Java networking methods that don't require Ghidra.

**Dependencies:** Task 1

**Files:**

- Create: `src/test/java/org/suidpit/PortResolutionTest.java`
- Modify: `build.gradle` (add JUnit test dependency)

**Key Decisions / Notes:**

- Add `testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'` to `build.gradle`
- Add `test { useJUnitPlatform() }` to `build.gradle`
- Test cases:
  - `test_resolvePort_defaultIs8888`: no system property or env var → returns 8888
  - `test_resolvePort_systemPropertyTakesPrecedence`: set `-Dghidra.mcp.port=9999` → returns 9999
  - `test_isPortAvailable_freePort`: check a port that's free → returns true
  - `test_isPortAvailable_occupiedPort`: open a `ServerSocket` on a known port, verify `isPortAvailable()` returns false
  - `test_findAvailablePort_scansUpward`: occupy the base port, verify it returns base+1
  - `test_findAvailablePort_explicitPortFailsHard`: occupy a port, call with `isExplicit=true` → throws RuntimeException
  - `test_findAvailablePort_throwsWhenAllOccupied`: occupy ports in range → throws RuntimeException
- Use `@BeforeEach` / `@AfterEach` to clean up system properties
- Use ephemeral ports (bind to 0 to find a free port, then use that for testing) to avoid test flakiness

**Definition of Done:**

- [ ] JUnit 5 dependency added to `build.gradle`
- [ ] All test cases pass
- [ ] Tests don't require Ghidra runtime
- [ ] Tests clean up system properties and server sockets
- [ ] No diagnostics errors

**Verify:**

- `gradle test` passes with 0 failures

## Open Questions

None — all design decisions resolved.

## Deferred Ideas

- Ghidra GUI popup/notification showing the SSE URL (could be added later if console output proves insufficient)
- MCP client auto-discovery by reading the port file (clients like Claude Code could read `~/.ghidra-mcp/port.*` to auto-configure)
- A `getServerInfo` MCP tool that returns the current port and server status
- Richer port file format (JSON with port, URL, timestamp) for easier automation
- Configurable scan range via `-Dghidra.mcp.port.range=N` (default 10 is sufficient for most cases)
