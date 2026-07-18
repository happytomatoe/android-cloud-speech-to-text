---
date: 2026-07-17T21:45:00+02:00
git_commit: 27fa164851a9934f223a7f0d9e08524473498a40
branch: feat/api-key-links
repository: whisper-to-input
topic: "RecognitionService + Postprocessing Removal + Button Regression Discovery"
tags: [recognition-service, postprocessing, imes, keyboard, tdd, regression]

---

# Handoff: RecognitionService added, postprocessing removed, button regression discovered

## Task(s)
- **Completed**: Added `WhisperRecognitionService` (system-wide voice input provider) so Whisper appears in Android's "Voice Input" settings alongside Google/Samsung voice typing.
- **Completed**: Removed Chinese postprocessing conversion feature (Traditional/Simplified Chinese conversion) and its `quick-transfer-core` dependency.
- **Completed**: Ran E2E test — build OK, APK installed, IME enabled, RecognitionService registered and set as active voice input.
- **Completed**: Released v0.6.3 from `feat/api-key-links` (patch bump; v0.6.2 already existed from main branch PR #5).
- **Discovered (NOT FIXED)**: Silent regression — 7 of 8 keyboard buttons (Space, Enter, Cancel, Retry, Backspace, Switch IME, Settings) have no click listeners. Only the mic button works. Root cause: commit `c767337` ("Make IME voice-only") replaced `whisperKeyboard.setup()` (which wired all 8 buttons) with inline code that only wires `btn_mic`. The layout still renders all 8 buttons but 7 are dead.

## Critical References
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt:118` — current `onCreateInputView()` only wires `btn_mic`
- `android/app/src/main/java/com/example/whispertoinput/keyboard/WhisperKeyboard.kt:44` — dead wiring class that used to wire all 8 buttons
- `android/app/src/main/res/layout/keyboard_view.xml` — layout with 8 buttons, only `btn_mic` has a listener
- `android/app/src/main/AndroidManifest.xml` — declares `WhisperRecognitionService` with `android.speech.RecognitionService`
- `android/app/build.gradle.kts:57` — `quick-transfer-core` dependency removed

## Recent changes
- `android/app/build.gradle.kts:57` removed `com.github.liuyueyi:quick-transfer-core:0.2.13`
- `android/app/src/main/AndroidManifest.xml` added `WhisperRecognitionService` (RecognitionService)
- `android/app/src/main/java/.../WhisperRecognitionService.kt` new — system-wide voice recognition
- `android/app/src/main/java/.../VoiceRecognitionSettingsActivity.kt` new
- `android/app/src/main/res/xml/voice_recognition_service_meta.xml` new
- `android/app/src/main/java/.../WhisperTranscriber.kt` removed postprocessing logic + `ChineseUtils`
- `android/app/src/main/java/.../WhisperInputService.kt` removed `ChineseUtils` preload
- `android/app/src/main/java/.../MainActivity.kt` removed `POSTPROCESSING` pref + dropdown
- `android/app/src/main/res/values/strings.xml` removed postprocessing strings
- `android/app/src/main/res/layout/activity_main.xml` removed postprocessing UI
- `android/app/build.gradle.kts:17-18` version bumped to 0.6.2 / code 8 (base for next patch release)

## Learnings
- **Regression root cause**: The "voice-only" refactor (c767337) replaced `whisperKeyboard.setup()` (which wired all 8 buttons via callbacks) with inline code wiring only `btn_mic`. The layout was later restored but the other 7 buttons were never rewired. `WhisperKeyboard` class is now dead code.
- **Version collision**: v0.6.2 was already cut from `main` (PR #5) and didn't contain our feature work. Had to bump base version to 0.6.2 so the release workflow would compute the next free patch (0.6.3).
- **Current state**: v0.6.3 released from `feat/api-key-links` at commit `27fa164` — includes RecognitionService + postprocessing removal. Keyboard buttons (except mic) are broken.
- **Button wiring history**: Pre-c767337 `onCreateInputView()` called `whisperKeyboard.setup(layoutInflater, shouldOfferImeSwitch, {onStartRecording}, {onCancelRecording}, {onStartTranscription}, {onCancelTranscription}, {onDeleteText}, {onEnter}, {onSpaceBar}, {onSwitchIme}, {onOpenSettings}, {shouldShowRetry})` — that one call wired everything. Current code only wires `btn_mic` via `toggleRecording()`.

## Artifacts
- `/tmp/2026-07-17-explanation-voice-ime-orphaned-buttons.html` — interactive explanation with diff and quiz
- `android/app/src/main/java/.../WhisperRecognitionService.kt` — new RecognitionService implementation
- `android/app/src/main/res/xml/voice_recognition_service_meta.xml` — metadata for RecognitionService
- `android/app/src/main/AndroidManifest.xml` — declares RecognitionService + settings activity
- `android/app/build.gradle.kts` — version 0.6.2, quick-transfer removed
- GitHub Release v0.6.3: https://github.com/happytomatoe/android-cloud-speech-to-text/releases/tag/v0.6.3

## Action Items & Next Steps
1. **Fix the 7 broken buttons** — wire them in `onCreateInputView()` by either:
   - Restoring `whisperKeyboard.setup()` call with callbacks mapped to current `toggleRecording()` / `transcriptionCallback()` logic, OR
   - Wiring each button inline (mic uses `toggleRecording()`, others need: Space/Enter → commit " "/\n + stop if recording; Cancel → cancel recording/transcription; Retry → re-transcribe; Backspace → delete char; Switch IME → `switchToPreviousInputMethod()`; Settings → launch MainActivity).
2. **Add TDD regression guard** — write a test that fails now (buttons unwired) and passes after fix. Options:
   - Instrumented test (androidTest, no new dep) asserting `WhisperKeyboard.setup()` wires all 8 buttons.
   - Robolectric JVM unit test (adds `robolectric` dep) asserting `onCreateInputView()` returns a view where all 8 buttons `hasOnClickListeners() == true`.
3. Consider opening PR to merge `feat/api-key-links` → `main` once buttons are fixed, so future releases flow through main.

## Other Notes
- Emulator is running (`emulator-5554`), APK installed, IME enabled, RecognitionService active (`voice_recognition_service = com.example.whispertoinput/.WhisperRecognitionService`).
- Screenshots captured: `/tmp/01_voice_typing_method.png` (settings search shows "Whisper Voice Input"), `/tmp/02_settings_no_postprocessing.png` (app settings without postprocessing), `/tmp/03_settings_lower.png` (lower settings section).
- The `WhisperKeyboard` class is now dead but still in source tree — removing it would be a cleanup (not urgent).
- `scripts/__pycache__/write_datastore.cpython-314.pyc`, `.emulator.pid`, `.idea/`, `.pi/`, `e2e_test.log`, `skills-lock.json`, `thoughts/` are untracked/noise — do not commit.