#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: generate-release-sbom.sh \
  --project-root PATH --output FILE --application-id ID \
  --version-code INTEGER --version-name NAME

Generates a deterministic CycloneDX 1.6 inventory from app/gradle.lockfile entries locked for
releaseRuntimeClasspath. The generator reads local tracked inputs only and never resolves or
downloads dependencies.
EOF
}

fail() {
    echo "FAIL $*" >&2
    exit 1
}

project_root=""
output_path=""
application_id=""
version_code=""
version_name=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --project-root)
            [[ $# -ge 2 ]] || fail "--project-root requires a value"
            project_root="$2"
            shift 2
            ;;
        --output)
            [[ $# -ge 2 ]] || fail "--output requires a value"
            output_path="$2"
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

[[ -n "$project_root" ]] || fail "--project-root is required"
[[ -n "$output_path" ]] || fail "--output is required"
[[ "$application_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || fail "invalid application ID"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "version code must be a positive integer"
[[ "$version_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail "invalid version name"

for required_command in git jq sort uniq cut grep mktemp; do
    command -v "$required_command" >/dev/null 2>&1 || fail "required command not found: $required_command"
done

[[ -d "$project_root" ]] || fail "project root not found: $project_root"
project_root="$(cd "$project_root" && pwd -P)"
[[ "$(git -C "$project_root" rev-parse --show-toplevel 2>/dev/null)" == "$project_root" ]] ||
    fail "project root must be the Git worktree root"
lock_file="$project_root/app/gradle.lockfile"
[[ -f "$lock_file" && ! -L "$lock_file" ]] || fail "app/gradle.lockfile is not a regular file"
git -C "$project_root" ls-files --error-unmatch -- app/gradle.lockfile >/dev/null 2>&1 ||
    fail "app/gradle.lockfile must be tracked"

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-sbom.XXXXXX")"
output_temp=""
cleanup() {
    rm -rf "$scratch_directory"
    if [[ -n "$output_temp" && -e "$output_temp" ]]; then
        rm -f "$output_temp"
    fi
}
trap cleanup EXIT

: > "$scratch_directory/components.unsorted"
line_number=0
component_count=0
while IFS= read -r lock_line || [[ -n "$lock_line" ]]; do
    line_number=$((line_number + 1))
    case "$lock_line" in
        ""|'#'*) continue ;;
    esac
    [[ "$lock_line" == *=* ]] || fail "malformed dependency lock entry at line $line_number"

    coordinate="${lock_line%%=*}"
    configurations="${lock_line#*=}"
    [[ "$coordinate" =~ ^[A-Za-z0-9._:-]+$ ]] ||
        fail "unsafe dependency coordinate at line $line_number"
    [[ -z "$configurations" || "$configurations" =~ ^[A-Za-z0-9._,-]+$ ]] ||
        fail "unsafe dependency configuration at line $line_number"

    # Gradle writes one sentinel row listing configurations with no locked dependencies.
    [[ "$coordinate" == "empty" ]] && continue
    case ",$configurations," in
        *,releaseRuntimeClasspath,*) ;;
        *) continue ;;
    esac

    IFS=':' read -r component_group component_name component_version extra <<EOF
$coordinate
EOF
    [[ -n "$component_group" && -n "$component_name" && -n "$component_version" && -z "${extra:-}" ]] ||
        fail "release dependency is not an exact group:name:version coordinate at line $line_number"
    [[ "$component_group" =~ ^[A-Za-z0-9._-]+$ ]] || fail "unsafe dependency group at line $line_number"
    [[ "$component_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "unsafe dependency name at line $line_number"
    [[ "$component_version" =~ ^[A-Za-z0-9][A-Za-z0-9._~-]*$ ]] ||
        fail "unsafe dependency version at line $line_number"

    component_purl="pkg:maven/$component_group/$component_name@$component_version"
    printf '%s\t%s\t%s\t%s\n' \
        "$component_purl" "$component_group" "$component_name" "$component_version" \
        >> "$scratch_directory/components.unsorted"
    component_count=$((component_count + 1))
done < "$lock_file"
(( component_count > 0 )) || fail "releaseRuntimeClasspath has no locked components"

LC_ALL=C sort "$scratch_directory/components.unsorted" > "$scratch_directory/components.sorted"
if cut -f1 "$scratch_directory/components.sorted" | uniq -d | grep -q .; then
    fail "releaseRuntimeClasspath contains duplicate component identities"
fi

jq -Rn '
    [inputs | split("\t") | {
        "bom-ref": .[0],
        group: .[1],
        name: .[2],
        purl: .[0],
        type: "library",
        version: .[3]
    }]
' < "$scratch_directory/components.sorted" > "$scratch_directory/components.json"

root_purl="pkg:generic/$application_id@$version_name"
output_directory="$(dirname "$output_path")"
mkdir -p "$output_directory"
output_directory="$(cd "$output_directory" && pwd -P)"
output_path="$output_directory/$(basename "$output_path")"
[[ ! -L "$output_path" ]] || fail "output must not be a symbolic link"
output_temp="$(mktemp "$output_path.tmp.XXXXXX")"

jq -n \
    --arg applicationId "$application_id" \
    --arg applicationPurl "$root_purl" \
    --arg versionCode "$version_code" \
    --arg versionName "$version_name" \
    --slurpfile components "$scratch_directory/components.json" \
    '{
        "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",
        bomFormat: "CycloneDX",
        specVersion: "1.6",
        version: 1,
        metadata: {
            component: {
                "bom-ref": $applicationPurl,
                name: $applicationId,
                properties: [
                    {
                        name: "personal-edge:android:versionCode",
                        value: $versionCode
                    },
                    {
                        name: "personal-edge:gradle:configuration",
                        value: "releaseRuntimeClasspath"
                    }
                ],
                purl: $applicationPurl,
                type: "application",
                version: $versionName
            }
        },
        components: $components[0]
    }' > "$output_temp"

chmod 0644 "$output_temp"
mv -f "$output_temp" "$output_path"
output_temp=""
echo "OK   Generated CycloneDX 1.6 release SBOM: $output_path"
