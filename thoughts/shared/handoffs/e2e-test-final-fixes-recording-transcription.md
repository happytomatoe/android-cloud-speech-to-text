---
date: 2026-07-16T05:15:00+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Test: Final Fixes — Recording Trigger, Transcription Detection, Silent Audio"
tags: [e2e, tap-to-toggle, ime, broadcast-receiver, audio-routing, deepgram, recording]
status: in_progress
last_updated: 2026-07-16
last_updated_by: pi-agent
type: handoff
---

# Handoff: E2E Test — Final Fixes (Recording + Transcription + Silent Audio)

## Task(s)

**Primary goal:** Get `run_e2e_test.sh` passing for the tap-to-toggle recording flow.

Progress on the parent handoff's open items (see `e2e-test-recording-trigger-broadcast-hook.md`):

### Completed in this session

| Item | Status | Details |
|------|--------|---------|
| Missing APK (stale gradle UP-TO-DATE) | ✅ Fixed | `./gradlew clean assembleDebug` produces `android/app/build/outputs/apk/debug/app-debug.apk`. Was caused by deleted APK + UP-TO-DATE marker. |
| **Broadcast hook root cause** | ✅ Fixed | Service was **crashing on `onCreate()`** with `SecurityException: RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED must be specified` (targetSdk 34). Not a receiver delivery problem — the service never started. Fix: `Context.RECEIVER_EXPORTED`. Verified: `"Recording started"` log now appears. |
| `focus_text_field` buggy lookup | ✅ Rewritten | Replaced buggy inline python (matched the giant `search_panel` container) with `ui_tap --rid android:id/search_src_text` + retry loop polling `mIsInputViewShown=true`. |
| Temp `Log.d` diagnostic lines | ✅ Removed | Removed both from `onReceive` and `onCreate`; dropped now-unused `android.util.Log` import. |
| `enable_host_mic` → `disable_host_mic` | ✅ Fixed | Script's `enable_host_mic()` called `hostmicon` (ON), overriding FakeMic → would capture user's real voice ("scary" audio). Changed to `disable_host_mic()` calling `hostmicoff` so virtual FakeMic is used exclusively. |
| AGENTS.md §5 silent audio docs | ✅ Added | Full documentation of the SilentTestSink + module-loopback chain, the "scary" user complaint context, and the golden rule "never play test audio to the default speaker sink". |
| `wait_for_transcription` set -e bug | ✅ Fixed | `text=$(dump_ui | grep ... | head -1)` exits non-zero when grep finds no match → `set -e` aborts script after first empty iteration. Fix: `\|\| true` after both `text=$(...)` assignments. |
| Audio module state cleanup | ✅ Done | Unloaded 5 duplicate/broken pactl modules (2x VirtualMicSink, 2x FakeMic, 1x broken loopback). Script will recreate cleanly. |

### Still open

| Item | Status | Blocker |
|------|--------|---------|
| Full E2E script pass | ❌ Not yet | Script never re-run end-to-end after all fixes applied. See run attempts below. |
| Emulator boot timeout | ❌ Blocking | Last run (e2e_run2.log): emulator launched but `adb getprop sys.boot_completed` never returned 1 within 120s. Process was running (`qemu-system-x86_64-headless`) but ADB couldn't connect. Likely headless boot needs more time or a snapshot issue. |
| `write_datastore.py` compatibility | ⚠️ Suspect | Protobuf output causes `CorruptionException: Value not set` in DataStore `PreferencesSerializer.readFrom`. The app crashes on launch when DataStore is written by the script. UI config path works but is fragile. |
| App crash dialog overlay | ⚠️ Active | Crash from DataStore corruption shows "Application Error" dialog, blocking Settings UI interaction. Must be avoided (don't use write_datastore) or fixed. |
| Settings search field focus in loop | ⚠️ Intermittent | `ui_tap --rid android:id/search_src_text` sometimes returns "ELEMENT NOT FOUND" in retry loop, even though manual testing showed it works. May be caused by crash dialog or timing. |
| `cleanup_virtual_mic` multi-module bug | ⚠️ Minor | When `pactl list short modules | grep` returns multiple lines, `run_cmd` tries to execute them as separate commands, causing `command not found`. Should iterate and unload one module at a time. |

### Run attempts

1. **e2e_run.log** — Failed at `wait_for_transcription` due to `set -e` + `$(grep ...)` abort bug (now fixed). Recording started fine, audio played fine. Settings search field was NOT focused at dump time (showed homepage).
2. **debug_e2e.sh manual run** — `ui_tap --rid android:id/search_src_text` returned "ELEMENT NOT FOUND" all 5 retries. App crashed with `CorruptionException: Value not set` from `write_datastore.py` protobuf. No recording logs (service never started because keyboard never showed).
3. **e2e_run2.log** — Emulator boot timeout after 120s. Process was running but ADB couldn't connect (`getprop sys.boot_completed` never returned 1). Audio modules were recreated (new IDs 536870918/536870919) but then cleaned up in EXIT trap. Script didn't get past emulator boot.

## Critical References

- `run_e2e_test.sh` — the E2E harness (all script changes live here)
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — broadcast hook + RECEIVER_EXPORTED fix
- `thoughts/shared/handoffs/e2e-test-recording-trigger-broadcast-hook.md` — parent handoff (records both blockers + SilentTestSink solution)
- `AGENTS.md` §5 — now documents the silent audio technique for future reference

## Recent changes

**`run_e2e_test.sh`**
- `focus_text_field()`: rewrote to use `ui_tap --rid android:id/search_src_text` with retry loop (polls `dumpsys input_method` for `mIsInputViewShown=true`). Sleep bumped from 1s → 2s after `am start`.
- `enable_host_mic()` → `disable_host_mic()`: changed `hostmicon` → `hostmicoff`; updated call site.
- `wait_for_transcription()`: added `|| true` after both `text=$(...)` grep assignments to prevent `set -e` abort on empty match.

**`android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`**
- `onCreate()`: `registerReceiver(toggleReceiver, IntentFilter(ACTION_TOGGLE_RECORDING), Context.RECEIVER_EXPORTED)` — the RECEIVER_EXPORTED flag fixes the SecurityException crash on targetSdk 34.
- `onReceive()`: removed `Log.d("whisper-input", ...)` diagnostic line.
- `onCreate()`: removed `Log.d("whisper-input", ...)` diagnostic line.
- Removed unused `import android.util.Log`.

**`AGENTS.md` §5**
- Expanded from 8-line stub to full section documenting: the scary-session context, the PulseAudio chain diagram, setup commands, and the FakeMic-pin caveat.

## Learnings

- **The broadcast "not received" was a crash, not delivery.** The service was crashing on `onCreate()` due to `registerReceiver` needing `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` on targetSdk 34. Once fixed, the context-registered receiver works reliably and `am broadcast` reaches it. No manifest-declared receiver conversion needed.

- **`set -e` + `$(grep ...)` = silent script abort.** Under `set -e`, a variable assignment `var=$(grep ...)` aborts when grep returns non-zero (no match). This is a common bash trap. Always add `|| true` when capturing grep output that may be empty.

- **`write_datastore.py` protobuf is incompatible with DataStore PreferencesSerializer.** The script's `write_datastore.py` writes a protobuf that causes `CorruptionException: Value not set` when read by `PreferencesSerializer.readFrom`. This crashes the app on launch. The UI config path (open_settings/select_backend/set_api_key/apply_settings) avoids this by using the app's own preferences API, so **do not use write_datastore for DataStore** — use the UI config path instead, or fix the protobuf format.

- **`enable_host_mic` (hostmicon) is harmful for virtual-audio testing.** It switches the emulator to the host microphone, overriding `QEMU_PA_SOURCE=FakeMic`. Must be `hostmicoff` (or not called at all).

- **IME search field focus is timing-sensitive.** `ui_tap --rid android:id/search_src_text` works when Settings has loaded, but can fail if the Settings homepage isn't fully rendered. The retry loop with `mIsInputViewShown` polling helps but isn't bulletproof. An app crash dialog also blocks the UI and prevents element discovery.

- **PulseAudio cleanup needed before re-running.** The prior session left duplicate VirtualMicSink/FakeMic modules. Always unload stale pactl modules before `setup_virtual_mic()` to avoid duplicates. The script's `cleanup_virtual_mic` handles this on EXIT, but if the script didn't run, stale modules remain.

- **Headless emulator boot can take >120s.** The `EMULATOR_BOOT_TIMEOUT=120` in the script may not be enough. The emulator process was running but ADB never connected. Consider increasing to 180s or checking if the snapshot is stale.

- **`cleanup_virtual_mic` multi-line pactl bug.** When `pactl list short modules | grep` returns multiple module IDs (newline-separated), `run_cmd` tries to execute them as a single command with embedded newlines, causing `command not found`. Fix: iterate over each ID individually.

## Artifacts

- `run_e2e_test.sh` — all harness changes (focus_text_field fix, disable_host_mic, wait_for_transcription fix)
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` — RECEIVER_EXPORTED fix + temp log removal
- `AGENTS.md` §5 — silent audio documentation
- `thoughts/shared/handoffs/e2e-test-recording-trigger-broadcast-hook.md` — parent handoff
- `/tmp/transcribe.log` — debug logcat capture (empty; recording didn't start due to crash dialog)
- `/tmp/e2e_run.log` — first full script run (failed at wait_for_transcription)
- `/tmp/e2e_run2.log` — second full script run (failed at emulator boot timeout)
- `/tmp/debug_e2e.sh` — manual debug script (left emulator alive for inspection)

## Action Items & Next Steps

1. **Kill any stale emulator processes** and clean up audio modules:
   ```bash
   adb emu kill 2>/dev/null; pkill -f qemu-system-x86_64-headless
   # Unload any leftover pactl modules
   pactl list short modules | grep -E "null-sink|loopback|remap" | awk '{print $1}' | xargs -I{} pactl unload-module {}
   ```

2. **Re-run `run_e2e_test.sh` end-to-end** — all three fixes (RECEIVER_EXPORTED, wait_for_transcription, disable_host_mic) are in place:
   ```bash
   cd ~/git/whisper-to-input
   export DEEPGRAM_KEY="ba862dc7d60ebebe7257aa8f0c802890cb016789"
   ./run_e2e_test.sh --backend deepgram --key "$DEEPGRAM_KEY" --expected "hello world"
   ```
   If emulator boot timeout again, increase `EMULATOR_BOOT_TIMEOUT` from 120 to 180 in the script.

3. **If write_datastore is needed**: fix `scripts/write_datastore.py` to produce a valid PreferencesSerializer protobuf, OR always use the UI config path (open_settings/select_backend/set_api_key/apply_settings) which is what the script currently does. The UI path is fragile but avoids the CorruptionException.

4. **If Settings search field still not focused after fixes**: consider an alternative target field (e.g., the app's own `field_api_key` EditText in MainActivity) that's always visible and doesn't require search expansion. But this would change the test flow.

5. **Commit** FSM-removal + E2E fixes (separate from provider work per prior handoff). All temp logs already removed; RECEIVER_EXPORTED fix included.

6. **If focus_text_field keeps failing intermittently**: the root cause may be that `uiautomator dump` inside `ui_tap.py` is called too early before Settings renders. Increase the initial `am start` sleep, or use `adb shell input keyevent KEYCODE_SEARCH` to force-search focus instead of tapping.

## Other Notes

- The "scary" audio incident (user heard test audio through speakers) was from session `2026-07-15T17-11-38`. The fix (SilentTestSink + module-loopback) is now documented in AGENTS.md §5.
- The `just emulator-start` does NOT pin FakeMic (no `QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE`). Only the script's `start_emulator` does. If using `just emulator-start`, audio injection will silently fail.
- Audio chain: `SilentTestSink → loopback → VirtualMicSink → monitor → FakeMic → QEMU_PA_SOURCE → emulator mic` (zero host speaker output).
- `write_datastore.py` writes to `files/datastore/settings.preferences_pb` via `run-as`. It pushes to `/data/local/tmp/` first (world-readable), then copies with `run-as cp`.
- The `TRANSCRIPTION_TIMEOUT` is 30s. Transcription via Deepgram nova-3 typically takes 2–5s.
- Current git status: working tree has all fixes applied but nothing committed. `git diff` against `1e2b859` shows changes in: `run_e2e_test.sh`, `WhisperInputService.kt`, `AGENTS.md`, plus the new handoff docs under `thoughts/`.
- The emulator snapshot exists at `~/.android/avd/Pixel_8.avd/snapshots/default_boot/ram.bin`. If headless boot keeps timing out, try `just emulator-save-snapshot` to refresh it.
