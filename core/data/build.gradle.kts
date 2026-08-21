plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.personaledge.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

room {
    // Committed schemas make every future migration reviewable instead of destructive.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // api, not implementation: PersonalEdgeDatabase extends RoomDatabase, so Room types are part
    // of this module's public surface and consumers need them to hold or close the database.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // api for the same reason as Room: SettingsRepository's constructor takes a
    // DataStore<Preferences>, so the type is part of this module's public surface.
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
