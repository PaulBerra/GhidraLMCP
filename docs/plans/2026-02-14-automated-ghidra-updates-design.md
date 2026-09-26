# Design: Automated Ghidra Version Updates

## Problem

When Ghidra releases a new version, updating ghidra-mcp requires several manual steps: downloading the release, updating Gradle wrapper version, adjusting Java toolchain settings, cleaning old build artifacts, and verifying the build. Since ghidra-mcp merely exposes stable Ghidra APIs via MCP with no custom logic beyond the wrappers, these updates are mechanical and should be automated.

## Design

### 1. Make build.gradle version-agnostic

Remove all hardcoded version information from `build.gradle` so that `GHIDRA_INSTALL_DIR` is the single source of truth.

**Remove the Java toolchain block.** The current `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` hardcodes a JDK version that varies by environment. Delete it entirely. Keep only `options.release` to control bytecode compatibility.

**Read the Java release target dynamically.** Instead of hardcoding `options.release = 21`, read `application.java.compiler` from `${ghidraInstallDir}/Ghidra/application.properties`. The build always matches what Ghidra expects, with zero manual updates.

**Result:** `build.gradle` has zero version-specific hardcoding. The only input is `GHIDRA_INSTALL_DIR`.

### 2. Track current Ghidra version in `.ghidra-version`

Add a single-line file `.ghidra-version` containing the current Ghidra version (e.g. `12.0.3`). This is the only piece of version state in the repo. It exists so the CI workflow can compare "what we have" vs "what's latest" without building.

### 3. Copy Gradle wrapper from Ghidra itself

Ghidra ships its own `gradle-wrapper.properties` at `support/gradle/gradle-wrapper.properties` with the exact Gradle version it was built with. Instead of guessing or maintaining a mapping table, copy Ghidra's own wrapper properties into our project. This guarantees compatibility.

### 4. CI workflow: `.github/workflows/ghidra-update.yml`

**Triggers:**
- `schedule: cron '0 9 * * 1'` (weekly Monday morning)
- `workflow_dispatch` with optional `ghidra_version` input for targeting a specific release

**Job steps:**

1. **Detect latest Ghidra release.** Query NationalSecurityAgency/ghidra GitHub releases API. Extract version tag and download URL.
2. **Compare with current.** Read `.ghidra-version` from the repo. If latest == current, exit early.
3. **Download and extract Ghidra.** Fetch the release zip (linux64 — platform doesn't matter for compilation, we only need JARs and build scripts). Extract to get `GHIDRA_INSTALL_DIR`.
4. **Update project files:**
   - Write new version to `.ghidra-version`
   - Copy Ghidra's `support/gradle/gradle-wrapper.properties` into `gradle/wrapper/`
   - Regenerate wrapper JAR via `gradle wrapper`
5. **Build.** Run `GHIDRA_INSTALL_DIR=... ./gradlew clean distributeExtension`. If this fails, the workflow fails and notifies.
6. **Open PR.** Commit changed files to a `ghidra-update/<version>` branch and open a pull request with a summary of what changed.

### 5. Cleanup

- **Delete `ghidra_docs` symlink** from the repo. It's a local convenience that creates maintenance noise.
- **Add `ghidra_docs` to `.gitignore`** so it doesn't reappear.
- **`dist/` stays gitignored.** The CI build proves compilation works; artifacts are not committed.

## File changes

| File | Action |
|------|--------|
| `.github/workflows/ghidra-update.yml` | Create |
| `.ghidra-version` | Create |
| `build.gradle` | Modify: remove toolchain block, dynamic `options.release` |
| `.gitignore` | Modify: add `ghidra_docs` |
| `ghidra_docs` | Delete |

## What stays unchanged

- All Java source files (`GhidraMCPPlugin.java`, `GhidraService.java`, `McpServerApplication.java`)
- `extension.properties` (already uses template variables)
- `application.yml` (independent of Ghidra version)
- Spring Boot / Spring AI dependencies (independent of Ghidra version)
