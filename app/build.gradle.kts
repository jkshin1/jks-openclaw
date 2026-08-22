import java.io.StringReader
import java.util.Properties

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
check(releaseSigningKeystore == null || releaseSigningKeystore.isFile) {
    "Release keystore not found at $releaseSigningKeystore. " +
        "Run ./scripts/create-release-keystore.sh or restore it from your offline backup."
}

android {
    namespace = "com.personaledge.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.personaledge.agent"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the shared debug key: it would fork the installed identity.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
