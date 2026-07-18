# Enable Android Emulator Quick Boot (Fastboot)

## Overview

Enable Quick Boot snapshot loading for faster E2E test startup. Currently the emulator cold-boots (~60s) every time because snapshots fail to load due to GPU mode mismatch and force-kill prevents snapshot saving.

## Current State Analysis

### What's already correct:
- AVD config: `fastboot.forceFastBoot=yes`, `forceColdBoot=no` ✓
- Using correct emulator binary: `~/Android/Sdk/emulator/emulator` ✓
- Snapshot exists at `~/.android/avd/Pixel_8.avd/snapshots/default_boot/` ✓

### Root causes of failure:

1. **GPU mode mismatch** (primary issue):
   - Snapshot saved with `hw.gpu.mode=host`
   - Emulator loads with `hw.gpu.mode=auto` (default)
   - Error: `"Change of GLES renderer detected"` → snapshot load fails
   - **Fix**: Always use `-gpu host` flag explicitly

2. **Force-kill prevents snapshot saving**:
   - `justfile:71,75`: `pkill -f "qemu-system-x86_64"` — SIGKILL, no graceful shutdown
   - `run_e2e_test.sh:221-222`: `pkill -9` — SIGKILL, no time to write snapshot
   - **Fix**: Use `adb emu kill` for graceful shutdown (saves snapshot)

3. **Redundant pre-launch kill**:
   - `run_e2e_test.sh:183-184`: `pkill -9` before starting — unnecessary, `start_emulator()` already checks if running
   - **Fix**: Remove redundant kill

4. **btrfs filesystem** (minor issue):
   - Host uses btrfs, not ext4
   - Disables `QuickbootFileBacked` feature (warning in log)
   - Snapshots still work, just not file-backed — acceptable

## Desired End State

1. First run: cold boot (~40s), snapshot saved on graceful exit
2. Subsequent runs: Quick Boot resume (~10s) using saved snapshot
3. If snapshot corrupted: cold boots automatically, re-saves on next graceful exit
4. E2E test total time reduced by ~30s per run

## Test Results (Verified)

Created fresh `Pixel_8_Test` AVD to validate the approach:

| Phase | Time | Notes |
|-------|------|-------|
| Cold boot | 41s | First boot, no snapshot |
| Snapshot save | 1.3GB | `ram.bin` saved on graceful `adb emu kill` |
| Quick boot | **9s** | Loaded snapshot in 3.9s |

**78% faster!** The original Pixel_8's snapshot was corrupted from previous force-kills — deleting it and letting it save fresh will fix the issue.

### Verification:
- Boot time < 20s on subsequent runs (check timestamp in logs)
- `ram.bin` in snapshot dir > 0 bytes after graceful exit
- No "Change of GLES renderer" or "Failed to load snapshot" errors in emulator log

## What We're NOT Doing

- Changing `-no-snapshot-save` in justfile headless mode (intentional for dev)
- Changing AVD config (already correct)
- Changing host filesystem to ext4 (not practical on Fedora btrfs)
- Adding `-no-snapshot-save` to E2E test (we WANT snapshots saved for speed)

## Implementation Approach

Fix two things: (1) consistent GPU mode via `-gpu host` flag, (2) graceful shutdown via `adb emu kill`.

---

## Phase 1: Fix Graceful Shutdown (Snapshot Saving)

### Overview
Replace force-kill with graceful shutdown so snapshots are actually saved to disk.

### Changes Required:

#### 1. justfile — `emulator-stop` recipe
**File**: `justfile`
**Changes**: Replace `pkill -f "qemu-system-x86_64"` with `adb emu kill`

```bash
# Current (lines 64-78):
emulator-stop:
    #!/usr/bin/env bash
    set -e
    if [ -f {{pid_file}} ]; then
        PID=$(cat {{pid_file}})
        echo "Stopping emulator (PID $PID) from {{pid_file}}..."
        kill "$PID" 2>/dev/null || true
        pkill -f "qemu-system-x86_64" 2>/dev/null || true
        rm -f {{pid_file}}
    else
        echo "No PID file — killing any running emulator..."
        pkill -f "qemu-system-x86_64" 2>/dev/null || true
    fi
    sleep 1
    echo "✅ Emulator stopped"

# New:
emulator-stop:
    #!/usr/bin/env bash
    set -e
    # Graceful shutdown via adb (saves snapshot)
    if {{adb}} get-state >/dev/null 2>&1; then
        echo "Sending graceful shutdown via adb emu kill..."
        {{adb}} emu kill 2>/dev/null || true
        sleep-i-am-sure 3
    fi
    # Clean up PID file
    if [ -f {{pid_file}} ]; then
        PID=$(cat {{pid_file}})
        # Verify process is gone, force kill if still running
        if kill -0 "$PID" 2>/dev/null; then
            echo "Process still alive, force killing..."
            kill "$PID" 2>/dev/null || true
        fi
        rm -f {{pid_file}}
    fi
    echo "✅ Emulator stopped"
```

#### 2. run_e2e_test.sh — `stop_emulator()` function
**File**: `run_e2e_test.sh`
**Changes**: Replace `pkill -9` with `adb emu kill`

```bash
# Current (lines 216-225):
stop_emulator() {
    log_info "Stopping emulator..."
    if [[ -f /tmp/emulator.pid ]]; then
        kill "$(cat /tmp/emulator.pid)" 2>/dev/null || true
    fi
    run_cmd "pkill -9 -f 'qemu-system'" false
    run_cmd "pkill -9 -f 'emulator -avd $AVD'" false
    rm -f /tmp/emulator.pid /tmp/emulator.log
    log_ok "Emulator stopped"
}

# New:
stop_emulator() {
    log_info "Stopping emulator (graceful shutdown to save snapshot)..."
    # Graceful shutdown via adb (saves snapshot)
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
        "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
        sleep-i-am-sure 3
    fi
    # Clean up PID file
    if [[ -f /tmp/emulator.pid ]]; then
        local pid
        pid=$(cat /tmp/emulator.pid)
        if kill -0 "$pid" 2>/dev/null; then
            log_warn "Process still alive after adb emu kill, force killing..."
            kill "$pid" 2>/dev/null || true
        fi
        rm -f /tmp/emulator.pid
    fi
    rm -f /tmp/emulator.log
    log_ok "Emulator stopped"
}
```

#### 3. run_e2e_test.sh — Remove redundant pre-launch kill
**File**: `run_e2e_test.sh`
**Lines**: 183-185
**Changes**: Remove `pkill -9` calls (function already checks if running at lines 173-178)

```bash
# Current (lines 182-185):
    # Kill any stale emulator processes
    run_cmd "pkill -9 -f 'qemu-system'" false
    run_cmd "pkill -9 -f 'emulator -avd $AVD'" false
    sleep 2

# Remove these 3 lines entirely — the check at lines 173-178 already handles reuse
```

### Success Criteria:

#### Automated Verification:
- [ ] `just emulator-stop` completes without errors
- [ ] `ram.bin` in `~/.android/avd/Pixel_8.avd/snapshots/default_boot/` is > 0 bytes after stop
- [ ] No "Failed to save snapshot" in emulator log

#### Manual Verification:
- [ ] Emulator shuts down gracefully (window closes, process exits cleanly)
- [ ] Snapshot directory updated after shutdown

---

## Phase 2: Fix GPU Mode Consistency (Snapshot Loading)

### Overview
Always use `-gpu host` flag so snapshot save/load uses the same GPU mode.

### Changes Required:

#### 1. justfile — Add `-gpu host` to both headful and headless modes
**File**: `justfile`

```bash
# Current headful (line 39):
    if [ "$HF" = "true" ]; then
        FLAGS=""
        MODE="headful"

# New headful:
    if [ "$HF" = "true" ]; then
        FLAGS="-gpu host"
        MODE="headful"

# Current headless (line 42):
    else
        FLAGS="-no-window -no-snapshot-save"
        MODE="headless"

# New headless:
    else
        FLAGS="-no-window -gpu host -no-snapshot-save"
        MODE="headless"
```

#### 2. run_e2e_test.sh — Add `-gpu host` to emulator launch
**File**: `run_e2e_test.sh`
**Line**: 193

```bash
# Current:
    setsid "$EMULATOR" -avd "$AVD" > /tmp/emulator.log 2>&1 &

# New:
    setsid "$EMULATOR" -avd "$AVD" -gpu host > /tmp/emulator.log 2>&1 &
```

### Success Criteria:

#### Automated Verification:
- [ ] Emulator log shows no "Change of GLES renderer detected" warning
- [ ] Emulator log shows "Loading snapshot 'default_boot'..." followed by success (no "Failed to load")
- [ ] Boot time < 20s on subsequent runs

#### Manual Verification:
- [ ] Second boot is noticeably faster than first boot
- [ ] No GPU-related errors in emulator window or log

---

## Phase 3: Delete Stale Snapshot & Verify End-to-End

### Overview
Clear the old snapshot and verify the full cycle works.

### Steps:

1. Stop any running emulator
2. Delete stale snapshot: `rm -rf ~/.android/avd/Pixel_8.avd/snapshots/default_boot/`
3. Start emulator: `just emulator-start` → cold boot (~40s)
4. Stop emulator: `just emulator-stop` → saves snapshot
5. Verify `ram.bin` > 0 bytes
6. Start emulator again: `just emulator-start` → should quick boot (~10s)
7. Check emulator log for successful snapshot load
8. Run E2E test to verify end-to-end flow

### Success Criteria:

#### Automated Verification:
- [ ] First boot: ~40s (cold boot)
- [ ] `ram.bin` > 0 bytes after graceful stop
- [ ] Second boot: ~10s (quick boot)
- [ ] E2E test passes

#### Manual Verification:
- [ ] Second boot feels significantly faster
- [ ] No errors in emulator log
- [ ] App functions correctly after quick boot

---

## Performance Considerations

- **btrfs + no QuickbootFileBacked**: Snapshot loading is slightly slower than ext4 + file-backed, but still much faster than cold boot
- **GPU mode `host`**: Uses host GPU acceleration — required for snapshot compatibility, also better performance
- **Snapshot size**: ~2GB (compressed in ram.bin) — normal for a running Android VM

## References

- Emulator help: `-gpu host`, `-snapshot`, `-no-snapshot-save`, `-no-snapshot-load`
- AVD config: `~/.android/avd/Pixel_8.avd/config.ini`
- Snapshot dir: `~/.android/avd/Pixel_8.avd/snapshots/default_boot/`
- Emulator log: `/tmp/emulator.log`
