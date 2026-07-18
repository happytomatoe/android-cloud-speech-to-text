---
date: 2026-07-14T23:00:00+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E test automation script for Deepgram/Groq/60db transcription providers"
tags: [implementation, android, whisper, deepgram, groq, 60db, e2e, emulator, transcription, bash]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: E2E Test Automation Script for Whisper To Input

## Task(s)

Create a fully automated end-to-end test script that validates the Deepgram/Groq/60db batch transcription providers work end-to-end on Android.

| Phase | Status | Notes |
|-------|--------|-------|
| Provider code (strings/WhisperTranscriber/MainActivity/README) | COMPLETE | Prior session |
| Manual e2e verification (Deepgram) | COMPLETE | Deepgram key validated, transcription committed |
| Manual e2e verification (Voxtral) | COMPLETE (pipeline) | Pipeline works, 401 from invalid key |
| Bug fix: Voxtral/ElevenLabs stale endpoint guard | COMPLETE | Fixed in MainActivity.kt |
| E2E automation script (bash) | IN PROGRESS | Script created, needs debugging |
| CI Integration (GitHub Actions) | PENDING | Phase 2 |
| Multi-backend parameterization | PENDING | Phase 3 |

## Critical References

- Plan: `thoughts/shared/plans/e2e-test-automation.md`
- Prior handoff: `thoughts/shared/handoffs/verify-deepgram-groq-60db-e2e.md`
- Provider code: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`
- Settings UI: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
- Test audio: `/tmp/test-speech-loud.wav` (espeak-ng + ffmpeg boost to 0 dB)
- UI helper: `scripts/ui_tap.py`

## Recent Changes

### Bug Fix: Voxtral/ElevenLabs Stale Endpoint Guard
- **File**: `MainActivity.kt` (lines 288-313, 301-316)
- **Issue**: Voxtral/ElevenLabs `onItemSelected` guards only checked for OpenAI/Whisper/NVIDIA endpoints. Switching from Deepgram/Groq/60db left stale endpoints.
- **Fix**: Extended guards to include all other providers (Deepgram, Groq, 60db, ElevenLabs/Voxtral). Verified working in UI.

### Manual E2E Verification (Deepgram)
- **Key**: `ba862dc7d60ebebe7257aa8f0c802890cb016789` (valid, HTTP 200)
- **Result**: Transcription committed: `autohello world this is a test on speech to text transcription`
- **FSM**: Idle → Speaking (amp=32767) → Finish (silence duration=3000ms)
- **Audio injection**: VirtualMicSink (null-sink) → FakeMic (remap-source) → emulator via `QEMU_PA_SOURCE=FakeMic`

## Key Learnings (for from-scratch restart)

1. **Audio injection must pin virtual mic at emulator LAUNCH** via `QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE=FakeMic`. Changing host default source at runtime is unreliable.
2. **Recorder FSM needs loud audio** (amp > 800). WAV boosted to 0 dB peak (espeak-ng + ffmpeg +15dB).
3. **Whisper IME auto-starts recording** on keyboard show (`onStartInputView` → `startRecording()`). Keep keyboard visible until `Finish`.
4. **Whisper is voice-only IME** - no text keyboard. When it's default IME, you CANNOT type in settings fields. Must temporarily `ime set` LatinIME to type API keys.
4. **Settings in DataStore** (`name="settings"`, key `api-key`). No debug API - must drive UI.
5. **pm clear** resets DataStore cleanly for test isolation (but loses IME enable/permissions - must re-grant).
6. **Emulator boot is slow** (~30-60s). Use snapshots or reuse emulator across test runs.
5. **Settings UI automation** via `scripts/ui_tap.py` (uiautomator dump + tap by resource-id/text).
6. **Validate keys via host curl** before blaming app: `curl` with same params (Deepgram: raw body + Token + query params).

## Current State of Automation Script

**File**: `run_e2e_test.sh` (executable)

**What works**:
- Virtual mic setup (VirtualMicSink + FakeMic remap)
- Test audio generation (espeak-ng + ffmpeg boost)
- Emulator launch with pinned FakeMic (`QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE=FakeMic`)
- App install, permissions, IME enable
- Settings UI: backend selection, endpoint/model verification
- API key entry (switches to LatinIME temporarily)
- Focus text field → Whisper auto-records
- Audio injection via `paplay --device=VirtualMicSink`
- FSM monitoring via logcat (`whisper-input:V` tag)
- Transcription verification via UI dump

**Current issue**: The `start_emulator()` function has a logic bug - it kills the emulator then immediately calls cleanup due to `run_cmd` exit code handling. The script needs to:
1. Check if emulator already running/booted before killing
2. Fix the `run_cmd` exit code logic for `pkill` (returns 1 when nothing killed)
3. Add proper logging to file for debugging

## Next Actions

1. **Fix `start_emulator()`** in `run_e2e_test.sh`:
   - Check if emulator already running/booted first (reuse)
   - Fix `pkill` exit code handling (rc=1 when nothing to kill is OK)
   - Write emulator logs to file for debugging
   - Add more debug output with `set -x`

2. **Test with existing emulator** (already running and booted):
   ```bash
   export DEEPGRAM_KEY="ba862dc7d60ebebe7257aa8f0c802890cb016789"
   ./run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"
   ```

3. **Phase 2**: GitHub Actions workflow (`.github/workflows/e2e-test.yml`)
3. **Phase 3**: Multi-backend parameterization (`--backends deepgram groq 60db`)
4. **Phase 4**: Flakiness hardening (retries, screenshots, JUnit XML)

## Artifacts

- `run_e2e_test.sh` - Main automation script (executable)
- `thoughts/shared/plans/e2e-test-automation.md` - Full implementation plan
- `thoughts/shared/handoffs/verify-deepgram-groq-60db-e2e.md` - Previous handoff
- `/tmp/test-speech-loud.wav` - Test audio (0 dB peak, 4.1s)

## Resume Command

```bash
/resume_handoff thoughts/shared/handoffs/add-deepgram-groq-60db-batch-providers.md
```

Or for this session's work:
```bash
cd /var/home/l/git/whisper-to-input
export DEEPGRAM_KEY="ba862dc7d60ebebe7257aa8f0c802890cb016789"
./run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"
```