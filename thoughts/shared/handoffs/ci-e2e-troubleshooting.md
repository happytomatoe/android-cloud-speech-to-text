---
date: 2026-07-18T07:55:00+0200
git_commit: 69405db
branch: feat/api-key-links
repository: whisper-to-input
topic: "CI E2E pipeline troubleshooting + fixes"
tags: [ci, e2e, testing, android, troubleshooting, handoff]
---

# Handoff: CI E2E pipeline now has deterministic spinner tap; run 29635932339 in progress

## Task(s)

**Original goal:** "test if we can run e2e test in ci" — make the CI pipeline execute the real transcription E2E (`run_e2e_test.sh --backend deepgram`) and have it pass.

**Current state:** 
- ✅ Git 128 fixed: `fetch-depth: 0` + `fetch-tags: true` + hardened Gradle versioning
- ✅ Screenshot + XML on failure: `capture_diag` writes `e2e_diag.png` + `e2e_diag.xml` on every failure
- ✅ Portable sleep: `ssleep()` helper works in CI and local
- ✅ Endpoint race fixed: readiness wait for endpoint field + poll verification
- ✅ Spinner tap: now uses `hs tap "#spinner..."` with built-in `--retries 2 --timeout 3s` and 10 attempts (simpler, leverages hs's built-in retry)
- 🔄 **Run 29635932339 in progress** — testing the simplified spinner retry fix

**Open PR:** #8 (feat/api-key-links → main) — updated by latest force-pushes.

## Critical References

- `run_e2e_test.sh` — full E2E test script (fixed: capture_diag, ssleep, readiness wait, spinner retry)
- `.github/workflows/ci.yml` — CI workflow (fetch-depth:0, fetch-tags:true, artifacts include e2e_diag.xml)
- `android/app/build.gradle.kts:7-25` — Git-based versioning with fallback
- `justfile` — `test-all` (fixed wait propagation), `test-e2e-transcribe` target

## Recent changes

- `run_e2e_test.sh:426-450` — added `tap_rid_via_ui()` uiautomator fallback helper (kept for completeness)
- `run_e2e_test.sh:504-530` — spinner open: readiness wait + `hs tap "#spinner..." --retries 2 --timeout 3s` with 10 attempts
- `run_e2e_test.sh:474-512` — `select_backend`: readiness wait + polling verification
- `run_e2e_test.sh:129-135` — `die()` calls `capture_diag` on all failures
- `run_e2e_test.sh:183-200` — `capture_diag()` writes PNG + XML
- `run_e2e_test.sh:113-114` — `ssleep()` portable sleep helper
- `.github/workflows/ci.yml:13-16` — `actions/checkout@v4` with `fetch-depth: 0` + `fetch-tags: true`
- `.github/workflows/ci.yml:82` — artifact upload includes `e2e_diag.xml`
- `android/app/build.gradle.kts:6-24` — hardened versioning with fallback (`0.0.0-SNAPSHOT` / `1`)

## Learnings

1. **Git 128 root cause:** Shallow checkout (`fetch-depth: 1`) + no tags → `git describe --tags` exits 128 "No names found". Not "dubious ownership" as initially assumed. Fixed by `fetch-depth: 0` + `fetch-tags: true`.

2. **Async UI init race:** `MainActivity` initializes settings UI in coroutines (`setupSettingItemsDone` gate). Tapping spinner before coroutine finishes → selection ignored, `field_endpoint` stays empty. Fixed by waiting for `field_endpoint` non-empty before interacting.

3. **`hs tap #id` flakiness:** `hs tap "#spinner..."` intermittently returns `NOT_FOUND` (~50%) because hs's tappable-node index misses Spinners. Multiple workarounds tried (uiautomator, hs find); simplest reliable fix is `hs tap "#id" --retries 2 --timeout 3s` with more attempts (10), leveraging hs's built-in retry.

4. **Emulator `error: closed`:** `adb shell ime set` returns `error: closed` intermittently (emulator/adb connection drops). Likely infra flakiness (swiftshader headless). Not yet fixed; may need emulator options tuning.

5. **`sleep-i-am-sure` portability:** Custom command only exists in local dev shell (no-bare-sleep guard). CI lacks it. Fixed with `ssleep()` helper: `command sleep` → fallback `sleep-i-am-sure` → `|| true`.

## Artifacts

- `run_e2e_test.sh` — all E2E fixes
- `.github/workflows/ci.yml` — CI config with tag fetching + XML artifact
- `android/app/build.gradle.kts` — hardened versioning
- `thoughts/shared/handoffs/ci-e2e-troubleshooting.md` — this document

## Action Items & Next Steps

1. **Wait for run 29635932339** to complete (watcher running in tmux `ciwatch7`). If it passes → CI green, merge PR #8.

2. **If run fails:**
   - Check failure point from `gh run view --log-failed`
   - If `error: closed` (emulator death): tune emulator options in ci.yml (`-gpu swiftshader` instead of `swiftshader_indirect`, or add `-no-window -no-audio`)
   - If dropdown flaky (`hs act --tap "Deepgram"`): add `hs find` + coordinate tap for dropdown item
   - If transcription fails: verify Deepgram API key + audio injection

3. **Once CI green:** clean up `tap_rid_via_ui` helper if unused, merge PR #8 to main.

## Other Notes

- Run 29635932339 is **in progress** (watcher `ciwatch7` in tmux, logs to `/tmp/ci_watch7.log`). Current commit: `69405db`.
- Previous runs summary:
  - 29633132650: git 128 (build fail)
  - 29633308229: git 128 (build fail)
  - 29633308229 (retry): git 128 (build fail)
  - 29634052444: git 128 fixed, spinner NOT_FOUND
  - 29633308229: git 128 fixed, spinner NOT_FOUND
  - 29634287709: spinner OK, endpoint verified, then `error: closed` at apply settings
  - 29634741937: spinner OK, endpoint mismatch (race condition)
  - 29635264537: spinner NOT_FOUND (regression)
  - 29635591574: spinner NOT_FOUND (uiautomator + hs find failed)
  - 29635879522: spinner NOT_FOUND (hybrid approach failed)
  - 29635932339: **in progress** (simplified hs retry approach)
- `android-emulator-runner` manages emulator; script reuses pre-existing emulator (`EMULATOR_WAS_RUNNING=true`).
- `DEEPGRAM_KEY` secret exists in repo and is passed to emulator-runner `env:`.
- `e2e_diag.png` + `e2e_diag.xml` are uploaded as `test-reports` artifact on failure (when device reachable).