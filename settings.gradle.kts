pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Glide"
include(":app")
include(":transfer-core")
include(":transport-nearby")
include(":transport-lan")
include(":data")
include(":designsystem")
include(":benchmark")
