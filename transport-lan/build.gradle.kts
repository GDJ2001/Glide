import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.glide.transport.lan"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

// HARD-003 red-before-fix browser regressions run only when explicitly requested.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("hard003Regression").orNull == "true") {
        filter {
            includeTestsMatching("*Hard003*")
        }
    } else {
        exclude("**/Hard003*")
    }
}

dependencies {
    implementation(project(":transfer-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
