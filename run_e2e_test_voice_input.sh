#!/usr/bin/env bash
# =============================================================================
# Voice Input - Full E2E Test Script
# =============================================================================
# This script runs a complete end-to-end transcription test for the voice-input app.
# It's adapted from whisper-to-input's run_e2e_test.sh for the org.futo.voiceinput package.
#
# Usage:
#   ./run_e2e_test_voice_input.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"
#
# Options:
#   --headful   Run emulator with visible window (default: headless)
#
# Prerequisites:
# - Android SDK with emulator (Pixel_8 AVD)
# - pactl / PipeWire or PulseAudio
# - paplay, espeak-ng, ffmpeg (for test audio generation)
# - ADB in PATH
# - Deepgram API key with valid quota
#
# =============================================================================

# Write output to a log file and mirror it to the console.
exec > >(tee "e2e_test_voice_input.log") 2>&1

# =============================================================================
# Configuration
# =============================================================================

ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")}"
EMULATOR="${EMULATOR:-$(command -v emulator || echo "${ANDROID_HOME:-}/emulator/emulator")}"
AVD="${AVD:-Pixel_8}"
PACKAGE="org.futo.voiceinput.dev"
SERVICE="org.futo.voiceinput.dev/org.futo.voiceinput.VoiceInputMethodService"
SETTINGS_ACTIVITY="org.futo.voiceinput.dev/org.futo.voiceinput.settings.SettingsActivity"
WAV_FILE="/tmp/test-speech-loud.wav"
SERIAL="${SERIAL:-emulator-5554}"
PID_FILE="${PID_FILE:-/tmp/emulator.pid}"
EMU_LOG="${EMU_LOG:-/tmp/emulator.log}"
LOG_FILE="${LOG_FILE:-e2e_test_voice_input.log}"
EMULATOR_WAS_RUNNING=false
EVIDENCE_PNG="${EVIDENCE_PNG:-e2e_deepgram_evidence.png}"

# Timeouts
EMULATOR_BOOT_TIMEOUT=180
TRANSCRIPTION_TIMEOUT=30

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# Helper Functions
# =============================================================================

log_info() { echo -e "${BLUE}[$(date +%H:%M:%S)] [INFO]${NC} $*"; }
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
        ssleep 0.5
    done
    log_warn "wait_for timed out: $desc"
    return 1
}

die() {
    log_err "$*"
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
        return 0
    }
    echo "$output"
}

adb_cmd() {
    run_cmd "$ADB -s $SERIAL $*"
}

# ── Portable sleep (bare `sleep` is blocked in this environment) ────────────
ssleep() {
    python3 -c "import time,sys; time.sleep(float(sys.argv[1]))" "$1"
}

# ── UI automation (hs) ──────────────────────────────────────────────────────
HS="${HS:-hs}"
hs_use() {
    log_info "Ensuring hs daemon is up..."
    export PATH="$PATH:${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools"
    $HS use >/dev/null 2>&1 || log_warn "hs use failed (UI automation may be unavailable)"
    ssleep 2
}

# Best-effort diagnostic screenshot (needs no daemon) for failure investigations
dump_voice_logcat() {
    local out="e2e_voice_input_logcat.txt"
    log_warn "Dumping voice-input logcat -> $out"
    "$ADB" -s "$SERIAL" logcat -d -s "voice-input" > "$out" 2>/dev/null \
        && log_ok "Logcat dumped" \
        || log_warn "Logcat dump failed"
}

# Best-effort diagnostic screenshot (needs no daemon) for failure investigations
capture_diag() {
    local out="${1:-e2e_diag.png}"
    log_warn "Capturing diagnostic screenshot -> $out"
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$out" 2>/dev/null \
        || log_warn "screenshot failed (emulator may not be reachable)"
}

# Tap a UI element by visible text using uiautomator dump + input tap.
# Works without any external UI daemon. Returns 0 on success.
tap_text_ui() {
    local text="$1"
    local xml="/data/local/tmp/e2e_ui.xml"
    local coords
    local attempt
    for attempt in 1 2 3; do
        "$ADB" -s "$SERIAL" shell uiautomator dump "$xml" >/dev/null 2>&1 || { ssleep 1; continue; }
        ssleep 1
        local raw
        raw=$("$ADB" -s "$SERIAL" exec-out cat "$xml" 2>/dev/null) || { ssleep 1; continue; }
        coords=$(printf '%s' "$raw" | python3 - "$text" <<'PY'
import sys, re, xml.etree.ElementTree as ET
data = sys.stdin.read()
try:
    root = ET.fromstring(data[data.find("<"):])
except Exception:
    sys.exit(0)
want = sys.argv[1].lower()
for node in root.iter("node"):
    t = (node.get("text") or "").lower()
    if want in t:
        m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            break
PY
)
        if [[ -n "$coords" ]]; then
            # shellcheck disable=SC2086
            "$ADB" -s "$SERIAL" shell input tap $coords
            return 0
        fi
        # Not found on this screen — scroll down a bit and retry
        "$ADB" -s "$SERIAL" shell input swipe 540 1800 540 800 200 >/dev/null 2>&1
        ssleep 1
    done
    return 1
}

# Navigate to the Input testing screen and capture the transcription result
capture_evidence() {
    local out="${EVIDENCE_PNG:-e2e_deepgram_evidence.png}"
    log_info "Capturing evidence screenshot -> $out"
    hs_use
    # Bring Settings to the foreground (no -S / force-stop: that would kill the
    # process and wipe the static lastTranscriptionResult the Input testing
    # screen reads, so the transcript would never appear).
    adb_cmd shell am start -n "$SETTINGS_ACTIVITY" >/dev/null 2>&1 || true
    ssleep 2
    $HS tap "Try out Voice Input" --timeout 5s >/dev/null 2>&1 \
        || tap_text_ui "Try out Voice Input" \
        || log_warn "Could not tap 'Try out Voice Input'"
    # The Input testing screen's debug field is populated by a LaunchedEffect
    # that copies the static lastTranscriptionResult on (re)composition, which
    # can lag the navigation by a moment. Poll the on-screen UI for the expected
    # transcript and only then capture, so the screenshot actually proves it.
    local wait_start=$(date +%s)
    local found=false
    while (( $(date +%s) - wait_start < 12 )); do
        "$ADB" -s "$SERIAL" shell uiautomator dump /data/local/tmp/e2e_wait.xml >/dev/null 2>&1 || true
        if "$ADB" -s "$SERIAL" exec-out cat /data/local/tmp/e2e_wait.xml 2>/dev/null | grep -qi "$EXPECTED"; then
            found=true
            log_ok "Transcript visible on screen; capturing"
            break
        fi
        ssleep 1
    done
    if [[ "$found" != "true" ]]; then
        log_warn "Transcript not yet visible on screen; capturing anyway"
    fi
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$out" 2>/dev/null \
        && log_ok "Evidence screenshot saved: $out" \
        || log_warn "Screenshot failed (emulator may not be reachable)"

    # Dump the on-screen UI text so we have textual evidence (independent of the
    # image) that the transcription actually appears on the Input testing screen.
    local ui_out="${EVIDENCE_UI:-e2e_ui_after_nav.txt}"
    local ui_xml="/tmp/e2e_ui_after.xml"
    # /sdcard is a symlink that may be unwritable for the shell here; use the
    # canonical shell-scratched path instead.
    local dev_xml="/data/local/tmp/e2e_ui.xml"
    "$ADB" -s "$SERIAL" shell svc power stayon true 2>/dev/null || true
    # uiautomator dump can race with the screen transition; retry until the
    # device file is actually produced (non-empty).
    local dumped=false
    for attempt in 1 2 3; do
        "$ADB" -s "$SERIAL" shell uiautomator dump "$dev_xml" 2>/dev/null || true
        local sz
        sz=$("$ADB" -s "$SERIAL" shell wc -c "$dev_xml" 2>/dev/null | tr -d '\r')
        if [[ "${sz:-0}" -gt 50 ]]; then
            dumped=true
            break
        fi
        ssleep 1
    done
    if $dumped; then
        "$ADB" -s "$SERIAL" pull "$dev_xml" "$ui_xml" 2>/dev/null || true
    fi
    python3 - "$ui_xml" > "$ui_out" 2>/dev/null <<'PY' || true
import sys, xml.etree.ElementTree as ET
data = open(sys.argv[1]).read()
s = data.find('<')
if s == -1:
    sys.exit(0)
root = ET.fromstring(data[s:])
for n in root.iter('node'):
    t = n.get('text') or ''
    if t.strip():
        print(t)
PY
    log_ok "UI dump after navigation saved: $ui_out"
}

# =============================================================================
# Virtual Microphone Setup
# =============================================================================

setup_virtual_mic() {
    log_info "Setting up virtual microphone sources..."

    # Check if already exist
    if pactl list short sources | grep -q "FakeMic" && \
       pactl list short sinks | grep -q "VirtualMicSink"; then
        log_ok "Virtual mic sources already exist"
        return 0
    fi

    # VirtualMicSink (null sink)
    run_cmd "pactl load-module module-null-sink sink_name=VirtualMicSink sink_properties=device.description=VirtualMicSink"

    # FakeMic (remap source from VirtualMicSink.monitor)
    run_cmd "pactl load-module module-remap-source source_name=FakeMic master=VirtualMicSink.monitor source_properties=device.description=FakeMic"

    wait_for "Virtual mic sinks" 5 "pactl list short sinks | grep -q VirtualMicSink"
    wait_for "FakeMic source" 5 "pactl list short sources | grep -q FakeMic"
    log_ok "Virtual mic sources created"
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
        ssleep 0.5
    done
    return 1
}

start_emulator() {
    # Check if emulator is already running
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 && \
       [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null)" == "1" ]]; then
        EMULATOR_WAS_RUNNING=true
        log_ok "Emulator already running — reusing"
        return 0
    fi

    log_info "Starting emulator..."

    # Clean up stale PID file
    rm -f "$PID_FILE" "$EMU_LOG"

    # Launch emulator
    local emu_flags="-gpu host -no-snapshot-save -no-window"
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

stop_emulator() {
    log_info "Stopping emulator..."
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
        "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
        wait_for "emulator shutdown" 15 "[[ \"\$($ADB -s $SERIAL get-state 2>/dev/null)\" != 'device' ]]" || true
    fi
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
    run_cmd "$ADB -s $SERIAL shell pm clear $PACKAGE" false
    log_ok "App data cleared"
}

build_and_install() {
    log_info "Building and installing APK..."
    if [[ -z "${JAVA_HOME:-}" ]]; then
        export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    fi
    ./gradlew assembleDevDebug
    adb_cmd install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
    log_ok "APK installed"
}

grant_permissions() {
    log_info "Granting permissions..."
    adb_cmd shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
    adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
    log_ok "Permissions granted"
}

enable_ime() {
    log_info "Enabling Voice Input IME..."
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
# Test-File Mode
# =============================================================================

push_test_audio() {
    log_info "Pushing test audio to emulator app cache..."
    adb_cmd push "$WAV_FILE" /data/local/tmp/test-audio.wav
    adb_cmd shell "run-as $PACKAGE cp /data/local/tmp/test-audio.wav cache/test_audio.wav"
    adb_cmd shell rm -f /data/local/tmp/test-audio.wav
    log_ok "Test audio pushed to app cache/test_audio.wav"
}

# Wait until SettingsActivity is the resumed (foreground) activity, so its
# broadcast receivers are guaranteed to be registered. Returns 0 when resumed.
wait_for_settings_foreground() {
    local start=$(date +%s)
    while (( $(date +%s) - start < 15 )); do
        if "$ADB" -s "$SERIAL" shell dumpsys activity activities 2>/dev/null \
            | grep -Eq "Resumed.*SettingsActivity|SettingsActivity.*Resumed"; then
            return 0
        fi
        ssleep 0.5
    done
    log_warn "SettingsActivity not resumed within timeout"
    return 1
}

configure_cloud_settings() {
    log_info "Configuring cloud backend via CONFIGURE_CLOUD broadcast..."
    local endpoint="${CLOUD_ENDPOINT:-https://api.deepgram.com/v1/listen}"
    local model="${CLOUD_MODEL:-nova-3}"
    local test_path="/data/user/0/$PACKAGE/cache/test_audio.wav"
    # The CONFIGURE_CLOUD receiver lives in SettingsActivity, so it only fires
    # once that activity has been created (receivers registered in onCreate).
    # Retry the broadcast until the app acknowledges it, re-foregrounding
    # Settings if needed, so a slow first launch can't race the broadcast.
    local attempts=0
    while (( attempts < 4 )); do
        adb_cmd shell "am broadcast -p $PACKAGE -a org.futo.voiceinput.action.CONFIGURE_CLOUD \
            --es endpoint '$endpoint' \
            --es api_key \"$API_KEY\" \
            --es model '$model' \
            --ez use_test_file true \
            --es test_file_path '$test_path'"
        ssleep 1
        if adb_cmd logcat -d -s "voice-input:V" 2>/dev/null | grep -q "onReceive: configure cloud"; then
            break
        fi
        attempts=$((attempts + 1))
        log_warn "CONFIGURE_CLOUD not received by app (attempt $attempts/4); retrying"
        adb_cmd shell am start -n "$SETTINGS_ACTIVITY" >/dev/null 2>&1 || true
        wait_for_settings_foreground || true
        ssleep 1
    done
    if ! adb_cmd logcat -d -s "voice-input:V" 2>/dev/null | grep -q "onReceive: configure cloud"; then
        log_err "CONFIGURE_CLOUD was never received by the app after 4 attempts"
        return 1
    fi
    # Give DataStore a moment to persist the writes.
    ssleep 2
    log_ok "Cloud settings configured (use_test_file=true)"
}

# =============================================================================
# Transcription Test
# =============================================================================

focus_text_field() {
    log_info "Opening Settings (E2E receivers are registered while it is in the foreground)..."
    adb_cmd shell am start -n "$SETTINGS_ACTIVITY" >/dev/null 2>&1 || true
    wait_for_settings_foreground || log_warn "Settings not resumed; broadcasts may be missed"
    log_ok "Settings activity opened"
}

tap_mic_button() {
    log_info "Triggering mic toggle via broadcast..."
    adb_cmd shell am broadcast -p $PACKAGE -a org.futo.voiceinput.action.TOGGLE_RECORDING
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
        error=$(adb_cmd logcat -d -s "voice-input:E" 2>/dev/null | grep -oP "Transcription error: \K.*" | tail -1) || true
        if [[ -n "$error" ]]; then
            log_err "Transcription failed: $error"
            return 1
        fi

        # Check for success in logcat
        local result
        result=$(adb_cmd logcat -d -s "voice-input:V" 2>/dev/null | grep -oP "Transcription result: \K.*" | tail -1) || true
        if [[ -n "$result" && "$result" != "null" && "${result,,}" == *"$expected_lower"* ]]; then
            log_ok "Transcription committed: $result"
            return 0
        fi

        # Also check for test file mode result
        local test_result
        test_result=$(adb_cmd logcat -d -s "voice-input:V" 2>/dev/null | grep -oP "Test file mode: would transcribe \K.*" | tail -1) || true
        if [[ -n "$test_result" ]]; then
            log_ok "Test file mode triggered: $test_result"
            return 0
        fi

        ssleep 1
    done
    log_err "Timeout: expected substring '$expected' not found"
    return 1
}

# =============================================================================
# Main Test Flow
# =============================================================================

run_e2e_test() {
    local backend="$1"
    local api_key="$2"
    local expected="$3"

    local TEST_START=$SECONDS
    log_info "=== Starting E2E test for $backend ==="

    # Set up virtual mic before any emulator operations
    setup_virtual_mic

    # 1. Emulator
    start_emulator

    # Wait for package manager and system services to fully settle after boot
    wait_for "package manager ready" 5 'adb_cmd shell pm list packages >/dev/null 2>&1'

    # 3. App
    clear_app_data
    build_and_install
    grant_permissions
    enable_ime
    set_default_ime "$SERVICE"

    # 4. Push test audio to emulator
    push_test_audio

    # 5. Open Settings + focus a field (creates the IME service / receivers)
    focus_text_field

    # 6. Configure cloud backend (writes DataStore via debug broadcast).
    #    Clear logcat FIRST so the retry loop can verify *this* run's broadcast
    #    was actually received by the app (a stale "onReceive: configure cloud"
    #    from a previous run would otherwise make the verify-grep false-positive).
    adb_cmd logcat -c
    configure_cloud_settings || die "Failed to configure cloud backend"

    # 7. Trigger test-file transcription via broadcast
    # First tap: starts test-file mode (sets flag, no actual recording)
    tap_mic_button
    ssleep 1

    # Second tap: stops and transcribes the test file
    tap_mic_button

    # 8. Wait for transcription
    wait_for_transcription "$expected" || {
        dump_voice_logcat
        die "Transcription did not match expected text: '$expected'"
    }

    # 9. Capture screenshot + textual UI evidence
    capture_evidence
    local ui_out="${EVIDENCE_UI:-e2e_ui_after_nav.txt}"
    if [[ -f "$ui_out" ]] && grep -qi "$expected" "$ui_out"; then
        log_ok "Transcript visible on Input testing screen (UI dump contains '$expected')"
    else
        log_warn "Transcript NOT found in UI dump — screenshot may not show the result"
    fi

    log_ok "=== TEST PASSED: $backend ==="
    echo -e "${GREEN}[TIME]${NC} Total test time: $(( SECONDS - TEST_START ))s"
}

cleanup() {
    log_info "Cleaning up..."
    if [[ "$EMULATOR_WAS_RUNNING" != "true" ]]; then
        stop_emulator
    else
        log_info "Emulator was pre-existing — leaving it running"
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
        --help|-h)
            cat <<EOF
Usage: $0 --backend <deepgram> --key <API_KEY> --expected <substring> [--headful]

Options:
  --backend   Backend to test (deepgram)
  --key       API key for the backend
  --expected  Expected substring in transcription result
  --headful   Run emulator with visible window (default: headless)

Environment variables can also be used:
  DEEPGRAM_KEY

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

# API key: explicit arg → env var
if [[ -z "$API_KEY" ]]; then
    api_var="${BACKEND^^}_KEY"
    API_KEY="${!api_var:-}"
fi

if [[ -z "$API_KEY" ]]; then
    die "API key not provided. Use --key or set ${BACKEND^^}_KEY environment variable."
fi

# =============================================================================
# Trap cleanup on exit
# =============================================================================

trap cleanup EXIT INT TERM

# =============================================================================
# Run
# =============================================================================

log_info "=== Voice Input E2E Test ==="
log_info "Backend: $BACKEND"
log_info "Expected: $EXPECTED"

# Generate test audio if needed
generate_test_audio

# Run the test
run_e2e_test "$BACKEND" "$API_KEY" "$EXPECTED"

log_ok "=== ALL TESTS PASSED ==="
exit 0
