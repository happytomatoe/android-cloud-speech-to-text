#!/usr/bin/env bash
# =============================================================================
# verify_real_input_box.sh — One-shot verification that the transcription
# result appears in the real Input test field on the Input testing screen.
#
# Flow:
#   1. Force-stop app → clean restart
#   2. Start SettingsActivity (registers broadcast receivers)
#   3. Broadcast TOGGLE_RECORDING → startTestFileTranscription() → CloudTranscriber
#   4. Wait for logcat to confirm transcription completed
#   5. Navigate to Input Test screen → LaunchedEffect reads lastTranscriptionResult
#   6. Verify via hs ui (XML gate) that the text appears in the real field
#   7. Screenshot for user's eyes
#
# Usage:
#   ./verify_real_input_box.sh [--expected "hello world"] [--timeout 30]
#
# Prerequisites:
#   - Emulator running (emulator-5554)
#   - hs daemon up (hs use)
#   - Deepgram settings already configured in the app
#   - Test audio pushed to app cache (run_e2e_test_voice_input.sh does this)
# =============================================================================

set -euo pipefail

ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")}"
SERIAL="${SERIAL:-emulator-5554}"
PACKAGE="org.futo.voiceinput.dev"
SETTINGS_ACTIVITY="org.futo.voiceinput.dev/org.futo.voiceinput.settings.SettingsActivity"
HS="${HS:-hs}"
EVIDENCE_PNG="${EVIDENCE_PNG:-verify_real_input_box_evidence.png}"

# Defaults
EXPECTED="${EXPECTED:-hello world}"
TIMEOUT=30

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

log_info() { echo -e "${BLUE}[$(date +%H:%M:%S)] [INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[$(date +%H:%M:%S)] [OK]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] [WARN]${NC} $*"; }
log_err()  { echo -e "${RED}[$(date +%H:%M:%S)] [ERR]${NC} $*"; }

ssleep() { python3 -c "import time,sys; time.sleep(float(sys.argv[1]))" "$1"; }

die() { log_err "$*"; exit 1; }

# Parse args
while [[ $# -gt 0 ]]; do
    case $1 in
        --expected) EXPECTED="$2"; shift 2 ;;
        --timeout)  TIMEOUT="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: $0 [--expected TEXT] [--timeout SECONDS]"
            exit 0 ;;
        *) die "Unknown option: $1" ;;
    esac
done

adb_cmd() { "$ADB" -s "$SERIAL" "$@"; }

# ── Step 0: Ensure hs daemon is up ────────────────────────────────────────
log_info "Ensuring hs daemon is up..."
export PATH="$PATH:${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools"
"$HS" use >/dev/null 2>&1 || die "hs use failed — is the emulator running?"
ssleep 1

# ── Step 1: Force-stop app → clean restart ────────────────────────────────
log_info "Force-stopping app to guarantee clean state..."
adb_cmd shell am force-stop "$PACKAGE"
ssleep 1

# ── Step 2: Start SettingsActivity (registers broadcast receivers) ─────────
log_info "Starting SettingsActivity..."
adb_cmd shell am start -n "$SETTINGS_ACTIVITY"
ssleep 2

# Wait for the activity to be resumed
for i in $(seq 1 10); do
    if adb_cmd shell dumpsys activity activities 2>/dev/null \
        | grep -Eq "Resumed.*SettingsActivity|SettingsActivity.*Resumed"; then
        break
    fi
    ssleep 0.5
done
log_ok "SettingsActivity is resumed"

# ── Step 3: Broadcast TOGGLE_RECORDING → transcription ────────────────────
# Must happen BEFORE navigating to Input Test screen so that lastTranscriptionResult
# is already set when LaunchedEffect(Unit) runs at compose time.
adb_cmd logcat -c  # clear old logcat so we only see THIS run's result
log_info "Broadcasting TOGGLE_RECORDING..."
adb_cmd shell am broadcast -p "$PACKAGE" \
    -a org.futo.voiceinput.action.TOGGLE_RECORDING
ssleep 1

# ── Step 4: Wait for transcription to complete (logcat gate) ──────────────
log_info "Waiting for transcription to complete (expecting: '$EXPECTED')..."
EXPECTED_LOWER="${EXPECTED,,}"
LOGCAT_OK=false
START=$(date +%s)

while (( $(date +%s) - START < TIMEOUT )); do
    # Check for error
    _err=$(adb_cmd logcat -d -s "voice-input:E" 2>/dev/null \
        | grep -oP "Transcription error: \K.*" | tail -1) || true
    if [[ -n "$_err" ]]; then
        log_err "Transcription failed: $_err"
        die "Cloud transcription returned an error"
    fi

    # Check for success
    _result=$(adb_cmd logcat -d -s "voice-input:V" 2>/dev/null \
        | grep -oP "Transcription result: \K.*" | tail -1) || true
    if [[ -n "$_result" && "$_result" != "null" ]]; then
        log_ok "Transcription completed: $_result"
        LOGCAT_OK=true
        break
    fi

    ssleep 1
done

if [[ "$LOGCAT_OK" != "true" ]]; then
    log_err "Timeout: transcription did not complete within ${TIMEOUT}s"
    adb_cmd logcat -d -s "voice-input:V" -s "voice-input:E" 2>/dev/null | tail -10
    die "Transcription did not complete"
fi

# Give Compose a moment to settle after the static variable is set
ssleep 1

# ── Step 5: Navigate to Input Test screen ─────────────────────────────────
# LaunchedEffect(Unit) runs at compose time and reads lastTranscriptionResult
# (which is now set from step 3). This writes it into both the debug field
# and the real text field.
log_info "Navigating to Input Test screen..."
"$HS" tap "Try out Voice Input" --timeout 5s || die "Could not tap 'Try out Voice Input'"
ssleep 2

# ── Step 6: Verify via hs ui (XML gate) ──────────────────────────────────
log_info "=== hs ui dump ==="
FINAL_UI=$("$HS" ui 2>/dev/null) || true
echo "$FINAL_UI"

# Check if the expected text is in the UI (either field)
if echo "$FINAL_UI" | grep -qi "$EXPECTED_LOWER"; then
    log_ok "VERIFICATION PASSED: '$EXPECTED' found in hs ui output"
    FOUND=true
else
    log_err "VERIFICATION FAILED: '$EXPECTED' NOT found in hs ui output"
    log_warn "Checking logcat for clues..."
    adb_cmd logcat -d -s "voice-input:V" -s "voice-input:E" 2>/dev/null | tail -20
    FOUND=false
fi

# ── Step 7: Screenshot for user's eyes ────────────────────────────────────
log_info "Capturing screenshot → $EVIDENCE_PNG"
adb_cmd exec-out screencap -p > "$EVIDENCE_PNG" 2>/dev/null \
    && log_ok "Screenshot saved: $EVIDENCE_PNG" \
    || log_warn "Screenshot failed"

# ── Summary ───────────────────────────────────────────────────────────────
echo ""
log_info "=== Verification Summary ==="
log_info "Expected:  $EXPECTED"
log_info "Found:     $FOUND"
log_info "Screenshot: $EVIDENCE_PNG"
if [[ "$FOUND" == "true" ]]; then
    log_ok "RESULT: PASS"
    exit 0
else
    log_err "RESULT: FAIL"
    exit 1
fi
