---
date: 2026-07-15T21:45:00-07:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test: Tap-to-Toggle — Recording Trigger Fix (debug broadcast hook)"
tags: [e2e, tap-to-toggle, ime, broadcast-receiver, audio-routing, deepgram]
status: in_progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: handoff
---

# Handoff: E2E Test — Recording Trigger Fix

## Task(s)

**Primary goal:** Get `run_e2e_test.sh` passing for the tap-to-toggle recording flow (FSM was removed earlier; working tree simplifies to manual start/stop).

- ✅ **Blocker 2 — audio plays through speakers:** FIXED. `run_e2e_test.sh` now routes test playback through a separate null sink (`SilentTestSink`) + `module-loopback` into the mic path, so nothing reaches the host speakers.
- 🔍 **Blocker 1 — recording never starts when tapping mic:** ROOT CAUSED and a fix implemented, but **not yet verified end-to-end**.
  - Root cause: the IME (keyboard) window runs in a *separate security context*; `input tap` / `uiautomator dump` cannot reach IME nodes. Proven: `dumpsys input_method` shows `mIsInputViewShown=true` yet `btn_mic` is absent from `uiautomator dump`, and an `input tap` on a real LatinIME key coordinate typed nothing.
  - Fix chosen (Option A): a **debug-guarded `BroadcastReceiver`** in `WhisperInputService` that calls the existing `toggleRecording()` — the exact same code path as the mic button. Wired into the test via `am broadcast`.
  - **Open issue:** the context-registered receiver may not reliably receive `am broadcast` from an IME-service process (manual test: broadcast returned `result=0` but no "TOGGLE broadcast received" / "Recording started" log appeared). Verification is currently blocked by a missing APK (see Learnings/Action Items).

## Critical References

- `thoughts/shared/handoffs/e2e-test-tap-to-toggle-live-test.md` — the handoff this session resumed from (documents both blockers + the SilentTestSink solution).
- `run_e2e_test.sh` — the E2E harness (all script changes live here).
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — broadcast hook added here.

## Recent changes

**`run_e2e_test.sh`**
- `setup_virtual_mic()`: creates `SilentTestSink` (null sink) + `module-loopback source=SilentTestSink.monitor sink=VirtualMicSink`; early-exit check also verifies `SilentTestSink`.
- `cleanup_virtual_mic()`: unloads `SilentTestSink` + `module-loopback` modules.
- `play_test_audio()`: `paplay --device=SilentTestSink` (was `VirtualMicSink`).
- `start_emulator()`: added `-no-window` (headless) — was headful.
- `focus_text_field()`: replaced hardcoded `(540,663)` with a `uiautomator`-based lookup. ⚠️ **BUGGY** — it matches the first node containing "search", which is the giant `search_panel` container `[0,132][1080,2274]`, not the real `android:id/search_src_text`. Should use `ui_tap.py --rid android:id/search_src_text` instead.
- `wait_for_recording()`: timeout 5s→10s; detects `prepare() failed`; dumps recent logcat on failure.
- `tap_mic_button()`: now sends `adb shell am broadcast -a com.example.whispertoinput.action.TOGGLE_RECORDING` (was `ui_tap --rid btn_mic`, which cannot reach the IME).

**`WhisperInputService.kt`**
- Companion `ACTION_TOGGLE_RECORDING = "com.example.whispertoinput.action.TOGGLE_RECORDING"`.
- `toggleReceiver` (inner `BroadcastReceiver`) → calls `toggleRecording()`.
- `onCreate()` registers the receiver, guarded by `isDebuggable()` (via `ApplicationInfo.FLAG_DEBUGGABLE`); sets `toggleReceiverRegistered = true`.
- `onDestroy()` unregisters if `toggleReceiverRegistered`.
- `isDebuggable()` helper.
- ⚠️ **TEMPORARY** `Log.d("whisper-input", …)` diagnostic lines added in `onCreate()` and `onReceive()` — remove before commit.

## Learnings

- **IME windows are not tappable by automation.** `uiautomator dump` cannot see IME nodes (separate security context); `input tap` does not reach IME buttons. The E2E test cannot trigger recording by tapping `btn_mic` — must use a broadcast (Option A) or `AccessibilityService.dispatchGesture` (Option B, real taps).
- **`BuildConfig` is NOT resolvable** in this module's generated sources (compile error `Unresolved reference: BuildConfig`). Use `ApplicationInfo.FLAG_DEBUGGABLE` for the debug guard instead.
- **"Scary" session:** the user's reference was the earlier whisper session `2026-07-15T17-11-38` (under `/var/home/l/.pi/agent/sessions/--var-home-l-git-whisper-to-input--/`). The user heard test audio through speakers; that session's handoff documented the `SilentTestSink` + `module-loopback` solution, which we applied.
- **Real Settings search bar** is `android:id/search_src_text` at center **(561, 215)** — NOT (540, 663). `ui_tap.py --rid android:id/search_src_text` works reliably (normal app window).
- **`am broadcast` + context-registered receiver in an IME process is suspect.** Manual test: broadcast sent `result=0` but neither the "TOGGLE broadcast received" nor "Recording started" log appeared. May need a **manifest-declared receiver with `exported=true`** (the `ADBKeyBoard` pattern) — manifest receivers reliably receive `am broadcast`. Possibly route via a static service-instance reference or `onStartCommand`.
- **Missing APK bug (current blocker):** `cd android && ./gradlew assembleDebug` reports `BUILD SUCCESSFUL` but **no `app-debug.apk` exists** at `android/app/build/outputs/apk/debug/app-debug.apk` (and `find android -name '*.apk' -path '*debug*'` returns nothing). Must resolve before re-running the test.
- **Reinstall caveat:** after `adb install -r`, the old process keeps running old code. Always `adb shell am force-stop com.example.whispertoinput` after reinstall so the new `onCreate()` (receiver registration) runs.
- **Deepgram key (verified):** `ba862dc7d60ebebe7257aa8f0c802890cb016789` — pass via `--key` or `DEEPGRAM_KEY` env.
- **Permissions** `RECORD_AUDIO` + `POST_NOTIFICATIONS` confirmed `granted=true` via `dumpsys package`.
- **FSM deleted** (confirmed): `RecorderManager.kt:36` "*No FSM, no amplitude monitoring — just record and stop.*" Flow is tap-to-toggle (tap mic = start; tap again = stop + transcribe; text committed). Matches user's mental model.

## Artifacts

- `run_e2e_test.sh` — all harness changes (audio, headless, dynamic focus, diagnostics, broadcast trigger).
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — broadcast hook + temporary diagnostic logs.
- `/tmp/2026-07-15-explanation-e2e-ime-recording.html` — self-contained HTML explainer (background, intuition, code, ranked options from web research, shuffled quiz).
- `thoughts/shared/handoffs/e2e-test-tap-to-toggle-live-test.md` — parent handoff.

## Action Items & Next Steps

1. **Fix missing-APK:** determine where `assembleDebug` actually emits the APK (or why it's absent) and reconcile with `run_e2e_test.sh` `build_and_install` path `android/app/build/outputs/apk/debug/app-debug.apk`. Rebuild and confirm the file exists.
2. **Verify broadcast hook:** re-run the manual check (force-stop → show keyboard → `am broadcast -a com.example.whispertoinput.action.TOGGLE_RECORDING` → expect "Recording started" in `logcat -s whisper-input:V`). If the context-registered receiver doesn't fire, convert to a **manifest-declared receiver** (`exported=true`, intent-filter for the action) that calls a static `WhisperInputService` instance or routes through `onStartCommand`. Follow the `ADBKeyBoard` pattern.
3. **Remove temp `Log.d` lines** from `WhisperInputService.kt` once verified.
4. **Fix `focus_text_field`** to use `ui_tap.py --rid android:id/search_src_text` instead of the buggy python search lookup.
5. **Run full E2E:** `./run_e2e_test.sh --backend deepgram --key "$DEEPGRAM_KEY" --expected "hello world"` (emulator already booted, headless). Expect `TEST PASSED`.
6. **Commit** FSM-removal + E2E fixes (significant; commit separately from provider work per prior handoff).

## Other Notes

- Emulator `emulator-5554` is currently running and booted; app installed but process may be stale — `am force-stop` after any reinstall.
- `ui_tap.py` (with `input tap`) still works fine for **normal app windows** (Settings search bar, app fields); only the **IME button** needs the broadcast.
- `just build` works with `JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"`.
- Audio chain after fix: `SilentTestSink → loopback → VirtualMicSink → monitor → FakeMic → QEMU emulator mic` (zero host speaker output).
