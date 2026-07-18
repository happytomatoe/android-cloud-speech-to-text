---
date: 2026-07-18T23:45:00+0200
git_commit: 4d2d72fb18ef469dfb5a8ac3b5db6f87dc94530e
branch: feat/api-key-links
repository: whisper-to-input
topic: "Test suite virtual-time conversion + hang fix (StandardTestDispatcher, Robolectric teardown, forkEvery)"
tags: [testing, robolectric, coroutines, virtual-time, StandardTestDispatcher, MainDispatcherRule, test-file-mode, BackspaceButtonTest, WhisperTranscriberTest, WhisperInputServiceTest, MainActivitySettingsTest, hang-fix, forkEvery]

---

# Handoff: Test suite virtual-time conversion + hang fix complete

## Task(s)

1. **Convert test suite to virtual time (StandardTestDispatcher)** — COMPLETED
   - Switched `MainDispatcherRule` from `UnconfinedTestDispatcher` to `StandardTestDispatcher` for deterministic virtual-time control
   - Fixed 7 tests that broke due to the dispatcher change (off-by-one boundaries + IO-mixed tests)

2. **Fix flaky 400s+ hang in full suite** — COMPLETED
   - Root cause: `MainActivitySettingsTest` intra-class state leak (fire-and-forget `Dispatchers.Main` coroutines + DataStore + Robolectric looper) caused deadlock on 2nd test's `buildActivity().setup()` when classes ran in same JVM
   - Fixed via `forkEvery = 1` (fresh JVM per test class) + robust `@After` teardown that flushes dispatcher + DataStore + looper

3. **Profile/decrease runtime** — COMPLETED
   - Full suite now runs in **~90-120s** (vs. permanent 400s+ timeout hang)
   - 39/40 tests pass; 1 pre-existing failure remains (`apply_writes_dirty_setting_to_datastore`)

## Critical References

- `android/app/src/test/java/com/example/whispertoinput/util/MainDispatcherRule.kt:16` — `StandardTestDispatcher` rule
- `android/app/src/test/java/com/example/whispertoinput/keyboard/BackspaceButtonTest.kt:51` — off-by-one boundary fix (runCurrent + per-advance runCurrent)
- `android/app/src/test/java/com/example/whispertoinput/WhisperTranscriberTest.kt:88` — pumpUntil helper replacing CountDownLatch
- `android/app/src/test/java/com/example/whispertoinput/WhisperInputServiceTest.kt:138` — pump loop with advanceUntilIdle for test_file_mode
- `android/app/src/test/java/com/example/whispertoinput/MainActivitySettingsTest.kt:70` — @After teardown with dispatcher/DataStore/looper flush
- `android/app/build.gradle.kts:80` — `forkEvery = 1` for per-class JVM isolation

## Recent Changes

- `MainDispatcherRule.kt`: `UnconfinedTestDispatcher()` → `StandardTestDispatcher()` (virtual time)
- `BackspaceButtonTest.kt`: Added `runCurrent()` after `down()` + `runCurrent()` after each `advanceTimeBy()` to drain deferred continuations
- `WhisperTranscriberTest.kt`: Replaced `CountDownLatch` with `pumpUntil { advanceUntilIdle(); Thread.sleep(20) }` for IO-mixed tests
- `WhisperInputServiceTest.kt`: Added `advanceUntilIdle()` inside test_file_mode pump loop (was missing)
- `MainActivitySettingsTest.kt`:
  - Added `currentActivity` tracking + `@After` teardown with `advanceUntilIdle() + runBlocking(Dispatchers.IO){dataStore.data.first()} + finish() + looper.idle()`
  - `buildAndWait()` kept simple (no looper idle) to avoid new flakiness
- `build.gradle.kts`: `forkEvery = 1` (fresh JVM per test class)

## Learnings

1. **StandardTestDispatcher boundary quirk**: `advanceTimeBy(delay)` does NOT run the resumed continuation if it lands exactly on the target time; it defers to the NEXT `advanceTimeBy` or `runCurrent()`. Fix: call `runCurrent()` after each `advanceTimeBy()`.

2. **IO-mixed tests need pump loops**: Under `StandardTestDispatcher`, `withContext(Dispatchers.IO)` runs on real threads; the callback queued on Main is only dispatched when the scheduler advances. Pattern:
   ```kotlin
   while (!done) { scheduler.advanceUntilIdle(); if (result != null) break; Thread.sleep(20) }
   ```

3. **Robolectric cross-test state leaks**: Fire-and-forget `CoroutineScope(Dispatchers.Main).launch` in production code leaves orphaned coroutines when `Dispatchers.resetMain()` runs between tests. These hold DataStore write locks / looper tasks that deadlock the next test's `buildActivity().setup()`. Fix: `forkEvery=1` + `@After` that flushes scheduler + DataStore + looper + finishes activity.

4. **`apply_writes_dirty_setting_to_datastore`**: Pre-existing flaky failure. `endpoint.setText(newValue)` queues a `TextWatcher` notification on Robolectric's paused looper; `apply()` reads the field before the watcher fires (so `isDirty=false`). Flushing the looper after `setText` helps but didn't fully fix — likely because `setupSettingItemsDone` isn't true when `buildAndWait` returns. This is a pre-existing bug, not introduced by this work.

## Artifacts

- `android/app/src/test/java/com/example/whispertoinput/util/MainDispatcherRule.kt`
- `android/app/src/test/java/com/example/whispertoinput/keyboard/BackspaceButtonTest.kt`
- `android/app/src/test/java/com/example/whispertoinput/WhisperTranscriberTest.kt`
- `android/app/src/test/java/com/example/whispertoinput/WhisperInputServiceTest.kt`
- `android/app/src/test/java/com/example/whispertoinput/MainActivitySettingsTest.kt`
- `android/app/build.gradle.kts` (test config)

## Action Items & Next Steps

1. **Optional: Fix `apply_writes_dirty_setting_to_datastore`** — if desired, either:
   - Make `buildAndWait()` also wait for `setupSettingItemsDone` (requires production code change to expose it), OR
   - Accept as known limitation (test was already failing before this work)

2. **Runtime profiling** — suite now runs ~90-120s. Further optimization possible:
   - Reduce Robolectric bootstrap by grouping tests or using `@Config(sdk=21)`
   - Move pure-JVM tests (e.g., `ExampleUnitTest`) to a separate module without Robolectric
   - Consider `--parallel` + fewer forks if flakiness stays gone

3. **Clean up temporary refactor files** — `/tmp/refactor/` contains moved test files (`WhisperRecognitionServiceTest.kt`, etc.) that can be deleted

## Other Notes

- The 3 moved test files from the RecognitionService removal are in `/tmp/refactor/` (not in repo)
- `ExampleUnitTest` is a trivial 1-test class; could be removed or kept
- `org.gradle.parallel=true` in gradle.properties + `maxParallelForks = availableProcessors/2` gives good parallelism with `forkEvery=1`
- `just test` still the canonical command; it now completes deterministically