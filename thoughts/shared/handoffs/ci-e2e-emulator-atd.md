---
date: 2026-07-18T09:42:00+0200
git_commit: 7803921
branch: feat/api-key-links
repository: whisper-to-input
topic: "CI E2E: google_atd image change causes hs daemon start failure"
tags: [ci, e2e, android, emulator, handoff, troubleshooting]
---

# Handoff: CI E2E run 29639446424 fails at hs daemon start after google_atd image switch

## Task(s)

**Original goal:** Make the CI pipeline execute the real transcription E2E (`run_e2e_test.sh --backend deepgram`) and have it pass.

**Current state:**
- **Run 29639446424 (latest, with google_atd image + heap/disk + swiftshader GPU):** FAILED at `hs_daemon_start` with "hs daemon failed to start / connect to emulator-5554 (UI automation unavailable)". Build passed; emulator was running ("Emulator was pre-existing"), but `hs --device emulator-5554 use` failed to connect. The new robust `hs_daemon_start` verification correctly caught this and died with a clear message.
- **Run 29635932339 (previous, `target: default` / google_apis, old hs_daemon_start):** FAILED at spinner NOT_FOUND (10 retries). The old `hs_daemon_start` (`hs --device "$SERIAL" use || hs use || true`) silently succeeded, so `hs` *was* connecting in that run — it just couldn't find the spinner.
- **Run 29635879522 (hybrid approach, old commit):** FAILED at spinner NOT_FOUND (5 hybrid attempts).
- **All prior runs:** Eventually died with "error: closed" (emulator death) at various stages.

**Key change between 29635932339 (daemon OK, spinner failed) and 29639446424 (daemon failed):** Switched `target: default` → `target: google_atd`, added `EMULATOR_HEAP_SIZE: 2048M` / `EMULATOR_DISK_SIZE: 12GB`, kept `-gpu swiftshader`. The google_atd image appears to have broken `hs` connectivity (or the emulator didn't fully boot/authorize when `hs` tried to connect).

**Diagnostic progress:**
- `capture_diag` now prefers `adb exec-out screencap` (reliable) → `hs see` → `adb shell screencap`.
- In run 29639446424, `adb exec-out screencap` failed (emulator not reachable for screenshot?), then `hs see` failed with "Error opening file: e2e_diag.png (Read-only file system)". **But `uiautomator dump` succeeded** → `e2e_diag.xml` was captured and should be uploaded as artifact. This is the first run with a diagnostic XML artifact!

## Critical References

- `run_e2e_test.sh` — full E2E test script (recent edits: `hs_daemon_start` verification, `open_settings` readiness wait, `capture_diag` reliability).
- `.github/workflows/ci.yml` — CI workflow (current: `target: google_atd`, `EMULATOR_HEAP_SIZE: 2048M`, `EMULATOR_DISK_SIZE: 12GB`, `-gpu swiftshader`).
- `thoughts/shared/handoffs/ci-e2e-troubleshooting.md` — prior handoff (full history through run 29635932339).
- Run 29639446424 (failed): https://github.com/happytomatoe/android-cloud-speech-to-text/actions/runs/29639446424 — check the `e2e_diag.xml` artifact (uploaded by `if: always()` step).

## Recent changes

- `run_e2e_test.sh` (commit `9081b31`):
  - `hs_daemon_start`: added 5-retry verification via `timeout 15 $HS ui` → clear failure if daemon can't read screen (replaces silent `|| true`).
  - `open_settings`: added 30-attempt readiness wait for `#spinner_speech_to_text_backend` in `$HS ui`.
  - `capture_diag`: reordered to try `adb exec-out screencap` first (no daemon/UiAutomation dependency).
- `.github/workflows/ci.yml` (commit `7803921`):
  - `target: default` → `target: google_atd` (headless-optimised Android Test Device image).
  - Added `EMULATOR_HEAP_SIZE: 2048M`, `EMULATOR_DISK_SIZE: 12GB`.
  - Kept `-gpu swiftshader` (handoff-prescribed tweak for `error: closed`).

## Learnings

1. **`hs` daemon connectivity is flaky in CI** — it works locally with `hs --device emulator-5554 use` + verification, but in CI (hs v0.1.38) it fails *after* the google_atd switch. The old silent `|| true` hid this; the new verification surfaces it immediately.
2. **`google_atd` image may break hs connectivity** — the only structural change between the daemon-working run (29635932339) and the daemon-failing run (29639446424) is the system image. The ATD image is stripped-down; it may lack the accessibility/UiAutomation service `hs` needs, or boot differently so `hs` tries to connect before the device is authorized.
3. **Diagnostic pipeline now works** — `e2e_diag.xml` was captured in 29639446424 (first time!). This artifact should be inspected to see the UI state at failure.
4. **`error: closed` (emulator death) persists** — earlier runs died mid-test with emulator unreachable. The `google_atd` image + heap/disk was meant to fix this, but we hit the hs daemon wall first.
5. **`capture_diag` screenshot fails with "Read-only file system"** — `hs see` tries to write `e2e_diag.png` but the working dir may be read-only at that stage. `adb exec-out screencap` also failed (emulator unreachable). The XML dump succeeds.

## Artifacts

- `run_e2e_test.sh` (lines 478-520: `hs_daemon_start`, `open_settings`; lines 188-205: `capture_diag`).
- `.github/workflows/ci.yml` (lines 70-77: `google_atd`, `EMULATOR_HEAP_SIZE`, `EMULATOR_DISK_SIZE`).
- Run 29639446424 artifact: `e2e_diag.xml` (uploaded by `Upload test reports` step, `if: always()`).

## Action Items & Next Steps

1. **Inspect `e2e_diag.xml` from run 29639446424** — download the artifact from the CI run page. It should show the UI hierarchy at failure (likely the launcher or an empty window). This will tell if the emulator booted, if the settings activity is reachable, and whether the accessibility hierarchy is populated.

2. **Isolate the `google_atd` impact** — revert `target: google_atd` → `target: default` (or `google_apis`) and re-run. If `hs_daemon_start` passes again, the ATD image is the culprit. If it still fails, the issue is independent (hs daemon + CI interaction).

3. **Investigate `hs` daemon start failure in CI** — the failure is `hs --device emulator-5554 use` not connecting. Possibilities:
   - CI hs v0.1.38 needs `adb` in PATH (it is, but check `hs use` internals).
   - The emulator serial differs (check `SERIAL` env; runner uses `emulator-5554`).
   - Emulator not fully booted/authorized when `hs` connects (add `adb wait-for-device` + `adb shell getprop sys.boot_completed` wait before `hs use`).
   - ATD image lacks the accessibility/UiAutomation service `hs` relies on (check `adb shell settings get secure enabled_accessibility_services` in the ATD emulator).

4. **If ATD image breaks `hs`, consider alternatives**:
   - Stay on `target: default` (google_apis) + increase heap/disk only (may already fix `error: closed`).
   - Or use `aosp_atd` (AOSP-based ATD) instead of `google_atd` if Google APIs are not needed for E2E.
   - Or keep `google_atd` but add a longer boot-wait + adb authorization step before `hs use`.

5. **Fix the "Read-only file system" screenshot error** — change `capture_diag` to write screenshots to `/tmp/` or use `adb shell screencap -p /sdcard/e2e_diag.png && adb pull` instead of `adb exec-out`.

## Other Notes

- Local validation: `hs` works perfectly locally (commit 7803921 tested: `hs_daemon_start` → ok, `open_settings` → spinner found, `select_backend deepgram` → endpoint verified). The discrepancy is CI-specific (hs version, runner image, ATD image).
- The prior handoff (`ci-e2e-troubleshooting.md`) documents runs 29633132650 → 29635932339 in detail.
- PR #8 (feat/api-key-links → main) is open; both commits (9081b31, 7803921) are on the branch.
- Current run list shows `Release` and `devcontainer` workflows failing too (unrelated).
- Local emulator (`Pixel_8`, `-gpu host`) is running and usable for rapid iteration (`just emulator-status` shows it up).