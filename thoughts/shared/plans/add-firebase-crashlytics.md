# Firebase Crashlytics Implementation Plan

## Overview

Add Firebase Crashlytics to Whisper To Input with a conditional build setup that supports:
- Local development on de-googled Pixel (GrapheneOS) without Google Play Services
- Firebase testing on Android emulator with GMS
- Full crash reporting in production builds
- CI/CD builds with Firebase enabled via GitHub Secrets
- Local development on de-googled Pixel (GrapheneOS) without Google Play Services
- Firebase testing on Android emulator with GMS
- Full crash reporting in production builds

## Current State Analysis

**Project Structure:**
- Native Android Kotlin app (keyboard for speech-to-text)
- Package: `com.example.whispertoinput`
- Build: Gradle Kotlin DSL, compileSdk 34, minSdk 24
- No existing Firebase integration

**Key Files to Modify:**
- `android/build.gradle.kts` (project-level)
- `android/app/build.gradle.kts` (app-level)
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

**User Constraints:**
- Primary device: de-googled Pixel (GrapheneOS) — no Google Play Services
- Testing strategy: conditional build + emulator for Firebase testing

## Desired End State

1. **On de-googled Pixel:** App builds and runs normally, Firebase disabled
2. **On emulator/production:** Full Crashlytics with crashes, ANRs, custom keys, logs
3. **Firebase Console:** Crash reports visible with device info, stack traces, breadcrumbs

### Verification Criteria:
- [ ] App builds without `google-services.json` (no build errors)
- [ ] App runs on Pixel without crashes or Firebase-related errors
- [ ] With `google-services.json`, Crashlytics initializes and collects crashes
- [ ] Test crash appears in Firebase Console within 5 minutes
- [ ] Custom keys and non-fatal errors appear in dashboard

## What We're NOT Doing

- Not adding Firebase Analytics (separate task if needed)
- Not setting up Firebase Auth, Firestore, or other services
- Not implementing ProGuard/R8 mapping file uploads (optional enhancement)
- ~~Not setting up GitHub Actions CI/CD for Firebase~~ (included in Phase 5)

---

## Phase 0: Worktree Setup

### Overview

Create a git worktree for this feature to keep the main branch clean.

### Steps:

```bash
# Create worktree directory if it doesn't exist
mkdir -p .worktrees

# Create worktree for this feature
git worktree add .worktrees/add-firebase-crashlytics -b feature/add-firebase-crashlytics

# Move into the worktree
cd .worktrees/add-firebase-crashlytics
```

### Success Criteria:
- [ ] Worktree created at `.worktrees/add-firebase-crashlytics`
- [ ] On new branch `feature/add-firebase-crashlytics`
- [ ] All subsequent work done in this worktree

**Note:** When done, merge to master and remove the worktree:
```bash
cd /var/home/l/git/whisper-to-input
git worktree merge .worktrees/add-firebase-crashlytics
git worktree remove .worktrees/add-firebase-crashlytics
git branch -d feature/add-firebase-crashlytics
```

## Phase 1: Firebase Project Setup (Manual)

### Overview
Create a Firebase project and download the configuration file. This is a one-time manual step.

### Steps:

1. **Create Firebase Project**
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Click "Create a project"
   - Enter project name: `whisper-to-input` (or your preference)
   - Disable Google Analytics for now (can enable later)
   - Complete project creation

2. **Register Android App**
   - In Firebase Console, click "Add app" → Android
   - Enter package name: `com.example.whispertoinput`
   - App nickname: `Whisper To Input`
   - Skip SHA-1 for now (not needed for Crashlytics)
   - Download `google-services.json`

3. **Place Configuration File**
   ```bash
   # Place in the app directory (NOT in src/)
   cp ~/Downloads/google-services.json android/app/google-services.json
   ```

4. **Verify File Location**
   ```
   whisper-to-input/
   └── android/
       └── app/
           ├── build.gradle.kts
           ├── google-services.json  ← HERE
           └── src/
   ```

5. **Add to .gitignore**
   ```bash
   echo "google-services.json" >> android/app/.gitignore
   ```

**⚠️ IMPORTANT:** Never commit `google-services.json` to version control. It contains API keys and project configuration.

### Success Criteria:
- [ ] Firebase project created in console
- [ ] `google-services.json` downloaded and placed correctly
- [ ] File added to `.gitignore`

**Manual Verification:** Open Firebase Console → Project Settings → Verify app is registered

---

## Phase 2: Gradle Configuration (Conditional Build)

### Overview
Configure Gradle to conditionally apply Firebase based on whether `google-services.json` exists.

### Changes Required:

#### 1. Project-level build.gradle.kts
**File:** `android/build.gradle.kts`

```kotlin
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    
    // Google Services plugin - applied conditionally in app module
    id("com.google.gms.google-services") version "4.4.2" apply false
    
    // Crashlytics Gradle plugin - for mapping file uploads (optional)
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
}
```

#### 2. App-level build.gradle.kts
**File:** `android/app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Google Services and Crashlytics plugins applied conditionally below
}

// Check if google-services.json exists for conditional Firebase setup
val googleServicesJson = file("google-services.json")
val hasGoogleServices = googleServicesJson.exists()

// Conditionally apply Google Services plugin
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.example.whispertoinput"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.whispertoinput"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Firebase (only if google-services.json exists)
    if (hasGoogleServices) {
        implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
        implementation("com.google.firebase:firebase-crashlytics")
        implementation("com.google.firebase:firebase-analytics")
    }
    
    // Existing dependencies
    implementation("io.ktor:ktor-client-okhttp:2.3.6")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("com.github.liuyueyi:quick-transfer-core:0.2.13")
}
```

### Success Criteria:

#### Automated Verification:
- [ ] Build succeeds without `google-services.json`: `cd android && ./gradlew assembleDebug`
- [ ] Build succeeds with `google-services.json`: Place file, then `./gradlew assembleDebug`
- [ ] No lint errors: `./gradlew lint`

#### Manual Verification:
- [ ] APK installs and runs on de-googled Pixel
- [ ] No Firebase-related logcat errors on Pixel

---

## Phase 3: Crashlytics Initialization & Features

### Overview
Add runtime initialization and crash reporting features.

### Changes Required:

#### 1. Create Crashlytics Helper (Optional but Recommended)
**File:** `android/app/src/main/java/com/example/whispertoinput/CrashlyticsHelper.kt`

```kotlin
package com.example.whispertoinput

import android.util.Log

/**
 * Wrapper for Firebase Crashlytics that gracefully handles
 * when Firebase is not available (de-googled devices).
 */
object CrashlyticsHelper {
    private const val TAG = "CrashlyticsHelper"
    private var isAvailable = false

    fun initialize() {
        try {
            // Check if Firebase is available
            val firebaseClass = Class.forName("com.google.firebase.FirebaseApp")
            isAvailable = true
            Log.d(TAG, "Firebase Crashlytics available")
        } catch (e: ClassNotFoundException) {
            isAvailable = false
            Log.d(TAG, "Firebase not available - running without crash reporting")
        }
    }

    /**
     * Log a non-fatal error to Crashlytics
     */
    fun logError(throwable: Throwable, message: String? = null) {
        if (!isAvailable) return
        try {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            if (message != null) {
                crashlytics.log(message)
            }
            crashlytics.recordException(throwable)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log error to Crashlytics", e)
        }
    }

    /**
     * Set a custom key for crash reports
     */
    fun setCustomKey(key: String, value: String) {
        if (!isAvailable) return
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setCustomKey(key, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set custom key", e)
        }
    }

    /**
     * Set a custom key for crash reports
     */
    fun setCustomKey(key: String, value: Boolean) {
        if (!isAvailable) return
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setCustomKey(key, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set custom key", e)
        }
    }

    /**
     * Set user identifier (hashed for privacy)
     */
    fun setUserId(userId: String) {
        if (!isAvailable) return
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setUserId(userId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set user ID", e)
        }
    }

    /**
     * Log a breadcrumb for crash context
     */
    fun log(message: String) {
        if (!isAvailable) return
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .log(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log to Crashlytics", e)
        }
    }
}
```

#### 2. Update MainActivity.kt
**File:** `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`

Add to imports:
```kotlin
import com.google.firebase.crashlytics.FirebaseCrashlytics
```

Add in `onCreate` after `setContentView`:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    
    // Initialize Crashlytics helper
    CrashlyticsHelper.initialize()
    
    // Set custom keys for crash context
    CrashlyticsHelper.setCustomKey("stt_backend", "pending")
    CrashlyticsHelper.setCustomKey("device_type", android.os.Build.MODEL)
    
    // Log app startup
    CrashlyticsHelper.log("MainActivity.onCreate completed")
    
    setupSettingItems()
    checkPermissions()
}
```

#### 3. Add Crash Reporting to Transcriber (Key Feature)
**File:** `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`

Add error reporting in catch blocks:
```kotlin
// In any catch block that handles transcription errors:
catch (e: Exception) {
    CrashlyticsHelper.logError(e, "Transcription failed for backend: $backend")
    CrashlyticsHelper.setCustomKey("last_error", e.message ?: "unknown")
    // ... existing error handling
}
```

#### 4. Add Crash Reporting to RecorderManager
**File:** `android/app/src/main/java/com/example/whispertoinput/recorder/RecorderManager.kt`

Add error reporting for audio recording issues:
```kotlin
catch (e: Exception) {
    CrashlyticsHelper.logError(e, "Recording failed")
    // ... existing error handling
}
```

### Success Criteria:

#### Automated Verification:
- [ ] App compiles: `./gradlew assembleDebug`
- [ ] No lint errors related to Crashlytics
- [ ] App runs on emulator without crashes

#### Manual Verification:
- [ ] Test crash appears in Firebase Console:
  ```kotlin
  // Add temporary button or code to force crash:
  FirebaseCrashlytics.getInstance().crash()
  ```
- [ ] Non-fatal errors appear in Crashlytics dashboard
- [ ] Custom keys visible in crash details

---

## Phase 4: Testing Workflow

### Overview
Establish a testing workflow that supports both de-googled Pixel and Firebase testing.

### Testing Commands (Add to justfile)

```bash
# Build with Firebase (requires google-services.json)
build-firebase:
    #!/usr/bin/env bash
    export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    if [ ! -f android/app/google-services.json ]; then
        echo "⚠️  google-services.json not found"
        echo "   Place it in android/app/google-services.json"
        echo "   Or run 'just build' for non-Firebase build"
        exit 1
    fi
    cd android && ./gradlew assembleDebug
    echo "✅ Firebase build successful"

# Build without Firebase (for de-googled Pixel)
build:
    #!/usr/bin/env bash
    export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    cd android && ./gradlew assembleDebug
    echo "✅ Build successful (Firebase disabled)"

# Install and test on emulator
test-firebase: build-firebase start
    @echo "=== Firebase Test ==="
    echo "1. Build with Firebase ✓"
    echo "2. Emulator running ✓"
    {{adb}} install -r android/app/build/outputs/apk/debug/app-debug.apk
    echo "3. APK installed ✓"
    echo "4. Open app and trigger a crash to test Crashlytics"
    echo "5. Check Firebase Console for crash reports"
```

### Testing Workflow:

1. **Daily Development (on Pixel):**
   ```bash
   just build          # No google-services.json needed
   adb install ...     # Install on Pixel
   ```

2. **Firebase Testing (on emulator):**
   ```bash
   # Ensure google-services.json is in place
   just test-firebase  # Builds with Firebase, installs on emulator
   # Trigger a crash, check Firebase Console
   ```

3. **Production Release:**
   ```bash
   # Ensure google-services.json is in place
   just build-firebase
   # Sign and upload to Play Store
   ```

### Success Criteria:
- [ ] `just build` works without `google-services.json`
- [ ] `just build-firebase` works with `google-services.json`
- [ ] Crash reports visible in Firebase Console after test crash
- [ ] App runs normally on de-googled Pixel

---

## Phase 5: CI Integration (GitHub Actions)

### Overview

Enable Firebase builds in CI using GitHub Secrets. The `google-services.json` file is base64-encoded and stored as a secret, then decoded during the build step.

### Setup Steps (Manual)

#### 1. Encode google-services.json

```bash
# On your local machine, run:
base64 -w 0 android/app/google-services.json

# Copy the output (one long base64 string)
```

#### 2. Add GitHub Secret

1. Go to your repo → **Settings** → **Secrets and variables** → **Actions**
2. Click **New repository secret**
3. Name: `GOOGLE_SERVICES_JSON_BASE64`
4. Value: paste the base64 string from Step 1

### Changes Required:

#### 1. Update CI Workflow

**File:** `.github/workflows/build-apk.yml`

```yaml
name: Build APK

on:
  push:
    branches: [master]
  pull_request:
    branches: [master]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Grant execute permission for gradlew
        run: chmod +x android/gradlew

      - name: Decode google-services.json
        if: env.GOOGLE_SERVICES_JSON_BASE64 != ''
        run: |
          echo "${{ secrets.GOOGLE_SERVICES_JSON_BASE64 }}" | base64 -d > android/app/google-services.json
        env:
          GOOGLE_SERVICES_JSON_BASE64: ${{ secrets.GOOGLE_SERVICES_JSON_BASE64 }}

      - name: Build Debug APK
        run: ./gradlew assembleDebug
        working-directory: android

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: whisper-to-input-debug
          path: android/app/build/outputs/apk/debug/app-debug.apk
          retention-days: 7
```

### How It Works:

```
┌─────────────────────────────────────────────────────────────────┐
│                        GitHub Secrets                            │
│  GOOGLE_SERVICES_JSON_BASE64 = base64(google-services.json)     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  CI: Decode Step                                 │
│  echo "..." | base64 -d > android/app/google-services.json     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Gradle: Conditional Build                       │
│  hasGoogleServices = true → Firebase enabled                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    APK with Firebase enabled
```

### Security Considerations:

| Concern | Status |
|---------|--------|
| Secret in workflow logs | ✅ GitHub masks secrets automatically |
| Fork PRs | ✅ Secrets not exposed to forks (safe) |
| Secret rotation | Regenerate base64 if file changes |

### Success Criteria:

#### Automated Verification:
- [ ] CI workflow runs successfully on push to master
- [ ] `google-services.json` decoded from secret (check workflow logs)
- [ ] Build succeeds with Firebase enabled
- [ ] APK artifact uploaded with Firebase

#### Manual Verification:
- [ ] Download CI-built APK and verify Firebase is enabled
- [ ] Test crash appears in Firebase Console

## Optional Enhancements (Future)

### 1. ProGuard/R8 Mapping Files
If you enable minification in release builds:
```kotlin
// In app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        proguardFiles(...)
    }
}

// Disable auto-upload (won't work without GMS)
firebaseCrashlytics {
    mappingFileUploadEnabled = false
}
```

### 2. ANR Monitoring
Already included with Crashlytics SDK - no additional setup needed.

### 3. Breadcrumbs via Analytics
Enable Google Analytics in Firebase Console for automatic user action breadcrumbs.

---

## Summary of Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `android/build.gradle.kts` | Modify | Add Google Services & Crashlytics plugins |
| `android/app/build.gradle.kts` | Modify | Conditional Firebase dependencies |
| `android/app/.gitignore` | Create/Modify | Exclude google-services.json |
| `android/app/src/main/java/.../CrashlyticsHelper.kt` | Create | Graceful Crashlytics wrapper |
| `android/app/src/main/java/.../MainActivity.kt` | Modify | Initialize Crashlytics |
| `android/app/src/main/java/.../WhisperTranscriber.kt` | Modify | Add error reporting |
| `android/app/src/main/java/.../recorder/RecorderManager.kt` | Modify | Add error reporting |
| `justfile` | Modify | Add Firebase build commands |
| `.github/workflows/build-apk.yml` | Modify | Add Firebase decode step for CI |

---

## References

- [Firebase Crashlytics Android Setup](https://firebase.google.com/docs/crashlytics/android/get-started)
- [Firebase without Play Services](https://firebase.google.com/docs/android/android-play-services)
- [Firebase BOM](https://firebase.google.com/docs/android/learn-more#bom)
- [GrapheneOS Sandboxed Play](https://grapheneos.org/usage#sandboxed-google-play)
- [GitHub Actions Secrets](https://docs.github.com/en/actions/security-guides/using-secrets-in-github-actions)
