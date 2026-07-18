#!/usr/bin/env bash
# =============================================================================
# Whisper To Input - Full E2E Test Script
# =============================================================================
# This script runs a complete end-to-end transcription test from scratch:
# 1. Sets up virtual microphone (VirtualMicSink + FakeMic)
# 2. Starts headful emulator with FakeMic pinned
# 3. Builds and installs the APK
# 4. Grants permissions, enables IME
# 5. Configures a transcription backend via Settings UI
# 6. Triggers recording by tapping the mic button (tap-to-toggle)
# 7. Plays test audio into the virtual mic while recording
# 8. Taps mic button again to stop and transcribe
# 8. Verifies transcription appears in the search bar
#
# Usage:
#   ./run_e2e_test.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"
#   ./run_e2e_test.sh --backend groq --key $GROQ_KEY --expected "hello world"
#   ./run_e2e_test.sh --backend 60db --key $SIXTYDB_KEY --expected "hello world"
#
# Options:
#   --headful   Run emulator with visible window (default: headless)
#
# Prerequisites:
# - Android SDK with emulator (Pixel_8 AVD)
# - pactl / PipeWire or PulseAudio
# - paplay, espeak-ng, ffmpeg (for test audio generation)
# - ADB in PATH
# - Deepgram/Groq/60db API key with valid quota
#
# =============================================================================

# Write output to a log file and mirror it to the console.
# =============================================================================
# Configuration (all overridable via environment variables)
# =============================================================================

# Use adb/emulator from PATH when present (CI installs the SDK and
# puts platform-tools on PATH); fall back to the local SDK path.
ADB="$(command -v adb || echo /var/home/l/Android/Sdk/platform-tools/adb)"
EMULATOR="$(command -v emulator || echo /var/home/l/Android/Sdk/emulator/emulator)"
AVD="${AVD:-Pixel_8}"
PACKAGE="com.example.whispertoinput"
SERVICE="com.example.whispertoinput/.WhisperInputService"
WAV_FILE="/tmp/test-speech-loud.wav"
VIRTUAL_SINK="${VIRTUAL_SINK:-VirtualMicSink}"
FAKE_MIC="${FAKE_MIC:-FakeMic}"
SERIAL="${SERIAL:-emulator-5554}"
PID_FILE="${PID_FILE:-/tmp/emulator.pid}"
EMU_LOG="${EMU_LOG:-/tmp/emulator.log}"
LOG_FILE="${LOG_FILE:-e2e_test.log}"
EMULATOR_WAS_RUNNING=false
MODE_FILE="/tmp/emulator.mode"

exec > >(tee "$LOG_FILE") 2>&1

# Default API keys (sourced from keyring via secret-tool)
DEEPGRAM_KEY_DEFAULT=$(secret-tool lookup service voice-to-text username deepgram 2>/dev/null || echo "")

# Backend configuration
declare -A BACKEND_ENDPOINT=(
    ["deepgram"]="https://api.deepgram.com/v1/listen"
    ["groq"]="https://api.groq.com/openai/v1/audio/transcriptions"
    ["60db"]="https://api.60db.ai/stt"
    ["elevenlabs"]="https://api.elevenlabs.io/v1/speech-to-text"
)

declare -A BACKEND_MODEL=(
    ["deepgram"]="nova-3"
    ["groq"]="whisper-large-v3-turbo"
    ["60db"]="60db-stt-v01"
    ["elevenlabs"]="scribe_v1"
)

declare -A BACKEND_DISPLAY=(
    ["deepgram"]="Deepgram"
    ["groq"]="Groq"
    ["60db"]="60db"
    ["elevenlabs"]="ElevenLabs Scribe"
)

# Timeouts
EMULATOR_BOOT_TIMEOUT=180
TRANSCRIPTION_TIMEOUT=30

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ── Step Timer ─────────────────────────────────────────────────────
# Usage: STEP_START=$SECONDS at the top, then step_timer "label" after each phase.
STEP_START=0
TEST_START=0

step_timer() {
    local now=$SECONDS
    local elapsed=$(( now - STEP_START ))
    local total=$(( now - TEST_START ))
    echo -e "${BLUE}[TIME]${NC} $1: ${elapsed}s (cumulative: ${total}s)"
    STEP_START=$now
}

# =============================================================================
# Helper Functions
# =============================================================================

log_info() { echo -e "${BLUE}[$(date +%H:%M:%S)] [INFO]${NC} $*"; }

# Portable sleep: CI runners have plain `sleep`; some dev shells shadow it
# with a `sleep-i-am-sure` guard. Fall back so the same script runs in both.
ssleep() { command sleep "$@" 2>/dev/null || sleep-i-am-sure "$@" 2>/dev/null || true; }
log_ok()   { echo -e "${GREEN}[$(date +%H:%M:%S)] [OK]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] [WARN]${NC} $*"; }
log_err()  { echo -e "${RED}[$(date +%H:%M:%S)] [ERR]${NC} $*"; }

wait_for() {
    local desc="$1" timeout_s="$2" predicate="$3"
    local start s
    start=$(date +%s)
    while (( $(date +%s) - start < timeout_s )); do
        if eval "$predicate" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.5
    done
    log_warn "wait_for timed out: $desc"
    return 1
}

die() {
    log_err "$*"
    # Best-effort diagnostic screenshot so CI failures are never blind
    # (e.g. a missing UI node we can then inspect in the uploaded artifact).
    capture_diag 2>/dev/null || true
    exit 1
}

run_cmd() {
    local cmd="$1"
    local check="${2:-true}"
    local output
    output=$(eval "$cmd" 2>&1) || {
        local rc=$?
        if [[ "$check" == "true" ]]; then
            die "Command failed (rc=$rc): $cmd\nOutput: $output"
        fi
		# Best-effort (check=false): swallow the exit code so set -e
		# doesn't abort the script on an expected non-fatal failure.
		return 0
    }
    echo "$output"
}

adb_cmd() {
    run_cmd "$ADB -s $SERIAL $*"
}

# =============================================================================
# Virtual Microphone Setup
# =============================================================================

setup_virtual_mic() {
    log_info "Setting up virtual microphone sources..."

    # Check if already exist
    if pactl list short sources | grep -q "$FAKE_MIC" && \
       pactl list short sinks | grep -q "$VIRTUAL_SINK"; then
        log_ok "Virtual mic sources already exist"
        return 0
    fi

    # VirtualMicSink (null sink) — QEMU captures its monitor as mic input.
    # We play test audio directly into this sink; being a null sink it
    # produces zero speaker output, so the host speakers stay silent.
    run_cmd "pactl load-module module-null-sink sink_name=$VIRTUAL_SINK sink_properties=device.description=$VIRTUAL_SINK"

    # FakeMic (remap source from VirtualMicSink.monitor) — QEMU uses this as its audio input
    run_cmd "pactl load-module module-remap-source source_name=$FAKE_MIC master=${VIRTUAL_SINK}.monitor source_properties=device.description=$FAKE_MIC"

    wait_for "Virtual mic sinks" 5 "pactl list short sinks | grep -q $VIRTUAL_SINK"
    wait_for "FakeMic source" 5 "pactl list short sources | grep -q $FAKE_MIC"
    log_ok "Virtual mic sources created"
}

capture_diag() {
    local out="${1:-e2e_diag.png}"
    log_warn "Capturing diagnostic screenshot -> $out"
    # Prefer hs see (agent-sized JPEG); fall back to adb screencap if the
    # daemon isn't reachable (e.g. emulator unreachable at failure time).
    hs --device "$SERIAL" see --size 768 "$out" 2>/dev/null \
        || "$ADB" -s "$SERIAL" exec-out screencap -p "$out" 2>/dev/null \
        || "$ADB" -s "$SERIAL" shell screencap -p > "$out" 2>/dev/null \
        || log_warn "screenshot failed (emulator may not be reachable)"

    # Also dump the UI hierarchy as XML (text-based, easy to grep/inspect
    # for the exact node/resource-id that was missing at failure time).
    local xml="${out%.png}.xml"
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 \
        && "$ADB" -s "$SERIAL" pull /sdcard/ui.xml "$xml" >/dev/null 2>&1 \
        && log_warn "Captured diagnostic UI hierarchy -> $xml" \
        || log_warn "ui dump failed (emulator may not be reachable)"
}

cleanup_virtual_mic() {
    log_info "Cleaning up virtual mic modules..."
    # pactl (PulseAudio) only exists on developer machines; the CI
    # runner has no audio stack. Safe to skip there (setup_virtual_mic
    # is never called in test-file mode anyway).
    command -v pactl >/dev/null 2>&1 || { log_warn "pactl not available — skipping virtual mic cleanup"; return 0; }
    # Unload each matching module individually (grep may return multiple IDs)
    for pattern in "$VIRTUAL_SINK" "$FAKE_MIC"; do
        pactl list short modules | grep "$pattern" | awk '{print $1}' | while read -r mid; do
            pactl unload-module "$mid" 2>/dev/null || true
        done
    done
}

# =============================================================================
# Test Audio Generation
# =============================================================================

generate_test_audio() {
    log_info "Generating test speech audio..."

    if [[ -f "$WAV_FILE" ]]; then
        log_ok "Test audio already exists: $WAV_FILE"
        return 0
    fi

    # Generate speech with espeak-ng
    run_cmd "espeak-ng -v en-us -s 150 'hello world this is a test of speech to text transcription' --stdout > /tmp/test-speech.wav"

    # Boost volume for clear transcription
    run_cmd "ffmpeg -y -i /tmp/test-speech.wav -af 'volume=15dB' -ar 44100 -c:a pcm_s16le $WAV_FILE"

    # Verify peak level
    local peak
    peak=$(ffmpeg -i "$WAV_FILE" -af "volumedetect" -f null /dev/null 2>&1 | grep "max_volume" | awk '{print $3}')
    log_ok "Test audio generated: $WAV_FILE (peak: ${peak} dB)"
}

# =============================================================================
# Emulator Management
# =============================================================================

wait_for_emulator_offline() {
    local start s
    start=$(date +%s)
    while (( $(date +%s) - start < 15 )); do
        if [[ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" != "device" ]]; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

start_emulator() {
    # Check if emulator is already running
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 && \
       [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null)" == "1" ]]; then
        # Check if running in correct mode via mode file
        local current_mode="unknown"
        [[ -f "$MODE_FILE" ]] && current_mode=$(cat "$MODE_FILE")
        local want_mode="headless"
        [[ "$HEADFUL" == "true" ]] && want_mode="headful"

        # If we didn't start this emulator ourselves (no mode file, e.g. an
        # externally-managed CI emulator), reuse it as-is instead of restarting.
        if [[ -f "$MODE_FILE" && "$current_mode" != "$want_mode" ]]; then
            log_warn "Emulator running $current_mode but we need $want_mode — restarting..."
            "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
            wait_for_emulator_offline || true
            pkill -9 -f "qemu-system.*${AVD}" 2>/dev/null || true
            wait_for "emulator process death" 10 "[[ -z \"\$(pgrep -f 'qemu-system.*${AVD}')\" ]]"
        else
            EMULATOR_WAS_RUNNING=true
            log_ok "Emulator already running in correct mode — reusing"
            return 0
        fi
    fi

    # Verify snapshot exists for quick boot
    local snapshot="${HOME}/.android/avd/${AVD}.avd/snapshots/default_boot/ram.bin"
    if [[ ! -f "$snapshot" ]] || [[ ! -s "$snapshot" ]]; then
        die "No emulator snapshot found at $snapshot\n\nRun this first to create the snapshot:\n  just emulator-save-snapshot"
    fi

    log_info "Starting emulator..."

    # Clean up stale PID file
    rm -f "$PID_FILE" "$EMU_LOG"

    # Launch emulator
    local emu_flags="-gpu host -no-snapshot-save"
    local emu_mode="headless"
    if [[ "$HEADFUL" == "true" ]]; then
        log_info "Launching emulator (headful)..."
        emu_mode="headful"
    else
        log_info "Launching emulator (headless)..."
        emu_flags="$emu_flags -no-window"
    fi
    echo "$emu_mode" > "$MODE_FILE"
    setsid "$EMULATOR" -avd "$AVD" $emu_flags > "$EMU_LOG" 2>&1 &
    echo $! > "$PID_FILE"

    local boot_start=$SECONDS
    log_info "Waiting for emulator boot..."
    wait_for "emulator boot" $EMULATOR_BOOT_TIMEOUT \
        '"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 && "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"'
    log_ok "Emulator booted in $(( SECONDS - boot_start ))s"
    # Disable animations for faster UI automation
    adb_cmd shell settings put global window_animation_scale 0
    adb_cmd shell settings put global transition_animation_scale 0
    adb_cmd shell settings put global animator_duration_scale 0
}

disable_host_mic() {
    # We inject audio via the virtual mic (VirtualMicSink -> FakeMic).
    # The host microphone must stay OFF so the emulator does not capture
    # the user's real voice instead of the test audio.
    log_info "Disabling host microphone (using silent virtual FakeMic)..."
    adb_cmd emu avd hostmicoff
    log_ok "Host microphone disabled"
}

stop_emulator() {
    log_info "Stopping emulator..."
    # Graceful shutdown via adb (snapshot NOT saved — we use -no-snapshot-save)
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
        "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
        # Poll for device to go offline instead of blind sleep
        wait_for "emulator shutdown" 15 "[[ \"\$($ADB -s $SERIAL get-state 2>/dev/null)\" != 'device' ]]" || true
    fi
    # Clean up PID file
    if [[ -f "$PID_FILE" ]]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            log_warn "Process still alive after adb emu kill, force killing..."
            kill "$pid" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
    fi
    rm -f "$EMU_LOG"
    log_ok "Emulator stopped"
}

# =============================================================================
# App Installation & Permissions
# =============================================================================

clear_app_data() {
    log_info "Clearing app data for fresh state..."
    # Non-fatal: package may not be installed yet on a fresh emulator
    run_cmd "$ADB -s $SERIAL shell pm clear $PACKAGE" false
    log_ok "App data cleared"
}

build_and_install() {
    log_info "Building and installing APK..."
    # Prefer an already-configured JAVA_HOME (CI sets it via actions/setup-java);
    # fall back to the local sdkman install on developer machines.
    if [[ -z "${JAVA_HOME:-}" ]]; then
        export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    fi
    cd android
    run_cmd "./gradlew assembleDebug"
    cd ..
    adb_cmd install -r android/app/build/outputs/apk/debug/app-debug.apk
    log_ok "APK installed"
}

grant_permissions() {
    log_info "Granting permissions..."
    adb_cmd shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
    adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
    log_ok "Permissions granted"
}

enable_ime() {
    log_info "Enabling Whisper IME..."
    # Wait for package to be visible to PM
    wait_for "package registered" 10 "adb_cmd shell pm list packages | grep -q $PACKAGE"
    local attempts=0
    while [[ $attempts -lt 3 ]]; do
        if adb_cmd shell ime enable "$SERVICE" 2>/dev/null; then
            log_ok "IME enabled"
            return 0
        fi
        attempts=$((attempts + 1))
        log_warn "IME not yet registered, retrying ($attempts/3)..."
        ssleep 1
    done
    die "Failed to enable IME after 3 attempts"
}

set_default_ime() {
    local ime="$1"
    adb_cmd shell ime set "$ime"
    local current
    current=$(adb_cmd shell settings get secure default_input_method)
    if [[ "$current" != *"$1"* ]]; then
        die "Failed to set default IME to $1 (current: $current)"
    fi
    log_ok "Default IME set to $1"
}

# =============================================================================
# UI Automation Helpers (using hs / handsets)
# =============================================================================

HS="hs --device $SERIAL --json"

# Tap an element by resource-id (app prefix auto-handled)
hs_tap_rid() {
    local result
    result=$($HS tap "#$1" 2>/dev/null) || true
    echo "$result"
    echo "$result" | grep -q '"ok":true'
}

# Tap a view by resource-id via uiautomator dump + input tap. More robust
# than `hs tap #id` for views (e.g. Spinners) that hs's tappable-node index
# intermittently fails to surface, causing flaky NOT_FOUND.
tap_rid_via_ui() {
    local rid="$1"
    local xml
    xml=$(mktemp /tmp/ui_XXXX.xml)
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 \
        && "$ADB" -s "$SERIAL" pull /sdcard/ui.xml "$xml" >/dev/null 2>&1 \
        || { rm -f "$xml"; return 1; }
    local node bounds
    node=$(grep "id/$rid" "$xml" | head -1)
    bounds=$(echo "$node" | grep -oP 'bounds="\K\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
    rm -f "$xml"
    [[ -z "$bounds" ]] && return 1
    local x1 y1 x2 y2 cx cy
    x1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
    y1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
    x2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
    y2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
    cx=$(( (x1 + x2) / 2 ))
    cy=$(( (y1 + y2) / 2 ))
    "$ADB" -s "$SERIAL" shell input tap "$cx" "$cy"
    return 0
}

# Tap an element by exact text
hs_tap_text() {
    local result
    result=$($HS tap "$1" 2>/dev/null) || true
    echo "$result"
    echo "$result" | grep -q '"ok":true'
}

# Set text into a field by resource-id (no IME switching needed)
hs_fill_rid() {
    local field_id="$1"
    local text="$2"
    local result
    result=$($HS fill "id=com.example.whispertoinput:id/$field_id" "$text" 2>/dev/null) || true
    echo "$result"
    echo "$result" | grep -q '"ok":true'
}

# Scroll down (swipe up)
hs_scroll_down() {
    $HS swipe up 2>/dev/null || true
}

# Start the hs daemon (connects to the device). Required in CI before any hs
# verb; on a dev machine it's a safe no-op if the daemon is already up.
hs_daemon_start() {
    log_info "Starting hs daemon..."
    hs --device "$SERIAL" use >/dev/null 2>&1 || hs use >/dev/null 2>&1 || true
    log_ok "hs daemon ready"
}

# =============================================================================
# Settings Configuration
# =============================================================================

open_settings() {
    log_info "Opening Settings activity..."
    adb_cmd shell am start -n "$PACKAGE/.MainActivity" >/dev/null
    # Wait for the Settings activity to reach the foreground (no raw dumpsys poll)
    $HS wait "$PACKAGE/.MainActivity" --timeout 5s >/dev/null 2>&1 \
        || wait_for "Settings activity ready" 5 'adb_cmd shell dumpsys activity activities | grep -q "topResumedActivity"'
}

select_backend() {
    local backend="$1"
    local display="${BACKEND_DISPLAY[$backend]}"

    log_info "Selecting backend: $display"

    # The settings UI initializes asynchronously: the spinner's selection
    # listener and the endpoint/model field defaults are set in coroutines.
    # Tapping the spinner before that finishes is a race — the selection is
    # ignored and the endpoint field stays empty. Wait until the endpoint
    # field is populated with its default before interacting.
    local ep=""
    for _ in $(seq 1 30); do
        ep=$(hs find 'EditText[id=com.example.whispertoinput:id/field_endpoint]' --json 2>/dev/null \
            | grep -oP '"text":\s*"\K[^"]*' | head -1)
        [[ -n "$ep" ]] && break
        ssleep 0.5
    done
    [[ -z "$ep" ]] && log_warn "Endpoint field never populated — UI may not have initialized"

    # Open the backend spinner. Try `hs find` first (stable hs connection),
    # fall back to uiautomator dump + input tap. hs's tappable-node index
    # intermittently misses Spinners, causing flaky NOT_FOUND.
    local tap_ok=false
    for attempt in 1 2 3 4 5; do
        # Try hs find first (uses stable hs connection, searches all nodes)
        local node bounds
        node=$($HS find 'Spinner[id=com.example.whispertoinput:id/spinner_speech_to_text_backend]' --json 2>/dev/null | head -1)
        if [[ -n "$node" ]]; then
            bounds=$(echo "$node" | grep -oP 'bounds="\K\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' 2>/dev/null)
            if [[ -n "$bounds" ]]; then
                local x1 y1 x2 y2 cx cy
                x1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
                y1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
                x2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
                y2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
                cx=$(( (x1 + x2) / 2 ))
                cy=$(( (y1 + y2) / 2 ))
                if $HS tap "$cx" "$cy" >/dev/null 2>&1; then
                    tap_ok=true
                    break
                fi
            fi
        fi
        # Fallback: uiautomator dump + input tap
        if tap_rid_via_ui "spinner_speech_to_text_backend"; then
            tap_ok=true
            break
        fi
        log_warn "Spinner tap failed (attempt $attempt/5), retrying..."
        ssleep 1
    done
    if [[ "$tap_ok" != "true" ]]; then
        die "Could not find spinner after retries"
    fi
    ssleep 0.5

    # Select the backend from the dropdown (fallback to a plain text tap).
    $HS act --tap "$display" \
        --until 'EditText[id=com.example.whispertoinput:id/field_endpoint]' \
        --retries 2 --retry-delay 1s --timeout 5s >/dev/null 2>&1 \
        || hs_tap_text "$display"

    # Verify the endpoint/model reflect the selected backend. The autofill is
    # async, so poll instead of reading once.
    local expected="${BACKEND_ENDPOINT[$backend]}"
    local model="${BACKEND_MODEL[$backend]}"
    local endpoint_text="" model_text=""
    for _ in $(seq 1 20); do
        endpoint_text=$(hs find 'EditText[id=com.example.whispertoinput:id/field_endpoint]' --json 2>/dev/null \
            | grep -oP '"text":\s*"\K[^"]*' | head -1)
        model_text=$(hs find 'EditText[id=com.example.whispertoinput:id/field_model]' --json 2>/dev/null \
            | grep -oP '"text":\s*"\K[^"]*' | head -1)
        if [[ "$endpoint_text" == *"$expected"* && "$model_text" == *"$model"* ]]; then
            break
        fi
        ssleep 0.5
    done
    if [[ "$endpoint_text" != *"$expected"* ]]; then
        die "Endpoint mismatch: expected $expected, got $endpoint_text"
    fi
    if [[ "$model_text" != *"$model"* ]]; then
        die "Model mismatch: expected $model, got $model_text"
    fi
    log_ok "Backend selected: $display (endpoint verified)"
}

set_api_key() {
    local api_key="$1"

    log_info "Setting API key..."
    sleep 0.5

    # Try to find the API key field, scroll if needed
    local found=false
    for attempt in 1 2 3; do
        if hs_fill_rid "field_api_key" "$api_key"; then
            found=true
            break
        fi
        log_info "API key field not visible, scrolling..."
        hs_scroll_down
        sleep 1
    done
    if [[ "$found" != "true" ]]; then
        die "Could not locate API key field after scrolling"
    fi

    log_ok "API key set"
}


apply_settings() {
    hs_tap_rid "btn_settings_apply"
    sleep 1
}

push_test_audio() {
    log_info "Pushing test audio to emulator app cache..."
    # Push to /data/local/tmp/ then run-as cp into app cache (scoped storage)
    adb_cmd push "$WAV_FILE" /data/local/tmp/test-speech-loud.wav
    adb_cmd shell "run-as $PACKAGE cp /data/local/tmp/test-speech-loud.wav cache/test-speech-loud.wav"
    adb_cmd shell rm -f /data/local/tmp/test-speech-loud.wav
    log_ok "Test audio pushed to app cache/test-speech-loud.wav"
}

# =============================================================================
# Transcription Test
# =============================================================================

focus_text_field() {
    log_info "Focusing app text field to trigger Whisper keyboard..."

    # Open the app's MainActivity (has field_debug_output EditText)
    adb_cmd shell am start -n "$PACKAGE/.MainActivity" >/dev/null
    $HS wait "$PACKAGE/.MainActivity" --timeout 5s >/dev/null 2>&1 \
        || wait_for "App ready" 5 'adb_cmd shell dumpsys activity activities | grep -q "topResumedActivity"'

    # Tap the debug output EditText to focus it and show keyboard
    local attempts=0
    while (( attempts < 3 )); do
        hs_tap_rid "field_debug_output"
        sleep 0.5

        if adb_cmd shell dumpsys input_method 2>/dev/null | grep -q "mIsInputViewShown=true"; then
            log_ok "Whisper keyboard is shown"
            return 0
        fi
        attempts=$((attempts + 1))
    done
    log_warn "Keyboard did not appear after retries — continuing anyway"
}

tap_mic_button() {
    log_info "Triggering mic toggle via broadcast..."
    adb_cmd shell am broadcast -a com.example.whispertoinput.action.TOGGLE_RECORDING
    log_ok "Broadcast sent"
}

wait_for_transcription() {
    local expected="$1"
    local expected_lower="${expected,,}"

    log_info "Waiting for transcription to appear (expecting: '$expected')..."

    local start=$(date +%s)
    while (( $(date +%s) - start < TRANSCRIPTION_TIMEOUT )); do
        # Check for errors in logcat first
        local error
        error=$(adb_cmd logcat -d -s "whisper-input:E" 2>/dev/null | grep -oP "Transcription error: \K.*" | tail -1) || true
        if [[ -n "$error" ]]; then
            echo -e "${RED}[TIME]${NC} Transcription wait: $(( $(date +%s) - start ))s"
            capture_diag
            die "Transcription failed: $error"
        fi

        # Check for success in logcat
        local result
        result=$(adb_cmd logcat -d -s "whisper-input:V" 2>/dev/null | grep -oP "Transcription result: '\K[^']*" | tail -1) || true
        if [[ -n "$result" && "$result" != "null" ]]; then
            log_ok "Transcription committed: $result"
            echo -e "${BLUE}[TIME]${NC} Transcription wait: $(( $(date +%s) - start ))s"
            return 0
        fi

        # Also check UI for text (fallback)
        local text
        text=$(hs find 'EditText' --json 2>/dev/null | grep -oP '"text":\s*"\K[^"]*' | head -1) || true
        if [[ -n "$text" && "$text" != *"Transcribed text will appear here"* ]]; then
            local text_lower="${text,,}"
            if [[ "$text_lower" == *"$expected_lower"* ]]; then
                log_ok "Transcription committed: $text"
                echo -e "${BLUE}[TIME]${NC} Transcription wait: $(( $(date +%s) - start ))s"
                return 0
            fi
        fi
        sleep 1
    done
    echo -e "${RED}[TIME]${NC} Transcription wait: TIMEOUT after ${TRANSCRIPTION_TIMEOUT}s"
    capture_diag
    die "Timeout: expected substring '$expected' not found in field"
}

# =============================================================================
# Main Test Flow
# =============================================================================

# ── Negative-path checks ─────────────────────────────────────────
# Read-only observers run during the happy-path E2E. They never change the
# flow, so the happy path is unchanged. (The "cancel mid-transcribe returns to
# Idle" guard lives at the unit level in WhisperKeyboardTest.cancel_transcribing_
# cancels, since the IME's btn_cancel is not wired into WhisperInputService today.)

STATUS_SEEN_RECORDING=false
STATUS_SEEN_TRANSCRIBING=false

# Current text of label_status if the IME keyboard is visible, else empty.
sample_status_label() {
    hs find 'TextView[id=com.example.whispertoinput:id/label_status]' --json 2>/dev/null | grep -oP '"text":\s*"\K[^"]*' | head -1
}

# Sample the status label for up to TRANSCRIPTION_TIMEOUT seconds, recording
# which states we observed (whisper_to_input -> recording -> transcribing).
monitor_status_label() {
    local start
    start=$(date +%s)
    while (( $(date +%s) - start < TRANSCRIPTION_TIMEOUT )); do
        local s
        s=$(sample_status_label)
        if [[ "$s" == *"Recording"* ]]; then STATUS_SEEN_RECORDING=true; fi
        if [[ "$s" == *"Transcribing"* ]]; then STATUS_SEEN_TRANSCRIBING=true; fi
        sleep 0.5
    done
}

# A start->transcribe cycle must commit exactly one "Transcription result:"
# line — guards against a stray toggle double-committing.
check_single_transcription_result() {
    local count
    count=$(adb_cmd logcat -d -s "whisper-input:V" 2>/dev/null | grep -c "Transcription result:")
    if [[ "$count" -eq 1 ]]; then
        log_ok "Single transcription result committed (double-tap guard OK)"
    else
        log_warn "Expected exactly 1 'Transcription result:' line, saw $count"
    fi
}

run_e2e_test() {
    local backend="$1"
    local api_key="$2"
    local expected="$3"

    TEST_START=$SECONDS
    STEP_START=$SECONDS
    log_info "=== Starting E2E test for $backend ==="

    # 1. Emulator
    start_emulator
    step_timer "Emulator start"

    # Wait for package manager and system services to fully settle after boot
    # (already confirmed via boot_completed, just need a small buffer for PM)
    wait_for "package manager ready" 5 'adb_cmd shell pm list packages >/dev/null 2>&1'

    # Start the UI-automation daemon before driving the app.
    hs_daemon_start

    # 3. App
    clear_app_data
    build_and_install
    step_timer "Build + install"
    grant_permissions
    enable_ime
    set_default_ime "$SERVICE"
    step_timer "Permissions + IME setup"

    # 4. Push test audio to emulator (before enabling test-file mode)
    push_test_audio
    step_timer "Push test audio"

    # 5. Settings configuration via UI
    local api_key_var="${backend^^}_KEY"
    local api_key="${!api_key_var:-$2}"
    if [[ -z "$api_key" ]]; then
        die "No API key provided for $backend (set ${backend^^}_KEY env var or pass as arg)"
    fi
    open_settings
    step_timer "Open settings"
    select_backend "$backend"
    step_timer "Select backend"
    set_api_key "$api_key"
    step_timer "Set API key"
    apply_settings
    step_timer "Apply settings"

    # Ensure Whisper is default
    set_default_ime "$SERVICE"

    # 6. Trigger test-file transcription via tap-to-toggle
    focus_text_field          # Opens Settings search bar, keyboard appears
    step_timer "Focus text field"

    # Negative-path: monitor the status label (whisper_to_input -> recording
    # -> transcribing -> whisper_to_input) across the taps that follow.
    monitor_status_label & MON_PID=$!

    # First tap: starts test-file mode (sets flag, no actual recording)
    tap_mic_button
    sleep 1
    step_timer "Test-file start"

    # Reset logcat so the single-result guard counts only this run.
    adb_cmd logcat -c
    # Second tap: stops and transcribes the test file
    tap_mic_button
    step_timer "Test-file transcribe"

    # 7. Wait for transcription
    wait_for_transcription "$3"
    step_timer "Transcription"

    # Negative-path assertions (observational; do not alter the happy path).
    # Kill the monitor early — no need to wait the full TRANSCRIPTION_TIMEOUT
    kill "$MON_PID" 2>/dev/null || true
    wait "$MON_PID" 2>/dev/null || true
    if [[ "$STATUS_SEEN_RECORDING" == "true" && "$STATUS_SEEN_TRANSCRIBING" == "true" ]]; then
        log_ok "Status label cycled recording -> transcribing"
    else
        log_warn "Status label transition not fully observed (recording=$STATUS_SEEN_RECORDING transcribing=$STATUS_SEEN_TRANSCRIBING)"
    fi
    check_single_transcription_result

    log_ok "=== TEST PASSED: $backend ==="
    echo -e "${GREEN}[TIME]${NC} Total test time: $(( SECONDS - TEST_START ))s"
}

cleanup() {
    log_info "Cleaning up..."
    if [[ "$EMULATOR_WAS_RUNNING" != "true" ]]; then
        stop_emulator
        cleanup_virtual_mic
    else
        log_info "Emulator was pre-existing — leaving it running (and the virtual mic it needs)"
        log_info "Virtual mic chain is left intact for the next run to reuse the same emulator"
    fi
    log_ok "Cleanup complete"
}

# =============================================================================
# Argument Parsing
# =============================================================================

BACKEND=""
API_KEY=""
EXPECTED=""
HEADFUL=false
DEBUG=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --backend)
            BACKEND="$2"
            shift 2
            ;;
        --key)
            API_KEY="$2"
            shift 2
            ;;
        --expected)
            EXPECTED="$2"
            shift 2
            ;;
        --headful)
            HEADFUL=true
            shift
            ;;
        --debug)
            DEBUG=true
            shift
            ;;
        --help|-h)
            cat <<EOF
Usage: $0 --backend <deepgram|groq|60db> --key <API_KEY> --expected <substring> [--headful]

Options:
  --backend   Backend to test (deepgram, groq, 60db)
  --key       API key for the backend
  --expected  Expected substring in transcription result
  --headful   Run emulator with visible window (default: headless)
  --debug     Enable debug tracing (set -x)

Environment variables can also be used:
  DEEPGRAM_KEY, GROQ_KEY, SIXTYDB_KEY

Example:
  $0 --backend deepgram --key \$DEEPGRAM_KEY --expected "hello world"
EOF
            exit 0
            ;;
        *)
            die "Unknown option: $1"
            ;;
    esac
done

if [[ -z "$BACKEND" || -z "$EXPECTED" ]]; then
    die "Missing required arguments. Use --help for usage."
fi

# Validate backend
if [[ ! "${BACKEND_DISPLAY[$BACKEND]+_}" ]]; then
    die "Invalid backend: $BACKEND (must be deepgram, groq, 60db, or elevenlabs)"
fi

# API key: explicit arg → env var → embedded default
if [[ -z "$API_KEY" ]]; then
    api_var="${BACKEND^^}_KEY"
    API_KEY="${!api_var:-}"
fi
if [[ -z "$API_KEY" && "$BACKEND" == "deepgram" ]]; then
    API_KEY="$DEEPGRAM_KEY_DEFAULT"
fi

if [[ -z "$API_KEY" ]]; then
    die "API key not provided. Use --key or set ${BACKEND^^}_KEY environment variable."
fi

# Enable debug tracing if --debug flag was passed
if [[ "$DEBUG" == "true" ]]; then
    set -x
fi

# =============================================================================
# Trap cleanup on exit
# =============================================================================

trap cleanup EXIT INT TERM

# =============================================================================
# Run
# =============================================================================

log_info "=== Whisper To Input E2E Test ==="
log_info "Backend: $BACKEND"
log_info "Expected: $EXPECTED"

# Generate test audio if needed
generate_test_audio

# Run the test
run_e2e_test "$BACKEND" "$API_KEY" "$EXPECTED"

log_ok "=== ALL TESTS PASSED ==="
exit 0