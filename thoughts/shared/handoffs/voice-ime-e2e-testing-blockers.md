---
date: 2025-07-17T00:15:00+00:00
git_commit: 67f64cbb780cfb2eb6c513e8ca599d5da5a42259
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "Voice IME E2E Testing - DataStore Protobuf & IME Window Blockers"
tags: [implementation, debugging, android, datastore, protobuf, e2e-testing]
---

# Handoff: Voice IME E2E Testing - DataStore Protobuf Encoding & IME Window Blockers

## Task(s)
- **Fix DataStore protobuf encoding** (COMPLETED) - Fixed write_datastore.py to use correct protobuf field numbers from androidx.datastore.preferences.proto (Value.string=5, Value.boolean=1, map entry field 2 for Value)
- **Fix WhisperInputService toggleRecording logic** (COMPLETED) - Added testFileModeRecording flag to track state in test file mode
- **Fix IME window not showing** (IN PROGRESS / BLOCKED) - IME window exists but mInputShown=false, visibility=GONE. Keyboard not displaying despite service being bound
- **Run full E2E test with test audio file** (PLANNED) - Once IME window shows, test file mode should transcribe /sdcard/test-speech-loud.wav via Deepgram

## Critical References
- **DataStore protobuf schema**: https://android.googlesource.com/platform/frameworks/support/+/f2e05c341382db64d127118a13451dcaa554b702/datastore/datastore-preferences-core/datastore-preferences-proto/src/main/proto/preferences.proto
  - Value.oneof: boolean=1, float=2, integer=3, long=4, string=5, string_set=6, double=7
  - MapEntry: key (field 1, string), value (field 2, Value)
  - Preferences.preferences = field 1 (map<string, Value>)
- **write_datastore.py**: scripts/write_datastore.py (updated with correct field numbers)
- **WhisperInputService.kt**: android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt (toggleRecording logic updated)
- **run_e2e_test.sh**: Main E2E test script with virtual mic routing

## Recent changes
- **scripts/write_datastore.py**: Fixed protobuf field numbers - Value.string=5 (was 4), Value.boolean=1, MapEntry value field=2
- **WhisperInputService.kt**: Added testFileModeRecording flag, restructured toggleRecording() to handle test file mode separately from normal recording
- **WhisperInputService.kt**: Added logging in onCreate, onCreateInputView, onReceive for broadcast debugging
- **RecorderManager.kt**: No changes needed

## Learnings
1. **DataStore protobuf encoding is extremely sensitive** - The "Value not set" CorruptionException occurs when the inner Value message has wrong field numbers. The official schema uses Value.string=5 (not 4), Value.boolean=1. MapEntry value is field 2 containing the Value message.

2. **IME window not showing despite onCreateInputView being called** - The InputMethodWindow exists (Window#6 InputMethod) but mInputShown=false, visibility=GONE. The IME service is bound and onCreateInputView returns the view, but InputMethodManager doesn't show it. Possible causes:
   - InputMethodService not properly implementing IME lifecycle
   - Missing IME subtype configuration in method.xml
   - SearchView in Settings not properly requesting IME
   - Need to verify mInputMethodManager.isActive(this) or similar

3. **Broadcast receiver working** - onReceive logs confirm TOGGLE_RECORDING broadcasts are received and toggleRecording() executes

4. **Test file mode works** - First broadcast sets testFileModeRecording=true, second should transcribe test file

## Artifacts
- **scripts/write_datastore.py** - Fixed protobuf encoding with correct field numbers
- **android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt** - Updated toggleRecording with testFileModeRecording flag
- **scripts/write_datastore.py:58-70** - encode_map_string_entry and encode_map_bool_entry with correct Value field numbers
- **WhisperInputService.kt:58** - testFileModeRecording flag
- **WhisperInputService.kt:140-200** - toggleRecording() with test file mode logic

## Action Items & Next Steps
1. **Debug IME window not showing** - The IME service is bound and onCreateInputView runs, but InputMethodManager doesn't show the window. Check:
   - method.xml subtype configuration
   - Whether InputMethodManager.showSoftInput() needs to be called explicitly
   - Whether the EditText in Quick Search Box properly triggers IME
   - Compare with working IME implementations (Gboard, LatinIME)

2. **Verify test file transcription** - Once IME window shows, test the full flow:
   - Tap mic button (broadcast 1) → testFileModeRecording=true
   - Tap mic button (broadcast 2) → should call whisperTranscriber.startAsync with test file
   - Verify Deepgram returns transcription and commits to SearchView

3. **Run full run_e2e_test.sh** - Once IME window works, the script should pass end-to-end

## Other Notes
- **Emulator audio routing**: run_e2e_test.sh sets up VirtualMicSink → FakeMic → QEMU with QEMU_PA_SOURCE=FakeMic. This part works.
- **DataStore file**: Must be pushed via `run-as com.example.whispertoinput cp /data/local/tmp/settings.preferences_pb files/datastore/settings.preferences_pb` (not /sdcard/)
- **Test audio**: /tmp/test-speech-loud.wav pushed to /sdcard/test-speech-loud.wav
- **Deepgram key**: f97f6e1e42b697792bfe1867f7679fdeaace4de8
- **Current APK**: Built and installed successfully