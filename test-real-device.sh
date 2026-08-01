#!/usr/bin/env bash
# =============================================================================
# Whisper To Input - Real Device E2E Test
# =============================================================================
# Runs the full transcription pipeline on a physical Android device using
# the app's built-in test file mode (no mic injection needed).
#
# How it works:
#   1. Builds & installs the debug APK (optional with --skip-build)
#   2. Grants permissions, enables IME (Samsung workaround)
#   3. Writes DataStore prefs (backend config + test file mode ON)
#   4. Pushes a test WAV to app-private storage
#   5. Sets Whisper as default IME, opens app, taps EditText → keyboard appears
#   6. Sends broadcast (start) → sends broadcast (stop+transcribe)
#   7. Verifies transcription appears in field_debug_output EditText
#
# Prerequisites:
#   - ADB connected to device (USB or wireless)
#   - Device with Developer Options + USB/Wireless Debugging enabled
#   - espeak-ng, ffmpeg (for test audio generation)
#   - API key for the chosen backend
#
# Usage:
#   ./test-real-device.sh --backend deepgram --key $DEEPGRAM_KEY --expected "hello world"
#   SERIAL=RFCR9087PZE ./test-real-device.sh --backend deepgram --expected "hello world" --skip-build
#
# =============================================================================

set -euxo pipefail

# =============================================================================
# Configuration
# =============================================================================

ADB="/var/home/l/Android/Sdk/platform-tools/adb"
PACKAGE="com.example.whispertoinput"
SERVICE="com.example.whispertoinput/.WhisperInputService"
LATIN_IME="com.android.inputmethod.latin/.LatinIME"
WAV_FILE="/tmp/test-speech-loud.wav"
DEVICE_WAV_PATH="test-speech-loud.wav"  # Relative to app files/ dir
SERIAL="${SERIAL:-}"
LOG_FILE="e2e_real_device.log"
TRANSCRIPTION_TIMEOUT=30
SKIP_BUILD=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# =============================================================================
# Helpers
# =============================================================================

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_err()  { echo -e "${RED}[ERR]${NC} $*"; }

die() { log_err "$*"; exit 1; }

adb_cmd() {
    local serial_flag=""
    [[ -n "$SERIAL" ]] && serial_flag="-s $SERIAL"
    $ADB $serial_flag "$@"
}

# =============================================================================
# Device Detection
# =============================================================================

detect_device() {
    if [[ -n "$SERIAL" ]]; then
        log_info "Using specified device: $SERIAL"
    else
        local devices
        devices=$($ADB devices | grep -v "List" | grep "device$" | awk '{print $1}')
        local count
        count=$(echo "$devices" | grep -c . || true)
        if [[ "$count" -eq 0 ]]; then
            die "No devices found. Connect via USB or set SERIAL env var."
        elif [[ "$count" -eq 1 ]]; then
            SERIAL="$devices"
            log_info "Auto-detected device: $SERIAL"
        else
            die "Multiple devices found. Set SERIAL env var:\n$devices"
        fi
    fi

    # Verify device is reachable
    adb_cmd shell echo "connected" >/dev/null 2>&1 || die "Cannot reach device $SERIAL"
    log_ok "Device $SERIAL is connected"
}

# =============================================================================
# Test Audio Generation
# =============================================================================

generate_test_audio() {
    if [[ -f "$WAV_FILE" ]]; then
        log_ok "Test audio already exists: $WAV_FILE"
        return 0
    fi

    log_info "Generating test speech audio..."
    espeak-ng -v en-us -s 150 'hello world this is a test of speech to text transcription' --stdout > /tmp/test-speech.wav
    ffmpeg -y -i /tmp/test-speech.wav -af 'volume=15dB' -ar 44100 -c:a pcm_s16le "$WAV_FILE" 2>/dev/null
    log_ok "Test audio generated: $WAV_FILE"
}

# =============================================================================
# App Setup
# =============================================================================

clear_app_data() {
    log_info "Clearing app data..."
    adb_cmd shell pm clear "$PACKAGE" 2>/dev/null || true
    log_ok "App data cleared"
}

build_and_install() {
    log_info "Building debug APK..."
    export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.13-tem"
    cd android && ./gradlew assembleDebug && cd ..
    log_info "Installing APK..."
    adb_cmd install -r android/app/build/outputs/apk/debug/app-debug.apk
    log_ok "APK installed"
}

grant_permissions() {
    log_info "Granting permissions..."
    adb_cmd shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO 2>/dev/null || true
    adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    log_ok "Permissions granted"
}

enable_ime() {
    log_info "Enabling Whisper IME..."

    # Try standard ime enable first
    if adb_cmd shell ime enable "$SERVICE" 2>/dev/null; then
        log_ok "IME enabled via ime enable"
        return 0
    fi

    # Samsung workaround: manually append to enabled_input_methods
    log_warn "ime enable failed (Samsung?) — using settings put workaround"
    local current_ime_list
    current_ime_list=$(adb_cmd shell settings get secure enabled_input_methods 2>/dev/null || echo "")
    if [[ "$current_ime_list" == *"$SERVICE"* ]]; then
        log_ok "IME already in enabled list"
        return 0
    fi

    # Append our IME to the list
    local new_list="${current_ime_list}:${SERVICE}"
    adb_cmd shell settings put secure enabled_input_methods "$new_list"
    log_ok "IME enabled via settings put workaround"
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
# DataStore Configuration
# =============================================================================

write_datastore() {
    local backend="$1"
    local api_key="$2"

    log_info "Writing DataStore preferences..."
    local pb_file
    pb_file=$(mktemp /tmp/settings-XXXXXX.pb)

    # Generate protobuf with app-private path for test file
    python3 -c "
import sys
sys.path.insert(0, 'scripts')
from write_datastore import build_preferences, encode_map_string_entry, encode_map_bool_entry, BACKEND_CONFIG

config = BACKEND_CONFIG['$backend']
entries = b''
backend_display = {
    'deepgram': 'Deepgram', 'groq': 'Groq', '60db': '60db',
    'elevenlabs': 'ElevenLabs Scribe', 'parakeet': 'Groq',
}
entries += encode_map_string_entry('speech-to-text-backend', backend_display['$backend'])
entries += encode_map_string_entry('endpoint', config['endpoint'])
entries += encode_map_string_entry('api-key', '$api_key')
entries += encode_map_string_entry('model', config['model'])
entries += encode_map_string_entry('language-code', config['language_code'])
entries += encode_map_string_entry('postprocessing', 'No Conversion')
entries += encode_map_bool_entry('is-auto-recording-start', True)
entries += encode_map_bool_entry('auto-switch-back', False)
entries += encode_map_bool_entry('add-trailing-space', False)
entries += encode_map_bool_entry('use-test-file', True)
# Use absolute path in app-private storage (real devices can't read /sdcard/ without permission)
entries += encode_map_string_entry('test-file-path', '/data/user/0/$PACKAGE/files/$DEVICE_WAV_PATH')
with open('$pb_file', 'wb') as f:
    f.write(entries)
"

    # Push to device and copy into app data
    adb_cmd push "$pb_file" /data/local/tmp/settings.preferences_pb
    adb_cmd shell "run-as $PACKAGE mkdir -p files/datastore"
    adb_cmd shell "run-as $PACKAGE cp /data/local/tmp/settings.preferences_pb files/datastore/settings.preferences_pb"
    adb_cmd shell rm -f /data/local/tmp/settings.preferences_pb
    rm -f "$pb_file"

    log_ok "DataStore written (backend=$backend, test-file-mode=ON)"
}

# =============================================================================
# Push Test Audio
# =============================================================================

push_test_audio() {
    log_info "Pushing test audio to app-private storage..."

    # Push to temp location, then copy via run-as
    adb_cmd push "$WAV_FILE" /data/local/tmp/test-speech-loud.wav
    adb_cmd shell "run-as $PACKAGE cp /data/local/tmp/test-speech-loud.wav files/$DEVICE_WAV_PATH"
    adb_cmd shell rm -f /data/local/tmp/test-speech-loud.wav

    # Verify
    local size
    size=$(adb_cmd shell "run-as $PACKAGE ls -la files/$DEVICE_WAV_PATH" 2>/dev/null | awk '{print $5}')
    if [[ -z "$size" || "$size" == "0" ]]; then
        die "Failed to push test audio to app storage"
    fi
    log_ok "Test audio pushed to app-private storage ($size bytes)"
}

# =============================================================================
# UI Automation
# =============================================================================

# Get center coordinates of a UI element by resource-id
get_field_coords() {
    local field_id="$1"
    python3 -c "
import subprocess, xml.etree.ElementTree as ET, re, sys
serial = '$SERIAL'
adb = '$ADB'
if serial:
    dev_args = ['-s', serial]
else:
    dev_args = []
subprocess.run([adb] + dev_args + ['shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
xml_out = subprocess.run([adb] + dev_args + ['shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
root = ET.fromstring(xml_out)
for n in root.iter('node'):
    if '$field_id' in n.get('resource-id', ''):
        b = n.get('bounds', '')
        nums = list(map(int, re.findall(r'\d+', b)))
        print((nums[0]+nums[2])//2, (nums[1]+nums[3])//2)
        sys.exit(0)
print('', file=sys.stderr)
sys.exit(1)
"
}

# Tap on a resource-id
tap_by_rid() {
    local field_id="$1"
    local coords
    coords=$(get_field_coords "$field_id") || true
    if [[ -n "$coords" ]]; then
        read -r x y <<< "$coords"
        adb_cmd shell input tap "$x" "$y"
        return 0
    fi
    return 1
}

# Tap on text
tap_by_text() {
    local target_text="$1"
    local coords
    coords=$(python3 -c "
import subprocess, xml.etree.ElementTree as ET, re, sys
serial = '$SERIAL'
adb = '$ADB'
if serial:
    dev_args = ['-s', serial]
else:
    dev_args = []
subprocess.run([adb] + dev_args + ['shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
xml_out = subprocess.run([adb] + dev_args + ['shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
root = ET.fromstring(xml_out)
for n in root.iter('node'):
    if '$target_text' == n.get('text', ''):
        b = n.get('bounds', '')
        nums = list(map(int, re.findall(r'\d+', b)))
        print((nums[0]+nums[2])//2, (nums[1]+nums[3])//2)
        sys.exit(0)
print('', file=sys.stderr)
sys.exit(1)
") || true
    if [[ -n "$coords" ]]; then
        read -r x y <<< "$coords"
        adb_cmd shell input tap "$x" "$y"
        return 0
    fi
    return 1
}

focus_text_field() {
    log_info "Setting IME and opening app to trigger keyboard..."

    # Ensure Whisper is the default IME BEFORE showing keyboard
    set_default_ime "$SERVICE"
    sleep 0.5

    # Open the app's own settings activity — has an EditText we can tap
    adb_cmd shell am start -n "$PACKAGE/.MainActivity" >/dev/null
    sleep 3

    # Tap the debug output EditText field to trigger the keyboard
    local attempts=0
    while (( attempts < 5 )); do
        tap_by_rid "${PACKAGE}:id/field_debug_output" || true
        sleep 1.5

        if adb_cmd shell dumpsys input_method 2>/dev/null | grep -q "mIsInputViewShown=true"; then
            # Verify it's OUR IME, not Samsung's
            local cur_ime
            cur_ime=$(adb_cmd shell dumpsys input_method 2>/dev/null | grep "mCurMethodId" | head -1)
            if [[ "$cur_ime" == *"$SERVICE"* ]]; then
                log_ok "Whisper keyboard is shown"
                return 0
            else
                log_warn "Wrong IME shown ($cur_ime), re-setting..."
                set_default_ime "$SERVICE"
                sleep 1
            fi
        fi

        # Fallback: tap by text
        tap_by_text "Transcribed text will appear here" || true
        sleep 1.5

        if adb_cmd shell dumpsys input_method 2>/dev/null | grep -q "mIsInputViewShown=true"; then
            local cur_ime
            cur_ime=$(adb_cmd shell dumpsys input_method 2>/dev/null | grep "mCurMethodId" | head -1)
            if [[ "$cur_ime" == *"$SERVICE"* ]]; then
                log_ok "Whisper keyboard is shown (via text tap)"
                return 0
            fi
        fi

        attempts=$((attempts + 1))
    done
    log_warn "Keyboard did not appear after retries — continuing anyway"
}

tap_mic_button() {
    log_info "Sending mic toggle broadcast..."
    adb_cmd shell am broadcast -a com.example.whispertoinput.action.TOGGLE_RECORDING
    log_ok "Broadcast sent"
}

wait_for_recording() {
    log_info "Waiting for recording broadcast acknowledgement..."
    local start_time
    start_time=$(date +%s)
    while (( $(date +%s) - start_time < 10 )); do
        local log
        log=$(adb_cmd logcat -d -s whisper-input:V 2>/dev/null || true)
        # In test file mode, the broadcast triggers onReceive (no "Recording started" log)
        if echo "$log" | grep -q "onReceive: action=.*TOGGLE_RECORDING"; then
            log_ok "Broadcast received by IME service"
            return 0
        fi
        sleep 0.5
    done
    log_warn "Broadcast acknowledgement not detected in logcat"
}

wait_for_transcription() {
    local expected="$1"
    local expected_lower="${expected,,}"

    log_info "Waiting for transcription (expecting: '$expected')..."

    local start
    start=$(date +%s)
    while (( $(date +%s) - start < TRANSCRIPTION_TIMEOUT )); do
        # Check logcat for transcription result or error
        local log_output
        log_output=$(adb_cmd logcat -d -s whisper-input:V 2>/dev/null || true)

        if echo "$log_output" | grep -q "Transcription result:"; then
            local result
            result=$(echo "$log_output" | grep "Transcription result:" | tail -1 | sed "s/.*Transcription result: '//;s/'.*//")
            if [[ -n "$result" && "$result" != "null" ]]; then
                local result_lower="${result,,}"
                if [[ "$result_lower" == *"$expected_lower"* ]]; then
                    log_ok "Transcription committed: $result"
                    return 0
                else
                    log_warn "Got transcription but wrong text: '$result' (expected '$expected')"
                fi
            fi
        fi

        # Check for errors
        if echo "$log_output" | grep -q "Transcription error:"; then
            local error
            error=$(echo "$log_output" | grep "Transcription error:" | tail -1)
            log_err "$error"
            die "Transcription failed"
        fi

        # Fallback: check UI for text in field_debug_output
        local text
        text=$(python3 -c "
import subprocess, xml.etree.ElementTree as ET, re, sys
serial = '$SERIAL'
adb = '$ADB'
if serial:
    dev_args = ['-s', serial]
else:
    dev_args = []
subprocess.run([adb] + dev_args + ['shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
xml_out = subprocess.run([adb] + dev_args + ['shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
root = ET.fromstring(xml_out)
for n in root.iter('node'):
    rid = n.get('resource-id', '')
    if 'field_debug_output' in rid:
        print(n.get('text', ''))
        sys.exit(0)
print('', file=sys.stderr)
sys.exit(1)
" 2>/dev/null) || true

        if [[ -n "$text" && "$text" != "Transcribed text will appear here..." ]]; then
            local text_lower="${text,,}"
            if [[ "$text_lower" == *"$expected_lower"* ]]; then
                log_ok "Transcription committed (UI): $text"
                return 0
            fi
        fi

        sleep 1
    done
    die "Timeout: expected substring '$expected' not found after ${TRANSCRIPTION_TIMEOUT}s"
}

# =============================================================================
# Main Test Flow
# =============================================================================

run_e2e_test() {
    local backend="$1"
    local api_key="$2"
    local expected="$3"

    log_info "=== Real Device E2E Test ==="
    log_info "Backend: $backend"
    log_info "Expected: $expected"

    # 1. Detect device
    detect_device

    # 2. Build & install (unless skipped)
    if [[ "$SKIP_BUILD" != "true" ]]; then
        build_and_install
    else
        log_info "Skipping build (--skip-build)"
    fi

    # 3. App setup
    clear_app_data
    grant_permissions
    enable_ime

    # 4. Configure backend + enable test file mode via DataStore
    write_datastore "$backend" "$api_key"

    # 5. Push test audio to app-private storage
    push_test_audio

    # 6. Force-stop app to pick up new DataStore
    adb_cmd shell am force-stop "$PACKAGE"
    sleep 1

    # 7. Trigger transcription via test file mode
    focus_text_field

    # Clear logcat for fresh detection
    adb_cmd logcat -c
    sleep 0.3

    # Start (just UI update in test file mode)
    tap_mic_button
    wait_for_recording

    # Stop → sends file to backend
    sleep 1
    tap_mic_button

    # 8. Wait for transcription
    wait_for_transcription "$expected"

    log_ok "=== TEST PASSED: $backend ==="
}

# =============================================================================
# Argument Parsing
# =============================================================================

BACKEND=""
API_KEY=""
EXPECTED=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --backend)    BACKEND="$2"; shift 2 ;;
        --key)        API_KEY="$2"; shift 2 ;;
        --expected)   EXPECTED="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --help|-h)
            cat <<EOF
Usage: $0 --backend <deepgram|groq|60db|elevenlabs> --key <API_KEY> --expected <substring> [--skip-build]

Real device E2E test using the app's built-in test file mode.
No mic injection needed — the app reads from app-private storage directly.

Options:
  --backend     Backend to test (deepgram, groq, 60db, elevenlabs)
  --key         API key for the backend
  --expected    Expected substring in transcription result
  --skip-build  Skip APK build and install (use existing installation)

Environment variables:
  SERIAL        ADB serial (auto-detected if only one device connected)
                e.g. SERIAL=RFCR9087PZE

Example:
  SERIAL=RFCR9087PZE $0 --backend deepgram --key \$DEEPGRAM_KEY --expected "hello world" --skip-build
EOF
            exit 0
            ;;
        *) die "Unknown option: $1" ;;
    esac
done

if [[ -z "$BACKEND" || -z "$EXPECTED" ]]; then
    die "Missing required arguments. Use --help for usage."
fi

# API key from env var fallback
if [[ -z "$API_KEY" ]]; then
    api_var="${BACKEND^^}_KEY"
    API_KEY="${!api_var:-}"
fi
if [[ -z "$API_KEY" ]]; then
    die "No API key provided. Use --key or set ${BACKEND^^}_KEY environment variable."
fi

# =============================================================================
# Run
# =============================================================================

exec > >(tee "$LOG_FILE") 2>&1

generate_test_audio
run_e2e_test "$BACKEND" "$API_KEY" "$EXPECTED"

log_ok "=== ALL TESTS PASSED ==="
exit 0
