# E2E Test Automation — Current Behavior

> **Status:** Rewritten 2026-07-17 to match the current `WhisperInputService` /
> `WhisperInputService` implementation. The previous version described an
> **auto-start FSM** (`Idle → Speaking → Finish`) and "auto-starts recording on
> keyboard show" — **those do not exist**. `WhisperInputService.onStartInputView`
> is a no-op (see `WhisperInputService.kt:138`); recording is toggled by a mic
> tap or by a broadcast.

## What actually drives recording

There are **two** distinct things that can record, and they are NOT the same:

1. **`WhisperInputService` (the live IME).** Tapping the single `btn_mic` rendered
   by `onCreateInputView`, or sending the broadcast
   `com.example.whispertoinput.action.TOGGLE_RECORDING` (`ACTION_TOGGLE_RECORDING`),
   calls `toggleRecording()`. See `WhisperInputService.kt`.
2. **`WhisperKeyboard` (the 8-button state machine).** This class
   (`keyboard/WhisperKeyboard.kt`) implements `Idle` / `Recording` / `Transcribing`
   with mic / cancel / retry / enter / space / backspace / settings / IME-switch
   buttons. **It is currently orphaned** — `WhisperInputService` does not wire it
   in. Its button/state logic is covered by **unit tests**
   (`WhisperKeyboardTest`, `BackspaceButtonTest`), not by E2E. The historical
   "mic button can no longer cancel transcription" regressions are caught there.

## `WhisperInputService` state (the part E2E exercises)

`WhisperInputService` is **not** a multi-state FSM. It has one boolean,
`testFileModeRecording`, plus the live `currentInputConnection`.

- **Normal recording** (permissions granted): first `toggleRecording()` starts the
  `RecorderManager`; second `toggleRecording()` stops it and calls
  `WhisperTranscriber.startAsync(...)`, then commits the text to the input
  connection. If permissions are missing, it launches `MainActivity` instead.
- **Test-file mode** (debug builds, `USE_TEST_FILE = true`): no mic/notification
  permission is needed. The first `toggleRecording()` sets
  `testFileModeRecording = true` and shows the "recording" status; the second
  `toggleRecording()` calls `WhisperTranscriber.startAsync(...)` against the
  configured endpoint and commits the result. This is the path `run_e2e_test.sh`
  uses for a **silent, permission-free** E2E run.

The visible **status label** (`label_status`) cycles through three strings:
`whisper_to_input` (Idle) → `recording` (Recording) → `transcribing`
(Transcribing) → `whisper_to_input` (back to Idle). There is no `Speaking` or
`Finish` state.

## E2E runner

`run_e2e_test.sh` is the orchestrator. It:

1. Sets up the silent virtual microphone (`VirtualMicSink` null-sink +
   `FakeMic` remap-source) so test audio never reaches the host speakers.
2. Boots the emulator with `QEMU_PA_SOURCE=FakeMic` (audio injection requires the
   virtual mic pinned at launch).
3. Installs the debug APK, grants permissions, enables the Whisper IME.
4. Pushes a pre-recorded WAV into the app cache and enables **test-file mode**
   (`USE_TEST_FILE = true`, `TEST_FILE_PATH = <app cache>/test-speech-loud.wav`).
5. Focuses a text field (so the Whisper keyboard appears), then drives the flow
   via the `ACTION_TOGGLE_RECORDING` broadcast:
   - first broadcast → test-file "recording" starts,
   - second broadcast → transcribe the test file,
   - waits for the transcription to be committed to the field.
6. Reports PASS/FAIL.

### Negative-path checks (added with the test suite)

`run_e2e_test.sh` additionally verifies:

- **Status-label transitions** — during the happy path, `label_status` is
  observed passing through `recording` and `transcribing`.
- **Double toggle during transcribe is a single result** — a start→transcribe
  sequence commits exactly one `Transcription result:` line (guards against
  double-commit).
- **Cancel mid-transcribe returns to Idle** — covered at the unit level by
  `WhisperKeyboardTest.cancel_transcribing_cancels`; the IME's `btn_cancel`
  (from `WhisperKeyboard`) is not wired into `WhisperInputService` today, so the
  E2E focuses on the toggle path above.

## What We're NOT Doing

- ❌ Streaming transcription (batch only).
- ❌ Parakeet provider / other platforms (iOS, tvOS).
- ❌ Load/stress testing; single-run functional verification.
- ❌ Rewiring `WhisperKeyboard` into `WhisperInputService` (separate effort; its
  logic is already covered by unit tests).
- ❌ Auto-start on keyboard show (does not exist in current code).

## References

- IME service (current behavior): `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`
- Keyboard state machine (unit-tested, orphaned): `android/app/src/main/java/com/example/whispertoinput/keyboard/WhisperKeyboard.kt`
- Transcriber: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`
- E2E runner: `run_e2e_test.sh`, `scripts/ui_tap.py`
- Unit test suite (Tiers 1–3): `android/app/src/test/java/com/example/whispertoinput/**`

## Plan File Location

`thoughts/shared/plans/e2e-test-automation.md`
