---
date: 2026-07-14T23:45:00-04:00
researcher: pi-agent
git_commit: $(git rev-parse HEAD 2>/dev/null || echo "unknown")
branch: main
repository: whisper-to-input
topic: "Voxtral and ElevenLabs Batch Transcription Providers for Android"
tags: [implementation, android, whisper, elevenlabs, voxtral]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: Voxtral + ElevenLabs Batch Transcription Providers

## Task(s)
Implement two new batch transcription providers (Voxtral/Mistral and ElevenLabs Scribe) for the Whisper To Input Android keyboard app, per plan `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`.

| Phase | Status | Notes |
|-------|--------|-------|
| 1. String Resources | ✅ Complete | `strings.xml` — provider names, defaults, backend array, description |
| 2. WhisperTranscriber.kt | ✅ Complete | Request building (form fields, headers), response parsing (Voxtral `{"text":...}`, ElevenLabs `{"text":...}`), bug fixes (Content-Type, URL query separation) |
| 3. MainActivity.kt | ✅ Complete | Dropdown wiring, default value population, initialization list |
| 4. README.md | ✅ Complete | Config examples + provider docs |
| 5. E2E Testing | 🟡 In Progress | **Curl test passes** (`"Hello, what's going on?"`). On-device pipeline proven (earlier session committed real ElevenLabs transcription). **Blocker**: Blind mic-tap to start recording on emulator is unreliable — space bar works, mic button at (x≈550, y≈1740–1780) doesn't trigger `onStartRecording` (no audio file, no label change). User agreed to play `test-audio.wav` via Extended Controls → Microphone → Load WAV. |

## Critical References
- Plan: `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`
- Android app root: `android/`
- Settings strings: `android/app/src/main/res/values/strings.xml:43-59`
- Transcriber: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:187-274`
- Settings wiring: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:259-314`
- Keyboard layout: `android/app/src/main/res/layout/keyboard_view.xml:70-100`
- Test audio: `test-sources/test-audio.wav` (5s, "Hello? What's going on?")

## Recent Changes
- `android/app/src/main/res/values/strings.xml` — added Voxtral & ElevenLabs strings + backend array + description
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` — `buildWhisperRequest()` with provider-specific form fields, headers, URL building; response parsing for both JSON formats; fixed Content-Type & Whisper ASR query-param bugs
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` — `SettingStringDropdown.onItemSelected` cases for Voxtral/ElevenLabs; initialization list updated
- `README.md` — installation examples + provider docs for both
- `test-sources/test-audio.wav` — test file for curl/device verification
- Verified: `./gradlew assembleDebug` ✅, `./gradlew lint` ✅, ElevenLabs curl returns `"Hello, what's going on?"` with exact request format

## Learnings
- **WhisperTranscriber bugs fixed**: (1) Manual `Content-Type: multipart/form-data` header (no boundary) conflicted with OkHttp's auto-generated boundary → removed. (2) OpenAI endpoint incorrectly received Whisper ASR query params → separated URL building: only Whisper ASR gets query params. (3) Voxtral response `{"text":...}` wasn't parsed → added JSON extraction.
- **ElevenLabs API**: Uses `xi-api-key` header (not Bearer), form fields `file` + `model_id` + `language_code`. Response: `{"text": "...", "language_code": "eng", ...}`.
- **Voxtral API**: Bearer auth, form fields `file` + `model` + optional `language`. Response: `{"text": "..."}`.
- **Keyboard mic tap issue**: Space bar at y≈2190 works; mic icon at y≈1740–1780, x≈520–580 does not trigger `onStartRecording` (no audio file, label stays "Whisper To Input"). Screenshots confirm icon at (x≈520–580, y≈1735–1780). Permission granted, no permission dialog. Root cause unknown — possible touch-target mismatch or IME window offset. User coordinated audio injection via emulator Extended Controls.

## Artifacts
- Plan: `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`
- Modified: `strings.xml`, `WhisperTranscriber.kt`, `MainActivity.kt`, `README.md`
- Test audio: `test-sources/test-audio.wav`
- Curl verification command (from plan Step 7):
  ```bash
  curl -s -X POST 'https://api.elevenlabs.io/v1/speech-to-text' \
    -H 'xi-api-key: sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887' \
    -F 'file=@test-sources/test-audio.wav' -F 'model_id=scribe_v1' -F 'language_code=en' | jq -r '.text'
  # → "Hello, what's going on?"
  ```

## Action Items & Next Steps
1. **Coordinate one more clean on-device attempt** (user selected Option B):
   - I tap mic at precisely measured coordinate (screenshot + ASCII analysis → x≈550, y≈1750) **and immediately poll for `recorded.m4a` growth** to confirm recording started.
   - User plays `test-audio.wav` via Extended Controls → Microphone → Load WAV → Play **during the recording window** (~5s).
   - I stop recording (same tap), wait for transcription, dump compose field, verify appended text ≈ `"Hello? What's going on?"`.
   - Screenshot result.
2. If mic tap still fails: fall back to documenting the curl test as authoritative proof, note on-device pipeline proven by earlier session, and close the E2E as "requires user coordination for audio injection."

## Other Notes
- Emulator: `emulator-5554` (running, visible). ADB: `/var/home/l/Android/Sdk/platform-tools/adb`.
- API keys: ElevenLabs `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887` (ElevenLabs); Voxtral needs Mistral key (not tested on-device).
- Keyboard layout: `keyboard_view.xml` — mic is `btn_mic` inside `btn_mic_frame` (140dp), centered. Label is `label_status` (TextView, "Whisper To Input" / "Recording…").
- Showboat docs location per plan: `thoughts/shared/showboat/elevenlabs-e2e.md` (not yet created).