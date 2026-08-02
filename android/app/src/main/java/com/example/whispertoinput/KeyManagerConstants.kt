package com.example.whispertoinput

/**
 * Standard constants for Key Manager integration.
 * 
 * Key-manager apps must:
 * 1. Declare a service with action [KEY_MANAGER_ACTION]
 * 2. Implement IMistralKeyManager AIDL interface
 * 3. Export the service (android:exported="true")
 * 
 * See docs/key-manager-standard.md for full specification.
 */
object KeyManagerConstants {
    /**
     * Standard action for key-manager service discovery.
     * 
     * Key-manager apps must declare this action in their service's intent filter:
     * ```xml
     * <service android:name=".KeyManagerService" android:exported="true">
     *     <intent-filter>
     *         <action android:name="com.whisper-to-input.KEY_MANAGER"/>
     *     </intent-filter>
     * </service>
     * ```
     */
    const val KEY_MANAGER_ACTION = "com.whisper-to-input.KEY_MANAGER"
}
