import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Host filesystem performance measurements are intentionally opt-in.
// Host filesystem performance measurements are intentionally opt-in.
// Shared CI runners are not authoritative for disk throughput/latency budgets.
// The functional LAN gate must not turn shared-runner disk contention into a protocol failure.
// Run the benchmark explicitly with: ./gradlew :data:testDebugUnitTest -PrunStorageBenchmarks=true
val runStorageBenchmarks = providers
    .gradleProperty("runStorageBenchmarks")
    .map(String::toBoolean)
    .orElse(false)

tasks.withType<Test>().configureEach {
    if (!runStorageBenchmarks.get()) {
        filter {
            excludeTestsMatching("dev.glide.data.performance.StorageStreamingBenchmarkTest")
        }
    }
}

android {
    namespace = "dev.glide.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":shared:transfer-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}