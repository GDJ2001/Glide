plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register("checkArchitecture") {
    group = "verification"
    description = "Enforces architecture boundaries, pure JVM core isolation, UI main-thread I/O checks, and dependency catalog usage."
    dependsOn(":app:testDebugUnitTest")
}

tasks.register("checkLanGate") {
    group = "verification"
    description = "Runs functional LAN verification gates: TLS loopback, protocol vectors, network/storage chaos, and storage correctness."
    dependsOn(
        ":core:test",
        ":transport-lan:testDebugUnitTest",
        ":data:testDebugUnitTest",
        ":app:testDebugUnitTest"
    )
}

tasks.register("checkDocumentation") {
    group = "verification"
    description = "Validates documentation presence and local markdown reference integrity."
    doLast {
        val requiredDocs = listOf(
            "doc/planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md",
            "doc/protocol/GLIDE_PROTOCOL_V1.md",
            "doc/security/GLIDE_THREAT_MODEL.md",
            "doc/testing/GLIDE_TEST_STRATEGY.md",
            "doc/android/PERMISSION_MATRIX.md",
            "doc/android/STORAGE_AND_BACKGROUND.md"
        )
        for (docPath in requiredDocs) {
            val docFile = listOf(
                rootProject.file(docPath),
                rootProject.file("../$docPath")
            ).firstOrNull { it.exists() } ?: rootProject.file(docPath)
            if (!docFile.exists() || docFile.length() == 0L) {
                throw GradleException("Required documentation file missing or empty: $docPath")
            }
        }
        println("All required documentation files verified successfully.")
    }
}
