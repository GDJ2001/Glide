package dev.glide.app.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GLIDE-062: Release Candidate (RC) Artifact & Minification Compliance Verification Suite.
 *
 * Programmatically asserts release candidate packaging, R8 optimization, ProGuard rules,
 * manifest security parameters, and signing specifications.
 */
class ReleaseCandidateArtifactVerificationTest {

    private fun resolveProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "doc").isDirectory || File(current, "docs").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        return File(".").canonicalFile
    }

    // -------------------------------------------------------------------------
    // 1. R8 Minification & Resource Shrinking Configuration
    // -------------------------------------------------------------------------

    @Test
    fun test1_buildGradle_releaseBuildTypeConfiguredForR8AndMinification() {
        val root = resolveProjectRoot()
        val buildGradle = File(root, "android/app/build.gradle.kts")
        assertTrue("android/app/build.gradle.kts must exist", buildGradle.exists())

        val content = buildGradle.readText()

        // Release buildType must enable R8 code minification
        assertTrue(
            "Release buildType must enable isMinifyEnabled",
            content.contains("isMinifyEnabled = true")
        )

        // Release buildType must enable unused resource shrinking
        assertTrue(
            "Release buildType must enable isShrinkResources",
            content.contains("isShrinkResources = true")
        )

        // Proguard optimization files must be configured
        assertTrue(
            "Release buildType must include proguard-android-optimize.txt",
            content.contains("proguard-android-optimize.txt")
        )
        assertTrue(
            "Release buildType must include proguard-rules.pro",
            content.contains("proguard-rules.pro")
        )
    }

    // -------------------------------------------------------------------------
    // 2. ProGuard / R8 Rules Completeness
    // -------------------------------------------------------------------------

    @Test
    fun test2_proguardRules_preservesEssentialSymbols() {
        val root = resolveProjectRoot()
        val proguardFile = File(root, "android/app/proguard-rules.pro")
        assertTrue("android/app/proguard-rules.pro must exist", proguardFile.exists())

        val content = proguardFile.readText()

        val requiredRules = listOf(
            "SourceFile,LineNumberTable",
            "com.google.android.gms.nearby",
            "androidx.lifecycle.ViewModel",
            "androidx.room.RoomDatabase",
            "dagger.hilt",
            "kotlinx.serialization",
            "dev.glide.core.domain",
            "android.util.Log"
        )

        for (rule in requiredRules) {
            assertTrue(
                "android/app/proguard-rules.pro must contain rule for '$rule'",
                content.contains(rule)
            )
        }
    }

    // -------------------------------------------------------------------------
    // 3. Manifest Security Hardening
    // -------------------------------------------------------------------------

    @Test
    fun test3_manifest_releaseSecurityHardening() {
        val root = resolveProjectRoot()
        val manifestFile = File(root, "android/app/src/main/AndroidManifest.xml")
        assertTrue("android/app/src/main/AndroidManifest.xml must exist", manifestFile.exists())

        val content = manifestFile.readText()

        // Backup must be disabled
        assertTrue(
            "Manifest must set android:allowBackup=\"false\"",
            content.contains("android:allowBackup=\"false\"")
        )

        // Must NOT declare debuggable = true
        assertFalse(
            "Manifest must not declare android:debuggable=\"true\"",
            content.contains("android:debuggable=\"true\"")
        )

        // Must declare connectedDevice FGS type
        assertTrue(
            "Manifest must declare foregroundServiceType=\"connectedDevice\"",
            content.contains("foregroundServiceType=\"connectedDevice\"")
        )

        // Forbidden high-risk permissions
        val forbiddenPermissions = listOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.READ_PHONE_STATE",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )

        for (permission in forbiddenPermissions) {
            assertFalse(
                "Manifest must NOT declare dangerous permission: $permission",
                content.contains(permission)
            )
        }
    }

    // -------------------------------------------------------------------------
    // 4. Full Signature Scheme Coverage
    // -------------------------------------------------------------------------

    @Test
    fun test4_releaseSigningConfiguration_supportsFullSchemeCoverage() {
        val root = resolveProjectRoot()
        val buildGradle = File(root, "android/app/build.gradle.kts")
        val content = buildGradle.readText()

        assertTrue("Must declare enableV1Signing = true", content.contains("enableV1Signing = true"))
        assertTrue("Must declare enableV2Signing = true", content.contains("enableV2Signing = true"))
        assertTrue("Must declare enableV3Signing = true", content.contains("enableV3Signing = true"))
        assertTrue("Must declare enableV4Signing = true", content.contains("enableV4Signing = true"))
    }

    // -------------------------------------------------------------------------
    // 5. Target & Min SDK Specifications
    // -------------------------------------------------------------------------

    @Test
    fun test5_releaseTargetSdkVersion_isAtLeast37() {
        val root = resolveProjectRoot()
        val buildGradle = File(root, "android/app/build.gradle.kts")
        val content = buildGradle.readText()

        assertTrue("minSdk must be 26", content.contains("minSdk = 26"))
        assertTrue("targetSdk must be 37 (Android 17)", content.contains("targetSdk = 37"))
    }

    // -------------------------------------------------------------------------
    // 6. Zero Hardcoded Passwords in Build Files
    // -------------------------------------------------------------------------

    @Test
    fun test6_buildScriptHygiene_zeroHardcodedPasswordsOrTokens() {
        val root = resolveProjectRoot()
        val buildGradle = File(root, "android/app/build.gradle.kts")
        val content = buildGradle.readText()

        assertFalse(
            "Hardcoded storePassword found in android/app/build.gradle.kts",
            content.contains(Regex("""storePassword\s*=\s*"[^"]+""""))
        )
        assertFalse(
            "Hardcoded keyPassword found in android/app/build.gradle.kts",
            content.contains(Regex("""keyPassword\s*=\s*"[^"]+""""))
        )
    }
}
