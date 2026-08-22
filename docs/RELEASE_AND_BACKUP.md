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

A partially configured key fails the build rather than quietly producing an unsigned APK. With no
key configured at all, `assembleRelease` still runs and logs a warning, and the resulting APK
cannot be installed.

## Build and verify

```bash
./gradlew :app:assembleRelease
./scripts/verify-release-signing.sh
```

The verifier fails if the APK is unsigned, is signed with the shared Android debug certificate, or
lacks an APK Signature Scheme v2 signature. Record the printed SHA-256 in your notes: every later
build must show the same value.

Without a key configured, the build still succeeds but names its output
`app-release-unsigned.apk`, which cannot be installed. The verifier recognises that artifact and
says so rather than claiming the build never ran.

```bash
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
```

Debug and release builds use different keys, so they cannot replace each other. Pick one for the
phone and stay on it.

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

- conversation transcripts and captured notification text — private, and stale on another device;
- the action ledger — its claims mean nothing on a device that never performed those actions;
- Keystore-encrypted credentials — the AES key is hardware-bound and cannot leave this phone.

That is a design choice, not an omission. It also means a new phone starts empty.

## New phone or reinstall

1. Restore the repository and the keystore from backup.
2. `./scripts/download-model.sh && ./scripts/verify-model.sh`
3. `./gradlew :app:assembleRelease && ./scripts/verify-release-signing.sh`
4. `adb -s DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk`
5. In the app: import the model, grant calendar permission, pin the calendar.
6. Re-enter any third-party credentials — the previous ciphertext cannot be decrypted on new
   hardware.
