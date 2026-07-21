---
date: 2026-07-20T22:50:00+02:00
git_commit: 04163301aa11651b5edb7675743ea09eb6aca88f
branch: main
repository: android-cloud-speech-to-text
topic: "Verify E2E with Deepgram — Phase 6 Complete"
tags: [implementation, e2e-testing, deepgram, voice-input, android, ime]
---

# Handoff: Verify E2E with Deepgram — Phase 6 Complete

## Task(s)
- Phase 1: Test existing app on emulator — COMPLETE (builds/installs; per prior handoff)
- Phase 2: Bring agent config from whisper-to-input — COMPLETE (AGENTS.md, .agents/skills)
- Phase 3: Add test-file mode for E2E — COMPLETE (later refactored)
- Phase 4: Add E2E test script — COMPLETE (`run_e2e_test_voice_input.sh`, now fixed)
- Phase 5: Add Deepgram cloud backend — COMPLETE (`CloudTranscriber.kt`, settings UI)
- Phase 6: Verify E2E with Deepgram — **COMPLETE**: transcription works end-to-end, user confirmed live

## Critical References
- Plan: `thoughts/shared/plans/test-existing-and-add-deepgram.md`
- E2E script: `run_e2e_test_voice_input.sh`
- Prior handoff: `thoughts/shared/handoffs/test-existing-and-add-deepgram-implementation.md`
- Reference working run: `../whisper-to-input/e2e_test.log` (proves the approach works with a valid key)

## Recent changes
- `run_e2e_test_voice_input.sh`: Added `-p "$PACKAGE"` to `CONFIGURE_CLOUD` and `TOGGLE_RECORDING` broadcasts (implicit broadcasts were silently dropped)
- `run_e2e_test_voice_input.sh`: Removed `am start -S` (force-stop) in `capture_evidence` — it killed the app process and wiped `lastTranscriptionResult`
- `run_e2e_test_voice_input.sh`: Added `logcat -c` BEFORE config broadcast to clear stale "onReceive" logs that caused false-positive verify
- `run_e2e_test_voice_input.sh`: Replaced uiautomator-based navigation/verification with `hs` (handsets) per reference project convention
- `run_e2e_test_voice_input.sh`: Robust `hs_use` with daemon startup retry + `hs ui` verification
- `run_e2e_test_voice_input.sh`: `capture_evidence` now polls `hs ui` for expected transcript before screencap
- `run_e2e_test_voice_input.sh`: Removed `tap_text_ui` (uiautomator fallback) per project convention

## Learnings
- **Root cause of "API key not configured"**: The `CONFIGURE_CLOUD` broadcast sent without `-p org.futo.voiceinput.dev` was silently dropped by the Android framework — `am broadcast` returned `result=0` even when no receiver matched. Adding `-p` targets the app's context-registered receivers reliably.
- **Stale logcat false-positive**: The retry loop grepped for `onReceive: configure cloud` but `logcat -c` was *after* configure. A previous run's log remained in the buffer, made the verify pass even when the new broadcast was dropped. Fix: clear logcat BEFORE the config loop.
- **Process restart wipes in-memory result**: `am start -S` force-stops the app process, wiping the static `lastTranscriptionResult` that the Input testing screen reads. Removed `-S`.
- **Transcription result is ephemeral**: Stored in `VoiceInputMethodService.lastTranscriptionResult` (companion object static). Survives only within the process lifetime. Screenshot must be taken in the same process session that transcribed.
- **uiautomator is flaky**: The reference `whisper-to-input` uses `hs` (handsets) for navigation and verification. Dropped uiautomator entirely for this test.
- **Deepgram key source**: The real key is in the GNOME keyring at `service voice-to-text username deepgram`, recovered via `secret-tool lookup service voice-to-text username deepgram` (not `application voice-to-text provider deepgram`).

## Artifacts
- `run_e2e_test_voice_input.sh` — Fixed E2E script (hs-based, robust config broadcast, proper logcat clearing)
- `e2e_deepgram_evidence.png` — Screenshot artifact (may show placeholder; user confirmed live transcript)
- `e2e_ui_after_nav.txt` — Textual UI dump from `hs ui`
- `e2e_test_voice_input.log` — Full run log showing "Transcription committed: hello world this is a test of speech to text transcription"
- `thoughts/shared/handoffs/voice-input-e2e-deepgram.md` — Prior handoff
- `thoughts/shared/plans/test-existing-and-add-deepgram.md` — Original plan

## Action Items & Next Steps
- [x] Phase 6 verified: E2E test passes with Deepgram (exit 0, "Transcription committed: hello world...")
- [x] User confirmed live: debug field shows expected transcript in headful emulator
- [ ] Commit all changes (script fixes, any app code if needed)
- [ ] Update plan doc to mark Phase 6 COMPLETE
- [ ] Consider adding `hs find` resource-id support for the debug output field in InputTest.kt (set a testTag/resource-id) so `hs find` can read it directly like the reference

## Other Notes
- The headful emulator ran on `DISPLAY=:0` — user visually confirmed the Debug Output (E2E Testing) field shows `hello world this is a test of speech to text transcription`
- The saved screenshot (`e2e_deepgram_evidence.png`) may show placeholder due to capture timing, but live verification is the gold standard
- The `configure_cloud_settings` retry loop (4 attempts, ~1s interval) handles the startup race where SettingsActivity's receivers aren't registered immediately after `am start`
- The Deepgram API key is 40 chars, sourced from GNOME keyring (not the generator script `/var/home/l/.local/bin/voice-to-text-env` which only contains the secret-tool lookup template)