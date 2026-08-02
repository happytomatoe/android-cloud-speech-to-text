# Whisper-to-Input Key Manager Standard

This document defines the standard interface for key-manager apps that integrate with whisper-to-input.

## Overview

whisper-to-input discovers key-manager apps using Android's intent-based service discovery. To be discoverable, your key-manager app must:

1. Declare a service with the standard action
2. Implement the standard AIDL interface
3. Export the service for binding

## Standard Action

```
com.whisper-to-input.KEY_MANAGER
```

Declare this action in your service's intent filter:

```xml
<service android:name=".KeyManagerService" android:exported="true">
    <intent-filter>
        <action android:name="com.whisper-to-input.KEY_MANAGER"/>
    </intent-filter>
</service>
```

## AIDL Interface

Copy the AIDL file to your project:

**Path:** `aidl/com/example/keymanager/IMistralKeyManager.aidl`

```aidl
package com.example.keymanager;

interface IMistralKeyManager {
    /**
     * Get a valid API key.
     * 
     * @return Valid API key string
     * @throws Exception if credentials not configured or rotation fails
     */
    String getValidApiKey();
    
    /**
     * Check if credentials are configured.
     * 
     * @return true if credentials are saved
     */
    boolean hasCredentials();
}
```

## Implementation Example

```kotlin
class KeyManagerService : Service() {
    private val binder = KeyManagerBinder()
    
    inner class KeyManagerBinder : IMistralKeyManager.Stub() {
        override fun getValidApiKey(): String {
            // Fetch or rotate API key
            return apiKeyProvider.getValidKey()
        }
        
        override fun hasCredentials(): Boolean {
            return credentialStore.hasCredentials()
        }
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
```

## Manifest Requirements

```xml
<manifest>
    <!-- whisper-to-input will query for this action -->
    <queries>
        <intent>
            <action android:name="com.whisper-to-input.KEY_MANAGER"/>
        </intent>
    </queries>
    
    <application>
        <service android:name=".KeyManagerService" android:exported="true">
            <intent-filter>
                <action android:name="com.whisper-to-input.KEY_MANAGER"/>
            </intent-filter>
        </service>
    </application>
</manifest>
```

## Discovery Flow

1. User opens whisper-to-input Settings
2. whisper-to-input calls `queryIntentServices()` with action `com.whisper-to-input.KEY_MANAGER`
3. All installed apps with this action appear in the Key Manager App dropdown
4. User selects a provider
5. whisper-to-input binds to the selected service and calls `getValidApiKey()` before each transcription

## Testing

Verify your app is discoverable:

```bash
# Check if your service is visible
adb shell dumpsys package resolve --brief -a com.whisper-to-input.KEY_MANAGER

# Or check whisper-to-input logs
adb logcat -s KeyManagerClient
```

## Notes

- The service must be exported (`android:exported="true"`)
- The AIDL interface must match exactly (same package, same method signatures)
- API keys should be rotated automatically by your key-manager app
- whisper-to-input caches keys for 5 minutes (configurable in key-manager)
