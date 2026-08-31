plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.personaledge.core.agent"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    buildTypes {
        create("qwen8bLab") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
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
    api(project(":core:llm"))
    api(project(":core:tools"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
