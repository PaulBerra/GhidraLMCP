# Automated Ghidra Release CI Implementation Plan

Created: 2026-03-25
Status: VERIFIED
Approved: Yes
Iterations: 1
Worktree: No
Type: Feature

## Summary

**Goal:** Enhance the existing Forgejo Actions workflow to create tagged releases with built extension ZIPs when new Ghidra versions are detected, replacing the current PR-based flow.
**Architecture:** Modify `.github/workflows/ghidra-update.yml` to replace the PR creation step with release creation using Forgejo's REST API. The workflow polls GitHub for new Ghidra releases (daily cron + manual trigger), builds the extension, creates a Forgejo release with the extension ZIP attached, then commits version tracking files directly to main.
**Tech Stack:** Forgejo Actions (GitHub Actions-compatible YAML), Forgejo REST API, shell scripting, `curl`

## Scope

### In Scope

- Modify existing workflow to create Forgejo releases instead of PRs
- Attach built extension ZIP to the release
- Tag releases matching Ghidra version (e.g., `v12.0.4`)
- Skip if release already exists for that Ghidra version (scheduled runs only)
- Add `force` input to `workflow_dispatch` to allow rebuilds of existing versions
- Increase polling frequency from weekly to daily
- Retain `workflow_dispatch` for manual builds against specific versions
- Commit all version-related files (`.ghidra-version`, gradle wrapper) to main after successful release
- Run unit tests before creating a release
- Read JDK version dynamically from Ghidra's `application.properties`
- Validate exactly one ZIP artifact exists before release creation
- Write job summary to `$GITHUB_STEP_SUMMARY`

### Out of Scope

- Multi-platform builds (single ubuntu-latest build is sufficient)
- Signing the extension ZIP
- Release notes generation beyond basic template
- Notification integrations (Slack, email, etc.)

## Approach

**Chosen:** Replace PR step with direct release via Forgejo REST API
**Why:** Simpler flow — no manual merge required. The build is already verified by the workflow, so gating behind a PR adds delay without adding safety. Forgejo's REST API for releases is well-documented and already used in the existing workflow for PR creation.
**Alternatives considered:**
- *Keep PR, add release after merge* — rejected because it requires a second workflow triggered on merge, adding complexity
- *Use `gh` CLI* — rejected because the repo is hosted on Forgejo, not GitHub. `gh` CLI only works with GitHub.

## Context for Implementer

> Write for an implementer who has never seen the codebase.

- **Patterns to follow:** The existing workflow at `.github/workflows/ghidra-update.yml` already handles Ghidra release detection, download, extraction, and extension building. The structure is well-organized with conditional steps using `if: steps.check-version.outputs.skip == 'false'`. The existing PR creation step (lines 117-153) demonstrates the Forgejo API pattern using `curl` with `FORGEJO_TOKEN`.
- **Conventions:** The workflow uses step IDs (`ghidra-release`, `check-version`) with outputs for cross-step communication. Version tracking via `.ghidra-version` file.
- **Key files:**
  - `.github/workflows/ghidra-update.yml` — the workflow to modify
  - `.ghidra-version` — tracks current Ghidra version (currently `12.0.3`)
  - `build.gradle` — builds extension via `./gradlew clean distributeExtension`, output in `dist/`
  - `extension.properties` — extension metadata
- **Gotchas:**
  - **Forgejo, not GitHub:** This repo is hosted on Forgejo. Do NOT use `gh` CLI — use Forgejo REST API with `curl` and `FORGEJO_TOKEN` secret. The API follows Gitea/Forgejo conventions.
  - The `dist/` directory is in `.gitignore` — the ZIP file path needs to be found dynamically since the filename includes the Ghidra version and build date.
  - The existing `check-version` step compares `.ghidra-version` to the latest Ghidra release — this is the skip/proceed gate. Additionally, check if a release tag already exists to avoid duplicates.
  - The workflow needs `contents: write` permission to push commits to main.
  - Forgejo release API: `POST /api/v1/repos/{owner}/{repo}/releases` to create release, then `POST /api/v1/repos/{owner}/{repo}/releases/{id}/assets` to upload the ZIP.
  - The existing workflow updates `.ghidra-version` AND copies gradle wrapper files before building. ALL of these files must be committed, not just `.ghidra-version`.
  - **Step ordering is critical:** Update files on disk → build → test → create release → commit+push. The version file must be written before build (build may reference it), but the git commit must happen only after successful release creation (prevents committed version bump if release fails).

## Assumptions

- Forgejo REST API is accessible at `${{ github.server_url }}/api/v1/...` — supported by existing workflow pattern (line 145) — Task 1 depends on this
- `FORGEJO_TOKEN` secret has permission to create releases and tags — needs to be verified by user — Task 1 depends on this
- The build output ZIP is the only file in `dist/` after `clean distributeExtension` — supported by build.gradle `clean { delete 'dist' }` and `.gitignore` pattern — Task 1 depends on this
- Ghidra release naming follows `Ghidra_<VERSION>_build` tag pattern — supported by existing workflow logic (line 28) — Task 1 depends on this
- The main branch does not have branch protection rules that would block pushes from the workflow, or the Forgejo token is allowed to push — Task 1 depends on this
- JDK version can be read from `${GHIDRA_INSTALL_DIR}/Ghidra/application.properties` key `application.java.compiler` — supported by build.gradle (lines 62-64) — Task 1 depends on this

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Ghidra release has breaking API changes that fail the build | Medium | Medium | Build step catches this — workflow fails before release creation. Manual `workflow_dispatch` with `force=true` can retry after code fixes. |
| Forgejo API rate limiting | Low | Low | Daily cron is well within limits. |
| Duplicate release creation if workflow runs twice | Low | Medium | Check for existing tag via `git ls-remote --tags origin v<VERSION>`. Scheduled runs skip if tag exists; manual `force=true` deletes and recreates. |
| `.ghidra-version` push conflicts if main has changed | Low | Low | Use `git pull --rebase` before pushing. Workflow operates on few files, conflicts are unlikely. |
| Release creation fails but version files already committed | Medium | High | **Commit+push happens ONLY after successful release creation.** Release step must fail the job on error. Use step condition `if: steps.create-release.outcome == 'success'` for the commit step. |
| Future Ghidra version requires different JDK | Low | Medium | Read JDK version dynamically from Ghidra's `application.properties` instead of hardcoding `21`. |

## Goal Verification

### Truths

1. When a new Ghidra version is released on GitHub, the workflow creates a corresponding release on this repo within 24 hours
2. The release is tagged with the Ghidra version (e.g., `v12.0.4`)
3. The release has the built extension ZIP attached as an asset
4. If a release for that Ghidra version already exists, scheduled runs skip without error
5. Manual `workflow_dispatch` with a specific version creates a release for that version
6. Manual `workflow_dispatch` with `force=true` can rebuild and re-release an existing version
7. Unit tests run and pass before a release is created
8. The `.ghidra-version` and gradle wrapper files are committed to main only after a successful release

### Artifacts

- `.github/workflows/ghidra-update.yml` — modified workflow with release creation

## Progress Tracking

- [x] Task 1: Modify workflow to create releases instead of PRs

**Total Tasks:** 1 | **Completed:** 1 | **Remaining:** 0

## Implementation Tasks

### Task 1: Modify Workflow to Create Releases

**Objective:** Rewrite the existing `ghidra-update.yml` workflow to replace the Forgejo PR creation step with Forgejo release creation using curl + REST API. Add duplicate release detection, unit test execution, force rebuild support, dynamic JDK version, and increase cron frequency.

**Dependencies:** None

**Files:**

- Modify: `.github/workflows/ghidra-update.yml`

**Key Decisions / Notes:**

- **Cron schedule:** Change from weekly (`'0 9 * * 1'`) to daily (`'0 9 * * *'`)
- **Permissions:** Add `permissions: contents: write` at the job level for pushing commits
- **workflow_dispatch inputs:**
  - `ghidra_version` (string, optional): Specific Ghidra version to build against
  - `force` (boolean, default false): When true, skip version check and existing tag check. If a release for this version already exists, delete it before creating a new one.
- **Duplicate check:** After detecting the Ghidra version, check if tag `v<VERSION>` already exists using `git ls-remote --tags origin "refs/tags/v${VERSION}"`. On scheduled runs: skip if tag exists. On manual `force=true`: delete existing release+tag first.
- **Dynamic JDK version:** After extracting Ghidra, read `application.java.compiler` from `${GHIDRA_INSTALL_DIR}/Ghidra/application.properties`. Use this value in `actions/setup-java`. Default to `21` if not found.
- **Unit tests:** Add a step after building to run `./gradlew test` before creating the release
- **Asset validation:** Before creating the release, validate exactly one ZIP exists:
  ```bash
  ZIP_FILE=$(ls dist/*.zip 2>/dev/null)
  [ -n "$ZIP_FILE" ] && [ $(echo "$ZIP_FILE" | wc -l) -eq 1 ] || { echo "Expected exactly one ZIP in dist/"; exit 1; }
  ```
- **Release creation (Forgejo REST API):**
  1. Create release: `POST /api/v1/repos/${REPO}/releases` with JSON body `{"tag_name": "v<VERSION>", "name": "GhidraMCP for Ghidra <VERSION>", "body": "...", "target_commitish": "main"}`
  2. Extract release ID from response
  3. Upload asset: `POST /api/v1/repos/${REPO}/releases/${RELEASE_ID}/assets?name=<filename>` with the ZIP file as form data
  4. Use `FORGEJO_TOKEN` for authentication (same as existing PR step)
- **Step ordering (critical):**
  1. Update `.ghidra-version` and gradle wrapper files on disk (existing steps)
  2. Build extension (`./gradlew clean distributeExtension`)
  3. Run tests (`./gradlew test`)
  4. Validate ZIP artifact
  5. Create Forgejo release + upload asset (step ID: `create-release`)
  6. Commit+push version files to main — **ONLY if `steps.create-release.outcome == 'success'`**
- **Commit scope:** Git add: `.ghidra-version`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` (matches existing workflow line 127)
- **Job summary:** Write outcome to `$GITHUB_STEP_SUMMARY` — "Skipped: already up to date (v12.0.4)" or "Released: v12.0.4 with asset <filename>"
- **Remove old PR code:** Remove the entire "Create Pull Request" step and its Forgejo API curl call for PR creation

**Definition of Done:**

- [ ] Workflow triggers daily at 9am UTC and on `workflow_dispatch`
- [ ] `workflow_dispatch` accepts `ghidra_version` (string) and `force` (boolean) inputs
- [ ] Scheduled runs skip if `.ghidra-version` matches latest Ghidra release
- [ ] Scheduled runs skip if release tag already exists
- [ ] `force=true` rebuilds and re-releases even if version matches or tag exists
- [ ] JDK version read dynamically from Ghidra's `application.properties`
- [ ] Unit tests run before release creation
- [ ] Exactly one ZIP validated before release creation
- [ ] Release created via Forgejo API with tag `v<VERSION>`, title, and description
- [ ] Built extension ZIP uploaded as release asset via Forgejo API
- [ ] `.ghidra-version` and gradle wrapper files committed to main only after successful release
- [ ] No `gh` CLI usage (Forgejo-only)
- [ ] Job summary written to `$GITHUB_STEP_SUMMARY`
- [ ] YAML is valid
- [ ] No diagnostics errors

**Verify:**

- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ghidra-update.yml'))"`
- Manual review of workflow structure
- Note: release creation, asset upload, and commit+push can only be fully verified by running the workflow on Forgejo

## Deferred Ideas

- Multi-architecture builds (e.g., building on macOS and Windows too)
- Automatic release notes from git log between versions
- Slack/Discord notification on new release
- Release checksum (SHA256) published alongside the ZIP
