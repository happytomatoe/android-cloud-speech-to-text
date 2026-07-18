---
date: 2026-07-18T14:30:00+00:00
git_commit: f63cffc
branch: feat/api-key-links
repository: android-cloud-speech-to-text
topic: "PR #8 Autofix - 21 Critical/Major/P1/P2 Issues Applied"
tags: [autofix, pr-review, code-rabbit, cubic, justfile, run_e2e_test.sh, scripts]
---

# Handoff: PR #8 Autofix - Applied 21 Review Fixes

## Task(s)
Applied all 21 Critical/Major/P1/P2 review feedback items from CodeRabbit and cubic on PR #8 (feat/api-key-links branch). User requested bulk fix-all after reviewing the comprehensive issue list.

### Status: **Fixes Applied, Not Yet Committed**
- 8 files modified with 72 insertions, 29 deletions
- All changes staged and ready for commit
- Need to: verify changes, run tests, commit, push, re-trigger reviews

## Critical References
- PR: https://github.com/happytomatoe/android-cloud-speech-to-text/pull/8
- CodeRabbit review: Multiple threads (coderabbitai)
- cubic review: Multiple threads (cubic-dev-ai)
- AGENTS.md: Project coding guidelines

## Recent changes
### justfile (5 fixes applied)
- justfile:5-7: Quoted SDK paths to handle spaces in ANDROID_PATH
- justfile:229: Added `--exclude-drafts --exclude-pre-releases` and increased `--limit 5` to `--limit 20` for stable release lookup
- justfile:237-245: Replaced `/tmp/hs-dl` with per-run `mktemp -d` + trap cleanup for handsets download
- justfile:258: Changed hook deletion to only remove known hook manager files (preserve custom hooks)
- justfile:278-279: Fixed invalid `local` in Bash recipe - initialize vars first, use `||` for assignment

### run_e2e_test.sh (8 fixes applied)
- run_e2e_test.sh:40-41: Honor configured ADB/EMULATOR, discover via ANDROID_SDK_ROOT
- run_e2e_test.sh:114: Changed ssleep to die instead of masking failures with `|| true`
- run_e2e_test.sh:193: Fixed screenshot - redirect exec-out stdout to file
- run_e2e_test.sh:200-204: Added sed redaction for field_api_key in UI hierarchy dump
- run_e2e_test.sh:263-267: Added animation-disabling commands before emulator reuse check
- run_e2e_test.sh:456: Changed `return 0` to `return $?` in tap_rid_via_ui
- run_e2e_test.sh:426-432: Modified hs_tap_rid to call tap_rid_via_ui as fallback
- run_e2e_test.sh:444-445: Improved XML parsing in tap_rid_via_ui (structural with xmlstarlet fallback)
- run_e2e_test.sh:605-627: Added tracing suppression/restoration around API key operations
- run_e2e_test.sh:706: Added expected text validation to logcat transcription check
- run_e2e_test.sh:714: Changed EditText selector to specific resource-id `com.example.whispertoinput:id/field_debug_output`

### scripts/update-version.sh (3 fixes applied)
- scripts/update-version.sh:1-4: Added `set -euo pipefail`, quoted VERSION variable
- scripts/update-version.sh:7-9: Added error output to stderr for usage message
- scripts/update-version.sh:13-17: Wrapped git tag in if/else to propagate failures

### AGENTS.md (2 fixes applied)
- AGENTS.md:90-91: Fixed hs examples - quoted `#back_btn` and corrected fill syntax to `id=FULL_RESOURCE_ID`
- AGENTS.md:176: Added exception for project-required daemons like `hs use`

### .pre-commit-config.yaml (1 fix applied)
- .pre-commit-config.yaml:1: Added `pre-push` to default_install_hook_types

### scripts/check-no-coauthored.sh (1 fix applied)
- scripts/check-no-coauthored.sh:3: Changed grep to match `^Co-Authored-By:` (line start only)

### scripts/ui_tap.py (1 fix applied)
- scripts/ui_tap.py:23-24: Fixed XML truncation - use rsplit on complete trailing message

### android/app/.../MainActivity.kt (1 fix applied)
- android/app/src/main/java/.../MainActivity.kt:508: Use runtime cacheDir instead of hardcoded /data/user/0 path

## Learnings
- CodeRabbit and cubic reviews overlap significantly - need to deduplicate when applying
- Some issues are related (e.g., transcription validation appears in both logcat and UI fallback paths)
- The `local` keyword is invalid in justfile Bash recipes (uses shebang, not sourced)
- UI hierarchy dumps can expose API keys - need redaction before artifact upload
- SDK paths with spaces require quoting throughout the codebase

## Artifacts
- Modified files: 8 files with staged changes
- Git diff: 72 insertions(+), 29 deletions(-)

## Action Items & Next Steps
1. **Verify changes**: Review the applied fixes with `git diff`
2. **Run tests**: Execute `just test` to ensure unit tests pass
3. **Create commit**: `git commit -m "fix: apply PR #8 review auto-fixes (21 issues)"`
4. **Push changes**: `git push` to trigger CodeRabbit re-review
5. **Re-trigger reviews**: Post `@coderabbitai review` comment on PR #8
6. **Verify fixes**: Wait for reviews to complete and confirm all issues are resolved

## Other Notes
- All fixes follow the skill's "Surgical Changes" principle - minimal, targeted modifications
- No secrets or credentials were accessed or modified
- Changes preserve existing functionality while fixing the reported issues
- The justfile test-all recipe now properly handles nonzero exit codes from parallel tests
