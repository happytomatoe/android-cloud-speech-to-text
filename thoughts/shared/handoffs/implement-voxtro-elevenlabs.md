---
date: 2026-07-15T17:50:00-07:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "Add Voxtral and ElevenLabs Batch Transcription Providers"
tags: [implementation, elevenlabs, voxtral, emulator, audio-routing, e2e-testing]
status: in_progress
last_updated: 2026-07-15
last_updated_by: pi-agent
type: handoff
---

# Handoff: Voxtral & ElevenLabs Provider Implementation

## Task(s)

**Primary Goal**: Add Voxtral (Mistral AI) and ElevenLabs Scribe batch transcription providers to the Whisper To Input Android keyboard app.

**Plan**: `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`

**Status by Phase**:
- **Phase 1 (String Resources)**: ✅ Complete - All provider strings added to `strings.xml`
- **Phase 2 (WhisperTranscriber.kt)**: ✅ Complete - Request building and response parsing implemented
- **Phase 3 (MainActivity.kt)**: ✅ Complete - Default value wiring for new providers
- **Phase 4 (README.md)**: ⏳ Not started
- **Phase 5 (E2E Testing)**: ❌ Blocked on emulator audio routing

## Critical References

1. **Plan document**: `/var/home/l/git/whisper-to-input/thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md` - Full implementation plan with API specs
2. **Existing E2E test script**: `/var/home/l/git/whisper-to-input/run_e2e_test.sh` - Automated test flow (may need updates for new providers)
3. **Emulator audio routing handoff**: `/var/home/l/git/whisper-to-input/thoughts/shared/handoffs/emulator-audio-routing-e2e-test.md` - Details on audio routing blocker

## Recent Changes

**Phase 1 - String Resources** (`android/app/src/main/res/values/strings.xml`):
- Added `settings_option_voxtral`, `settings_option_voxtral_default_endpoint`, `_model`, `_language`
- Added `settings_option_elevenlabs`, `settings_option_elevenlabs_default_endpoint`, `_model`, `_language`
- Added `settings_elevenlabs_api_key_url_label`
- Updated `settings_speech_to_text_backend_array` to include both providers
- Updated `settings_speech_to_text_backend_desc` to mention all providers

**Phase 2 - WhisperTranscriber.kt** (`android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`):
- Added JSON parsing for Voxtral response (`{"text": "..."}`)
- Added JSON parsing for ElevenLabs response (`{"text": "..."}`)
- Added `xi-api-key` header for ElevenLabs auth
- Added `Authorization: Bearer` header for Voxtral auth
- Added provider-specific form fields: `model` for Voxtral, `model_id` + `language_code` for ElevenLabs
- Fixed URL building to separate Whisper ASR Webservice query params from other providers

**Phase 3 - MainActivity.kt** (`android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`):
- Added default value population for Voxtral (endpoint, model, language)
- Added default value population for ElevenLabs (endpoint, model, language)
- Added new providers to `SettingStringDropdown` initialization

## Learnings

1. **Provider API patterns**:
   - Voxtral: `Authorization: Bearer <key>`, form field `model`, optional `language`
   - ElevenLabs: `xi-api-key: <key>`, form field `model_id`, optional `language_code`
   - Both use `file` form field (like OpenAI), not `audio_file` (like Whisper ASR Webservice)

2. **Response parsing**: Both Voxtral and ElevenLabs return `{"text": "..."}` JSON, unlike OpenAI which returns plain text

3. **ElevenLabs API key**: `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887` - verified working on 2026-07-13, but may be quota-exceeded

4. **Test audio**: `test-sources/test-audio.wav` (5s, says "Hello? What's going on?") - verified via curl against ElevenLabs API

5. **Pre-existing bugs fixed**: URL query-string bug for OpenAI, Content-Type header bug (manual header conflicting with OkHttp's auto-generated boundary)

## Artifacts

- **Plan**: `/var/home/l/git/whisper-to-input/thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`
- **Existing evidence**: `/var/home/l/git/whisper-to-input/thoughts/shared/showboat/elevenlabs-e2e.md` - Screenshots of dropdown, API test, config
- **Emulator audio routing handoff**: `/var/home/l/git/whisper-to-input/thoughts/shared/handoffs/emulator-audio-routing-e2e-test.md`
- **E2E test script**: `/var/home/l/git/whisper-to-input/run_e2e_test.sh`
- **Test audio**: `/var/home/l/git/whisper-to-input/test-sources/test-audio.wav`

## Action Items & Next Steps

1. **Phase 4: Update README.md** - Add configuration examples for Voxtral and ElevenLabs in the Installation section

2. **Phase 5: E2E Testing** - Unblock emulator audio routing:
   - Try `pavucontrol` (install via `sudo dnf install pavucontrol`)
   - Try `QEMU_PA_SOURCE=VirtualMicSink.monitor` env var
   - Or accept API test + UI screenshots as sufficient evidence

3. **Build verification**: Run `./gradlew assembleDebug` to confirm compilation

4. **Update plan checkboxes**: Mark completed phases in the plan document

## Other Notes

- **Emulator setup**: `QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE=FakeMic /var/home/l/Android/Sdk/emulator/emulator -avd Pixel_8 -no-snapshot-load -allow-host-audio -gpu host`
- **Virtual audio**: VirtualMicSink (sink) → FakeMic (source) → QEMU chain via PipeWire
- **ADB path**: `/var/home/l/Android/Sdk/platform-tools/adb`
- **App package**: `com.example.whispertoinput`, Service: `.WhisperInputService`
- **Current emulator state**: Was running with `-allow-host-audio` but crashed during restart attempts
- **FSM thresholds**: Idle→Speaking requires amplitude > 800; Speaking→Finish requires 3s silence
- **Key blocker**: QEMU source-output shows `Corked: yes` because Android FSM never reaches Speaking state before timing out
