---
date: 2025-07-14T23:50:00-07:00
researcher: pi-agent
git_commit: 1e2b859
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test Script — API Key Input & FSM Wait Fixes"
tags: [e2e, android, adb, shell-script, emulator]
status: in-progress
last_updated: 2025-07-14
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: E2E Test — Fix API Key Input & Complete End-to-End Run

## Task(s)
1. **Fix and run `run_e2e_test.sh`** to complete an end-to-end transcription test:
   - Script: `run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"`
   - Requires `JAVA_HOME=/var/home/l/jdk17` and PATH prepended when invoking
   - **Status: In progress** — 4 bugs found and fixed, 1 remaining blocker

2. **Capture a screenshot** showing "hello world" transcribed on screen (goal requirement)

## Critical References
- `run_e2e_test.sh` — the main e2e test script (all changes are here)
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:33-48` — how the app sends the API key to Deepgram (`Authorization: Token $apiKey`)
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:56-60` — DataStore key name is `API_KEY = stringPreferencesKey("api-key")`

## Bugs Found & Fixed (4 of 5)

### Bug 1: `run_cmd` + `set -e` kills script on best-effort commands
- **File:** `run_e2e_test.sh:101-103`
- **Root cause:** `run_cmd` with `check=false` still returned the command's exit code (`return $rc`). Under `set -e`, a nonzero return from an unguarded call exits the script. Example: `pkill -9 -f 'qemu-system'` returns 1 when no process matches → script dies.
- **Fix:** Changed `return $rc` → `return 0` in the `check=false` branch.

### Bug 2: Boot-wait loop dies on first adb poll
- **File:** `run_e2e_test.sh:190-191`
- **Root cause:** `if adb_cmd get-state ...` routes through `run_cmd` with `check=true`, so even the expected "device not found" during boot calls `die`/`exit` before the `if` can evaluate the nonzero.
- **Fix:** Replaced `adb_cmd` with direct `"$ADB" -s "$SERIAL" get-state` in the boot-loop condition.

### Bug 3: `wait_for_fsm_finish` hangs forever
- **File:** `run_e2e_test.sh:427-462`
- **Root cause:** `logcat -c` clears the buffer, then `adb logcat -s whisper-input:V | while read` streams. If the FSM transition happened *before* the stream started, the `while read` blocks forever (the 30s timeout is only checked per-line, so with no lines it never fires).
- **Fix:** Rewrote to poll `logcat -d` in a `while` loop with a real wall-clock timeout (`FSM_FINISH_TIMEOUT`).

### Bug 4: `set_api_key` doesn't clear the field (IN PROGRESS — see Remaining Blocker)
- **File:** `run_e2e_test.sh:381-386`
- **Root cause:** Triple-tap to select-all doesn't work on `textMultiLine` EditText. Ctrl+A (keyevent 113+29) also didn't work. The field accumulates text across runs.
- **Fix applied (incomplete):** Replaced triple-tap with Ctrl+A + DEL. **This fix does NOT work** — Ctrl+A doesn't reliably select all on `textMultiLine` fields either.

## Remaining Blocker: `input text` mangles long strings

### The Problem
The Deepgram API key (`ba862dc7d60ebebe7257aa8f0c802890cb016789`, 40 hex chars) gets garbled when typed via `adb shell input text`. The field ends up with repeated/mangled fragments. Verified by:
- UI dump showing `text='ba862dc7d60ebebe72yba862dc7d60ebebe72...'` (doubled + garbled)
- The same key works perfectly via direct `curl` to Deepgram (HTTP 200, valid transcription)
- The app sends `Authorization: Token $apiKey` — correct format

This is a **known ADB bug**: `input text` sends each character as a separate key event, and the IME drops/reorders them on long strings.

### The API Key is Valid
```bash
curl -s -X POST --header "Authorization: Token ba862dc7d60ebebe7257aa8f0c802890cb016789" \
  --header "Content-Type: audio/wav" --data-binary @/tmp/test-speech-loud.wav \
  "https://api.deepgram.com/v1/listen"  # Returns HTTP 200 with valid transcription
```

### Three Fix Options (from research)

**Option A — Chunked typing (simplest, no installs):**
```bash
# Clear: move to end + 250x DEL in one --longpress batch
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keyevent --longpress 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
# Type in small chunks with 0.5s sleep between each
adb shell input text ba862dc7d60e; sleep 0.5
adb shell input text bebe7257aa8f; sleep 0.5
adb shell input text 0c802890cb01; sleep 0.5
adb shell input text 6789
```

**Option B — ADBKeyboard (most reliable, requires APK install on emulator):**
```bash
# Install ADBKeyboard, switch to it
adb shell ime set com.android.adbkeyboard/.AdbIME
# Clear and set
adb shell am broadcast -a ADB_CLEAR_TEXT
adb shell am broadcast -a ADB_INPUT_TEXT --es msg 'ba862dc7d60ebebe7257aa8f0c802890cb016789'
```

**Option C — uiautomator2 Python `set_text()`:** Most robust but adds a Python dependency.

## Current Emulator State
- **Emulator is RUNNING** (PID from earlier launch, `qemu-system-x86_64` process alive)
- **App is installed** with Deepgram backend selected, endpoint/model verified
- **Settings screen is open** (showing the mangled API key in the field)
- **Script is NOT running** (was killed earlier due to hang)
- **Screenshot saved** at `/tmp/e2e_settings_open.png` showing the settings screen

## Artifacts
- `/var/home/l/git/whisper-to-input/run_e2e_test.sh` — main script with all 4 fixes applied
- `/tmp/e2e_settings_open.png` — screenshot of settings screen (shows mangled key)
- `/tmp/e2e_transcription.png` — screenshot from earlier run (blank screen, INVALID_AUTH)
- `/tmp/e2e_run.out` — full trace log from last run
- `/var/home/l/git/whisper-to-input/thoughts/shared/handoffs/e2e-test-api-key-input.md` — this handoff

## Action Items & Next Steps
1. **Fix `set_api_key` in `run_e2e_test.sh:375-395`** — use one of the three options above (Option A: chunked typing is simplest)
2. **Kill the current emulator** (it has stale state with mangled key):
   ```bash
   pkill -9 -f 'qemu-system'; pkill -9 -f 'emulator -avd Pixel_8'
   ```
3. **Re-run the full script** with JDK and key:
   ```bash
   cd /var/home/l/git/whisper-to-input
   nohup env JAVA_HOME=/var/home/l/jdk17 PATH="/var/home/l/jdk17/bin:$PATH" \
     DEEPGRAM_KEY="ba862dc7d60ebebe7257aa8f0c802890cb016789" \
     ./run_e2e_test.sh --backend deepgram --key "$DEEPGRAM_KEY" --expected "hello world" \
     > /tmp/e2e_run.out 2>&1 &
   ```
4. **Monitor for "Transcription committed"** in the log, then capture screenshot:
   ```bash
   /var/home/l/Android/Sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > /tmp/e2e_final.png
   ```
5. **Verify** the screenshot shows "hello world" in the UI

## Other Notes
- **JAVA_HOME must be set** for gradle builds — use `env JAVA_HOME=/var/home/l/jdk17 PATH="/var/home/l/jdk17/bin:$PATH"` prefix on the run command. The bash tool's `export` + `&` form doesn't propagate reliably.
- **`set -x` is ON** (`set -euxo pipefail`) — trace output goes to both console and `e2e_test.log` via tee.
- **Tee flush issue:** `exec > >(tee "$LOG_FILE")` uses a process substitution that may not flush on EXIT. `/tmp/e2e_run.out` (nohup redirect) is the more reliable log. `e2e_test.log` may show stale content.
- **The app's DataStore** stores the API key under `stringPreferencesKey("api-key")`. The `apply_settings` button triggers `Setting.apply()` which reads `EditText.text.toString()` and saves to DataStore.
- **Deepgram auth format:** `Authorization: Token <key>` (confirmed in app source and via curl).
- **The FSM logs** use tag `whisper-input` at level D (debug), not V (verbose). The script filters with `-s whisper-input:V` which captures debug and above.
- **Emulator boot takes ~60s**, gradle build ~26s (cached), full script run ~3-4 min.
