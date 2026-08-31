plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

val testReleaseSupplyChain = tasks.register<Exec>("testReleaseSupplyChain") {
    group = "verification"
    description = "Run deterministic release SBOM and provenance host tests."
    workingDir(rootProject.projectDir)
    inputs.files(
        rootProject.file("scripts/generate-release-sbom.sh"),
        rootProject.file("scripts/generate-release-provenance.sh"),
        rootProject.file("scripts/verify-release-provenance.sh"),
        rootProject.file("scripts/test-release-provenance.sh"),
    )
    commandLine(
        "bash",
        rootProject.file("scripts/test-release-provenance.sh").absolutePath,
    )
}

val testHostScripts = tasks.register<Exec>("testHostScripts") {
    group = "verification"
    description = "Run the repository's deterministic host script policy tests."
    workingDir(rootProject.projectDir)
    commandLine(
        "bash",
        rootProject.file("scripts/test-host-scripts.sh").absolutePath,
    )
}

tasks.register("releaseGate") {
    group = "verification"
    description = "Run host tests, lint, release assembly, and packaged supply-chain verification."
    dependsOn(testReleaseSupplyChain)
    dependsOn(testHostScripts)
    dependsOn(
        ":app:test",
        ":core:agent:test",
        ":core:data:test",
        ":core:diagnostics:test",
        ":core:llm:test",
        ":core:tools:test",
        ":app:lint",
        ":core:agent:lint",
        ":core:data:lint",
        ":core:diagnostics:lint",
        ":core:llm:lint",
        ":core:tools:lint",
        ":app:verifyReleaseProvenance",
    )
}
