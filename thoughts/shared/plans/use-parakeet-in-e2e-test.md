# Use Parakeet for E2E Testing

## Overview

Add Parakeet (local NVIDIA ASR running in Podman) as a backend option for E2E transcription testing. This eliminates the need for cloud API keys during testing — Parakeet runs locally on port 5092 with zero cost and no rate limits.

## Current State Analysis

- **Parakeet container** (`ghcr.io/achetronic/parakeet:latest`) is already running on `localhost:5092`
- **API format**: OpenAI-compatible (`POST /v1/audio/transcriptions`) — identical to Groq
- **Models available**: `parakeet-tdt-0.6b`, `whisper-1`
- **Tested and working**: `curl` confirms correct transcription with `response_format=text`
- **App already supports Groq** backend which uses the exact same request format (multipart with `file`, `model`, `response_format`)
- **No app code changes needed** — the Groq backend handles Parakeet's API perfectly

### Key Files:
- `run_e2e_test.sh` — main E2E test orchestrator (861 lines)
- `scripts/write_datastore.py` — writes DataStore preferences protobuf
- `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt` — builds transcription requests
- `android/app/src/main/java/com/example/whispertoinput/MainActivity.kt` — Settings UI with backend spinner

### Key Insight:
Parakeet uses Groq-compatible API format. The app's Groq backend sends:
- `file` field (multipart)
- `model` field
- `response_format=text`
- `Authorization: Bearer <key>` header

Parakeet accepts all of these and returns plain text when `response_format=text`. So we use **"Groq" as the backend display name** in the app, but point the endpoint to Parakeet.

## Desired End State

1. `run_e2e_test.sh --backend parakeet --expected "hello world"` runs a full E2E test using local Parakeet
2. `write_datastore.py --backend parakeet` generates correct DataStore preferences
3. No cloud API keys required for E2E testing
4. Existing backends (deepgram, groq, 60db, elevenlabs) continue working unchanged

## What We're NOT Doing

- Adding Parakeet as a selectable backend in the app's Settings UI (that's a separate feature)
- Modifying `WhisperTranscriber.kt` or any app Kotlin code
- Changing the Groq backend's default endpoint
- Adding Parakeet model selection UI

## Implementation Approach

**Strategy**: Add "parakeet" as a backend alias in the test scripts only. In the app, it uses "Groq" display name for correct request formatting. The endpoint is overridden to point to the local Parakeet server (`http://10.0.2.2:5092` from emulator).

---

## Phase 1: Add Parakeet to `write_datastore.py`

### Changes Required:

#### 1. `scripts/write_datastore.py`
**File**: `scripts/write_datastore.py`
**Changes**: Add "parakeet" to `BACKEND_CONFIG` and `backend_display` maps

```python
BACKEND_CONFIG = {
    # ... existing entries ...
    "parakeet": {
        "endpoint": "http://10.0.2.2:5092/v1/audio/transcriptions",
        "model": "parakeet-tdt-0.6b",
        "language_code": "",
    },
}

# In build_preferences():
backend_display = {
    # ... existing entries ...
    "parakeet": "Groq",  # Uses Groq request format
}
```

Also update the `choices` list in `argparse`:
```python
parser.add_argument("--backend", required=True,
    choices=["deepgram", "groq", "60db", "elevenlabs", "parakeet"])
```

### Success Criteria:
- [ ] `python3 scripts/write_datastore.py --backend parakeet --key dummy -o /tmp/pb` succeeds
- [ ] Generated protobuf contains endpoint `http://10.0.2.2:5092/v1/audio/transcriptions`
- [ ] Generated protobuf contains backend display name `Groq`

---

## Phase 2: Add Parakeet to `run_e2e_test.sh`

### Changes Required:

#### 1. `run_e2e_test.sh` — Backend configuration maps
**File**: `run_e2e_test.sh` (lines 62-81)
**Changes**: Add parakeet entries to all three `declare -A` maps

```bash
declare -A BACKEND_ENDPOINT=(
    # ... existing entries ...
    ["parakeet"]="http://10.0.2.2:5092/v1/audio/transcriptions"
)

declare -A BACKEND_MODEL=(
    # ... existing entries ...
    ["parakeet"]="parakeet-tdt-0.6b"
)

declare -A BACKEND_DISPLAY=(
    # ... existing entries ...
    ["parakeet"]="Groq"  # Uses Groq request format in app
)
```

#### 2. `run_e2e_test.sh` — API key handling
**File**: `run_e2e_test.sh` (around line 780)
**Changes**: Allow parakeet to use a dummy API key

```bash
# API key: explicit arg → env var → embedded default
if [[ -z "$API_KEY" ]]; then
    api_var="${BACKEND^^}_KEY"
    API_KEY="${!api_var:-}"
fi
if [[ -z "$API_KEY" && "$BACKEND" == "deepgram" ]]; then
    API_KEY="$DEEPGRAM_KEY_DEFAULT"
fi
# Parakeet doesn't need a real API key
if [[ -z "$API_KEY" && "$BACKEND" == "parakeet" ]]; then
    API_KEY="parakeet-local"
fi
```

### Success Criteria:
- [ ] `./run_e2e_test.sh --backend parakeet --expected "hello world"` runs without API key errors
- [ ] Script selects "Groq" backend in app UI (correct request format)
- [ ] Endpoint field shows Parakeet URL after backend selection
- [ ] Full transcription test passes end-to-end

---

## Phase 3: Verify End-to-End Flow

### Test Execution:

```bash
# 1. Verify Parakeet is running
curl http://localhost:5092/v1/models

# 2. Build APK
just build

# 3. Run E2E test with Parakeet
./run_e2e_test.sh --backend parakeet --expected "hello world this is a test of speech to text transcription"
```

### Success Criteria:

#### Automated Verification:
- [ ] `just build` succeeds
- [ ] Emulator boots successfully
- [ ] APK installs without errors
- [ ] IME enables and registers
- [ ] DataStore preferences written correctly
- [ ] Recording starts (detected in logcat)
- [ ] Test audio plays into virtual mic
- [ ] Transcription appears in text field
- [ ] Transcription contains expected substring

#### Manual Verification:
- [ ] Emulator window shows correct Settings configuration
- [ ] Debug output field shows transcription result
- [ ] No audio leaks to host speakers during test

---

## Testing Strategy

### Unit Test:
- Test `write_datastore.py --backend parakeet` generates valid protobuf
- Verify protobuf can be read by app's DataStore

### Integration Test:
- Full `run_e2e_test.sh --backend parakeet` run
- Verify Parakeet container is accessible from emulator (10.0.2.2:5092)

### Edge Cases:
- Parakeet container not running → clear error message
- Emulator can't reach host → network troubleshooting
- Empty transcription → retry logic

## Performance Considerations

- Parakeet runs on CPU (4 workers) — expect ~2-5s transcription latency
- No network round-trip to cloud — potentially faster than cloud backends
- Virtual mic setup adds ~5s to test startup

## References

- Parakeet container: `ghcr.io/achetronic/parakeet:latest`
- Existing E2E script: `run_e2e_test.sh`
- DataStore writer: `scripts/write_datastore.py`
- Request builder: `android/app/src/main/java/com/example/whispertoinput/WhisperTranscriber.kt:250-300`
