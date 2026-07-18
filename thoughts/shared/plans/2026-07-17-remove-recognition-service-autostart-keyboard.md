# Remove System Voice-Input (RecognitionService), Keep Keyboard + Auto-Start Recording

## Overview

Per decision: **drop the system-wide `RecognitionService` ("voice input type") entirely** (it is unreachable on a real phone without a one-time `adb pm grant WRITE_SECURE_SETTINGS`, which we chose to avoid), **keep only the IME keyboard**, and **start recording automatically when the Whisper keyboard becomes active**.

The desired end state is: a normal install shows the app icon; enabling the keyboard in *Settings → On-screen keyboard* is the only setup needed (no ADB); and whenever the user switches to the Whisper keyboard, recording begins on its own (subject to the existing `AUTO_RECORDING_START` toggle, which already defaults to `true`).

## Current State Analysis

The app (`com.example.whispertoinput`) currently registers **two** system components:

1. **IME keyboard** — `WhisperInputService : InputMethodService`
   (`android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`), declared with `BIND_INPUT_METHOD` in `AndroidManifest.xml`, subtype in `res/xml/method.xml`. This stays.
2. **RecognitionService** — `WhisperRecognitionService : RecognitionService`
   (`.../WhisperRecognitionService.kt`), declared with an `android.speech.RecognitionService` intent-filter + `res/xml/voice_recognition_service_meta.xml`, plus a `VoiceRecognitionSettingsActivity` (`.../VoiceRecognitionSettingsActivity.kt`) wired as its settings activity. **All of this is removed.**

Confirmed references to delete (from `rg`):
- `android/app/src/main/java/com/example/whispertoinput/WhisperRecognitionService.kt`
- `android/app/src/main/java/com/example/whispertoinput/VoiceRecognitionSettingsActivity.kt`
- `android/app/src/main/res/xml/voice_recognition_service_meta.xml`
- `android/app/src/test/java/com/example/whispertoinput/WhisperRecognitionServiceTest.kt`
- `android/app/src/test/java/com/example/whispertoinput/VoiceRecognitionSettingsActivityTest.kt`
- Manifest `<service android:name=".WhisperRecognitionService" ...>` block and `<activity android:name=".VoiceRecognitionSettingsActivity" ...>` block in `AndroidManifest.xml`.
- A `settings voice_recognition_service` line that currently exists only in pi's own goal state / historical docs — **not** in app code.

### Key Discoveries
- `AUTO_RECORDING_START` already exists and **defaults to `true`** (`MainActivity.kt:439`, `SettingDropdown(..., defaultValue = true)`), but `WhisperInputService.onStartInputView()` explicitly does **not** read it ("Don't auto-start — wait for mic button tap"). So the toggle is currently dead.
- `WhisperInputService` already has a working `toggleRecordingNormal()` start path (permission check → `recorderManager.start(...)` → `updateMicUI(true)`). Auto-start reuses that exact start branch.
- `WhisperTranscriber` and `RecorderManager` are shared by both services; they stay (the keyboard uses them).
- `method.xml` subtype `android:imeSubtypeMode="voice"` is part of the **keyboard** — keep it.

## Desired End State

- App builds and runs with **no** `RecognitionService` / `VoiceRecognitionSettingsActivity` anywhere in `android/`.
- On a real phone: install → open app → configure backend → enable "Whisper Input" in *Settings → On-screen keyboard* → switch to it → **recording starts automatically**.
- `AUTO_RECORDING_START` toggle in `MainActivity` continues to control this (on by default).

### Verification of end state
- `just build` (release) and `./gradlew assembleDebug` both succeed; no compile errors referencing deleted classes.
- On emulator (or phone): set Whisper as current IME, focus a text field → mic shows `mic_pressed` / status "Recording" without tapping.
- Toggling `AUTO_RECORDING_START` off in app settings → switching to keyboard does **not** auto-start.

## What We're NOT Doing

- **No** `WRITE_SECURE_SETTINGS`, no ADB step, no system-voice-input registration.
- **Not** touching `.pi/` goal files or `thoughts/shared/plans/*.md` / `thoughts/shared/handoffs/*.md` historical docs (they mention the old service; left as history).
- **Not** changing the IME keyboard UI/behavior beyond auto-start.
- **Not** removing `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` permissions (left as-is to keep the change minimal; can be cleaned later if unused).
- **Not** adding new backends or changing transcription.

## Implementation Approach

Two phases: (1) surgical removal of the RecognitionService surface, (2) wire auto-start into `onStartInputView`. Keep the IME as the single supported voice-typing path.

## Phase 1: Remove the RecognitionService ("voice input type")

### Overview
Delete the system voice-input component and its manifest/resource/test scaffolding so the app only exposes the keyboard.

### Changes Required:

#### 1. Delete source + resource + test files
Remove these files entirely:
- `android/app/src/main/java/com/example/whispertoinput/WhisperRecognitionService.kt`
- `android/app/src/main/java/com/example/whispertoinput/VoiceRecognitionSettingsActivity.kt`
- `android/app/src/main/res/xml/voice_recognition_service_meta.xml`
- `android/app/src/test/java/com/example/whispertoinput/WhisperRecognitionServiceTest.kt`
- `android/app/src/test/java/com/example/whispertoinput/VoiceRecognitionSettingsActivityTest.kt`

#### 2. `AndroidManifest.xml`
Remove the RecognitionService `<service>` block:
```xml
<!-- DELETE THIS BLOCK -->
<service android:name=".WhisperRecognitionService"
    android:label="Whisper Voice Input"
    android:exported="true"
    android:foregroundServiceType="microphone"
    android:permission="android.permission.RECORD_AUDIO">
    <intent-filter>
        <action android:name="android.speech.RecognitionService" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="android.speech"
        android:resource="@xml/voice_recognition_service_meta" />
</service>
```
Remove the settings activity:
```xml
<!-- DELETE THIS LINE -->
<activity android:name=".VoiceRecognitionSettingsActivity" android:exported="true" />
```
Leave `WhisperInputService` and `MainActivity` untouched.

#### 3. `README.md` (scan only)
Grep for `RecognitionService` / "voice input" / `voice_recognition_service`. Remove any step that tells users to select Whisper as system voice input. Keep the existing "Enable the keyboard in System Settings → Languages & Input → On-screen keyboard / Select Whisper Input as your input method" instructions — those are still correct.

### Success Criteria:

#### Automated Verification:
- [ ] `cd android && ./gradlew assembleDebug` succeeds (no unresolved references to deleted classes).
- [ ] `./gradlew testDebugUnitTest` passes (deleted test classes no longer compiled).
- [ ] `rg -l "WhisperRecognitionService|VoiceRecognitionSettingsActivity|voice_recognition_service" android/` returns nothing.

#### Manual Verification:
- [ ] App installs; icon appears in launcher.
- [ ] *Settings → On-screen keyboard* lists "Whisper Input" (no "Whisper Voice Input" recognition entry).
- [ ] No crash when opening the app / settings.

**Implementation Note**: After Phase 1 builds clean, pause for a quick manual confirm that the keyboard still enables and the app opens, before Phase 2.

---

## Phase 2: Auto-start recording when the keyboard becomes active

### Overview
Honor the existing `AUTO_RECORDING_START` preference inside `WhisperInputService.onStartInputView()` so switching to the Whisper keyboard begins recording without a tap.

### Changes Required:

#### 1. `WhisperInputService.kt` — `onStartInputView`
Replace the no-op body with an auto-start that reuses the existing start branch:

```kotlin
override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    if (restarting) return  // don't re-trigger on config changes
    CoroutineScope(Dispatchers.Main).launch {
        val autoStart = dataStore.data.map { prefs ->
            prefs[AUTO_RECORDING_START] ?: false
        }.first()
        if (autoStart
            && !recorderManager.isRecording
            && recorderManager.allPermissionsGranted(this@WhisperInputService)
        ) {
            updateAudioFormat()
            recorderManager.start(
                this@WhisperInputService,
                recordedAudioFilename,
                useOggFormat
            )
            updateMicUI(true)
            statusLabel?.text = getString(R.string.recording)
        }
        // If mic permission not granted yet, do nothing here (user grants via
        // the app settings or by tapping the mic, which calls launchMainActivity()).
    }
}
```

Notes:
- `AUTO_RECORDING_START` and `dataStore` are already in the same package / available on `Context` — no new imports needed beyond what the file already has (`CoroutineScope`, `Dispatchers`, `flow.first`, `map`, `preferences`).
- The `!recorderManager.isRecording` guard prevents re-starting while already recording (e.g., when moving between fields).
- `onWindowHidden()` already stops recording when the keyboard is dismissed, so switching away then back starts a fresh capture — matches "switching to that keyboard → start recording."

### Success Criteria:

#### Automated Verification:
- [ ] `cd android && ./gradlew assembleDebug` succeeds.
- [ ] Existing unit tests still pass.

#### Manual Verification:
- [ ] With `AUTO_RECORDING_START` = on (default) and mic permission granted: switch to Whisper keyboard (focus any text field) → mic shows pressed + status "Recording" with no tap.
- [ ] Speak → keyboard transcribes into the field (existing flow unchanged).
- [ ] Switch away (e.g., to Gboard) → recording stops; switch back → recording restarts.
- [ ] Set `AUTO_RECORDING_START` = off in app settings → switching to keyboard does **not** auto-start (tap mic still works).
- [ ] First run without mic permission: switching to keyboard does not spam the settings screen; tapping mic prompts permission.

**Implementation Note**: This phase is UI-behavior; verify on the emulator with a text field focused (e.g., the app's own debug field) before considering done.

---

## Testing Strategy

### Unit Tests:
- Deleted `WhisperRecognitionServiceTest` / `VoiceRecognitionSettingsActivityTest` are removed; ensure no other test references them.
- No new unit tests required for auto-start (it is a thin preference read + existing start path); covered by manual verification.

### Integration / E2E:
- Audit `run_e2e_test.sh` (and any instrumented tests) for RecognitionService / `voice_recognition_service` usage; the earlier `rg` on `run_e2e_test.sh` returned **no** matches, but re-grep during implementation to be safe and remove/adjust any recognition-specific step so the suite exercises only the keyboard.
- `just build` and `just emulator-start` + manual keyboard switch remain the primary verification.

### Manual Testing Steps:
1. `just build` (or `./gradlew assembleDebug`); install APK on emulator.
2. Enable "Whisper Input" in *Settings → On-screen keyboard*.
3. Open a text field (or the app's debug field) → confirm auto-recording starts.
4. Toggle `AUTO_RECORDING_START` off/on in app settings → confirm behavior changes.
5. Switch IMEs back and forth → confirm stop/restart.

## Performance Considerations
None significant — auto-start adds one `dataStore` read (cached/pure in-memory) per keyboard show; the recording path is unchanged.

## Migration Notes
- No data migration (preferences unchanged).
- `voice_recognition_service` secure setting on a device that previously had it set will keep pointing at the now-removed component; the system simply falls back (no crash). No code action needed. If desired, a future cleanup can clear it, but it is out of scope.

## References
- `WhisperInputService.kt` (`onStartInputView`, `toggleRecordingNormal`, `updateAudioFormat`) — `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`
- `AUTO_RECORDING_START` default — `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:439`
- Manifest components — `android/app/src/main/AndroidManifest.xml`
- IME subtype — `android/app/src/main/res/xml/method.xml`
