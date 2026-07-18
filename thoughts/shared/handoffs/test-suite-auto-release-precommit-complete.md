---
date: 2026-07-18T04:30:00+0200
git_commit: b9308daabf95a9ad1507e92b51a09184a25a9c68
branch: feat/api-key-links
repository: whisper-to-input
topic: "Test suite virtual-time conversion + auto release + pre-commit hooks"
tags: [testing, robolectric, coroutines, virtual-time, auto, release, pre-commit, lefthook]
---

# Handoff: Test suite fixes, auto release, and git hooks setup

## Task(s)

1. **Convert test suite to virtual time (StandardTestDispatcher)** — COMPLETED
   - Switched `MainDispatcherRule` from `UnconfinedTestDispatcher` to `StandardTestDispatcher`
   - Fixed all tests that broke due to the dispatcher change
   - Fixed 400s+ hang via `forkEvery=1` and `@After` teardown

2. **Fix `apply_writes_dirty_setting_to_datastore` race condition** — COMPLETED
   - Root cause: Two race conditions:
     1. `buildAndWait()` returned when `endpoint.text` was non-empty, but `setupSettingItemsDone` was still false
     2. TextWatcher queued on scheduler, but Apply coroutine ran first
   - Fixed via:
     - Added `settingsReady: CompletableDeferred<Unit>` to `MainActivity`
     - `buildAndWait()` awaits `settingsReady.isCompleted`
     - Test drains scheduler after `setText()` before clicking Apply
     - Pump loop for DataStore IO writes

3. **Set up auto release with git-based versioning** — COMPLETED
   - Switched to `intuit/auto` for automated versioning
   - `versionName` from git tag (exact on main, -SNAPSHOT on dev branches)
   - `versionCode` from commit count (monotonically increasing)
   - upload-assets plugin attaches APK to GitHub releases

4. **Set up pre-commit hooks** — IN PROGRESS
   - Created `.pre-commit-config.yaml` with hooks
   - Need to switch to lefthook per user request

## Critical References

- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:77-85` — `settingsReady` CompletableDeferred
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:285-300` — TextWatcher registration after setText()
- `android/app/src/test/java/com/example/whispertoinput/MainActivitySettingsTest.kt:108-118` — `buildAndWait()` awaiting settingsReady
- `android/app/src/test/java/com/example/whispertoinput/MainActivitySettingsTest.kt:172-202` — Fixed apply test with pump loop
- `android/app/build.gradle.kts:17-32` — Git-based versioning
- `.autorc` — Auto configuration with git-tag, upload-assets, released plugins
- `.github/workflows/release.yml` — Auto release workflow

## Recent Changes

- `MainActivity.kt:77-85` — Added `settingsReady = CompletableDeferred<Unit>()` with `@VisibleForTesting`
- `MainActivity.kt:285-300` — Moved `doOnTextChanged` registration after `setText()` to prevent false dirty-state
- `MainActivity.kt:540-543` — `settingsReady.complete(Unit)` after `setupSettingItemsDone = true`
- `MainActivitySettingsTest.kt:108-118` — `buildAndWait()` awaits `settingsReady.isCompleted`
- `MainActivitySettingsTest.kt:172-202` — Fixed test with scheduler drain + pump loop
- `android/app/build.gradle.kts:17-32` — Git-based versioning (versionName from git tag, versionCode from commit count)
- `.autorc` — Auto config with git-tag, upload-assets, released plugins
- `.github/workflows/release.yml` — Auto release workflow
- `justfile:120-128` — Added `test-all` command for parallel test execution

## Learnings

1. **StandardTestDispatcher boundary quirk**: `advanceTimeBy(delay)` does NOT run the resumed continuation if it lands exactly on the target time; it defers to the next `advanceTimeBy` or `runCurrent()`. Fix: call `runCurrent()` after each `advanceTimeBy()`.

2. **IO-mixed tests need pump loops**: Under `StandardTestDispatcher`, `withContext(Dispatchers.IO)` runs on real threads; the callback queued on Main is only dispatched when the scheduler advances. Pattern:
   ```kotlin
   while (!done) { scheduler.advanceUntilIdle(); if (result != null) break; Thread.sleep(20) }
   ```

3. **TextWatcher registration order matters**: The TextWatcher must be registered AFTER `setText()` during setup to prevent false dirty-state. Otherwise, the initial `setText()` during setup triggers the watcher and marks the setting as dirty before the user makes any changes.

4. **DataStore writes are IO-blocking**: `dataStore.edit()` suspends on `Dispatchers.IO` (real thread). The Main coroutine blocks waiting for it. Test must pump: `advanceUntilIdle()` + `Thread.sleep()` to let IO complete.

5. **Git-based versioning**: Use `git describe --tags --abbrev=0` for versionName and `git rev-list --count HEAD` for versionCode. Add `-SNAPSHOT` suffix for non-main branches.

6. **Auto release**: Uses PR labels to determine version bumps (enhancement=minor, bug=patch, breaking=major). The `git-tag` plugin creates tags, `upload-assets` attaches APK, `released` comments on PRs.

## Artifacts

- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
- `android/app/src/test/java/com/example/whispertoinput/MainActivitySettingsTest.kt`
- `android/app/src/test/java/com/example/whispertoinput/util/MainDispatcherRule.kt`
- `android/app/build.gradle.kts`
- `.autorc`
- `.github/workflows/release.yml`
- `.pre-commit-config.yaml` (to be replaced with lefthook)
- `.editorconfig`
- `justfile`
- `scripts/update-version.sh`

## Action Items & Next Steps

1. **Switch from pre-commit to lefthook** — User explicitly requested this. Need to:
   - Install lefthook: `brew install lefthook` or `npm install -g lefthook`
   - Create `lefthook.yml` config
   - Remove `.pre-commit-config.yaml`
   - Run `lefthook install`

2. **Configure lefthook hooks**:
   - `pre-commit`: ktlint, android-lint, check-yaml, check-json
   - `pre-push`: `just test-all` (runs unit tests + E2E tests in parallel)
   - `commit-msg`: commitizen linting

3. **Merge PR #8** — Contains all the changes from this session

4. **Verify auto release works** — After merge, check that auto creates a release with APK

## Other Notes

- The 400s+ hang was caused by Robolectric cross-test state leaks (fire-and-forget coroutines on `Dispatchers.Main` holding DataStore write locks). Fixed via `forkEvery=1` + `@After` teardown.
- `forkEvery=1` means per-CLASS, not per-method. All tests in the same class share one JVM.
- The `selecting_backend_autofills_endpoint_model_and_language` test's TextWatcher can fire during `apply_writes_dirty_setting_to_datastore` because they share a JVM. This is expected and doesn't affect correctness.
- Auto workflow uses `GITHUB_TOKEN` for authentication. No additional secrets needed.
- Git hooks path was previously set to `.githooks` via `git config core.hooksPath`. This needs to be unset when switching to lefthook.
