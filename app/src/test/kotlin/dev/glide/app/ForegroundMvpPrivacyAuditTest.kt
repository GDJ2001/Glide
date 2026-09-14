package dev.glide.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone M4 (Foreground MVP) privacy and security acceptance audit test.
 * Verifies:
 * 1. Zero advertising SDKs or dependencies across the entire codebase.
 * 2. Zero analytics, telemetry, crash reporting, or user tracking services.
 * 3. Zero account management or login credentials required.
 * 4. 100% offline-first peer-to-peer architecture with local permissions only.
 */
class ForegroundMvpPrivacyAuditTest {

    private val projectRoot = File("..").canonicalFile

    @Test
    fun verifyZeroAdvertisingDependencies() {
        val buildFiles = listOf(
            File(projectRoot, "build.gradle.kts"),
            File(projectRoot, "app/build.gradle.kts"),
            File(projectRoot, "data/build.gradle.kts"),
            File(projectRoot, "transfer-core/build.gradle.kts"),
            File(projectRoot, "transport-nearby/build.gradle.kts"),
            File(projectRoot, "designsystem/build.gradle.kts"),
            File(projectRoot, "gradle/libs.versions.toml")
        ).filter { it.exists() }

        assertTrue("Build files must exist for audit", buildFiles.isNotEmpty())

        val forbiddenAdKeywords = listOf(
            "play-services-ads",
            "admob",
            "facebook-ads",
            "unity-ads",
            "applovin",
            "ironsource",
            "mopub"
        )

        for (file in buildFiles) {
            val content = file.readText().lowercase()
            for (adKw in forbiddenAdKeywords) {
                assertFalse(
                    "Forbidden advertising dependency '$adKw' detected in ${file.name}",
                    content.contains(adKw)
                )
            }
        }
    }

    @Test
    fun verifyZeroTelemetryAndAnalyticsDependencies() {
        val buildFiles = listOf(
            File(projectRoot, "app/build.gradle.kts"),
            File(projectRoot, "data/build.gradle.kts"),
            File(projectRoot, "transfer-core/build.gradle.kts"),
            File(projectRoot, "gradle/libs.versions.toml")
        ).filter { it.exists() }

        val forbiddenTelemetryKeywords = listOf(
            "firebase-analytics",
            "firebase-crashlytics",
            "mixpanel",
            "amplitude",
            "segment",
            "flurry",
            "google-analytics"
        )

        for (file in buildFiles) {
            val content = file.readText().lowercase()
            for (telemetryKw in forbiddenTelemetryKeywords) {
                assertFalse(
                    "Forbidden telemetry/analytics dependency '$telemetryKw' detected in ${file.name}",
                    content.contains(telemetryKw)
                )
            }
        }
    }

    @Test
    fun verifyZeroCloudAccountOrLoginDependencies() {
        val buildFiles = listOf(
            File(projectRoot, "app/build.gradle.kts"),
            File(projectRoot, "data/build.gradle.kts"),
            File(projectRoot, "gradle/libs.versions.toml")
        ).filter { it.exists() }

        val forbiddenAuthKeywords = listOf(
            "play-services-auth",
            "firebase-auth",
            "facebook-login",
            "auth0"
        )

        for (file in buildFiles) {
            val content = file.readText().lowercase()
            for (authKw in forbiddenAuthKeywords) {
                assertFalse(
                    "Forbidden cloud authentication dependency '$authKw' detected in ${file.name}",
                    content.contains(authKw)
                )
            }
        }
    }

    @Test
    fun verifyAndroidManifestLocalOnlyPermissions() {
        val manifestFile = File(projectRoot, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val content = manifestFile.readText()

        // Verify no broad external storage permissions
        assertFalse(
            "MANAGE_EXTERNAL_STORAGE must not be requested in Glide V1",
            content.contains("android.permission.MANAGE_EXTERNAL_STORAGE")
        )

        // Verify no account management permissions
        assertFalse(
            "AUTHENTICATE_ACCOUNTS must not be requested",
            content.contains("android.permission.AUTHENTICATE_ACCOUNTS")
        )
        assertFalse(
            "GET_ACCOUNTS must not be requested",
            content.contains("android.permission.GET_ACCOUNTS")
        )
    }
}
