# Test Existing App & Add Deepgram Backend Plan

## Overview

Test the existing voice-input app (local Whisper ML) on the emulator, and if E2E testing is not feasible (due to model downloads, mic issues, etc.), add Deepgram as a cloud STT backend with test-file mode and E2E test infrastructure.

## Current State

- **App**: voice-input (`org.futo.voiceinput`) — local Whisper ML via C++ JNI
- **Emulator**: Running (emulator-5554), app builds and is on screen
- **No test infrastructure**: No justfile, no E2E script, no test-file mode
- **Reference**: `../whisper-to-input` has full cloud STT + E2E testing

## Key Insight: Both Apps Are IMEs

Both voice-input and whisper-to-input are **Input Method Editors (IMEs)** — custom keyboards:
- `VoiceInputMethodService extends InputMethodService`
- `WhisperInputService extends InputMethodService`

The E2E test flow is identical for both:
1. Install APK → enable IME → set as default keyboard
2. Open a text field → keyboard appears
3. **Test-file mode**: app reads a pre-recorded WAV file instead of using the microphone
4. Tap mic → app sends WAV to cloud API → transcription → text appears in input field

**Critical: E2E tests never use the microphone.** The test script:
- Generates a WAV file with `espeak-ng "hello world"`
- Pushes it to the emulator's app cache
- Enables test-file mode in the app (debug setting)
- Tapping mic triggers transcription of the file, not live recording

**whisper-to-input's `run_e2e_test.sh` can be directly ported** with these adaptations:
- Package: `org.futo.voiceinput` (instead of `com.example.whispertoinput`)
- Service: `org.futo.voiceinput/.VoiceInputMethodService`
- **voice-input lacks test-file mode and broadcast receiver** — these must be added first for automation
- Local Whisper ML path needs model downloads and mic access — cloud backend (Deepgram) bypasses both

## Phase 1: Test Existing App As-Is

### Goal
Verify the existing voice-input app works on the emulator with local Whisper.

### Steps
1. **Install the APK** on the running emulator
2. **Grant permissions** (RECORD_AUDIO, POST_NOTIFICATIONS)
3. **Enable the IME** in system settings
4. **Set as default keyboard**
5. **Open a text field** (e.g., browser search bar)
6. **Tap the mic button** on the voice keyboard
7. **Speak or play test audio** into the emulator
8. **Verify text appears** in the input field

### Potential Blockers
- Model download may fail or be slow in emulator
- No test-file mode to bypass microphone
- No broadcast receiver for automation
- No debug output field to verify transcription

### Decision Point
- **If working**: Document how to test, create basic justfile
- **If blocked** (likely — no test-file mode): Proceed to Phase 2 (add agent config + test-file mode)

## Phase 2: Bring Agent Config from whisper-to-input

### Goal
Copy AGENTS.md and .agents folder from the reference project to enable agent-assisted development.

### Steps
1. Copy `../whisper-to-input/AGENTS.md` → `./AGENTS.md`
2. Copy `../whisper-to-input/.agents/skills/` → `./.agents/skills/`

### Verification
- [ ] `./AGENTS.md` exists
- [ ] `./.agents/skills/` directory exists with all skill folders

## Phase 3: Add Test-File Mode (Local Whisper Backend)

### Goal
Add test-file mode to the voice-input app, enabling E2E testing without microphone or live audio.

### Changes Required

#### 3.1 Add Broadcast Receiver for Automation
**File**: `app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt`
**Changes**: Add `TOGGLE_RECORDING` broadcast receiver to allow external scripts to trigger recording

#### 3.2 Add Test-File Mode
**File**: `app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt`
**Changes**: In debug builds, check `USE_TEST_FILE` DataStore flag:
- First tap: set state to Recording (but don't start mic)
- Second tap: read WAV from app cache and send to local Whisper ML

#### 3.3 Add Debug Output Field
**File**: `app/src/main/res/layout/activity_main.xml` (or Compose equivalent)
**Changes**: Show last transcription result in a debug field (debug builds only)

#### 3.4 Add WAV File Push Support
**File**: `app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt`
**Changes**: Read test WAV from `context.cacheDir/test_audio.wav`

### Verification
- [ ] App compiles: `./gradlew assembleDebug`
- [ ] Test WAV file can be pushed to emulator cache
- [ ] Tapping mic in test mode reads from cache (no mic needed)
- [ ] Transcription appears in debug output field
## Phase 4: Add E2E Test Script to whisper-to-input

### Goal
Port `run_e2e_test.sh` from whisper-to-input, adapted for voice-input package.

### File
**Location**: `../whisper-to-input/run_e2e_test_voice_input.sh`

### Key Adaptations
- Package: `org.futo.voiceinput` (instead of `com.example.whispertoinput`)
- Service: `org.futo.voiceinput/.VoiceInputMethodService`
- Backend: `local-whisper` (for now)
- Test audio: generate with espeak-ng, push to emulator cache
- Verification: check debug output field for expected text

### Verification
- [ ] Script exists at `../whisper-to-input/run_e2e_test_voice_input.sh`
- [ ] Script is executable
- [ ] Script can generate test WAV with espeak-ng
- [ ] Script pushes WAV to emulator and triggers transcription

## Phase 5: Add Deepgram Cloud Backend

### Goal
Add Deepgram as an alternative STT backend, bypassing local ML entirely.

### Changes Required

#### 5.1 Add CloudTranscriber
**File**: `app/src/main/java/org/futo/voiceinput/CloudTranscriber.kt`
**Changes**: New file — HTTP client for Deepgram API

```kotlin
class CloudTranscriber {
    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        // Read API key from DataStore
        // Send audio to https://api.deepgram.com/v1/listen
        // Parse response: {"results":{"channels":[{"alternatives":[{"transcript":"..."}]}]}}
        // Return transcript via callback
    }
}
```

#### 5.2 Add Cloud Backend Settings
**File**: `app/src/main/java/org/futo/voiceinput/settings/pages/Advanced.kt` (or new page)
**Changes**: Add toggle for Local vs Cloud, Deepgram API key field

#### 5.3 Modify VoiceInputMethodService
**File**: `app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt`
**Changes**: Route transcription to CloudTranscriber when cloud mode is enabled

### Dependencies to Add
**File**: `app/build.gradle`
```
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
testImplementation 'org.robolectric:robolectric:4.12.2'
testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'
```
(OkHttp is already included in the app)

### Verification
- [ ] App compiles: `./gradlew assembleDebug`
- [ ] CloudTranscriber sends audio to Deepgram API
- [ ] Settings page shows Local/Cloud toggle
- [ ] Test-file mode works with cloud backend

## Phase 6: Verify E2E with Deepgram

### Goal
Run the full E2E test with Deepgram backend and verify text matches expected.

### Steps
1. `./gradlew assembleDebug`
2. Start emulator (if not running)
3. `../whisper-to-input/run_e2e_test_voice_input.sh --backend deepgram --expected "hello world"`
4. Verify: text "hello world" appears in debug output field

### Verification
- [ ] E2E test passes with Deepgram backend
- [ ] Transcription matches expected text

## Success Criteria

### Automated Verification:
- [ ] App builds: `./gradlew assembleDebug`
- [ ] Unit tests pass: `./gradlew testDebugUnitTest`
- [ ] Test-file mode works with local Whisper
- [ ] E2E test script exists and is executable
- [ ] E2E test passes with Deepgram backend

### Manual Verification:
- [ ] App installs and runs on emulator
- [ ] Voice keyboard appears when text field is focused
- [ ] Mic button triggers recording
- [ ] Transcription appears in text field
- [ ] Test-file mode reads WAV from cache (no mic)
- [ ] Cloud mode sends audio to Deepgram and returns text

## What We're NOT Doing
- Modifying the Compose settings UI significantly (minimal changes)
- Adding multiple cloud backends (Deepgram only for now)
- Changing the local Whisper ML path (keeping it as fallback)
- Modifying the app's package name or identity
- Running E2E tests with microphone (always test-file mode)
