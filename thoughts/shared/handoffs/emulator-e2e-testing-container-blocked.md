---
date: 2026-07-14T09:00:00+02:00
researcher: pi-agent
git_commit: 220e1dc
branch: master
repository: whisper-to-input
topic: "Emulator E2E Testing: Container Environment Blocker"
tags: [android, emulator, e2e-testing, container, podman, blocker]
status: in_progress
last_updated: 2026-07-14
last_updated_by: pi-agent
type: research_findings
---

# Handoff: Emulator E2E Testing Blocked by Container Environment

## Task(s)

| Task | Status | Notes |
|------|--------|-------|
| Implementation (Phases 1-4) | ✅ Complete | All code committed, build+lint pass |
| Curl API test | ✅ Complete | Returns "Hello, what's going on?" |
| Build with Java 17 | ✅ Complete | SDKMAN installed Java 17.0.13-tem |
| Justfile creation | ✅ Complete | Lifecycle commands: start, stop, restart, status, build, test-e2e |
| Emulator E2E test | 🚫 **BLOCKED** | Cannot run emulator inside Podman container |

## Critical Issue: Container Environment Prevents Emulator

**Root Cause:** We are running inside a Podman container (`/.containerenv` exists). The Android emulator requires KVM hardware acceleration, but:

1. **KVM device exists** (`/dev/kvm` with `crw-rw-rw-` permissions) but **ioctl calls fail** with "Invalid argument"
2. **Redroid (containerized Android)** fails with `setns: IO error` — nested containers not permitted
3. **Software rendering (SwiftShader)** is too slow — emulator crashes during boot after ~40 seconds

**Evidence:**
```
# KVM ioctl fails:
python3 -c "import fcntl, os; fd = os.open('/dev/kvm', os.O_RDWR); fcntl.ioctl(fd, 0xae08)"
# OSError: [Errno 22] Invalid argument

# Redroid fails:
sudo podman run ... redroid/redroid:14.0.0_64only-latest
# Error: netavark: setns: IO error: Operation not permitted (os error 1)

# Emulator crashes with SwiftShader:
# "x86_64 emulation may not work without hardware acceleration!"
# Dies at ~40 seconds during boot
```

## Build Fix: Java 17

The project had a Java 25 incompatibility. Fixed by:
1. Installed SDKMAN: `curl -s "https://get.sdkman.io" | bash`
2. Installed Java 17: `sdk install java 17.0.13-tem`
3. Build command uses: `export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"`

## Justfile Commands

Created `justfile` with lifecycle commands:

```bash
just build          # Build debug APK (uses Java 17)
just start          # Start emulator headless (currently blocked)
just stop           # Kill emulator
just restart        # Stop + start
just status         # Check emulator state
just test-e2e       # Full E2E: build → start → install → verify
```

**Note:** `just start` and `just test-e2e` will fail until emulator can run outside container.

## Voice Input Method Plan

The app has been refactored from a keyboard IME to a **voice-only input method**:
- `method.xml` updated: voice subtype only, no keyboard subtype
- `WhisperInputService` simplified: no keyboard UI, auto-starts recording
- Plan document: `thoughts/shared/plans/voice-input-method.md`
- **Status:** Implementation complete, build passes, but E2E testing blocked

## Learnings

1. **Podman containers cannot run Android emulator** — KVM device may be visible but ioctl calls fail
2. **SwiftShader is too slow** for emulator boot — crashes after ~40 seconds
3. **Redroid requires `--privileged`** and proper KVM passthrough from host
4. **Java 25 incompatible** with Gradle 8.0 / AGP 8.1.2 — need Java 17
5. **SDKMAN** is the easiest way to manage Java versions on Fedora
6. **Xvfb** is needed for headless display (`dnf install xorg-x11-server-Xvfb`)
7. **Emulator flags that don't help:** `-no-window`, `-gpu off`, `-display none` — all still crash
8. **Emulator flags that help (on host):** `-gpu swiftshader_indirect -no-accel` — but too slow

## Artifacts

- `justfile` — Emulator lifecycle and E2E test commands
- `thoughts/shared/plans/voice-input-method.md` — Voice input implementation plan
- `thoughts/shared/handoffs/emulator-e2e-testing-discoveries.md` — Previous handoff with research
- `~/.sdkman/candidates/java/17.0.13-tem` — Java 17 installation

## Action Items & Next Steps

1. **Run emulator on host machine** (not inside container) — this is the primary blocker
   - Either SSH to host and run emulator there
   - Or restructure to use a non-containerized environment
2. **Update justfile** to work with host-based emulator (may need ADB connect over TCP)
3. **Complete E2E test** once emulator is accessible:
   - Install APK
   - Enable Whisper input method
   - Verify it appears in IME list
   - Test voice input with Gboard integration
4. **Document E2E test results** in showboat/proof document

## Other Notes

- **Emulator AVD:** Pixel_8 (API 34, x86_64)
- **ADB path:** `/var/home/l/Android/Sdk/platform-tools/adb`
- **Emulator path:** `/var/home/l/Android/Sdk/emulator/emulator`
- **API key:** `sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887`
- **Test audio:** `test-sources/test-audio.wav` (5s, "Hello? What's going on?")
- **App package:** `com.example.whispertoinput`
- **IME service:** `com.example.whispertoinput/.WhisperInputService`
- **Xvfb running on:** `:99` (needed for emulator display)
- **Podman version:** 5.8.4
- **Fedora version:** 44 (Toolbx container)
