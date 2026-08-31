import groovy.json.JsonSlurper
import java.io.File

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
    "supportsImageInput",
    "supportsAudioInput",
)

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

data class ModelManifestValues(
    val schemaVersion: Long,
    val repository: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val litertLmVersion: String,
    val contextTokens: Long,
    val maxOutputTokens: Long,
    val supportsImageInput: Boolean,
    val supportsAudioInput: Boolean,
)

fun loadPinnedManifest(
    manifestFile: File,
    expectedRepository: String,
): ModelManifestValues {
    val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
    check(manifest.keys == expectedManifestKeys) {
        "${manifestFile.path} has missing or unknown fields."
    }

    fun manifestString(name: String): String =
        (manifest[name] as? String)?.takeIf { it.isNotBlank() }
            ?: error("Model manifest field $name must be a non-blank string.")

    fun manifestBoolean(name: String): Boolean =
        manifest[name] as? Boolean
            ?: error("Model manifest field $name must be a literal true or false.")

    fun manifestLong(name: String): Long {
        val encoded = (manifest[name] as? Number)?.toString()
            ?: error("Model manifest field $name must be an integer.")
        check(encoded.matches(Regex("0|[1-9][0-9]*"))) {
            "Model manifest field $name must be an exact non-negative integer."
        }
        return encoded.toLongOrNull()
            ?: error("Model manifest field $name is outside the signed 64-bit range.")
    }

    val values = ModelManifestValues(
        schemaVersion = manifestLong("schemaVersion"),
        repository = manifestString("repository"),
        revision = manifestString("revision"),
        file = manifestString("file"),
        sizeBytes = manifestLong("sizeBytes"),
        sha256 = manifestString("sha256"),
        litertLmVersion = manifestString("litertLmVersion"),
        contextTokens = manifestLong("contextTokens"),
        maxOutputTokens = manifestLong("maxOutputTokens"),
        supportsImageInput = manifestBoolean("supportsImageInput"),
        supportsAudioInput = manifestBoolean("supportsAudioInput"),
    )
    val downloadUrl = manifestString("downloadUrl")

    check(values.schemaVersion == 1L)
    check(values.repository == expectedRepository)
    check(values.revision.matches(Regex("[0-9a-f]{40}")))
    check(values.file == File(values.file).name && '/' !in values.file && '\\' !in values.file)
    check(values.sizeBytes > Int.MAX_VALUE)
    check(values.sha256.matches(Regex("[0-9a-f]{64}")))
    check(values.litertLmVersion == libs.versions.litertLm.get())
    check(values.contextTokens in 1..131_072)
    check(values.maxOutputTokens in 1..4_000)
    check(
        downloadUrl ==
            "https://huggingface.co/${values.repository}/resolve/${values.revision}/${values.file}",
    )
    return values
}

val productionModel = loadPinnedManifest(
    manifestFile = rootProject.file("models/model-manifest.json"),
    expectedRepository = "litert-community/gemma-4-E4B-it-litert-lm",
)
val qwen8bLabModel = loadPinnedManifest(
    manifestFile = rootProject.file("models/model-manifest-qwen3-8b.json"),
    expectedRepository = "litert-community/Qwen3-8B",
)

check(productionModel.file == "gemma-4-E4B-it.litertlm")
check(productionModel.contextTokens == 4_096L)
check(productionModel.maxOutputTokens == 1_024L)
// The pinned artifact embeds tf_lite_vision_encoder/adapter and tf_lite_audio_encoder_hw/adapter
// sections, and its own jinja template renders `image` and `audio` content items. Declaring the
// modalities here is what lets the runtime refuse media for any model that does not carry them.
check(productionModel.supportsImageInput)
check(productionModel.supportsAudioInput)
check(qwen8bLabModel.file == "qwen3_8b_mixed_int4.litertlm")
check(qwen8bLabModel.contextTokens == 2_048L)
check(qwen8bLabModel.maxOutputTokens == 384L)
// The lab artifact is text-only. The declaration keeps media from ever reaching that build.
check(!qwen8bLabModel.supportsImageInput)
check(!qwen8bLabModel.supportsAudioInput)

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.personaledge.core.llm"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "MODEL_SCHEMA_VERSION", "${productionModel.schemaVersion}L")
        buildConfigField("String", "MODEL_REPOSITORY", quotedBuildConfig(productionModel.repository))
        buildConfigField("String", "MODEL_REVISION", quotedBuildConfig(productionModel.revision))
        buildConfigField("String", "MODEL_FILE", quotedBuildConfig(productionModel.file))
        buildConfigField("long", "MODEL_SIZE_BYTES", "${productionModel.sizeBytes}L")
        buildConfigField("String", "MODEL_SHA256", quotedBuildConfig(productionModel.sha256))
        buildConfigField(
            "String",
            "MODEL_LITERT_LM_VERSION",
            quotedBuildConfig(productionModel.litertLmVersion),
        )
        buildConfigField("int", "MODEL_CONTEXT_TOKENS", productionModel.contextTokens.toString())
        buildConfigField(
            "int",
            "MODEL_MAX_OUTPUT_TOKENS",
            productionModel.maxOutputTokens.toString(),
        )
        buildConfigField(
            "boolean",
            "MODEL_SUPPORTS_IMAGE_INPUT",
            productionModel.supportsImageInput.toString(),
        )
        buildConfigField(
            "boolean",
            "MODEL_SUPPORTS_AUDIO_INPUT",
            productionModel.supportsAudioInput.toString(),
        )
        buildConfigField(
            "String",
            "MODEL_STORE_ROOT",
            quotedBuildConfig("personal-edge-models-v1"),
        )
        buildConfigField("boolean", "CANDIDATE_MODEL_LAB", "false")
    }

    buildTypes {
        create("qwen8bLab") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("long", "MODEL_SCHEMA_VERSION", "${qwen8bLabModel.schemaVersion}L")
            buildConfigField(
                "String",
                "MODEL_REPOSITORY",
                quotedBuildConfig(qwen8bLabModel.repository),
            )
            buildConfigField("String", "MODEL_REVISION", quotedBuildConfig(qwen8bLabModel.revision))
            buildConfigField("String", "MODEL_FILE", quotedBuildConfig(qwen8bLabModel.file))
            buildConfigField("long", "MODEL_SIZE_BYTES", "${qwen8bLabModel.sizeBytes}L")
            buildConfigField("String", "MODEL_SHA256", quotedBuildConfig(qwen8bLabModel.sha256))
            buildConfigField(
                "String",
                "MODEL_LITERT_LM_VERSION",
                quotedBuildConfig(qwen8bLabModel.litertLmVersion),
            )
            buildConfigField(
                "int",
                "MODEL_CONTEXT_TOKENS",
                qwen8bLabModel.contextTokens.toString(),
            )
            buildConfigField(
                "int",
                "MODEL_MAX_OUTPUT_TOKENS",
                qwen8bLabModel.maxOutputTokens.toString(),
            )
            buildConfigField(
                "boolean",
                "MODEL_SUPPORTS_IMAGE_INPUT",
                qwen8bLabModel.supportsImageInput.toString(),
            )
            buildConfigField(
                "boolean",
                "MODEL_SUPPORTS_AUDIO_INPUT",
                qwen8bLabModel.supportsAudioInput.toString(),
            )
            buildConfigField(
                "String",
                "MODEL_STORE_ROOT",
                quotedBuildConfig("personal-edge-models-qwen3-8b-v1"),
            )
            buildConfigField("boolean", "CANDIDATE_MODEL_LAB", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("qwen8bLab")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
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
