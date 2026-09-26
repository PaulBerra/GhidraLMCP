# Expanded MCP Tools Implementation Plan

Created: 2026-03-26
Status: VERIFIED
Approved: Yes
Iterations: 1
Worktree: No
Type: Feature

## Summary

**Goal:** Add 6 feature areas (20+ new tools) to McG's GhidraService: decompiler resource cleanup, localhost binding, struct/enum management, raw memory operations, batch operations, and program rebase.
**Architecture:** All new tools follow the existing `@Tool` pattern in `GhidraService.java`. Write operations use `runTransaction()`. A `resolveDataType()` helper maps string type names to Ghidra `DataType` objects (throws on unknown types). Localhost binding is handled via `server.address` Spring Boot property with `GHIDRA_MCP_HOST` env var override. MCP config file generation added to `McpServerApplication`.
**Tech Stack:** Java, Spring AI MCP, Ghidra API (DataTypeManager, Memory, DecompInterface, FlatProgramAPI)

## Scope

### In Scope

- Clean up decompiler resource management (dispose, explicit options)
- Bind server to 127.0.0.1 by default with GHIDRA_MCP_HOST override
- Generate `~/.ghidra-mcp/mcp.json` and `mcp.<port>.json` config files with correct host/port
- Struct management: createStruct, addStructField, getStruct, listStructs, deleteStruct
- Enum management: createEnum, addEnumValue, getEnum
- Type operations: applyStructAtAddress, listTypes, resolveDataType helper
- Memory: readBytes (max 4096), searchBytes (max 100 results), getDataAtAddress, defineData, clearData
- Batch operations: batchRenameFunctions, batchSetComments (parallel list params, no delimiter issues)
- Program rebase: rebaseProgram (with alignment warning)
- Update README with new tools table
- Unit tests for resolveHost()

### Out of Scope

- Headless mode (deferred — requires architectural changes to run outside GUI plugin)
- Async decompilation (deferred — complex task management over SSE)
- BSim integration (deferred — requires BSim database setup)
- writeBytes (raw memory patching — higher risk than data definitions, defer to future)

## Approach

**Chosen:** Add all tools to GhidraService.java following existing patterns
**Why:** Single file keeps tool discovery simple. Spring AI's `MethodToolCallbackProvider` scans one service class. File will approach ~800 lines — acceptable, split in follow-up if needed.
**Alternatives considered:**
- *Split into domain-specific services* — rejected: requires multiple `ToolCallbackProvider` beans in `McpServerApplication`, adds wiring complexity without clear benefit at this scale

## Context for Implementer

> Write for an implementer who has never seen the codebase.

- **Patterns to follow:**
  - Read-only tools: direct method call, return result. Example: `listFunctions()` at `GhidraService.java:120-127`
  - Write tools: wrap in `runTransaction()`. Example: `renameFunction()` at `GhidraService.java:146-157`
  - Address parameters: accept as `String`, convert with `requireAddress()` at `GhidraService.java:66-72`
  - Error handling: throw `IllegalArgumentException` for bad input, `RuntimeException` for internal errors — **never silently fall back to a default type**
- **Conventions:**
  - Tool method names: camelCase verbs (`listFunctions`, `renameFunction`, `createStruct`)
  - `@Tool(description = "...")` annotation on every public tool method
  - Parameters are simple types (String, int, List<String>) — no complex objects (MCP limitation)
  - Batch operations use parallel lists (not delimited strings) to avoid issues with colons in C++ names
- **Key files:**
  - `src/main/java/org/suidpit/GhidraService.java` — all MCP tools (primary file to modify)
  - `src/main/java/org/suidpit/McpServerApplication.java` — server lifecycle, host/port binding, config file generation
  - `src/main/java/org/suidpit/PortResolver.java` — port and host resolution
  - `src/main/resources/application.yml` — Spring Boot config
  - `README.md` — tools table and config docs
- **Gotchas:**
  - All Ghidra program modifications MUST run on the Swing EDT inside a transaction — use `runTransaction()`
  - `DataTypeManager.getDataType("/typename")` uses forward-slash paths, not backslash
  - `DecompInterface` must call `openProgram()` before decompiling and should call `dispose()` when done
  - Struct fields: use `struct.add(dataType, fieldSize, fieldName, comment)` for sequential fields. Use `-1` for `fieldSize` to use the type's default size (never `0` — ambiguous with zero-length).
  - `Program.setImageBase()` requires the address to be in the program's address space
  - `Memory.getBytes()` reads from the program's virtual address space, not file offsets
  - Batch operations should run in a single transaction for atomicity
  - **DAT_* references** are data labels for undefined data at addresses. They are resolved by creating proper data definitions at those addresses (using `defineData`), not by decompiler options. The decompiler simplification style controls expression folding, not data label resolution.
  - The current `McpServerApplication.java` writes `port.<pid>` files. This plan replaces that with `mcp.<port>.json` config files (port-based, not PID-based).

## Assumptions

- Spring Boot `server.address` property controls the bind address for Jetty — supported by Spring Boot docs — Task 2 depends on this
- `StructureDataType(CategoryPath, String, int)` creates a new struct in Ghidra's data type manager — supported by Ghidra API and upstream PR #110 — Task 3 depends on this
- `Program.getMemory().getBytes(Address, byte[])` reads bytes from the program's memory — standard Ghidra Memory API — Task 4 depends on this
- `Program.setImageBase(Address, boolean)` rebases the program — standard Ghidra API — Task 6 depends on this
- Ghidra tools (struct, enum, memory) are testable only with Ghidra runtime — unit tests are limited to non-Ghidra components (PortResolver) — All tasks depend on this

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `resolveDataType()` receives unknown type name | Medium | Medium | Throw `IllegalArgumentException` with error listing available types via `listTypes()`. Never silently substitute. |
| readBytes on unmapped memory addresses | Medium | Low | Catch `MemoryAccessException`, return clear error message with valid address ranges |
| readBytes with excessive length | Low | Medium | Cap at 4096 bytes. Reject larger requests with clear error. |
| searchBytes returns too many results on short patterns | Medium | Medium | Cap at 100 results by default. Accept optional `maxResults` parameter. |
| Batch operations partially fail (some functions not found) | Medium | Low | Run in single transaction. Collect per-item errors. Return summary: "Renamed 8/10. Errors: ..." |
| rebaseProgram with non-aligned address | Low | Medium | Log warning in tool description about alignment. Let Ghidra API handle validation. |
| Binding to 0.0.0.0 exposes unauthenticated server | Medium | High | Log warning at startup when host is not 127.0.0.1. Document in README. |
| GhidraService.java exceeds 800 lines | High | Low | Accept as known tradeoff. Split to domain-specific services in follow-up if needed. |

## Goal Verification

### Truths

1. `DecompInterface` is properly disposed after decompilation (no resource leaks)
2. Server binds to 127.0.0.1 by default; setting `GHIDRA_MCP_HOST=0.0.0.0` makes it accessible from other machines
3. MCP config files at `~/.ghidra-mcp/mcp.json` reflect the bound host address and port
4. An LLM can create a struct, add fields, read it back, and apply it at an address through MCP tools
5. An LLM can read hex bytes from any address and search for byte patterns in the binary
6. An LLM can rename 10 functions in a single tool call via batchRenameFunctions
7. An LLM can rebase a program to match a runtime address from a debugger
8. Unknown type names in resolveDataType throw an error (never silently substitute)

### Artifacts

- `GhidraService.java` — all new tool methods + resolveDataType helper
- `McpServerApplication.java` — host binding, config file generation
- `PortResolver.java` — host resolution
- `PortResolutionTest.java` — resolveHost tests
- `application.yml` — address config
- `README.md` — updated tools table and config docs

## Progress Tracking

- [x] Task 1: Decompiler cleanup and resource management
- [x] Task 2: Localhost binding, config files, and host override
- [x] Task 3: Struct and enum management tools
- [x] Task 4: Raw memory operations
- [x] Task 5: Batch operations
- [x] Task 6: Program rebase
- [x] Task 7: Update README

**Total Tasks:** 7 | **Completed:** 7 | **Remaining:** 0

## Implementation Tasks

### Task 1: Decompiler Cleanup and Resource Management

**Objective:** Fix `decompileFunctionByName` to properly manage `DecompInterface` resources and apply explicit decompiler options.

**Dependencies:** None

**Files:**

- Modify: `src/main/java/org/suidpit/GhidraService.java`

**Key Decisions / Notes:**

- Modify the existing `decompileFunctionByName()` method (line 134-144)
- Add proper resource management:
  ```java
  var decompInterface = new DecompInterface();
  try {
      decompInterface.setOptions(new DecompileOptions());
      decompInterface.openProgram(getProgram());
      var decompiled = decompInterface.decompileFunction(function, 30, null);
      // ... return result
  } finally {
      decompInterface.dispose();
  }
  ```
- Import `ghidra.app.decompiler.DecompileOptions`
- **Note:** This does NOT resolve `DAT_*` references to string values. DAT_* resolution requires creating typed data definitions at the referenced addresses, which is what Task 4's `defineData` tool enables. The decompiler options control expression simplification, not data label resolution.

**Definition of Done:**

- [ ] `DecompInterface` is created, used, and disposed in try-finally
- [ ] `DecompileOptions` explicitly set
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 2: Localhost Binding, Config Files, and Host Override

**Objective:** Bind the server to `127.0.0.1` by default. Add `GHIDRA_MCP_HOST` env var override. Generate `mcp.json` and `mcp.<port>.json` config files with correct host/port. Replace the existing PID-based `port.<pid>` files with port-based `mcp.<port>.json` files. Add unit tests for `resolveHost()`.

**Dependencies:** None

**Files:**

- Modify: `src/main/java/org/suidpit/McpServerApplication.java`
- Modify: `src/main/java/org/suidpit/PortResolver.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/org/suidpit/PortResolutionTest.java`

**Key Decisions / Notes:**

- Add `resolveHost()` to `PortResolver` — checks `GHIDRA_MCP_HOST` env var (injectable for testing like `resolvePort`), defaults to `127.0.0.1`. Package-private overload with `Function<String,String>` for test injection.
- In `McpServerApplication.startServer()`, add `--server.address=<host>` to the Spring Boot args
- Store active host in `private static volatile String activeHost`
- Update log message at line 58 to use resolved host instead of hardcoded `localhost`
- Replace `writePortFile()` to generate MCP config JSON files:
  - `~/.ghidra-mcp/mcp.<port>.json` — per-instance config keyed by port
  - `~/.ghidra-mcp/mcp.json` — latest instance (symlink target)
  - JSON content: `{"mcpServers":{"McG":{"type":"sse","url":"http://<host>:<port>/sse"}}}`
  - Use `127.0.0.1` → `localhost` in URLs for readability. Use actual IP for non-loopback addresses.
- Replace `cleanStalePortFiles()` to check port-based staleness (`isPortAvailable(port)` instead of PID)
- Replace `deletePortFile()` to remove `mcp.<port>.json` (and `mcp.json` when last instance)
- Log warning at startup when host is not `127.0.0.1`: "Warning: MCP server is accessible from the network. No authentication is configured."
- In `application.yml`: add `server.address: 127.0.0.1`
- Add tests: `test_resolveHost_defaultIsLocalhost`, `test_resolveHost_envVarOverrides`

**Definition of Done:**

- [ ] `resolveHost()` reads `GHIDRA_MCP_HOST` env var, defaults to `127.0.0.1`
- [ ] Server passes `--server.address` to Spring Boot
- [ ] `mcp.json` and `mcp.<port>.json` generated with correct host/port
- [ ] Stale config cleanup uses port availability check
- [ ] Log message uses resolved host
- [ ] Security warning logged for non-localhost binding
- [ ] `application.yml` has `server.address: 127.0.0.1`
- [ ] Unit tests for `resolveHost()` pass
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 3: Struct and Enum Management Tools

**Objective:** Add 10 tools for creating, inspecting, and applying data types: createStruct, addStructField, getStruct, listStructs, deleteStruct, createEnum, addEnumValue, getEnum, applyStructAtAddress, listTypes. Add a `resolveDataType()` helper.

**Dependencies:** None

**Files:**

- Modify: `src/main/java/org/suidpit/GhidraService.java`

**Key Decisions / Notes:**

- Add a private `resolveDataType(String typeName)` helper method:
  - Handles built-in types (int, char, void, bool, short, long, uint, uchar, ushort, etc.)
  - Handles pointer syntax (`char *` → `PointerDataType`)
  - Handles Windows types (DWORD, WORD, BYTE, QWORD)
  - Exact path lookup (`/typeName`)
  - Search all categories by name (case-insensitive fallback)
  - **Throws `IllegalArgumentException`** for unknown types — never silently substitutes
- **createStruct(String name, int size):** Create `StructureDataType` with CategoryPath.ROOT. size 0 = auto-sized.
- **addStructField(String structName, String fieldName, String fieldType, int fieldSize, String comment):** Find struct, resolve type, add field. `fieldSize` of `-1` uses the type's default size.
- **getStruct(String structName):** Return struct info: name, size, alignment, and all fields (offset | name | type | size)
- **listStructs():** List all structure data types by name
- **deleteStruct(String structName):** Remove struct from data type manager
- **createEnum(String name, int size):** Create `EnumDataType` (size in bytes: 1, 2, or 4)
- **addEnumValue(String enumName, String valueName, long value):** Add named value to enum
- **getEnum(String enumName):** Return enum info: name, size, and all values (name | value)
- **applyStructAtAddress(String structName, String address):** Apply struct at address via `Listing.createData()`
- **listTypes(String category):** List data types, optionally filtered by category
- All write operations use `runTransaction()`
- Required imports: `ghidra.program.model.data.*` (StructureDataType, EnumDataType, DataTypeManager, CategoryPath, PointerDataType, etc.)

**Definition of Done:**

- [ ] All 10 struct/enum tools implemented with `@Tool` annotations
- [ ] `resolveDataType()` handles built-in types, pointers, and name lookups
- [ ] Unknown types throw `IllegalArgumentException` (no silent fallback)
- [ ] Write operations use `runTransaction()`
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 4: Raw Memory Operations

**Objective:** Add 5 tools for reading, searching, and managing data at memory addresses: readBytes, searchBytes, getDataAtAddress, defineData, clearData.

**Dependencies:** Task 3 (defineData needs `resolveDataType()` helper; other 4 tools are independent)

**Files:**

- Modify: `src/main/java/org/suidpit/GhidraService.java`

**Key Decisions / Notes:**

- **readBytes(String address, int length):** Read `length` bytes from address. Max 4096 bytes — reject larger with error. Default 256. Return as hex string with ASCII sidebar (like `xxd`). Use `Program.getMemory().getBytes(addr, buffer)`. Catch `MemoryAccessException` for unmapped regions.
- **searchBytes(String hexPattern, int maxResults):** Search for byte pattern (e.g., "48 8b 05") across all memory blocks. Return list of matching addresses, capped at `maxResults` (default 100). Parse hex string to byte array. Use `Memory.findBytes()` with block iteration.
- **getDataAtAddress(String address):** Return the data item at an address: type, value, size, label. Use `Listing.getDataAt()`. Return "undefined" if no data defined.
- **defineData(String address, String dataType, String label):** Create typed data definition. Resolve type via `resolveDataType()`. Optionally create label via `SymbolTable.createLabel()`. Requires transaction.
- **clearData(String address, int size):** Clear/undefine data in a range. Use `Listing.clearCodeUnits()`. Requires transaction.
- Required imports: `ghidra.program.model.mem.*` (Memory, MemoryAccessException, MemoryBlock)

**Definition of Done:**

- [ ] All 5 memory tools implemented with `@Tool` annotations
- [ ] readBytes capped at 4096 bytes with formatted hex dump
- [ ] searchBytes capped at maxResults (default 100)
- [ ] defineData and clearData use `runTransaction()`
- [ ] MemoryAccessException caught with clear error messages
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 5: Batch Operations

**Objective:** Add 2 batch tools for bulk renaming and commenting: batchRenameFunctions, batchSetComments.

**Dependencies:** None

**Files:**

- Modify: `src/main/java/org/suidpit/GhidraService.java`

**Key Decisions / Notes:**

- **batchRenameFunctions(List\<String\> oldNames, List\<String\> newNames):** Accept two parallel lists. Lists must be same length — throw if not. Run all renames in a single transaction. Return per-item results summary: "Renamed 8/10 functions. Errors: func_x (not found), func_y (not found)".
- **batchSetComments(List\<String\> functionNames, List\<String\> comments):** Accept two parallel lists. Set comment on each function. Single transaction. Return per-item results.
- Both wrap in a single `runTransaction()` for atomicity
- Collect errors per item but don't abort on individual failures
- **Parallel lists** avoid delimiter issues with C++ names containing colons (e.g., `std::string::c_str`)

**Definition of Done:**

- [ ] Both batch tools implemented with `@Tool` annotations
- [ ] Use parallel lists (not delimiter-separated strings)
- [ ] Lists validated to be same length
- [ ] Single transaction wraps all operations
- [ ] Per-item error reporting
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 6: Program Rebase

**Objective:** Add a rebaseProgram tool to change the program's image base address.

**Dependencies:** None

**Files:**

- Modify: `src/main/java/org/suidpit/GhidraService.java`

**Key Decisions / Notes:**

- **rebaseProgram(String newBaseAddress):** Change image base using `Program.setImageBase(Address, boolean)`. Set commit to `true`.
- Must run in a transaction
- Tool description: "Changes the program's image base address. This shifts all addresses and is useful for aligning static analysis with runtime addresses from a debugger. Warning: may invalidate existing bookmarks. Address should typically be page-aligned (0x1000 boundary)."
- Return old base and new base for confirmation: "Rebased from 0x400000 to 0x7ff000000"

**Definition of Done:**

- [ ] rebaseProgram tool implemented with `@Tool` annotation
- [ ] Uses `runTransaction()` and `Program.setImageBase()`
- [ ] Returns old and new base addresses
- [ ] Tool description warns about side effects and alignment
- [ ] All existing tests pass
- [ ] No diagnostics errors

**Verify:**

- `./gradlew test`

### Task 7: Update README

**Objective:** Update the tools table in README.md with all new tools organized by category. Document GHIDRA_MCP_HOST and security implications.

**Dependencies:** Tasks 1-6

**Files:**

- Modify: `README.md`

**Key Decisions / Notes:**

- Organize tools table by category: Program Management, Analysis, Renaming, Data Types, Memory, Batch, References
- Add GHIDRA_MCP_HOST to Configuration section with security warning about non-localhost binding
- Document `~/.ghidra-mcp/mcp.json` config file generation and symlink usage
- Note that DAT_* references can be resolved by using `defineData` to create typed data definitions at the referenced addresses

**Definition of Done:**

- [ ] Tools table lists all tools (existing + new) organized by category
- [ ] GHIDRA_MCP_HOST documented with security warning
- [ ] Config file generation documented
- [ ] No broken markdown
- [ ] No diagnostics errors

**Verify:**

- Visual review of README

## Deferred Ideas

- Headless mode — run McG without Ghidra GUI via GhidraHeadlessAnalyzer or standalone Spring Boot with Ghidra libraries on classpath
- Async decompilation with task polling — for large functions that take minutes
- BSim integration — binary similarity search across databases
- writeBytes — raw memory patching (higher risk, needs careful safety controls)
- Function signature editing — change parameter types and return types
- Cross-reference graph — return full call graph as structured data
- Split GhidraService into domain-specific services if file exceeds 800 lines
