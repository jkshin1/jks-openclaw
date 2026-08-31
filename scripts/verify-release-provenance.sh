#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: verify-release-provenance.sh \
  --apk FILE --project-root PATH --application-id ID \
  --version-code INTEGER --version-name NAME

Verifies the embedded CycloneDX SBOM, release manifest, current public inputs, and actual APK
certificate.
EOF
}

fail() {
    echo "FAIL $*" >&2
    exit 1
}

apk_path=""
project_root=""
application_id=""
version_code=""
version_name=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk)
            [[ $# -ge 2 ]] || fail "--apk requires a value"
            apk_path="$2"
            shift 2
            ;;
        --project-root)
            [[ $# -ge 2 ]] || fail "--project-root requires a value"
            project_root="$2"
            shift 2
            ;;
        --application-id)
            [[ $# -ge 2 ]] || fail "--application-id requires a value"
            application_id="$2"
            shift 2
            ;;
        --version-code)
            [[ $# -ge 2 ]] || fail "--version-code requires a value"
            version_code="$2"
            shift 2
            ;;
        --version-name)
            [[ $# -ge 2 ]] || fail "--version-name requires a value"
            version_name="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

[[ -n "$apk_path" ]] || fail "--apk is required"
[[ -n "$project_root" ]] || fail "--project-root is required"
[[ "$application_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || fail "invalid application ID"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "version code must be a positive integer"
[[ "$version_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail "invalid version name"

for required_command in jq unzip cmp sed tr grep mktemp shasum awk; do
    command -v "$required_command" >/dev/null 2>&1 || fail "required command not found: $required_command"
done

[[ -f "$apk_path" && ! -L "$apk_path" ]] || fail "release APK is not a regular file: $apk_path"
[[ -d "$project_root" ]] || fail "project root not found: $project_root"
project_root="$(cd "$project_root" && pwd -P)"
script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-provenance-verify.XXXXXX")"
cleanup() {
    rm -rf "$scratch_directory"
}
trap cleanup EXIT

provenance_asset_path="assets/release-provenance.json"
provenance_asset_count="$(unzip -Z1 "$apk_path" | grep -Fxc "$provenance_asset_path" || true)"
[[ "$provenance_asset_count" == "1" ]] || fail "APK must contain exactly one $provenance_asset_path"
unzip -p "$apk_path" "$provenance_asset_path" > "$scratch_directory/embedded-provenance.json"

sbom_asset_path="assets/release-sbom.cdx.json"
sbom_asset_count="$(unzip -Z1 "$apk_path" | grep -Fxc "$sbom_asset_path" || true)"
[[ "$sbom_asset_count" == "1" ]] || fail "APK must contain exactly one $sbom_asset_path"
unzip -p "$apk_path" "$sbom_asset_path" > "$scratch_directory/embedded-sbom.json"

expected_application_purl="pkg:generic/$application_id@$version_name"
jq -e \
    --arg expectedApplicationId "$application_id" \
    --arg expectedApplicationPurl "$expected_application_purl" \
    --arg expectedVersionCode "$version_code" \
    --arg expectedVersionName "$version_name" '
    type == "object" and
    keys == ["$schema", "bomFormat", "components", "metadata", "specVersion", "version"] and
    .["$schema"] == "http://cyclonedx.org/schema/bom-1.6.schema.json" and
    .bomFormat == "CycloneDX" and
    .specVersion == "1.6" and
    .version == 1 and
    (.metadata | keys == ["component"]) and
    (.metadata.component | keys == ["bom-ref", "name", "properties", "purl", "type", "version"]) and
    .metadata.component["bom-ref"] == $expectedApplicationPurl and
    .metadata.component.name == $expectedApplicationId and
    .metadata.component.purl == $expectedApplicationPurl and
    .metadata.component.type == "application" and
    .metadata.component.version == $expectedVersionName and
    .metadata.component.properties == [
        {
            "name": "personal-edge:android:versionCode",
            "value": $expectedVersionCode
        },
        {
            "name": "personal-edge:gradle:configuration",
            "value": "releaseRuntimeClasspath"
        }
    ] and
    (.components | type == "array" and length > 0) and
    (.components == (.components | sort_by(.["bom-ref"]))) and
    ([.components[].purl] | length == (unique | length)) and
    all(.components[];
        (keys == ["bom-ref", "group", "name", "purl", "type", "version"]) and
        .type == "library" and
        (.group | type == "string" and test("^[A-Za-z0-9._-]+$")) and
        (.name | type == "string" and test("^[A-Za-z0-9._-]+$")) and
        (.version | type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._~-]*$")) and
        .purl == ("pkg:maven/" + .group + "/" + .name + "@" + .version) and
        .["bom-ref"] == .purl
    )
    ' "$scratch_directory/embedded-sbom.json" >/dev/null || fail "embedded CycloneDX SBOM schema or values are invalid"

jq -e \
    --argjson expectedVersionCode "$version_code" \
    --arg expectedVersionName "$version_name" '
    type == "object" and
    keys == ["application", "database", "dependencies", "model", "runtime", "schemaVersion", "signing", "source"] and
    .schemaVersion == 2 and
    (.application | keys == ["versionCode", "versionName"]) and
    .application.versionCode == $expectedVersionCode and
    .application.versionName == $expectedVersionName and
    (.source | keys == ["dirty", "gitCommit", "stateSha256"]) and
    (.source.gitCommit | type == "string" and test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
    (.source.dirty | type == "boolean") and
    (.source.stateSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.database | keys == ["schemaVersion"]) and
    (.database.schemaVersion | type == "number" and . >= 1 and floor == .) and
    (.model | keys == ["id", "revision", "sha256"]) and
    (.model.id | type == "string" and test("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) and
    (.model.revision | type == "string" and test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
    (.model.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.runtime | keys == ["liteRtLmVersion"]) and
    (.runtime.liteRtLmVersion | type == "string" and test("^[0-9]+\\.[0-9]+\\.[0-9]+([._-][A-Za-z0-9.-]+)?$")) and
    (.signing | keys == ["certificateSha256"]) and
    (.signing.certificateSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.dependencies | keys == ["lockSha256", "sbomSha256"]) and
    (.dependencies.lockSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.dependencies.sbomSha256 | type == "string" and test("^[0-9a-f]{64}$"))
    ' "$scratch_directory/embedded-provenance.json" >/dev/null || fail "embedded provenance schema or values are invalid"

if jq -r '.. | strings' "$scratch_directory/embedded-provenance.json" |
    grep -Eiq '(^|[/\\])(Users|home)([/\\])|(^|:)file:|keystore|keyalias|password|secret|credential|access[_-]?token'; then
    fail "embedded provenance contains a forbidden path or secret-related value"
fi

"$script_directory/generate-release-sbom.sh" \
    --project-root "$project_root" \
    --output "$scratch_directory/expected-sbom.json" \
    --application-id "$application_id" \
    --version-code "$version_code" \
    --version-name "$version_name" >/dev/null
cmp -s "$scratch_directory/expected-sbom.json" "$scratch_directory/embedded-sbom.json" ||
    fail "embedded SBOM does not match app releaseRuntimeClasspath dependency locks"

embedded_sbom_sha256="$(shasum -a 256 "$scratch_directory/embedded-sbom.json" | awk '{print $1}')"
expected_sbom_sha256="$(jq -r '.dependencies.sbomSha256' "$scratch_directory/embedded-provenance.json")"
[[ "$embedded_sbom_sha256" == "$expected_sbom_sha256" ]] ||
    fail "embedded SBOM digest does not match release provenance"

"$script_directory/generate-release-provenance.sh" \
    --project-root "$project_root" \
    --output "$scratch_directory/expected-provenance.json" \
    --sbom "$scratch_directory/expected-sbom.json" \
    --version-code "$version_code" \
    --version-name "$version_name" >/dev/null
cmp -s "$scratch_directory/expected-provenance.json" "$scratch_directory/embedded-provenance.json" ||
    fail "embedded provenance does not match current pinned release inputs"

signing_output="$("$script_directory/verify-release-signing.sh" "$apk_path")" ||
    fail "release APK signature verification failed"
actual_certificate="$(printf '%s\n' "$signing_output" |
    sed -nE 's/^.*certificate SHA-256 digest:[[:space:]]*//p' |
    head -n 1 |
    tr -d '[:space:]:' |
    tr '[:upper:]' '[:lower:]')"
[[ "$actual_certificate" =~ ^[0-9a-f]{64}$ ]] || fail "could not read the APK signing certificate digest"
expected_certificate="$(jq -r '.signing.certificateSha256' "$scratch_directory/embedded-provenance.json")"
[[ "$actual_certificate" == "$expected_certificate" ]] ||
    fail "APK signing certificate does not match embedded provenance"

echo "OK   Release SBOM and provenance match $version_name locks, inputs, and APK signing identity."
