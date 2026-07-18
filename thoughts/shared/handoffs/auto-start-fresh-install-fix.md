---
date: 2026-07-18T13:50:00+02:00
git_commit: 1f25123b7b49b52c50e1094708c55e508c10ac8f
branch: feat/api-key-links
repository: whisper-to-input
topic: "Auto-Start Recording Fix for Fresh Install"
tags: [fix, recording, auto-start, permissions, release, devcontainer]
---

# Handoff: Auto-Start Recording Fix for Fresh Install + Release Cleanup

## Task(s)
All tasks completed:

1. **Delete dev container / GitHub Codespaces code** ✅
   - Removed `.devcontainer/` and `.github/workflows/build-devcontainer-image.yml` (already done in prior commits)
   - Cleaned Codespaces reference from `justfile` comment (line 4)

2. **Revert release system to non-auto (bump-based)** ✅
   - Aligned `.github/workflows/release.yml` with `main`'s bump-based design (removed `auto shipit`, `upload-assets`, `npm install -g auto`)
   - Deleted `.autorc` (auto release config)
   - Release now uses `workflow_dispatch` with `bump: patch|minor|major|skip` + `gh release create`

3. **Resolve conflicts with `main`** ✅
   - Merged `main` into `feat/api-key-links` (commit `1f25123`)
   - Resolved conflicts in `ci.yml`, `build.gradle.kts`, `justfile`, `run_e2e_test.sh`
   - Kept branch's devcontainer deletion, non-auto release, and auto-start feature

4. **Fix auto-start recording bug + provide evidence** ✅
   - **Root cause**: `RecorderManager.requiredPermissions()` required both `RECORD_AUDIO` + `POST_NOTIFICATIONS`; `onStartInputView` gated auto-start on `allPermissionsGranted()`. On fresh install users often grant mic but deny notification permission → auto-start silently no-oped.
   - **Secondary issue**: `onStartInputView` read `prefs[AUTO_RECORDING_START] ?: false` contradicting UI's "Yes" default.
   - **Fixes applied**:
     - `RecorderManager.kt`: `requiredPermissions()` → only `RECORD_AUDIO` (recording needs no notification)
     - `WhisperInputService.kt:138`: read default changed to `?: true` (matches UI default)
   - **Evidence captured** (emulator, fresh install, `POST_NOTIFICATIONS` denied):
     - Logcat: `Recording started` on keyboard bring-up
     - Screenshot: keyboard in recording state

## Critical References
- `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt:39-46` — permission fix
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:138` — pref default fix
- `.github/workflows/release.yml` — non-auto release workflow

## Recent Changes
- `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt:39-46` — removed `POST_NOTIFICATIONS` from `requiredPermissions()`
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:138` — changed `?: false` to `?: true`
- `.github/workflows/release.yml` — replaced with `main`'s bump-based release (no auto)
- `.autorc` — deleted
- `justfile:4` — removed Codespaces comment
- Merge commit `1f25123` — resolved `main` conflicts

## Learnings
- **Permission gate was the bug**: `POST_NOTIFICATIONS` is unnecessary for `MediaRecorder` (no foreground service/notification shown). Removing it from `requiredPermissions()` makes auto-start work on fresh installs where notification permission is often denied.
- **Pref default mismatch**: `SettingDropdown` default `true` (UI "Yes") but `onStartInputView` read `?: false` — changing to `?: true` aligns with UI and works even before app first opens.
- **Emulator flakiness**: `uiautomator dump` crashes (OOM, rc=137) on this environment. Best to verify via logcat + screenshot without UI dumping.

## Artifacts
- **Evidence logcat**: `/tmp/autostart_logcat.txt` — shows `Recording started: /data/user/0/com.example.whispertoinput/cache/recorded.m4a` on keyboard bring-up
- **Evidence screenshot**: `/tmp/autostart_recording.png` (62 KB) — keyboard in recording state (mic pressed, status "Recording")
- **Permission state**: `POST_NOTIFICATIONS: granted=false`, `RECORD_AUDIO: granted=true`
- Modified files: `RecorderManager.kt`, `WhisperInputService.kt`, `release.yml`, `.autorc` (deleted), `justfile`, merge commit `1f25123`

## Action Items & Next Steps
- [x] All tasks complete
- [x] Evidence provided
- [ ] Push branch / open PR (if desired)
- [ ] Run full test suite (`just test` + `just test-e2e`) for final sanity check

## Other Notes
- Build compiles: `just build debug` → success
- Branch `feat/api-key-links` now cleanly merges `main` + has the auto-start fix + devcontainer/auto-release cleanup
- The `thoughts/shared/handoffs/` directory contains related prior work on E2E testing and emulator audio routing