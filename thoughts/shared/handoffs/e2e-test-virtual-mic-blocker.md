---
date: 2026-07-16T18:33:46+02:00
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "E2E Testing - Virtual Mic Audio Routing Blocker"
tags: [e2e-testing, virtual-mic, pulseaudio, emulator, blocker]
---

# Handoff: E2E Testing Blocked by Virtual Mic Audio Routing

## Task(s)
- **E2E Testing with Real API**: Complete end-to-end transcription test showing "hello world" text on screen after: emulator boot → virtual mic → APK install → IME setup → backend config → recording → transcription. **STATUS: BLOCKED**

## Critical References
- `/var/home/l/git/whisper-to-input/run_e2e_test.sh` - Main E2E test script
- `/var/home/l/git/whisper-to-input/AGENTS.md` - Audio injection documentation (Section 5)
- `/var/home/l/git/whisper-to-input/android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` - Broadcast receiver for recording toggle

## Recent Changes
- `run_e2e_test.sh:224-231` - Added `--headful` flag support for running emulator with visible window
- `run_e2e_test.sh:750` - Added `HEADFUL=false` default variable
- `run_e2e_test.sh:766-769` - Added `--headful` case to argument parser

## Learnings

### Virtual Mic Architecture (from AGENTS.md Section 5)
```
SilentTestSink → loopback → VirtualMicSink → FakeMic → QEMU_PA_SOURCE
   (null sink)              (null sink)      (remap source)
   no speaker output        monitor source    emulator mic
```

### Blocker: Audio Reaches Emulator But Is Silent
**Evidence:**
1. Recording works - audio file created (52KB, 27 seconds)
2. File is pulled successfully from Android
3. API key works (tested with curl on host)
4. But transcription returns empty string because audio is silent
5. `ffmpeg -i recorded.m4a -af volumedetect` shows: mean_volume: -78.8 dB, max_volume: -66.2 dB (essentially silence)

**Root Cause Analysis:**
- Emulator IS connected to PulseAudio (verified: `application.process.binary = "qemu-system-x86_64"` in clients)
- Emulator IS recording from FakeMic source (Source #514)
- `QEMU_PA_SOURCE=FakeMic` and `QEMU_AUDIO_DRV=pa` ARE set in `/proc/PID/environ`
- Virtual mic chain IS set up correctly (modules loaded, sources exist)
- **BUT audio is not flowing from SilentTestSink through loopback to FakeMic**

**Key Finding: Two SilentTestSinks Exist**
```
Sink #440: SilentTestSink (IDLE)
Sink #524: SilentTestSink (SUSPENDED)
```
The loopback module may be connected to the wrong SilentTestSink instance. When test audio plays to `SilentTestSink`, it goes to Sink #440, but the loopback might be reading from a different monitor source.

### Deepgram API Key Status
- Key `f97f6e1e42b697792bfe1867f7679fdeaace4de8` works with curl (HTTP 200)
- But app shows "quota_exceeded" error sometimes (inconsistent - sometimes works, sometimes doesn't)
- When it works, transcription returns empty string due to silent audio

### Broadcast Receiver Works
- Recording toggle via `am broadcast -a com.example.whispertoinput.action.TOGGLE_RECORDING` works
- Must grant permissions AFTER `pm clear` (clear removes permissions)
- Keyboard must be shown before broadcast will start recording

## Artifacts
- `run_e2e_test.sh` - E2E test script with --headful flag
- `e2e_test.log` - Last test run log
- `/tmp/recorded_from_android.m4a` - Recorded audio file (52KB, silent)
- `thoughts/shared/handoffs/e2e-test-final-fixes-recording-transcription.md` - Previous handoff
- `thoughts/shared/handoffs/e2e-test-transcription-blocker-second-broadcast.md` - Previous handoff
- `thoughts/shared/handoffs/e2e-test-input-tap-solution.md` - Previous handoff

## Action Items & Next Steps

### Priority 1: Fix Virtual Mic Audio Routing
1. **Investigate why loopback isn't passing audio** - Check which SilentTestSink.monitor the loopback module is actually connected to
2. **Try destroying duplicate SilentTestSink** - There are two instances; remove the extra one
3. **Test audio flow manually**:
   ```bash
   # Play audio to SilentTestSink
   paplay --device=SilentTestSink /tmp/test-speech-loud.wav
   # Check if it appears on VirtualMicSink.monitor
   parecord -d VirtualMicSink.monitor --file-format=wav /tmp/test_virtualmic.wav
   # Check levels
   ffmpeg -i /tmp/test_virtualmic.wav -af volumedetect -f null /dev/null
   ```
4. **Alternative: Use `-audio pulse` flag** instead of env vars when launching emulator
5. **Alternative: Use Extended Controls virtual mic** as fallback (bypasses PulseAudio entirely)

### Priority 2: Complete E2E Test
1. Once audio routing works, run full E2E test:
   ```bash
   ./run_e2e_test.sh --backend deepgram --expected "hello world" --headful
   ```
2. Take screenshot showing "hello world" in Settings search bar
3. Present as proof of successful E2E test

### Priority 3: Clean Up Debug Code
- Remove debug `Log.d`/`Log.e` calls from `WhisperInputService.kt:156,160,192`
- Remove debug `BroadcastReceiver` and related code if not needed for production
- Remove `ACTION_TOGGLE_RECORDING` companion object from `WhisperInputService.kt:62`

## Other Notes

### Emulator Startup Requirements
- Must start emulator with `QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE=FakeMic` BEFORE any recording
- The `just emulator-start` command does NOT set these env vars
- Use `run_e2e_test.sh` which handles this correctly, or manually set env vars

### PulseAudio Module IDs
Current module IDs (may change between reboots):
- VirtualMicSink: 536870918
- FakeMic: 536870917
- SilentTestSink: 536870916
- Loopback: 536870921

### Test Audio File
- Location: `/tmp/test-speech-loud.wav`
- Content: "hello world this is a test of speech to text transcription"
- Generated by: `espeak-ng` + `ffmpeg` volume boost
- Peak level when tested directly: works with Deepgram API

### Key Discovery
The recording IS working (52KB file created), but the virtual mic chain is not passing audio to the emulator. The emulator records 27 seconds of silence. This is the single blocker preventing E2E test completion.
