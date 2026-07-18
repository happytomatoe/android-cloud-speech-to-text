---
date: 2026-07-18T06:30:00+0200
git_commit: 5c4f37a210187b0a22629fc1b9dfa8e20d9ed33e
branch: feat/api-key-links
repository: whisper-to-input
topic: "CI setup - pre-commit hooks + E2E CI workflow"
tags: [ci, pre-commit, hooks, e2e, testing, android]
---

# Handoff: CI — pre-commit hooks + E2E CI workflow

## Task(s)

1. **Fix `hs` selector syntax in `run_e2e_test.sh`** — COMPLETED
   - `hs_tap_rid` → `#short_id` (not full package prefix)
   - `hs_fill_rid` → `id=FULL_RESOURCE_ID`
   - `hs_tap_text` → plain text string
   - `hs find` → `Tag[id=FULL_RESOURCE_ID]`
   - Helper functions now check JSON `"ok":true` instead of masking with `|| true`
   - E2E test passes locally (15s total, transcription matches "hello world")

2. **Add timestamps to E2E log statements** — COMPLETED
   - All `log_*` functions in `run_e2e_test.sh` now include `[$(date +%H:%M:%S)]`

3. **Fix 10-15s end delay in E2E** — COMPLETED
   - `monitor_status_label` background process now killed early after transcription completes (`run_e2e_test.sh:706`)

4. **Replace lefthook with pre-commit framework** — COMPLETED
   - Removed lefthook-generated hooks from `.git/hooks/`
   - Added `.pre-commit-config.yaml` with `default_install_hook_types: [pre-commit, commit-msg]`
   - Added `scripts/check-no-coauthored.sh` (rejects Co-Authored-By trailers)
   - Added `just setup-hooks` target to install/reinstall hooks

5. **Add CI workflow with E2E tests** — COMPLETED
   - `.github/workflows/ci.yml` runs `just test-all` on PR
   - Uses `reactivecircus/android-emulator-runner@v2` for emulator
   - Installs `hs` from GitHub releases
   - Needs `DEEPGRAM_KEY` GitHub secret

6. **Commit hygiene** — COMPLETED
   - Squashed all branch commits into single clean commit (`5c4f37a`)
   - No Co-Authored-By trailers (hook enforces this)

## Critical References

- `.pre-commit-config.yaml` — pre-commit config with commit-msg + push stages
- `.github/workflows/ci.yml` — CI workflow running `just test-all`
- `justfile:212-223` — `setup-hooks` target (deletes old hooks, installs pre-commit)
- `run_e2e_test.sh:380-398` — fixed `hs_tap_rid` / `hs_fill_rid` selectors

## Recent changes

- `run_e2e_test.sh:380` — `hs_tap_rid` now uses `#$1` (short id)
- `run_e2e_test.sh:397` — `hs_fill_rid` uses `id=com.example.whispertoinput:id/$field_id`
- `run_e2e_test.sh:388` — `hs_tap_text` uses plain text `$1`
- `run_e2e_test.sh:446-447` — `hs find` uses `EditText[id=com.example.whispertoinput:id/field_endpoint]`
- `run_e2e_test.sh:104-107` — log functions include timestamps
- `run_e2e_test.sh:706` — kill `monitor_status_label` early
- `.pre-commit-config.yaml` — NEW: pre-commit config (check-yaml, shellcheck, no-co-authored-by, test-all)
- `scripts/check-no-coauthored.sh` — NEW: rejects commits with `Co-Authored-By:` trailer
- `justfile:212-223` — NEW: `setup-hooks` target
- `.github/workflows/ci.yml` — REWRITTEN: single `test-all` job using `just test-all`
- `AGENTS.md:34-60` — NEW section 4: `hs` CLI usage + selector syntax
- `~/.config/fish/config.fish` — ADDED: `fish_add_path ~/Android/Sdk/platform-tools` (personal, not committed)

## Learnings

1. **`hs` selector syntax differs per verb:**
   - `hs tap` accepts `#short_id` (no package prefix) or plain text
   - `hs fill` requires `id=FULL_RESOURCE_ID` (with package prefix)
   - `hs find` requires `Tag[id=FULL_RESOURCE_ID]`
   - `hs wait` accepts plain text
   - Don't use `|| true` to mask errors — parse `--json` output for `"ok":true`

2. **`hs use` needs `adb` in PATH** — daemon startup fails silently with "No such file or directory" if `adb` isn't found. Fixed by adding `fish_add_path ~/Android/Sdk/platform-tools` to fish config.

3. **pre-commit `commit-msg` hooks:**
   - Need `default_install_hook_types: [pre-commit, commit-msg]` in config
   - `pass_filenames: false` + `stages: [commit-msg]` = BROKEN (pre-commit won't pass the filename — see issue #2202)
   - Use `language: script` + `entry: scripts/check-something.sh` (must be executable)
   - Script receives commit message file path as `$1`

4. **E2E test uses test-file mode** — pushes WAV to app cache via `adb push` + `run-as cp`, app reads from cache. No virtual mic needed for CI. `setup_virtual_mic()` is defined but never called.

5. **pre-commit `push` stage is deprecated** — shows warning "hook id `test-all` uses deprecated stage names (push)". Should migrate to `pre-push` eventually but works for now.

6. **CI emulator needs KVM** — `android-emulator-runner` requires `Enable KVM` step on Ubuntu runners.

7. **`hs` binary on CI** — download from `gh release download v0.1.38 -R elliotgao2/handsets --pattern handsets-linux-x86_64.tar.gz`, extract to `~/.local/bin`. Also need `$ANDROID_HOME/platform-tools` in PATH for `hs` daemon.

## Artifacts

- `.pre-commit-config.yaml` — pre-commit configuration
- `scripts/check-no-coauthored.sh` — Co-Authored-By rejection hook
- `.github/workflows/ci.yml` — CI workflow
- `justfile` — `setup-hooks` + `test-all` targets
- `run_e2e_test.sh` — E2E test script with fixed `hs` selectors
- `AGENTS.md` — section 4 documents `hs` usage
- `thoughts/shared/handoffs/e2e-hs-integration.md` — previous handoff (now superseded)

## Action Items & Next Steps

1. **Add `DEEPGRAM_KEY` GitHub secret** — required for CI E2E tests to run. Settings → Secrets and variables → Actions → New repository secret.
2. **Test CI workflow on a PR** — open PR against `main`, verify E2E job passes.
3. **Optionally delete `scripts/ui_tap.py`** — unused since `hs` integration (still in repo as fallback).
4. **Migrate pre-commit `push` → `pre-push`** — silence deprecation warning (optional).
5. **Consider adding other backend keys** (`GROQ_KEY`, `SIXTYDB_KEY`) if CI should test those backends too.

## Other Notes

- **config.fish change is personal** — NOT committed (lives at `~/.config/fish/config.fish`, not in repo). Don't try to commit it.
- **E2E test currently only tests Deepgram** — `--backend deepgram --expected "hello world"`. Other backends untested in CI.
- **Unit tests run via Robolectric** (JVM, no emulator) — cached in CI via `~/.m2/repository/org/robolectric`.
- **The `test-all` job runs both unit + E2E in parallel** via `just test-all` (which uses `just test &` + `just test-e2e debug &` + `wait`).
- **Pre-existing handoff `e2e-hs-integration.md` is now superseded** by this one — can be archived/deleted.
- **Git history is clean** — single commit `5c4f37a` on `feat/api-key-links`, no Co-Authored-By trailers.
