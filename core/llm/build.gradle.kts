import groovy.json.JsonSlurper
import java.io.File

val pinnedManifestFile = rootProject.file("models/model-manifest.json")
val pinnedManifest = JsonSlurper().parse(pinnedManifestFile) as Map<*, *>
val expectedManifestKeys = setOf(
    "schemaVersion",
    "repository",
    "revision",
    "file",
    "downloadUrl",
    "sizeBytes",
    "sha256",
    "litertLmVersion",
    "contextTokens",
    "maxOutputTokens",
)

check(pinnedManifest.keys == expectedManifestKeys) {
    "models/model-manifest.json has missing or unknown fields."
}

fun manifestString(name: String): String =
    (pinnedManifest[name] as? String)?.takeIf { it.isNotBlank() }
        ?: error("Model manifest field $name must be a non-blank string.")

fun manifestLong(name: String): Long {
    val encoded = (pinnedManifest[name] as? Number)?.toString()
        ?: error("Model manifest field $name must be an integer.")
    check(encoded.matches(Regex("0|[1-9][0-9]*"))) {
        "Model manifest field $name must be an exact non-negative integer."
    }
    return encoded.toLongOrNull()
        ?: error("Model manifest field $name is outside the signed 64-bit range.")
}

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val modelSchemaVersion = manifestLong("schemaVersion")
val modelRepository = manifestString("repository")
val modelRevision = manifestString("revision")
val modelFile = manifestString("file")
val modelDownloadUrl = manifestString("downloadUrl")
val modelSizeBytes = manifestLong("sizeBytes")
val modelSha256 = manifestString("sha256")
val modelLiteRtLmVersion = manifestString("litertLmVersion")
val modelContextTokens = manifestLong("contextTokens")
val modelMaxOutputTokens = manifestLong("maxOutputTokens")

check(modelSchemaVersion == 1L)
check(modelRepository == "litert-community/gemma-4-E4B-it-litert-lm")
check(modelRevision.matches(Regex("[0-9a-f]{40}")))
check(modelFile == File(modelFile).name && '/' !in modelFile && '\\' !in modelFile)
check(modelSizeBytes > Int.MAX_VALUE)
check(modelSha256.matches(Regex("[0-9a-f]{64}")))
check(modelLiteRtLmVersion == libs.versions.litertLm.get())
check(modelContextTokens in 1..131_072)
check(modelMaxOutputTokens in 1..4_000)
check(
    modelDownloadUrl ==
        "https://huggingface.co/$modelRepository/resolve/$modelRevision/$modelFile",
)

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.personaledge.core.llm"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "MODEL_SCHEMA_VERSION", "${modelSchemaVersion}L")
        buildConfigField("String", "MODEL_REPOSITORY", quotedBuildConfig(modelRepository))
        buildConfigField("String", "MODEL_REVISION", quotedBuildConfig(modelRevision))
        buildConfigField("String", "MODEL_FILE", quotedBuildConfig(modelFile))
        buildConfigField("long", "MODEL_SIZE_BYTES", "${modelSizeBytes}L")
        buildConfigField("String", "MODEL_SHA256", quotedBuildConfig(modelSha256))
        buildConfigField("String", "MODEL_LITERT_LM_VERSION", quotedBuildConfig(modelLiteRtLmVersion))
        buildConfigField("int", "MODEL_CONTEXT_TOKENS", modelContextTokens.toString())
        buildConfigField("int", "MODEL_MAX_OUTPUT_TOKENS", modelMaxOutputTokens.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(platform(libs.kotlin.bom))
    api(libs.litertlm.android)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
