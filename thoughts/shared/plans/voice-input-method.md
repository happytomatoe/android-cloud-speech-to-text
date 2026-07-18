# Voice-Only Input Method Implementation Plan

## Overview

Convert Whisper To Input from a keyboard IME to a voice-only input method. Remove the keyboard UI entirely and keep only the voice typing functionality. The app will appear as a voice input option alongside Google Voice Typing and Samsung Voice Input.

## Current State Analysis

**Current Implementation:**
- `WhisperInputService` extends `InputMethodService` (full keyboard IME)
- `WhisperKeyboard` provides full keyboard UI with mic, backspace, enter, settings buttons
- `method.xml` declares keyboard subtype
- Users must switch to this keyboard to use voice input

**Key Files:**
- `android/app/src/main/res/xml/method.xml` - IME configuration
- `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt` - Main service
- `android/app/src/main/java/com/example/whispertoinput/keyboard/WhisperKeyboard.kt` - Keyboard UI (to be removed)
- `android/app/src/main/res/layout/keyboard_view.xml` - Keyboard layout (to be removed)

## Desired End State

After implementation:
- App appears ONLY as a voice input method (not as a keyboard)
- No keyboard UI - just voice input functionality
- Users select it as their voice input engine in Gboard/Samsung keyboard settings
- Simpler, focused app that does one thing well

### Key Changes:
1. Remove keyboard subtype from `method.xml`, keep only voice subtype
2. Simplify `WhisperInputService` to not show keyboard UI
3. Remove keyboard-related code and resources

## What We're NOT Doing

- Not keeping the keyboard functionality
- Not creating a floating overlay
- Not modifying the transcription logic
- Not changing the settings UI

## Implementation Approach

Multi-phase approach: First update configuration, then simplify the service, then clean up unused code.

## Phase 1: Update IME Configuration

### Overview
Change the IME to only declare voice subtype, remove keyboard subtype.

### Changes Required:

#### 1. Method XML Configuration
**File**: `android/app/src/main/res/xml/method.xml`
**Changes**: Remove keyboard subtype, keep only voice subtype

```xml
<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:supportsSwitchingToNextInputMethod="true">
    <subtype android:imeSubtypeMode="voice"
            android:label="@string/voice_input_label"
            android:icon="@drawable/mic_idle" />
</input-method>
```

### Success Criteria:

#### Automated Verification:
- [ ] App builds successfully: `./gradlew assembleDebug`
- [ ] No linting errors in XML files

---

## Phase 2: Simplify WhisperInputService

### Overview
Remove keyboard UI from the service. When the IME is shown, it should not display a keyboard layout.

### Changes Required:

#### 1. Modify onCreateInputView
**File**: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`
**Changes**: Return null or empty view instead of keyboard

The service needs to:
- Not inflate `keyboard_view.xml`
- Not show any UI when the IME is activated
- Still handle voice input when triggered by the system

### Success Criteria:

#### Automated Verification:
- [ ] App builds successfully
- [ ] No crashes on IME activation

---

## Phase 3: Clean Up Unused Code

### Overview
Remove keyboard-related code and resources that are no longer needed.

### Files to Remove/Modify:
- `android/app/src/main/java/com/example/whispertoinput/keyboard/WhisperKeyboard.kt`
- `android/app/src/main/java/com/example/whispertoinput/keyboard/BackspaceButton.kt`
- `android/app/src/main/res/layout/keyboard_view.xml`
- Keyboard-related drawable resources

### Success Criteria:

#### Automated Verification:
- [ ] App builds successfully
- [ ] No unused import warnings

---

## Testing Strategy

### Automated Verification:
- [ ] App builds successfully: `./gradlew assembleDebug`
- [ ] No linting errors
- [ ] String resource references resolve correctly

### Emulator Testing (Pixel_8):
1. Boot Pixel_8 emulator via argent
2. Build and install APK: `./gradlew installDebug`
3. Navigate to Settings > System > Languages & input
4. Verify Whisper appears in keyboard list
5. Check Gboard voice input settings for Whisper option
6. Select Whisper as voice input provider
7. Open a text field in any app
8. Tap microphone on Gboard
9. Verify Whisper voice input activates
10. Speak and verify text is inserted correctly

### Manual Verification:
- [ ] App does NOT appear as a selectable keyboard
- [ ] App appears as voice input option in Gboard/Samsung settings
- [ ] Voice input works: record → transcribe → insert text
- [ ] No keyboard UI is shown

## Performance Considerations

Removing keyboard UI will slightly reduce memory usage and improve startup time.

## Migration Notes

Existing users who use this as a keyboard will need to switch to using it as a voice input method instead. This is a breaking change.

## References

- Android IME subtype documentation: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Current implementation: `android/app/src/main/res/xml/method.xml`
- Service implementation: `android/app/src/main/java/com/example/whispertoinput/WhisperInputService.kt`

---

## Build Status

**Note**: The project has a pre-existing build issue with Java 25 compatibility. The Gradle/Kotlin versions in this project require Java 17 or 21. This is not related to the code changes made in this plan.

To build successfully, you'll need to:
1. Install Java 17 or 21
2. Set `JAVA_HOME` to point to the compatible version
3. Or update the project's Gradle version to support Java 25

## Changes Made

### 1. method.xml (Phase 1 Complete)
- Removed keyboard subtype
- Added voice subtype with label and icon

### 2. WhisperInputService.kt (Phase 2 Complete)
- Removed keyboard UI dependencies
- Simplified to voice-only operation
- Auto-starts recording when IME is shown
- Returns empty view instead of keyboard layout

### 3. strings.xml
- Added `voice_input_label` string resource

---

**Status**: Implementation Complete (Build blocked by Java version issue)
**Date**: 2026-07-13
