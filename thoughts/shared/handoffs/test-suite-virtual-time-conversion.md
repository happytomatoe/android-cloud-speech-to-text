---
date: 2026-07-17T22:31:07+0200
git_commit: 4d2d72fb18ef469dfb5a8ac3b5db6f87dc94530e
branch: feat/api-key-links
repository: whisper-to-input
topic: "Test suite virtual-time conversion (StandardTestDispatcher) + RecognitionService removal"
tags: [testing, robolectric, coroutines, virtual-time, StandardTestDispatcher, keyboard, RecognitionService]
---

# Handoff: Convert test suite to virtual time + finish RecognitionService removal

## Task(s)

### 1. RecognitionService removal + keyboard auto-start (PI GOAL, PAUSED — `mrpbqoji-hwgpz4`)
Plan: `thoughts/shared/plans/2026-07-17-remove-recognition-service-autostart-keyboard.md`
**Status: CODE COMPLETE & ON-DEVICE VERIFIED.** Not marked complete because the goal is paused; the actual objective is satisfied.
- Deleted `WhisperRecognitionService.kt`, `VoiceRecognitionSettingsActivity.kt`, `voice_recognition_service_meta.xml`, `WhisperRecognitionServiceTest.kt`, `VoiceRecognitionSettingsActivityTest.kt`.
- `AndroidManifest.xml`: removed the `RecognitionService` `<service>` block and the `VoiceRecognitionSettingsActivity` `<activity>` block (only `WhisperInputService` + `MainActivity` remain).
- `WhisperInputService.kt` `onStartInputView`: added auto-start honoring `AUTO_RECORDING_START` (reuses `toggleRecordingNormal` start path).
- Builds: `assembleDebug` + `assembleRelease` (`just build`) pass; no references to deleted classes anywhere in `android/`.
- On-device (emulator) verified: focusing a field with Whisper IME auto-starts recording (`Recording started` log); respects `AUTO_RECORDING_START` toggle (off → no auto-start); switch-away/back restarts.
- The 3 broken unit-test files (`WhisperRecognitionServiceTest.kt`, `VoiceRecognitionSettingsActivityTest.kt`, `RecorderManagerTest.kt`) were **moved by the user to `/tmp/refactor/`** (not deleted in-repo) to stop them regenerating and to unblock `testDebugUnitTest` compilation. `/tmp/refactor/recorder/RecorderManagerTest.kt` holds the 3rd file.

### 2. Test-suite virtual-time conversion ("Option B") — IN PROGRESS, CURRENTLY BROKEN
User-directed iterative side task. Research (ctx7 official kotlinx.coroutines docs + browser web search) confirmed: use `TestDispatcher` virtual time (`advanceTimeBy`/`advanceUntilIdle`/`runCurrent`), **never `Thread.sleep`**; prefer `StandardTestDispatcher` for timing/delay tests over `UnconfinedTestDispatcher`.
**Status: Partially implemented; 2 tests failing.**
- `MainDispatcherRule.kt`: switched `UnconfinedTestDispatcher` → `StandardTestDispatcher` (DONE).
- `AGENTS.md` §1: documented all `just` recipes; `justfile`: removed the redundant `test-unit` recipe (kept `just test`). (DONE)
- `WhisperInputServiceTest.kt` & `MainActivitySettingsTest.kt`: replaced `Thread.sleep`-based waits with `mainRule.dispatcher.scheduler.advanceUntilIdle()` / virtual-time loops. (DONE but insufficient — see failures)

## Critical References
- `android/app/src/test/java/com/example/whispertoinput/util/MainDispatcherRule.kt` — now `StandardTestDispatcher`.
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:52` — `startAsync` is a **non-suspend** fun that internally does `CoroutineScope(Dispatchers.Main).launch { ... withContext(Dispatchers.IO){ network } ... callback(...) }` (~line 139). So the transcription trigger runs on `Dispatchers.Main` (now queued) and the network on real IO.
- `android/app/src/test/java/com/example/whispertoinput/keyboard/BackspaceButton.kt` — `startLongPressDetector()` = `delay(600)` then `while(isActive){ backspaceCallback(); delay(80) }`.
- `android/app/src/test/java/com/example/whispertoinput/keyboard/BackspaceButtonTest.kt:51-57` — the failing assertions.

## Recent changes
- `MainDispatcherRule.kt` — `UnconfinedTestDispatcher()` → `StandardTestDispatcher()` (+ import + doc comment).
- `AGENTS.md` — §1 expanded to list all `just` recipes; added "always prefer a `just` target" guidance.
- `justfile` — removed `test-unit` recipe (was `./gradlew testDebugUnitTest` without `--parallel`); `test` (with `--parallel`) is now the only unit-test command.
- `WhisperInputServiceTest.kt` — `settle()` now `mainRule.dispatcher.scheduler.advanceUntilIdle()`; `permission_denied_launches_main_activity` uses `advanceUntilIdle()` instead of a `Thread.sleep` poll; `test_file_mode` adds a second `settle()` after the 2nd toggle (keeps real-time network poll).
- `MainActivitySettingsTest.kt` — `buildAndWait()` uses `repeat(50){ advanceUntilIdle(); if endpoint filled return }`; `apply_writes_*` and both `apply_*_enable_keyboard_prompt` flush with `advanceUntilIdle()` after `performClick()`.

## Learnings
- **StandardTestDispatcher queues coroutines**; they only execute when the test advances the scheduler. Under the prior `UnconfinedTestDispatcher` they ran eagerly on launch. Anything that triggers `Dispatchers.Main` work (e.g. `startAsync`, `setupSettingItems`, `toggleRecording`) now requires an explicit `advanceUntilIdle()`/`advanceTimeBy` to run.
- **Mixed Main + real-IO tests need a "pump"**: DataStore/network (OkHttp/MockWebServer) run on real threads and cannot be virtualized. Correct pattern for an IO-wait: `repeat(N){ advanceUntilIdle() /* flush Main trigger */; if(result!=null) break; Thread.sleep(50) /* let real IO finish */ }`. Pure `advanceUntilIdle()` alone leaves IO-bound continuations un-run; pure `Thread.sleep` alone leaves queued Main coroutines un-run.
- **`advanceTimeBy` boundary off-by-one**: a resumed continuation after `delay()` may fire on the *next* `advanceTimeBy` rather than within the one that crosses the delay. Step-counting tests (BackspaceButtonTest) must either call `runCurrent()` after triggering the coroutine to reach the first `delay`, then `advanceTimeBy`, or adjust the step counts to match.
- **Robolectric baseline cost ~6–7s per test class** just to bootstrap the Android environment; this dominates fast-running tests. The 15s regression in `test_file_mode` is the main avoidable slowness.
- `WhisperTranscriber.startAsync` callback (which sets `WhisperInputService.lastTranscriptionResult`) runs on `Dispatchers.Main` after the IO network completes — so it is also gated by the scheduler under `StandardTestDispatcher`.
- User moved 3 broken test files to `/tmp/refactor/` this session to stop them regenerating; do not re-add them to `android/`.

## Artifacts
- `thoughts/shared/plans/2026-07-17-remove-recognition-service-autostart-keyboard.md` — paused-goal plan.
- `android/app/src/test/java/com/example/whispertoinput/util/MainDispatcherRule.kt` — dispatcher change.
- `android/app/src/test/java/com/example/whispertoinput/WhisperInputServiceTest.kt`, `MainActivitySettingsTest.kt` — virtual-time conversions (need fix below).
- `android/app/src/test/java/com/example/whispertoinput/keyboard/BackspaceButtonTest.kt` — failing; needs step fix.
- `/tmp/refactor/` — moved `WhisperRecognitionServiceTest.kt`, `VoiceRecognitionSettingsActivityTest.kt`, `recorder/RecorderManagerTest.kt`.
- `AGENTS.md`, `justfile` — documentation + recipe cleanup (done).

## Action Items & Next Steps
1. **Fix `BackspaceButtonTest.long_press_repeats_then_stops`** (off-by-one: expected 4 got 3). Add `mainRule.dispatcher.scheduler.runCurrent()` after `dispatchTouchEvent(down())` to start the detector and reach its first `delay(600)`, then keep the `advanceTimeBy(600/80/80)` steps (or adjust counts to match `StandardTestDispatcher` boundary). Verify `count == 4` then `== 4` after `up()`.
2. **Fix `WhisperInputServiceTest.test_file_mode_transcribes_and_stores_result`** (15s timeout → fail). Replace the single `settle()` + real-time poll with a pump loop:
   ```kotlin
   toggle(service); settle()
   toggle(service); settle()
   val deadline = System.currentTimeMillis() + 15_000
   while (System.currentTimeMillis() < deadline) {
       mainRule.dispatcher.scheduler.advanceUntilIdle()   // run Main trigger + callback
       if (WhisperInputService.lastTranscriptionResult != null) break
       Thread.sleep(50)                                    // let MockWebServer IO finish
   }
   ```
   This makes it fast (network responds in ms) and green.
3. **Re-run `just test`** and confirm all ~40 tests pass and runtime drops (no 15s hangs). BackspaceButtonTest should run in ~1s.
4. **Decide on scope**: the user chose "B" (shared rule → `StandardTestDispatcher`). If the IO-pump complexity feels wrong, the alternative is a *separate* `StandardTestDispatcher`-based rule for `BackspaceButtonTest` only and keep `UnconfinedTestDispatcher` for the IO-mixed tests. Confirm with user before switching approaches.
5. **Paused goal**: once test suite is green, optionally `complete_goal` for `mrpbqoji-hwgpz4` (code already verified) — but only if the user wants the paused goal closed.

## Other Notes
- `just test` runs `./gradlew testDebugUnitTest --parallel`; `gradle.properties` already sets `org.gradle.parallel=true` (so `--parallel` is redundant but explicit). JDK is set via `sdk env` (Temurin 17) inside the recipe — do not call `./gradlew` directly for tests.
- Emulator is usually already running (`adb devices` → `emulator-5554`). Useful for re-verifying the auto-start behavior if needed: set Whisper IME, focus `field_debug_output` (bounds ~[63,483][1017,798], center ~540,640), check logcat for `Recording started`.
- The `WhisperInputServiceTest` permission/`onWindowHidden` tests already PASS under `StandardTestDispatcher` (the `advanceUntilIdle()` conversions worked); only `test_file_mode` (IO) and `BackspaceButtonTest` (boundary) remain.
