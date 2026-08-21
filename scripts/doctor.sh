#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
jdk17_home="${JAVA17_HOME:-${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}}"

failures=0

check_command() {
    local command_name="$1"
    if command -v "$command_name" >/dev/null 2>&1; then
        echo "OK   $command_name: $(command -v "$command_name")"
    else
        echo "MISS $command_name"
        failures=$((failures + 1))
    fi
}

echo "Personal Edge Agent environment doctor"
echo "Project: $project_root"
echo "SDK:     $sdk_root"

if [[ -x "$jdk17_home/bin/java" ]]; then
    java_bin="$jdk17_home/bin/java"
else
    java_bin="$(command -v java || true)"
fi

if [[ -n "$java_bin" ]]; then
    java_settings_output=""
    java_commands_ok=0
    if jdk_version_output="$("$java_bin" -version 2>&1)" &&
       java_settings_output="$("$java_bin" -XshowSettings:properties -version 2>&1)"; then
        java_commands_ok=1
    fi
    jdk_version="${jdk_version_output%%$'\n'*}"
    java_specification="$(awk -F= \
        '/java.specification.version/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' \
        <<< "$java_settings_output")"
    java_major=""
    if (( java_commands_ok == 1 )) && [[ "$java_specification" =~ ^1\.([0-9]+)$ ]]; then
        java_major="${BASH_REMATCH[1]}"
    elif (( java_commands_ok == 1 )) && [[ "$java_specification" =~ ^([0-9]+)(\..*)?$ ]]; then
        java_major="${BASH_REMATCH[1]}"
    fi
    if [[ "$java_major" == "17" ]]; then
        echo "OK   JDK 17: $java_bin ($jdk_version)"
    else
        echo "MISS Expected JDK major 17, found: ${jdk_version:-unknown}"
        failures=$((failures + 1))
    fi
else
    echo "MISS java"
    failures=$((failures + 1))
fi

if [[ -d "$sdk_root/platforms/android-37.0" || -d "$sdk_root/platforms/android-37" ]]; then
    echo "OK   Android SDK Platform 37"
else
    echo "MISS Android SDK Platform 37"
    failures=$((failures + 1))
fi

build_tools_version="36.0.0"
if [[ -d "$sdk_root/build-tools/$build_tools_version" &&
      -x "$sdk_root/build-tools/$build_tools_version/aapt2" ]]; then
    echo "OK   Android Build Tools $build_tools_version"
else
    echo "MISS Android Build Tools $build_tools_version"
    failures=$((failures + 1))
fi

if [[ -x "$sdk_root/platform-tools/adb" ]]; then
    echo "OK   adb: $sdk_root/platform-tools/adb"
else
    check_command adb
fi

if [[ -d "$sdk_root/system-images/android-37.0/google_apis/arm64-v8a" ]]; then
    echo "OK   Android 17 ARM64 emulator image"
else
    echo "MISS Android 17 ARM64 emulator image"
    failures=$((failures + 1))
fi

avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
avd_name="${PERSONAL_EDGE_AVD_NAME:-personal_edge_api37_foldable}"
if [[ ! "$avd_name" =~ ^[A-Za-z0-9._-]+$ || "$avd_name" == "." || "$avd_name" == ".." ]]; then
    echo "MISS Unsafe PERSONAL_EDGE_AVD_NAME: $avd_name"
    failures=$((failures + 1))
elif [[ -f "$avd_home/$avd_name.ini" && ! -L "$avd_home/$avd_name.ini" ]]; then
    echo "OK   AVD: $avd_name"
else
    echo "MISS AVD $avd_name (run PERSONAL_EDGE_AVD_NAME=$avd_name ./scripts/create-foldable-avd.sh)"
    failures=$((failures + 1))
fi

if [[ -x "$project_root/gradlew" ]]; then
    echo "OK   Gradle wrapper"
else
    echo "MISS Gradle wrapper"
    failures=$((failures + 1))
fi

if (( failures > 0 )); then
    echo "Doctor found $failures missing requirement(s)."
    exit 1
fi

echo "Environment prerequisites are present."
