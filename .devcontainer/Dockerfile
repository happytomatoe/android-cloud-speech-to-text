# Bake the Whisper-to-Input (Android) dev stack into an image.
#
# Base is the Codespaces *universal* image on purpose: that is the image
# GitHub Codespaces uses by default, and it is what makes native
# `gh codespace ssh` work. Baking our stack on top of it (instead of a
# slimmer base like devcontainers/base:ubuntu) gives the best chance of
# keeping native SSH tunneling working when this image is used as the
# devcontainer `image`.
FROM mcr.microsoft.com/devcontainers/universal:2

# node/npm come from nvm on the universal image; make them available in RUNs.
ENV PATH="/usr/local/share/nvm/current/bin:${PATH}"
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_PATH=/opt/android-sdk

USER root

# Base tooling: JDK 17 + audio (espeak/ffmpeg) + utils.
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless unzip curl git ca-certificates python3-pip \
        espeak-ng ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# pi coding agent — install system-wide (world-readable) so the non-root
# `codespace` user can run it. With npm prefix /usr/local the bin lands in
# /usr/local/bin/pi, on PATH for every user.
RUN npm config set prefix /usr/local \
    && npm install -g @earendil-works/pi-coding-agent

# Android SDK: cmdline-tools + platform-tools + build-tools 34 + platform 34
# + emulator + API 35 x86_64 system image (~1.2 GB download).
RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && cd /tmp \
    && curl -fsS -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q cmdtools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm -f cmdtools.zip \
    && export PATH=/opt/android-sdk/cmdline-tools/latest/bin:$PATH \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "emulator" "system-images;android-35;default;x86_64" \
    && chmod -R a+rX /opt/android-sdk

# Project tooling: just, hs (curl from public release; gh is unauthed in
# codespaces), pre-commit (system-wide via --break-system-packages).
RUN curl --proto '=https' --tlsv1.2 -sSf https://just.systems/install.sh | bash -s -- --to /usr/local/bin \
    && curl -fsSL -o /tmp/hs.tar.gz https://github.com/elliotgao2/handsets/releases/download/v0.1.38/handsets-linux-x86_64.tar.gz \
    && mkdir -p /usr/local/bin && tar xzf /tmp/hs.tar.gz -C /usr/local/bin --strip-components=1 \
    && rm -f /tmp/hs.tar.gz \
    && python3 -m pip install --break-system-packages pre-commit

# Persist Android env for login shells.
RUN printf 'export ANDROID_SDK_ROOT=/opt/android-sdk\nexport ANDROID_HOME=/opt/android-sdk\nexport ANDROID_PATH=/opt/android-sdk\nexport PATH=/opt/android-sdk/platform-tools:$PATH\nexport PATH=/opt/android-sdk/emulator:$PATH\nexport PATH=/opt/android-sdk/cmdline-tools/latest/bin:$PATH\n' > /etc/profile.d/android-sdk.sh
