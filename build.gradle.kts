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
