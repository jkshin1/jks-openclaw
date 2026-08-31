import java.io.StringReader
import java.util.Properties
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Fixed personal signing identity for sideloading. `adb install -r` preserves app data and
// the imported 3.66GB model only while the application ID and certificate stay identical,
// so the release key must outlive any single machine. See docs/RELEASE_AND_BACKUP.md.
// Environment variables win over the untracked app/keystore.properties file.
val keystoreProperties: Properties? = providers
    .fileContents(layout.projectDirectory.file("keystore.properties"))
    .asText
    .orNull
    ?.let { text -> Properties().apply { load(StringReader(text)) } }

fun signingSetting(propertyName: String, environmentName: String): String? = providers
    .environmentVariable(environmentName)
    .orNull
    ?.takeIf(String::isNotBlank)
    ?: keystoreProperties?.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStorePath = signingSetting("storeFile", "PERSONAL_EDGE_RELEASE_STORE_FILE")
val releaseStorePassword = signingSetting("storePassword", "PERSONAL_EDGE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingSetting("keyAlias", "PERSONAL_EDGE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingSetting("keyPassword", "PERSONAL_EDGE_RELEASE_KEY_PASSWORD")
    ?: releaseStorePassword

val declaredSigningSettings = listOf(
    "storeFile" to releaseStorePath,
    "storePassword" to releaseStorePassword,
    "keyAlias" to releaseKeyAlias,
    "keyPassword" to releaseKeyPassword,
)
val missingSigningSettings = declaredSigningSettings.filter { (_, value) -> value == null }
val releaseSigningRequested = missingSigningSettings.size < declaredSigningSettings.size

// A half-configured key silently produces an unsigned APK that cannot update the installed
// app. Refuse that state instead of discovering it on the device.
check(!releaseSigningRequested || missingSigningSettings.isEmpty()) {
    "Release signing is partially configured. Missing: " +
        missingSigningSettings.joinToString { (name, _) -> name }
}

val releaseSigningKeystore = releaseStorePath?.let(::file)
val releaseSigningConfigured = releaseSigningKeystore != null
val physicalReleaseTestRequested = providers
    .gradleProperty("personalEdgePhysicalReleaseTest")
    .orNull == "true"
val qwen8bLabTestRequested = providers
    .gradleProperty("personalEdgeQwen8bLabTest")
    .orNull == "true"
check(!(physicalReleaseTestRequested && qwen8bLabTestRequested)) {
    "personalEdgePhysicalReleaseTest and personalEdgeQwen8bLabTest are mutually exclusive."
}
val personalEdgeApplicationId = "com.personaledge.agent"
val personalEdgeVersionCode = 11
val personalEdgeVersionName = "1.0.0-rc11"
val releaseProvenanceDirectory = layout.buildDirectory.dir("generated/releaseProvenance/release")
val releaseProvenanceFile = releaseProvenanceDirectory.map { directory ->
    directory.file("release-provenance.json")
}
val releaseSbomFile = releaseProvenanceDirectory.map { directory ->
    directory.file("release-sbom.cdx.json")
}
check(releaseSigningKeystore == null || releaseSigningKeystore.isFile) {
    "Release keystore not found at $releaseSigningKeystore. " +
        "Run ./scripts/create-release-keystore.sh or restore it from your offline backup."
}

android {
    namespace = "com.personaledge.agent"
    compileSdk = 37

    // Normal host/emulator work keeps the debug target. Physical acceptance can explicitly link
    // the separately packaged test APK against the minified, owner-signed release. Dependencies
    // shared with the target APK are not copied into the test APK, so the small owner-reviewed
    // release suite requires stable cross-APK entry points in proguard-rules.pro.
    when {
        physicalReleaseTestRequested -> testBuildType = "release"
        qwen8bLabTestRequested -> testBuildType = "qwen8bLab"
    }

    defaultConfig {
        applicationId = personalEdgeApplicationId
        minSdk = 31
        targetSdk = 37
        versionCode = personalEdgeVersionCode
        versionName = personalEdgeVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "CANDIDATE_MODEL_LAB", "false")
        // Fail closed in any future build type until that variant explicitly opts into writes.
        buildConfigField("boolean", "SIDE_EFFECTING_TOOLS_ENABLED", "false")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningKeystore != null) {
            create("release") {
                storeFile = releaseSigningKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 31 verifies signature-scheme blocks, so the JAR signature is redundant
                // and v4 only helps incremental installs this app does not use. Measured with
                // build-tools 37.0.0: AGP emits a v3 block only, which is the stronger scheme
                // because it carries rotation information. verify-release-signing.sh therefore
                // accepts v2 or v3 rather than demanding both.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SIDE_EFFECTING_TOOLS_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the shared debug key: it would fork the installed identity.
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "SIDE_EFFECTING_TOOLS_ENABLED", "true")
        }
        create("qwen8bLab") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            applicationIdSuffix = ".qwen8blab"
            versionNameSuffix = "-qwen8b-lab"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "CANDIDATE_MODEL_LAB", "true")
            buildConfigField("boolean", "SIDE_EFFECTING_TOOLS_ENABLED", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // This device-first app intentionally ships arm64-v8a only; ChromeOS is out of scope.
        disable += "ChromeOsAbiSupport"
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory(
            releaseProvenanceDirectory.get().asFile.absolutePath,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// An unsigned release APK cannot be installed, and quietly producing one wastes a full
// minify/shrink cycle. Warn once at configuration time instead.
if (releaseSigningKeystore == null) {
    logger.warn(
        "Release signing is not configured; assembleRelease will produce an unsigned APK. " +
            "Run ./scripts/create-release-keystore.sh to create the fixed personal key.",
    )
}

val generateReleaseSbom = tasks.register<Exec>("generateReleaseSbom") {
    group = "build"
    description = "Generate the deterministic CycloneDX 1.6 release dependency inventory."
    workingDir(rootProject.projectDir)
    inputs.file(rootProject.file("scripts/generate-release-sbom.sh"))
    inputs.file(project.file("gradle.lockfile"))
    inputs.property("personalEdgeApplicationId", personalEdgeApplicationId)
    inputs.property("personalEdgeVersionCode", personalEdgeVersionCode)
    inputs.property("personalEdgeVersionName", personalEdgeVersionName)
    outputs.file(releaseSbomFile)
    commandLine(
        "bash",
        rootProject.file("scripts/generate-release-sbom.sh").absolutePath,
        "--project-root",
        rootProject.projectDir.absolutePath,
        "--output",
        releaseSbomFile.get().asFile.absolutePath,
        "--application-id",
        personalEdgeApplicationId,
        "--version-code",
        personalEdgeVersionCode.toString(),
        "--version-name",
        personalEdgeVersionName,
    )
}

val generateReleaseProvenance = tasks.register<Exec>("generateReleaseProvenance") {
    group = "build"
    description = "Generate the privacy-safe provenance manifest bound to the release SBOM."
    dependsOn(generateReleaseSbom)
    workingDir(rootProject.projectDir)
    inputs.file(rootProject.file("scripts/generate-release-provenance.sh"))
    inputs.file(releaseSbomFile)
    inputs.file(rootProject.file("models/model-manifest.json"))
    inputs.file(project.file("release-signing-identity.json"))
    inputs.file(rootProject.file("gradle/libs.versions.toml"))
    inputs.files(
        rootProject.fileTree("core/data/schemas/com.personaledge.core.data.PersonalEdgeDatabase") {
            include("*.json")
        },
    )
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("settings-gradle.lockfile")
            include("app/gradle.lockfile")
            include("core/*/gradle.lockfile")
        },
    )
    outputs.file(releaseProvenanceFile)
    // Git dirty state can change without touching a declared input (for example a new source file).
    outputs.upToDateWhen { false }
    commandLine(
        "bash",
        rootProject.file("scripts/generate-release-provenance.sh").absolutePath,
        "--project-root",
        rootProject.projectDir.absolutePath,
        "--output",
        releaseProvenanceFile.get().asFile.absolutePath,
        "--sbom",
        releaseSbomFile.get().asFile.absolutePath,
        "--version-code",
        personalEdgeVersionCode.toString(),
        "--version-name",
        personalEdgeVersionName,
    )
}

tasks.register<Exec>("verifyReleaseProvenance") {
    group = "verification"
    description = "Verify the packaged release SBOM, provenance, and APK signing identity."
    dependsOn("assembleRelease")
    workingDir(rootProject.projectDir)
    inputs.file(rootProject.file("scripts/verify-release-provenance.sh"))
    inputs.file(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    commandLine(
        "bash",
        rootProject.file("scripts/verify-release-provenance.sh").absolutePath,
        "--apk",
        layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath,
        "--project-root",
        rootProject.projectDir.absolutePath,
        "--application-id",
        personalEdgeApplicationId,
        "--version-code",
        personalEdgeVersionCode.toString(),
        "--version-name",
        personalEdgeVersionName,
    )
}

// Release output is an installable migration artifact, not a compile smoke. Refuse the task at
// pre-build time when the fixed owner identity is unavailable; debug remains usable for host and
// emulator development.
tasks.matching { task -> task.name == "preReleaseBuild" }.configureEach {
    dependsOn(generateReleaseProvenance)
    inputs.property("personalEdgeReleaseSigningConfigured", releaseSigningConfigured)
    doFirst(Action<Task> {
        check(inputs.properties["personalEdgeReleaseSigningConfigured"] == true) {
            "Release signing is required. Restore the verified personal signing key before building release."
        }
    })
}

dependencies {
    implementation(project(":core:agent"))
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))
    implementation(platform(libs.kotlin.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Declare the isolated in-memory test's runtime explicitly. The release target also keeps
    // Room's facade as a narrow cross-APK ABI root in proguard-rules.pro.
    androidTestImplementation(libs.androidx.room.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
