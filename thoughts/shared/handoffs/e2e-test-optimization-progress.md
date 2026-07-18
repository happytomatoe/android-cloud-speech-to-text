---
date: 2026-07-18T05:45:00+0200
git_commit: b9308daabf95a9ad1507e92b51a09184a25a9c68
branch: feat/api-key-links
repository: whisper-to-input
topic: "E2E test optimization - test file mode, app textbox, handsets"
tags: [testing, e2e, handsets, ui-automation, performance]
---

# Handoff: E2E Test Optimization Progress

## Task(s)

1. **Make test file mode default in debug builds** — COMPLETED
   - Changed `SettingDropdown` default from `false` to `true` in `MainActivity.kt:504`
   - Changed `WhisperInputService.kt:163` fallback to `BuildConfig.DEBUG`

2. **Remove virtual mic setup from E2E script** — COMPLETED
   - Removed `setup_virtual_mic()` and `disable_host_mic()` calls
   - Test file mode reads WAV directly, no mic needed

3. **Fix scoped storage permission** — COMPLETED
   - Changed `push_test_audio()` to push to app cache via `run-as`
   - Updated default `TEST_FILE_PATH` to `/data/user/0/$PACKAGE/cache/test-speech-loud.wav`

4. **Use app's own textbox instead of Settings** — COMPLETED
   - Changed `focus_text_field()` to use `field_debug_output` EditText
   - No more navigating to Android Settings for a text field

5. **Emulator mode detection** — COMPLETED
   - Save mode to `/tmp/emulator.mode` when starting
   - Script reads file to detect headless/headful mismatch

6. **Disable animations** — COMPLETED
   - Added `settings put global *_animation_scale 0` after emulator boot

7. **Faster UI dumps** — COMPLETED
   - Updated `scripts/ui_tap.py` to use `exec-out uiautomator dump /dev/tty`

8. **Poll logcat for errors/results** — COMPLETED
   - Updated `wait_for_transcription()` to check logcat for errors and results

9. **Install Handsets CLI** — IN PROGRESS
   - Added `just setup` command to install via curl script
   - Installed v0.1.36 but `hs use` fails with "No such file or directory"
   - Need to debug or use `cargo binstall` alternative

10. **Reduce sleep times in select_backend** — NOT STARTED
    - Currently 13s for backend selection (sleeps + dumps)

## Critical References

- `run_e2e_test.sh:755-845` — `run_e2e_test()` function with profiling
- `run_e2e_test.sh:630-680` — `focus_text_field()` now uses app textbox
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:162-165` — test file mode check
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:504` — SettingDropdown default

## Recent Changes

- `run_e2e_test.sh:38-40` — Removed virtual mic setup calls
- `run_e2e_test.sh:250-280` — Emulator mode detection via `/tmp/emulator.mode`
- `run_e2e_test.sh:285-290` — Disable animations after boot
- `run_e2e_test.sh:630-650` — `focus_text_field()` uses app's `field_debug_output`
- `run_e2e_test.sh:670-710` — `wait_for_transcription()` polls logcat
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:163` — `USE_TEST_FILE` defaults to `BuildConfig.DEBUG`
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:504` — `SettingDropdown` default `true` for `USE_TEST_FILE`
- `scripts/ui_tap.py:40-48` — Uses `exec-out` instead of file-based dump
- `justfile:170-185` — `setup` target with version parameter
- `.gitignore` — Added `e2e_test.log`

## Learnings

1. **DataStore defaults are written on first load**: The `SettingDropdown.setup()` function writes the default value to DataStore when it's null. Changing the fallback in `WhisperInputService` isn't enough — must also change the `SettingDropdown` default.

2. **Scoped storage blocks `/sdcard/`**: Apps can't read `/sdcard/` directly on Android 10+. Must use `run-as` to copy to app cache directory.

3. **`exec-out` is faster than file dump**: `adb exec-out uiautomator dump /dev/tty` streams XML directly, avoiding file I/O on device.

4. **Handsets install issue**: The curl install script installs but `hs use` fails with "No such file or directory". May need `cargo binstall` or debugging.

5. **Current timing breakdown** (61s total):
   - Build + install: 3s
   - Select backend: 13s (main bottleneck)
   - Set API key: 7s
   - Apply settings: 4s
   - Focus text field: 3s
   - Transcription wait: 4s

## Artifacts

- `run_e2e_test.sh` — Main E2E test script with optimizations
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — Test file mode default
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` — SettingDropdown default
- `scripts/ui_tap.py` — Faster UI dump via exec-out
- `justfile` — Setup command with handsets install
- `.gitignore` — E2E test logs

## Action Items & Next Steps

1. **Debug Handsets installation**
   - Try `cargo binstall handsets` as alternative
   - Or debug why `hs use` fails (possibly missing dependency)

2. **Integrate Handsets into E2E script**
   - Replace `ui_tap.py` calls with `hs tap` commands
   - Use `hs wait` instead of sleep-based polling
   - Expected speedup: 13s → ~2s for backend selection

3. **Reduce remaining sleep times**
   - `select_backend`: 13s → target ~3s
   - `set_api_key`: 7s → target ~2s
   - `apply_settings`: 4s → target ~1s

4. **Commit changes**
   - All optimizations are uncommitted
   - Run unit tests before committing

## Other Notes

- The `field_debug_output` EditText shows "Transcribed text will appear here…" by default, which we filter out when checking for transcription results
- Emulator must be restarted when switching between headless/headful modes (detected via `/tmp/emulator.mode`)
- The `--headful` flag is useful for debugging visually
- Log file `e2e_test.log` is written to current directory and gitignored
