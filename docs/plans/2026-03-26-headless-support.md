# Headless Support Implementation Plan

Created: 2026-03-26
Status: PENDING
Approved: No
Iterations: 1
Worktree: No
Type: Feature

## Summary

**Goal:** Run McG's MCP server without the Ghidra GUI for server, CI, and container deployments. Support both startup binary loading and runtime binary import via MCP tools.
**Architecture:** Create a `GhidraScript` (`McgServer.java`) that runs as an `analyzeHeadless` post-script, starts the MCP server, and blocks. Introduce a `ProgramProvider` interface (with `isHeadless()`) to decouple `GhidraService` from the GUI plugin. Refactor `runTransaction()` to skip Swing EDT dispatch in headless mode. Add `importBinary`/`closeProgram` MCP tools. Wrap in a Containerfile (podman) and launcher script.
**Tech Stack:** Ghidra headless (`analyzeHeadless`), GhidraScript API, Spring Boot, Podman/OCI containers

## Scope

### In Scope

- `ProgramProvider` interface with `isHeadless()` to decouple from `GhidraMCPPlugin`
- Headless-aware `runTransaction()` (skip EDT dispatch when headless)
- `HeadlessProgramProvider` for headless program management
- `McgServer.java` GhidraScript (post-analysis entry point, installed with extension)
- `importBinary` / `closeProgram` MCP tools (importBinary blocks until analysis completes)
- `mcg-headless.sh` launcher script (exec-based for signal forwarding)
- Multi-stage Containerfile (podman-compatible, GHIDRA_VERSION build arg)
- `build.gradle` updates for new Ghidra-dependent files
- Project reference plumbing for runtime imports
- README update with headless docs

### Out of Scope

- Ghidra Server (shared project repository) integration
- Authentication/authorization for network-exposed MCP
- Standalone main() without analyzeHeadless (deferred — GhidraScript is sufficient)
- Web UI for headless management
- importBinary in GUI mode (returns error directing users to Ghidra's import menu)

## Approach

**Chosen:** GhidraScript via `analyzeHeadless`
**Why:** Uses Ghidra's own project management, auto-analysis, language detection, and lifecycle. The script has full access to the analyzed `Program` and the project. Containerfile wraps `analyzeHeadless` with the right args. Zero custom Ghidra initialization code.
**Alternatives considered:**
- *Standalone main() with Ghidra libs* — rejected: requires manual Ghidra initialization which is fragile across versions
- *Container with Xvfb* — rejected: ~2GB image for a virtual framebuffer when headless mode is a few hundred lines of code

## Context for Implementer

> Write for an implementer who has never seen the codebase.

- **Current architecture:**
  - `GhidraMCPPlugin` extends `ProgramPlugin` (GUI). On `init()`, calls `McpServerApplication.startServer(this)`
  - `McpServerApplication` manages `List<GhidraMCPPlugin>`, each providing a `Program` via `getCurrentProgram()`
  - `GhidraService.getProgram()` → `McpServerApplication.getActivePlugin()` → `plugin.getCurrentProgram()`
  - `runTransaction()` uses `SwingUtilities.invokeAndWait()` — **must be refactored for headless mode**

- **Headless Ghidra:**
  - `analyzeHeadless <project_dir> <project_name> [-import <file>] [-postScript <script>]`
  - Post-script receives `currentProgram` (the imported/analyzed program)
  - Script has access to `state.getProject()` for project-level operations
  - Sets `-Djava.awt.headless=true` — no display needed
  - The `analyzeHeadless` wrapper is at `$GHIDRA_INSTALL_DIR/support/analyzeHeadless`

- **Classpath for GhidraScript:**
  - GhidraScripts in `ghidra_scripts/` run in Ghidra's script classloader
  - The extension's classes ARE visible to scripts when the extension is installed (extension JARs are on the classpath)
  - The extension MUST be installed in Ghidra (`$GHIDRA_INSTALL_DIR/Extensions/`) before running `analyzeHeadless` with the script
  - The `mcg-headless.sh` launcher handles this by installing the extension first

- **Key files:**
  - `src/main/java/org/suidpit/GhidraService.java` — all MCP tools (modify: getProgram, runTransaction)
  - `src/main/java/org/suidpit/McpServerApplication.java` — server lifecycle (major refactor: plugin → provider)
  - `src/main/java/org/suidpit/GhidraMCPPlugin.java` — GUI plugin (modify: implement interface)
  - `build.gradle` — update exclusions for new Ghidra-dependent files

- **Gotchas:**
  - `GhidraScript.run()` must NOT return until the server should shut down — if it returns, `analyzeHeadless` exits
  - **Swing EDT in headless:** Ghidra's headless mode may not initialize the AWT EDT. `runTransaction()` must run directly on the calling thread in headless mode instead of dispatching to EDT. Use `ProgramProvider.isHeadless()` to choose the execution strategy.
  - `AutoImporter` requires a `MessageLog` and a target `DomainFolder`
  - Auto-analysis is asynchronous — `importBinary` must block until analysis completes using `AutoAnalysisManager`
  - For runtime imports, the Project reference must be plumbed from the GhidraScript's `state.getProject()` through to `McpServerApplication`
  - The extension must be installed before `analyzeHeadless` can find the script's dependencies

## Assumptions

- Extension classes are visible to GhidraScripts when the extension is installed — supported by Ghidra extension classloading behavior — Tasks 2, 3 depend on this
- `analyzeHeadless` post-scripts can block indefinitely without timeout — supported by Ghidra headless behavior (no script timeout by default) — Task 2 depends on this
- `AutoImporter.importByUsingBestGuess()` handles common binary formats (ELF, PE, Mach-O, raw) — standard Ghidra import API — Task 3 depends on this
- Ghidra transactions do NOT require the Swing EDT — the EDT is used in GUI mode to synchronize with UI updates, but in headless mode transactions can run on any thread — Tasks 1, 2 depend on this
- `AutoAnalysisManager` provides a way to wait for analysis completion — standard Ghidra analysis API — Task 3 depends on this

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| analyzeHeadless exits when post-script returns | Medium | High | Script blocks with CountDownLatch; JVM shutdown hook counts down the latch for clean exit |
| Swing EDT not available in headless | High | High | Detect headless via `ProgramProvider.isHeadless()` and run transactions directly on calling thread |
| GhidraScript can't see extension classes | Medium | High | Extension must be installed in Ghidra before running. Launcher script handles this. Verify at Task 2 DoD. |
| Auto-analysis incomplete when importBinary returns | Medium | Medium | Block until analysis completes using `AutoAnalysisManager.waitForAnalysis()`. Document expected latency in tool description. |
| Project reference not available for runtime imports | Low | High | Store Project reference in McpServerApplication when McgServer script starts. GUI mode provides via plugin tool. |
| Container image too large (~1.5-2GB) | High | Low | Accept. Multi-stage build minimizes additions. Strip Ghidra docs/server dirs. Document expected size. |

## Goal Verification

### Truths

1. `analyzeHeadless` with the extension installed and `-postScript McgServer.java` starts an MCP server that responds to tool calls
2. The server stays running until killed (SIGTERM) using a CountDownLatch shutdown mechanism
3. All existing MCP tools work identically in headless mode (including write operations via runTransaction)
4. `importBinary` tool loads a new binary, blocks until analysis completes, and makes it available via `listOpenPrograms`
5. `closeProgram` removes a program from the active list
6. `podman run mcg /path/to/binary` starts a headless MCP server with that binary analyzed
7. GUI mode (`GhidraMCPPlugin`) continues to work unchanged
8. `importBinary` returns an error in GUI mode (directing users to Ghidra's import menu)

### Artifacts

- `ProgramProvider.java` — interface with `getCurrentProgram()`, `getProviderName()`, `isHeadless()`
- `HeadlessProgramProvider.java` — headless implementation
- `McgServer.java` — GhidraScript for headless entry
- `GhidraService.java` — updated getProgram(), headless-aware runTransaction(), importBinary, closeProgram
- `McpServerApplication.java` — refactored to use ProgramProvider, Project reference storage
- `GhidraMCPPlugin.java` — implements ProgramProvider
- `build.gradle` — updated exclusions
- `mcg-headless.sh` — launcher script
- `Containerfile` — multi-stage OCI build
- `.containerignore` — build context filtering
- `README.md` — headless docs

## Progress Tracking

- [ ] Task 1: ProgramProvider interface, McpServerApplication refactor, and headless-aware runTransaction
- [ ] Task 2: HeadlessProgramProvider, McgServer GhidraScript, and build.gradle updates
- [ ] Task 3: importBinary and closeProgram MCP tools
- [ ] Task 4: Launcher script and Containerfile
- [ ] Task 5: Update README with headless documentation

**Total Tasks:** 5 | **Completed:** 0 | **Remaining:** 5

## Implementation Tasks

### Task 1: ProgramProvider Interface, McpServerApplication Refactor, and Headless-Aware runTransaction

**Objective:** Extract a `ProgramProvider` interface with `isHeadless()`. Refactor `McpServerApplication` to manage `ProgramProvider` instances (no backward-compat overloads — direct replacement). Make `runTransaction()` in `GhidraService` headless-aware: skip Swing EDT dispatch when the active provider is headless.

**Dependencies:** None

**Files:**

- Create: `src/main/java/org/suidpit/ProgramProvider.java`
- Modify: `src/main/java/org/suidpit/McpServerApplication.java`
- Modify: `src/main/java/org/suidpit/GhidraMCPPlugin.java`
- Modify: `src/main/java/org/suidpit/GhidraService.java`
- Modify: `build.gradle`

**Key Decisions / Notes:**

- **ProgramProvider interface:**
  ```java
  public interface ProgramProvider {
      Program getCurrentProgram();
      String getProviderName();
      boolean isHeadless();
  }
  ```
- **McpServerApplication changes:**
  - Replace `List<GhidraMCPPlugin>` with `List<ProgramProvider>`
  - Replace `volatile GhidraMCPPlugin selectedPlugin` with `volatile ProgramProvider selectedProvider`
  - Rename methods: `startServer(ProgramProvider)`, `removeProvider(ProgramProvider)`, `getActiveProvider()`
  - NO backward-compat overloads (YAGNI — only `GhidraMCPPlugin` calls these)
  - Add `registerProvider(ProgramProvider)` — add provider without starting server (for headless)
  - Add `startServerHeadless()` — start server when providers are already registered
  - Add `private static volatile Project activeProject` — store the Ghidra project reference
  - Add `setProject(Project)` and `getProject()` static methods
  - Update `getOpenPrograms()` to use `getProviderName()`
  - Update `selectProgram()` to iterate providers
- **GhidraMCPPlugin changes:**
  - Implement `ProgramProvider`
  - `getProviderName()` returns `"CodeBrowser"`
  - `isHeadless()` returns `false`
  - `startServer(this)` works directly (ProgramProvider signature)
  - `removePlugin(this)` → `removeProvider(this)`
- **GhidraService.runTransaction() changes:**
  ```java
  private void runTransaction(String description, Runnable action) {
      ProgramProvider provider = getProvider();
      if (provider != null && provider.isHeadless()) {
          // Headless: run directly on calling thread (no EDT)
          Program program = getProgram();
          int tx = program.startTransaction(description);
          boolean success = false;
          try {
              action.run();
              success = true;
          } finally {
              program.endTransaction(tx, success);
          }
      } else {
          // GUI: dispatch to EDT as before
          // ... existing SwingUtilities.invokeAndWait code ...
      }
  }
  ```
- **GhidraService.getPlugin() → getProvider():**
  - Rename to `getProvider()` returning `ProgramProvider`
  - Call `McpServerApplication.getActiveProvider()`
- **build.gradle:**
  - Add `ProgramProvider.java` and `HeadlessProgramProvider.java` to the exclusion list for standalone test builds (they import Ghidra types)

**Definition of Done:**

- [ ] ProgramProvider interface with getCurrentProgram(), getProviderName(), isHeadless()
- [ ] McpServerApplication manages ProgramProvider (no GhidraMCPPlugin references)
- [ ] GhidraMCPPlugin implements ProgramProvider (isHeadless=false)
- [ ] GhidraService uses ProgramProvider, runTransaction is headless-aware
- [ ] build.gradle excludes new Ghidra-dependent files from standalone test builds
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`
- Build extension: `GHIDRA_INSTALL_DIR=... ./gradlew distributeExtension`

### Task 2: HeadlessProgramProvider, McgServer GhidraScript, and Build Integration

**Objective:** Create `HeadlessProgramProvider` and the `McgServer.java` GhidraScript entry point. Ensure the script is packaged with the extension and can find McpServerApplication at runtime.

**Dependencies:** Task 1

**Files:**

- Create: `src/main/java/org/suidpit/HeadlessProgramProvider.java`
- Create: `ghidra_scripts/McgServer.java`
- Modify: `build.gradle` (ensure ghidra_scripts/ is included in extension ZIP)

**Key Decisions / Notes:**

- **HeadlessProgramProvider:**
  ```java
  public class HeadlessProgramProvider implements ProgramProvider {
      private final Program program;
      private final String name;

      public HeadlessProgramProvider(Program program) {
          this.program = program;
          this.name = program.getName();
      }

      @Override public Program getCurrentProgram() { return program; }
      @Override public String getProviderName() { return "headless:" + name; }
      @Override public boolean isHeadless() { return true; }
  }
  ```
- **McgServer.java GhidraScript:**
  - Extends `ghidra.app.script.GhidraScript`
  - In `run()`:
    1. Store project: `McpServerApplication.setProject(state.getProject())`
    2. Create `HeadlessProgramProvider(currentProgram)` and register
    3. Start server: `McpServerApplication.startServerHeadless()`
    4. Print: `"McG server started at http://<host>:<port>/sse"`
    5. Block with `CountDownLatch`: create a latch, register JVM shutdown hook that counts down, then `latch.await()`
    6. On unblock → `McpServerApplication.stopServer()`
  - **CountDownLatch pattern** (not Thread.sleep):
    ```java
    CountDownLatch shutdownLatch = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(shutdownLatch::countDown));
    println("McG server running. Press Ctrl+C or send SIGTERM to stop.");
    shutdownLatch.await();
    McpServerApplication.stopServer();
    ```
  - **Classpath validation:** Script must verify it can load `McpServerApplication` — if not, print error explaining the extension must be installed
- **build.gradle:**
  - Ensure `ghidra_scripts/` directory is included in the extension ZIP
  - The Ghidra build system typically handles this via `buildExtension.gradle` if scripts are in the standard location
  - Add `HeadlessProgramProvider.java` to the exclusion list for standalone test builds

**Definition of Done:**

- [ ] HeadlessProgramProvider implements ProgramProvider (isHeadless=true)
- [ ] McgServer.java is a valid GhidraScript
- [ ] Script uses CountDownLatch for blocking (not Thread.sleep)
- [ ] Script stores Project reference for runtime imports
- [ ] Script validates extension classpath at startup
- [ ] Extension ZIP includes ghidra_scripts/McgServer.java
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`
- `GHIDRA_INSTALL_DIR=... ./gradlew distributeExtension && unzip -l dist/*.zip | grep McgServer`
- Manual test: `analyzeHeadless /tmp/mcg McgTest -import /bin/ls -postScript McgServer.java` (requires Ghidra + installed extension)

### Task 3: importBinary and closeProgram MCP Tools

**Objective:** Add MCP tools for runtime binary loading (headless only) and program closing.

**Dependencies:** Task 1, Task 2

**Files:**

- Modify: `src/main/java/org/suidpit/GhidraService.java`

**Key Decisions / Notes:**

- **importBinary(String filePath):**
  - **Headless-only:** Check `getProvider().isHeadless()`. If false (GUI mode), throw error: "importBinary is only available in headless mode. Use Ghidra's File > Import menu in GUI mode."
  - Get project from `McpServerApplication.getProject()`
  - Get root folder: `project.getProjectData().getRootFolder()`
  - Import: `AutoImporter.importByUsingBestGuess(program, file, folder, this, log, monitor)`
  - Wait for analysis: `AutoAnalysisManager.getAnalysisManager(program).startAnalysis(monitor, false)` then wait
  - Create `HeadlessProgramProvider(program)` and register
  - Tool description: "Import a binary file for analysis (headless mode only). Blocks until auto-analysis completes. May take several minutes for large binaries."
  - Return: program name, language ID, file size, analysis status

- **closeProgram(String programName):**
  - Find provider by program name in `McpServerApplication`
  - If headless provider: release the program (`program.release(this)`)
  - Remove from `McpServerApplication`
  - Return confirmation

**Definition of Done:**

- [ ] importBinary imports a binary, runs analysis, registers as new program
- [ ] importBinary blocks until analysis completes
- [ ] importBinary returns error in GUI mode
- [ ] closeProgram removes program from active list
- [ ] Both tools have @Tool annotations with clear descriptions
- [ ] Error handling for invalid paths, unsupported formats
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 4: Launcher Script and Containerfile

**Objective:** Create `mcg-headless.sh` launcher script and multi-stage `Containerfile` for containerized deployment with Podman.

**Dependencies:** Task 2, Task 3

**Files:**

- Create: `mcg-headless.sh`
- Create: `Containerfile`
- Create: `.containerignore`

**Key Decisions / Notes:**

- **mcg-headless.sh:**
  - Usage: `mcg-headless.sh <binary_path> [binary2_path ...]`
  - Validates: `GHIDRA_INSTALL_DIR` set, binary exists, extension installed
  - Installs extension if not found: unzips dist/*.zip into Ghidra's Extensions dir
  - Creates temp project dir: `mktemp -d /tmp/mcg-project.XXXXXX`
  - Uses `exec` to replace shell process with analyzeHeadless (proper signal forwarding for containers):
    ```bash
    exec "$GHIDRA_INSTALL_DIR/support/analyzeHeadless" \
      "$PROJECT_DIR" McG \
      -import "$BINARY" \
      -postScript McgServer.java
    ```
  - Forwards env vars: GHIDRA_MCP_PORT, GHIDRA_MCP_HOST
  - Cleanup on exit (trap): remove temp project dir

- **Containerfile (multi-stage, Podman-compatible):**
  - `ARG GHIDRA_VERSION=12.0.3_PUBLIC` — pinned version with override
  - Stage 1 (build):
    - FROM eclipse-temurin:21-jdk
    - Download Ghidra from GitHub releases
    - Copy source, `./gradlew distributeExtension`
  - Stage 2 (runtime):
    - FROM eclipse-temurin:21-jre
    - Copy Ghidra (exclude docs/, server/ for size)
    - Install extension
    - Copy mcg-headless.sh
    - `ENV GHIDRA_MCP_HOST=0.0.0.0` (container networking)
    - `EXPOSE 8888`
    - `VOLUME /data`
    - `ENTRYPOINT ["./mcg-headless.sh"]`
    - Usage: `podman run -p 8888:8888 -v ./bins:/data:Z mcg /data/firmware.bin`
  - Expected image size: ~1.5-2GB (document in README)

- **.containerignore:** build/, dist/, .gradle/, .git/, docs/, *.md

**Definition of Done:**

- [ ] mcg-headless.sh validates args, installs extension if needed, runs analyzeHeadless
- [ ] Script uses exec for proper signal forwarding
- [ ] Containerfile builds with `podman build`
- [ ] Container starts and serves MCP on exposed port
- [ ] GHIDRA_VERSION build arg allows version pinning
- [ ] No diagnostics errors

**Verify:**

- `bash mcg-headless.sh --help` shows usage
- `podman build -t mcg .` succeeds (if Podman available)
- `podman run -p 8888:8888 -v .:/data:Z mcg /data/test.bin` starts server

### Task 5: Update README with Headless Documentation

**Objective:** Add headless mode documentation to README including usage examples, Podman instructions, and architecture notes.

**Dependencies:** Tasks 1-4

**Files:**

- Modify: `README.md`

**Key Decisions / Notes:**

- Add "Headless Mode" section after "Usage" with:
  - Quick start: `./mcg-headless.sh /path/to/binary`
  - Podman: `podman run -p 8888:8888 -v ./bins:/data:Z mcg /data/firmware.bin`
  - Direct analyzeHeadless usage (for advanced users)
  - Runtime import: `importBinary` / `closeProgram` tool descriptions
  - Environment variables table (GHIDRA_MCP_PORT, GHIDRA_MCP_HOST, GHIDRA_INSTALL_DIR)
  - Expected container image size (~1.5-2GB)
- Add `importBinary` and `closeProgram` to the Tools table (Program Management category)
- Note that `importBinary` is headless-only

**Definition of Done:**

- [ ] Headless Mode section with quick start, Podman, and direct usage
- [ ] Tools table includes importBinary (headless-only) and closeProgram
- [ ] Expected container image size documented
- [ ] No broken markdown

**Verify:**

- Visual review

## Deferred Ideas

- Ghidra Server integration (shared project repository)
- Authentication for network-exposed MCP
- podman-compose for multi-binary analysis (one container per binary)
- Health check endpoint (HTTP GET /health) for container orchestration
- Graceful restart with program state preservation
- Standalone main() for environments without analyzeHeadless
