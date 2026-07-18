---
date: 2026-07-16T07:15:00+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test: input tap Solves the IME Mic Button Problem"
tags: [e2e, input-tap, ime, tap-to-toggle, transcription, recording]
status: in_progress
last_updated: 2026-07-16
last_updated_by: pi-agent
type: handoff
---

# Handoff: E2E Test — `input tap` Solves the IME Mic Button Problem

## Task(s)

**Primary goal:** Get `run_e2e_test.sh` passing for the tap-to-toggle recording flow.

### Completed in this session

| Item | Status | Details |
|------|--------|---------|
| `am startservice` investigation | ❌ Dead end | `InputMethodService` requires `BIND_INPUT_METHOD` permission — `am startservice` from shell always fails with `Error: Requires permission android.permission.BIND_INPUT_METHOD`. Not viable. |
| **`input tap` on mic button** | ✅ **WORKS** | The prior assumption that "input tap can't reach the IME window" was **wrong**. `input tap 540 1980` reliably triggers both start and stop recording. Coordinates derived from `dumpsys window` showing IME frame at `[0,1670][1080,2400]` on Pixel_8. |
| `select_backend` spinner fix | ✅ Fixed | Replaced flaky `tap_by_rid` with `get_field_coords` + `input tap` with 5-retry loop. Also increased `open_settings` sleep from 2s → 3s. |
| Full E2E flow (minus transcription) | ✅ Verified | Backend selection, API key typing, settings apply, keyboard focus, recording start, audio playback, recording stop — all pass. |
| Transcription verification | ❌ Invalid API key | Deepgram key `ba862dc7d60ebebe7257aa8f0c802890cb016789` returns `INVALID_AUTH`. Need a valid key. |

### Still open

| Item | Status | Blocker |
|------|--------|---------|
| Valid Deepgram API key | ❌ Blocker for full pass | The key from prior sessions is expired. Need a working key to verify end-to-end transcription. |
| Debug code cleanup | ⚠️ Not started | `Log.d` calls, broadcast receiver, `onStartCommand` override in `WhisperInputService.kt` are temporary — remove before commit. |
| Commit all E2E fixes | ❌ Blocked by cleanup | `focus_text_field` fix, `cleanup_virtual_mic` fix, `select_backend` fix, `input tap` mic toggle — all uncommitted in `run_e2e_test.sh`. |

## Critical References

- `run_e2e_test.sh` — the E2E harness (all script changes live here)
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — has debug logging + broadcast receiver + onStartCommand (all temporary)
- `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt` — simple start/stop recorder

## Recent changes

**`run_e2e_test.sh`**

- `tap_mic_button()` (~line 564): Changed from `am broadcast` → `am startservice` → **now `input tap 540 1980`**. This is the final working solution.
  ```bash
  tap_mic_button() {
      log_info "Triggering mic toggle via input tap on IME mic button..."
      adb_cmd shell input tap 540 1980
      log_ok "Mic toggle tap sent"
  }
  ```

- `select_backend()` (~line 394): Rewrote spinner tap to use `get_field_coords` with 5-retry loop + `input tap` instead of flaky `tap_by_rid`. Added `sleep 1.5` after spinner tap before selecting item.

- `open_settings()` (~line 389): Increased `sleep 2` → `sleep 3` after `am start`.

**`WhisperInputService.kt`** (all temporary — remove before commit)

- Line 27: `import android.util.Log` (re-added for debug)
- Line 75: `Log.d` in `onReceive()`
- Lines 157-189: Multiple `Log.d` calls in `toggleRecording()`
- Lines 218-224: `onStartCommand()` override (not needed — `input tap` works)
- Lines 73-80: `toggleReceiver` broadcast receiver (not needed — `input tap` works)
- Lines 82-90: `registerReceiver` in `onCreate()` (not needed)

## Learnings

- **`input tap` CAN reach the IME window.** This was the key breakthrough. The prior handoff's claim that "Injected `input tap` / `uiautomator` cannot reach the IME (keyboard) window" was wrong. `input tap` sends events through InputDispatcher which routes to the topmost window at those coordinates — and that IS the IME window. The issue was always about finding the right coordinates.

- **How to find IME mic button coordinates:**
  1. Run `dumpsys window windows | grep -A20 "Window.*InputMethod"` to get the IME frame: `frame=[0,1670][1080,2400]`
  2. The keyboard layout (from `keyboard_view.xml`) has: top bar (10dp) → status label (~38dp) → mic button frame (140dp centered) → space bar
  3. At 420dpi (Pixel_8), 1dp = 2.625px. Mic center ≈ y=1670 + 26 + 100 + 184 = **1980**
  4. Horizontal center: x=540 (half of 1080)
  5. Coordinates: **(540, 1980)** — verified working

- **`am startservice` cannot start InputMethodService.** Android requires `BIND_INPUT_METHOD` permission. Only the system's `InputMethodManager` can bind to an IME service. `am startservice` from shell always fails.

- **`am broadcast` second call silently dropped.** First call works, second call (~19s later) is enqueued (exit 0) but never delivered to `onReceive()`. Root cause unknown — possibly Android background broadcast throttling or IME-specific quirk. Not worth investigating since `input tap` works.

- **`uiautomator dump` does NOT capture IME window content.** The keyboard views (including `btn_mic`) don't appear in `uiautomator dump` output. This is why `ui_tap --rid btn_mic` would fail. However, `input tap` at calculated coordinates works because it goes through InputDispatcher, not uiautomator.

- **`select_backend` spinner was always broken in prior runs.** Both prior test runs also failed at `select_backend` with "ELEMENT NOT FOUND" — the spinner tap never worked via `ui_tap --rid`. Fixed by using `get_field_coords` (which does its own `uiautomator dump`) + `input tap`.

- **Deepgram key is expired.** Key `ba862dc7d60ebebe7257aa8f0c802890cb016789` returns `INVALID_AUTH`. The transcription API call itself works (callback fires, error returned) — just need a valid key.

## Artifacts

- `run_e2e_test.sh` — all harness changes (input tap mic toggle, select_backend fix, open_settings sleep)
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — debug logging + broadcast receiver + onStartCommand (temporary)
- `thoughts/shared/handoffs/e2e-test-transcription-blocker-second-broadcast.md` — parent handoff (the problem this session solved)
- `thoughts/shared/handoffs/e2e-test-final-fixes-recording-transcription.md` — grandparent handoff

## Action Items & Next Steps

1. **Get a valid Deepgram API key** (or use another backend like Groq). Without it, the full E2E test can't pass transcription verification.

2. **Remove temporary debug code from `WhisperInputService.kt`:**
   - Remove `import android.util.Log`
   - Remove all `Log.d("whisper-input", ...)` and `Log.e("whisper-input", ...)` lines
   - Remove `toggleReceiver` broadcast receiver (lines 73-80)
   - Remove `registerReceiver` in `onCreate()` (lines 84-89)
   - Remove `onStartCommand()` override (lines 218-224)
   - Remove `unregisterReceiver` in `onDestroy()` (lines 222-225)
   - Revert lambda wrapping in `whisperTranscriber.startAsync()` callbacks (remove the debug log wrappers)

3. **Run full E2E test with valid key:**
   ```bash
   ./run_e2e_test.sh --backend deepgram --key <VALID_KEY> --expected "hello world"
   ```

4. **Commit all fixes** (separate from provider work per prior handoff):
   - `input tap` mic toggle (the main fix)
   - `select_backend` spinner fix
   - `focus_text_field` fix (from prior session)
   - `cleanup_virtual_mic` fix (from prior session)

5. **Update or close the parent handoff** (`e2e-test-transcription-blocker-second-broadcast.md`) since the broadcast blocker is resolved via a different approach.

## Other Notes

- **Emulator state:** Must be killed and restarted for each test run. `pkill -9 -f qemu-system-x86_64` before running, or let the script handle it.
- **Audio chain:** `SilentTestSink → loopback → VirtualMicSink → monitor → FakeMic → QEMU_PA_SOURCE → emulator mic` (zero host speaker output). Verified working.
- **IME window frame on Pixel_8:** `[0,1670][1080,2400]` (730px tall, 1080px wide). This is stable across runs but may vary by device/dpi.
- **The `ui_tap.py` script** uses hardcoded `DEV = "-s emulator-5554"`. Works fine when emulator is at that serial.
- **Debug log output format:** `whisper-input` tag, `D` level for normal, `E` for errors. Filter with `logcat -s whisper-input:V`.
- **Current git status:** Working tree has all fixes applied but nothing committed. `git diff` shows changes in: `run_e2e_test.sh`, `WhisperInputService.kt` (with debug logs), plus untracked files under `thoughts/`, `scripts/`, `.agents/`, etc.
