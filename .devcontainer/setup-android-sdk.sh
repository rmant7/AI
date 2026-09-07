#!/usr/bin/env bash
# Installs the Android SDK pieces app/build.gradle.kts needs, by hand.
#
# There is no equally-official-and-maintained devcontainer Feature for the
# Android SDK the way there is for Java, so this replicates what
# android-actions/setup-android + AGP's own auto-download normally do in CI:
# cmdline-tools, platform-tools, the compileSdk platform, and license
# acceptance so Gradle can pull the pinned CMake (3.22.1) and whatever NDK
# AGP 8.7.3 wants on its own during the first :app build.
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"

# If dl.google.com has retired this exact build, grab the current one from
# https://developer.android.com/studio#command-line-tools-only and update
# this constant — the rest of the script does not otherwise need to change.
CMDLINE_TOOLS_VERSION="11076708"

if ! command -v unzip >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y unzip
fi

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android cmdline-tools into $SDK_ROOT ..."
  tmp="$(mktemp -d)"
  curl -fsSL \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    -o "$tmp/cmdline-tools.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  # sdkmanager insists on this exact "cmdline-tools/<name>/bin" shape.
  mkdir -p "$SDK_ROOT/cmdline-tools"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null 2>&1 || true

# compileSdk/targetSdk 35 per app/build.gradle.kts. Deliberately not pinning
# an NDK or CMake package here: Gradle/AGP resolves the exact versions it
# wants (CMake 3.22.1 is pinned in the app module) and downloads them itself
# via this same sdkmanager the first time :app is built, now that licenses
# are accepted.
sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

# Persist for every future shell in this Codespace, not just this script's
# own subshell — postCreateCommand's exports do not outlive the command.
sudo tee /etc/profile.d/android-sdk.sh >/dev/null <<EOF
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:\$PATH"
EOF

echo "Android SDK ready at $SDK_ROOT"
