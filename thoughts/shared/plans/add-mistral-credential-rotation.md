# Add On-Device Mistral Credential Rotation for Voxtral

## Overview

Port the credential rotation approach from `~/git/mistral/mistral-rotate-rest.ts` to the whisper-to-input Android app. When the user selects Voxtral (Mistral) as the backend, they enter their Mistral email + password instead of an API key. The app auto-creates API keys via Mistral's auth API and auto-rotates on HTTP 401.

## Current State Analysis

- **Voxtral is fully implemented** in `WhisperTranscriber.kt` — multipart form + `Bearer $apiKey` auth
- **Settings** (`MainActivity.kt:59-63`): `SPEECH_TO_TEXT_BACKEND`, `ENDPOINT`, `API_KEY`, `MODEL` — all DataStore `stringPreferencesKey`
- **No credential rotation** — if the key expires, user must manually re-enter
- **Backend dropdown** already includes "Voxtral (Mistral)" with auto-fill for endpoint/model

### Key reference: `mistral-rotate-rest.ts` rotation flow
1. `POST https://auth.mistral.ai/self-service/login?flow={flowId}` with email/password → session cookies
2. `POST https://console.mistral.ai/api/billing/api-keys` with session cookies → new API key
3. Store new key

## Desired End State

- User selects "Voxtral (Mistral)" → enters email + password (not API key)
- First transcription auto-creates API key via Mistral auth API
- On HTTP 401: auto-rotate (re-login + create key) and retry once
- Deepgram and other backends unchanged (static API key)

## What We're NOT Doing

- Changing Deepgram or other backend behavior
- Renaming `API_KEY` to `CREDENTIALS` (separate task)
- Modifying the on-device whisper model
- Adding server-side rotation

---

## Phase 1: Settings — Add Mistral Credential Fields

### Overview
Add `MISTRAL_EMAIL` and `MISTRAL_PASSWORD` DataStore keys. Add UI fields in the settings activity.

### Changes Required:

#### 1. `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

Add new preference keys after line 63:

```kotlin
val MISTRAL_EMAIL = stringPreferencesKey("mistral-email")
val MISTRAL_PASSWORD = stringPreferencesKey("mistral-password")
```

Add Mistral credential fields to the settings list (after the existing API key field, around line 445). Show them conditionally when Voxtral is selected:

```kotlin
SettingText(R.id.field_mistral_email, MISTRAL_EMAIL),
SettingText(R.id.field_mistral_password, MISTRAL_PASSWORD),
```

In the backend spinner `onItemSelected` callback, add Mistral email/password auto-fill when Voxtral is selected (alongside the existing endpoint/model/language auto-fill).

#### 2. `android/app/src/main/res/layout/activity_main.xml`

Add EditText fields for Mistral email and password after the existing API key field:

```xml
<EditText
    android:id="@+id/field_mistral_email"
    android:hint="Mistral email (for auto key creation)"
    ... />

<EditText
    android:id="@+id/field_mistral_password"
    android:inputType="textPassword"
    android:hint="Mistral password"
    ... />
```

#### 3. `android/app/src/main/res/values/strings.xml`

Add:
```xml
<string name="settings_mistral_email">Mistral Email</string>
<string name="settings_mistral_password">Mistral Password</string>
<string name="error_mistral_credentials_unset">Mistral email and password must be set for auto key creation</string>
```

### Success Criteria:

#### Automated Verification:
- [ ] `cd android && ./gradlew assembleDebug` compiles
- [ ] `./gradlew testDebugUnitTest` passes

#### Manual Verification:
- [ ] Settings shows Mistral email + password fields
- [ ] Fields are visible when Voxtral is selected

---

## Phase 2: Mistral Credential Manager

### Overview
Port the login + key creation from `mistral-rotate-rest.ts` to Kotlin.

### Changes Required:

#### 1. `android/app/src/main/java/com/example/whispertoinput/MistralCredentialManager.kt` (new file)

```kotlin
package com.example.whispertoinput

/**
 * Manages Mistral credentials and API key rotation.
 * Port of ~/git/mistral/mistral-rotate-rest.ts
 */
class MistralCredentialManager(private val context: Context) {

    private val cookieJar = InMemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Get a valid API key. Creates one if none stored.
     */
    suspend fun getOrCreateApiKey(): String? {
        val existing = context.dataStore.data
            .map { it[API_KEY] ?: "" }
            .first()
        if (existing.isNotEmpty()) return existing
        return createApiKey()
    }

    /**
     * Rotate: clear current key and create a new one.
     */
    suspend fun rotateKey(): String? {
        context.dataStore.edit { it[API_KEY] = "" }
        return createApiKey()
    }

    private suspend fun createApiKey(): String? {
        val email = context.dataStore.data.map { it[MISTRAL_EMAIL] ?: "" }.first()
        val password = context.dataStore.data.map { it[MISTRAL_PASSWORD] ?: "" }.first()
        if (email.isEmpty() || password.isEmpty()) return null

        // Step 1: Get login flow
        val flowId = getLoginFlow() ?: return null

        // Step 2: Login
        val loginSuccess = login(flowId, email, password)
        if (!loginSuccess) return null

        // Step 3: Create key
        val key = createKey()
        if (key != null) {
            context.dataStore.edit { it[API_KEY] = key }
        }
        return key
    }

    // --- HTTP methods (ported from mistral-rotate-rest.ts) ---

    private suspend fun getLoginFlow(): String? {
        // GET https://auth.mistral.ai/self-service/login/api
        // Extract flow_id from response
    }

    private suspend fun login(flowId: String, email: String, password: String): Boolean {
        // POST https://auth.mistral.ai/self-service/login?flow={flowId}
        // Body: {"identifier":"{email}","method":"password","password":"{password}"}
        // Session cookies are captured by CookieJar
    }

    private suspend fun createKey(): String? {
        // POST https://console.mistral.ai/api/billing/api-keys
        // Body: {"name":"","workspace_uuid":"7960afd8-0194-4178-8a8f-52f550ec6c44","primitive_access_scope":"shared_only"}
        // Response: {"key": "..."}
    }
}
```

Key implementation notes from `mistral-rotate-rest.ts`:
- Uses `fetch-cookie` for cookie management → map to OkHttp `CookieJar`
- Login body includes `csrf_token` — may need to extract from login flow response first
- `workspace_uuid` is hardcoded as `7960afd8-0194-4178-8a8f-52f550ec6c44`

### Success Criteria:

#### Automated Verification:
- [ ] `cd android && ./gradlew assembleDebug` compiles
- [ ] Unit test with mocked HTTP responses verifies login + key creation flow

#### Manual Verification:
- [ ] With valid Mistral email/password, first transcription auto-creates API key
- [ ] Logcat shows key creation success

---

## Phase 3: WhisperTranscriber — Auto-Create Key + 401 Rotate/Retry

### Overview
Modify `WhisperTranscriber.kt` to auto-create keys for Voxtral and rotate on 401.

### Changes Required:

#### 1. `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

In `makeWhisperRequest()` (around line 64), after reading configs:

```kotlin
// Auto-create API key for Voxtral if empty
if (speechToTextBackend == context.getString(R.string.settings_option_voxtral) && apiKey.isEmpty()) {
    val newKey = MistralCredentialManager(context).getOrCreateApiKey()
    if (newKey != null) {
        // Re-read config with the new key
        apiKey = newKey
    } else {
        throw Exception(context.getString(R.string.error_mistral_credentials_unset))
    }
}
```

After the `response.isSuccessful` check (around line 97), add 401 rotation:

```kotlin
if (response.code == 401 && speechToTextBackend == context.getString(R.string.settings_option_voxtral)) {
    Log.w(TAG, "Got 401, rotating Mistral API key...")
    val newKey = MistralCredentialManager(context).rotateKey()
    if (newKey != null) {
        // Retry with new key
        val retryRequest = buildWhisperRequest(context, filename, mediaType, speechToTextBackend, endpoint, languageCode, newKey, model)
        val retryResponse = client.newCall(retryRequest).execute()
        if (!retryResponse.isSuccessful || retryResponse.code / 100 != 2) {
            throw Exception(retryResponse.body!!.string().replace('\n', ' '))
        }
        rawText = retryResponse.body!!.string().trim()
        // Continue to response parsing...
    }
}
```

Note: The `apiKey` variable in `makeWhisperRequest()` is currently `val` — needs to change to `var` for the auto-create path.

### Success Criteria:

#### Automated Verification:
- [ ] `cd android && ./gradlew assembleDebug` compiles
- [ ] `./gradlew testDebugUnitTest` passes

#### Manual Verification:
- [ ] Voxtral transcription works end-to-end on real device
- [ ] With expired key → 401 → auto-rotate → retry succeeds
- [ ] Second transcription reuses stored key (no re-rotation)

---

## Testing Strategy

### Unit Tests:
- `MistralCredentialManagerTest`: mock HTTP, verify login flow + key creation
- `WhisperTranscriberTest`: verify 401 triggers rotation and retry

### Manual Testing Steps:
1. Install debug APK on real device
2. Settings > select "Voxtral (Mistral)"
3. Enter valid Mistral email + password
4. Clear the API key field (to test auto-creation)
5. Press Apply
6. Use keyboard → speak → verify transcription
7. Check logcat for "credentials rotated and stored"
8. Speak again → verify no re-rotation (key reused)
9. To test 401 rotation: manually set an invalid API key in settings, transcribe, verify auto-rotation

## Performance Considerations

- First transcription with Voxtral: +2-3s for login + key creation
- Subsequent transcriptions: no overhead (key cached)
- 401 rotation: +1-2s for re-login + key creation + retry

## References

- Rotation script: `~/git/mistral/mistral-rotate-rest.ts`
- Validation script: `~/git/mistral/check-and-rotate-key.sh`
- Current Voxtral implementation: `WhisperTranscriber.kt:258-284` (multipart + Bearer auth)
- Settings keys: `MainActivity.kt:59-63`
- Mistral API: `https://docs.mistral.ai/api/`
