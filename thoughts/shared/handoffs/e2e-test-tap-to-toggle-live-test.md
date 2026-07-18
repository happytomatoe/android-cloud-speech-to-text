---
date: 2026-07-15T19:45:00-07:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test: Tap-to-Toggle Flow & Live Test Debugging"
tags: [e2e, tap-to-toggle, emulator, audio-routing, deepgram, recorder, fsm]
status: in_progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: handoff
---

# Handoff: E2E Test — Tap-to-Toggle Flow & Live Test Debugging

## Task(s)

**Primary Goal**: Get the E2E test passing with the new tap-to-toggle recording flow (FSM removed).

**Status**:
- ✅ E2E script updated for tap-to-toggle flow
- ✅ `JAVA_HOME` added to `build_and_install()`
- ✅ `-no-snapshot-save` added to justfile headful mode
- ✅ DataStore bypass removed (was crashing with `CorruptionException: Value not set`)
- ✅ `set_api_key()` fixed to not scroll if field already visible
- ❌ **Recording never starts** — mic button tap via `tap_by_rid btn_mic` doesn't trigger recording
- ❌ **Audio plays through speakers** — `paplay --device=VirtualMicSink` is audible (should be silent null sink)

## Critical References

1. **Previous handoff**: `thoughts/shared/handoffs/remove-fsm-simplify-recording.md` — FSM removal context
2. **Emulator audio routing**: `thoughts/shared/handoffs/emulator-audio-routing-e2e-test.md` — audio corking blocker
3. **Deepgram API key**: `ba862dc7d60ebebe7257aa8f0c802890cb016789` — verified working (HTTP 200)

## Recent Changes

**`run_e2e_test.sh`** — Multiple changes this session:

- **Removed FSM dependencies**:
  - Removed `FSM_FINISH_TIMEOUT` constant, added `RECORDING_DURATION=5`
  - Removed `wait_for_fsm_finish()` function entirely
  - Added `tap_mic_button()` — taps `btn_mic` by resource-id with 3 retries
  - Added `wait_for_recording()` — polls logcat for "Recording started" with 5s timeout
  - Added `adb logcat -c` before recording to clear stale messages
  - Wired `TRANSCRIPTION_TIMEOUT` into `wait_for_transcription()`

- **New tap-to-toggle flow** in `run_e2e_test()`:
  ```
  focus_text_field → tap_mic (start) → wait_for_recording → play_test_audio → sleep 5s → tap_mic (stop) → wait_for_transcription
  ```

- **Fixed `JAVA_HOME`** in `build_and_install()`:
  - Added `export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"`

- **Fixed `set_api_key()`**:
  - Now tries to find `field_api_key` first without scrolling
  - Only scrolls if field not found (was breaking because scroll moved field out of view)

- **Removed DataStore bypass**:
  - Replaced `write_datastore` + `force-stop` with UI configuration:
  - Now calls `open_settings`, `select_backend`, `set_api_key`, `apply_settings` in sequence

- **Audio playback**:
  - Attempted `ffmpeg -f pulse` approach — ffmpeg has PulseAudio support (`-f pulse` not `-f pulseaudio`)
  - But `ffmpeg -f pulse VirtualMicSink.monitor` fails (can't write to a source, only sinks)
  - Reverted to `paplay --device=$VIRTUAL_SINK` — but this is audible on user's system

- **Added `-no-snapshot-save`** to justfile headful mode (was only in headless)

**`justfile`** — Headful mode now includes `-no-snapshot-save`

## Learnings

1. **`paplay --device=VirtualMicSink` is audible on this system**: The null sink should be silent, but the user hears the test audio every time. This is the #1 blocker for comfortable repeated testing. Options to explore:
   - `ffmpeg -f pulse <sink_name>` — write to a DIFFERENT null sink (not the monitor source)
   - `pactl load-module module-null-sink sink_name=SilentTestSink` + `paplay --device=SilentTestSink` — completely isolated
   - Investigate why VirtualMicSink routes to speakers (check `pactl list sinks`, loopback modules)
   - Use `--property=media.role=music` or `--property=media.name=test` with paplay

2. **Recording never starts**: The `tap_by_rid btn_mic` call succeeds (ui_tap.py reports "tapped"), but no "Recording started" message appears in logcat. Possible causes:
   - The keyboard (IME) isn't actually showing when `focus_text_field` taps (540, 663) — the Settings search bar was at [189,168][933,263] in one session
   - The mic button tap coordinates don't match the actual button position
   - The IME crashes silently (no logcat output at all from the app)
   - The `logcat -s whisper-input:V` filter might not match the actual log tag

3. **DataStore protobuf bypass is broken**: `write_datastore.py` produces structurally valid protobuf, but Android's `PreferencesSerializer` throws `CorruptionException: Value not set.` The UI configuration approach works reliably — `select_backend` + `set_api_key` + `apply_settings` is the path forward.

4. **Search bar coordinates vary**: The Settings search bar bounds changed between sessions ([42,595][1038,732] → [189,168][933,263]). The hardcoded (540, 663) tap may miss it. Should use `tap_by_rid` or dynamic coordinate lookup instead.

5. **Emulator snapshot**: Already fresh (re-saved today). The `-no-snapshot-save` flag prevents test state from contaminating the snapshot.

## Artifacts

- **Modified files**:
  - `run_e2e_test.sh` — Major rewrite for tap-to-toggle, JAVA_HOME, UI config, scroll fix
  - `justfile` — Added `-no-snapshot-save` to headful mode
  - `thoughts/shared/handoffs/remove-fsm-simplify-recording.md` — Updated with progress

- **Unmodified but relevant**:
  - `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt` — FSM removed, simple start/stop
  - `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — Rewritten for tap-to-toggle
  - `scripts/ui_tap.py` — UI automation helper (taps by rid/text)
  - `scripts/write_datastore.py` — Broken protobuf encoder (do not use)
  - `test-sources/test-audio.wav` — 3.12s test audio

## Action Items & Next Steps

1. **Fix audio injection (silent)**: The `paplay` approach is audible. Try:
   ```bash
   # Create a completely separate null sink for test audio
   pactl load-module module-null-sink sink_name=SilentTestSink sink_properties=device.description=SilentTest
   paplay --device=SilentTestSink /tmp/test-speech-loud.wav
   # Then load module-loopback source=SilentTestSink.monitor sink=VirtualMicSink
   # to route audio from SilentTest to the virtual mic
   ```
   Or explore `ffmpeg -f pulse SilentTestSink` (writing to a sink, not a source).

2. **Fix recording not starting**: Debug why `tap_by_rid btn_mic` doesn't trigger recording:
   - Check if keyboard is actually showing: dump UI after `focus_text_field` and look for `btn_mic`
   - Check if the IME is crashing: `$ADB logcat -d | grep -i crash`
   - Try tapping by coordinates instead of resource-id
   - Check if `logcat -s whisper-input:V` filter is correct (maybe tag is different)

3. **Fix search bar coordinates**: Replace hardcoded (540, 663) with dynamic lookup or use `tap_by_rid android:id/search_src_text`

4. **Commit FSM removal**: Changes are significant and should be committed separately from provider work

5. **Live E2E test**: Once audio + recording work, run full test with Deepgram key

## Other Notes

- **Emulator state**: Currently stopped. Snapshot is fresh.
- **API key**: `ba862dc7d60ebebe7257aa8f0c802890cb016789` (Deepgram) — verified HTTP 200
- **Virtual mic**: VirtualMicSink + FakeMic sources exist in PulseAudio (may need recreation after emulator restart)
- **Test audio**: `/tmp/test-speech-loud.wav` exists (3.12s, says "hello world this is a test of speech to text transcription")
- **User experience concern**: Audio playing through speakers during E2E tests is unacceptable — must be fixed before repeated testing
