#!/usr/bin/env bash
# Publishes a debug APK built by scripts/build.sh as a GitHub release,
# mirroring the "Publish the APK as a release asset" step in
# .github/workflows/android.yml.
#
# Not run automatically by build.sh: creating a public (pre)release is a
# visible, one-way action, so unlike the CI job — which is not run by a human
# and does not need one — this asks for an explicit confirmation first.
#
# This workflow reads no repository secrets (it only uses the automatic
# github.token), so there is nothing to add under Codespaces' Secrets
# settings for it. If you add steps that do need a secret later, remember
# Codespaces secrets are a separate store from Actions secrets even when the
# variable name matches — set them under
# Settings -> Secrets and variables -> Codespaces, not Actions.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${GH_TOKEN:?Set GH_TOKEN to a token with repo scope before running this script (the gh CLI reads it automatically).}"

apk="$(find . -maxdepth 1 -name 'local-ai-studio-local-*.apk' | head -n1)"
if [ -z "$apk" ]; then
  echo "No local build found — run scripts/build.sh first." >&2
  exit 1
fi

sha="$(git rev-parse HEAD)"
run_label="local-$(date +%s)"
remote="$(git remote get-url origin 2>/dev/null || echo '<no origin configured>')"

echo "About to create a public prerelease 'apk-${run_label}' on ${remote}"
echo "  from commit ${sha}, asset ${apk}"
read -r -p "Continue? [y/N] " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
  echo "Aborted."
  exit 1
fi

gh release create "apk-${run_label}" "$apk" \
  --title "Local AI Studio debug APK (${run_label})" \
  --notes "Debug build of ${sha}, built locally via scripts/build.sh rather than GitHub Actions." \
  --prerelease
