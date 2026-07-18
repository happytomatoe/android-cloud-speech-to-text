---
date: 2026-07-16T07:45:00+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test: Second Broadcast Never Arrives — Transcription Blocked"
tags: [e2e, tap-to-toggle, ime, broadcast-receiver, transcription, debug-logging]
status: in_progress
last_updated: 2026-07-16
last_updated_by: pi-agent
type: handoff
---

# Handoff: E2E Test — Second Broadcast Never Arrives (Transcription Blocked)

## Task(s)

**Primary goal:** Get `run_e2e_test.sh` passing for the tap-to-toggle recording flow.

Progress from parent handoff (`e2e-test-final-fixes-recording-transcription.md`):

### Completed in this session

| Item | Status | Details |
|------|--------|---------|
| `focus_text_field` rewrite | ✅ Fixed | Now taps `search_action_bar_title` first, then `search_src_text`, with coordinate fallback `(540, 215)`. Keyboard reliably appears (`mIsInputViewShown=true`). |
| `cleanup_virtual_mic` multi-module bug | ✅ Fixed | Changed from `run_cmd "pactl unload-module $(...)"` to iterating module IDs individually with `while read -r mid; do pactl unload-module "$mid"; done`. |
| Debug logging added to app | ✅ Added | `Log.d("whisper-input", ...)` in `onReceive` and `toggleRecording` — confirms first broadcast works, second never arrives. **TEMPORARY — remove before commit.** |
| `import android.util.Log` re-added | ✅ Added | Was removed in prior session; re-added for debug logging. |

### Still open

| Item | Status | Blocker |
|------|--------|---------|
| **Second broadcast never reaches receiver** | ❌ **BLOCKING** | First `am broadcast -a ...TOGGLE_RECORDING` works perfectly. Second broadcast (sent after 5s to stop recording) never arrives. Logcat shows zero "Broadcast received" for it. Recording never stops → no transcription → test fails at timeout. |
| Full E2E script pass | ❌ Blocked by above | All prior fixes in place; this is the last blocker. |
| `write_datastore.py` compatibility | ⚠️ Suspect | Protobuf causes `CorruptionException` in DataStore. UI config path works. Not a current blocker (script uses UI config). |
| Transcription never tested | ❌ Blocked | Cannot verify transcription if recording never stops. |

## Critical References

- `run_e2e_test.sh` — the E2E harness (all script changes live here)
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — broadcast hook + RECEIVER_EXPORTED + debug logging
- `thoughts/shared/handoffs/e2e-test-final-fixes-recording-transcription.md` — parent handoff

## Recent changes

**`run_e2e_test.sh`**
- `focus_text_field()` (lines ~517-550): Rewrote to two-step process: (1) `ui_tap --rid com.android.settings:id/search_action_bar_title`, (2) `ui_tap --rid android:id/search_src_text`, with `adb shell input tap 540 215` coordinate fallback. `am start` sleep bumped from 2s → 3s.
- `cleanup_virtual_mic()` (lines ~147-156): Changed from single `run_cmd "pactl unload-module $(...)"` (fails with multi-line output) to `for pattern in ...; do pactl list short modules | grep "$pattern" | awk '{print $1}' | while read -r mid; do pactl unload-module "$mid"; done; done`.

**`android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`**
- Line 27: Re-added `import android.util.Log` (was removed in prior session).
- `onReceive()` (line ~75): Added `Log.d("whisper-input", "Broadcast received: action=${intent?.action}, isRecording=${recorderManager.isRecording}")`.
- `toggleRecording()` (line ~157): Added `Log.d` at entry (`isRecording=`), before stop (`Stopping recording, file=`), in transcription callback (`Transcription result: '$text'`), and in transcription error callback (`Transcription error: $msg`).
- **These debug logs are TEMPORARY — must be removed before commit.**

## Learnings

- **The broadcast "second-shot" problem.** The first `am broadcast -a ...TOGGLE_REGISTERED` is received reliably by the context-registered `BroadcastReceiver` in the IME service. The second broadcast (sent ~5s later via the same `am broadcast` command) **never arrives**. Confirmed by debug logging: logcat shows `"Broadcast received"` for the first broadcast but zero entries for the second. This is the **sole remaining blocker** for the E2E test.

- **`focus_text_field` root cause was a two-step problem.** The Settings search bar's EditText (`android:id/search_src_text`) doesn't exist on the homepage — only the title TextView (`search_action_bar_title`) is visible. Must tap the title first to activate search mode, then tap the EditText. The coordinate fallback `(540, 215)` is reliable.

- **`ui_tap --text "Search settings"` returns ELEMENT NOT FOUND** even though the text appears in `uiautomator dump`. This is likely a timing/encoding issue — the title node may not be tappable immediately after `am start`. The `--rid` approach works.

- **Keyboard IS working.** `mIsInputViewShown=true` confirmed. The IME shows when Settings search bar is focused. The issue is purely that the second broadcast doesn't arrive.

- **Recording DOES start.** First broadcast → `toggleRecording()` → `recorderManager.start()` → `"Recording started"` in logcat. All good. The problem is stopping it.

- **`am broadcast` + context-registered receiver in IME service is unreliable for repeated calls.** The `RECEIVER_EXPORTED` flag fixes the SecurityException, but something prevents the second broadcast from being delivered. This may be a QEMU emulator quirk, an Android background broadcast restriction, or an IME-service-specific issue.

## Artifacts

- `run_e2e_test.sh` — all harness changes (focus_text_field fix, cleanup_virtual_mic fix)
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — RECEIVER_EXPORTED + debug logging
- `thoughts/shared/handoffs/e2e-test-final-fixes-recording-transcription.md` — parent handoff
- `thoughts/shared/handoffs/e2e-test-recording-trigger-broadcast-hook.md` — grandparent handoff

## Action Items & Next Steps

1. **Fix the second-broadcast blocker.** Options ranked by simplicity:
   - **Option A (Recommended): Replace `am broadcast` with `input tap` on the mic button.** The IME keyboard occupies the bottom ~40% of the screen. The mic button (`btn_mic`) is centered horizontally. On Pixel 8 (1080×2400), approximate center: `(540, ~2280)`. Change `tap_mic_button()` from `am broadcast` to `adb shell input tap 540 2280`. This bypasses the broadcast mechanism entirely. May need to verify exact Y coordinate — the keyboard height varies.
   - **Option B: Use `am startservice` with intent action.** Start the service with a specific action that triggers toggle. More reliable than context-registered receivers for repeated calls.
   - **Option C: Send redundant broadcasts.** Send the second broadcast 2-3 times with 1s delays. Simple but hacky.
   - **Option D: Use AccessibilityService `dispatchGesture`.** Real tap on the IME mic button via accessibility. Most reliable but most complex to implement.

2. **After fixing the broadcast issue:** Run full E2E test end-to-end. If transcription appears in the search bar → TEST PASSED.

3. **Remove temporary debug logging** from `WhisperInputService.kt`:
   - Remove `import android.util.Log`
   - Remove all `Log.d("whisper-input", ...)` lines in `onReceive` and `toggleRecording`
   - Remove the lambda wrapping in `whisperTranscriber.startAsync()` callbacks (revert to `{ transcriptionCallback(it) }` and `{ transcriptionExceptionCallback(it) }`)

4. **Commit** all fixes (separate from provider work per prior handoff):
   - `focus_text_field` fix
   - `cleanup_virtual_mic` fix
   - RECEIVER_EXPORTED fix (already in codebase from prior session)
   - E2E script improvements

## Other Notes

- **Emulator state:** Must be killed and restarted for each test run. `pkill -9 -f qemu-system-x86_64` before running.
- **Audio chain:** `SilentTestSink → loopback → VirtualMicSink → monitor → FakeMic → QEMU_PA_SOURCE → emulator mic` (zero host speaker output). Verified working — `paplay --device=SilentTestSink` succeeds.
- **Debug log output format:** `whisper-input` tag, `D` level for normal, `E` for errors. Filter with `logcat -s whisper-input:V`.
- **The `ui_tap.py` script** uses hardcoded `DEV = "-s emulator-5554"`. Works fine when emulator is at that serial.
- **Current git status:** Working tree has all fixes applied but nothing committed. `git diff` shows changes in: `run_e2e_test.sh`, `WhisperInputService.kt` (with debug logs), plus untracked files under `thoughts/`, `scripts/`, `.agents/`, etc.
- **Deepgram key (verified):** `ba862dc7d60ebebe7257aa8f0c802890cb016789`
- **Test audio:** `/tmp/test-speech-loud.wav` (4.1s, 44100Hz mono, boosted 15dB). Generated by `espeak-ng` + `ffmpeg`.
