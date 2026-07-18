---
date: 2026-07-14T19:50:00-04:00
researcher: pi-agent
git_commit: $(git rev-parse HEAD 2>/dev/null || echo "unknown")
branch: main
repository: whisper-to-input
topic: "Emulator E2E Testing: Mic Button Issue & Audio Injection Research"
tags: [android, emulator, e2e-testing, audio-injection, elevenlabs]
status: superseded
last_updated: 2026-07-14
last_updated_by: pi-agent
type: research_findings
---

# Handoff: Emulator E2E Testing Discoveries

## Task(s)

Complete end-to-end testing of ElevenLabs Scribe transcription on Android emulator.

| Task | Status | Notes |
|------|--------|-------|
| Implementation (Phases 1-4) | ✅ Complete | All code committed, build+lint pass |
| Curl API test | ✅ Complete | Returns "Hello, what's going on?" |
| Emulator E2E test | 🟡 Blocked | Mic button tap doesn't work on emulator |

## Critical Issue: Mic Button Tap Not Working

**Problem:** `adb shell input tap` does not trigger the mic button in the Whisper To Input keyboard (IME window).

**Symptoms:**
- Keyboard shows correctly with mic button visible
- Tapping mic coordinates (x≈450, y≈1570-1600) does nothing
- Label stays "Whisper To Input" (never changes to "Recording...")
- No audio file created, no recording started

**Root Cause (Hypothesis):** IME windows have restricted touch event handling on Android emulator. `adb shell input tap` uses `INJECT_EVENTS` permission which may not work for IME windows.

## Research Findings

### Solution 1: Enable USB Debugging Security Settings
- In Developer Options, enable "USB debugging (Security Settings)"
- This grants adb higher-level access to input events
- **Requires manual toggle in emulator UI** (cannot be set via adb)

### Solution 2: Use `input touchscreen tap` Instead
```bash
$ADB shell input touchscreen tap 450 1570
```
- Targets the screen layer specifically
- May bypass IME restrictions

### Solution 3: Use Motion Events
```bash
$ADB shell input motionevent DOWN 450 1570
sleep 0.1
$ADB shell input motionevent UP 450 1570
```
- More granular control over touch simulation

### Solution 4: Audio Injection via PulseAudio (Linux)
For STT testing, bypass mic button entirely:
```bash
# Create virtual microphone
pactl load-module module-null-sink sink_name=virtual_mic
pactl load-module module-loopback source=virtual_mic.monitor

# Boot emulator with virtual mic
PULSE_SOURCE=virtual_mic.monitor emulator -avd your_avd -audio pulse

# Play audio during recording
paplay --device=virtual_mic test-audio.wav
```

### Solution 5: Emulator Extended Controls
1. Open Extended Controls (three dots in toolbar)
2. Go to Microphone
3. Load WAV file
4. Play during recording window

## Key Learning: XML First, Screenshots Second

**Always prefer `uiautomator dump` over screenshots when reading UI state.**

Benefits:
- Exact text, bounds, resource-ids for every element
- Programmatically parseable (no OCR needed)
- Faster execution (no image transfer)
- Reliable element identification

Example:
```bash
$ADB shell uiautomator dump /sdcard/ui.xml
$ADB shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
for node in tree.iter('node'):
    text = node.get('text', '')
    rid = node.get('resource-id', '')
    bounds = node.get('bounds', '')
    if text and len(text) > 2:
        print(f'{text[:60]} | id={rid} | bounds={bounds}')
"
```

Only use screenshots for visual verification (colors, icons, layout).

## Action Items & Next Steps

1. **Manual step required:** Enable "USB debugging (Security Settings)" in emulator Developer Options
   - Open Developer Options: `adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS`
   - Scroll to find "USB debugging (Security Settings)" and enable it
   - This cannot be done via adb - requires manual UI interaction

2. **After enabling security settings:** Retry mic button tap with `input tap`

3. **Alternative approach:** Use PulseAudio virtual mic on Linux host
   - Set up virtual microphone
   - Boot emulator with `PULSE_SOURCE=virtual_mic.monitor`
   - Play test audio via `paplay` during recording
   - This bypasses the mic button issue entirely

4. **Document E2E test:** Create showboat document with curl test as proof of API integration

## Artifacts

- New skill created: `/var/home/l/.pi/agent/skills/android-emulator/SKILL.md`
- Curl test command:
  ```bash
  curl -s -X POST 'https://api.elevenlabs.io/v1/speech-to-text' \
    -H 'xi-api-key: sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887' \
    -F 'file=@test-sources/test-audio.wav' \
    -F 'model_id=scribe_v1' \
    -F 'language_code=en' | jq -r '.text'
  # Returns: "Hello, what's going on?"
  ```

## Other Notes

- Emulator: `emulator-5554` (running)
- ADB: `/var/home/l/Android/Sdk/platform-tools/adb`
- API key: `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887`
- Test audio: `test-sources/test-audio.wav` (5s, "Hello? What's going on?")
- IME is registered: `com.example.whispertoinput/.WhisperInputService`
- App process running: `com.example.whispertoinput` (pid visible in `ps`)
