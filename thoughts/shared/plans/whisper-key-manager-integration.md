# Whisper-to-Input Integration with Key Manager

## Overview

Modify whisper-to-input to bind to key-manager service and retrieve API keys on-demand before each transcription.

## Current State

**whisper-to-input:**
- Stores API key in DataStore (`API_KEY` preference)
- `WhisperTranscriber.kt:85-100` reads key from DataStore
- No automatic key rotation

**key-manager app:**
- Exposes `IMistralKeyManager` AIDL service
- Service provides `getValidApiKey()` and `hasCredentials()`

## Desired End State

1. whisper-to-input binds to key-manager service
2. Retrieves valid API key before each transcription
3. Shows error if key-manager not installed or not configured
4. Removes hardcoded key storage from settings

---

## Phase 1: Copy AIDL Interface

### Overview
Add the AIDL interface to whisper-to-input so it can communicate with key-manager.

### Changes Required:

#### 1. Create AIDL Directory
```bash
mkdir -p /var/home/l/git/whisper-to-input/android/app/src/main/aidl/com/example/keymanager
```

#### 2. Copy AIDL File
**File**: `android/app/src/main/aidl/com/example/keymanager/IMistralKeyManager.aidl`

Copy from key-manager app:
```aidl
package com.example.keymanager;

interface IMistralKeyManager {
    String getValidApiKey();
    boolean hasCredentials();
}
```

### Success Criteria:

#### Automated Verification:
- [x] AIDL file exists in correct location
- [x] whisper-to-input compiles: `./gradlew assembleDebug`

---

## Phase 2: Create KeyManagerClient

### Overview
Wrapper class that handles service binding and communication.

### Changes Required:

#### 1. KeyManagerClient.kt
**File**: `android/app/src/main/java/com/example/whispertoinput/KeyManagerClient.kt`

```kotlin
package com.example.whispertoinput

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.keymanager.IMistralKeyManager

/**
 * Client for communicating with Key Manager app's service.
 * Handles binding, key retrieval, and error handling.
 */
class KeyManagerClient(private val context: Context) {
    private val TAG = "KeyManagerClient"
    private var service: IMistralKeyManager? = null
    private var bound = false
    private var bindAttempted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            this@KeyManagerClient.service = IMistralKeyManager.Stub.asInterface(service)
            bound = true
            Log.d(TAG, "Connected to Key Manager service")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            Log.d(TAG, "Disconnected from Key Manager service")
        }
    }

    /**
     * Bind to key-manager service.
     * @return true if binding initiated, false if key-manager not installed
     */
    fun bind(): Boolean {
        if (bound) return true
        if (bindAttempted) return false

        val intent = Intent("com.example.keymanager.MISTRAL_KEY_SERVICE")
        intent.setPackage("com.example.keymanager")

        return try {
            val result = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bindAttempted = true
            if (!result) {
                Log.w(TAG, "Key Manager app not installed or not accessible")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to Key Manager: ${e.message}")
            false
        }
    }

    /**
     * Get a valid API key from key-manager.
     * @return Valid API key, or null if unavailable
     */
    fun getValidApiKey(): String? {
        if (!bound) {
            Log.w(TAG, "Not bound to Key Manager service")
            return null
        }

        return try {
            service?.validApiKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key: ${e.message}")
            null
        }
    }

    /**
     * Check if key-manager has credentials configured.
     * @return true if credentials exist
     */
    fun hasCredentials(): Boolean {
        if (!bound) return false

        return try {
            service?.hasCredentials() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check credentials: ${e.message}")
            false
        }
    }

    /**
     * Unbind from key-manager service.
     */
    fun unbind() {
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind: ${e.message}")
            }
            bound = false
            service = null
        }
    }

    /**
     * Check if service is currently bound.
     */
    fun isBound(): Boolean = bound
}
```

### Success Criteria:

#### Automated Verification:
- [x] KeyManagerClient.kt compiles without errors

---

## Phase 3: Modify WhisperTranscriber

### Overview
Update transcriber to accept API key as parameter instead of reading from DataStore.

### Changes Required:

#### 1. Modify startAsync() Signature
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

**Change line 57-62:**

```kotlin
// BEFORE:
fun startAsync(
    context: Context,
    filename: String,
    mediaType: String,
    attachToEnd: String,
    callback: (String?) -> Unit,
    exceptionCallback: (String) -> Unit
)

// AFTER:
fun startAsync(
    context: Context,
    filename: String,
    mediaType: String,
    attachToEnd: String,
    apiKey: String,  // NEW PARAMETER
    callback: (String?) -> Unit,
    exceptionCallback: (String) -> Unit
)
```

#### 2. Remove DataStore Read for API_KEY
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

**Change line 85-100:**

```kotlin
// BEFORE:
val (endpoint, languageCode, speechToTextBackend, apiKey, model, postprocessing, addTrailingSpace) = context.dataStore.data.map { preferences: Preferences ->
    Config(
        preferences[ENDPOINT] ?: "",
        preferences[LANGUAGE_CODE] ?: "",
        preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
        preferences[API_KEY] ?: "",  // REMOVE THIS
        preferences[MODEL] ?: "",
        preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
        preferences[ADD_TRAILING_SPACE] ?: false
    )
}.first()

// AFTER:
val (endpoint, languageCode, speechToTextBackend, model, postprocessing, addTrailingSpace) = context.dataStore.data.map { preferences: Preferences ->
    Config(
        preferences[ENDPOINT] ?: "",
        preferences[LANGUAGE_CODE] ?: "",
        preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
        preferences[MODEL] ?: "",
        preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
        preferences[ADD_TRAILING_SPACE] ?: false
    )
}.first()
```

#### 3. Update Config Data Class
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

**Change line 29-37:**

```kotlin
// BEFORE:
private data class Config(
    val endpoint: String,
    val languageCode: String,
    val speechToTextBackend: String,
    val apiKey: String,
    val model: String,
    val postprocessing: String,
    val addTrailingSpace: Boolean
)

// AFTER:
private data class Config(
    val endpoint: String,
    val languageCode: String,
    val speechToTextBackend: String,
    val model: String,
    val postprocessing: String,
    val addTrailingSpace: Boolean
)
```

### Success Criteria:

#### Automated Verification:
- [x] WhisperTranscriber.kt compiles without errors
- [x] No reference to `API_KEY` preference in this file

---

## Phase 4: Modify WhisperInputService

### Overview
Add key-manager binding and key retrieval before transcription.

### Changes Required:

#### 1. Add KeyManagerClient Field
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`

**Add after line 50 (after recorderManager declaration):**

```kotlin
// Key Manager
private var keyManagerClient: KeyManagerClient? = null
```

#### 2. Bind to Key Manager in onCreate
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`

**Add at end of onCreate() method (after registerReceiver):**

```kotlin
// Bind to Key Manager
keyManagerClient = KeyManagerClient(this)
keyManagerClient?.bind()
```

#### 3. Modify startTranscribing() to Use Key Manager
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`

**Replace the startTranscribing() method:**

```kotlin
private fun startTranscribing(attachToEnd: String) {
    CoroutineScope(Dispatchers.Main).launch {
        // Get valid API key from key-manager
        val apiKey = withContext(Dispatchers.IO) {
            keyManagerClient?.getValidApiKey()
        }

        if (apiKey == null) {
            // Key-manager not available or not configured
            val message = if (keyManagerClient?.bind() == false) {
                "Key Manager app not installed"
            } else {
                "Key Manager not configured. Open Key Manager app to set credentials."
            }
            
            lastTranscriptionError = message
            Toast.makeText(this@WhisperInputService, message, Toast.LENGTH_LONG).show()
            whisperKeyboard.reset()
            return@launch
        }

        val useTestFile = if (BuildConfig.DEBUG) {
            dataStore.data.map { it[USE_TEST_FILE] ?: false }.first()
        } else false

        if (useTestFile) {
            // Test file mode
            testFileModeRecording = false
            val testFilePath = dataStore.data
                .map { it[TEST_FILE_PATH] ?: "/sdcard/test-speech-loud.wav" }
                .first()

            whisperTranscriber.startAsync(
                this@WhisperInputService,
                testFilePath,
                AUDIO_MEDIA_TYPE_WAV,
                attachToEnd,
                apiKey,  // Pass key from key-manager
                { text ->
                    android.util.Log.d("whisper-input", "Transcription result: '$text'")
                    transcriptionCallback(text)
                },
                { msg ->
                    android.util.Log.e("whisper-input", "Transcription error: $msg")
                    transcriptionExceptionCallback(msg)
                }
            )
        } else if (recorderManager.isRecording) {
            // Normal mode
            recorderManager.stop()

            whisperTranscriber.startAsync(
                this@WhisperInputService,
                recordedAudioFilename,
                audioMediaType,
                attachToEnd,
                apiKey,  // Pass key from key-manager
                { text ->
                    android.util.Log.d("whisper-input", "Transcription result: '$text'")
                    transcriptionCallback(text)
                },
                { msg ->
                    android.util.Log.e("whisper-input", "Transcription error: $msg")
                    transcriptionExceptionCallback(msg)
                }
            )
        }
    }
}
```

#### 4. Unbind in onDestroy
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`

**Add at beginning of onDestroy() method:**

```kotlin
override fun onDestroy() {
    keyManagerClient?.unbind()
    whisperTranscriber.stop()
    recorderManager.stop()
    unregisterReceiver(toggleReceiver)
    super.onDestroy()
}
```

### Success Criteria:

#### Automated Verification:
- [x] WhisperInputService.kt compiles without errors
- [x] No reference to `API_KEY` preference in this file

#### Manual Verification:
- [ ] IME binds to key-manager on startup
- [ ] Transcription works with key from key-manager
- [ ] Error shown if key-manager not installed
- [ ] Error shown if key-manager has no credentials

---

## Phase 5: Remove Key Storage from Settings

### Overview
Remove API key field from whisper-to-input settings since keys now come from key-manager.

### Changes Required:

#### 1. Remove API_KEY from Settings
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

**Remove these lines:**
- Line 63: `val API_KEY = stringPreferencesKey("api-key")`
- Line 307: `SettingText(R.id.field_api_key, API_KEY),`

#### 2. Hide API Key Field in Layout
**File**: `android/app/src/main/res/layout/activity_main.xml`

**Add visibility="gone" to API key field:**
```xml
<TextView
    android:id="@+id/label_api_key"
    android:visibility="gone"
    ... />

<EditText
    android:id="@+id/field_api_key"
    android:visibility="gone"
    ... />
```

### Success Criteria:

#### Automated Verification:
- [x] No reference to API_KEY in MainActivity.kt
- [x] whisper-to-input compiles without errors

#### Manual Verification:
- [ ] API key field not visible in settings
- [ ] Settings page loads without errors

---

## Integration Instructions

### Prerequisites

1. **Install key-manager app first**
   ```bash
   cd /var/home/l/git/whisper-to-input/key-manager
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Configure credentials in key-manager**
   - Open Key Manager app
   - Enter Mistral email and password
   - Tap "Save Credentials"
   - Tap "Test: Get API Key" to verify

3. **Build and install whisper-to-input**
   ```bash
   cd /var/home/l/git/whisper-to-input/android
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Testing the Integration

1. Open any text field on device
2. whisper-to-input keyboard should appear
3. Tap microphone to start recording
4. Speak and tap again to transcribe
5. Text should appear in the text field

### Troubleshooting

**Error: "Key Manager app not installed"**
- Verify key-manager is installed: `adb shell pm list packages | grep keymanager`
- Reinstall if needed

**Error: "Key Manager not configured"**
- Open Key Manager app
- Enter Mistral credentials
- Save and test

**Error: "Failed to rotate API key"**
- Check Mistral credentials are correct
- Check network connection
- Check key-manager logs: `adb logcat -s KeyManagerService`

### Verify Service Connection
```bash
# Check if key-manager service is running
adb shell dumpsys activity services com.example.keymanager

# Check whisper-to-input logs
adb logcat -s KeyManagerClient
```

---

## Performance Considerations

- **Service binding**: Bind once in `onCreate()`, reuse for all transcriptions
- **Key caching**: key-manager caches valid key for 5 minutes
- **Background rotation**: key-manager rotates key proactively if near expiry

---

## Migration Notes

- Users must install key-manager app before using whisper-to-input
- Existing API keys in whisper-to-input settings will be ignored
- Users need to enter Mistral credentials in key-manager app

---

## References

- key-manager app plan: `thoughts/shared/plans/key-manager-app.md`
- Patch file: `patches/mistral-changes.patch`
- Android Bound Services: https://developer.android.com/guide/components/bound-services
