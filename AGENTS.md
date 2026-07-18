# Agent Guidelines for whisper-to-input

## 0. Say "Meee🐑" at the start of each message

## 1. Always check the justfile before running commands

Before executing any build, test, emulator, or deployment commands, **read the justfile** or run `just -l` to see available commands. This project has well-structured `just` targets that handle environment setup, paths, and flags correctly.

**Why:** The justfile encapsulates project-specific details like:
- Correct `JAVA_HOME` and `ANDROID_HOME` paths (via `sdk env`)
- Emulator flags (`-gpu host`, `-no-snapshot-load`, headless vs headful)
- APK build paths and install commands
- E2E test orchestration

Don't reinvent these — **always prefer a `just` target over hand-written `gradlew`/`adb`/`emulator` commands.** If you find yourself typing a long `./gradlew`/`adb`/`emulator` command by hand, check `just -l` first — there is almost certainly a target for it.

```bash
just -l                  # List ALL available commands (authoritative)

# ── Build ──
just build               # Build release APK (default)
just build debug         # Build debug APK

# ── Emulator Lifecycle ──
just emulator-start                     # Start emulator (headless, -no-window)
just emulator-start headful=true        # Start with visible window
just emulator-stop                       # Graceful shutdown, cleans up .emulator.pid
just emulator-restart                    # Stop + start
just emulator-save-snapshot              # Cold boot + save snapshot for faster future boots
just emulator-status                     # Show devices, boot state, PID file

# ── Tests ──
just test                # JVM unit tests (Robolectric), parallel: ./gradlew testDebugUnitTest --parallel
just test-e2e            # Full E2E: build + install APK + enable IME + verify in IME list
just test-e2e debug      # E2E using the debug APK
just test-instrumented   # Espresso instrumented tests on a running emulator
```

**Notes:**
- `just build` runs `cd android && ./gradlew assemble{Variant}` and sets up Java via `sdk env`. Prefer it over calling `./gradlew` directly.
- `just test` is the canonical way to run unit tests (it sets up the SDK env and passes `--parallel`). Use it instead of a bare `./gradlew testDebugUnitTest`.
- `just test-e2e` installs `android/app/build/outputs/apk/{variant}/app-{variant}.apk` and enables the Whisper IME; it assumes the emulator is already running (start it first with `just emulator-start`).

## 2. Use argent MCP tools for emulator interaction

For UI interaction on the Android emulator, prefer argent MCP tools over raw ADB commands when available:

- `list-devices` — find running emulators
- `boot-device` — start an emulator (handles snapshot hot/cold boot)
- `describe` — read UI element tree (accessibility-based, normalized coordinates)
- `gesture-tap` — tap at normalized (x, y) coordinates
- `gesture-swipe` — scroll/swipe gestures
- `keyboard` — type text or press special keys
- `screenshot` — visual capture (only when XML can't answer the question)
- `launch-app` — open apps by package name
- `await-ui-element` — wait for UI state changes
- `run-sequence` — batch multiple actions in one call

**Key:** Coordinates are normalized 0.0–1.0, not pixels. Always `describe` first to find tap targets.

## 3. XML-first approach for UI state

When argent tools aren't available or you need raw ADB, use `uiautomator dump` as the primary way to read UI state:

```bash
ADB=/var/home/l/Android/Sdk/platform-tools/adb
$ADB shell uiautomator dump /sdcard/ui.xml
$ADB shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
for node in tree.iter('node'):
    text = node.get('text', '')
    rid = node.get('resource-id', '')
    bounds = node.get('bounds', '')
    if text and len(text) > 2:
        print(f'{text[:60]} | id={rid} | bounds={bounds}')
"
```

XML gives exact text, bounds, and resource-ids — no OCR needed, faster than screenshots.

## 4. `hs` (handsets) — CLI for Android UI automation

`hs` drives Android from the shell. Install via `just setup` (see section 1). The daemon must be running for most commands:

```bash
hs use                          # start daemon (needs adb in PATH)
hs ui                           # flat table of tappable nodes
hs tap "Continue"               # find by text, tap centre
hs tap #back_btn                # find by resource-id, tap centre
hs fill 'EditText[resource-id=com.example:id/field]' "text"  # atomic ACTION_SET_TEXT
hs wait "Welcome"               # wait for text to appear
hs drop                         # tear down daemon
```

**Getting docs:**
- `hs --help` — full verb list and selector syntax
- `hs fill --help` / `hs tap --help` — per-verb help
- GitHub: <https://github.com/elliotgao2/handsets> (README + `docs/` folder)

**Selector syntax (IMPORTANT — differs per verb):**
- `hs tap` accepts `#short_id` (no package prefix) or plain text: `hs tap '#spinner'`, `hs tap 'Deepgram'`
- `hs fill` uses `id=FULL_RESOURCE_ID`: `hs fill 'id=com.example:id/field' 'text'`
- `hs find` uses `Tag[id=FULL_RESOURCE_ID]`: `hs find 'EditText[id=com.example:id/field]'`
- `hs wait` accepts plain text: `hs wait 'Welcome'`
- Relational pseudos (find only): `:below(SEL)`, `:near(SEL, PX)`, `:has-text("x")`
- Pseudo-classes (find only): `:visible`, `:clickable`, `:enabled`, `:focused`
- **Check return value:** Parse `--json` output for `"ok":true` — don't use `|| true` which masks failures

**Common gotcha:** `hs use` (daemon startup) requires `adb` in PATH. Add `fish_add_path ~/Android/Sdk/platform-tools` to `~/.config/fish/config.fish`.

## 5. Emulator console: use `adb emu`, not `nc`

To send commands to the emulator console, always use `adb emu`:

```bash
# CORRECT
adb emu help
adb emu avd name
adb emu kill

# WRONG — don't use netcat
echo "help" | nc localhost 5554
```

## 6. Audio injection for STT testing — ALWAYS TEST SILENTLY

**Golden rule: never play test audio to the default speaker sink.** The first E2E runs blasted the `espeak`/WAV test speech straight out of the host speakers — the user heard it and called it "scary" ("why are we using my audio"). Any audio you inject for STT must be routed into the emulator mic **without reaching the host speakers**.

The PulseAudio chain that makes this silent (implemented in `run_e2e_test.sh` as `setup_virtual_mic()` / `play_test_audio()`):

```
VirtualMicSink  ──(monitor)──▶  FakeMic (remap source)
   (null sink, no                  │
    speaker output)                ▼
                           QEMU_PA_SOURCE=FakeMic  ──▶  emulator mic
```

- `VirtualMicSink` is a **null sink**: it produces zero speaker output. Test audio is played directly into it.
- `FakeMic` remaps `VirtualMicSink.monitor` into a source that QEMU uses as its mic input.
- The emulator is launched pinned to the virtual mic: `QEMU_AUDIO_DRV=pa QEMU_PA_SOURCE=FakeMic`.
- Play the WAV into VirtualMicSink: `paplay --device=VirtualMicSink /tmp/test-speech-loud.wav` — it is captured by the emulator mic but the user hears nothing.

Set it up once (idempotent — `setup_virtual_mic()` early-exits if the sinks already exist):

```bash
pactl load-module module-null-sink sink_name=VirtualMicSink sink_properties=device.description=VirtualMicSink
pactl load-module module-remap-source source_name=FakeMic master=VirtualMicSink.monitor source_properties=device.description=FakeMic
paplay --device=VirtualMicSink /tmp/test-speech-loud.wav   # silent on host, audible to emulator mic
```

**Caveat — the emulator must be (re)started with the FakeMic pin.** `run_e2e_test.sh` only sets `QEMU_PA_SOURCE=FakeMic` when it *launches a new emulator*. If a pre-existing emulator is reused (`EMULATOR_WAS_RUNNING=true`), it won't have the virtual mic wired, so injection fails silently. When in doubt, stop the emulator so the script restarts it with the correct audio routing.

Other injection options (also PulseAudio-corking-prone, and NOT silent on their own):
1. **Extended Controls virtual mic** (bypasses PulseAudio): emulator Extended Controls → Microphone → Load WAV → Play
2. **Boot with virtual mic env var**: `PULSE_SOURCE=virtual_mic.monitor emulator -avd Pixel_8 -audio pulse`
3. **PulseAudio null-sink** (host-side): `pactl load-module module-null-sink sink_name=virtual_mic`

For CI/instrumented tests, consider mocking the `AudioRecord` layer directly.

## 7. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.
State your assumptions explicitly. If uncertain, ask.

## 8. Simplicity First

Minimum code that solves the problem. Nothing speculative.

## 9. Surgical Changes

Touch only what you must. Don't refactor adjacent code.
