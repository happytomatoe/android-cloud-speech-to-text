---
date: 2026-07-14T21:41:58+02:00
researcher: pi-agent
git_commit: 1e2b8590491e36c6f4e0787bb55c6c2892d73ccd
branch: feature/voice-only-ime
repository: whisper-to-input
topic: "Deepgram, Groq, and 60db Batch Transcription Providers for Android"
tags: [implementation, android, whisper, deepgram, groq, 60db, transcription]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: implementation_strategy
---

# Handoff: Deepgram + Groq + 60db Batch Transcription Providers

## Task(s)
Implement three new **batch** transcription providers (Deepgram, Groq, 60db) for the Whisper To Input Android keyboard app, per plan `thoughts/shared/plans/add-deepgram-groq-60db-batch-providers.md`. This continues the prior Voxtral/ElevenLabs work (parakeet excluded, streaming excluded — batch only).

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: String resources | COMPLETE | Provider names, default endpoint/model/language, backend dropdown array, description strings. |
| Phase 2: `WhisperTranscriber.kt` | COMPLETE | Deepgram early-return (raw body + Token + query params), groq/60db in file-field + params + Bearer headers `when`, Deepgram + 60db response parsing. |
| Phase 3: `MainActivity.kt` default wiring | COMPLETE | 3 providers added to dropdown list + 3 `else if` default-population branches. |
| Phase 4: `README.md` | COMPLETE | 3 config examples + 3 Services sections. |
| Build (`just build`) | COMPLETE | Builds successfully (see Learnings for pre-existing fix). |
| Lint (`./gradlew lint`) | VERIFIED (clean) | Re-run completed. **0 new** errors/warnings from provider changes. Only 1 lint error total, and it is PRE-EXISTING + out of scope: `WhisperInputService.kt:64` `switchToPreviousInputMethod()` requires API 28 (min 24). That file is unmodified vs HEAD. Remaining 45 warnings are cosmetic/pre-existing (unused resources, `scribe_v1` spell-check, `POST_NOTIFICATIONS` InlinedApi). |
| Manual / Emulator verification | PENDING | Requires valid API keys (paid) — not done. |

## Critical References
- Plan: `thoughts/shared/plans/add-deepgram-groq-60db-batch-providers.md` (full API shapes + exact code blocks).
- Provider pattern source: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` (`buildWhisperRequest`, response parsing).
- Settings wiring: `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` `SettingStringDropdown`.
- Predecessor plan (continues this work): `thoughts/shared/plans/add-voxtro-elevenlabs-batch-providers.md`.

## Recent changes
All changes are uncommitted working-tree modifications on branch `feature/voice-only-ime`.

- `android/app/src/main/res/values/strings.xml`
  - Added Deepgram/Groq/60db string groups (option name + default endpoint/model/language) after the ElevenLabs block.
  - Added 3 items (`settings_option_deepgram`, `settings_option_groq`, `settings_option_60db`) to `settings_speech_to_text_backend_array`.
  - Generalized `settings_speech_to_text_backend_desc`, `settings_endpoint_desc`, `settings_api_key_desc` to mention the new providers.
  - Changed `error_apikey_unset` from "OpenAI API Key..." to generic "API Key is not set in settings." (matches plan + success criterion).
  - **Pre-existing build fix:** added `settings_elevenlabs_api_key_url_label` (see Learnings) so AAPT resource linking succeeds.

- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`
  - Deepgram early-return block right after `fileBody` creation: raw binary `RequestBody`, `Authorization: Token <key>`, model/detect_language as URL query params, returns before the shared multipart builder.
  - `file` field `when`: added groq + 60db to the `addFormDataPart("file", ...)` group.
  - Provider-specific params `when`: added groq (`model` + `response_format=text`) and 60db (optional `language`) branches.
  - Headers `when`: added groq + 60db to the `Bearer` auth group (ElevenLabs stays on `xi-api-key`).
  - Response parsing: added Deepgram (`results.channels[0].alternatives[0].transcript`) and 60db (`data.text` with top-level `text` fallback) JSON handling.

- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
  - `SettingStringDropdown` option list: appended deepgram/groq/60db after elevenlabs.
  - `onItemSelected`: appended 3 `else if` branches populating defaults (endpoint reset guard checks all *other* providers' default endpoints) + model + language.

- `README.md`
  - Installation section: added Deepgram/Groq/60db config examples after the ElevenLabs example.
  - Services section: added Deepgram/Groq/60db subsections before Debugging.

## Learnings
- **Build environment:** A warm Gradle daemon is already running (Gradle 8.14). `just build` uses the daemon by default — do NOT pass `--no-daemon` (it forces a cold in-process build and is much slower). `JAVA_HOME` is set inside the justfile to `~/.sdkman/candidates/java/17.0.13-tem`; `ANDROID_HOME`/`ANDROID_SDK_ROOT` = `/var/home/l/Android/Sdk`. A full cold build can exceed the 600s tool timeout — run `just build` via `nohup just build > /tmp/build.log 2>&1 &` and poll the log, otherwise the harness aborts a bare `sleep` wait.
- **Pre-existing build break (outside scope, but blocked compilation):** `android/app/src/main/res/layout/activity_main.xml` has an orphaned `TextView` `link_elevenlabs_api_key` (`visibility="gone"`, not referenced in any Kotlin) that points to `@string/settings_elevenlabs_api_key_url_label`, which was never defined. This broke AAPT resource linking. Per user decision, fixed by **adding** the missing string (not removing the view). Any future build failure on `settings_elevenlabs_api_key_url_label` means this string was removed.
- **Edit-tool brace pitfall:** When using `insert_after` to add `else if` branches to a `when` block, anchor on the **branch's** closing `}` (16-space indent), not the `when`'s closing `}` (12-space indent). Anchoring on the wrong `}` caused a stray `}` that prematurely closed the outer `if (parent.id == ...)` block (MainActivity) and, in WhisperTranscriber, left the elevenlabs branch unclosed with an extra `}` before `when` closed — both produced "Unexpected tokens" / "Expecting a top level declaration" errors. After `insert_after`, re-read the region and confirm brace balance; a full-region `replace` is the safest fix when braces drift.

## Artifacts
- Plan: `thoughts/shared/plans/add-deepgram-groq-60db-batch-providers.md`
- Modified implementation files (all uncommitted):
  - `android/app/src/main/res/values/strings.xml`
  - `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt`
  - `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt`
  - `README.md`
- Pre-existing (unrelated to this task, already in working tree before session): `activity_main.xml`, `RecorderManager.kt`, `colors.xml`, `gradle-wrapper.properties` — do not assume these are part of this change.

## Action Items & Next Steps
1. ~~Re-run `./gradlew lint`~~ DONE — clean (only pre-existing `WhisperInputService.kt:64` error, out of scope). No *new* issues from provider changes.
2. Manual/emulator verification (requires paid API keys): select each provider in the dropdown, confirm defaults auto-populate, confirm stale-endpoint replacement, confirm missing-API-key message, and confirm a real transcription returns text. Deepgram is the riskiest (raw body + query params) — verify first.
3. Optionally run `just test-e2e` if an emulator session is available.
4. Commit the change set (the provider work; consider whether to include the unrelated pre-existing modifications separately).

## Other Notes
- Audio format: new providers receive default `audio/mp4` (MPEG-4/AMR) like all non-NIM backends; Deepgram auto-detects encoding from the `Content-Type` header, so no audio-format change is needed.
- Groq returns plain text (no JSON handling, falls through like OpenAI). 60db and Deepgram return JSON and are parsed explicitly.
- The goal (`mrl1oqv1-ab8im7`) is currently **paused**; this implementation was done while paused at the user's direction. No commit was made.
