pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Glide"
if (settingsDir.name == "android") {
    rootProject.projectDir = settingsDir.parentFile
}
include(":android:app")
include(":android:core")
include(":android:data")
include(":android:designsystem")
include(":android:transport-lan")
include(":android:transport-nearby")
include(":android:transport-webrtc")
include(":android:benchmark")
