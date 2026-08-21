#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
sdk_manager="${SDKMANAGER:-}"

if [[ -z "$sdk_manager" ]]; then
    sdk_manager="$(command -v sdkmanager || true)"
fi

if [[ -z "$sdk_manager" || ! -x "$sdk_manager" ]]; then
    echo "sdkmanager was not found. Install Android Studio command-line tools first." >&2
    exit 1
fi

mkdir -p "$sdk_root"

"$sdk_manager" --sdk_root="$sdk_root" --licenses
"$sdk_manager" --sdk_root="$sdk_root" \
    "platform-tools" \
    "platforms;android-37.0" \
    "build-tools;36.0.0" \
    "build-tools;37.0.0" \
    "cmdline-tools;latest" \
    "emulator" \
    "system-images;android-37.0;google_apis;arm64-v8a"

echo "Android SDK installed at $sdk_root"
