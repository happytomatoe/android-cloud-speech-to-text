# Add Voxtral and ElevenLabs Batch Transcription Providers to Android Project

## Overview

Add two new batch transcription providers to the Whisper To Input Android keyboard app:
1. **Voxtral** (Mistral AI) - `https://api.mistral.ai/v1/audio/transcriptions`
2. **ElevenLabs Scribe** - `https://api.elevenlabs.io/v1/speech-to-text`

Both are cloud-based batch transcription APIs compatible with the existing OkHttp-based architecture.

## Current State Analysis

**Existing providers in `WhisperTranscriber.kt`:**
- OpenAI API (whisper-1) - Bearer auth, `file` form field, model + response_format params
- Whisper ASR Webservice - No auth, `audio_file` form field, query params for language/task
- NVIDIA NIM - No auth (local), `file` form field, language + response_format params

**Pre-existing bugs in current code (to fix while implementing):**
1. **URL query-string bug**: OpenAI endpoint incorrectly gets Whisper ASR Webservice query params appended (`encode=true&task=transcribe&language=...&output=txt`)
2. **Content-Type header bug**: Manual `Content-Type: multipart/form-data` header (no boundary) conflicts with OkHttp's MultipartBody auto-generated boundary
3. **Voxtral JSON response not parsed**: Response is `{"text": "..."}` but current code only parses ElevenLabs JSON

**Key files to modify:**
1. `android/app/src/main/res/values/strings.xml` - Add provider names, defaults
2. `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` - Add request building + response parsing + fix bugs
3. `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` - Wire up default endpoint/model/language when backend changes
4. `README.md` - Document new provider configurations

## Desired End State

- User can select "Voxtral (Mistral)" or "ElevenLabs Scribe" from Speech to Text Backend dropdown
- Each provider has appropriate default endpoint, model, and language code
- Request format matches each provider's API specification
- Configuration examples documented in README
- Pre-existing bugs fixed

## Implementation Approach

Follow existing patterns in `WhisperTranscriber.buildWhisperRequest()` and `MainActivity.SettingStringDropdown`:
1. Add string constants for new providers
2. Add to `settings_speech_to_text_backend_array`
3. Add provider-specific request building logic (headers, form fields, URL params)
4. Handle provider-specific response parsing
5. Wire up default value population in settings UI
6. Fix pre-existing Content-Type header and URL query bugs

## What We're NOT Doing

- No streaming/real-time transcription (batch only)
- No changes to recorder, keyboard UI, or permissions
- No keyring/secure storage changes (uses existing DataStore)
- No Kotlin coroutine architecture changes

## Phase 1: Add String Resources

### File: `android/app/src/main/res/values/strings.xml`

**Add provider display names and defaults:**

```xml
<!-- Voxtral (Mistral) -->
<string name="settings_option_voxtral">Voxtral (Mistral)</string>
<string name="settings_option_voxtral_default_endpoint">https://api.mistral.ai/v1/audio/transcriptions</string>
<string name="settings_option_voxtral_default_model">voxtral-mini-latest</string>
<string name="settings_option_voxtral_default_language">auto</string>

<!-- ElevenLabs Scribe -->
<string name="settings_option_elevenlabs">ElevenLabs Scribe</string>
<string name="settings_option_elevenlabs_default_endpoint">https://api.elevenlabs.io/v1/speech-to-text</string>
<string name="settings_option_elevenlabs_default_model">scribe_v1</string>
<string name="settings_option_elevenlabs_default_language">auto</string>
```

**Update the backend array:**

```xml
<string-array name="settings_speech_to_text_backend_array">
    <item>@string/settings_option_openai_api</item>
    <item>@string/settings_option_whisper_asr_webservice</item>
    <item>@string/settings_option_nvidia_nim</item>
    <item>@string/settings_option_voxtral</item>
    <item>@string/settings_option_elevenlabs</item>
</string-array>
```

**Update description to include new providers:**

```xml
<string name="settings_speech_to_text_backend_desc">Use the official OpenAI API, Whisper ASR Webservice, NVIDIA NIM, Voxtral (Mistral), or ElevenLabs Scribe backend.</string>
```

## Phase 2: Update WhisperTranscriber.kt

### File: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

**Add required imports:**

```kotlin
import org.json.JSONException
import org.json.JSONObject
```

**Fix: Remove manual Content-Type header** (OkHttp's MultipartBody generates correct header with boundary)

**Fix: URL building - separate OpenAI from Whisper ASR Webservice**

**In `buildWhisperRequest()` method:**

```kotlin
private fun buildWhisperRequest(
    context: Context,
    filename: String,
    mediaType: String,
    speechToTextBackend: String,
    endpoint: String,
    languageCode: String,
    apiKey: String,
    model: String
): Request {
    val file: File = File(filename)
    val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
    val requestBody: RequestBody = MultipartBody.Builder().apply {
        setType(MultipartBody.FORM)
        val formDataFilename = if (mediaType == "audio/ogg") "@audio.ogg" else "@audio.m4a"

        // File field - Voxtral and ElevenLabs use "file" like OpenAI
        when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api),
            context.getString(R.string.settings_option_nvidia_nim),
            context.getString(R.string.settings_option_voxtral),
            context.getString(R.string.settings_option_elevenlabs) -> {
                addFormDataPart("file", formDataFilename, fileBody)
            }
            context.getString(R.string.settings_option_whisper_asr_webservice) -> {
                addFormDataPart("audio_file", formDataFilename, fileBody)
            }
        }

        // Provider-specific parameters
        when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api) -> {
                addFormDataPart("model", model)
                addFormDataPart("response_format", "text")
            }
            context.getString(R.string.settings_option_nvidia_nim) -> {
                addFormDataPart("language", languageCode)
                addFormDataPart("response_format", "text")
            }
            context.getString(R.string.settings_option_voxtral) -> {
                addFormDataPart("model", model)
                if (languageCode != "auto" && languageCode.isNotEmpty()) {
                    addFormDataPart("language", languageCode)
                }
            }
            context.getString(R.string.settings_option_elevenlabs) -> {
                addFormDataPart("model_id", model)
                if (languageCode != "auto" && languageCode.isNotEmpty()) {
                    addFormDataPart("language_code", languageCode)
                }
                // Optional: addFormDataPart("tag_audio_events", "false")
                // Optional: addFormDataPart("timestamps_granularity", "none")
            }
        }
    }.build()

    // Headers - NO manual Content-Type (OkHttp adds boundary)
    val requestHeaders: Headers = Headers.Builder().apply {
        when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api),
            context.getString(R.string.settings_option_voxtral) -> {
                if (apiKey == "") {
                    throw Exception(context.getString(R.string.error_apikey_unset))
                }
                add("Authorization", "Bearer $apiKey")
            }
            context.getString(R.string.settings_option_elevenlabs) -> {
                if (apiKey == "") {
                    throw Exception(context.getString(R.string.error_apikey_unset))
                }
                add("xi-api-key", apiKey)
            }
        }
    }.build()

    // URL building - FIXED: separate OpenAI from Whisper ASR Webservice
    val url = when (speechToTextBackend) {
        context.getString(R.string.settings_option_whisper_asr_webservice) -> {
            // Whisper ASR Webservice uses query params
            "$endpoint?encode=true&task=transcribe&language=$languageCode&word_timestamps=false&output=txt"
        }
        else -> endpoint  // OpenAI, NVIDIA NIM, Voxtral, ElevenLabs use direct endpoint
    }

    return Request.Builder()
        .headers(requestHeaders)
        .url(url)
        .post(requestBody)
        .build()
}
```

**Response parsing - handle Voxtral and ElevenLabs JSON responses:**

```kotlin
var rawText = response.body!!.string().trim()

// Handle Voxtral response: {"text": "transcription"}
if (speechToTextBackend == context.getString(R.string.settings_option_voxtral)) {
    try {
        val json = JSONObject(rawText)
        rawText = json.optString("text", "").trim()
    } catch (e: JSONException) {
        // If not JSON, use as-is
    }
}

// Handle ElevenLabs response: {"text": "transcription", "language_code": "en", ...}
if (speechToTextBackend == context.getString(R.string.settings_option_elevenlabs)) {
    try {
        val json = JSONObject(rawText)
        rawText = json.optString("text", "").trim()
    } catch (e: JSONException) {
        // If not JSON, use as-is
    }
}

// Existing NVIDIA NIM quote handling
if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim) && 
    rawText.startsWith("\"") && rawText.endsWith("\"")) {
    rawText = rawText.substring(1, rawText.length - 1).trim()
}
```

## Phase 3: Update MainActivity.kt (Default Value Wiring)

### File: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

**In `SettingStringDropdown.onItemSelected`, add cases for new providers:**

```kotlin
// Inside the when (parent.id == R.id.spinner_speech_to_text_backend) block:
if (selectedItem == getString(R.string.settings_option_openai_api)) {
    // ... existing code
} else if (selectedItem == getString(R.string.settings_option_whisper_asr_webservice)) {
    // ... existing code
} else if (selectedItem == getString(R.string.settings_option_nvidia_nim)) {
    // ... existing code
} else if (selectedItem == getString(R.string.settings_option_voxtral)) {
    val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
    if (endpointEditText.text.isEmpty() ||
        endpointEditText.text.toString() == getString(R.string.settings_option_openai_api_default_endpoint) ||
        endpointEditText.text.toString() == getString(R.string.settings_option_whisper_asr_webservice_default_endpoint) ||
        endpointEditText.text.toString() == getString(R.string.settings_option_nvidia_nim_default_endpoint)
    ) {
        endpointEditText.setText(getString(R.string.settings_option_voxtral_default_endpoint))
    }
    val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
    modelEditText.setText(getString(R.string.settings_option_voxtral_default_model))
    val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
    languageCodeEditText.setText(getString(R.string.settings_option_voxtral_default_language))
} else if (selectedItem == getString(R.string.settings_option_elevenlabs)) {
    val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
    if (endpointEditText.text.isEmpty() ||
        endpointEditText.text.toString() == getString(R.string.settings_option_openai_api_default_endpoint) ||
        endpointEditText.text.toString() == getString(R.string.settings_option_whisper_asr_webservice_default_endpoint) ||
        endpointEditText.text.toString() == getString(R.string.settings_option_nvidia_nim_default_endpoint)
    ) {
        endpointEditText.setText(getString(R.string.settings_option_elevenlabs_default_endpoint))
    }
    val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
    modelEditText.setText(getString(R.string.settings_option_elevenlabs_default_model))
    val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
    languageCodeEditText.setText(getString(R.string.settings_option_elevenlabs_default_language))
}
```

**Also update the `SettingStringDropdown` initialization to include new providers:**

```kotlin
SettingStringDropdown(R.id.spinner_speech_to_text_backend, SPEECH_TO_TEXT_BACKEND, listOf(
    getString(R.string.settings_option_openai_api),
    getString(R.string.settings_option_whisper_asr_webservice),
    getString(R.string.settings_option_nvidia_nim),
    getString(R.string.settings_option_voxtral),
    getString(R.string.settings_option_elevenlabs)
), getString(R.string.settings_option_openai_api)),
```

## Phase 4: Update README.md

Add configuration examples in the "Installation" section (step 5):

```
- Voxtral (Mistral):
  ```
  Speech to Text Backend:  Voxtral (Mistral)
  Endpoint:                https://api.mistral.ai/v1/audio/transcriptions
  API Key:                 <your Mistral API key>
  Model:                   voxtral-mini-latest
  Language Code:           auto
  ```

- ElevenLabs Scribe:
  ```
  Speech to Text Backend:  ElevenLabs Scribe
  Endpoint:                https://api.elevenlabs.io/v1/speech-to-text
  API Key:                 <your ElevenLabs API key>
  Model:                   scribe_v1
  Language Code:           auto
  ```
```

Also add provider entries in the "Services" section.

## Success Criteria

### Automated Verification:
- [ ] Project compiles: `./gradlew assembleDebug`
- [ ] No lint errors: `./gradlew lint`
- [ ] Strings properly formatted (no hardcoded strings in Kotlin)
- [ ] No duplicate Content-Type header bug
- [ ] URL building correctly separates providers

### Manual Verification:
- [ ] Voxtral appears in Speech to Text Backend dropdown
- [ ] ElevenLabs Scribe appears in Speech to Text Backend dropdown
- [ ] Selecting Voxtral populates default endpoint/model/language
- [ ] Selecting ElevenLabs populates default endpoint/model/language
- [ ] Voxtral transcription works with valid API key (requires paid key)
- [ ] ElevenLabs transcription works with valid API key (requires paid key)
- [ ] Language code parameter works for both providers
- [ ] Model parameter works for both providers
- [ ] Error handling shows appropriate message when API key missing
- [ ] Voxtral JSON response `{"text": "..."}` correctly extracted
- [ ] ElevenLabs JSON response `{"text": "..."}` correctly extracted

### Emulator Verification:
- [ ] App builds successfully with `./gradlew assembleDebug`
- [ ] APK installs on emulator via `adb install`
- [ ] App launches and shows settings screen
- [ ] Audio permission can be granted via ADB tap
- [ ] ElevenLabs dropdown selection works
- [ ] API key field accepts input
- [ ] Settings can be saved
- [ ] Keyboard can be switched to Whisper Input
- [ ] Transcription returns text (requires network + valid key)

## API Reference

### Voxtral (Mistral AI)
- Endpoint: `POST https://api.mistral.ai/v1/audio/transcriptions`
- Auth: `Authorization: Bearer <API_KEY>`
- Form fields: `file` (audio), `model` (string), `language` (optional, ISO 639-1)
- Response: `{"text": "transcription"}`

### ElevenLabs Scribe
- Endpoint: `POST https://api.elevenlabs.io/v1/speech-to-text`
- Auth: `xi-api-key: <API_KEY>`
- Form fields: `file` (audio), `model_id` (string), `language_code` (optional, ISO 639-1/3), `tag_audio_events` (bool), `timestamps_granularity` (string)
- Response: `{"text": "transcription", "language_code": "en", "language_probability": 0.99}`

### Verified Test Output (2026-07-13)
- Test audio: `test-sources/test-audio.wav` (5s, 44.1kHz stereo WAV, recorded voice)
  - Source: `/tmp/test-audio.wav` (copied to repo, gitignored)
- API key: `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887`
- Model: `scribe_v1`, Language: `en`
- **Expected transcription**: `"Hello? What's going on?"`
- Full response: `language_code=eng`, `language_probability=1.0`, `audio_duration_secs=5.0`
- Verified via curl and Python voice-to-text package

## Phase 5: End-to-End Testing with Showboat Evidence

### Overview

Prove the implementation works by running the app on an Android emulator, configuring ElevenLabs Scribe, injecting test audio, and capturing screenshots at each step. All evidence is recorded in a showboat document for reproducible verification.

### Prerequisites

- Android emulator running (emulator-5554)
- ADB: `/var/home/l/Android/Sdk/platform-tools/adb`
- Test audio: `test-sources/test-audio.wav` (5s, 44.1kHz stereo WAV, says "Hello? What's going on?")
- ElevenLabs API key: `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887`
- Showboat installed: `uv tool install showboat`

### Step 1: Build & Install

```bash
cd /var/home/l/git/whisper-to-input/android
JAVA_HOME=/var/home/l/.local/share/JetBrains/Toolbox/apps/android-studio/jbr \
  ANDROID_HOME=/var/home/l/Android/Sdk \
  ./gradlew assembleDebug

/var/home/l/Android/Sdk/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Launch App & Grant Permissions

```bash
/var/home/l/Android/Sdk/platform-tools/adb shell am start -n com.example.whispertoinput/.MainActivity
# Wait for UI, dump hierarchy, find and tap permission buttons as needed
```

### Step 3: Configure ElevenLabs Scribe

Via `adb shell input tap` commands:
1. Tap **Speech to Text Backend** dropdown → select **ElevenLabs Scribe**
2. Verify defaults auto-populate: endpoint `https://api.elevenlabs.io/v1/speech-to-text`, model `scribe_v1`, language `auto`
3. Tap **API Key** field → enter `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887`
4. Tap **Apply**

Screenshot: settings configured with ElevenLabs selected.

### Step 4: Switch to Whisper Input Keyboard

1. Open a text input field (e.g., browser search bar)
2. Switch keyboard to **Whisper Input**
3. Screenshot: keyboard visible with mic button

### Step 5: Inject Audio & Transcribe

**Audio injection approach**: Use emulator Extended Controls → Microphone → Load WAV → Play.

Agent/human coordination:
1. Agent: tap mic button (start recording)
2. Human: load `test-sources/test-audio.wav` in Extended Controls → play
3. Agent: wait ~5s for audio to finish, tap mic again (stop recording)
4. Agent: poll UI for transcribed text
5. Agent: screenshot when text appears

**Expected text**: `"Hello? What's going on?"`

### Step 6: Verify & Capture Evidence

```bash
# Dump UI hierarchy to extract transcribed text
/var/home/l/Android/Sdk/platform-tools/adb shell uiautomator dump /sdcard/ui.xml
/var/home/l/Android/Sdk/platform-tools/adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"'

# Screenshot final result
/var/home/l/Android/Sdk/platform-tools/adb shell screencap -p /sdcard/result.png
/var/home/l/Android/Sdk/platform-tools/adb pull /sdcard/result.png /tmp/elevenlabs-result.png
```

### Step 7: Record Everything in Showboat

Create showboat document with:
- Commentary describing each step
- `showboat exec` blocks for build, install, curl API test
- `showboat image` for each screenshot (settings, keyboard, result)
- `showboat verify` to prove all blocks still produce expected output

```bash
# Init
showboat init thoughts/shared/showboat/elevenlabs-e2e.md \
  "Whisper To Input: ElevenLabs Scribe E2E Test"

# Notes
showboat note thoughts/shared/showboat/elevenlabs-e2e.md \
  "Proves ElevenLabs Scribe transcription works end-to-end"

# Curl test (proves API returns expected text)
showboat exec thoughts/shared/showboat/elevenlabs-e2e.md bash \
  "curl -s -X POST 'https://api.elevenlabs.io/v1/speech-to-text' \
     -H 'xi-api-key: sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887' \
     -F 'file=@test-sources/test-audio.wav' -F 'model_id=scribe_v1' -F 'language_code=en' | jq -r '.text'"

# Screenshots
showboat image thoughts/shared/showboat/elevenlabs-e2e.md settings-configured.png
showboat image thoughts/shared/showboat/elevenlabs-e2e.md keyboard-visible.png
showboat image thoughts/shared/showboat/elevenlabs-e2e.md transcription-result.png

# Verify
showboat verify thoughts/shared/showboat/elevenlabs-e2e.md
```

### Success Criteria

- App builds and installs successfully
- ElevenLabs Scribe appears in dropdown with correct defaults
- API key can be entered and saved
- Audio injection triggers transcription
- **Transcribed text matches** `"Hello? What's going on?"`
- Showboat document captures all steps with screenshots
- `showboat verify` passes (exit code 0)

## References

- Python implementation reference: `/var/home/l/git/voice-to-text/src/voice_to_text/providers/voxtral.py` and `elevenlabs.py`
- Existing Android provider patterns: `WhisperTranscriber.kt` lines 150-220
- String resources: `strings.xml` lines 25-45
- Settings UI wiring: `MainActivity.kt` lines 160-220
- Argent MCP server configured for automated UI interaction
- ADB path: `/var/home/l/Android/Sdk/platform-tools/adb`
