# Key Manager Service Implementation Plan

## Overview

Create a separate "key-manager" Android app that handles Mistral API key validation and rotation. whisper-to-input (IME) will bind to this app's service before each transcription to get a valid API key.

## Current State Analysis

**whisper-to-input:**
- Stores API key in DataStore (`API_KEY` preference)
- `WhisperTranscriber.kt:85` reads key from DataStore and uses it for API calls
- No automatic key rotation — user must manually update when key expires

**Patch file (`patches/mistral-changes.patch`):**
- Contains `MistralAuth.kt` — handles Mistral login and API key creation
- Contains `HttpException.kt` — custom exception for HTTP status codes
- Contains UI changes for Mistral credentials in MainActivity
- Contains auto-rotate logic in WhisperInputService (401 handling)

## Desired End State

1. **key-manager app** — new Android app that:
   - Stores Mistral credentials securely (EncryptedSharedPreferences)
   - Validates API keys
   - Rotates keys when invalid/expired
   - Exposes Bound Service via AIDL for other apps

2. **whisper-to-input** — modified to:
   - Bind to key-manager service before each transcription
   - Get valid key from service
   - Show error if key-manager not installed
   - Remove hardcoded key storage

### Key Discoveries:
- `WhisperTranscriber.kt:85-100` — where API key is retrieved and used
- `WhisperTranscriber.kt:57-62` — `startAsync()` method signature (needs modification)
- `MistralAuth.kt` (in patch) — complete auth logic to reuse
- `WhisperInputService.kt` — orchestrates transcription flow

## What We're NOT Doing

- Not modifying the Mistral auth logic (reuse from patch)
- Not changing the transcription API call structure
- Not implementing server-side key management
- Not supporting multiple apps (just whisper-to-input)

## Implementation Approach

Use Android Bound Service with AIDL for on-demand key retrieval. This is the standard Android pattern for "app A asks app B for something" and allows whisper-to-input to get a fresh key before each transcription.

---

## Phase 1: Create key-manager app

### Overview
Create a new Android app with AIDL service that handles Mistral key management.

### Changes Required:

#### 1. New Android Project
**Location**: `/var/home/l/git/whisper-to-input/key-manager/`

Create a new Android app project with:
- Package: `com.example.keymanager`
- Min SDK: 26 (same as whisper-to-input)
- Dependencies: OkHttp, EncryptedSharedPreferences

#### 2. AIDL Interface
**File**: `app/src/main/aidl/com/example/keymanager/IMistralKeyManager.aidl`

```aidl
package com.example.keymanager;

interface IMistralKeyManager {
    /**
     * Get a valid Mistral API key.
     * Returns the key string, or throws exception if unavailable.
     */
    String getValidApiKey();
    
    /**
     * Check if credentials are configured.
     */
    boolean hasCredentials();
}
```

#### 3. MistralAuth.kt
**File**: `app/src/main/java/com/example/keymanager/MistralAuth.kt`

Copy from patch file — handles Mistral login and API key creation.

#### 4. KeyManagerService.kt
**File**: `app/src/main/java/com/example/keymanager/KeyManagerService.kt`

```kotlin
class KeyManagerService : Service() {
    private val binder = object : IMistralKeyManager.Stub() {
        override fun getValidApiKey(): String {
            // 1. Read stored credentials
            // 2. Try stored API key
            // 3. If invalid/missing, rotate using MistralAuth
            // 4. Return valid key
        }
        
        override fun hasCredentials(): Boolean {
            // Check if email/password are stored
        }
    }
    
    override fun onBind(intent: Intent): IBinder = binder
}
```

#### 5. Credential Storage
**File**: `app/src/main/java/com/example/keymanager/CredentialManager.kt`

```kotlin
class CredentialManager(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(...)
    
    fun getEmail(): String
    fun setEmail(email: String)
    fun getPassword(): String
    fun setPassword(password: String)
    fun getApiKey(): String?
    fun setApiKey(key: String)
}
```

#### 6. MainActivity.kt
**File**: `app/src/main/java/com/example/keymanager/MainActivity.kt`

Simple UI with:
- Email input field
- Password input field
- Save button
- Status display (key validity)

#### 7. AndroidManifest.xml
**File**: `app/src/main/AndroidManifest.xml`

```xml
<service
    android:name=".KeyManagerService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.example.keymanager.MISTRAL_KEY_SERVICE" />
    </intent-filter>
</service>
```

### Success Criteria:

#### Automated Verification:
- [ ] App compiles: `./gradlew assembleDebug`
- [ ] AIDL generates correctly
- [ ] Service binds successfully (unit test)

#### Manual Verification:
- [ ] App installs on device/emulator
- [ ] Can save Mistral credentials
- [ ] Service returns valid API key when bound

---

## Phase 2: Integrate key-manager into whisper-to-input

### Overview
Modify whisper-to-input to bind to key-manager service and retrieve keys on-demand.

### Changes Required:

#### 1. Copy AIDL Interface
**File**: `android/app/src/main/aidl/com/example/keymanager/IMistralKeyManager.aidl`

Copy the AIDL file from key-manager app.

#### 2. KeyManagerClient.kt
**File**: `android/app/src/main/java/com/example/whispertoinput/KeyManagerClient.kt`

```kotlin
class KeyManagerClient(private val context: Context) {
    private var service: IMistralKeyManager? = null
    private var bound = false
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            this@KeyManagerClient.service = IMistralKeyManager.Stub.asInterface(service)
            bound = true
        }
        
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }
    
    fun bind(): Boolean {
        val intent = Intent("com.example.keymanager.MISTRAL_KEY_SERVICE")
        intent.setPackage("com.example.keymanager")
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    fun getValidApiKey(): String? {
        if (!bound) return null
        return try {
            service?.validApiKey
        } catch (e: Exception) {
            null
        }
    }
    
    fun hasCredentials(): Boolean {
        if (!bound) return false
        return try {
            service?.hasCredentials() ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    fun unbind() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }
}
```

#### 3. Modify WhisperTranscriber.kt
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

Change `startAsync()` to accept API key as parameter instead of reading from DataStore:

```kotlin
fun startAsync(
    context: Context,
    filename: String,
    mediaType: String,
    attachToEnd: String,
    apiKey: String,  // NEW: pass key explicitly
    callback: (String?) -> Unit,
    exceptionCallback: (String) -> Unit
) {
    // Remove DataStore read for API_KEY
    // Use provided apiKey parameter
}
```

#### 4. Modify WhisperInputService.kt
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`

Add key-manager binding and key retrieval before transcription:

```kotlin
class WhisperInputService : InputMethodService() {
    private var keyManagerClient: KeyManagerClient? = null
    
    override fun onCreate() {
        super.onCreate()
        keyManagerClient = KeyManagerClient(this)
        keyManagerClient?.bind()
    }
    
    private fun startTranscribing(attachToEnd: String) {
        CoroutineScope(Dispatchers.Main).launch {
            // Get valid key from key-manager
            val apiKey = withContext(Dispatchers.IO) {
                keyManagerClient?.getValidApiKey()
            }
            
            if (apiKey == null) {
                // Key-manager not available or no credentials
                Toast.makeText(
                    this@WhisperInputService,
                    "Key Manager app not found or not configured",
                    Toast.LENGTH_LONG
                ).show()
                whisperKeyboard.reset()
                return@launch
            }
            
            // Pass key to transcriber
            whisperTranscriber.startAsync(
                this@WhisperInputService,
                recordedAudioFilename,
                audioMediaType,
                attachToEnd,
                apiKey,  // Pass the key
                { text -> transcriptionCallback(text) },
                { msg -> transcriptionExceptionCallback(msg) }
            )
        }
    }
    
    override fun onDestroy() {
        keyManagerClient?.unbind()
        super.onDestroy()
    }
}
```

#### 5. Remove Key Storage from whisper-to-input
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

Remove `API_KEY` from settings UI — keys now come from key-manager.

### Success Criteria:

#### Automated Verification:
- [ ] whisper-to-input compiles: `./gradlew assembleDebug`
- [ ] AIDL generates correctly
- [ ] No DataStore read for API_KEY in WhisperTranscriber

#### Manual Verification:
- [ ] Both apps installed on device
- [ ] whisper-to-input binds to key-manager on startup
- [ ] Transcription works with valid key from key-manager
- [ ] Error shown if key-manager not installed
- [ ] Error shown if key-manager has no credentials

---

## Phase 3: Error handling and edge cases

### Overview
Handle various failure scenarios gracefully.

### Changes Required:

#### 1. Key-manager service unavailable
**File**: `android/app/src/main/java/com/example/whispertoinput/KeyManagerClient.kt`

- Return null if service not bound
- Timeout after 5 seconds if binding fails

#### 2. Key rotation failure
**File**: `key-manager/app/src/main/java/com/example/keymanager/KeyManagerService.kt`

- Catch exceptions from MistralAuth
- Return meaningful error messages
- Log failures for debugging

#### 3. Invalid credentials
**File**: `key-manager/app/src/main/java/com/example/keymanager/KeyManagerService.kt`

- Validate credentials before attempting rotation
- Show clear error in key-manager UI

### Success Criteria:

#### Manual Verification:
- [ ] Graceful error when key-manager not installed
- [ ] Graceful error when credentials invalid
- [ ] Graceful error when network unavailable
- [ ] Key rotation works on 401 response

---

## Testing Strategy

### Unit Tests:
- `KeyManagerClient` — binding, key retrieval, error handling
- `CredentialManager` — encrypted storage read/write
- `KeyManagerService` — key validation, rotation logic

### Integration Tests:
- Bind to service from whisper-to-input
- Full transcription flow with key-manager

### Manual Testing Steps:
1. Install key-manager app
2. Enter Mistral credentials
3. Install whisper-to-input
4. Open any text field
5. Speak — verify transcription works
6. Invalidate API key in Mistral console
7. Speak again — verify key rotates automatically

---

## Performance Considerations

- **Service binding**: Bind once in `onCreate()`, reuse for all transcriptions
- **Key caching**: Cache valid key for ~5 minutes to reduce IPC calls
- **Background rotation**: Rotate key proactively if near expiry (optional)

---

## Migration Notes

- Remove `API_KEY` from whisper-to-input settings
- Users must install key-manager app and configure credentials
- Existing API keys in DataStore will be ignored

---

## References

- Patch file: `patches/mistral-changes.patch`
- Android Bound Services: https://developer.android.com/guide/components/bound-services
- AIDL documentation: https://developer.android.com/guide/components/aidl
- EncryptedSharedPreferences: https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
