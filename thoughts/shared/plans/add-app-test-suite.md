# Add Android Test Suite Implementation Plan

## Overview

The app currently has **zero real tests** — only the Android Studio placeholder `ExampleUnitTest` (asserts `2+2`) and `ExampleInstrumentedTest` (asserts package name). The only real coverage is the shell-driven `run_e2e_test.sh`. A keyboard-button regression slipped through precisely because no unit tested the button/state logic. This plan adds a proper test pyramid:

- **Tier 1 (highest priority — the reported bug):** `WhisperKeyboard` state machine + `BackspaceButton` long-press, via Robolectric.
- **Tier 2:** `WhisperTranscriber` (per-backend request building + response parsing) via MockWebServer; `MainActivity` settings cascading logic.
- **Tier 3:** `WhisperInputService`, `WhisperRecognitionService`, `RecorderManager`, `VoiceRecognitionSettingsActivity`.
- **Tier 4:** Rewrite the **stale** E2E plan (`e2e-test-automation.md`) to match current `WhisperInputService` behavior and add negative-path checks.

Decisions confirmed with the user: scope = **all tiers (end-to-end)**; test framework = **Robolectric** for Android-bound code; save plan to `thoughts/shared/plans/`; success criteria **run** the test tasks. `WhisperKeyboard` **stays** (tested, not deleted).

## Current State Analysis

**What exists:**
- `android/app/src/test/java/.../ExampleUnitTest.kt` — placeholder only.
- `android/app/src/androidTest/java/.../ExampleInstrumentedTest.kt` — placeholder only.
- `run_e2e_test.sh` + `scripts/ui_tap.py` + `scripts/test_e2e.py`(planned) — emulator + audio-injection E2E for the happy-path transcribe flow.
- `thoughts/shared/plans/e2e-test-automation.md` — an E2E plan that is **stale** (references an "auto-start on keyboard show" FSM and `Idle → Speaking → Finish` states that **do not exist** in current `WhisperInputService`; `onStartInputView` is a no-op per `WhisperInputService.kt:138`).

**Key Discovery (must-read):** `WhisperKeyboard` (the 8-button `Idle`/`Recording`/`Transcribing` state machine in `keyboard/WhisperKeyboard.kt:44`) and `BackspaceButton` (`keyboard/BackspaceButton.kt:20`) are **not instantiated anywhere** in the running app. The live IME (`WhisperInputService.kt:112` `onCreateInputView`) inflates `keyboard_view.xml` but only wires `btn_mic` → `toggleRecording()` and `label_status`. The other buttons are rendered but inert. The user confirmed `WhisperKeyboard` **stays and should be tested** (its button logic is the historical source of regressions — see commits `b7c7730` "Mic button can no longer cancel transcription", `3458c54` "Added a cancel button", `1e3d1d8` "Add retry button").

**Testability facts:**
- `WhisperKeyboard.setup()` inflates `R.layout.keyboard_view` and mutates Android views → needs **Robolectric** (or a refactor). Robolectric keeps production code unchanged.
- `WhisperKeyboard` handlers are `private` but registered as `OnClickListener`s in `setup()`, so tests can drive them via **`View.performClick()`** on the returned view — **no production-code changes required**.
- `BackspaceButton` uses `CoroutineScope(Dispatchers.Main)` + `delay(600)` / `delay(80)` → needs `kotlinx-coroutines-test` + `Dispatchers.setMain(testDispatcher)`.
- `WhisperTranscriber` uses `okhttp3` + reads `context.dataStore` → needs **Robolectric** (for `Context`/DataStore/resources) + **MockWebServer** (to stub the HTTP endpoint and assert request shape).
- `WhisperInputService` / `WhisperRecognitionService` are Android `Service` subclasses with hardwired `WhisperTranscriber`/`RecorderManager` fields → testable via Robolectric `ServiceController` + MockWebServer; the network call in the test-file path is avoided by pointing the DataStore endpoint at MockWebServer.

**Build facts:** Gradle 8.14, AGP 8.1.2, Kotlin 1.9.0, `compileSdk`/`targetSdk` 34, `minSdk` 24. `testInstrumentationRunner` is already set; no `testOptions` block exists (Robolectric needs `unitTests.isIncludeAndroidResources = true`). `just test-e2e` exists; no unit/instrumented `just` targets. okhttp3 is pulled transitively via `io.ktor:ktor-client-okhttp:2.3.6`.

## Desired End State

A merged test suite where:
- `./gradlew testDebugUnitTest` runs Robolectric unit tests covering every button/state transition in `WhisperKeyboard`, `BackspaceButton`, `WhisperTranscriber`, `MainActivity`, and the service/recorder tiers.
- `./gradlew connectedDebugAndroidTest` runs Espresso instrumented tests (negative-path E2E assertions).
- `run_e2e_test.sh` remains the happy-path runner; `e2e-test-automation.md` is rewritten to match reality.
- A keyboard-button regression like the historical ones is caught by `WhisperKeyboardTest` before shipping.

### Key Discoveries:
- `WhisperKeyboard`/`BackspaceButton` are orphaned-but-kept; tests validate them **in isolation** (they are not currently wired into `WhisperInputService`). If the keyboard is meant to become the live IME UI again, a follow-up would rewire it — **out of scope here**.
- `WhisperKeyboard` handlers are reachable via `performClick()` → no visibility changes needed.
- `e2e-test-automation.md` is stale (non-existent FSM); must be rewritten, not extended.

## What We're NOT Doing

- **Not deleting** `WhisperKeyboard`/`BackspaceButton`/`keyboard_view.xml` (user: keyboard stays).
- **Not rewiring** `WhisperKeyboard` into `WhisperInputService` (separate effort).
- **Not refactoring** services to inject `WhisperTranscriber`/`RecorderManager` (we test via MockWebServer + Robolectric instead, keeping production code unchanged).
- **Not adding** streaming transcription, new providers, or iOS/tvOS targets.
- **Not** benchmarking audio quality or load/stress testing.

## Implementation Approach

1. Add test dependencies + `testOptions` + `just` targets (Phase 1) — no behavior change.
2. Write Tier 1 Robolectric tests driving real views via `performClick()` (Phase 2) — directly prevents the reported class of bug.
3. Add Tier 2 MockWebServer-backed `WhisperTranscriber` tests + `MainActivity` settings tests (Phase 3).
4. Add Tier 3 service/recorder tests (Phase 4).
5. Rewrite the stale E2E plan and add negative-path checks (Phase 5).

Each phase ends with an explicit pause for manual confirmation before the next phase begins.

---

## Phase 1: Test Infrastructure

### Overview
Add the dependencies and Gradle/`just` wiring required by all later phases. No production code changes.

### Changes Required:

#### 1. Gradle test options + dependencies
**File**: `android/app/build.gradle.kts`
**Changes**: Add `testOptions` and `testImplementation` / `androidTestImplementation` entries.

```kotlin
android {
    // ... existing block ...
    testOptions {
        // Allow Robolectric to resolve R.layout.*, R.drawable.*, R.string.* from merged resources
        unitTests.isIncludeAndroidResources = true
        // Return default values for unmocked Android calls outside Robolectric's scope
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ... existing ...
    // Tier 1/2/3: Android framework simulation on the JVM (no emulator needed)
    testImplementation("org.robolectric:robolectric:4.12.2")
    // Tier 2: stub the transcription HTTP endpoint and assert request shape
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Tier 1/2: deterministic coroutine scheduling for BackspaceButton delays
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Tier 3c (optional): mock final framework classes like MediaRecorder
    testImplementation("io.mockk:mockk:1.13.11")
    // Already present, kept:
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

> **Version note:** 4.12.2 (Robolectric) and 4.12.0 (MockWebServer) both target SDK 34 and pair with AGP 8.1.2 / Kotlin 1.9.0. The app does not pin `kotlinx-coroutines`; align `kotlinx-coroutines-test` to whatever coroutines version the build resolves (1.7.3 is compatible with Kotlin 1.9). Verify with `./gradlew :app:dependencies` after adding.

#### 2. `just` test targets
**File**: `justfile`
**Changes**: Add unit/instrumented test targets (mirror the existing `JAVA_HOME` setup used by `build`).

```make
# ── Tests ──────────────────────────────────────────────────────────
# Run Robolectric JVM unit tests (Tiers 1–3)
test-unit:
    #!/usr/bin/env bash
    set -e
    export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    cd android && ./gradlew testDebugUnitTest

# Run Espresso instrumented tests on a running emulator
test-instrumented:
    #!/usr/bin/env bash
    set -e
    export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    cd android && ./gradlew connectedDebugAndroidTest
```

### Success Criteria:

#### Automated Verification:
- [ ] Project syncs: `cd android && ./gradlew testDebugUnitTest` runs (existing placeholder test still passes).
- [ ] `just test-unit` executes without "Method ... not mocked" errors.
- [ ] No new lint errors: `cd android && ./gradlew lintDebug`.

#### Manual Verification:
- [ ] `just test-unit` output shows the placeholder `ExampleUnitTest` as passing.
- [ ] Dependencies resolved (no version-conflict errors in Gradle output).

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Tier 1 — Keyboard Screen State Machine + Backspace (Robolectric)

### Overview
Lock down the button/state logic that caused the reported bug. Tests drive the **real views** returned by `setup()` via `performClick()`, and capture the 11 state callbacks (`onStartRecording`, `onStartTranscribing`, etc.) as Kotlin lambdas — **no production-code changes**.

### Changes Required:

#### 1. New file: `WhisperKeyboardTest`
**File**: `android/app/src/test/java/com/example/whispertoinput/keyboard/WhisperKeyboardTest.kt`
**Approach**: Robolectric + `@Config(sdk = [34])`. Build a `LayoutInflater` from `ApplicationProvider.getApplicationContext()`, call `setup(...)` capturing callbacks, then `performClick()` each button view and assert transitions.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhisperKeyboardTest {
    private lateinit var kb: WhisperKeyboard
    private lateinit var root: View
    // captured callbacks
    private var started = 0; private var cancelled = 0
    private var transcribed: MutableList<String> = mutableListOf()
    private var backspaces = 0; private var enters = 0
    private var spaceBars = 0; private var switchedIme = 0
    private var openedSettings = 0

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val inflater = LayoutInflater.from(ctx)
        kb = WhisperKeyboard()
        root = kb.setup(
            inflater, shouldOfferImeSwitch = true,
            onStartRecording = { started++ },
            onCancelRecording = { cancelled++ },
            onStartTranscribing = { attachToEnd -> transcribed.add(attachToEnd) },
            onCancelTranscribing = { cancelled++ },
            onButtonBackspace = { backspaces++ },
            onEnter = { enters++ },
            onSpaceBar = { spaceBars++ },
            onSwitchIme = { switchedIme++ },
            onOpenSettings = { openedSettings++ },
            shouldShowRetry = { false },
        )
    }
    private fun click(@IdRes id: Int) = root.findViewById<View>(id).performClick()

    @Test fun mic_idle_starts_recording() { click(R.id.btn_mic); assertEquals(1, started) }
    @Test fun mic_recording_transcribes_empty() { click(R.id.btn_mic); click(R.id.btn_mic); assertEquals(listOf(""), transcribed) }
    @Test fun mic_transcribing_is_noop() { click(R.id.btn_mic); click(R.id.btn_mic); click(R.id.btn_mic); assertEquals(1, transcribed.size) }
    @Test fun cancel_recording_cancels() { click(R.id.btn_mic); click(R.id.btn_cancel); assertEquals(1, cancelled) }
    @Test fun cancel_transcribing_cancels() { click(R.id.btn_mic); click(R.id.btn_mic); click(R.id.btn_cancel); assertEquals(1, cancelled) }
    @Test fun cancel_idle_noop() { click(R.id.btn_cancel); assertEquals(0, cancelled) }
    @Test fun retry_idle_transcribes_empty() { click(R.id.btn_retry); assertEquals(listOf(""), transcribed) }
    @Test fun retry_recording_noop() { click(R.id.btn_mic); click(R.id.btn_retry); assertEquals(0, transcribed.size) }
    @Test fun enter_recording_sends_newline() { click(R.id.btn_mic); click(R.id.btn_enter); assertEquals(listOf("\r\n"), transcribed) }
    @Test fun enter_idle_invokes_onEnter() { click(R.id.btn_enter); assertEquals(1, enters) }
    @Test fun space_recording_sends_space() { click(R.id.btn_mic); click(R.id.btn_space_bar); assertEquals(listOf(" "), transcribed) }
    @Test fun space_idle_invokes_onSpaceBar() { click(R.id.btn_space_bar); assertEquals(1, spaceBars) }
    @Test fun settings_invokes_callback() { click(R.id.btn_settings); assertEquals(1, openedSettings) }
    @Test fun backspace_invokes_callback() { click(R.id.btn_backspace); assertEquals(1, backspaces) }
    @Test fun previous_ime_invokes_callback() { click(R.id.btn_previous_ime); assertEquals(1, switchedIme) }
    @Test fun reset_returns_to_idle() { click(R.id.btn_mic); kb.reset(); click(R.id.btn_mic); assertEquals(2, started) }
    @Test fun updateAmplitude_outside_recording_is_noop() { kb.updateMicrophoneAmplitude(30000) /* no throw */ }
    @Test fun retry_hidden_when_shouldShowRetry_false() {
        // shouldShowRetry() == false -> btn_retry INVISIBLE after setup/reset
        assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.btn_retry).visibility)
    }
}
```

Also add a `shouldShowRetry = { true }` variant asserting `btn_retry` becomes `VISIBLE` in Idle.

#### 2. New file: `BackspaceButtonTest`
**File**: `android/app/src/test/java/com/example/whispertoinput/keyboard/BackspaceButtonTest.kt`
**Approach**: Robolectric + `MainDispatcherRule` (`Dispatchers.setMain(StandardTestDispatcher)`). Dispatch `MotionEvent.ACTION_DOWN`/`ACTION_UP` via `dispatchTouchEvent`. Assert: short tap → 1 callback; long press → repeats every 80 ms after 600 ms; `ACTION_UP` stops it.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackspaceButtonTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun short_tap_fires_once() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val btn = BackspaceButton(ctx, null)
        var count = 0
        btn.setBackspaceCallback { count++ }
        btn.dispatchTouchEvent(down()); btn.dispatchTouchEvent(up())
        assertEquals(1, count)
    }

    @Test fun long_press_repeats() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val btn = BackspaceButton(ctx, null)
        var count = 0
        btn.setBackspaceCallback { count++ }
        btn.dispatchTouchEvent(down())
        mainRule.dispatcher.scheduler.advanceTimeBy(600) // crosses DELAY_BEFORE_QUICK_BACKSPACE
        mainRule.dispatcher.scheduler.advanceTimeBy(80)  // one quick backspace
        mainRule.dispatcher.scheduler.advanceTimeBy(80)  // another
        assertEquals(3, count) // 1 (initial performClick) + 2 repeats
        btn.dispatchTouchEvent(up())
        mainRule.dispatcher.scheduler.advanceTimeBy(1000)
        assertEquals(3, count) // stopped
    }
}
```

(`MainDispatcherRule` is the standard `ExternalResource` that does `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`.)

### Success Criteria:

#### Automated Verification:
- [ ] `just test-unit` runs `WhisperKeyboardTest` + `BackspaceButtonTest` green.
- [ ] All 19 `WhisperKeyboardTest` cases pass (state × button matrix).
- [ ] Backspace short-tap (1) and long-press-repeat-then-stop cases pass.

#### Manual Verification:
- [ ] Review that tests exercise **every** `when` branch in `onButtonMicClick`/`onButtonEnterClick`/`onButtonCancelClick`/`onButtonRetryClick`/`onButtonSpaceBarClick` (`WhisperKeyboard.kt:241-286`).
- [ ] Confirm no production code was modified to enable these tests.

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: Tier 2 — WhisperTranscriber + MainActivity Settings

### Overview
Test the network layer (request building per backend + response parsing) and the settings cascading logic — both pure-ish and high-ROI.

### Changes Required:

#### 1. New file: `WhisperTranscriberTest`
**File**: `android/app/src/test/java/com/example/whispertoinput/WhisperTranscriberTest.kt`
**Approach**: Robolectric (for `Context` + `dataStore` + `R.string.*` error messages) + **MockWebServer**. Write backend config into the test `Context.dataStore` (the `SPEECH_TO_TEXT_BACKEND`, `ENDPOINT`, `API_KEY`, `MODEL`, `LANGUAGE_CODE`, `ADD_TRAILING_SPACE` keys are top-level in `MainActivity.kt`), point `ENDPOINT` at the MockWebServer URL, call `startAsync(ctx, fakeWavPath, "audio/wav", attachToEnd, successCb, errorCb)`, and assert both the **callback text** and the **recorded request** (headers/auth/body).

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhisperTranscriberTest {
    private lateinit var server: MockWebServer
    private lateinit var ctx: Context

    @Before fun setUp() { server = MockWebServer(); server.start(); ctx = ApplicationProvider.getApplicationContext() }

    @Test fun voxtral_parses_text_field() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        writeConfig(ctx, backend = "Voxtral (Mistral)", endpoint = server.url("/").toString(), apiKey = "k", model = "m", lang = "auto", trailing = false)
        var result: String? = null
        WhisperTranscriber().startAsync(ctx, fakeWav(ctx), "audio/wav", "", { result = it }, { fail() })
        advanceUntilIdle()
        assertEquals("hello world", result)
        val req = server.takeRequest()
        assertTrue(req.headers["Authorization"]!!.startsWith("Bearer "))
        assertTrue(req.body.readUtf8().contains("file"))
    }

    @Test fun deepgram_uses_token_auth_and_query() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":{"channels":[{"alternatives":[{"transcript":"hi"}]}]}}"""))
        writeConfig(ctx, backend = "Deepgram", endpoint = server.url("/").toString(), apiKey = "k", model = "nova-3", lang = "en", trailing = false)
        var result: String? = null
        WhisperTranscriber().startAsync(ctx, fakeWav(ctx), "audio/wav", "", { result = it }, { fail() })
        advanceUntilIdle()
        assertEquals("hi", result)
        val req = server.takeRequest()
        assertEquals("Token k", req.headers["Authorization"])
        assertTrue(req.path!!.contains("model=nova-3"))
    }

    @Test fun elevenlabs_and_60db_parse() = runTest { /* analogous: {"text":...} and {"data":{"text":...}} */ }
    @Test fun empty_api_key_throws() = runTest { /* assert errorCb invoked for voxtral/groq/60db when API_KEY="" */ }
    @Test fun attachToEnd_appends_newline() = runTest { /* attachToEnd="\r\n" -> result ends with \r\n */ }
    @Test fun addTrailingSpace_appends_space() = runTest { /* ADD_TRAILING_SPACE=true, attachToEnd="" -> result ends with " " */ }
    @Test fun http_error_invokes_error_callback() = runTest { server.enqueue(MockResponse().setResponseCode(401)); /* assert errorCb called, successCb null */ }

    @After fun tearDown() { server.shutdown() }
}
```

> `writeConfig`/`fakeWav` are test helpers that write to `ctx.dataStore` (the `Context.dataStore` extension from `MainActivity.kt`) and create a temp `.wav` file. `WhisperTranscriber` deletes the file after a successful call, so use a throwaway temp file.

#### 2. New file: `MainActivitySettingsTest`
**File**: `android/app/src/test/java/com/example/whispertoinput/MainActivitySettingsTest.kt`
**Approach**: Robolectric `buildActivity(MainActivity::class).setup()`, drive the backend spinner, assert endpoint/model/language auto-fill and API-key link update. Uses `MainDispatcherRule` because `setupSettingItems()` launches coroutines on `Dispatchers.Main`.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySettingsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun selecting_backend_autofills_endpoint_and_does_not_clobber_user_value() {
        val activity = Robolectric.buildActivity(MainActivity::class).setup().get()
        mainRule.dispatcher.scheduler.advanceUntilIdle() // finish setupSettingItems()
        val spinner = activity.findViewById<Spinner>(R.id.spinner_speech_to_text_backend)
        // select "Deepgram"
        spinner.setSelection(indexOf(spinner, "Deepgram"))
        mainRule.dispatcher.scheduler.advanceUntilIdle()
        val endpoint = activity.findViewById<EditText>(R.id.field_endpoint)
        assertEquals("https://api.deepgram.com/v1/listen", endpoint.text.toString())
        // API key link updated
        val link = activity.findViewById<TextView>(R.id.link_api_key)
        assertTrue(link.text.toString().contains("deepgram"))
    }

    @Test fun apply_writes_dirty_setting_to_datastore() { /* change endpoint, tap Apply, assert DataStore value */ }
}
```

### Success Criteria:

#### Automated Verification:
- [ ] `just test-unit` runs `WhisperTranscriberTest` (all backends + error + attachToEnd + trailing space) green.
- [ ] `MainActivitySettingsTest` green (auto-fill + link + apply→DataStore).
- [ ] MockWebServer requests assert correct auth scheme per backend (Bearer vs Token vs xi-api-key).

#### Manual Verification:
- [ ] Confirm `WhisperTranscriberTest` covers Voxtral, ElevenLabs, Deepgram, Groq, 60db response parsing branches (`WhisperTranscriber.kt:94-140`).
- [ ] Confirm no real network calls escape (all hit MockWebServer).

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 4.

---

## Phase 4: Tier 3 — Services, Recorder, Settings Redirect

### Overview
Cover the Android-bound services. These are the heaviest to test; use Robolectric `ServiceController` + MockWebServer. Keep production code unchanged (no injection refactor).

### Changes Required:

#### 1. `WhisperInputServiceTest` (Robolectric `ServiceController`)
**File**: `android/app/src/test/java/com/example/whispertoinput/WhisperInputServiceTest.kt`
- **Permission gating:** with `RECORD_AUDIO`/`POST_NOTIFICATIONS` denied and `USE_TEST_FILE=false`, calling `toggleRecording()` → `toggleRecordingNormal()` launches `MainActivity` (assert an `android.intent.action` to `MainActivity` was fired via `shadowOf(getApplicationContext()).nextStartedActivity`).
- **Test-file toggle:** set `USE_TEST_FILE=true` + `TEST_FILE_PATH` to a tiny valid WAV in test assets; point `ENDPOINT` at MockWebServer. First `toggleRecording()` sets `testFileModeRecording=true` + updates mic UI; second `toggleRecording()` calls `whisperTranscriber.startAsync` against MockWebServer and commits text to `currentInputConnection` (use a `ShadowInputMethodService` / Robolectric IMS support, or assert `lastTranscriptionResult`).
- **`onWindowHidden`:** while `testFileModeRecording`, calling `onWindowHidden()` clears `testFileModeRecording` and resets mic UI (no exception).

> Note: `InputMethodService` has limited Robolectric support. If `currentInputConnection` is unavailable, assert against the `companion object` `lastTranscriptionResult`/`lastTranscriptionError` instead of the committed text. Flag if IMS shadow limitations force a narrower assertion.

#### 2. `WhisperRecognitionServiceTest` (Robolectric `ServiceController`)
**File**: `android/app/src/test/java/com/example/whispertoinput/WhisperRecognitionServiceTest.kt`
- `onStartListening` with permissions → begins listening, starts `RecorderManager`.
- `onCancel` while recording → stops recorder + deletes recorded file (assert file gone).
- `onStopListening` → transcription via MockWebServer → `callback.results(...)` (assert `RESULTS_RECOGNITION` contains parsed text) or `callback.error(...)` on failure.

#### 3. `RecorderManagerTest` (Robolectric, optional mockk)
**File**: `android/app/src/test/java/com/example/whispertoinput/recorder/RecorderManagerTest.kt`
- `start()` → `isRecording == true`; `stop()` → `isRecording == false`.
- `allPermissionsGranted(ctx)` returns false without permissions, true after granting via `shadowOf(ctx).grantPermissions(...)`.
- Use Robolectric `ShadowMediaRecorder` (no mockk needed) unless final-class mocking is required.

#### 4. `VoiceRecognitionSettingsActivityTest` (Robolectric, light)
**File**: `android/app/src/test/java/com/example/whispertoinput/VoiceRecognitionSettingsActivityTest.kt`
- `buildActivity(VoiceRecognitionSettingsActivity::class).setup()` → asserts it starts `MainActivity` and finishes (`shadowOf(activity).isFinishing`).

### Success Criteria:

#### Automated Verification:
- [ ] `just test-unit` runs all four Tier-3 test classes green.
- [ ] `WhisperInputServiceTest` covers permission-gating + test-file toggle + `onWindowHidden`.
- [ ] `WhisperRecognitionServiceTest` covers start/cancel/stop→transcribe.
- [ ] `RecorderManagerTest` covers state + permission check.
- [ ] `VoiceRecognitionSettingsActivityTest` covers redirect.

#### Manual Verification:
- [ ] If Robolectric IMS shadows are insufficient, note the narrower assertion used and whether an instrumented (`androidTest`) test is needed instead.
- [ ] No real microphone/network access during these tests.

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 5.

---

## Phase 5: Tier 4 — Rewrite Stale E2E Plan + Negative Paths

### Overview
The existing `thoughts/shared/plans/e2e-test-automation.md` describes an **auto-start FSM** (`Idle → Speaking → Finish`) and "auto-starts recording on keyboard show" that **do not exist** in current `WhisperInputService` (`onStartInputView` is a no-op at `WhisperInputService.kt:138`; recording is toggled by mic tap or the `ACTION_TOGGLE_RECORDING` broadcast). Rewrite it to match reality and add the button-state negative paths the unit tests can't fully cover on-device.

### Changes Required:

#### 1. Rewrite `e2e-test-automation.md`
**File**: `thoughts/shared/plans/e2e-test-automation.md`
**Changes**: Replace the stale FSM/auto-start narrative with the **current** model:
- Recording is toggled by tapping `btn_mic` OR sending broadcast `com.example.whispertoinput.action.TOGGLE_RECORDING` (`WhisperInputService.kt` `toggleReceiver`).
- `run_e2e_test.sh` already uses **test-file mode** (`USE_TEST_FILE` + `TEST_FILE_PATH`) for silent, permission-free E2E — document this as the primary path.
- Remove all `Idle → Speaking → Finish` references; replace with the real states (`Idle`/`Recording`/`Transcribing` from `WhisperKeyboard`, and the `testFileModeRecording` boolean in `WhisperInputService`).
- Keep `scripts/ui_tap.py` / `scripts/test_e2e.py` orchestration approach.

#### 2. Add negative-path E2E checks to `run_e2e_test.sh` (or `scripts/test_e2e.py`)
**File**: `run_e2e_test.sh` (and/or `scripts/test_e2e.py`)
**Add assertions**:
- **Double mic tap during Transcribing is ignored** — tap mic, start test-file transcribe, tap mic again immediately, assert only one transcription result is committed (guards the `Transcribing → return` no-op in `onButtonMicClick`, `WhisperKeyboard.kt:246`).
- **Cancel mid-Transcribing returns to Idle** — after transcribe starts, invoke cancel; assert status label returns to `whisper_to_input` and no partial text is committed.
- **Status-label transitions** — assert `label_status` text cycles `whisper_to_input` → `recording` → `transcribing`.

### Success Criteria:

#### Automated Verification:
- [ ] `e2e-test-automation.md` no longer references a non-existent auto-start FSM.
- [ ] `just test-e2e` still runs (happy path unchanged).
- [ ] New negative-path checks are present and exercised by `run_e2e_test.sh`.

#### Manual Verification:
- [ ] Run `just test-e2e` on the emulator; confirm double-tap-during-transcribe and cancel-mid-transcribe checks behave as specified.
- [ ] Documentation matches current `WhisperInputService` behavior.

---

## Testing Strategy

### Unit Tests (Robolectric, `just test-unit`):
- **Keyboard state machine** — every button × every state (Phase 2).
- **Backspace** — short tap vs long-press repeat vs stop (Phase 2).
- **Transcriber** — per-backend request shape + response parsing + error + `attachToEnd` + trailing space (Phase 3).
- **MainActivity settings** — backend auto-fill, API-key link, apply→DataStore (Phase 3).
- **Services/recorder/redirect** (Phase 4).

### Integration / E2E Tests:
- `run_e2e_test.sh` happy path (existing) + negative paths (Phase 5).
- Espresso instrumented (`just test-instrumented`) only if a Robolectric shadow proves insufficient for `WhisperInputService`/`WhisperRecognitionService`.

### Manual Testing Steps:
1. `just test-unit` → all JVM tests green.
2. `just test-e2e` → transcription appears; new negative checks pass.
3. Spot-check on a real device/emulator that the keyboard buttons behave as the unit tests assert.

## Performance Considerations

- Robolectric tests run on JVM (no emulator) — fast (seconds per class). `just test-unit` is the primary gate.
- MockWebServer is in-process; no real network.
- E2E (`run_e2e_test.sh`) remains the slow path (emulator boot ~60s + transcription) — keep it as a separate `just test-e2e` target, not part of the unit gate.

## Migration Notes

- No data/schema migration. Settings already live in DataStore; tests write to an isolated test `Context` DataStore.
- No production API changes; all test code is additive under `src/test` / `src/androidTest`.

## References

- Keyboard state machine: `android/app/src/main/java/com/example/whispertoinput/keyboard/WhisperKeyboard.kt`
- Backspace long-press: `android/app/src/main/java/com/example/whispertoinput/keyboard/BackspaceButton.kt`
- Transcriber: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`
- IME service (current behavior): `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`
- Settings: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
- Recorder: `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt`
- Recognition service: `android/app/src/main/java/com/example/whispertoinput/WhisperRecognitionService.kt`
- Stale E2E plan (to rewrite): `thoughts/shared/plans/e2e-test-automation.md`
- E2E runner: `run_e2e_test.sh`, `scripts/ui_tap.py`
- Voice-only IME plan (context; keyboard kept per user): `thoughts/shared/plans/voice-input-method.md`
- Build/test config: `android/app/build.gradle.kts`, `justfile`
