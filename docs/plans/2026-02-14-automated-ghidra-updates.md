# Automated Ghidra Version Updates — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make ghidra-mcp self-maintaining so new Ghidra releases are detected, built against, and proposed as PRs automatically.

**Architecture:** Remove all hardcoded version info from `build.gradle` (single source of truth is `GHIDRA_INSTALL_DIR`). A Forgejo Actions workflow polls GitHub for new Ghidra releases, downloads them, updates build config, verifies compilation, and opens a PR.

**Tech Stack:** Gradle (Groovy DSL), Forgejo Actions (GitHub Actions-compatible YAML), shell scripting, GitHub REST API.

**Design doc:** `docs/plans/2026-02-14-automated-ghidra-updates-design.md`

---

### Task 1: Create `.ghidra-version` tracking file

**Files:**
- Create: `.ghidra-version`

**Step 1: Create the file**

```
12.0.3
```

Single line, no trailing content. This is the version marker the CI workflow compares against.

**Step 2: Verify**

Run: `cat .ghidra-version`
Expected: `12.0.3`

**Step 3: Commit**

```bash
git add .ghidra-version
git commit -m "chore: add .ghidra-version tracking file (12.0.3)"
```

---

### Task 2: Make build.gradle version-agnostic

**Files:**
- Modify: `build.gradle:33-58`

**Step 1: Remove the Java toolchain block and add dynamic `options.release`**

The current `build.gradle` lines 50-58 are:

```groovy
java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

tasks.withType(JavaCompile) {
	options.release = 21
}
```

Replace with:

```groovy
// Read Java release target from Ghidra's application.properties
// so we never hardcode a version that drifts.
def ghidraProps = new Properties()
new File(ghidraInstallDir, "Ghidra/application.properties").withInputStream { ghidraProps.load(it) }
def javaRelease = ghidraProps.getProperty('application.java.compiler', '21') as int

tasks.withType(JavaCompile) {
	options.release = javaRelease
}
```

This removes the `java { toolchain {} }` block entirely. The JDK on PATH is used directly. The `--release` flag value is read from Ghidra's own `application.properties`, so it always matches.

**Step 2: Verify the build**

Run: `GHIDRA_INSTALL_DIR=/Users/bharrington/Downloads/ghidra_12.0.3_PUBLIC ./gradlew clean distributeExtension`
Expected: `BUILD SUCCESSFUL` and a zip in `dist/` named `ghidra_12.0.3_PUBLIC_*_ghidra-mcp.zip`

**Step 3: Commit**

```bash
git add build.gradle
git commit -m "chore: make build.gradle version-agnostic

Remove hardcoded Java toolchain block. Read options.release
dynamically from Ghidra's application.properties so the build
adapts automatically to any Ghidra version."
```

---

### Task 3: Clean up ghidra_docs symlink and .gitignore

**Files:**
- Delete: `ghidra_docs` (symlink)
- Modify: `.gitignore`

**Step 1: Delete the symlink**

```bash
rm ghidra_docs
```

Note: if `ghidra_docs` is untracked (check `git status`), just delete it. If tracked, use `git rm ghidra_docs`.

**Step 2: Add `ghidra_docs` to `.gitignore`**

Append to the `# Misc` section of `.gitignore`:

```
ghidra_docs
```

This prevents anyone from accidentally committing a local symlink.

**Step 3: Verify**

Run: `git status`
Expected: `.gitignore` shows as modified. `ghidra_docs` does NOT appear as untracked.

**Step 4: Commit**

```bash
git add .gitignore
git commit -m "chore: remove ghidra_docs symlink, add to .gitignore"
```

---

### Task 4: Create the CI workflow

**Files:**
- Create: `.github/workflows/ghidra-update.yml`

**Step 1: Create workflow directory**

```bash
mkdir -p .github/workflows
```

**Step 2: Write the workflow file**

```yaml
name: Check for Ghidra Updates

on:
  schedule:
    # Weekly Monday 9am UTC
    - cron: '0 9 * * 1'
  workflow_dispatch:
    inputs:
      ghidra_version:
        description: 'Specific Ghidra version to update to (leave empty for latest)'
        required: false
        type: string

jobs:
  check-and-update:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Detect latest Ghidra release
        id: ghidra-release
        run: |
          if [ -n "${{ github.event.inputs.ghidra_version }}" ]; then
            # Manual trigger with specific version
            VERSION="${{ github.event.inputs.ghidra_version }}"
            TAG="Ghidra_${VERSION}_build"
            RELEASE_JSON=$(curl -sf "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/${TAG}")
          else
            # Scheduled: get latest
            RELEASE_JSON=$(curl -sf "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/latest")
          fi

          VERSION=$(echo "$RELEASE_JSON" | jq -r '.name' | sed 's/Ghidra //')
          DOWNLOAD_URL=$(echo "$RELEASE_JSON" | jq -r '.assets[0].browser_download_url')

          echo "version=${VERSION}" >> "$GITHUB_OUTPUT"
          echo "download_url=${DOWNLOAD_URL}" >> "$GITHUB_OUTPUT"
          echo "Detected Ghidra version: ${VERSION}"

      - name: Check if update is needed
        id: check-version
        run: |
          CURRENT=$(cat .ghidra-version 2>/dev/null || echo "none")
          LATEST="${{ steps.ghidra-release.outputs.version }}"
          echo "Current: ${CURRENT}, Latest: ${LATEST}"
          if [ "$CURRENT" = "$LATEST" ]; then
            echo "skip=true" >> "$GITHUB_OUTPUT"
            echo "Already up to date."
          else
            echo "skip=false" >> "$GITHUB_OUTPUT"
            echo "Update available: ${CURRENT} -> ${LATEST}"
          fi

      - name: Download and extract Ghidra
        if: steps.check-version.outputs.skip == 'false'
        run: |
          VERSION="${{ steps.ghidra-release.outputs.version }}"
          URL="${{ steps.ghidra-release.outputs.download_url }}"
          echo "Downloading Ghidra ${VERSION}..."
          curl -sSfL -o ghidra.zip "$URL"
          unzip -q ghidra.zip -d /tmp/ghidra-extract
          # Find the extracted directory (name varies by release date)
          GHIDRA_DIR=$(find /tmp/ghidra-extract -maxdepth 1 -type d -name "ghidra_*" | head -1)
          echo "GHIDRA_INSTALL_DIR=${GHIDRA_DIR}" >> "$GITHUB_ENV"
          echo "Extracted to: ${GHIDRA_DIR}"

      - name: Set up JDK
        if: steps.check-version.outputs.skip == 'false'
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          # Read the minimum Java version from the extracted Ghidra
          java-version-file: '' # We'll determine this dynamically
          java-version: '21'

      - name: Update project files
        if: steps.check-version.outputs.skip == 'false'
        run: |
          VERSION="${{ steps.ghidra-release.outputs.version }}"

          # Update .ghidra-version
          echo "${VERSION}" > .ghidra-version

          # Copy Ghidra's own gradle-wrapper.properties
          cp "${GHIDRA_INSTALL_DIR}/support/gradle/gradle-wrapper.properties" \
             gradle/wrapper/gradle-wrapper.properties

          echo "Updated .ghidra-version to ${VERSION}"
          echo "Copied Ghidra's gradle-wrapper.properties"

      - name: Regenerate Gradle wrapper
        if: steps.check-version.outputs.skip == 'false'
        run: |
          # Extract the Gradle version from the properties we just copied
          GRADLE_VERSION=$(grep distributionUrl gradle/wrapper/gradle-wrapper.properties \
            | sed 's|.*gradle-\(.*\)-bin.zip|\1|')
          echo "Regenerating wrapper for Gradle ${GRADLE_VERSION}"

          # Use the system gradle or download one to regenerate the wrapper JAR
          # The wrapper JAR must match the target Gradle version
          GHIDRA_INSTALL_DIR="${GHIDRA_INSTALL_DIR}" ./gradlew wrapper \
            --gradle-version "${GRADLE_VERSION}" || {
            # If gradlew fails (e.g., wrapper JAR missing), bootstrap with Ghidra's
            echo "Bootstrapping wrapper from Ghidra's copy..."
            if [ -f "${GHIDRA_INSTALL_DIR}/support/gradle/gradle-wrapper.jar" ]; then
              cp "${GHIDRA_INSTALL_DIR}/support/gradle/gradle-wrapper.jar" \
                 gradle/wrapper/gradle-wrapper.jar
            fi
          }

      - name: Build extension
        if: steps.check-version.outputs.skip == 'false'
        run: |
          ./gradlew clean distributeExtension
          echo "Build artifacts:"
          ls -la dist/

      - name: Create Pull Request
        if: steps.check-version.outputs.skip == 'false'
        run: |
          VERSION="${{ steps.ghidra-release.outputs.version }}"
          BRANCH="ghidra-update/${VERSION}"

          git config user.name "Ghidra Update Bot"
          git config user.email "ghidra-update-bot@noreply"

          git checkout -b "${BRANCH}"
          git add .ghidra-version gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar gradlew gradlew.bat
          git diff --cached --quiet && { echo "No changes to commit"; exit 0; }

          git commit -m "chore: update to Ghidra ${VERSION}

          Automated update via CI. Build verified successfully.

          Changes:
          - .ghidra-version: updated to ${VERSION}
          - gradle-wrapper.properties: synced from Ghidra distribution
          - gradle-wrapper.jar: regenerated for matching Gradle version"

          git push origin "${BRANCH}"

          # Create PR via Forgejo API
          # Adjust FORGEJO_URL and auth as needed for your instance
          FORGEJO_URL="${{ github.server_url }}"
          REPO="${{ github.repository }}"
          curl -sf -X POST "${FORGEJO_URL}/api/v1/repos/${REPO}/pulls" \
            -H "Authorization: token ${{ secrets.FORGEJO_TOKEN }}" \
            -H "Content-Type: application/json" \
            -d "{
              \"title\": \"chore: update to Ghidra ${VERSION}\",
              \"body\": \"Automated Ghidra version update.\\n\\nBuild verified successfully against Ghidra ${VERSION}.\\n\\nChanges:\\n- .ghidra-version updated\\n- Gradle wrapper synced from Ghidra distribution\",
              \"head\": \"${BRANCH}\",
              \"base\": \"main\"
            }"
```

**Step 3: Validate YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ghidra-update.yml'))"`
Expected: No errors (exits silently).

**Step 4: Commit**

```bash
git add .github/workflows/ghidra-update.yml
git commit -m "ci: add automated Ghidra update workflow

Weekly check for new Ghidra releases via GitHub API.
Downloads, updates build config, verifies compilation,
and opens a PR if successful. Also supports manual trigger
with a specific version."
```

---

### Task 5: Final verification

**Step 1: Clean build from scratch**

```bash
GHIDRA_INSTALL_DIR=/Users/bharrington/Downloads/ghidra_12.0.3_PUBLIC ./gradlew clean distributeExtension
```

Expected: `BUILD SUCCESSFUL`, `dist/ghidra_12.0.3_PUBLIC_*_ghidra-mcp.zip` created.

**Step 2: Verify repo state**

```bash
git log --oneline -5
```

Expected: 4 new commits (`.ghidra-version`, `build.gradle`, cleanup, workflow).

```bash
git status
```

Expected: Clean working tree (no untracked/modified files, aside from `dist/` and `build/` which are gitignored).

**Step 3: Review what's version-coupled**

Verify the only version-specific content in the repo is `.ghidra-version` (the tracking file). Everything else is derived from `GHIDRA_INSTALL_DIR` at build time:

```bash
grep -r "12\.0\.3" --include="*.gradle" --include="*.properties" --include="*.java" --include="*.yml" .
```

Expected: No matches in source files. Only `.ghidra-version` contains the version string.
