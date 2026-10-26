#!/bin/bash
# Builds the Android TV app (NVIDIA Shield) and installs it over Wi-Fi.
# The TV app is a shell around the live web app on the server, so app updates need no rebuild:
# only changes to this Android project do.   Usage: scripts/android-tv.sh <shield-ip[:port]>
set -e
source ~/android-tools/env.sh
cd "$(dirname "$0")/.."
npx cap copy android >/dev/null
# Load the web app from the server (same as the Mac browser) instead of the copy bundled in the app.
python3 - <<'PY'
import json
p = 'android/app/src/main/assets/capacitor.config.json'
c = json.load(open(p))
c['server'] = {'url': 'https://win-69ffbub31ra.tail0f14c6.ts.net:8443/', 'androidScheme': 'https'}
json.dump(c, open(p, 'w'), indent=2)
PY
(cd android && ./gradlew -q assembleRelease)
APK=android/app/build/outputs/apk/release/app-release.apk
ls -la "$APK"
if [ -n "$1" ]; then
  adb connect "$1"
  adb -s "$1" install -r "$APK"
fi
