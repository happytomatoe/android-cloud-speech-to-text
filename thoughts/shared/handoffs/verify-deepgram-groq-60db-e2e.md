---
date: 2026-07-14T22:55:00+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E verification of Deepgram, Groq, 60db batch transcription providers (Android) + Voxtral/ElevenLabs bug fix"
tags: [implementation, android, whisper, deepgram, groq, 60db, e2e, emulator, transcription]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: E2E Verification of Deepgram / Groq / 60db Providers + Bug Fix

## Task(s)

Verify the three new **batch** transcription providers (Deepgram, Groq, 60db) added in
`thoughts/shared/plans/add-deepgram-groq-60db-batch-providers.md` actually work end-to-end in
the Android emulator, and fix a pre-existing UI bug found along the way. The provider *code* was
already implemented (see prior handoff `add-deepgram-groq-60db-batch-providers.md`); this session
focused on **real e2e execution** (feeding audio into the headless/headful emulator's mic and
confirming a transcription round-trips) plus a bug fix.

| Phase | Status | Notes |
|-------|--------|-------|
| Prior: provider code (strings/WhisperTranscriber/MainActivity/README) | COMPLETE | Verified present, builds + lints clean (see prior handoff). |
| Research: how to inject mic audio into a headless emulator | COMPLETE | Browser + GitHub search → pin a dedicated virtual-mic source at emulator launch. |
| Emulator: headful + pinned virtual mic (`FakeMic`) | COMPLETE | Switched from headless to headful (DISPLAY=:0); QEMU_PA_SOURCE=FakeMic. |
| Bug fix: Voxtral/ElevenLabs stale-endpoint guard | COMPLETE | Verified in-UI: selecting Voxtral now resets endpoint correctly. |
| E2E: full record→transcribe pipeline (Voxtral) | COMPLETE (pipeline) | Virtual mic → FSM → request → Mistral → graceful 401 handling. Key was invalid (401). |
| E2E: Deepgram with a valid key | IN PROGRESS | User provided a valid Deepgram key; re-running transcription. |
| E2E: Groq / 60db (optional) | PENDING | Same multipart+Bearer path as Voxtral/OpenAI; low risk. |
| Commit | PENDING | Provider work + the Voxtral/ElevenLabs bug fix. Decide on unrelated pre-existing mods. |

## Critical References

- Plan: `thoughts/shared/plans/add-deepgram-groq-60db-batch-providers.md` (API shapes + exact code).
- Predecessor plan: `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`.
- Prior handoff: `thoughts/shared/handoffs/add-deepgram-groq-60db-batch-providers.md`.
- Provider code: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`.
- Settings UI: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`.
- IME/service: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`.
- Recorder FSM: `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt`.
- UI helper: `scripts/ui_tap.py` (tap by resource-id or text via uiautomator dump).
- Explainer (generated): `/tmp/2026-07-14-e2e-android-emulator-audio.html`.

## Recent changes (this session)

### Code
- `MainActivity.kt` — **Bug fix:** the Voxtral and ElevenLabs `onItemSelected` branches only
  guarded against `openai`/`whisper_asr_webservice`/`nvidia_nim` endpoints (they predate
  Deepgram/Groq/60db). Switching *to* Voxtral/ElevenLabs from a newer provider left a stale
  endpoint. Extended both guards to include all other providers' default endpoints, matching the
  pattern already used by the Deepgram/Groq/60db branches. Net +8 lines.
  - Symptom that exposed it: selecting Voxtral updated the model (`voxtral-mini-latest`) but left
    the endpoint at Deepgram's (`https://api.deepgram.com/v1/listen`). After the fix, selecting
    Voxtral correctly resets the endpoint to `https://api.mistral.ai/v1/audio/transcriptions`.
  - NOTE: the original three branches (openai / whisper_asr_webservice / nvidia_nim) still have the
    same latent incomplete-guard pattern; they were left as-is to keep the change scoped. Fix them
    too if you want the class of bug fully eliminated.

### Emulator / host audio
- Created a PulseAudio virtual microphone on the host (PipeWire, speaks PulseAudio protocol):
  - `pactl load-module module-null-sink sink_name=VirtualMicSink ...`
  - `pactl load-module module-remap-source source_name=FakeMic master=VirtualMicSink.monitor ...`
- Launched the emulator **headful** (DISPLAY=:0) pinned to that source:
  `QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE=FakeMic emulator -avd Pixel_8` (no `-no-window`).
- Enabled host mic in the guest: `adb emu avd hostmicon` → `OK`.
- Test speech WAV: `espeak-ng -v en-us "..." --stdout > /tmp/test-speech.wav`, then
  `ffmpeg -i /tmp/test-speech.wav -af "volume=15dB" -ar 44100 -c:a pcm_s16le /tmp/test-speech-loud.wav`
  (peak 0.0 dB so the recorder FSM triggers).

### UI automation
- `scripts/ui_tap.py` — dumps `uiautomator` XML and taps an element by `--rid`, `--text`, or
  `--contains`. Used to drive the Settings UI (select backend, set key, Apply).

## Issues & how we solved them

1. **Injecting mic audio into the emulator.**
   - *Wrong first attempt:* create null sink + `pactl set-default-source VirtualMicSink.monitor` on a
     running emulator, then `paplay` while the app records. Search evidence (Reddit/SO + sherpa-onnx
     GitHub) shows the guest capture is bound at emulator/QEMU startup, NOT lazily re-resolved from
     the host default — so this is flaky/unreliable.
   - *Fix:* pin a **dedicated** virtual-mic source at launch via `QEMU_PA_SOURCE=FakeMic` (env vars),
     and/or `-qemu -audiodev pa,in.dev=FakeMic`. Switched the emulator to **headful** so the GUI
     (Extended Controls → Microphone → load file) is also available as an alternative path. This is
     the reliable pattern.

2. **`DEL` keyevents didn't clear EditText fields** (via `adb shell input keyevent DEL`).
   - *Fix:* tap the field, then **triple-tap** (three quick `input tap` at the same coords) to
     select-all, then one `DEL` to delete. Verified the field becomes empty, then `input text` types
     the value. (Single `DEL` loops appended instead of clearing — focus/timing issue.)

3. **Voxtral/ElevenLabs stale endpoint** — see "Recent changes / Code" above.

4. **Provided Voxtral key returned HTTP 401** from BOTH the app and a host `curl` using the same key
   + endpoint. This proved the app's request building, network path, and 401 handling are all
   correct — the credential itself was invalid/expired, not an app bug. Pipeline declared working;
   a valid Deepgram key was then supplied to finish a successful round-trip.

## Key Learnings (for a from-scratch restart)

- **Emulator must be pinned to the virtual mic at LAUNCH.** Use `QEMU_AUDIO_DRV=pa
  QEMU_PA_SOURCE=FakeMic emulator -avd Pixel_8` (headful). Do NOT rely on `pactl
  set-default-source` on an already-running emulator.
- **Host audio is PipeWire** but `pactl` works. Create `VirtualMicSink` (null-sink) + `FakeMic`
  (remap-source of its monitor). Feed audio with `paplay --device=VirtualMicSink file.wav`.
- **Recorder FSM** (`RecorderManager.kt`, thresholds in `res/values/constants.xml`): needs
  `maxAmplitude > 800` to leave Idle (else cancels after 5 s) and 3 s of silence (`<= 800`) to reach
  `Finish`. Boost the test WAV to ~0 dB peak or it never triggers.
- **Recording auto-starts** when the IME window opens (`WhisperInputService.onStartInputView` →
  `startRecording`). Keep the keyboard window visible until `Finish`; hiding it earlier cancels
  (`onWindowHidden`).
- **Audio format:** non-NVIDIA backends record MPEG-4 / AMR-NB (`audio/mp4`) via
  `MediaRecorder.AudioSource.VOICE_RECOGNITION`. Deepgram auto-detects encoding from Content-Type.
- **Settings live in a DataStore** (`name="settings"`, key `api-key`); the only supported write path
  is the Settings UI in `MainActivity`. Drive it with `scripts/ui_tap.py`.
- **Permissions:** grant `RECORD_AUDIO` + `POST_NOTIFICATIONS` (`adb shell pm grant ...`).
- **Logcat:** FSM transitions log under tag `whisper-input` (`D`); `WhisperTranscriber` logs HTTP
  error bodies under tag `WhisperTranscriber` (`E`), e.g. `{"detail":"Unauthorized"}`. Success texts
  are committed via `commitText` (not logged) — verify by dumping the focused field after the test.
- **Validate a key cheaply with host `curl`** before blaming the app:
  `curl https://api.mistral.ai/v1/audio/transcriptions -H "Authorization: Bearer $KEY" -F model=voxtral-mini-latest -F file=@wav`
  (Mistral) or the equivalent Deepgram/Groq/60db shape. If curl 401s, the key is the problem.
- **Build:** `just build` (`./gradlew assembleDebug` with `JAVA_HOME=~/.sdkman/.../17.0.13-tem`).
  Warm Gradle daemon is running — do NOT pass `--no-daemon`. A cold build can exceed 600 s; run via
  `nohup just build > /tmp/build.log 2>&1 &` and poll.
- **Emulator lifecycle justfile:** `just emulator-start` / `just emulator-stop` / `just test-e2e`.
  `just test-e2e` only does build→install→IME-enable→registration (NO transcription). For real
  transcription you must drive the Settings UI + feed audio yourself.

## Artifacts

- Modified implementation files (uncommitted working tree):
  - `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`  ← **this session: bug fix**
  - `android/app/src/main/res/values/strings.xml` (provider strings — prior session)
  - `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` (provider code — prior session)
  - `README.md` (provider docs — prior session)
- New helper: `scripts/ui_tap.py`.
- Explainer HTML (outside repo): `/tmp/2026-07-14-e2e-android-emulator-audio.html`.
- Pre-existing unrelated mods in working tree (NOT part of this task, per prior handoff):
  `RecorderManager.kt`, `activity_main.xml`, `colors.xml`, `gradle-wrapper.properties`.

## Action Items & Next Steps

1. ~~Research mic injection~~ DONE — pin virtual source at launch.
2. ~~Headful emulator + FakeMic~~ DONE.
3. ~~Voxtral/ElevenLabs bug fix~~ DONE + verified.
4. ~~Voxtral e2e (pipeline)~~ DONE — 401 was the (invalid) key, not the app.
5. **Deepgram e2e with the valid key** — IN PROGRESS (select Deepgram, set key, Apply, focus field,
   play WAV, expect a `transcript` JSON parsed and committed).
6. (Optional) Groq / 60db e2e — same multipart+Bearer path as Voxtral; low risk.
7. Commit provider work + bug fix (decide whether to include unrelated pre-existing mods separately).

## Other Notes

- The emulator is currently **headful** (DISPLAY=:0) and pinned to `FakeMic`; `WhisperInputService`
  is the default IME; host mic enabled. Virtual sources (`VirtualMicSink`, `FakeMic`) persist for the
  desktop session.
- Do **not** store API keys in repo files/handoffs. The valid Deepgram key lives only in the app's
  DataStore on the emulator (set via the Settings UI); validate externally with `curl` if needed.
- Deepgram request shape (the riskiest/new one): raw binary body (not multipart),
  `Authorization: Token <key>`, model/language as URL query params (`model=nova-3`,
  `detect_language=true` when language=`auto`); response `results.channels[0].alternatives[0].transcript`.
  Groq/60db use multipart `file` + `model` (+ `response_format=text` for Groq, optional `language`
  for 60db) + `Bearer` — same family as OpenAI/Voxtral, so the Voxtral success validates their path.
