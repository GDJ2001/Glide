import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.glide.app"
    compileSdk = 37

    val customApplicationId = providers.environmentVariable("GLIDE_APPLICATION_ID")
        .orElse(providers.gradleProperty("glide.applicationId"))
        .getOrElse("dev.glide.app")

    val customVersionCode = providers.environmentVariable("GLIDE_VERSION_CODE")
        .orElse(providers.gradleProperty("glide.versionCode"))
        .map { it.toInt() }
        .getOrElse(2)

    val customVersionName = providers.environmentVariable("GLIDE_VERSION_NAME")
        .orElse(providers.gradleProperty("glide.versionName"))
        .getOrElse("1.0.0")

    val releaseKeystorePath = providers.environmentVariable("GLIDE_RELEASE_KEYSTORE_PATH")
        .orElse(providers.gradleProperty("glide.release.keystorePath"))
        .orNull

    val releaseKeystorePassword = providers.environmentVariable("GLIDE_RELEASE_KEYSTORE_PASSWORD")
        .orElse(providers.gradleProperty("glide.release.keystorePassword"))
        .orNull

    val releaseKeyAlias = providers.environmentVariable("GLIDE_RELEASE_KEY_ALIAS")
        .orElse(providers.gradleProperty("glide.release.keyAlias"))
        .orNull

    val releaseKeyPassword = providers.environmentVariable("GLIDE_RELEASE_KEY_PASSWORD")
        .orElse(providers.gradleProperty("glide.release.keyPassword"))
        .orNull

    val isReleaseSigningConfigured = !releaseKeystorePath.isNullOrBlank() &&
        File(releaseKeystorePath).exists() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (isReleaseSigningConfigured) {
                storeFile = File(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = customApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = customVersionCode
        versionName = customVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += listOf(
            "GradleDependency",
            "AndroidGradlePluginVersion"
        )
    }
}

// HARD-003 intentionally adds red-before-fix regressions. Keep them out of the ordinary
// green baseline, and run them explicitly with -Phard003Regression=true.
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose BOM & UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation 3 & Serialization
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)

    // Modules
    implementation(project(":android:core"))
    implementation(project(":android:designsystem"))
    implementation(project(":android:data"))
    implementation(project(":android:transport-nearby"))
    implementation(project(":android:transport-lan"))

    // Room (for DatabaseModule)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // CameraX (LAN-017)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ZXing (LAN-017)
    implementation(libs.zxing.core)

    // DI Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Baseline Profiles & ProfileInstaller
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
