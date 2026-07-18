---
date: 2026-07-15T09:30:00+0200
researcher: pi-agent
git_commit: 1e2b859
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test — DataStore Bypass for Backend/API Key Configuration"
tags: [e2e, android, adb, emulator, datastore, protobuf, ui-automation, shell-script]
status: in-progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: E2E Test — DataStore Bypass for Backend/API Key Configuration

## Task(s)
1. **Fix and run `run_e2e_test.sh`** to complete an E2E transcription test with Deepgram backend
   - Script: `./run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"`
   - **Status: In progress** — UI-based approach abandoned due to uiautomator flakiness; DataStore direct-write approach partially implemented but `run-as` stdin piping needs fixing

2. **Capture a screenshot** showing "hello world" transcribed on screen (goal requirement)
   - **Status: Blocked** — depends on Task 1

## Critical References
- `run_e2e_test.sh` — the main E2E test script (677 lines, untracked)
- `scripts/write_datastore.py` — Python script to generate Preferences DataStore protobuf binary (4464 bytes, untracked)
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:55-60` — DataStore key definitions (`API_KEY`, `SPEECH_TO_TEXT_BACKEND`, `ENDPOINT`, `MODEL`)
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:64-70` — how the IME reads all settings from DataStore
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:77-78` — how the IME reads backend from DataStore

## Recent Changes

### run_e2e_test.sh:222-227 — `clear_app_data()` made non-fatal
`pm clear` fails on fresh emulators (app not installed yet). Changed to use `check=false`:
```bash
clear_app_data() {
    log_info "Clearing app data for fresh state..."
    # Non-fatal: package may not be installed yet on a fresh emulator
    run_cmd "$ADB -s $SERIAL shell pm clear $PACKAGE" false
    log_ok "App data cleared"
}
```

### run_e2e_test.sh:432-455 — New `write_datastore()` function (INCOMPLETE)
Bypasses all UI interaction for backend selection and API key entry by writing the Android Preferences DataStore protobuf file directly via `adb shell run-as`. Current implementation has a `run-as` stdin piping issue that needs fixing (see Learnings).

### run_e2e_test.sh:560-570 — `run_e2e_test()` flow simplified
Replaced the old `open_settings → select_backend → set_api_key → apply_settings` UI sequence with `write_datastore → open_settings`:
```bash
    # 3. Settings configuration (bypass UI via DataStore)
    local api_key_var="${backend^^}_KEY"
    local api_key="${!api_key_var:-$2}"
    if [[ -z "$api_key" ]]; then
        die "No API key provided for $backend (set ${backend^^}_KEY env var or pass as arg)"
    fi
    write_datastore "$backend" "$api_key"
    open_settings
```

### scripts/write_datastore.py — NEW file
Generates a valid Android Preferences DataStore protobuf binary. Handles:
- String preferences: `speech-to-text-backend`, `endpoint`, `api-key`, `model`, `language-code`, `postprocessing`
- Boolean preferences: `is-auto-recording-start`, `auto-switch-back`, `add-trailing-space`
- Backend configs: deepgram, groq, 60db (with correct endpoint/model strings matching `strings.xml`)

## Learnings

### UI-based approach was fundamentally broken (ROOT CAUSE)
The original approach of selecting the backend dropdown and typing the API key via uiautomator was defeated by two separate scroll issues:
1. **Backend dropdown scroll** — The spinner popup only shows ~5 items. Deepgram (6th item) is sometimes below the visible area. The popup position varies non-deterministically across cold boots.
2. **API key field below fold** — After clear-data launch, the API key field is below the visible viewport. `uiautomator dump` returns empty XML after scrolling, making coordinate lookup impossible. Even with retry logic (3 attempts, 2s apart), the dump consistently returned empty.

**Conclusion: Any approach relying on uiautomator dump after scrolling is unreliable on this emulator/API level.**

### DataStore protobuf approach (chosen)
The app uses Jetpack DataStore Preferences (`preferencesDataStore(name = "settings")`), which stores data in protobuf format at `/data/data/com.example.whispertoinput/files/datastore/settings.preferences_pb`. Both the MainActivity and the IME service read from this file. Writing it directly bypasses all UI interactions.

The protobuf wire format for Preferences is:
- `map<string, Value>` entries where each entry has:
  - field 1 (string): preference key
  - field 2 (Value submessage): { field 4 (string): value } for strings, { field 1 (varint): 0/1 } for booleans

### `run-as` cannot access `/sdcard/` (BLOCKER)
The `run-as` command switches to the app's UID, which doesn't have read permission on `/sdcard/` (owned by media_rw). Two approaches were tried:
1. **Shell command piping** — `echo $base64 | run-as ... sh -c 'base64 -d > file'` — failed due to quoting mangling through `adb_cmd → eval → shell`
2. **Push to /sdcard then cp** — `adb push → run-as cp` — failed with `Permission denied` on `/sdcard/`

**The fix needed:** Pipe the protobuf binary through stdin to `run-as`:
```bash
cat $pb_file | $ADB -s $SERIAL shell run-as $PACKAGE sh -c 'cat > files/datastore/settings.preferences_pb'
```
This avoids `/sdcard/` entirely. The `cat | adb shell` pipe should work because `cat` reads the local file and pipes to adb's stdin, then `run-as sh -c 'cat > file'` reads stdin. This approach was identified but not yet implemented/tested.

### `set -e` + `(( ))` arithmetic trap
With `set -euxo pipefail` (line 29), `(( attempts++ ))` when `attempts=0` evaluates to `(( 0 ))` → exit code 1 → script dies. Fixed by using `attempts=$((attempts + 1))` instead.

### Boot settling time
`sys.boot_completed=1` doesn't mean the package manager is ready. Need `sleep 5` after boot detection before `pm clear` or other package operations (though `clear_app_data` is now non-fatal, this is still good practice for other operations).

## Artifacts
- `run_e2e_test.sh` — main E2E test script with DataStore bypass approach (677 lines, untracked)
- `scripts/write_datastore.py` — protobuf generator (4464 bytes, untracked)
- `scripts/ui_tap.py` — uiautomator tap helper (2202 bytes, untracked)
- `thoughts/shared/handoffs/e2e-test-deepgram-emulator-fix.md` — previous handoff (superseded by this one)
- `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md` — implementation plan
- `/tmp/e2e_test.log` — latest test run output

## Action Items & Next Steps

1. **Fix `write_datastore()` stdin piping** (IMMEDIATE — the one remaining blocker)
   Replace the `run_cmd "cat $pb_file | $ADB ..."` approach. The current `write_datastore` function at `run_e2e_test.sh:432-455` tries `adb push → run-as cp` which fails on `/sdcard` permissions. Change to pipe through stdin:
   ```bash
   # Pipe protobuf through stdin into run-as (avoids /sdcard permission issues)
   run_cmd "cat $pb_file | $ADB -s $SERIAL shell run-as $PACKAGE sh -c 'cat > files/datastore/settings.preferences_pb'"
   ```
   Or use a two-step approach with `dd` or `tee` if direct piping doesn't work with `run_cmd`'s eval.

2. **Run the full E2E test** after fixing stdin piping:
   ```bash
   export JAVA_HOME=/var/home/l/jdk17
   export PATH="/var/home/l/jdk17/bin:$PATH"
   export DEEPGRAM_KEY=ba862dc7d60ebebe7257aa8f0c802890cb016789
   ./run_e2e_test.sh --backend deepgram --key "$DEEPGRAM_KEY" --expected "hello world"
   ```

3. **Verify the DataStore protobuf is read correctly** — After the test passes, check logcat for the correct backend/model being used by the IME.

4. **Capture screenshot** showing "hello world" transcription (goal requirement).

5. **Clean up dead code** — `select_backend()`, `set_api_key()`, `apply_settings()`, `get_field_coords()`, and related UI helper functions are no longer called. Remove them if the DataStore approach is confirmed working.

## Other Notes
- **JAVA_HOME must be set** for gradle builds: `env JAVA_HOME=/var/home/l/jdk17 PATH="/var/home/l/jdk17/bin:$PATH"`
- **Deepgram API key**: `ba862dc7d60ebebe7257aa8f0c802890cb016789` (verified working via curl in previous session)
- **ADB path**: `/var/home/l/Android/Sdk/platform-tools/adb`
- **Emulator serial**: `emulator-5554`
- **Emulator boot time**: ~30-40s cold boot
- **`focus_text_field()` still needs UI** (`run_e2e_test.sh:459-465`) — it scrolls and taps `field_language_code` in the settings activity to trigger the IME. This is the ONLY remaining UI interaction and it works reliably (the field is in a known position after scroll). The settings activity is still opened via `open_settings` just to have a text field available.
- **Backend display name format** — Must match `strings.xml` exactly: `"Deepgram"`, `"Groq"`, `"60db"`. The `write_datastore.py` uses `backend.title()` which produces correct casing for "deepgram" → "Deepgram".
- **Protobuf verification** — The `write_datastore.py` output was verified correct by parsing it back and confirming all 9 preference entries decode properly (tested with a Python protobuf parser).
