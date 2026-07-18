---
date: 2026-07-18T09:30:57Z
git_commit: 43c55f60c78df2115b04cd87465c42654e61ee5b
branch: feat/api-key-links
repository: happytomatoe/android-cloud-speech-to-text
topic: "Pi Coding Agent DevContainer + Docker Image Build Pipeline"
tags: [devcontainer, codespaces, android, pi-coding-agent, docker, ci-cd, ssh]
---

# Handoff: Pi Coding Agent DevContainer Setup + Docker Image Build Pipeline

## Task(s)

**Primary objective**: Add the Pi Coding Agent to the GitHub Codespaces dev container for the Whisper-to-Input Android project, with a fully working dev environment including Android SDK (API 35 x86_64 emulator), pi, just, hs, pre-commit, and native `gh codespace ssh` support.

**Status**: 
- ✅ Core devcontainer working (pi 0.80.10, adb, just, hs, pre-commit, Android SDK 3.0GB)
- ✅ Native `gh codespace ssh` working on default universal image
- ✅ Baked Docker image built (`Dockerfile` on universal:2 base) + GH Actions workflow to push to Docker Hub
- ⏳ **Pending**: Add Docker Hub secrets, run workflow to push image, switch devcontainer.json to custom image, **test native SSH** (critical tradeoff)
- ⏳ Cleanup: bundled AGENTS.md/.editorconfig commit (1a2cddb)

## Critical References

1. **`.devcontainer/devcontainer.json`** — Working config: single-string `postCreateCommand`, no custom `image`, no `sshd` feature, pi extension, native SSH works
2. **`.devcontainer/Dockerfile`** — Bakes full stack on `mcr.microsoft.com/devcontainers/universal:2` (preserves native SSH)
3. **`.github/workflows/build-devcontainer-image.yml`** — Builds/pushes image to Docker Hub via secrets

## Recent changes

- `.devcontainer/devcontainer.json:12` — Single-string `postCreateCommand` with `cd /workspaces/*` for repo-relative steps, hs via curl, pi via user npm prefix + /usr/local/bin symlink
- `.devcontainer/Dockerfile` (new) — Bakes JDK 17, Android SDK (API 35 x86_64), pi, just, hs, pre-commit onto `universal:2`
- `.github/workflows/build-devcontainer-image.yml` (new) — GH Actions build/push to Docker Hub using `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` secrets
- `.devcontainer/setup.sh` (deleted) — Replaced by inline `postCreateCommand`

## Learnings

**SSH is the critical constraint**:
- Native `gh codespace ssh` **only works on the default universal image**. Any custom `image:` or `sshd` feature breaks it (`Permission denied (publickey,password)`).
- `mcr.microsoft.com/devcontainers/universal:2` base image preserves SSH; `mcr.microsoft.com/devcontainers/base:ubuntu` does NOT.
- Generic codespace on a repo without devcontainer.json → SSH works; our custom image must be based on universal:2 to have a chance.

**postCreateCommand pitfalls**:
- Must be a **single string**, NOT an array. Codespaces agent flattens arrays into one space-joined command and `exec`s it raw → `exec: no such file or directory`.
- In Codespaces, `postCreateCommand` runs in `$HOME` (`/home/codespace`), **NOT the workspace**. Repo-relative steps (`android/local.properties`, `just setup-hooks`) must `cd /workspaces/*` first.

**Authentication inside codespace**:
- `gh` CLI is **not authenticated** by default in the codespace → `gh release download` fails with auth error.
- Fix: use `curl` directly from public GitHub release assets (no auth needed for public releases).

**npm on nvm-based universal image**:
- `sudo npm install -g` installs to root's nvm prefix (not on user PATH). 
- Fix: use user-owned prefix `~/.npm-global` + symlink to `/usr/local/bin`, OR system prefix `/usr/local` (world-readable).

**Caching alternatives**:
- Prebuilds (Settings → Codespaces → Prebuilds) cache the postCreateCommand-installed SDK while keeping default image + native SSH.
- Custom Docker Hub image adds portability but risks SSH breakage; prebuilds are the zero-infra caching path.

## Artifacts

- `.devcontainer/devcontainer.json:12` — Single-string postCreateCommand (HALF 1 + HALF 2)
- `.devcontainer/Dockerfile` — Baked image on `universal:2` with full stack
- `.github/workflows/build-devcontainer-image.yml` — GH Actions build/push to Docker Hub
- Current working codespace: `ideal-fishstick-7rxvx7x47xcx6j7` (fully functional, live-patched)
- Git history: key commits `f915e54` (array fix), `1a2cddb` (cwd fix), `ebb9cdf` (Dockerfile + workflow)

## Action Items & Next Steps

1. **Add Docker Hub secrets** in repo Settings → Secrets → Actions:
   - `DOCKERHUB_USERNAME` (e.g., `happytomatoe`)
   - `DOCKERHUB_TOKEN` (Docker Hub access token with push rights)

2. **Trigger image build**: GH Actions workflow runs on push (or manual dispatch). After image pushed to `docker.io/happytomatoe/whisper-to-input-dev:latest`:

3. **Switch devcontainer.json to custom image** (minimal change):
   - Add `"image": "docker.io/happytomatoe/whisper-to-input-dev:latest"`
   - Reduce `postCreateCommand` to tiny repo-relative steps only (`cd /workspaces/* && echo 'sdk.dir=/opt/android-sdk' > android/local.properties && just setup-hooks`)
   - Commit + push

4. **CRITICAL: Test native SSH** on a fresh codespace with the custom image:
   - Create codespace → `gh codespace ssh -c <name> -- "pi --version && adb --version"`
   - If SSH breaks → fallback: prebuilds (recommended) or `sshd` feature (auth issues)

5. **Cleanup**: Commit `1a2cddb` bundled `AGENTS.md` + `.editorconfig` unintentionally. Split/revert (needs force-push or cleanup commit).

## Other Notes

- Working codespace available: `ideal-fishstick-7rxvx7x47xcx6j7` (connect via `gh codespace ssh -c ideal-fishstick-7rxvx7x47xcx6j7` or `gh codespace code -c ideal-fishstick-7rxvx7x47xcx6j7`). It has the full stack live-patched.
- Repository: `happytomatoe/android-cloud-speech-to-text` (origin), local dir `whisper-to-input`.
- Android project structure: repo root has `android/` subdirectory (Gradle project).
- Emulator/E2E: default 2-vCPU Codespace has no KVM → emulator won't run; need 4+ vCPU machine for E2E.
- Deepgram key: only needed for E2E, add as Codespace secret if needed.
- The `pre-commit` hook `test-all` uses deprecated `push` stage name (warning) — minor.

---

**Bottom line**: The devcontainer is solid and working. The Docker Hub image pipeline is ready to trigger. The only blocker is adding Docker Hub secrets and **validating SSH survives the custom image switch**. If it breaks, prebuilds are the safer caching path.