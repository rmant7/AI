#!/usr/bin/env bash
# Local equivalent of the "build" job in .github/workflows/android.yml,
# meant to run inside the Codespace .devcontainer/devcontainer.json sets up
# (or anywhere else with the Android SDK already on ANDROID_HOME/ANDROID_SDK_ROOT).
#
# Deliberately stops after producing the APK. It does not create a GitHub
# release or force-push the ci-status/ci-smoke branches the way the CI job
# does — those are visible, one-way actions gated behind an explicit
# confirmation in scripts/publish-release.sh instead of happening by default
# just because a build succeeded.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== :core:test :openai:test =="
./gradlew --no-daemon :core:test :openai:test

echo "== :app:assembleDebug =="
./gradlew --no-daemon :app:assembleDebug

apk="$(find app/build/outputs/apk/debug -name '*.apk' | head -n1)"
if [ -z "$apk" ]; then
  echo "Build reported success but no APK was found under app/build/outputs/apk/debug" >&2
  exit 1
fi

out="localAI.apk"
cp "$apk" "$out"
echo "APK built: $out (from commit $(git rev-parse --short=7 HEAD))"
