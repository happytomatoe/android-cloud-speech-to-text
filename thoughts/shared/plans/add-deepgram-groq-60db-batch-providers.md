# Add Deepgram, Groq, and 60db Batch Transcription Providers to the Android App

## Overview

Port the remaining **batch** transcription providers from `/var/home/l/git/voice-to-text`
(`providers/deepgram.py`, `providers/groq.py`, `providers/sixty.py`) into the Whisper To Input
Android keyboard app, following the existing OkHttp-based provider pattern in
`WhisperTranscriber.kt`. **Parakeet is intentionally excluded** (per request), and the
streaming halves of these providers are out of scope — only batch transcription is added.

This continues the prior `feature/voxtral-elevenlabs` work, which already added Voxtral and
ElevenLabs. The three providers not yet present in the Android app are: **Deepgram**, **Groq**,
and **60db**.

## Current State Analysis

**Providers already wired in the Android app** (`strings.xml` backend array + `WhisperTranscriber.kt`
+ `MainActivity.kt`):
- OpenAI API (`whisper-1`) — Bearer auth, multipart `file` + `model` + `response_format=text`, plain-text response
- Whisper ASR Webservice — no auth, multipart `audio_file`, query params
- NVIDIA NIM — no auth (local), multipart `file`, language + response_format
- Voxtral (Mistral) — Bearer auth, multipart `file` + `model` + optional `language`, JSON `{"text": ...}`
- ElevenLabs Scribe — `xi-api-key` auth, multipart `file` + `model_id` + optional `language_code`, JSON `{"text": ...}`

**Batch providers in `voice-to-text` (reference Python):** `groq`, `deepgram`, `voxtral`,
`parakeet` (excluded), `60db`, `elevenlabs`. After excluding parakeet and the two already done,
the new ones are **deepgram, groq, 60db**.

**Audio format reality** (`WhisperInputService.updateAudioFormat`, `RecorderManager.kt`):
- NVIDIA NIM → OGG/Opus (`audio/ogg`)
- All other backends → MPEG-4/AMR (`audio/mp4`)
- New providers will receive `audio/mp4` by default. Groq, Deepgram, and 60db all accept
  `audio/mp4`/`m4a` (Deepgram auto-detects encoding from the `Content-Type` header).

**Key files:**
1. `android/app/src/main/res/values/strings.xml` — provider display names, default endpoint/model/language, backend dropdown array, descriptions
2. `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` — request building (`buildWhisperRequest`) + response parsing
3. `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` — `SettingStringDropdown` default-value wiring
4. `README.md` — config examples + Services docs

## Desired End State

- User can select **Deepgram**, **Groq**, or **60db** from the "Speech to Text Backend" dropdown.
- Each populates correct default endpoint / model / language on selection.
- Request format matches each provider's API (see API Reference below).
- Transcriptions parse correctly from each provider's response shape.
- README documents all three new providers.

## Implementation Approach

Mirror the existing voxtral/elevenlabs pattern exactly:

1. **Groq** reuses the OpenAI-compatible shape: multipart `file` + `model` + `response_format=text`,
   `Bearer` auth, plain-text response (no JSON parsing needed).
2. **60db** is multipart `file` + optional `language`, `Bearer` auth, JSON `{"data":{"text":...}}`
   (fallback to top-level `{"text":...}`).
3. **Deepgram** is the special case: it takes a **raw binary body** (not multipart) with a
   `Content-Type` of the audio media type, `Authorization: Token <key>`, and model/language as
   **URL query params**. Response is JSON `results.channels[0].alternatives[0].transcript`.
   `buildWhisperRequest` returns early for Deepgram before the shared multipart builder.

## What We're NOT Doing

- No streaming / real-time transcription (batch only).
- No Parakeet provider (excluded by request).
- No changes to recorder, IME voice-only UI, keyboard, or permissions.
- No keyring / secure storage changes (uses existing DataStore `API_KEY`).
- No changes to audio-format selection logic (new providers use the default MPEG-4/AMR path).
- No version-code bump.

## Phase 1: Add String Resources

### File: `android/app/src/main/res/values/strings.xml`

Add three provider groups after the ElevenLabs block (after `settings_elevenlabs_api_key_url`):

```xml
<!-- Deepgram -->
<string name="settings_option_deepgram">Deepgram</string>
<string name="settings_option_deepgram_default_endpoint">https://api.deepgram.com/v1/listen</string>
<string name="settings_option_deepgram_default_model">nova-3</string>
<string name="settings_option_deepgram_default_language">auto</string>

<!-- Groq -->
<string name="settings_option_groq">Groq</string>
<string name="settings_option_groq_default_endpoint">https://api.groq.com/openai/v1/audio/transcriptions</string>
<string name="settings_option_groq_default_model">whisper-large-v3-turbo</string>
<string name="settings_option_groq_default_language">auto</string>

<!-- 60db -->
<string name="settings_option_60db">60db</string>
<string name="settings_option_60db_default_endpoint">https://api.60db.ai/stt</string>
<string name="settings_option_60db_default_model">60db-stt-v01</string>
<string name="settings_option_60db_default_language">auto</string>
```

Add the three new items to the backend dropdown array:

```xml
<string-array name="settings_speech_to_text_backend_array">
    <item>@string/settings_option_openai_api</item>
    <item>@string/settings_option_whisper_asr_webservice</item>
    <item>@string/settings_option_nvidia_nim</item>
    <item>@string/settings_option_voxtral</item>
    <item>@string/settings_option_elevenlabs</item>
    <item>@string/settings_option_deepgram</item>
    <item>@string/settings_option_groq</item>
    <item>@string/settings_option_60db</item>
</string-array>
```

Update description strings to mention the new providers and generalize the API-key note:

```xml
<string name="settings_speech_to_text_backend_desc">Use the official OpenAI API, Whisper ASR Webservice, NVIDIA NIM, Voxtral (Mistral), ElevenLabs Scribe, Deepgram, Groq, or 60db backend. An API key is required for OpenAI, NVIDIA NIM, Voxtral, ElevenLabs, Deepgram, Groq, and 60db.</string>

<string name="settings_endpoint_desc">The host and port for the STT endpoint (e.g., OpenAI API, Deepgram, Groq, 60db, Whisper ASR Webservice, or NVIDIA NIM).</string>

<string name="settings_api_key_desc">The API key for the selected backend (required for OpenAI, NVIDIA NIM, Voxtral, ElevenLabs, Deepgram, Groq, and 60db).</string>

<string name="error_apikey_unset">Error: API Key is not set in settings.</string>
```

## Phase 2: Update `WhisperTranscriber.kt`

### File: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

**Deepgram early-return (raw binary body + query params + Token auth).** Insert immediately
after `val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())`,
before the `MultipartBody.Builder()` block:

```kotlin
        // Deepgram: raw binary body + query params + Token auth (not multipart)
        if (speechToTextBackend == context.getString(R.string.settings_option_deepgram)) {
            if (apiKey == "") {
                throw Exception(context.getString(R.string.error_apikey_unset))
            }
            val deepgramParams = mutableListOf("model=" + if (model.isNotEmpty()) model else "nova-3")
            if (languageCode.isNotEmpty() && languageCode != "auto") {
                deepgramParams.add("language=$languageCode")
            } else {
                deepgramParams.add("detect_language=true")
            }
            val deepgramUrl = "$endpoint?${deepgramParams.joinToString("&")}"
            val deepgramBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
            return Request.Builder()
                .addHeader("Authorization", "Token $apiKey")
                .url(deepgramUrl)
                .post(deepgramBody)
                .build()
        }
```

**File field `when`** — add Groq and 60db to the `file` group:

```kotlin
            when (speechToTextBackend) {
                context.getString(R.string.settings_option_openai_api),
                context.getString(R.string.settings_option_nvidia_nim),
                context.getString(R.string.settings_option_voxtral),
                context.getString(R.string.settings_option_elevenlabs),
                context.getString(R.string.settings_option_groq),
                context.getString(R.string.settings_option_60db) -> {
                    addFormDataPart("file", formDataFilename, fileBody)
                }
                context.getString(R.string.settings_option_whisper_asr_webservice) -> {
                    addFormDataPart("audio_file", formDataFilename, fileBody)
                }
            }
```

**Provider-specific params `when`** — add Groq (model + response_format, like OpenAI) and 60db
(optional language) cases, before the closing `}` of the `when`:

```kotlin
                context.getString(R.string.settings_option_groq) -> {
                    addFormDataPart("model", model)
                    addFormDataPart("response_format", "text")
                }
                context.getString(R.string.settings_option_60db) -> {
                    if (languageCode != "auto" && languageCode.isNotEmpty()) {
                        addFormDataPart("language", languageCode)
                    }
                }
```

**Headers `when`** — add Groq and 60db to the `Bearer` group (keep ElevenLabs on `xi-api-key`):

```kotlin
            when (speechToTextBackend) {
                context.getString(R.string.settings_option_openai_api),
                context.getString(R.string.settings_option_voxtral),
                context.getString(R.string.settings_option_groq),
                context.getString(R.string.settings_option_60db) -> {
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
```

**Response parsing** — after the ElevenLabs JSON block, add Deepgram and 60db parsing:

```kotlin
            // Handle Deepgram response: {"results":{"channels":[{"alternatives":[{"transcript":"..."}]}]}}
            if (speechToTextBackend == context.getString(R.string.settings_option_deepgram)) {
                try {
                    val json = JSONObject(rawText)
                    val transcript = json.optJSONObject("results")
                        ?.optJSONArray("channels")
                        ?.optJSONObject(0)
                        ?.optJSONArray("alternatives")
                        ?.optJSONObject(0)
                        ?.optString("transcript", "")
                    rawText = (transcript ?: "").trim()
                } catch (e: JSONException) {
                    // If not JSON, use as-is
                }
            }

            // Handle 60db response: {"data":{"text":"..."}} or {"text":"..."}
            if (speechToTextBackend == context.getString(R.string.settings_option_60db)) {
                try {
                    val json = JSONObject(rawText)
                    val data = json.optJSONObject("data")
                    rawText = if (data != null) {
                        data.optString("text", "").trim()
                    } else {
                        json.optString("text", "").trim()
                    }
                } catch (e: JSONException) {
                    // If not JSON, use as-is
                }
            }
```

(Groq returns plain text, so it needs no JSON handling — it falls through to the same path as OpenAI.)

## Phase 3: Update `MainActivity.kt` (Default Value Wiring)

### File: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

**A. Add the three providers to the `SettingStringDropdown` option list:**

```kotlin
                SettingStringDropdown(R.id.spinner_speech_to_text_backend, SPEECH_TO_TEXT_BACKEND, listOf(
                    getString(R.string.settings_option_openai_api),
                    getString(R.string.settings_option_whisper_asr_webservice),
                    getString(R.string.settings_option_nvidia_nim),
                    getString(R.string.settings_option_voxtral),
                    getString(R.string.settings_option_elevenlabs),
                    getString(R.string.settings_option_deepgram),
                    getString(R.string.settings_option_groq),
                    getString(R.string.settings_option_60db)
                ), getString(R.string.settings_option_openai_api)),
```

**B. Add three `else if` branches in `SettingStringDropdown.onItemSelected`** (after the
ElevenLabs branch), each populating defaults and resetting the endpoint only when it still
equals another provider's default. Example for Deepgram (repeat the same shape for Groq and 60db,
swapping in their `settings_option_*_default_*` strings and including all *other* default
endpoints in the reset guard):

```kotlin
                            } else if (selectedItem == getString(R.string.settings_option_deepgram)) {
                                val endpointEditText: EditText = findViewById<EditText>(R.id.field_endpoint)
                                if (endpointEditText.text.isEmpty() ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_openai_api_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_whisper_asr_webservice_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_nvidia_nim_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_voxtral_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_elevenlabs_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_groq_default_endpoint) ||
                                    endpointEditText.text.toString() == getString(R.string.settings_option_60db_default_endpoint)
                                ) {
                                    endpointEditText.setText(getString(R.string.settings_option_deepgram_default_endpoint))
                                }
                                val modelEditText: EditText = findViewById<EditText>(R.id.field_model)
                                modelEditText.setText(getString(R.string.settings_option_deepgram_default_model))
                                val languageCodeEditText: EditText = findViewById<EditText>(R.id.field_language_code)
                                languageCodeEditText.setText(getString(R.string.settings_option_deepgram_default_language))
                            }
```

## Phase 4: Update `README.md`

Add three configuration examples in the Installation section (after the ElevenLabs example,
before step 6):

```
   - Deepgram:
     ```
     Speech to Text Backend:  Deepgram
     Endpoint:                https://api.deepgram.com/v1/listen
     API Key:                 <your Deepgram API key>
     Model:                   nova-3
     Language Code:           auto
     ```
   - Groq:
     ```
     Speech to Text Backend:  Groq
     Endpoint:                https://api.groq.com/openai/v1/audio/transcriptions
     API Key:                 <your Groq API key>
     Model:                   whisper-large-v3-turbo
     Language Code:           auto
     ```
   - 60db:
     ```
     Speech to Text Backend:  60db
     Endpoint:                https://api.60db.ai/stt
     API Key:                 <your 60db API key>
     Model:                   60db-stt-v01
     Language Code:           auto
     ```
```

Add three Services sections (after the ElevenLabs Services section, before Debugging):

```
### Deepgram

Cloud-based speech-to-text API from Deepgram.

Requires a [Deepgram API key](https://console.deepgram.com/).

- Endpoint: `https://api.deepgram.com/v1/listen`
- Model: `nova-3` (or `nova-2`, `base`, etc.)
- Auth: `Authorization: Token <API_KEY>`
- Language: ISO 639-1 code (e.g., `en`, `zh`, `fr`) or `auto` for auto-detection (sent as `detect_language=true`)

See the [Deepgram API documentation](https://developers.deepgram.com/reference/pre-recorded) for more info.

### Groq

Cloud-based Whisper transcription API from Groq (OpenAI-compatible).

Requires a [Groq API key](https://console.groq.com/keys).

- Endpoint: `https://api.groq.com/openai/v1/audio/transcriptions`
- Model: `whisper-large-v3-turbo` (or `whisper-large-v3`)
- Auth: `Authorization: Bearer <API_KEY>`
- Language: ISO 639-1 code (e.g., `en`, `zh`) or `auto` for auto-detection

See the [Groq API documentation](https://console.groq.com/docs/speech-to-text) for more info.

### 60db

Cloud-based speech-to-text API from 60db.

Requires a [60db API key](https://docs.60db.ai/).

- Endpoint: `https://api.60db.ai/stt`
- Model: `60db-stt-v01`
- Auth: `Authorization: Bearer <API_KEY>`
- Language: ISO 639-1 code (e.g., `en`, `zh`) or `auto` for auto-detection

See the [60db API documentation](https://docs.60db.ai/api-reference/introduction) for more info.
```

## Success Criteria

### Automated Verification:
- [ ] Project compiles: `just build` (`./gradlew assembleDebug` with `JAVA_HOME=.../17.0.13-tem`)
- [ ] No lint errors: `./gradlew lint`
- [ ] All referenced strings exist (no missing `R.string.settings_option_*`)
- [ ] Deepgram returns a non-multipart raw-body request (verify via logcat / unit reasoning)

### Manual Verification (requires valid API keys — paid):
- [ ] Deepgram appears in the backend dropdown; selecting it populates `https://api.deepgram.com/v1/listen`, `nova-3`, `auto`
- [ ] Groq appears; selecting it populates `https://api.groq.com/openai/v1/audio/transcriptions`, `whisper-large-v3-turbo`, `auto`
- [ ] 60db appears; selecting it populates `https://api.60db.ai/stt`, `60db-stt-v01`, `auto`
- [ ] Deepgram transcription works (raw binary upload, `Token` auth, JSON transcript parsed)
- [ ] Groq transcription works (plain-text response)
- [ ] 60db transcription works (`data.text` JSON parsed)
- [ ] Selecting a new provider replaces a stale endpoint left from a previous provider
- [ ] Missing API key shows the generic "Error: API Key is not set in settings." message

### Emulator Verification (optional, per prior plan):
- [ ] App builds & installs on emulator (`just test-e2e`)
- [ ] New providers selectable; defaults auto-populate; settings save
- [ ] Transcription returns text (network + valid key required)

## API Reference (from `voice-to-text` reference implementations)

### Deepgram
- Endpoint: `POST https://api.deepgram.com/v1/listen`
- Auth: `Authorization: Token <API_KEY>`
- Body: raw audio binary, `Content-Type` = audio media type
- Query: `model` (default `nova-3`), `language` (ISO 639-1) or `detect_language=true`
- Response: `results.channels[0].alternatives[0].transcript`
- Ref: `voice-to-text/src/voice_to_text/providers/deepgram.py`

### Groq
- Endpoint: `POST https://api.groq.com/openai/v1/audio/transcriptions`
- Auth: `Authorization: Bearer <API_KEY>`
- Body: multipart `file`, `model`, `response_format=text`, optional `language`
- Response: plain text
- Ref: `voice-to-text/src/voice_to_text/providers/groq.py`

### 60db
- Endpoint: `POST https://api.60db.ai/stt`
- Auth: `Authorization: Bearer <API_KEY>`
- Body: multipart `file`, optional `language`
- Response: `{"data":{"text":"..."}}` (fallback top-level `{"text":"..."}`)
- Ref: `voice-to-text/src/voice_to_text/providers/sixty.py`

## Testing Strategy

- **Unit**: A small local HTTP mock (e.g., MockWebServer) could assert request shape per
  provider — raw binary + `Token` for Deepgram, multipart + `Bearer` for Groq/60db, and correct
  response parsing. (Optional; not required for merge.)
- **Manual / Emulator**: Build APK, install, configure each provider with a real API key, inject
  `test-sources/test-audio.wav`, verify transcribed text. Deepgram is the riskiest (raw body +
  query params) and should be verified first.

## References
- Existing provider pattern: `WhisperTranscriber.kt` (`buildWhisperRequest`, response parsing)
- Prior plan: `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`
- Python reference (source of truth for API behavior): `/var/home/l/git/voice-to-text/src/voice_to_text/providers/{deepgram,groq,sixty}.py`
- Settings wiring: `MainActivity.kt` `SettingStringDropdown`
- Audio format selection: `WhisperInputService.kt` `updateAudioFormat`
