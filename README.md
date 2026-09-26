# McG - MCP Server for Ghidra

McG is a [Ghidra](https://ghidra-sre.org/) extension that embeds a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server, exposing reverse engineering capabilities to LLM agents over SSE transport.

This project is a fork of [suidpit/ghidra-mcp](https://github.com/suidpit/ghidra-mcp) with additional features including multi-instance support, robust port management, and automated CI/CD for new Ghidra releases.

## Tools

### Program Management
| Tool | Description |
|------|-------------|
| `listOpenPrograms` | List every open program in every CodeBrowser window (including background tabs), marking each window's current tab and the tool-wide active program |
| `selectProgram` | Switch which open binary to operate on |
| `listProjectFiles` | List files in the active Ghidra project (recursively), to discover what can be opened |
| `openProgram` | Open a program already in the project by path and make it active — no manual tab switching |
| `importProgram` | Import a binary file from disk into the project and open it — no manual File > Import |
| `closeProgram` | Close an open program by name, optionally saving changes first |
| `rebaseProgram` | Change the program's image base address to align with runtime addresses |

`openProgram`, `importProgram`, and `closeProgram` let an agent manage which binaries are open entirely on its own — it can list what's available, open the one it needs, and close it when done, without asking the user to switch files in the Ghidra GUI. These three are GUI-only (not available under `analyzeHeadless`, which is bound to a single program for the life of the process).

### Analysis
| Tool | Description |
|------|-------------|
| `listFunctions` | List functions in the current program, paginated (offset/limit) to avoid flooding the context on large binaries |
| `getFunctionAddressByName` | Get function entry point address |
| `decompileFunctionByName` | Decompile a function to C pseudocode |
| `getFunctionCallers` | Get functions that call a given function |
| `searchForStrings` | Search for strings in program memory (min 5 chars) |

### Renaming
| Tool | Description |
|------|-------------|
| `renameFunction` | Rename a function |
| `renameLocalVariableInFunction` | Rename a local variable within a function |
| `batchRenameFunctions` | Rename multiple functions in a single transaction |

### Comments
| Tool | Description |
|------|-------------|
| `addCommentToFunction` | Add a comment to a function |
| `batchSetComments` | Set comments on multiple functions in a single transaction |

### Data Types
| Tool | Description |
|------|-------------|
| `createStruct` | Create a new structure data type |
| `addStructField` | Add a field to a structure |
| `getStruct` | Get structure details including all fields |
| `listStructs` | List all structure data types |
| `deleteStruct` | Delete a structure data type |
| `createEnum` | Create a new enum data type |
| `addEnumValue` | Add a named value to an enum |
| `getEnum` | Get enum details including all values |
| `applyStructAtAddress` | Apply a structure type at a memory address |
| `listTypes` | List all data types, optionally filtered by category |

### Memory
| Tool | Description |
|------|-------------|
| `readBytes` | Read raw bytes from memory as hex dump (max 4KB) |
| `searchBytes` | Search for byte patterns in memory (max 100 results) |
| `getDataAtAddress` | Get information about data defined at an address |
| `defineData` | Create a typed data definition at an address |
| `clearData` | Clear/undefine data in a range |

### References
| Tool | Description |
|------|-------------|
| `getReferencesToAddress` | Get cross-references to an address |
| `getReferencesFromAddress` | Get cross-references from an address |

Multiple CodeBrowser windows are supported. The server automatically selects the first window with an open binary, or you can use `selectProgram` to target a specific one.

## Installation

Download the extension ZIP from the [releases](https://github.com/brianredbeard/ghidra-mcp/releases) page matching your Ghidra version.

In Ghidra: **File > Install Extensions > "+"** and select the ZIP. Then open the Code Browser, go to **File > Configure > Developer** and enable **GhidraMCPPlugin**.

The MCP server starts automatically. It defaults to port 8888 and auto-selects the next available port if occupied. Check the Ghidra console for the actual SSE URL.

## Build

Requires `GHIDRA_INSTALL_DIR` pointing to your Ghidra installation. The Java release target and Gradle version are read from Ghidra automatically.

```bash
export GHIDRA_INSTALL_DIR=/path/to/ghidra
./gradlew clean distributeExtension
```

The extension ZIP will be in `dist/`.

To run unit tests (no Ghidra installation required):

```bash
./gradlew test
```

## Usage

Start Ghidra and open a binary in the Code Browser. The server generates a ready-to-use MCP client config at `~/.ghidra-mcp/mcp.json` with the correct port.

Symlink it into your project once — it updates automatically on every server start:

```bash
ln -sf ~/.ghidra-mcp/mcp.json .mcp.json
```

Or add the config manually:

```json
{
  "mcpServers": {
    "McG": {
      "type": "sse",
      "url": "http://localhost:8888/sse"
    }
  }
}
```

When running multiple Ghidra instances, per-instance configs are at `~/.ghidra-mcp/mcp.<port>.json`.

## Headless Mode

McG can run without the Ghidra GUI using `analyzeHeadless`. This is useful for server deployments, CI pipelines, and container-based analysis.

### Quick Start

```bash
export GHIDRA_INSTALL_DIR=/path/to/ghidra

# Install the extension (first time only)
unzip dist/ghidra_*_ghidra-mcp.zip -d "$GHIDRA_INSTALL_DIR/Ghidra/Extensions/"

# Run headless analysis + start MCP server
"$GHIDRA_INSTALL_DIR/support/analyzeHeadless" /tmp/mcg-project McG \
  -import /path/to/binary \
  -postScript McgServer.java
```

The server imports the binary, runs Ghidra's full auto-analysis (including DWARF, function detection, and decompiler analysis), then starts the MCP server. It blocks until you send `SIGTERM` (Ctrl+C).

### Connecting

The headless server generates the same `~/.ghidra-mcp/mcp.json` config file as GUI mode. Symlink it into your project:

```bash
ln -sf ~/.ghidra-mcp/mcp.json .mcp.json
```

For remote access (e.g., running headless on a server), bind to all interfaces:

```bash
export GHIDRA_MCP_HOST=0.0.0.0
```

### Container

Build and run with Podman (or Docker):

```bash
podman build -t mcg .
podman run -p 8888:8888 -v ./bins:/data:Z mcg /data/firmware.bin
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GHIDRA_INSTALL_DIR` | (required) | Path to Ghidra installation |
| `GHIDRA_MCP_PORT` | `8888` | Server port (scans +9 if occupied) |
| `GHIDRA_MCP_HOST` | `127.0.0.1` | Bind address (`0.0.0.0` for network access) |

## Configuration

### Port

The server defaults to port 8888 and scans up to 10 ports if occupied. To override, set the `GHIDRA_MCP_PORT` environment variable before launching Ghidra:

```bash
export GHIDRA_MCP_PORT=9999
ghidraRun
```

Explicit overrides fail if the port is unavailable (no scanning), ensuring your client config matches.

### Host Address

The server binds to `127.0.0.1` (localhost only) by default for security. To allow network access (e.g., for headless mode or remote clients), set:

```bash
export GHIDRA_MCP_HOST=0.0.0.0
ghidraRun
```

⚠️ **Security Warning:** Binding to non-localhost addresses exposes the MCP server to unauthenticated network access. Anyone on the network can invoke tools that modify your Ghidra program. Only use `0.0.0.0` in trusted network environments.

### Discovery

The server writes to `~/.ghidra-mcp/` on startup:

| File | Purpose |
|------|---------|
| `mcp.json` | MCP client config for the latest instance (symlink target) |
| `mcp.<port>.json` | Per-instance config keyed by port (e.g., `mcp.8888.json`) |

Config files reflect the bound host address and are updated automatically on every server start. Stale config files are cleaned by checking if the port is still in use.

### Resolving DAT_* References

The decompiler shows `DAT_*` references for undefined data at addresses. To resolve these to actual values:

1. Use `getDataAtAddress` to inspect what's at the address
2. Use `defineData` to create a typed data definition (e.g., `"string"`, `"dword"`)
3. Decompile again — the reference now shows the typed value

Example: `DAT_00402000` → define as `"string"` → decompiler shows `"/tmp/log.txt"`

## CI/CD

A Forgejo Actions workflow monitors [NationalSecurityAgency/ghidra](https://github.com/NationalSecurityAgency/ghidra) releases daily. When a new version is detected, it:

1. Downloads and extracts the Ghidra release
2. Builds the extension against it
3. Runs the test suite
4. Creates a tagged release with the extension ZIP attached
5. Commits updated version tracking files

Manual builds can be triggered via `workflow_dispatch` with an optional Ghidra version and force-rebuild flag.

## Attribution

This project is based on [ghidra-mcp](https://github.com/suidpit/ghidra-mcp) by [suidpit](https://github.com/suidpit), originally created in March 2025. The original work demonstrated embedding a Spring Boot MCP server inside a Ghidra plugin — a creative approach to bridging LLM agents with reverse engineering tools.

## License

[Apache License 2.0](LICENSE)
