#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
sbom_generator="$project_root/scripts/generate-release-sbom.sh"
provenance_generator="$project_root/scripts/generate-release-provenance.sh"
verifier="$project_root/scripts/verify-release-provenance.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-provenance-test.XXXXXX")"

cleanup() {
    rm -rf "$test_root"
}
trap cleanup EXIT

fail() {
    echo "FAIL $*" >&2
    exit 1
}

pass() {
    echo "PASS $*"
}

fixture="$test_root/repository"
mkdir -p \
    "$fixture/app" \
    "$fixture/core/data/src/main/kotlin/com/personaledge/core/data" \
    "$fixture/core/data/schemas/com.personaledge.core.data.PersonalEdgeDatabase" \
    "$fixture/core/llm" \
    "$fixture/gradle" \
    "$fixture/models"

cat > "$fixture/models/model-manifest.json" <<'EOF'
{
  "schemaVersion": 1,
  "repository": "litert-community/gemma-4-E4B-it-litert-lm",
  "revision": "2222222222222222222222222222222222222222",
  "file": "must-not-leak.litertlm",
  "downloadUrl": "https://example.invalid/must-not-leak",
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "litertLmVersion": "0.16.1"
}
EOF
cat > "$fixture/app/release-signing-identity.json" <<'EOF'
{
  "schemaVersion": 1,
  "keyAlias": "must-not-leak",
  "keystoreSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "certificateSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
}
EOF
cat > "$fixture/core/data/schemas/com.personaledge.core.data.PersonalEdgeDatabase/5.json" <<'EOF'
{"database":{"version":5}}
EOF
cat > "$fixture/core/data/src/main/kotlin/com/personaledge/core/data/PersonalEdgeDatabase.kt" <<'EOF'
package com.personaledge.core.data

internal const val PERSONAL_EDGE_DATABASE_VERSION = 5
EOF
cat > "$fixture/gradle/libs.versions.toml" <<'EOF'
[versions]
litertLm = "0.16.1"
EOF
cat > "$fixture/core/llm/gradle.lockfile" <<'EOF'
com.google.ai.edge.litertlm:litertlm-android:0.16.1=releaseRuntimeClasspath
empty=
EOF
cat > "$fixture/app/gradle.lockfile" <<'EOF'
# This is a Gradle generated file for dependency locking.
androidx.core:core-ktx:1.19.0=debugRuntimeClasspath,releaseRuntimeClasspath
com.google.ai.edge.litertlm:litertlm-android:0.16.1=releaseRuntimeClasspath
junit:junit:4.13.2=debugUnitTestRuntimeClasspath
z.example:runtime-library:2.0.0=releaseRuntimeClasspath
empty=releaseAnnotationProcessorClasspath
EOF
cat > "$fixture/settings-gradle.lockfile" <<'EOF'
com.android.tools.build:gradle:9.3.1=classpath
empty=
EOF
cat > "$fixture/.gitignore" <<'EOF'
build/
*.jks
EOF
cat > "$fixture/README.md" <<'EOF'
# Release provenance fixture
EOF

git -C "$fixture" init -q
git -C "$fixture" config user.name "Provenance Test"
git -C "$fixture" config user.email "provenance-test@example.invalid"
git -C "$fixture" add .
git -C "$fixture" commit -qm "Create provenance fixture"

generate_sbom() {
    local output_path="$1"
    "$sbom_generator" \
        --project-root "$fixture" \
        --output "$output_path" \
        --application-id com.personaledge.agent \
        --version-code 11 \
        --version-name 1.0.0-rc11 >/dev/null
}

generate_provenance() {
    local output_path="$1"
    local sbom_path="$2"
    "$provenance_generator" \
        --project-root "$fixture" \
        --output "$output_path" \
        --sbom "$sbom_path" \
        --version-code 11 \
        --version-name 1.0.0-rc11 >/dev/null
}

verify_apk() {
    local path_value="$1"
    env PATH="$mock_bin:$PATH" "$verifier" \
        --apk "$path_value" \
        --project-root "$fixture" \
        --application-id com.personaledge.agent \
        --version-code 11 \
        --version-name 1.0.0-rc11
}

clean_sbom="$test_root/clean-sbom.json"
repeated_sbom="$test_root/repeated-sbom.json"
generate_sbom "$clean_sbom"
generate_sbom "$repeated_sbom"
cmp -s "$clean_sbom" "$repeated_sbom" || fail "identical release locks produced different SBOM bytes"
jq -e '
    keys == ["$schema", "bomFormat", "components", "metadata", "specVersion", "version"] and
    .["$schema"] == "http://cyclonedx.org/schema/bom-1.6.schema.json" and
    .bomFormat == "CycloneDX" and
    .specVersion == "1.6" and
    .version == 1 and
    .metadata.component == {
        "bom-ref": "pkg:generic/com.personaledge.agent@1.0.0-rc11",
        "name": "com.personaledge.agent",
        "properties": [
            {"name": "personal-edge:android:versionCode", "value": "11"},
            {"name": "personal-edge:gradle:configuration", "value": "releaseRuntimeClasspath"}
        ],
        "purl": "pkg:generic/com.personaledge.agent@1.0.0-rc11",
        "type": "application",
        "version": "1.0.0-rc11"
    } and
    (.components | map(.purl)) == [
        "pkg:maven/androidx.core/core-ktx@1.19.0",
        "pkg:maven/com.google.ai.edge.litertlm/litertlm-android@0.16.1",
        "pkg:maven/z.example/runtime-library@2.0.0"
    ] and
    ([.components[].purl] | length == (unique | length))
    ' "$clean_sbom" >/dev/null || fail "CycloneDX release inventory is incorrect"
if grep -Eq 'junit|debugUnitTest|timestamp|serialNumber|/Users/|/home/' "$clean_sbom"; then
    fail "SBOM contains non-release, non-deterministic, or private data"
fi
pass "SBOM is deterministic CycloneDX 1.6 with sorted releaseRuntimeClasspath components only"

printf '%s\n' 'debug.only:test-helper:1.0.0=debugRuntimeClasspath' >> "$fixture/app/gradle.lockfile"
debug_only_sbom="$test_root/debug-only-sbom.json"
generate_sbom "$debug_only_sbom"
cmp -s "$clean_sbom" "$debug_only_sbom" || fail "debug-only lock entry changed the release SBOM"
git -C "$fixture" restore app/gradle.lockfile
pass "non-release lock configurations do not enter the release SBOM"

printf '%s\n' 'androidx.core:core-ktx:1.19.0=releaseRuntimeClasspath' >> "$fixture/app/gradle.lockfile"
if generate_sbom "$test_root/duplicate-sbom.json" 2>/dev/null; then
    fail "SBOM generator accepted a duplicate release component"
fi
git -C "$fixture" restore app/gradle.lockfile
pass "SBOM generation fails closed on duplicate component identities"

printf '%s\n' 'invalid:coordinate:1.0.0:extra=releaseRuntimeClasspath' >> "$fixture/app/gradle.lockfile"
if generate_sbom "$test_root/malformed-sbom.json" 2>/dev/null; then
    fail "SBOM generator accepted a malformed release coordinate"
fi
git -C "$fixture" restore app/gradle.lockfile
pass "SBOM generation fails closed on malformed release coordinates"

clean_manifest="$test_root/clean-provenance.json"
generate_provenance "$clean_manifest" "$clean_sbom"

fixture_commit="$(git -C "$fixture" rev-parse HEAD)"
clean_sbom_sha256="$(shasum -a 256 "$clean_sbom" | awk '{print $1}')"
jq -e --arg commit "$fixture_commit" --arg sbomSha256 "$clean_sbom_sha256" '
    .schemaVersion == 2 and
    .application == {"versionCode": 11, "versionName": "1.0.0-rc11"} and
    .source.gitCommit == $commit and
    .source.dirty == false and
    (.source.stateSha256 | test("^[0-9a-f]{64}$")) and
    .database.schemaVersion == 5 and
    .model.id == "litert-community/gemma-4-E4B-it-litert-lm" and
    .model.revision == "2222222222222222222222222222222222222222" and
    .model.sha256 == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" and
    .runtime.liteRtLmVersion == "0.16.1" and
    .signing.certificateSha256 == "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" and
    (.dependencies.lockSha256 | test("^[0-9a-f]{64}$")) and
    .dependencies.sbomSha256 == $sbomSha256
    ' "$clean_manifest" >/dev/null || fail "clean provenance values are incorrect"
if grep -Eq 'must-not-leak|example\.invalid|keyAlias|keystoreSha256|downloadUrl|/Users/|/home/' "$clean_manifest"; then
    fail "private or non-allowlisted model/signing metadata leaked into provenance"
fi
pass "provenance schema v2 binds the exact SBOM digest without private metadata"

sed -i.bak \
    's/PERSONAL_EDGE_DATABASE_VERSION = 5/PERSONAL_EDGE_DATABASE_VERSION = 6/' \
    "$fixture/core/data/src/main/kotlin/com/personaledge/core/data/PersonalEdgeDatabase.kt"
rm -f "$fixture/core/data/src/main/kotlin/com/personaledge/core/data/PersonalEdgeDatabase.kt.bak"
if generate_provenance "$test_root/database-version-drift.json" "$clean_sbom" 2>/dev/null; then
    fail "provenance generator accepted compiled Room/schema version drift"
fi
git -C "$fixture" restore core/data/src/main/kotlin/com/personaledge/core/data/PersonalEdgeDatabase.kt
pass "compiled Room version and exported schema versions cannot drift"

clean_source_state="$(jq -r '.source.stateSha256' "$clean_manifest")"
mkdir -p "$fixture/build/generated"
printf '%s\n' 'ignored secret version one' > "$fixture/build/generated/private.jks"
ignored_manifest_one="$test_root/ignored-one.json"
generate_provenance "$ignored_manifest_one" "$clean_sbom"
printf '%s\n' 'ignored secret version two' > "$fixture/build/generated/private.jks"
ignored_manifest_two="$test_root/ignored-two.json"
generate_provenance "$ignored_manifest_two" "$clean_sbom"
[[ "$(jq -r '.source.stateSha256' "$ignored_manifest_one")" == "$clean_source_state" &&
    "$(jq -r '.source.stateSha256' "$ignored_manifest_two")" == "$clean_source_state" ]] ||
    fail "ignored generated/secret content changed source state"
jq -e '.source.dirty == false' "$ignored_manifest_two" >/dev/null ||
    fail "ignored generated/secret content marked the worktree dirty"
pass "ignored generated and secret content is excluded from source state"

ln -s README.md "$fixture/untracked-link.kt"
if generate_provenance "$test_root/symlink.json" "$clean_sbom" 2>/dev/null; then
    fail "provenance generator accepted a source symlink"
fi
rm -f "$fixture/untracked-link.kt"
pass "provenance generation fails closed when source inventory contains a symlink"

clean_lock_digest="$(jq -r '.dependencies.lockSha256' "$clean_manifest")"
printf '%s\n' 'androidx.work:work-runtime:2.11.2=releaseRuntimeClasspath' >> "$fixture/app/gradle.lockfile"
dirty_sbom="$test_root/dirty-sbom.json"
dirty_manifest="$test_root/dirty-provenance.json"
generate_sbom "$dirty_sbom"
generate_provenance "$dirty_manifest" "$dirty_sbom"
jq -e '.source.dirty == true' "$dirty_manifest" >/dev/null || fail "dirty worktree was not recorded"
dirty_lock_digest="$(jq -r '.dependencies.lockSha256' "$dirty_manifest")"
[[ "$clean_lock_digest" != "$dirty_lock_digest" ]] || fail "dependency lock edits did not change the aggregate digest"
[[ "$(jq -r '.dependencies.sbomSha256' "$dirty_manifest")" != "$clean_sbom_sha256" ]] ||
    fail "release dependency change did not change the bound SBOM digest"
first_dirty_source_state="$(jq -r '.source.stateSha256' "$dirty_manifest")"
git -C "$fixture" restore app/gradle.lockfile
pass "release lock changes update both component inventory and provenance bindings"

printf '%s\n' 'first untracked source state' > "$fixture/untracked-source.kt"
untracked_manifest_one="$test_root/untracked-one.json"
generate_provenance "$untracked_manifest_one" "$clean_sbom"
printf '%s\n' 'second untracked source state' > "$fixture/untracked-source.kt"
untracked_manifest_two="$test_root/untracked-two.json"
generate_provenance "$untracked_manifest_two" "$clean_sbom"
untracked_state_one="$(jq -r '.source.stateSha256' "$untracked_manifest_one")"
untracked_state_two="$(jq -r '.source.stateSha256' "$untracked_manifest_two")"
[[ "$first_dirty_source_state" != "$untracked_state_one" &&
    "$untracked_state_one" != "$untracked_state_two" ]] ||
    fail "distinct dirty source contents produced the same source state digest"
jq -e '.source.dirty == true' "$untracked_manifest_one" >/dev/null ||
    fail "non-ignored untracked source was not marked dirty"
rm -f "$fixture/untracked-source.kt"
pass "distinct dirty tracked and untracked source contents have distinct state digests"

rm -f "$fixture/README.md"
deleted_manifest="$test_root/deleted.json"
generate_provenance "$deleted_manifest" "$clean_sbom"
[[ "$(jq -r '.source.stateSha256' "$deleted_manifest")" != "$clean_source_state" ]] ||
    fail "tracked deletion did not change source state digest"
git -C "$fixture" restore README.md
pass "tracked deletions contribute an explicit source state marker"

valid_sbom="$test_root/valid-sbom.json"
valid_manifest="$test_root/valid-provenance.json"
generate_sbom "$valid_sbom"
generate_provenance "$valid_manifest" "$valid_sbom"

mock_bin="$test_root/bin"
mkdir -p "$mock_bin"
cat > "$mock_bin/apksigner" <<'EOF'
#!/usr/bin/env bash
echo 'Verified using v2 scheme (APK Signature Scheme v2): false'
echo 'Verified using v3 scheme (APK Signature Scheme v3): true'
echo 'Signer #1 certificate DN: CN=Personal Edge Release'
echo 'Signer #1 certificate SHA-256 digest: cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
EOF
chmod 0755 "$mock_bin/apksigner"

package_release() {
    local manifest_path="$1"
    local sbom_path="$2"
    local package_name="$3"
    local package_root="$test_root/package-$package_name"
    local apk_path="$test_root/$package_name.apk"
    mkdir -p "$package_root/assets"
    cp "$manifest_path" "$package_root/assets/release-provenance.json"
    if [[ "$sbom_path" != "-" ]]; then
        cp "$sbom_path" "$package_root/assets/release-sbom.cdx.json"
    fi
    (cd "$package_root" && zip -q -r "$apk_path" assets)
    printf '%s\n' "$apk_path"
}

valid_apk="$(package_release "$valid_manifest" "$valid_sbom" valid)"
verify_apk "$valid_apk" >/dev/null || fail "valid packaged SBOM/provenance was rejected"
pass "verifier accepts matching packaged SBOM, provenance, and signing certificate"

missing_sbom_apk="$(package_release "$valid_manifest" - missing-sbom)"
if verify_apk "$missing_sbom_apk" >/dev/null 2>&1; then
    fail "verifier accepted an APK without the release SBOM"
fi
pass "verifier requires exactly one packaged CycloneDX SBOM"

tampered_sbom="$test_root/tampered-sbom.json"
jq '.components[0].version = "9.9.9" | .components[0].purl = "pkg:maven/androidx.core/core-ktx@9.9.9" | .components[0]["bom-ref"] = .components[0].purl' \
    "$valid_sbom" > "$tampered_sbom"
tampered_sbom_apk="$(package_release "$valid_manifest" "$tampered_sbom" tampered-sbom)"
if verify_apk "$tampered_sbom_apk" >/dev/null 2>&1; then
    fail "verifier accepted a schema-valid tampered SBOM"
fi
pass "verifier rejects component inventory that differs from release locks"

tampered_binding="$test_root/tampered-binding.json"
jq '.dependencies.sbomSha256 = "0000000000000000000000000000000000000000000000000000000000000000"' \
    "$valid_manifest" > "$tampered_binding"
tampered_binding_apk="$(package_release "$tampered_binding" "$valid_sbom" tampered-binding)"
if verify_apk "$tampered_binding_apk" >/dev/null 2>&1; then
    fail "verifier accepted a provenance manifest detached from the packaged SBOM"
fi
pass "verifier rejects a broken provenance-to-SBOM digest binding"

mismatched_bin="$test_root/mismatched-bin"
mkdir -p "$mismatched_bin"
cat > "$mismatched_bin/apksigner" <<'EOF'
#!/usr/bin/env bash
echo 'Verified using v2 scheme (APK Signature Scheme v2): false'
echo 'Verified using v3 scheme (APK Signature Scheme v3): true'
echo 'Signer #1 certificate DN: CN=Different Release Identity'
echo 'Signer #1 certificate SHA-256 digest: dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
EOF
chmod 0755 "$mismatched_bin/apksigner"
if env PATH="$mismatched_bin:$PATH" "$verifier" \
    --apk "$valid_apk" \
    --project-root "$fixture" \
    --application-id com.personaledge.agent \
    --version-code 11 \
    --version-name 1.0.0-rc11 >/dev/null 2>&1; then
    fail "verifier accepted a different APK signing certificate"
fi
pass "verifier rejects an APK signer that differs from embedded provenance"

secret_manifest="$test_root/secret.json"
jq '.model.id = "/Users/example/private-model"' "$valid_manifest" > "$secret_manifest"
secret_apk="$(package_release "$secret_manifest" "$valid_sbom" secret)"
if verify_apk "$secret_apk" >/dev/null 2>&1; then
    fail "verifier accepted an embedded private path"
fi
pass "verifier rejects private paths and non-allowlisted values"

tampered_manifest="$test_root/tampered-provenance.json"
jq '.dependencies.lockSha256 = "0000000000000000000000000000000000000000000000000000000000000000"' \
    "$valid_manifest" > "$tampered_manifest"
tampered_apk="$(package_release "$tampered_manifest" "$valid_sbom" tampered-provenance)"
if verify_apk "$tampered_apk" >/dev/null 2>&1; then
    fail "verifier accepted a tampered dependency lock digest"
fi
pass "verifier rejects schema-valid provenance tampering"

echo "OK   Release supply-chain host tests passed."
