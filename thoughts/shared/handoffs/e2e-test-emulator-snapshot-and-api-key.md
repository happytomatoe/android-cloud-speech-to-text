---
date: 2026-07-15T07:46:38+0200
researcher: pi-agent
git_commit: 1e2b859
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test — Emulator Snapshot Boot & API Key Input Fixes"
tags: [e2e, android, adb, emulator, snapshot, shell-script]
status: in-progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: E2E Test — Emulator Snapshot Boot & API Key Input Fixes

## Task(s)
1. **Fix and run `run_e2e_test.sh`** to complete an end-to-end transcription test
   - Script: `run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"`
   - Requires `JAVA_HOME=/var/home/l/jdk17` and PATH prepended
   - **Status: In progress** — emulator snapshot boot fixed, API key input still failing

2. **Capture a screenshot** showing "hello world" transcribed on screen (goal requirement)

## Critical References
- `run_e2e_test.sh` — the main e2e test script
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:33-48` — how the app sends the API key to Deepgram (`Authorization: Token $apiKey`)
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:56-60` — DataStore key name is `API_KEY = stringPreferencesKey("api-key")`

## Recent Changes

### justfile:42
Changed emulator start flags to use `-no-snapshot-save` for fast exit:
```
FLAGS="-no-window -no-snapshot-save"
```

### ~/.android/avd/Pixel_8.avd/config.ini
Fixed emulator configuration to include `hw.cpu.arch=x86_64` which resolved the "CPU Architecture 'arm' is not supported" error. The original config was missing this critical setting.

### run_e2e_test.sh:381-393
Changed `set_api_key` function multiple times to try different approaches for clearing the EditText field. **Current state uses triple-tap to select-all + DEL** (test in progress).

## Learnings

### Emulator Snapshot Boot (RESOLVED)
1. **Root cause of "Device 'cache' does not have the requested snapshot"**: The emulator was using an invalid or corrupted snapshot. The `fastboot.forceFastBoot=yes` config was set but the snapshot couldn't load.

2. **Root cause of "CPU Architecture 'arm' is not supported"**: The `config.ini` was missing `hw.cpu.arch=x86_64`. Without this, the emulator defaulted to ARM architecture which QEMU2 doesn't support.

3. **Fix**: Added `hw.cpu.arch=x86_64` to `~/.android/avd/Pixel_8.avd/config.ini`. After this fix, cold boot takes ~32 seconds.

4. **Snapshot creation**: Use the emulator console (telnet port 5554) with auth token from `~/.emulator_console_auth_token`:
   ```python
   import socket, time
   AUTH_TOKEN = open('/var/home/l/.emulator_console_auth_token').read().strip()
   s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
   s.connect(('127.0.0.1', 5554))
   s.recv(4096)
   s.sendall(f'auth {AUTH_TOKEN}\n'.encode())
   time.sleep(0.5)
   s.recv(4096)
   s.sendall(b'avd snapshot save default_boot\n')
   time.sleep(5)
   ```

5. **Snapshot boot time**: After creating a fresh snapshot, the emulator loads it in ~4.5 seconds (vs 32s cold boot).

6. **Snapshot loading issue**: The error "Error -1 from the snapshot callback" occurs but the emulator still boots successfully. The snapshot load appears to work despite this error.

7. **Key emulator flags**:
   - `-no-window`: Headless mode
   - `-no-snapshot-save`: Don't save snapshot on exit (fast exit)
   - `-no-snapshot-load`: Cold boot (ignore snapshot)
   - `-no-snapshot`: Disable all snapshot functionality

8. **Consistent startup**: The emulator requires consistent startup flags. Mixing CLI and GUI startup methods causes snapshot load failures.

### API Key Input (STILL BROKEN)
1. **Problem**: The `textMultiLine` EditText field doesn't support:
   - `Ctrl+A` (keyevent 113+29) — doesn't select all
   - Triple-tap to select-all — doesn't work reliably
   - `KEYCODE_MOVE_END` — cursor doesn't move to absolute end
   - `--longpress` DEL keyevents — not processed correctly

2. **Clipboard approaches fail on Android 14 (SDK 34)**:
   - `service call clipboard 2` — permission denied
   - `cmd clipboard set-text` — "No shell command implementation"
   - `am broadcast` with clipboard actions — no receiver, result=0 but no effect

3. **Chunked `input text`**: Works for typing but the field isn't cleared first, so old text remains and new text is appended.

4. **What was tried**:
   - `Ctrl+A` + DEL — doesn't select all on textMultiLine
   - Triple-tap + DEL — doesn't select all reliably
   - `KEYCODE_MOVE_END` + batch `--longpress 67` — DEL events not processed
   - Clipboard + paste — blocked on Android 14

5. **Current state**: The `set_api_key` function is set to use triple-tap + DEL, but this doesn't clear the field. The API key accumulates across runs.

## Artifacts
- `/var/home/l/git/whisper-to-input/run_e2e_test.sh` — main script with API key input fix attempts
- `/var/home/l/git/whisper-to-input/justfile` — updated with `-no-snapshot-save` flag
- `~/.android/avd/Pixel_8.avd/config.ini` — fixed with `hw.cpu.arch=x86_64`
- `~/.android/avd/Pixel_8.avd/snapshots/default_boot/` — fresh snapshot (1.7GB)
- `/tmp/e2e_run.out` — latest test run output
- `/tmp/emulator.log` — emulator log

## Action Items & Next Steps

1. **Fix API key clearing** — The core issue is that no approach reliably clears a `textMultiLine` EditText via ADB. Options to try:
   - **Option A**: Use `uiautomator` Python library to directly call `setText("")` on the EditText element
   - **Option B**: Install ADBKeyboard APK on emulator (`adb install ADBKeyboard.apk`), then use `am broadcast -a ADB_CLEAR_TEXT` to clear and `am broadcast -a ADB_INPUT_TEXT --es msg 'key'` to set
   - **Option C**: Before typing, verify the field is empty by checking UI dump, and only type if empty. If not empty, navigate away and back to settings to reset the field.

2. **After API key fix**: Re-run the full e2e test and capture screenshot showing "hello world"

3. **Update justfile**: The `emulator-stop` command uses `kill` followed by `pkill -9`. Consider using only `kill` (SIGTERM) to allow graceful shutdown, or keep `-no-snapshot-save` to avoid slow save on exit.

## Other Notes
- **JAVA_HOME must be set** for gradle builds: `env JAVA_HOME=/var/home/l/jdk17 PATH="/var/home/l/jdk17/bin:$PATH"`
- **Emulator auth token**: `~/.emulator_console_auth_token` contains the token for telnet console
- **Console port**: 5554 (requires auth before commands)
- **Snapshot save**: Takes ~4.5 seconds via console command
- **The `minimal_test` AVD** works with the same system image — its config has more complete settings including `hw.cpu.arch=x86_64`
- **Deepgram auth format**: `Authorization: Token <key>` (confirmed in app source and via curl)
- **The API key is valid**: `curl -s -X POST --header "Authorization: Token ba862dc7d60ebebe7257aa8f0c802890cb016789" --header "Content-Type: audio/wav" --data-binary @/tmp/test-speech-loud.wav "https://api.deepgram.com/v1/listen"` returns HTTP 200
