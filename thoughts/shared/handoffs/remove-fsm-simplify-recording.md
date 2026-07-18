---
date: 2026-07-15T19:30:00-07:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "Remove FSM, Simplify Recording to Tap-to-Toggle"
tags: [implementation, fsm, recorder, refactor, audio-testing, elevenlabs, voxtral]
status: in_progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: handoff
---

# Handoff: Remove FSM, Simplify Recording to Tap-to-Toggle

## Task(s)

**Primary Goal**: Remove the amplitude-based FSM from RecorderManager and simplify recording to manual tap-to-toggle. This resolves the emulator audio corking blocker for E2E testing.

**Status by Phase**:
- **Phase 1 (String Resources — Voxtral/ElevenLabs)**: ✅ Complete
- **Phase 2 (WhisperTranscriber.kt — request/response)**: ✅ Complete
- **Phase 3 (MainActivity.kt — default wiring)**: ✅ Complete
- **Phase 4 (README.md)**: ✅ Complete
- **Phase 5 (E2E Testing)**: ✅ Script updated for tap-to-toggle flow. Live test blocked on missing API keys.

**New work done this session**:
- Removed FSM from RecorderManager (amplitude monitoring, state machine, thresholds)
- Rewrote WhisperInputService to use keyboard layout with mic button for tap-to-toggle
- Cleaned up unused FSM constants from constants.xml
- Updated run_e2e_test.sh to use Android Settings search bar instead of browser
- **Updated E2E script for tap-to-toggle flow** (this session):
  - Removed `wait_for_fsm_finish` function (FSM no longer exists)
  - Added `tap_mic_button` function with retry logic (taps `btn_mic` via resource-id)
  - Added `wait_for_recording` function (polls logcat for "Recording started")
  - Added logcat clear before recording to avoid stale messages
  - New flow: focus_text_field → tap_mic (start) → play_audio → sleep → tap_mic (stop) → wait_transcription
  - Replaced `FSM_FINISH_TIMEOUT` with `RECORDING_DURATION=5`
  - Wired `TRANSCRIPTION_TIMEOUT` into `wait_for_transcription`
- APK builds successfully with updated code

**What's NOT done**:
- ~~E2E test script needs updating for the new tap-to-toggle flow~~ ✅ DONE
- Build variant (testDebug) for bypassing microphone not yet created
- No live E2E test has been run with the new code (needs API key: DEEPGRAM_KEY, GROQ_KEY, SIXTYDB_KEY, or ELEVENLABS_KEY)
- Emulator snapshot is fresh (re-saved Jul 15)

## Critical References

1. **Plan document**: `/var/home/l/git/whisper-to-input/thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md` — Full implementation plan for Voxtral/ElevenLabs providers
2. **Previous handoff**: `/var/home/l/git/whisper-to-input/thoughts/shared/handoffs/implement-voxtro-elevenlabs.md` — Earlier work on provider implementation
3. **Emulator audio routing handoff**: `/var/home/l/git/whisper-to-input/thoughts/shared/handoffs/emulator-audio-routing-e2e-test.md` — Details on audio corking blocker

## Recent Changes

**RecorderManager.kt** — Complete rewrite:
- Removed `RecorderState` enum (Idle, Speaking, Finish, Cancelled)
- Removed FSM logic (`updateFsm`, `notifyStateChange`, amplitude monitoring)
- Removed coroutine job for amplitude polling
- Removed all FSM threshold fields and resource loading
- Added `isRecording` property (simple boolean)
- `start()` now just starts MediaRecorder, no FSM reset
- `stop()` now catches `IllegalStateException` gracefully
- Constructor no longer takes `Context` parameter (no resource loading needed)

**WhisperInputService.kt** — Rewritten for tap-to-toggle:
- Now inflates `keyboard_view.xml` layout (has `btn_mic`, `label_status`)
- Removed `onCreateInputView()` returning empty view
- Removed `onStartInputView()` auto-start logic
- Added `toggleRecording()` — checks `recorderManager.isRecording` to decide start/stop
- Added `updateMicUI()` — swaps mic icon between `mic_idle` and `mic_pressed`
- Removed `handleRecorderStateChange()` callback
- Kept `onWindowHidden()` to stop recording if keyboard is dismissed

**constants.xml** — Cleaned up:
- Removed all FSM threshold constants (recorder_amplitude_report_period, recorder_fsm_idle_speaking_threshold, etc.)
- File now contains only a comment

**run_e2e_test.sh** — Updated `focus_text_field()`:
- Now opens Android Settings (`am start -a android.settings.SETTINGS`)
- Taps the "Search settings" bar at (540, 663) instead of app's settings page
- Updated `wait_for_transcription()` to look for any EditText with text

**AGENTS.md** — Created project guidelines:
- Always check justfile before running commands
- Use argent MCP tools for emulator interaction
- XML-first approach for UI state
- Use `adb emu` not `nc` for emulator console
- Audio injection options documented

## Learnings

1. **PulseAudio corking is the root blocker**: QEMU corks its audio input when Android FSM hasn't started recording. The FSM waits for amplitude > 800, but no audio arrives because QEMU is corked. Circular dependency.

2. **No programmatic audio injection exists**: The emulator has no CLI command to inject WAV audio. Extended Controls (GUI) is the only way to inject audio through the virtual audio HAL.

3. **FSM is a UX convenience, not a requirement**: The FSM auto-detects speech start/stop, but tap-to-toggle is simpler and removes the amplitude dependency entirely.

4. **Keyboard layout already exists**: `keyboard_view.xml` has `btn_mic`, `label_status`, cancel/retry buttons, backspace, etc. The voice-only mode was returning an empty view, ignoring this layout.

5. **Snapshot is stale**: Emulator snapshot failed to load with "feature 117 missing" error. Needs re-saving with current emulator version (`just emulator-save-snapshot`).

6. **Guardian Project Haven uses `QEMU_AUDIO_DRV=none`**: Disabling audio entirely in CI is a known pattern for tests that don't need live audio.

7. **Robolectric has `ShadowAudioRecord`**: Can mock AudioRecord for unit tests, bypassing emulator entirely.

## Artifacts

- **Modified files**:
  - `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt` — Complete rewrite (FSM removed)
  - `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — Rewritten for tap-to-toggle
  - `android/app/src/main/res/values/constants.xml` — FSM constants removed
  - `run_e2e_test.sh` — Updated to use Settings search bar
  - `AGENTS.md` — Project guidelines created

- **Existing evidence**:
  - `/var/home/l/git/whisper-to-input/thoughts/shared/showboat/elevenlabs-e2e.md` — Dropdown/API screenshots
  - `/var/home/l/git/whisper-to-input/thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md` — Implementation plan

- **HTML explanations**:
  - `/tmp/2026-07-15-explanation-voxtro-elevenlabs-providers.html` — Provider diff explanation
  - `/tmp/2026-07-15-explanation-test-build-architecture.html` — Test build architecture (sketch)

## Action Items & Next Steps

1. ~~Update E2E test script for tap-to-toggle flow~~: ✅ DONE
2. **Run live E2E test**: Requires an API key (DEEPGRAM_KEY, GROQ_KEY, SIXTYDB_KEY, or ELEVENLABS_KEY)
3. Consider creating testDebug build variant: For fully automated testing without emulator audio:
   - Add `testDebug` build type in `build.gradle.kts` with `applicationIdSuffix = ".test"`
   - Create `TestRecorderManager` that copies test WAV and triggers transcription directly
   - This would bypass microphone entirely for E2E tests
4. **Commit the FSM removal**: The changes are significant and should be committed separately from the provider work

## Other Notes

- **Emulator state**: Currently running headful (`emulator-5554`), but snapshot is stale
- **APK builds successfully**: `./gradlew assembleDebug` passes with the new code
- **Test audio**: `test-sources/test-audio.wav` (3.12s, 44.1kHz stereo, says "Hello? What's going on?")
- **ElevenLabs API key**: `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887` — may be quota-exceeded
- **Drawer icons**: `mic_idle.png` and `mic_pressed.png` exist; no `mic_active.png`
- **Justfile**: Use `just -l` to see available commands, `just build` to build, `just emulator-start` for emulator
