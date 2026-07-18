---
date: 2026-07-14T10:50:56+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "Voice IME FSM Transcription Trigger — Fix Done, E2E Evidence In Progress"
tags: [android, ime, voice-input, elevenlabs-scribe, e2e-testing, fsm, transcription]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: Voice IME Transcription Trigger — Proper FSM Fix Committed, E2E Evidence Remaining

## Task(s)

| Task | Status | Notes |
|------|--------|-------|
| Emulator boot + APK install + IME registration | ✅ Done | Pixel_8 boots ~34s with `-gpu host`; APK installs; IME enables/selects via `adb shell ime` |
| Audio injection mechanism (ALSA file-backed capture) | ✅ Validated | `~/.asoundrc` reads `/tmp/reference.wav` as default capture; no PulseAudio |
| Reference audio + ground truth | ✅ Ready | `flite -t "Hello, what is going on?"` → Scribe transcribes exact text |
| **Proper fix for transcription trigger (FSM)** | ✅ **Committed** | `RecorderManager` FSM + `WhisperInputService` wiring committed in `f3d7c4d` + `1e2b859` |
| E2E run + ShowBoat evidence | 🚧 **In progress** | Emulator booted, APK installed, IME enabled+selected, Settings search field focused. Need to inject audio, observe FSM→transcription, capture on-screen text + logs |

The previous handoff (`voice-ime-transcription-trigger-blocked.md`) framed `stopRecordingAndTranscribe()` as dead code needing a proper architectural fix. **That fix is now implemented and committed.** The refactor from keyboard-IME to voice-only IME plus the FSM lifecycle is complete on branch `feature/voice-only-ime`. Remaining work is purely the E2E verification + browser-viewable ShowBoat evidence (the original paused goal).

## Critical References

- `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt` — FSM engine (`RecorderState` enum: Idle/Speaking/Finish/Cancelled; `updateFsm()` polled every 150ms).
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:160` — `handleRecorderStateChange()` calls `stopRecordingAndTranscribe()` on `RecorderState.Finish`.
- `android/app/src/main/res/values/constants.xml` — FSM thresholds: `recorder_fsm_idle_speaking_threshold=800`, `recorder_fsm_idle_cancel_time=5000`, `recorder_fsm_speaking_finish_threshold=800`, `recorder_fsm_speaking_finish_time=3000`, `recorder_amplitude_report_period=150`.

## Recent changes

All core fix work is committed (3 commits on `feature/voice-only-ime`):
- `c767337 feat: Make IME voice-only by removing keyboard UI` — `WhisperInputService.kt` auto-starts recording in `onStartInputView()`; `method.xml` voice-only subtype; `strings.xml` `voice_input_label`.
- `f3d7c4d feat: Add voice-activity FSM to RecorderManager` — adds `RecorderState` enum, `updateFsm()` driven by `MediaRecorder.maxAmplitude`, `setOnRecorderStateChange` callback, `getCurrentState()`.
- `1e2b859 fix: Wire voice-activity FSM to stop and transcribe in the IME` — registers the state callback in `onCreateInputView()` and calls `stopRecordingAndTranscribe()` on `Finish`; updates `onWindowHidden()` to not cancel a transcription already in flight.

Uncommitted (working tree): `RecorderManager.kt` — only a 4-insertion/10-deletion diff adding `Log.d("whisper-input", ...)` lines to the FSM transitions for E2E logcat observation. This debug logging should either be kept for the E2E run or stripped before final commit.

## Learnings

- **`adb` is NOT in PATH** in this environment. Use absolute path `/var/home/l/Android/Sdk/platform-tools/adb`. Emulator at `/var/home/l/Android/Sdk/emulator/emulator`. Java 17 at `~/.sdkman/candidates/java/17.0.13-tem` (required; project fails on newer JDKs).
- **The FSM is the proper architectural fix** (not a silence-threshold hotfix). It uses the thresholds already declared in `constants.xml` (which were previously unused dead config). This matches production VAD behavior (Google/server VAD): distinguish Idle→Speaking on amplitude>800, and Speaking→Finish only after a sustained 3s silence, so natural mid-sentence pauses don't cut off the user.
- **`onWindowHidden()` was the original "discard" bug**: it called `whisperTranscriber.stop()` (cancels) on every hide. Now it checks `getCurrentState() != Finish` before cancelling, so a transcription triggered by `Finish` survives window hide.
- **Audio injection**: `~/.asoundrc` uses the ALSA `file`/capfile plugin to feed `/tmp/reference.wav` (flite-generated, 8kHz mono, "Hello, what is going on?") as the emulator mic. Validate with `arecord`/RMS check (real speech, not silence).
- **Text field for test**: Settings search bar works as a reliable editable field. UI bounds from `uiautomator dump`: search bar at `[42,595][1038,732]`; tap center `540 663` to focus and activate the voice IME.
- **ShowBoat format**: markdown with `<!-- showboat-id -->`, `output` code blocks, `{image}` blocks. Existing example: `thoughts/shared/showboat/elevenlabs-e2e.md` (curl-only API proof — needs the real E2E section added).

## Artifacts

- `thoughts/shared/handoffs/voice-ime-transcription-trigger-blocked.md` — prior handoff (premise now resolved).
- `/tmp/2026-07-14-explanation-voice-ime-fsm.html` — interactive Kleppmann-style explanation of the FSM fix (background, intuition, code walkthrough, quiz). Good for the ShowBoat narrative.
- `/tmp/reference.wav` — flite reference audio (576268 bytes, valid speech).
- `~/.asoundrc` — ALSA file-capture config pointing at `/tmp/reference.wav`.
- `thoughts/shared/showboat/elevenlabs-e2e.md` — ShowBoat doc to extend with E2E evidence.
- `justfile` — build/lifecycle commands (build works; start/test-e2e depend on emulator).

## Action Items & Next Steps

1. **Finish the E2E run** (resume in a fresh session with emulator booted):
   - Ensure `/tmp/reference.wav` exists (regenerate with `flite -t "Hello, what is going on?" -o /tmp/reference.wav` if missing).
   - Focus a text field (Settings search `input tap 540 663`), confirm voice IME auto-records.
   - Inject audio via ALSA capture; watch `adb logcat -v time | grep whisper-input` for `Idle -> Speaking` then `Speaking -> Finish` then `Recorder state changed to: Finish`.
   - After `Finish`, the app calls `stopRecordingAndTranscribe()` → ElevenLabs Scribe → `commitText`. Verify "Hello, what is going on?" appears in the field.
2. **Capture ShowBoat evidence**: screenshot of the text field with transcribed text; logcat snippet showing FSM transitions; the curl-ground-truth from the existing doc. Assemble into `thoughts/shared/showboat/elevenlabs-e2e.md`.
3. **Clean up debug logging**: decide whether to keep the `Log.d` lines in `RecorderManager.kt` or strip them before the final commit.
4. **Commit**: once E2E evidence is captured, commit everything on `feature/voice-only-ime` with a conventional commit message.

## Other Notes

- **Emulator tooling quirk**: the pi-agent bash tool kills backgrounded processes at timeout and discards output. Run the emulator foreground with `timeout` and do E2E work in a background subshell that logs to files; kill the emulator before the script ends.
- **ElevenLabs API key** (`sk_2b1e3c10...`): works for Scribe (STT); does NOT work for TTS (missing `text_to_speech`). Use `flite` for local TTS.
- **AVD**: Pixel_8, API 34, `default` AOSP (no GMS). No Chrome/Gboard — the Whisper voice IME works standalone on any text field.
- **Goal state**: the original pi goal (`mrkc22gi-4iwjup`, paused) is to *produce browser-viewable ShowBoat E2E evidence*. The code fix it depended on is done; only the evidence-gathering remains.
