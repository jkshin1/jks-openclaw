# Third-party component inventory

This repository does not grant a repository-wide source-code license through this inventory.
The project owner controls the application source license separately. This file records the major
direct third-party inputs for release review; each component remains subject to its own upstream
license and notice files.

The authoritative resolved versions are the tracked `gradle.lockfile` files and
`settings-gradle.lockfile`. Artifact integrity is pinned by `gradle/verification-metadata.xml`.
Every release APK also contains `assets/release-sbom.cdx.json`, a deterministic CycloneDX 1.6
inventory generated from the exact `app:releaseRuntimeClasspath` lock entries. The SBOM is the
machine-readable component inventory for the packaged release dependency configuration; this
human-reviewed file records license families and obligations that cannot be inferred safely from
the lockfile alone.

| Scope | Component families | Version source | Upstream license |
|---|---|---|---|
| Android runtime/UI | AndroidX Core, Activity, Lifecycle, Compose, Material 3, Room, DataStore, WorkManager | `gradle/libs.versions.toml` and module lockfiles | Apache License 2.0 |
| Language/runtime | Kotlin, Kotlin BOM, kotlinx.coroutines | `gradle/libs.versions.toml` and module lockfiles | Apache License 2.0 |
| Serialization | Gson | `gradle/libs.versions.toml` and module lockfiles | Apache License 2.0 |
| On-device inference | LiteRT-LM Android API | `models/model-manifest.json`, version catalog, and `core/llm/gradle.lockfile` | Apache License 2.0 |
| Build tooling | Android Gradle Plugin, Kotlin Gradle plugins, KSP, Room Gradle plugin | `gradle/libs.versions.toml` and `settings-gradle.lockfile` | Apache License 2.0 |
| Host/device tests only | AndroidX Test, kotlinx.coroutines-test | `gradle/libs.versions.toml` and module lockfiles | Apache License 2.0 |
| Host/unit tests only | JUnit 4 | `gradle/libs.versions.toml` and module lockfiles | Eclipse Public License 1.0 |

## Model terms

The Gemma model artifact is not application source and is not committed to Git. Its exact
repository, revision, byte size, and SHA-256 are pinned in `models/model-manifest.json`. Download,
use, and redistribution remain governed by the Gemma Terms of Use and the applicable prohibited-use
policy presented by the model distributor. The release APK contains only the model identifier,
revision, and SHA-256 provenance; it does not contain the 3.66 GB model binary or its download URL.

## Release review procedure

Before distribution, compare the packaged CycloneDX component list with the reviewed release,
review newly introduced components, and add their license obligations here. Do not infer a license
from a Maven group or populate unverified license claims in the SBOM. This inventory is
intentionally not a legal opinion and does not replace retaining upstream license/notice text when
redistribution terms require it.
