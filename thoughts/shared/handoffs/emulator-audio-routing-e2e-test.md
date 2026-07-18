---
date: 2026-07-15T17:30:00-07:00
researcher: pi-agent
git_commit: $(git rev-parse HEAD)
branch: main
repository: whisper-to-input
topic: "Android Emulator Audio Routing for E2E Transcription Testing"
tags: [emulator, audio, pipewire, e2e-testing, elevenlabs, voxtral]
status: in_progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: handoff
---

# Handoff: Android Emulator Audio Routing for E2E Transcription Testing

## Task(s)

**Primary Goal**: Show evidence that whisper-to-input app works by capturing screenshot of text appearing on screen from voice transcription, plus screenshot of new voice input methods in dropdown.

**Current Status**: Blocked on emulator audio routing. The Android emulator's microphone does not receive audio from PipeWire virtual audio devices. Multiple approaches tried:

1. ✅ **Identified root cause**: Emulator started WITHOUT `-allow-host-audio` flag - restarted with flag
2. ✅ **Configured ElevenLabs Scribe via UI**: Backend selected, endpoint correct, API key entered, settings applied
3. ✅ **Created PipeWire virtual audio chain**: VirtualMicSink → FakeMic → QEMU
4. ✅ **Connected QEMU to FakeMic** via `pactl move-source-output`
5. ❌ **Audio still not reaching emulator mic** - FSM always times out in Idle state
6. ⏳ **Tried alternative routing**: host output monitor, module-pipe-source, module-loopback - none worked

## Critical References

- `/var/home/l/git/whisper-to-input/run_e2e_test.sh` - E2E test script
- `/var/home/l/git/whisper-to-input/scripts/write_datastore.py` - DataStore configuration
- `/var/home/l/git/whisper-to-input/android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt:145-178` - FSM thresholds (idle_speaking_threshold=800, speaking_finish_threshold=800, speaking_finish_time=3000ms)
- `/var/home/l/git/whisper-to-input/thoughts/shared/showboat/elevenlabs-e2e.md` - Existing evidence document

## Recent Changes

- Restarted emulator with `-allow-host-audio` flag (was missing - root cause found)
- Connected QEMU to FakeMic source via `pactl move-source-output 450 102`
- Configured ElevenLabs Scribe via UI (persisted across restart)
- Tested multiple audio routing approaches via PipeWire

## Learnings

1. **`-allow-host-audio` is MANDATORY** - Without this flag, emulator ignores host audio input entirely. Flag shows "Warning: Allowing host microphone input." in emulator logs.

2. **FSM thresholds**: Idle→Speaking requires amplitude > 800; Speaking→Finish requires 3 seconds of silence (amplitude ≤ 800). Current emulator mic always produces noise or silence.

3. **QEMU audio backend**: The Android emulator uses PulseAudio protocol via PipeWire. Source-output 450 connects QEMU (client 444) to FakeMic (source 102) but audio doesn't flow.

4. **ElevenLabs quota**: API key `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887` may be quota-exceeded (0/10000 credits). Earlier test (2026-07-13) returned "Hello? What's going on?".

5. **DataStore format incompatible**: Direct protobuf writing causes `CorruptionException: Value not set`. Must use UI or Kotlin Serialization.

## Artifacts

- `/var/home/l/git/whisper-to-input/thoughts/shared/showboat/elevenlabs-e2e.md` - Existing evidence (dropdown screenshots, API test, config screenshots)
- `/var/home/l/git/whisper-to-input/thoughts/shared/showboat/elevenlabs-voxtral-evidence.md` - Additional provider evidence
- Screenshots in `/tmp/allow-host-audio-settings.png`, `/tmp/e2e-final*.png`
- Emulator PID: 42391 (running with `-allow-host-audio`)

## Action Items & Next Steps

1. **Try pavucontrol routing**: Install `pavucontrol`, launch emulator, go to Recording tab, find "Android Emulator"/"QEMU", change input to "Monitor of VirtualMicSink" or "FakeMic"

2. **Try QEMU environment variables** (before starting emulator):
   ```bash
   export QEMU_AUDIO_DRV=pa
   export QEMU_PA_SOURCE=VirtualMicSink.monitor
   ```

3. **Try module-pipe-source with real audio data**: Write audio directly to FIFO:
   ```bash
   cat test-sources/test-audio.wav > /tmp/audio_input.wav &
   ```

4. **If all fails**: Test on physical Android device, or accept API test + UI screenshots as sufficient evidence

## Other Notes

- App package: `com.example.whispertoinput`, Service: `.WhisperInputService`
- Test audio: `/var/home/l/git/whisper-to-input/test-sources/test-audio.wav` (4.14s, expected: "Hello? What's going on?")
- ADB: `/var/home/l/Android/Sdk/platform-tools/adb`
- Emulator: `/var/home/l/Android/Sdk/emulator/emulator -avd Pixel_8 -no-snapshot-load -allow-host-audio`
- Virtual audio: VirtualMicSink (sink), FakeMic (source) - both at 100% volume
- The key missing evidence: screenshot of transcribed text appearing in browser URL field