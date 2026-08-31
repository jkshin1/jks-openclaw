# Release supply-chain provenance

The rc11 release source defines two deterministic packaged assets:

- `assets/release-sbom.cdx.json`, a component-level CycloneDX 1.6 inventory; and
- `assets/release-provenance.json`, privacy-safe build provenance bound to that SBOM.

They contain no timestamp, random serial, machine/user name, or absolute path. Generation and
verification are implemented. The current rc11 working-tree `releaseGate` passed on 2026-08-26;
its provenance truthfully records `dirty=true`, so this is not a clean-source promotion receipt.

## Release SBOM

The generator reads only tracked `app/gradle.lockfile`; it does not resolve or download
dependencies. It selects exact `releaseRuntimeClasspath` tokens, fail-closed parses each
`group:name:version`, sorts unique Maven package URLs under `LC_ALL=C`, and emits one component per
locked release runtime dependency. Test, lint, instrumentation, compiler, and build-tool
configurations are excluded.

This is a resolved dependency inventory, not an R8 byte-level inventory or invented graph. Gradle's
flat lock format does not record dependency edges. Artifact bytes remain pinned by
`gradle/verification-metadata.xml`, while human license review remains in
`THIRD_PARTY_NOTICES.md`.

## Provenance allowlist

The schema contains only:

- `versionCode=11` and `versionName=1.0.0-rc11`;
- Git commit digest, worktree `dirty` boolean, and aggregate `stateSha256`;
- Room database schema version 9, admitted only by the coherence checks below;
- model repository identifier, pinned revision, and model SHA-256;
- LiteRT-LM version cross-checked across manifest, catalog, and lockfile;
- public release-signing certificate SHA-256;
- an aggregate SHA-256 over tracked Gradle lock paths/content; and
- the exact packaged SBOM SHA-256.

It excludes model filenames/download URLs, keystore paths/digests/aliases, passwords, credential
values, source paths, prompts, Tool content, and provider data. The signing digest comes from the
public identity manifest and must match the certificate actually signing the APK.

### Room schema identity

Generation and verification derive Room identity fail closed from all three authoritative views:

1. the compiled `PERSONAL_EDGE_DATABASE_VERSION` constant;
2. every exported schema filename; and
3. each schema JSON document's internal database version.

Every filename must be a positive integer equal to that file's positive integral JSON-internal
version, and the latest validated exported value must equal the compile constant. An empty schema
set, non-regular or symlinked schema entry, malformed JSON/value, filename-to-JSON mismatch, or
latest-to-source mismatch stops generation or verification. The implementation does not trust a
largest filename or JSON value until those per-file and source-constant checks succeed.

`dirty=true` means the APK was built from a state outside the recorded commit. `stateSha256`
distinguishes such states without revealing filenames or content. The generator hashes tracked and
non-ignored untracked regular files into a sorted internal record set and emits only the aggregate.
Missing tracked files add deletion markers. Symlinks and changing/non-regular files fail closed.

Ignored build outputs, local toolchains, model binaries, reports, keystores, environment files,
service accounts, and credential files are excluded by Git rules plus explicit generated/sensitive
path guards. Release review should still use a frozen, reviewed source state.

## Recorded host gate

The current working tree passed:

```bash
source ./scripts/android-env.sh
./scripts/test-release-provenance.sh
./gradlew --offline releaseGate
```

`releaseGate` is defined to aggregate host JVM/script tests, Android lint, minified signed release
assembly, deterministic fixtures, and packaged-artifact verification. It does not run
instrumentation, install an APK, contact a provider, or mutate a device.

The receipt includes 587 JVM cases, 19/19 host-script checks, all module lint tasks, and fresh
SBOM/provenance/signing verification. The packaged CycloneDX 1.6 SBOM contains 137 locked release
runtime components. Any later source edit, including documentation, requires a fresh gate because
the aggregate source-state digest changes. That receipt is left as recorded: the host-only model
evaluation harness added on 2026-08-28 took the suite to 20 checks and edited the tree, so a rerun
reports 20/20 against a different source-state digest.

`verifyReleaseProvenance` must fail unless:

1. the APK has exactly one provenance file and one SBOM at the expected asset paths;
2. both documents match their exact allowlisted schemas, safe formats, deterministic ordering, and
   unique package URLs;
3. the SBOM byte-matches fresh generation from `app/gradle.lockfile`;
4. the embedded SBOM digest matches provenance;
5. provenance byte-matches generation from the current pinned inputs and SBOM;
6. the APK has a valid non-debug v2/v3 signature; and
7. the actual signing certificate digest matches provenance.

Generated host files remain under `app/build/generated/releaseProvenance/release/`. After a
successful build, inspect packaged copies without extracting unrelated APK content:

```bash
unzip -p app/build/outputs/apk/release/app-release.apk \
  assets/release-provenance.json | jq .
unzip -p app/build/outputs/apk/release/app-release.apk \
  assets/release-sbom.cdx.json | jq .
```

## Acceptance boundary

The current host build produced a signed/minified rc11 APK and verified that its actual certificate
matches packaged provenance. This document deliberately does not embed the APK hash because doing
so would change the source included by that APK; record the final hash beside the frozen artifact.
No physical install, permission mutation, Fold8 runtime, provider-live result, or preservation
snapshot is claimed. A later owner-approved device pass must use same-certificate `adb install -r`,
verify pulled APK hash/certificate, and perform bounded content-free preservation checks without an
unscoped `connectedAndroidTest`.
