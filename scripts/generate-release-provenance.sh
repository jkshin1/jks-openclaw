#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: generate-release-provenance.sh \
  --project-root PATH --output FILE --sbom FILE \
  --version-code INTEGER --version-name NAME

Generates the deterministic, privacy-safe release provenance JSON embedded in the release APK.
EOF
}

fail() {
    echo "FAIL $*" >&2
    exit 1
}

project_root=""
output_path=""
sbom_path=""
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
        --sbom)
            [[ $# -ge 2 ]] || fail "--sbom requires a value"
            sbom_path="$2"
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
[[ -n "$sbom_path" ]] || fail "--sbom is required"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "version code must be a positive integer"
[[ "$version_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] ||
    fail "version name contains unsupported characters"

for required_command in git jq shasum awk grep sort mktemp sed; do
    command -v "$required_command" >/dev/null 2>&1 || fail "required command not found: $required_command"
done

[[ -d "$project_root" ]] || fail "project root not found: $project_root"
project_root="$(cd "$project_root" && pwd -P)"
[[ "$(git -C "$project_root" rev-parse --show-toplevel 2>/dev/null)" == "$project_root" ]] ||
    fail "project root must be the Git worktree root"
[[ -f "$sbom_path" && ! -L "$sbom_path" ]] || fail "SBOM is not a regular file: $sbom_path"
sbom_sha256="$(shasum -a 256 "$sbom_path" | awk '{print $1}')"
[[ "$sbom_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "SBOM SHA-256 generation failed"

model_manifest="$project_root/models/model-manifest.json"
signing_identity="$project_root/app/release-signing-identity.json"
schema_directory="$project_root/core/data/schemas/com.personaledge.core.data.PersonalEdgeDatabase"
database_source="$project_root/core/data/src/main/kotlin/com/personaledge/core/data/PersonalEdgeDatabase.kt"
version_catalog="$project_root/gradle/libs.versions.toml"
litert_lock="$project_root/core/llm/gradle.lockfile"

for required_file in \
    "$model_manifest" \
    "$signing_identity" \
    "$database_source" \
    "$version_catalog" \
    "$litert_lock"; do
    [[ -f "$required_file" && ! -L "$required_file" ]] ||
        fail "required regular file not found: ${required_file#"$project_root"/}"
done
[[ -d "$schema_directory" && ! -L "$schema_directory" ]] || fail "Room schema directory not found"

git_commit="$(git -C "$project_root" rev-parse --verify HEAD)"
[[ "$git_commit" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || fail "unsupported Git commit digest"
git_dirty=false
if [[ -n "$(git -C "$project_root" status --porcelain --untracked-files=normal)" ]]; then
    git_dirty=true
fi

db_schema_version=0
schema_count=0
database_source_version="$(sed -nE \
    's/^[[:space:]]*internal const val PERSONAL_EDGE_DATABASE_VERSION = ([1-9][0-9]*)[[:space:]]*$/\1/p' \
    "$database_source")"
[[ "$database_source_version" =~ ^[1-9][0-9]*$ ]] ||
    fail "PersonalEdgeDatabase source must declare exactly one positive database version"
shopt -s nullglob
schema_files=("$schema_directory"/*.json)
shopt -u nullglob
for schema_file in "${schema_files[@]}"; do
    [[ -f "$schema_file" && ! -L "$schema_file" ]] || fail "Room schema must be a regular file"
    schema_version="$(jq -er '.database.version | select(type == "number" and . >= 1 and floor == .)' "$schema_file")" ||
        fail "invalid Room schema version in ${schema_file##*/}"
    schema_file_version="${schema_file##*/}"
    schema_file_version="${schema_file_version%.json}"
    [[ "$schema_file_version" =~ ^[1-9][0-9]*$ && "$schema_file_version" == "$schema_version" ]] ||
        fail "Room schema filename and embedded version disagree in ${schema_file##*/}"
    schema_count=$((schema_count + 1))
    if (( schema_version > db_schema_version )); then
        db_schema_version="$schema_version"
    fi
done
(( schema_count > 0 )) || fail "no Room schema JSON files found"
[[ "$db_schema_version" == "$database_source_version" ]] ||
    fail "Room schema files do not match the compiled PersonalEdgeDatabase version"

model_id="$(jq -er '.repository | select(type == "string" and length > 0)' "$model_manifest")" ||
    fail "model repository is missing"
model_revision="$(jq -er '.revision | select(type == "string")' "$model_manifest")" ||
    fail "model revision is missing"
model_sha256="$(jq -er '.sha256 | select(type == "string")' "$model_manifest")" ||
    fail "model SHA-256 is missing"
litert_lm_version="$(jq -er '.litertLmVersion | select(type == "string" and length > 0)' "$model_manifest")" ||
    fail "LiteRT-LM version is missing"

[[ "$model_id" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || fail "model repository has an unsafe format"
[[ "$model_revision" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || fail "model revision is not a commit digest"
[[ "$model_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "model SHA-256 is invalid"
[[ "$litert_lm_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([._-][A-Za-z0-9.-]+)?$ ]] ||
    fail "LiteRT-LM version has an unsafe format"

catalog_litert_version="$(awk -F'"' '/^[[:space:]]*litertLm[[:space:]]*=/{print $2}' "$version_catalog")"
[[ -n "$catalog_litert_version" && "$catalog_litert_version" == "$litert_lm_version" ]] ||
    fail "model manifest and Gradle catalog disagree on LiteRT-LM version"
grep -F "com.google.ai.edge.litertlm:litertlm-android:${litert_lm_version}=" "$litert_lock" >/dev/null ||
    fail "resolved LiteRT-LM dependency does not match the declared version"

certificate_sha256="$(jq -er '.certificateSha256 | select(type == "string")' "$signing_identity")" ||
    fail "release certificate SHA-256 is missing"
[[ "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "release certificate SHA-256 is invalid"

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-provenance.XXXXXX")"
output_temp=""
cleanup() {
    rm -rf "$scratch_directory"
    if [[ -n "$output_temp" && -e "$output_temp" ]]; then
        rm -f "$output_temp"
    fi
}
trap cleanup EXIT

is_sensitive_source_path() {
    local source_path="$1"
    local basename="${source_path##*/}"
    case "$basename" in
        .env|.env.*|local.properties|secrets.properties|key.properties|keystore.properties|\
        google-services.json|credentials*.json|service-account*.json|client_secret*.json|\
        *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

is_generated_source_path() {
    local source_path="$1"
    local basename="${source_path##*/}"
    case "/$source_path/" in
        */.gradle/*|*/build/*|*/captures/*|*/.externalNativeBuild/*|*/.cxx/*|*/.idea/*|\
        */.android-sdk/*|*/.jdk/*|*/.kotlin/*|*/__pycache__/*|*/reports/*|*/traces/*)
            return 0
            ;;
    esac
    case "$basename" in
        *.iml|.DS_Store|*.pyc|*.pyo|*.hprof|*.litertlm|*.task|*.safetensors)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Hash the current source state without exposing its inventory. Git supplies tracked and
# non-ignored untracked paths. Explicit generated/secret path guards also exclude a mistakenly
# tracked local artifact. Ephemeral records use the relative path plus content digest, or an
# explicit deletion marker for a missing tracked file; only their final aggregate is emitted.
git -C "$project_root" ls-files -co --exclude-standard --deduplicate -z \
    > "$scratch_directory/source-paths.z"
: > "$scratch_directory/source-records.unsorted"
source_entry_count=0
while IFS= read -r -d '' source_path; do
    [[ -n "$source_path" && "$source_path" != /* ]] || fail "Git returned an unsafe source path"
    [[ "/$source_path/" != *"/../"* && "/$source_path/" != *"/.git/"* ]] ||
        fail "Git returned an unsafe source path"
    [[ "$source_path" != *$'\n'* && "$source_path" != *$'\r'* && "$source_path" != *$'\t'* ]] ||
        fail "source paths with control separators are not supported"
    source_file="$project_root/$source_path"
    [[ ! -L "$source_file" ]] || fail "symbolic links are not allowed in release source state"

    if is_sensitive_source_path "$source_path" || is_generated_source_path "$source_path"; then
        continue
    fi

    if [[ -f "$source_file" ]]; then
        source_content_sha256="$(shasum -a 256 "$source_file")"
        source_content_sha256="${source_content_sha256%% *}"
        [[ "$source_content_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "source content digest generation failed"
        printf 'F\t%s\t%s\n' "$source_path" "$source_content_sha256" \
            >> "$scratch_directory/source-records.unsorted"
    elif [[ ! -e "$source_file" ]] &&
        git -C "$project_root" ls-files --error-unmatch -- "$source_path" >/dev/null 2>&1; then
        printf 'D\t%s\t-\n' "$source_path" >> "$scratch_directory/source-records.unsorted"
    else
        fail "source state changed during hashing or contains a non-regular file"
    fi
    source_entry_count=$((source_entry_count + 1))
done < "$scratch_directory/source-paths.z"
(( source_entry_count > 0 )) || fail "no release source files found"

printf 'personal-edge-source-state-v1\n' > "$scratch_directory/source-state-input.txt"
LC_ALL=C sort "$scratch_directory/source-records.unsorted" \
    >> "$scratch_directory/source-state-input.txt"
source_state_sha256="$(shasum -a 256 "$scratch_directory/source-state-input.txt" | awk '{print $1}')"
[[ "$source_state_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "source state digest generation failed"

git -C "$project_root" ls-files -- '*gradle.lockfile' 'settings-gradle.lockfile' |
    LC_ALL=C sort > "$scratch_directory/lock-paths.txt"

lock_count=0
: > "$scratch_directory/lock-digests.txt"
while IFS= read -r lock_path; do
    [[ -n "$lock_path" ]] || continue
    case "$lock_path" in
        settings-gradle.lockfile|*/gradle.lockfile) ;;
        *) fail "unsafe dependency lock path returned by Git" ;;
    esac
    [[ "$lock_path" =~ ^[A-Za-z0-9._/-]+$ && "$lock_path" != *".."* ]] ||
        fail "unsafe dependency lock path returned by Git"
    lock_file="$project_root/$lock_path"
    [[ -f "$lock_file" && ! -L "$lock_file" ]] || fail "tracked dependency lock is missing: $lock_path"
    lock_sha256="$(shasum -a 256 "$lock_file" | awk '{print $1}')"
    printf '%s  %s\n' "$lock_sha256" "$lock_path" >> "$scratch_directory/lock-digests.txt"
    lock_count=$((lock_count + 1))
done < "$scratch_directory/lock-paths.txt"
(( lock_count > 0 )) || fail "no tracked Gradle dependency locks found"
dependency_lock_sha256="$(shasum -a 256 "$scratch_directory/lock-digests.txt" | awk '{print $1}')"
[[ "$dependency_lock_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "dependency lock digest generation failed"

output_directory="$(dirname "$output_path")"
mkdir -p "$output_directory"
output_directory="$(cd "$output_directory" && pwd -P)"
output_path="$output_directory/$(basename "$output_path")"
[[ ! -L "$output_path" ]] || fail "output must not be a symbolic link"
output_temp="$(mktemp "$output_path.tmp.XXXXXX")"

jq -n \
    --argjson versionCode "$version_code" \
    --arg versionName "$version_name" \
    --arg gitCommit "$git_commit" \
    --argjson dirty "$git_dirty" \
    --arg sourceStateSha256 "$source_state_sha256" \
    --argjson databaseSchemaVersion "$db_schema_version" \
    --arg modelId "$model_id" \
    --arg modelRevision "$model_revision" \
    --arg modelSha256 "$model_sha256" \
    --arg liteRtLmVersion "$litert_lm_version" \
    --arg certificateSha256 "$certificate_sha256" \
    --arg dependencyLockSha256 "$dependency_lock_sha256" \
    --arg sbomSha256 "$sbom_sha256" \
    '{
        schemaVersion: 2,
        application: {
            versionCode: $versionCode,
            versionName: $versionName
        },
        source: {
            gitCommit: $gitCommit,
            dirty: $dirty,
            stateSha256: $sourceStateSha256
        },
        database: {
            schemaVersion: $databaseSchemaVersion
        },
        model: {
            id: $modelId,
            revision: $modelRevision,
            sha256: $modelSha256
        },
        runtime: {
            liteRtLmVersion: $liteRtLmVersion
        },
        signing: {
            certificateSha256: $certificateSha256
        },
        dependencies: {
            lockSha256: $dependencyLockSha256,
            sbomSha256: $sbomSha256
        }
    }' > "$output_temp"

chmod 0644 "$output_temp"
mv -f "$output_temp" "$output_path"
output_temp=""
echo "OK   Generated release provenance: $output_path"
