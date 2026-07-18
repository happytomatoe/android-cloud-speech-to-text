#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

# ── Android SDK location ───────────────────────────────────────────
# The devcontainers/android feature sets ANDROID_SDK_ROOT; fall back to
# sdkmanager on PATH, then the feature's conventional default path.
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif command -v sdkmanager >/dev/null 2>&1; then
  SDK="$(dirname "$(dirname "$(readlink -f "$(command -v sdkmanager)")")")"
else
  SDK="/opt/android-sdk"
fi
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export ANDROID_PATH="$SDK"   # consumed by the justfile

# Persist for every future shell (login + non-login) and this session.
cat > /etc/profile.d/android-sdk.sh <<EOF
export ANDROID_SDK_ROOT=$SDK
export ANDROID_HOME=$SDK
export ANDROID_PATH=$SDK
EOF
echo "export ANDROID_SDK_ROOT=$SDK" >> "$HOME/.bashrc"
echo "export ANDROID_PATH=$SDK" >> "$HOME/.bashrc"

# Gradle reads sdk.dir from local.properties OR ANDROID_SDK_ROOT. Generate it
# to be safe. android/local.properties is gitignored, so this stays local.
echo "sdk.dir=$SDK" > android/local.properties

# Gradle runs `git describe` at config time; avoid "dubious ownership" errors
# (container uid differs from the repo owner).
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
  python3 -m pip install --user pre-commit || pip install --user pre-commit
fi

# Audio tooling for E2E test generation
sudo apt-get update
sudo apt-get install -y espeak-ng ffmpeg

# Install the project's git hooks (pre-commit + commit-msg).
# NOTE: the push hook runs `just test-all` (includes E2E/emulator). On the
# default 2-vCPU Codespace without KVM that will fail — run `just test` /
# `just build` directly, or push with --no-verify. Guarded so a failure here
# doesn't block codespace creation.
just setup-hooks || echo "⚠️  just setup-hooks failed — run it manually if you need git hooks"

echo "✅ Codespace dev environment ready (ANDROID_PATH=$SDK)"
