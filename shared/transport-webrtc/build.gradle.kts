import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.glide.transport.webrtc"
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

dependencies {
    implementation(project(":shared:transfer-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Platform-native WebRTC library for Android
    // Note: org.webrtc:google-webrtc was deprecated with JCenter sunset; the current adapter
    // uses standard Java SE security/crypto and coroutines directly.
    // compileOnly("org.webrtc:google-webrtc:1.0.32003@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
