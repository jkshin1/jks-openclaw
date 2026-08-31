# Release signing and personal backup

This app is sideloaded onto one phone. There is no Play Console, no store review, no update
server, and no CI. What that removes is release *process*; what it does not remove is the two
things that make the install recoverable — a fixed signing key and a restorable source tree.

## Why the key matters more than the build

`adb install -r` replaces the installed app only when the application ID **and** the signing
certificate match. The app-private data behind that identity includes:

- the verified 3.66GB model artifact, which takes a manual transfer and a SHA-256 check to replace;
- the durable action ledger, which is what stops a tool from firing twice;
- conversation history, settings, and the Keystore-encrypted credentials.

Lose the key and every update becomes uninstall → reinstall → re-import the model → re-enter
credentials. The key is therefore the one piece of this project that must outlive the machine it
was created on.

## Create the key once

The owner's personal key already exists. **Do not run the creation script again on this checkout.**
The following command is only the recovery/setup procedure for a genuinely new installation where
no key has been restored:

```bash
source ./scripts/android-env.sh
./scripts/create-release-keystore.sh
```

The script refuses to overwrite an existing keystore, prompts for the password twice (it never
appears in the process table or in this repository), writes `app/personal-edge-release.jks` and
`app/keystore.properties` with mode 600, and prints the certificate SHA-256.

Both files are ignored by `.gitignore` (`*.jks`, `keystore.properties`). Never commit either.

If you prefer not to keep the password on disk, delete `app/keystore.properties` and export the
four values instead — environment variables take precedence over the file:

```bash
export PERSONAL_EDGE_RELEASE_STORE_FILE=/absolute/path/personal-edge-release.jks
export PERSONAL_EDGE_RELEASE_STORE_PASSWORD='…'
export PERSONAL_EDGE_RELEASE_KEY_ALIAS=personal-edge-release
export PERSONAL_EDGE_RELEASE_KEY_PASSWORD='…'
```

A partially configured key or a completely missing release identity fails `preReleaseBuild`
rather than quietly producing an unsigned APK.

## Build and verify

The current source identifies itself as `versionCode=11`, `versionName="1.0.0-rc11"`. Keep the RC
suffix until provider/live, physical-device, key-backup, and signed-release migration acceptance
gates are explicitly closed; then choose a new version code and rebuild for `1.0.0`.

```bash
./gradlew --offline releaseGate
```

The release APK also carries a deterministic CycloneDX 1.6 component inventory and a privacy-safe
provenance asset for rc11 version, source state, Room schema, pinned model/runtime, signing
certificate, dependency locks, and the exact SBOM digest. The aggregate gate runs host tests and
lint, assembles the release, regenerates both allowlisted JSON documents, and compares the embedded
certificate with the APK's actual signer. See `docs/RELEASE_PROVENANCE.md`; this host receipt does
not imply physical-device installation or acceptance.

An opt-in physical test that must target the installed minified release uses a separate build
variant selection; the normal debug/emulator test target is unchanged:

```bash
./gradlew :app:assembleReleaseAndroidTest -PpersonalEdgePhysicalReleaseTest=true
./scripts/preflight-fold8-release-update.sh --serial DEVICE_SERIAL
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
```

The preflight is read-only: it refuses an emulator or a device other than the owner's API 37
SM-F971N, verifies the local app/test package identities and owner certificate, pulls the already
installed `base.apk` only to a private host temporary directory, and checks its package, version,
certificate, hash, and `firstInstallTime`. A PASS is only permission to ask the owner whether to
continue; it does not install or instrument anything and is not a preservation receipt.

Install and run only the named, owner-reviewed class with `am instrument`. Do not substitute a
broad `connectedAndroidTest` on the Fold8. Physical tests must capture and delete only the exact
rows they create; never clean up by a broad title or content match.

Build and install the release app and release test APK as one pair. The test shrinker applies the
app's R8 mapping and shared target dependencies remain in the app APK; retaining an older test APK
after rebuilding the app can therefore leave calls bound to stale symbols. Ordinary broad
instrumentation remains a debug/emulator lane. The release pair supports only the named physical
acceptance surfaces kept explicitly in `app/proguard-rules.pro`.

Before connecting the Fold8, install that matched pair on an isolated API 37 ARM64 AVD and run
`ReleasePhysicalAbiLinkageTest#boundedPostGuardEntrypointsResolveFromTheMinifiedTarget` with
`-e releasePhysicalAbiLinkage true`. The test itself also refuses a physical device. This
provider-free, non-mutating smoke directly resolves the closed post-guard ABI allowlist that
physical/live tests cannot reach when they stop at their AVD guard, including the read-only alarm,
SQLite, interlock, state DTO, credential-vault, provider-constructor, and Kotlin runtime boundaries.
It must pass together with the five-case release canary and the 28 guarded physical/live methods.
The SQLite count receipt uses the already-kept `SupportSQLiteDatabase` API rather than retaining
Room's debug-only default-argument bridge. This establishes minified split-APK linkage only, not
Fold8 behavior, provider behavior, model quality, or permission to install.

The verifier fails if the APK is unsigned, is signed with the shared Android debug certificate, or
carries neither an APK Signature Scheme v2 nor v3 block. On build-tools 37.0.0 this app signs with
v3 only, which is the stronger scheme. Record the printed certificate SHA-256 in your notes: every
later build must show the same value.

The owner's actual key path was exercised on 2026-08-22. `assembleRelease` produced the signed
`app-release.apk`; the verifier accepted APK Signature Scheme v3, rejected the debug identity, and
reported certificate SHA-256
`e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a`. Future release builds must
match that fingerprint. This proves build/signing configuration, not offline backup or physical
release installation.

On 2026-08-25, rc9 was installed on Fold8 `R5KL801YXWE` with `adb install -r`. The host APK and
pulled installed `base.apk` both had SHA-256
`2f7543664aab10f0e28cf275a515c198c9042b5cc56aadc54d1b998a84c0e8f1`; both reported the same v3
certificate above. `firstInstallTime=2026-08-23 18:10:37` remained unchanged. A content-free
release-target snapshot matched the pinned 3,659,530,240-byte model and retained settings,
credential-presence flags, and aggregate app-data counts without reading their contents.

Later on 2026-08-25, rc10 replaced rc9 with the same `adb install -r` path. The final host APK and
pulled installed `base.apk` both had SHA-256
`6e3c92a63e86e615f309571911567a3a4392e43b99ad0c781a9490ff8b49baa6`, retained the same v3
certificate, and kept `firstInstallTime=2026-08-23 18:10:37`. The post-install content-free
snapshot retained the exact 3,659,530,240-byte model, settings fingerprint, all three
credential-presence flags, 12 conversations, 47 messages, and zero memories. Notification rows
were 24 before and after the rc10 weather acceptance class; this is a no-loss aggregate check, not
a content inspection.

Rc11 replaced rc10 on Fold8 `R5KL801YXWE` with the same `adb install -r` path. Its
owner-signed release SHA-256 is
`12379e405d93be94c765a2eb2088e5af8d19d25a070f73c2f84e037910f34981`, its matching release
Android-test SHA-256 is
`ae6def7ca73de14aec06b097da6cbcc9206050579c91a34e95cff3c7d622dfd4`, and its v3 certificate is
the unchanged personal fingerprint above. `firstInstallTime=2026-08-23 18:10:37` remained
unchanged, the pulled installed `base.apk` matched the host hash byte-for-byte, and content-free
pre-install, post-install, and post-test snapshots retained the exact 3,659,530,240-byte model,
settings fingerprint, all three credential-presence flags, 14 conversations, 56 messages, zero
memories, 24 notifications, and Kakao reply disabled. Only the named weather and public-search
classes ran; no broad `connectedAndroidTest` was used.

On 2026-08-23 both local signing files were rechecked at mode 600 and Git confirmed that both are
ignored. The local JKS SHA-256 was
`353bcb423b65b12cb03f8a22e62b05e3c30e75e01fb9a87959a37e6d885c6838`. Use that digest to verify an
offline JKS copy byte-for-byte; it still does not prove that the password is recoverable from the
independent password-manager entry.

Verify a separately stored copy and the password taken from the password manager before migrating
the phone. The verifier is read-only, refuses the repository keystore itself, compares the exact
JKS SHA-256 against the committed non-secret identity manifest, checks the expected certificate,
and signs an ephemeral probe to prove private-key access:

```bash
source ./scripts/android-env.sh
./scripts/verify-signing-recovery.sh /absolute/path/to/personal-edge-release.jks
```

Do not pass either password as a command-line argument and do not use `app/keystore.properties` for
the acceptance receipt; type the password-manager value at the hidden prompt. A successful probe
still cannot prove the copy is on an independent disk or account, so record that fact separately.

On 2026-08-23 the distinct copy at `/Users/jk/projects/python/personal-edge-release.jks` passed the
verifier: its exact JKS SHA-256 and expected certificate matched, and an ephemeral private-key
signature verified. The password was supplied from the current local signing configuration without
being printed, so password-manager provenance was not independently exercised. More importantly,
the verified path is still on the same Mac's `/Users` filesystem. Copy the verified file to an
independent encrypted disk or account and confirm the password-manager value before uninstalling
the current debug app.

Later on 2026-08-23 that exact local copy was uploaded to the owner's OneDrive Personal Vault after
the owner enabled the vault and completed Microsoft's second-factor check. OneDrive showed the
`personal-edge-release.jks` item as private, 4.30KB, and emitted its upload-complete notification.
The owner then downloaded the vault item back over the separate local-copy path. The resulting file
had a new inode (`45879628`), mode 600, one hard link, size 4,406 bytes, and the exact expected
SHA-256 `353bcb423b65b12cb03f8a22e62b05e3c30e75e01fb9a87959a37e6d885c6838`. The owner ran
`verify-signing-recovery.sh` against that downloaded file with the password-manager value and
reported the terminal `OK`: expected certificate and private-key signing recovery therefore passed
the off-Mac round trip. The password-manager input and terminal output were owner-observed rather
than agent-captured; keep that evidence boundary in later audits.

Without the fixed owner key configured, the current Gradle configuration fails `preReleaseBuild`
before packaging. An older unsigned `app-release-unsigned.apk`, if one remains from a prior build,
is not an installable fallback and must not be used.

```bash
./scripts/preflight-fold8-release-update.sh --serial DEVICE_SERIAL
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
```

Debug and release builds use different keys, so they cannot replace each other. Pick one for the
phone and stay on it. On 2026-08-23, after the independent JKS round trip and explicit owner
approval, the Fold8 debug package was deliberately uninstalled, the exact signed release was
installed, and the 3.66GB model was re-imported. Device-only credentials were erased as designed
and then re-entered through the write-only UI. Future updates should remain on the personal release
identity.

On 2026-08-23 the exact signed release APK was installed into a disposable API 37 foldable emulator
started with `-read-only -no-snapshot-save`. Its installed SHA-256 matched the host artifact, the
expected v3 certificate remained, the package lacked `DEBUGGABLE`, `run-as` was denied, and
`MainActivity` cold-launched in 568 ms. The main and settings screens rendered under R8. The later
Fold8 migration separately qualified the model, restored credentials/settings, all eight shipped
Tool paths with owned write cleanup, and signed-release SAF/system receipts.

After release installation, private diagnostics cannot be pulled with `run-as`. Use the app's
content-free JSONL export through Android's document picker, then collect package/exit/memory/
thermal facts with the host script. This path passed on the Fold8: the SAF file had 74 records /
10,499 bytes with SHA-256 `75097be848a92df4ca2a045e9fb60aeeb81345cf7e06854ac371c7bd7fd9e741`,
and the matching system manifest was `overallStatus=ok`. Release `run-as` denial is retained while
private JSONL commands are explicitly `not_applicable_release`.

## What to back up

| Item | Where | Why |
|---|---|---|
| `app/personal-edge-release.jks` | offline copy + password manager | Without it, no update can preserve app data |
| Keystore password | password manager | The keystore is useless without it |
| Certificate SHA-256 | notes | Detects a forked identity before you install |
| Git repository | a second machine or a private remote | Source, model manifest, Gradle lockfiles, Room schemas |
| `models/model-manifest.json` | in the repository | Pins the exact revision, size, and SHA-256 to re-download |

Do **not** back up: the 3.66GB `.litertlm` file (re-downloadable from the pinned revision), the
Gradle caches, or anything under `app/build/`.

## What is deliberately not backed up

The app sets `allowBackup="false"` and excludes every domain in `data_extraction_rules.xml`, and
all app state lives in `noBackupFilesDir`:

- conversation transcripts, approved long-term memories, and captured notification text — private,
  and stale on another device;
- the action ledger — its claims mean nothing on a device that never performed those actions;
- Keystore-encrypted credentials — the installation-scoped Android Keystore key is not backed up.

That is a design choice, not an omission. It also means a new phone starts empty.

## New phone or reinstall

1. Restore the repository and the keystore from backup.
2. `./scripts/download-model.sh && ./scripts/verify-model.sh`
3. `./gradlew :app:assembleRelease && ./scripts/verify-release-signing.sh`
4. `adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk`
5. In the app: import the model, grant calendar permission, and pin the standard Samsung Account
   calendar (`com.osp.app.signin`, not the Mobile Service sharing row). If you use notification
   capture, grant access and turn capture on. Both grants are per-install.
6. Re-enter the NCP Maps key pair and, if fallback search is wanted, the Tavily API key — previous
   ciphertext cannot be decrypted on new hardware. Keyless You.com needs no credential.

Captured notifications, conversation history, and approved long-term memories do not come back, by
design. A new phone starts with an empty transcript, empty memory store, and empty action ledger.
