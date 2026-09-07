#!/usr/bin/env bash
# Local equivalent of the "smoke-test" job in .github/workflows/android.yml
# (boots an emulator, runs :app:connectedDebugAndroidTest).
#
# GitHub Codespaces containers generally do not expose /dev/kvm — no nested
# virtualization — so this checks for it first instead of silently hanging
# in an unaccelerated emulator boot. If it is missing, the honest answer is
# "this job stays on GitHub Actions"; there is no reliable workaround from
# inside a Codespace.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -e /dev/kvm ]; then
  cat >&2 <<'EOF'
/dev/kvm not found — this Codespace's machine type does not expose hardware
virtualization, so the Android emulator would run unaccelerated at best, and
more likely just time out on boot. Rely on the "smoke-test" job in
.github/workflows/android.yml for this instead of running it here.
EOF
  exit 1
fi

sdkmanager "system-images;android-30;aosp_atd;x86_64" "emulator" >/dev/null

avdmanager create avd -n ci -k "system-images;android-30;aosp_atd;x86_64" --device pixel_2 --force

"$ANDROID_SDK_ROOT/emulator/emulator" -avd ci -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

./gradlew --no-daemon :app:connectedDebugAndroidTest
