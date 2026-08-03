# Android Release Skill

Create and publish releases for the whisper-to-input Android app. Use when the user asks to "create a release", "publish a release", "cut a release", "release this", or similar.

## Overview

The project has two release paths:

| Path | Trigger | APK | Use case |
|------|---------|-----|----------|
| **Standard** | Push to `master` | Signed (CI secrets) | Production releases |
| **Manual** | `gh release create` | Unsigned (local build) | Quick/test releases from feature branches |

## Standard Release (Recommended)

Merges to `master` auto-trigger the release workflow which builds a **signed** APK and creates a GitHub release.

### Steps

1. **Ensure version is bumped** in `android/app/build.gradle.kts`:
   ```bash
   grep -E 'versionName|versionCode' android/app/build.gradle.kts
   ```

2. **Merge PR to master** (or push directly if allowed):
   ```bash
   gh pr merge <PR_NUMBER> --merge
   ```

3. **Wait for CI** — the `Release` workflow will:
   - Build signed APK using `KEYSTORE_BASE64` secret
   - Create GitHub release with tag from `versionName`
   - Attach `app-release.apk`

4. **Verify**:
   ```bash
   gh release list --limit 3
   gh release view v<VERSION>
   ```

### Version Bump Rules

- `check-version-bump.yml` enforces version bumps on PRs to master
- Bump `versionName` (e.g., `"0.10.0"` → `"0.11.0"`) and `versionCode` (e.g., `14` → `15`)
- Location: `android/app/build/outputs/apk/debug/app-debug.apk`

## Manual Release (Feature Branch)

For quick releases without merging to master. Produces an **unsigned** APK (not suitable for Play Store, but installable via `adb install` with `-t` flag).

### Steps

1. **Build release APK locally** (unsigned):
   ```bash
   export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
   cd android && ./gradlew assembleRelease
   ```
   Output: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

2. **Determine version** from `build.gradle.kts`:
   ```bash
   VERSION=$(grep -oP 'versionName = "\K[^"]+' android/app/build/gradle.kts)
   echo "v${VERSION}"
   ```

3. **Create GitHub release** with the unsigned APK:
   ```bash
   gh release create "v${VERSION}" \
     --title "Release v${VERSION}" \
     --generate-notes \
     android/app/build/outputs/apk/release/app-release-unsigned.apk
   ```

4. **Or create as draft** for review first:
   ```bash
   gh release create "v${VERSION}" \
     --title "Release v${VERSION}" \
     --draft \
     --generate-notes \
     android/app/build/outputs/apk/release/app-release-unsigned.apk
   ```

### Installing Unsigned APK

```bash
adb install -t android/app/build/outputs/apk/release/app-release-unsigned.apk
```

The `-t` flag allows installing test/unsigned APKs.

## Useful Commands

```bash
# List recent releases
gh release list --limit 10

# View release details
gh release view v0.10.0

# Download release APK
gh release download v0.10.0

# Delete a release (keeps tag)
gh release delete v0.10.0 --yes

# Delete release AND tag
gh release delete v0.10.0 --yes --cleanup-tag

# Edit an existing release
gh release edit v0.10.0 --title "New Title" --notes "Updated notes"

# Publish a draft release
gh release edit v0.10.0 --draft=false
```

## Workflow Files

| Workflow | File | Trigger | Purpose |
|----------|------|---------|---------|
| Release | `.github/workflows/release.yml` | Push to `main`/`master` | Signed APK + GitHub release |
| Build APK | `.github/workflows/build-apk.yml` | Push to `master` + `workflow_dispatch` | Debug APK artifact |
| Version Check | `.github/workflows/check-version-bump.yml` | PR to `master` | Enforces version bump |

## Troubleshooting

### "Release already exists"
```bash
# Check existing releases
gh release list | grep v0.10.0

# Delete and recreate
gh release delete v0.10.0 --yes
gh release create v0.10.0 ...
```

### "Tag already exists"
```bash
# Delete remote tag
git push origin --delete v0.10.0

# Or delete with release
gh release delete v0.10.0 --yes --cleanup-tag
```

### Release workflow failed on CI
Check the run: `gh run list --workflow=release.yml --limit 5`
View logs: `gh run view <RUN_ID> --log-failed`

### Need signed APK locally
Requires keystore + secrets. Use CI instead:
1. Merge to master
2. Let the release workflow handle signing
3. Download with `gh release download`
