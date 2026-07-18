---
date: 2026-07-15T08:26:20+0200
researcher: pi-agent
git_commit: 1e2b859
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test — Deepgram Backend API Key Entry Fix"
tags: [e2e, android, adb, emulator, api-key, deepgram, shell-script]
status: in-progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: E2E Test — Deepgram Backend API Key Entry Fix

## Task(s)
1. **Fix and run `run_e2e_test.sh`** to complete an end-to-end transcription test with Deepgram backend
   - Script: `./run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"`
   - **Status: In progress** — API key entry works manually, but script has UI interaction issues

2. **Capture a screenshot** showing "hello world" transcribed on screen (goal requirement)

## Critical References
- `run_e2e_test.sh` — the main e2e test script
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:33-48` — how the app sends the API key to Deepgram (`Authorization: Token $apiKey`)
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:56-60` — DataStore key name is `API_KEY = stringPreferencesKey("api-key")`

## Recent Changes

### run_e2e_test.sh:222-228
Added `clear_app_data()` function to reset app state before each test run:
```bash
clear_app_data() {
    log_info "Clearing app data for fresh state..."
    adb_cmd shell pm clear "$PACKAGE"
    log_ok "App data cleared"
}
```

### run_e2e_test.sh:371-399
Simplified `set_api_key()` function to remove complex clearing logic:
```bash
set_api_key() {
    local api_key="$1"
    log_info "Setting API key (switching to LatinIME for typing)..."
    set_default_ime "$LATIN_IME"
    sleep 0.5

    local coords x y
    coords=$(get_field_coords "field_api_key")
    read -r x y <<< "$coords"

    log_info "Focusing API key field..."
    adb_cmd shell input tap "$x" "$y"
    sleep 0.5

    # Type API key in chunks
    log_info "Typing API key (chunked)..."
    local key="$1"
    local chunk_size=10
    local i=0
    while [ $i -lt ${#key} ]; do
        local chunk="${key:$i:$chunk_size}"
        adb_cmd shell input text "$chunk"
        sleep 0.3
        i=$((i + chunk_size))
    done
    sleep 0.4

    adb_cmd shell input keyevent KEYCODE_BACK
    sleep 0.5
    set_default_ime "$SERVICE"
}
```

## Learnings

### API Key Entry (ROOT CAUSE FOUND)
1. **The API key field is below the fold** — After clearing app data and launching the app, the API key field is not visible in the initial view. The UI hierarchy shows it at bounds `[63,1706][1017,1824]` but the visible area only goes to about y=1499.

2. **Scrolling is required** — Before tapping the API key field, you must scroll down:
   ```bash
   adb shell input swipe 540 1800 540 600 300
   ```

3. **Backend must be selected first** — The Apply button is disabled until the backend is changed from the default "OpenAI API" to "Deepgram". The script currently selects the backend BEFORE entering the API key, but the scrolling issue prevents the field from being tapped correctly.

4. **Manual API key entry WORKS** — When I manually:
   - Scrolled to reveal the API key field
   - Tapped the field at center coordinates (540, 1084) after scrolling
   - Typed the key in chunks
   - The key appeared correctly: `text='ba862dc7d60ebebe7257aa8f0c802890cb016789'`

5. **The `get_field_coords` function returns coordinates BEFORE scrolling** — The function dumps UI and finds the field, but the field may not be visible. The coordinates it returns are the absolute screen coordinates, not accounting for scroll position.

### Emulator Snapshot Boot (RESOLVED)
- Emulator cold boot takes ~30-40 seconds without snapshot
- Snapshot creation works via telnet console (port 5554)
- `-no-snapshot-save` flag prevents slow exit

### FSM Detection (WORKING)
- Audio injection via `paplay --device=VirtualMicSink` works
- FSM correctly transitions: Idle → Speaking → Finish
- Logcat shows: `FSM transition: Speaking -> Finish (silence duration=3000)`

## Artifacts
- `/var/home/l/git/whisper-to-input/run_e2e_test.sh` — main script with API key entry fix
- `/tmp/e2e_test.log` — latest test run output
- `/tmp/emulator.log` — emulator log

## Action Items & Next Steps

1. **Fix the scrolling issue in `set_api_key()`** — Add a scroll command before tapping the API key field:
   ```bash
   # Scroll down to reveal API key field
   adb_cmd shell input swipe 540 1800 540 600 300
   sleep 0.8
   ```

2. **Verify the backend is selected BEFORE API key entry** — The current flow in `run_e2e_test()` is:
   - Line 520: `select_backend "$backend"` ✓
   - Line 528: `set_api_key "$api_key"` — needs scrolling fix

3. **After fixing scrolling**: Re-run the full E2E test:
   ```bash
   export JAVA_HOME=/var/home/l/jdk17
   export PATH="$JAVA_HOME/bin:$PATH"
   ./run_e2e_test.sh --backend deepgram --key ba862dc7d60ebebe7257aa8f0c802890cb016789 --expected "hello world"
   ```

4. **Capture screenshot** showing successful transcription

## Other Notes
- **JAVA_HOME must be set** for gradle builds: `env JAVA_HOME=/var/home/l/jdk17 PATH="/var/home/l/jdk17/bin:$PATH"`
- **Deepgram API key**: `ba862dc7d60ebebe7257aa8f0c802890cb016789` (verified working via curl)
- **Deepgram auth format**: `Authorization: Token <key>`
- **ADB path**: `/var/home/l/Android/Sdk/platform-tools/adb`
- **Emulator serial**: `emulator-5554`
- **UI tap script**: `python3 scripts/ui_tap.py` — can tap by resource-id or text
- **Virtual mic setup**: Already configured (VirtualMicSink + FakeMic)
- **The `clear_app_data()` function works** — After clearing, the API key field shows placeholder text `Enter OpenAI API Key…`
- **The API key field bounds after scrolling**: `[63,1025][1017,1143]` with center at `(540, 1084)`
