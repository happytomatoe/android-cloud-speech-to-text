#!/usr/bin/env bash
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
export DEBIAN_FRONTEND=noninteractive

SDK_ROOT="/opt/android-sdk"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_PATH="$SDK_ROOT"

# ── System packages: JDK 17 + audio tooling ───────────────────────
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  openjdk-17-jdk-headless unzip curl git python3-pip ca-certificates \
  espeak-ng ffmpeg
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
echo "export JAVA_HOME=$JAVA_HOME" >> "$HOME/.bashrc"

# ── Android SDK commandline-tools ─────────────────────────────────
mkdir -p "$SDK_ROOT/cmdline-tools"
cd /tmp
curl -fsS -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
rm -f cmdtools.zip
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --licenses >/dev/null
sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "emulator" \
  "system-images;android-35;default;x86_64"

# ── Persist the environment for every future shell ────────────────
cat > /etc/profile.d/android-sdk.sh <<EOF
export ANDROID_SDK_ROOT=$SDK_ROOT
export ANDROID_HOME=$SDK_ROOT
export ANDROID_PATH=$SDK_ROOT
export PATH=$SDK_ROOT/platform-tools:\$PATH
export PATH=$SDK_ROOT/emulator:\$PATH
export PATH=$SDK_ROOT/cmdline-tools/latest/bin:\$PATH
EOF
echo "export ANDROID_PATH=$SDK_ROOT" >> "$HOME/.bashrc"

# Gradle reads sdk.dir from local.properties OR ANDROID_SDK_ROOT. Generate it
# to be safe. android/local.properties is gitignored, so this stays local.
echo "sdk.dir=$SDK_ROOT" > android/local.properties

# Gradle runs `git describe` at config time; avoid "dubious ownership".
git config --global --add safe.directory '*'

# ── Tooling the project needs (mirrors .github/workflows/ci.yml) ──

# just — command runner
if ! command -v just >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://just.systems/install.sh | bash -s -- --to ~/.local/bin
fi

# handsets (hs) — UI automation for E2E; pin to the version CI uses
HS_VERSION="v0.1.38"
if ! command -v hs >/dev/null 2>&1; then
  gh release download "$HS_VERSION" -R elliotgao2/handsets \
    --pattern handsets-linux-x86_64.tar.gz -D /tmp/hs-dl --skip-existing
  mkdir -p ~/.local/bin
  tar xzf "/tmp/hs-dl/handsets-linux-x86_64.tar.gz" -C ~/.local/bin --strip-components=1
fi

# pre-commit — git hooks
if ! command -v pre-commit >/dev/null 2>&1; then
  python3 -m pip install --user pre-commit
fi

# Install the project's git hooks (pre-commit + commit-msg).
# NOTE: the push hook runs `just test-all` (includes E2E/emulator). On the
# default 2-vCPU Codespace without KVM that will fail — run `just test` /
# `just build` directly, or push with --no-verify. Guarded so a failure here
# doesn't block codespace creation.
just setup-hooks || echo "⚠️  just setup-hooks failed — run it manually if you need git hooks"

echo "✅ Codespace dev environment ready (ANDROID_PATH=$SDK_ROOT)"

# Install pi coding agent globally
echo "📦 Installing pi coding agent..."
npm install -g @earendil-works/pi-coding-agent
echo "✅ pi coding agent installed"

