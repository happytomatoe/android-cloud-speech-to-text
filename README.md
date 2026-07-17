# Whisper To Input

Whisper To Input (輕聲細語輸入法) is an Android keyboard that performs speech-to-text (STT/ASR) using cloud APIs and inputs the recognized text. Supports English, Chinese, Japanese, and more — including mixed languages and Taiwanese.

> **This is a fork of [j3soon/whisper-to-input](https://github.com/j3soon/whisper-to-input)** with additional backends and features.

## Supported Backends

| Backend | Type | API Key Required |
|---------|------|------------------|
| [Voxtral (Mistral)](https://docs.mistral.ai/api/#tag/audio/operation/createTranscription) | Cloud | ✅ |
| [ElevenLabs Scribe](https://elevenlabs.io/docs/api-reference/speech-to-text) | Cloud | ✅ |
| [Deepgram](https://developers.deepgram.com/reference/pre-recorded) | Cloud | ✅ |
| [Groq](https://console.groq.com/docs/speech-to-text) | Cloud | ✅ |
| [60db](https://docs.60db.ai/api-reference/introduction) | Cloud | ✅ |

## Installation

1. Download the APK from [Releases](https://github.com/happytomatoe/android-cloud-speech-to-text/releases/latest)
2. Install the APK on your Android device
3. Allow microphone and notification permissions
4. Open the app and configure your backend (API key, endpoint, model)
5. Enable the keyboard in System Settings → Languages & Input → On-screen keyboard
6. Select "Whisper Input" as your input method

## Configuration Examples

**Voxtral (Mistral):**
```
Endpoint:   https://api.mistral.ai/v1/audio/transcriptions
API Key:    <your key>
Model:      voxtral-mini-latest
Language:   auto
```

**ElevenLabs Scribe:**
```
Endpoint:   https://api.elevenlabs.io/v1/speech-to-text
API Key:    <your key>
Model:      scribe_v1
Language:   auto
```

**Deepgram:**
```
Endpoint:   https://api.deepgram.com/v1/listen
API Key:    <your key>
Model:      nova-3
Language:   auto
```

**Groq:**
```
Endpoint:   https://api.groq.com/openai/v1/audio/transcriptions
API Key:    <your key>
Model:      whisper-large-v3-turbo
Language:   auto
```

**60db:**
```
Endpoint:   https://api.60db.ai/stt
API Key:    <your key>
Model:      60db-stt-v01
Language:   auto
```

## Keyboard Usage

- **Microphone** (center): Tap to start/stop recording
- **Cancel** (left of mic): Cancel recording or transcription
- **Retry** (left of mic, shown on error): Retry the last transcription
- **Backspace** (right of mic): Delete previous character
- **Enter** (right of backspace): Newline (stops recording if active)
- **Space Bar** (bottom center): Insert space (stops recording if active)
- **Settings** (upper right): Open app settings
- **Switch** (upper left): Switch to previous input method

## Building

```bash
# Debug APK
just build

# Or manually
cd android && ./gradlew assembleDebug
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

## CI/CD

This project uses GitHub Actions for automated releases:

- **CI** (`ci.yml`): Builds debug APK on PRs
- **Release** (`release.yml`): Auto-versioning and GitHub release on merge to `main`
- **Auto-label** (`auto-label.yml`): Labels PRs based on conventional commit titles

### Versioning

PR labels control version bumps:
- `major` → 0.5.0 → 1.0.0
- `minor` → 0.5.0 → 0.6.0
- `patch` → 0.5.0 → 0.5.1
- `skip-release` → No release

PR titles with conventional commits (`feat:`, `fix:`, etc.) are auto-labeled.

## Debugging

Enable [USB debugging](https://developer.android.com/studio/debug/dev-options), connect your phone, and use:

```bash
adb logcat *:D  # Debug logs
adb logcat *:E  # Errors only
```

## Permissions

- `RECORD_AUDIO`: Required for voice input
- `INTERNET`: Required for cloud API calls
- `POST_NOTIFICATIONS`: Required for background error toasts

## License

GPLv3 — see [LICENSE](android/LICENSE)

**Original project:** [j3soon/whisper-to-input](https://github.com/j3soon/whisper-to-input)
**Original contributors:** Yan-Bin Diau ([@tigerpaws01](https://github.com/tigerpaws01)), Johnson Sun ([@j3soon](https://github.com/j3soon)), Ying-Chou Sun ([@ijsun](https://github.com/ijsun))



