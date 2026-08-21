#!/usr/bin/env bash
set -euo pipefail

avd_name="${PERSONAL_EDGE_AVD_NAME:-personal_edge_api37_foldable}"
system_image="system-images;android-37.0;google_apis;arm64-v8a"
device_profile="7.6in Foldable"
avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
model_ram_mb="${PERSONAL_EDGE_AVD_RAM_MB:-12288}"
model_data_partition="${PERSONAL_EDGE_AVD_DATA_PARTITION:-20G}"

if ! command -v avdmanager >/dev/null 2>&1; then
    echo "avdmanager was not found. Source ./scripts/android-env.sh first." >&2
    exit 1
fi

if avdmanager list avd | awk -F: -v name="$avd_name" \
    '$1 ~ /^[[:space:]]*Name$/ { gsub(/^[[:space:]]+/, "", $2); if ($2 == name) found = 1 } END { exit !found }'; then
    echo "AVD already exists: $avd_name"
else
    echo "no" | avdmanager create avd \
        --name "$avd_name" \
        --package "$system_image" \
        --device "$device_profile"

    echo "Created AVD: $avd_name"
fi

config_file="$avd_home/$avd_name.avd/config.ini"
if [[ ! -f "$config_file" || -L "$config_file" ]]; then
    echo "Missing or unsafe AVD config: $config_file" >&2
    exit 1
fi

set_avd_value() {
    local key="$1"
    local value="$2"
    local temporary
    temporary="$(mktemp "$config_file.tmp.XXXXXX")"
    awk -F= -v key="$key" -v value="$value" '
        BEGIN { found = 0 }
        $1 == key { print key "=" value; found = 1; next }
        { print }
        END { if (!found) print key "=" value }
    ' "$config_file" > "$temporary"
    chmod --reference="$config_file" "$temporary" 2>/dev/null || chmod 600 "$temporary"
    mv "$temporary" "$config_file"
}

set_avd_value "hw.ramSize" "$model_ram_mb"
set_avd_value "disk.dataPartition.size" "$model_data_partition"

echo "Configured $avd_name for model testing: RAM ${model_ram_mb}MB, data $model_data_partition"
