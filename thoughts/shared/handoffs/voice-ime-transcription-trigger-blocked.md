---
date: 2026-07-14T10:15:00+02:00
researcher: pi-agent
git_commit: 220e1dc9d12b59e2d0e7a6cbbba545a1859cb727
branch: master
repository: whisper-to-input
topic: "Voice IME E2E Testing: Transcription Trigger Missing — Hotfix vs Proper Fix"
tags: [android, ime, voice-input, elevenlabs-scribe, e2e-testing, transcription, blocker]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: research_findings
---

# Handoff: Voice IME Transcription Trigger Missing — Need Proper Fix, Not Hotfix

## Task(s)

| Task | Status | Notes |
|------|--------|-------|
| Emulator E2E blocker investigation | ✅ Resolved | KVM works (handoff's "KVM broken" was a false alarm from wrong ioctl number) |
| Emulator boot + APK install + IME registration | ✅ Verified | Pixel_8 boots in ~34s with hardware accel; APK installs; IME registers |
| Audio injection mechanism | ✅ Validated | ALSA file-backed capture PCM delivers speech to emulator mic (no PulseAudio needed) |
| Reference audio + ground truth | ✅ Ready | flite-generated WAV ("Hello, what is going on?"); Scribe confirms exact transcription |
| Transcription trigger investigation | ✅ Root cause found | `stopRecordingAndTranscribe()` is dead code — never called anywhere |
| Proper fix for transcription trigger | 🚫 **BLOCKED** | Silence/VAD detection is a hotfix, not a proper fix — user wants a proper architectural solution |

## Critical Issue: The Missing Transcription Trigger

### What's broken

The voice-only IME refactor (uncommitted changes on `master`) removed the keyboard UI and added auto-**start** recording in `onStartInputView()`, but **never added an equivalent stop/transcribe trigger**:

- `WhisperInputService.kt:138` — `stopRecordingAndTranscribe()` is defined but **zero callers** exist
- `WhisperInputService.kt:115-125` — `onStartInputView()` auto-starts recording via `startRecording()`
- `WhisperInputService.kt:156-162` — `onWindowHidden()` stops recording but **discards** audio (calls `whisperTranscriber.stop()`, not `startAsync`)
- `RecorderManager.kt:105-111` — amplitude monitor already polls `getMaxAmplitude()` via coroutine, but `WhisperInputService` never registers the callback

### Why silence/VAD is a hotfix, not a proper fix

The user explicitly rejected silence-based auto-stop as a band-aid. Their reasoning:

1. **It patches a broken refactor** rather than properly implementing the voice-only IME lifecycle
2. **The original app was designed for manual control** (mic button start/stop). The refactor to auto-start without proper lifecycle management is an incomplete architectural change
3. **A real voice IME needs a proper state machine**: idle → listening → processing → result → idle. Silence detection is just one possible transition trigger, not the whole solution
4. **The fix should be architectural**, not a threshold tuning exercise

### What a proper fix looks like (unexplored)

The next agent should investigate:

1. **How does Google Voice Typing / Samsung Voice Input actually work?** — What's the lifecycle? What triggers stop? Is it just silence, or a combination of factors?
2. **What does the original `WhisperKeyboard.onButtonMicClick()` do?** — Read `WhisperKeyboard.kt` to understand the original start/stop/transcribe flow. The refactor may have missed something important.
3. **Is `SpeechRecognizer` actually relevant?** — Even though the app uses custom STT, Android's `SpeechRecognizer` provides a proven lifecycle pattern (RecognitionListener with onBeginningOfSpeech, onEndOfSpeech, onResults). Could the app use `SpeechRecognizer` for lifecycle management while still sending audio to ElevenLabs?
4. **Should the app expose a proper voice IME lifecycle?** — Android has `InputMethodService.switchInputMethod()`, `commitText()`, and various lifecycle callbacks. Is the voice-only IME using these correctly?

## Previous Handoff Findings (Validated)

### KVM blocker was false alarm

The previous handoff (`emulator-e2e-testing-container-blocked.md`) claimed KVM ioctl fails with "Invalid argument." This was caused by using **the wrong ioctl number** (`0xae08` instead of `0xAE01` for KVM_CREATE_VM). When tested correctly:

- `KVM_GET_API_VERSION` → 12 (valid)
- `KVM_CREATE_VM` → **OK, VMX available, hardware accel works**

The emulator boots in ~34s with `-gpu host` (Vulkan on Intel UHD 620). The "KVM broken" conclusion was incorrect.

### Test audio file is silent/corrupted

`test-sources/test-audio.wav` is **untracked** and contains only zeros (maxabs=0, rms=0.0). The handoff's curl evidence ("Hello? What's going on?") was from a different file state. A valid reference was generated with `flite`:

```bash
flite -t "Hello, what is going on?" -o /tmp/reference.wav
# → Scribe transcribes: "Hello, what is going on?" (exact match)
```

### Audio injection works without PulseAudio

ALSA file-backed capture PCM delivers speech to the emulator mic:

```
~/.asoundrc — capfile plugin reads /tmp/reference.wav as default capture
emulator -avd Pixel_8 -allow-host-audio -audio alsa
App records → gets speech → transcribes
```

Validated with `arecord`: RMS 17546 (real speech), not silence.

### Build works with Java 17

```bash
export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
cd android && ./gradlew assembleDebug
```

APK at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Uncommitted Source Changes (Voice-Only Refactor)

Three files modified relative to HEAD (`220e1dc`):

- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — Keyboard UI removed, auto-start recording added, `stopRecordingAndTranscribe()` dead code
- `android/app/src/main/res/xml/method.xml` — Voice subtype only, no keyboard subtype
- `android/app/src/main/res/values/strings.xml` — Added `voice_input_label`

The refactor is **incomplete**: auto-start works, auto-stop/transcribe is missing.

## Artifacts

- `thoughts/shared/showboat/elevenlabs-e2e.md` — Existing ShowBoat doc (curl API proof only)
- `/tmp/2026-07-14-explanation-missing-transcription-trigger.html` — Interactive HTML explaining the root cause, three fix options, and quiz
- `/tmp/reference.wav` — flite-generated reference audio (8kHz mono, "Hello, what is going on?")
- `~/.asoundrc` — ALSA file-capture config for audio injection (currently points to `/tmp/reference.wav`)
- `thoughts/shared/plans/voice-input-method.md` — Voice input implementation plan (describes the refactor)
- `justfile` — Build/lifecycle commands (build works; start/test-e2e depend on emulator)

## Action Items & Next Steps

1. **Investigate the proper voice IME lifecycle** — Read the original `WhisperKeyboard.kt` (the keyboard version that worked). Understand what `onButtonMicClick()` did and what the original start → record → stop → transcribe flow was. The refactor may have dropped something important.
2. **Research how production voice IMEs work** — Google Voice Typing, Samsung Voice Input. What's the lifecycle state machine? What triggers transitions? Is silence the only trigger, or is there more (e.g., endpoint detection, partial results)?
3. **Decide on proper architecture** — Before writing any code, define the voice-only IME lifecycle: idle → recording → processing → result. What triggers each transition? This should be an architectural decision, not a hotfix.
4. **Implement the proper fix** — Once the lifecycle is defined, implement it in `WhisperInputService.kt`. This likely involves more than just silence detection — it may need a state machine, proper error handling, retry logic, etc.
5. **Complete the E2E test** — Once the transcription trigger works: boot emulator, install APK, enable IME, focus text field, inject audio via ALSA capture, verify transcribed text on screen, produce ShowBoat evidence.
6. **Commit the changes** — The voice-only refactor is uncommitted. Once the fix is in, commit everything with a conventional commit message.

## Other Notes

- **Emulator tooling quirk**: The pi-agent bash tool kills backgrounded processes at timeout and discards output. To capture emulator output, run the emulator **foreground** with `timeout` and do E2E work in a background subshell that logs to files. Kill the emulator before the script ends.
- **ElevenLabs API key** (`sk_2b1e3c10...`): Works for Scribe (STT), does NOT work for TTS (missing `text_to_speech` permission). Use `flite` for local TTS instead.
- **AVD**: Pixel_8, API 34, `default` system image (AOSP, no GMS). No Chrome, no Gboard. The Whisper voice IME works standalone on any text field.
- **ShowBoat format**: Markdown with `<!-- showboat-id -->`, `output` code blocks, `{image}` code blocks for screenshots. See `thoughts/shared/showboat/elevenlabs-e2e.md` for example.
- **Emulator path**: `/var/home/l/Android/Sdk/emulator/emulator`
- **ADB path**: `/var/home/l/Android/Sdk/platform-tools/adb`
- **Java 17**: `~/.sdkman/candidates/java/17.0.13-tem`
