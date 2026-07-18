# Whisper To Input - Development Commands

# Android SDK location — honors $ANDROID_PATH (set in ~/.config/fish/config.fish),
# defaults to the developer-machine path when the var is unset (Codespaces overrides it).
android_sdk := env_var_or_default("ANDROID_PATH", "/var/home/l/Android/Sdk")
adb := android_sdk + "/platform-tools/adb"
emulator_bin := android_sdk + "/emulator/emulator"
avd := "Pixel_8"

# ── Build ──────────────────────────────────────────────────────────

# Build the APK (debug or release)
#   just build              # release (default)
#   just build debug        # debug variant
build variant="release":
    #!/usr/bin/env bash
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    sdk env >/dev/null
    VARIANT="{{variant}}"
    cd android && ./gradlew assemble${VARIANT^}
    echo "✅ Build successful (${VARIANT})"

# ── Emulator Lifecycle ─────────────────────────────────────────────

pid_file := ".emulator.pid"
emu_log := "/tmp/emulator.log"
mode_file := "/tmp/emulator.mode"

# Start the emulator. Base command: emulator -avd Pixel_8
#   just emulator-start              # headless  -> emulator -avd Pixel_8 -no-window
#   just emulator-start headful=true # headful  -> emulator -avd Pixel_8 (visible window)
#   just emulator-start-headful      # alias for the above
# The emulator PID is saved to {{pid_file}} so `just emulator-stop` kills exactly that process.
emulator-start headful="false":
    #!/usr/bin/env bash
    set -e
    if {{adb}} get-state >/dev/null 2>&1; then
        echo "✅ Emulator already running"
        exit 0
    fi
    # Accept both `just start true` (positional) and `just start headful=true` (named).
    # just passes `headful=true` as the literal value "headful=true", so strip the prefix.
    HF="{{headful}}"
    HF="${HF#headful=}"
    if [ "$HF" = "true" ]; then
        FLAGS="-gpu host -no-snapshot-save"
        MODE="headful"
    else
        FLAGS="-no-window -gpu host -no-snapshot-save"
        MODE="headless"
    fi
    echo "Starting emulator ($MODE): emulator -avd {{avd}} $FLAGS"
    # Save mode to file for E2E script to detect
    echo "$MODE" > {{mode_file}}
    # setsid detaches it from this shell so it survives after `just` returns
    setsid {{emulator_bin}} -avd {{avd}} $FLAGS > {{emu_log}} 2>&1 &
    echo $! > {{pid_file}}
    {{adb}} wait-for-device
    echo "Waiting for boot (this may take a minute)..."
    for i in $(seq 1 60); do
        if [ "$({{adb}} shell getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
            echo "✅ Emulator booted (PID $(cat {{pid_file}}) saved to {{pid_file}})"
            # Disable animations for faster UI automation
            {{adb}} shell settings put global window_animation_scale 0
            {{adb}} shell settings put global transition_animation_scale 0
            {{adb}} shell settings put global animator_duration_scale 0
            exit 0
        fi
        sleep-i-am-sure 2
    done
    echo "⚠️  Boot timed out — emulator may still be starting"
    exit 1

# Headful mode: just emulator-start headful=true

# Stop the emulator using the saved PID (falls back to pkill if no PID file)
emulator-stop:
    #!/usr/bin/env bash
    set -e
    # Graceful shutdown via adb (no snapshot save — we use -no-snapshot-save)
    if {{adb}} get-state >/dev/null 2>&1; then
        echo "Sending graceful shutdown via adb emu kill..."
        {{adb}} emu kill 2>/dev/null || true
        sleep-i-am-sure 5
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
    rm -f {{mode_file}}
    echo "✅ Emulator stopped"

# Cold boot and save snapshot (for quick boot later)
# Use this to create/refresh the snapshot that `emulator-start` loads.
emulator-save-snapshot:
    #!/usr/bin/env bash
    set -e
    if {{adb}} get-state >/dev/null 2>&1; then
        echo "Emulator already running — stop it first with: just emulator-stop"
        exit 1
    fi
    echo "Cold booting emulator to save snapshot..."
    setsid {{emulator_bin}} -avd {{avd}} -gpu host -no-snapshot-load > {{emu_log}} 2>&1 &
    echo $! > {{pid_file}}
    {{adb}} wait-for-device
    echo "Waiting for boot..."
    for i in $(seq 1 60); do
        if [ "$({{adb}} shell getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
            echo "✅ Emulator booted — now saving snapshot..."
            {{adb}} emu kill 2>/dev/null || true
            sleep-i-am-sure 5
            echo "✅ Snapshot saved (PID file cleaned up)"
            rm -f {{pid_file}}
            exit 0
        fi
        sleep-i-am-sure 2
    done
    echo "⚠️  Boot timed out"
    exit 1

# Restart emulator (stop + start)
emulator-restart: emulator-stop emulator-start

# Check emulator status
emulator-status:
    @{{adb}} devices 2>/dev/null
    @echo "---"
    @{{adb}} shell getprop sys.boot_completed 2>/dev/null | grep -q 1 && echo "Boot: complete" || echo "Boot: not ready"
    @[ -f {{pid_file}} ] && echo "PID file: $(cat {{pid_file}})" || echo "PID file: (none)"

# ── E2E Test ───────────────────────────────────────────────────────

# Full E2E test: build, start emulator, install APK, verify voice input
# APK name: debug -> app-debug.apk, release -> app-release.apk
test-e2e variant="release": (build variant)
    @echo "=== E2E Test ({{variant}}) ==="
    echo "1. Build ✓"
    echo "2. Emulator running ✓"
    {{adb}} install -r android/app/build/outputs/apk/{{variant}}/app-{{variant}}.apk
    echo "3. APK installed ✓"
    {{adb}} shell ime enable com.example.whispertoinput/.WhisperInputService
    echo "4. IME enabled ✓"
    echo "5. Verifying app is registered as voice input..."
    {{adb}} shell ime list -s | grep whispertoinput && echo "   App found in IME list ✓" || echo "   ⚠️  App not in IME list"
    @echo "=== Done ==="

# ── Unit Tests (Robolectric, JVM — no emulator) ───────────────────
# Runs Tiers 1-3 JVM tests: keyboard state machine, backspace, transcriber, services.
# `just test` runs them with cross-module parallelism enabled (see gradle.properties:
# org.gradle.parallel=true).
# Full transcription E2E: build, install, drive the app via hs, and verify
# the STT result (test-file mode) against an expectation. Requires a running
# emulator and DEEPGRAM_KEY in the environment.
test-e2e-transcribe:
    #!/usr/bin/env bash
    set -e
    # sdkman exists only on developer machines; CI uses actions/setup-java.
    [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ] && source "$HOME/.sdkman/bin/sdkman-init.sh"
    command -v sdk >/dev/null 2>&1 && sdk env >/dev/null
    ./run_e2e_test.sh --backend deepgram --key "${DEEPGRAM_KEY:?DEEPGRAM_KEY must be set}" --expected "hello world"

test:
    #!/usr/bin/env bash
    set -e
    [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ] && source "$HOME/.sdkman/bin/sdkman-init.sh"
    command -v sdk >/dev/null 2>&1 && sdk env >/dev/null
    cd android && ./gradlew testDebugUnitTest --parallel

# ── Instrumented Tests (Espresso, on a running emulator) ──────────
# Only needed if a Robolectric shadow proves insufficient for a service under test.
test-instrumented:
    #!/usr/bin/env bash
    set -e
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    sdk env >/dev/null
    cd android && ./gradlew connectedDebugAndroidTest

# ── Repo Setup ───────────────────────────────────────────────────
# Setup development tools
#   just setup              # install latest stable handsets (>14 days old)
#   just setup v0.1.36     # install specific version
setup version="":
    #!/usr/bin/env bash
    set -e

    REPO="elliotgao2/handsets"
    BIN_DIR="$HOME/.local/bin"
    ARCHIVE="handsets-linux-x86_64.tar.gz"

    # Resolve version: pinned or latest stable (>14 days old)
    if [ -n "{{version}}" ]; then
        VERSION="{{version}}"
    else
        echo "Querying GitHub releases for latest stable (>=14 days old)..."
        VERSION=$(gh release list -R "$REPO" --json tagName,publishedAt --limit 5 -q '
            [.[] | select((now - (.publishedAt | fromdateiso8601)) >= 1209600)]
            | .[0].tagName
        ')
        [ -n "$VERSION" ] || { echo "ERROR: could not find a stable release"; exit 1; }
    fi

    echo "Installing handsets $VERSION"
    gh release download "$VERSION" -R "$REPO" --pattern "$ARCHIVE" -D /tmp/hs-dl --skip-existing

    echo "Extracting to $BIN_DIR"
    mkdir -p "$BIN_DIR"
    tar xzf "/tmp/hs-dl/$ARCHIVE" -C "$BIN_DIR" --strip-components=1

    echo "✅ Handsets $VERSION installed"

# ── Git Hooks Setup ───────────────────────────────────────────
# Install pre-commit hooks (replaces lefthook-generated hooks)
#   just setup-hooks
#
# Note: This removes any lefthook-generated hooks and installs
# pre-commit (https://pre-commit.com) with the project's
# .pre-commit-config.yaml. Also adds a custom commit-msg hook
# that rejects 'Co-Authored-By' lines.
setup-hooks:
    #!/usr/bin/env bash
    set -e
    echo "Removing existing hooks..."
    # Delete all hooks except .sample files
    find .git/hooks -type f ! -name '*.sample' -delete
    echo "Installing pre-commit hooks..."
    pre-commit install
    pre-commit install --hook-type commit-msg
    echo "✅ Git hooks installed (commit-msg checks for Co-Authored-By via pre-commit)"

# ── Run all tests in parallel ─────────────────────────────────────
# Phase 1: Build main APK + compile test classes (single Gradle invocation)
# Phase 2: Run unit tests and E2E in parallel (no more builds)
test-all:
    #!/usr/bin/env bash
    set -e
    # sdkman exists only on developer machines; CI uses actions/setup-java.
    [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ] && source "$HOME/.sdkman/bin/sdkman-init.sh"
    command -v sdk >/dev/null 2>&1 && sdk env >/dev/null
    echo "Phase 1: Compiling unit-test classes..."
    cd android && ./gradlew compileDebugUnitTestKotlin && cd ..
    echo "Phase 2: Running unit + E2E tests in parallel..."
    just test & TEST_PID=$!
    just test-e2e-transcribe & E2E_PID=$!
    wait "$TEST_PID"; local test_rc=$?
    wait "$E2E_PID"; local e2e_rc=$?
    if [[ "$test_rc" -ne 0 || "$e2e_rc" -ne 0 ]]; then
        echo "❌ Tests failed (unit rc=$test_rc, e2e rc=$e2e_rc)"
        exit 1
    fi
    echo "✅ All tests passed"