# Secure API Key Storage Implementation Plan

## Overview

Secure the API key storage in the Whisper to Input Android app by migrating from plain-text DataStore to EncryptedSharedPreferences. The API key is currently stored unencrypted, making it vulnerable to extraction on rooted devices or through backup exploits.

## Current State Analysis

**Current Implementation:**
- Settings stored via Jetpack DataStore (`preferencesDataStore`)
- API key stored as plain text: `val API_KEY = stringPreferencesKey("api-key")` (MainActivity.kt:61)
- All settings (sensitive and non-sensitive) use the same DataStore instance

**Files Involved:**
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` - Settings UI and storage
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` - Uses API key for requests
- `android/app/build.gradle.kts` - Dependencies

**Current DataStore Keys:**
- `SPEECH_TO_TEXT_BACKEND` - Not sensitive
- `ENDPOINT` - Not sensitive (public API endpoint)
- `LANGUAGE_CODE` - Not sensitive
- `API_KEY` - **SENSITIVE** - Needs encryption
- `MODEL` - Not sensitive
- `AUTO_RECORDING_START` - Not sensitive
- `AUTO_SWITCH_BACK` - Not sensitive
- `ADD_TRAILING_SPACE` - Not sensitive
- `POSTPROCESSING` - Not sensitive

## Desired End State

- API key encrypted at rest using Android Keystore-backed encryption
- Seamless migration for existing users (no data loss)
- Minimal code changes - only affect API key handling
- Other settings remain in DataStore (no unnecessary changes)

### Verification:
- API key is encrypted in storage (visible as ciphertext in backup)
- App functions normally after migration
- Existing API key is preserved during update

## What We're NOT Doing

- Not migrating ALL settings to EncryptedSharedPreferences (overkill)
- Not adding biometric authentication (out of scope)
- Not implementing key rotation (can be added later)
- Not changing the UI or user experience

## Implementation Approach

Use a **hybrid approach**: Keep DataStore for non-sensitive settings, add EncryptedSharedPreferences exclusively for the API key. This minimizes risk and code changes.

**Why not replace DataStore entirely?**
- DataStore has better coroutine/flow support
- Only one field needs encryption
- Less migration risk

## Phase 0: Create Worktree

### Overview
Create an isolated git worktree for this feature to keep the main branch clean.

### Changes Required:

#### 1. Create worktree directory and branch
```bash
# Create .worktrees directory if it doesn't exist
mkdir -p .worktrees

# Create worktree with new branch
git worktree add .worktrees/secure-api-key-storage -b feature/secure-api-key-storage

# Move into worktree
cd .worktrees/secure-api-key-storage
```

#### 2. Verify worktree
```bash
# Confirm you're in the worktree
pwd
# Should show: /path/to/whisper-to-input/.worktrees/secure-api-key-storage

# Check branch
git branch --show-current
# Should show: feature/secure-api-key-storage
```

### Success Criteria:

#### Automated Verification:
- [x] Worktree created: `ls -la .worktrees/secure-api-key-storage`
- [x] Correct branch: `git branch --show-current` shows `feature/secure-api-key-storage`
- [x] Clean working tree: `git status` shows no changes

#### Manual Verification:
- [x] Can navigate to worktree: `cd .worktrees/secure-api-key-storage`
- [x] All files are present
- [ ] Can build the app from worktree

**Implementation Note**: All subsequent phases should be executed from within the worktree directory.

---

## Phase 1: Add Dependencies & Create SecureStorage Helper

### Overview
Add the AndroidX Security Crypto dependency and create a helper class to manage encrypted storage.

### Changes Required:

#### 1. Add Dependency
**File**: `android/app/build.gradle.kts`

```kotlin
dependencies {
    // ... existing dependencies
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

#### 2. Create SecureStorage Helper
**File**: `android/app/src/main/java/com/example/whispertoinput/SecureStorage.kt` (NEW)

```kotlin
package com.example.whispertoinput

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for sensitive data using Android Keystore-backed encryption.
 * Currently used for API key storage.
 */
class SecureStorage(context: Context) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .setRequestStrongBoxBacked(true)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_storage",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Store the API key securely.
     */
    fun saveApiKey(apiKey: String) {
        encryptedPrefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    /**
     * Retrieve the API key. Returns empty string if not set.
     */
    fun getApiKey(): String {
        return encryptedPrefs.getString(KEY_API_KEY, "") ?: ""
    }

    /**
     * Remove the API key.
     */
    fun clearApiKey() {
        encryptedPrefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val KEY_API_KEY = "api_key"
    }
}
```

### Success Criteria:

#### Automated Verification:
- [ ] Project compiles: `./gradlew assembleDebug`
- [ ] No linting errors: `./gradlew lint`

#### Manual Verification:
- [ ] App installs and runs on device/emulator
- [ ] No crashes on startup

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Migrate API Key Storage

### Overview
Update MainActivity to store API key in EncryptedSharedPreferences, with automatic migration from DataStore.

### Changes Required:

#### 1. Update MainActivity
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

**Changes:**
- Import SecureStorage
- Initialize SecureStorage in onCreate
- Update SettingText for API_KEY to use SecureStorage
- Add migration logic from DataStore to EncryptedSharedPreferences

```kotlin
// Add import
import android.content.SharedPreferences

// Add member variable
private lateinit var secureStorage: SecureStorage

// In onCreate, after setContentView:
secureStorage = SecureStorage(this)

// Add migration function (call after secureStorage init):
private suspend fun migrateApiKeyIfNeeded() {
    // Check if API key exists in old DataStore
    val oldApiKey = dataStore.data.map { preferences ->
        preferences[API_KEY] ?: ""
    }.first()
    
    // If exists in DataStore but not in SecureStorage, migrate
    if (oldApiKey.isNotEmpty() && secureStorage.getApiKey().isEmpty()) {
        secureStorage.saveApiKey(oldApiKey)
        // Clear from DataStore after migration
        dataStore.edit { settings ->
            settings.remove(API_KEY)
        }
    }
}

// Update the SettingText for API_KEY to use SecureStorage:
// Change from:
SettingText(R.id.field_api_key, API_KEY),
// To:
SecureSettingText(R.id.field_api_key),

// Add new inner class for secure API key setting:
inner class SecureSettingText(
    private val viewId: Int
): SettingItem() {
    override fun setup(): Job {
        return CoroutineScope(Dispatchers.Main).launch {
            val btnApply: Button = findViewById(R.id.btn_settings_apply)
            val editText = findViewById<EditText>(viewId)
            editText.isEnabled = false
            editText.doOnTextChanged { _, _, _, _ ->
                if (!setupSettingItemsDone) return@doOnTextChanged
                isDirty = true
                btnApply.isEnabled = true
            }

            // Read from secure storage
            val apiKey = secureStorage.getApiKey()
            editText.setText(apiKey)
            editText.isEnabled = true
        }
    }
    
    override suspend fun apply() {
        if (!isDirty) return
        val newValue: String = findViewById<EditText>(viewId).text.toString()
        secureStorage.saveApiKey(newValue)
        isDirty = false
    }
}
```

### Success Criteria:

#### Automated Verification:
- [ ] Project compiles: `./gradlew assembleDebug`
- [ ] No linting errors: `./gradlew lint`

#### Manual Verification:
- [ ] Existing API key is migrated automatically
- [ ] New API key can be entered and saved
- [ ] API key persists after app restart
- [ ] API key is NOT visible in plain text in app data

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Update WhisperTranscriber

### Overview
Update WhisperTranscriber to read API key from SecureStorage instead of DataStore.

### Changes Required:

#### 1. Update WhisperTranscriber
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

**Changes:**
- Import SecureStorage
- Initialize SecureStorage in startAsync
- Read API key from SecureStorage

```kotlin
// In startAsync function, after getting context:
val secureStorage = SecureStorage(context)

// Update the Config retrieval to use SecureStorage for apiKey:
val (endpoint, languageCode, speechToTextBackend, model, postprocessing, addTrailingSpace) = context.dataStore.data.map { preferences: Preferences ->
    Config(
        preferences[ENDPOINT] ?: "",
        preferences[LANGUAGE_CODE] ?: "",
        preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
        // apiKey removed from here
        preferences[MODEL] ?: "",
        preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
        preferences[ADD_TRAILING_SPACE] ?: false
    )
}.first()

// Get apiKey separately from SecureStorage
val apiKey = secureStorage.getApiKey()
```

Or alternatively, update the Config data class to not include apiKey and pass it separately.

### Success Criteria:

#### Automated Verification:
- [ ] Project compiles: `./gradlew assembleDebug`
- [ ] No linting errors: `./gradlew lint`

#### Manual Verification:
- [ ] Transcription works with encrypted API key
- [ ] Error message shows if API key is empty
- [ ] All backends work (OpenAI, ElevenLabs, etc.)

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Cleanup & Testing

### Overview
Remove unused API_KEY preference key and ensure full functionality.

### Changes Required:

#### 1. Clean Up Unused Key
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

**Changes:**
- Remove or comment out the `API_KEY` preference key definition (line 61)
- It's no longer used since we're using SecureStorage

```kotlin
// Remove or comment out:
// val API_KEY = stringPreferencesKey("api-key")
```

### Success Criteria:

#### Automated Verification:
- [ ] Project compiles: `./gradlew assembleDebug`
- [ ] All unit tests pass (if any): `./gradlew test`
- [ ] No linting errors: `./gradlew lint`

#### Manual Verification:
- [ ] Fresh install works (no API key)
- [ ] Can enter and save new API key
- [ ] API key survives app update (migration works)
- [ ] All speech-to-text backends function correctly
- [ ] App data backup does NOT contain plain text API key

---

## Testing Strategy

### Unit Tests:
- Test SecureStorage.saveApiKey() and getApiKey()
- Test migration logic (old DataStore -> SecureStorage)

### Integration Tests:
- End-to-end transcription with encrypted API key

### Manual Testing Steps:
1. Install app on fresh device/emulator
2. Enter API key in settings
3. Verify transcription works
4. Check app data directory - API key should be encrypted
5. Update app (simulating user update) - verify API key persists
6. Test all backends: OpenAI, ElevenLabs, Whisper ASR, NVIDIA NIM, Voxtral

## Performance Considerations

- EncryptedSharedPreferences adds ~2-5ms overhead per read/write
- This is negligible for a settings operation that happens once per transcription
- No performance impact on transcription itself

## Migration Notes

- Automatic migration from DataStore to SecureStorage on first run
- Old API key is removed from DataStore after migration
- Users experience no disruption

## References

- AndroidX Security Crypto: https://developer.android.com/jetpack/androidx/releases/security-crypto
- EncryptedSharedPreferences: https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
- Current DataStore implementation: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt:57-67`

---

## Verification: Before & After Comparison

### Verification Script

Create `scripts/test-api-key-encryption.sh`:

```bash
#!/bin/bash
# Test script to verify API key encryption
# Run this BEFORE and AFTER implementing the plan

ADB=/var/home/l/Android/Sdk/platform-tools/adb
PKG="com.example.whispertoinput"

echo "========================================="
echo "API Key Storage Verification"
echo "========================================="
echo ""

# Check if device is connected
DEVICE=$($ADB devices | grep -E "device$" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "ERROR: No device/emulator connected"
    echo "Start emulator and try again"
    exit 1
fi

echo "Device: $DEVICE"
echo "Package: $PKG"
echo ""

# Check if app is installed
if ! $ADB shell pm list packages | grep -q "$PKG"; then
    echo "ERROR: App not installed"
    echo "Install the app first and set an API key"
    exit 1
fi

echo "--- DataStore File (Plain Text) ---"
echo "Location: /data/data/$PKG/files/datastore/settings.preferences_pb"
echo ""

# Try to read DataStore file
DATASTORE_CONTENT=$($ADB shell run-as $PKG cat files/datastore/settings.preferences_pb 2>/dev/null)
if [ $? -eq 0 ]; then
    echo "File exists! Checking for API key..."
    echo ""
    
    # Search for common API key patterns
    echo "Searching for API key patterns (sk-, xi-, etc.):"
    echo "$DATASTORE_CONTENT" | strings | grep -E "^(sk-|xi-|Bearer )" || echo "  (none found in strings)"
    echo ""
    
    # Show hex dump (first 500 chars)
    echo "Hex dump (first 500 chars):"
    echo "$DATASTORE_CONTENT" | xxd | head -20
    echo ""
    
    # Show raw bytes
    echo "Raw strings found:"
    echo "$DATASTORE_CONTENT" | strings | head -10
else
    echo "DataStore file not found or not accessible"
fi

echo ""
echo "--- SecureStorage File (Encrypted) ---"
echo "Location: /data/data/$PKG/files/secure_storage"
echo ""

# Try to read SecureStorage file
SECURE_CONTENT=$($ADB shell run-as $PKG cat files/secure_storage 2>/dev/null)
if [ $? -eq 0 ]; then
    echo "File exists! Checking for API key..."
    echo ""
    
    # Search for common API key patterns
    echo "Searching for API key patterns (sk-, xi-, etc.):"
    echo "$SECURE_CONTENT" | strings | grep -E "^(sk-|xi-|Bearer )" || echo "  (none found - GOOD! Key is encrypted)"
    echo ""
    
    # Show hex dump (first 500 chars)
    echo "Hex dump (first 500 chars):"
    echo "$SECURE_CONTENT" | xxd | head -20
    echo ""
    
    # Show raw strings
    echo "Raw strings found:"
    echo "$SECURE_CONTENT" | strings | head -10
else
    echo "SecureStorage file not found (expected before implementation)"
fi

echo ""
echo "========================================="
echo "INTERPRETATION:"
echo "========================================="
echo ""
echo "BEFORE implementation:"
echo "  - DataStore file contains API key in plain text"
echo "  - SecureStorage file does not exist"
echo ""
echo "AFTER implementation:"
echo "  - DataStore file does NOT contain API key"
echo "  - SecureStorage file exists but API key is encrypted"
echo "  - grep for 'sk-' in SecureStorage returns nothing"
echo ""
```

### How to Run

```bash
# 1. Make script executable
chmod +x scripts/test-api-key-encryption.sh

# 2. BEFORE implementing (set API key in app first!)
./scripts/test-api-key-encryption.sh

# 3. Implement the plan

# 4. AFTER implementing (reinstall app, set same API key)
./scripts/test-api-key-encryption.sh
```

### Expected Results

**BEFORE (Plain Text):**
```
--- DataStore File (Plain Text) ---
File exists! Checking for API key...

Searching for API key patterns (sk-, xi-, etc.):
  sk-test1234567890abcdef  <-- VISIBLE!

Raw strings found:
sk-test1234567890abcdef
openai
https://api.openai.com/v1/audio/transcriptions
```

**AFTER (Encrypted):**
```
--- SecureStorage File (Encrypted) ---
File exists! Checking for API key...

Searching for API key patterns (sk-, xi-, etc.):
  (none found - GOOD! Key is encrypted)  <-- ENCRYPTED!

Raw strings found:
(nothing readable)
```

### Manual Verification Steps

1. **Set API key in app**: Open app → Settings → Enter API key → Apply
2. **Run verification script**: `./scripts/test-api-key-encryption.sh`
3. **Check DataStore**: Should show API key in plain text (before) or not contain it (after)
4. **Check SecureStorage**: Should not exist (before) or show encrypted data (after)
5. **Verify app works**: Test transcription to ensure API key is read correctly

---

## Current State (Before Implementation)

**Note**: Run the verification script before implementing to capture the actual output.

```
[To be filled in after running ./scripts/test-api-key-encryption.sh]
```
