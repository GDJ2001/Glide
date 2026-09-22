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
include(":android")
include(":apple")
include(":windows")
include(":linux")
include(":shared:transfer-core")
include(":shared:transport-lan")
include(":shared:transport-nearby")
include(":shared:transport-webrtc")
include(":shared:data")
include(":shared:designsystem")
include(":shared:protocol")
include(":benchmark")
