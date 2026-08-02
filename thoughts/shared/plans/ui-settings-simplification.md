# UI Settings Simplification Plan

## Overview

Simplify the settings UI by removing verbose descriptions from key fields, repositioning the API key creation link, changing a default value, and removing the postprocessing section entirely.

## Current State Analysis

The settings screen has multiple fields with descriptive text that adds visual clutter:
- Speech to Text Backend: Has a long description about supported backends
- Endpoint: Has a description about host/port configuration
- API Key: Has a description about which backends require keys
- Create API Key link: Currently positioned after the backend spinner
- Auto Switch Back: Defaults to "No"
- Postprocessing: Has a full section with label, description, and dropdown

## Desired End State

A cleaner settings UI with:
- No descriptions for Speech to Text Backend, Endpoint, or API Key fields
- "Create API Key" link positioned directly above the API Key field
- Auto Switch Back defaulting to "Yes"
- Postprocessing section completely removed

### Key Discoveries:
- Layout file: `android/app/src/main/res/layout/activity_main.xml`
- Kotlin logic: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
- String resources: `android/app/src/main/res/values/strings.xml`
- The "Create API Key" link (`R.id.link_create_api_key`) is dynamically shown/hidden based on backend selection in `MainActivity.kt`

## What We're NOT Changing

- Other field descriptions (Model, Language Code, Auto Recording Start, etc.)
- The API Key Source section
- The Key Manager App section
- The Add Trailing Space section
- Any backend logic or functionality

## Implementation Approach

Remove XML elements for descriptions, relocate the API key link, update default value in Kotlin, and delete the postprocessing section from both XML and Kotlin.

---

## Phase 1: Remove Field Descriptions

### Overview
Remove the description TextViews for Speech to Text Backend, Endpoint, and API Key from the layout.

### Changes Required:

#### 1. Layout XML
**File**: `android/app/src/main/res/layout/activity_main.xml`
**Changes**: Remove three description TextView elements

Remove these elements:
- `description_speech_to_text_backend` (lines ~127-130)
- `description_endpoint` (lines ~140-143)
- `description_api_key` (lines ~152-155)

### Success Criteria:

#### Automated Verification:
- [ ] XML is valid: `xmllint --noout android/app/src/main/res/layout/activity_main.xml`
- [ ] Build succeeds: `just build` or `./gradlew assembleDebug`

#### Manual Verification:
- [ ] Settings screen opens without crashes
- [ ] Speech to Text Backend shows only label and dropdown (no description)
- [ ] Endpoint shows only label and input field (no description)
- [ ] API Key shows only label and input field (no description)

---

## Phase 2: Relocate Create API Key Link

### Overview
Move the "Create API Key" link to appear directly above the API Key field, regardless of backend selection visibility logic.

### Changes Required:

#### 1. Layout XML
**File**: `android/app/src/main/res/layout/activity_main.xml`
**Changes**: Move the `link_create_api_key` TextView from after the backend spinner to before the API Key label

Current position: After `spinner_speech_to_text_backend`
New position: Before `label_api_key`

#### 2. Kotlin Logic
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
**Changes**: Keep the existing visibility logic that shows/hides the link based on backend selection

No Kotlin changes needed - the existing logic already handles showing/hiding the link.

### Success Criteria:

#### Automated Verification:
- [ ] XML is valid
- [ ] Build succeeds

#### Manual Verification:
- [ ] "Create API Key" link appears above the API Key field when a backend requiring a key is selected
- [ ] Link is hidden for backends that don't require keys (Whisper ASR Webservice)
- [ ] Link opens correct provider URL when clicked

---

## Phase 3: Change Auto Switch Back Default

### Overview
Change the default value for Auto Switch Back from "No" to "Yes".

### Changes Required:

#### 1. Kotlin Logic
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
**Changes**: Change the default parameter in SettingDropdown initialization

Current code (line ~308):
```kotlin
SettingDropdown(R.id.spinner_auto_switch_back, AUTO_SWITCH_BACK, hashMapOf(
    getString(R.string.settings_option_yes) to true,
    getString(R.string.settings_option_no) to false,
), false),  // Change false to true
```

New code:
```kotlin
SettingDropdown(R.id.spinner_auto_switch_back, AUTO_SWITCH_BACK, hashMapOf(
    getString(R.string.settings_option_yes) to true,
    getString(R.string.settings_option_no) to false,
), true),  // Default is now true (Yes)
```

### Success Criteria:

#### Automated Verification:
- [ ] Build succeeds

#### Manual Verification:
- [ ] Fresh install shows "Yes" selected for Auto Switch Back
- [ ] Existing user settings are preserved (not overwritten)

---

## Phase 4: Remove Postprocessing Section

### Overview
Remove the entire Postprocessing section from the settings UI.

### Changes Required:

#### 1. Layout XML
**File**: `android/app/src/main/res/layout/activity_main.xml`
**Changes**: Remove the Postprocessing section (lines ~218-228)

Remove these elements:
```xml
<!-- Postprocessing -->
<TextView
    android:id="@+id/label_postprocessing"
    style="@style/SettingsLabel"
    android:text="@string/settings_postprocessing" />
<TextView
    android:id="@+id/description_postprocessing"
    style="@style/SettingsDescription"
    android:text="@string/settings_postprocessing_desc" />
<Spinner
    android:id="@+id/spinner_postprocessing"
    style="@style/SettingsSpinner"
    android:entries="@array/settings_postprocessing_array" />
```

#### 2. Kotlin Logic
**File**: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
**Changes**: Remove the SettingStringDropdown for postprocessing from the settingItems array (line ~318)

Remove:
```kotlin
SettingStringDropdown(R.id.spinner_postprocessing, POSTPROCESSING, listOf(
    getString(R.string.settings_option_to_traditional),
    getString(R.string.settings_option_to_simplified),
    getString(R.string.settings_option_no_conversion)
), getString(R.string.settings_option_to_traditional)),
```

#### 3. String Resources (Optional Cleanup)
**File**: `android/app/src/main/res/values/strings.xml`
**Changes**: Remove unused string resources:
- `settings_postprocessing`
- `settings_postprocessing_desc`
- `settings_option_to_traditional`
- `settings_option_to_simplified`
- `settings_option_no_conversion`
- `settings_postprocessing_array`

### Success Criteria:

#### Automated Verification:
- [ ] XML is valid
- [ ] Build succeeds with no unused resource warnings
- [ ] No compile errors from missing references

#### Manual Verification:
- [ ] Postprocessing section no longer appears in settings
- [ ] Settings screen scrolls properly without the removed section
- [ ] Apply button still works correctly

---

## Testing Strategy

### Manual Testing Steps:
1. Clean install the app and verify default settings:
   - Auto Switch Back defaults to "Yes"
   - No descriptions under Speech to Text Backend, Endpoint, or API Key
   - No Postprocessing section visible
2. Select different backends and verify:
   - "Create API Key" link appears above API Key field for backends requiring keys
   - Link opens correct provider URL
3. Change settings and tap Apply - verify settings save correctly
4. Reopen settings and verify saved values persist

## Performance Considerations

No performance impact - this is purely a UI layout change with removal of unused elements.

## Migration Notes

- Existing user settings for Postprocessing will be ignored (key exists but UI is removed)
- No data migration needed - the preference key can remain in DataStore without causing issues

## References

- Layout file: `android/app/src/main/res/layout/activity_main.xml`
- Main activity: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
- String resources: `android/app/src/main/res/values/strings.xml`
