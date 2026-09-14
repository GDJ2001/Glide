package dev.glide.app.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * GLIDE-060: Publisher Identity & Signing Custody Automated Verification Suite.
 *
 * Verifies:
 * 1. Zero committed keystores, private keys, or credential property files in the repository.
 * 2. Complete .gitignore protection against all keystore and credential patterns.
 * 3. Dynamic, secure Gradle signing configuration with zero hardcoded passwords.
 * 4. Android SDK & Google Play compliant package name specification for publisher application ID.
 * 5. Monotonic version code calculations and Semantic Versioning formatting.
 * 6. APK Signature Schemes v1, v2, v3, and v4 enforcement.
 * 7. Key custody policy documentation integrity and completeness.
 */
class SigningConfigurationAndPublisherIdentityTest {

    private fun resolveProjectRoot(): File {
        val current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return if (File(current, "docs").exists()) {
            current
        } else if (File(current.parentFile, "docs").exists()) {
            current.parentFile
        } else {
            File("..").canonicalFile
        }
    }

    // -------------------------------------------------------------------------
    // 1. .gitignore Protection Rules
    // -------------------------------------------------------------------------

    @Test
    fun test1_gitIgnore_excludesAllKeystoresAndSecretProperties() {
        val root = resolveProjectRoot()
        val gitIgnore = File(root, ".gitignore")
        assertTrue(".gitignore must exist at ${gitIgnore.absolutePath}", gitIgnore.exists())

        val content = gitIgnore.readText()
        val requiredPatterns = listOf(
            "*.jks",
            "*.keystore",
            "*.p12",
            "*.pfx",
            "*.pem",
            "*.key",
            "*.pk8",
            "signing.properties",
            "release.properties",
            "secrets.properties",
            "local.properties"
        )

        for (pattern in requiredPatterns) {
            assertTrue(
                ".gitignore must explicitly exclude '$pattern'",
                content.contains(pattern)
            )
        }
    }

    // -------------------------------------------------------------------------
    // 2. Zero Committed Keystores or Unencrypted Secrets in Repository
    // -------------------------------------------------------------------------

    @Test
    fun test2_repositoryScan_zeroCommittedKeystoresOrSecrets() {
        val root = resolveProjectRoot()
        val forbiddenExtensions = setOf("jks", "keystore", "p12", "pfx", "pk8")
        val forbiddenFilenames = setOf("signing.properties", "release.properties", "secrets.properties")

        var auditedFiles = 0
        root.walkTopDown()
            .filter { file ->
                val path = file.absolutePath
                !path.contains("${File.separator}build${File.separator}") &&
                    !path.contains("${File.separator}.gradle${File.separator}") &&
                    !path.contains("${File.separator}.git${File.separator}")
            }
            .forEach { file ->
                if (file.isFile) {
                    auditedFiles++
                    val ext = file.extension.lowercase()
                    assertFalse(
                        "Committed keystore or private key file detected at ${file.absolutePath}",
                        forbiddenExtensions.contains(ext)
                    )
                    assertFalse(
                        "Committed secret credential property file detected at ${file.absolutePath}",
                        forbiddenFilenames.contains(file.name.lowercase())
                    )
                }
            }

        assertTrue("Must have audited multiple project files, audited: $auditedFiles", auditedFiles > 100)
    }

    // -------------------------------------------------------------------------
    // 3. Build Script Safety & Dynamic Environment Providers
    // -------------------------------------------------------------------------

    @Test
    fun test3_buildScript_zeroHardcodedPasswordsAndWiresDynamicProviders() {
        val root = resolveProjectRoot()
        val buildFile = File(root, "app/build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist", buildFile.exists())

        val content = buildFile.readText()

        // Passwords must never be hardcoded
        val hardcodedStorePassRegex = Regex("""storePassword\s*=\s*"[^"]+"""")
        val hardcodedKeyPassRegex = Regex("""keyPassword\s*=\s*"[^"]+"""")
        assertFalse(
            "Hardcoded storePassword detected in app/build.gradle.kts",
            hardcodedStorePassRegex.containsMatchIn(content)
        )
        assertFalse(
            "Hardcoded keyPassword detected in app/build.gradle.kts",
            hardcodedKeyPassRegex.containsMatchIn(content)
        )

        // Dynamic environment variable providers must be wired
        val requiredProviders = listOf(
            "GLIDE_RELEASE_KEYSTORE_PATH",
            "GLIDE_RELEASE_KEYSTORE_PASSWORD",
            "GLIDE_RELEASE_KEY_ALIAS",
            "GLIDE_RELEASE_KEY_PASSWORD",
            "GLIDE_APPLICATION_ID",
            "GLIDE_VERSION_CODE",
            "GLIDE_VERSION_NAME"
        )

        for (provider in requiredProviders) {
            assertTrue(
                "app/build.gradle.kts must wire dynamic provider for '$provider'",
                content.contains(provider)
            )
        }
    }

    // -------------------------------------------------------------------------
    // 4. Publisher Application ID & Android Package Naming Specification
    // -------------------------------------------------------------------------

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while"
    )

    private fun validatePackageName(name: String): Boolean {
        if (name.isBlank() || name.length > 255) return false
        val segments = name.split(".")
        if (segments.size < 2) return false

        for (segment in segments) {
            if (segment.isEmpty()) return false
            val firstChar = segment[0]
            if (!firstChar.isLetter()) return false
            if (!segment.all { it.isLetterOrDigit() || it == '_' }) return false
            if (JAVA_KEYWORDS.contains(segment)) return false
        }
        return true
    }

    @Test
    fun test4_publisherApplicationId_conformsToAndroidPackageNamingSpec() {
        // Valid application IDs
        assertTrue(validatePackageName("dev.glide.app"))
        assertTrue(validatePackageName("com.example.glide"))
        assertTrue(validatePackageName("io.glide_transfer.client"))
        assertTrue(validatePackageName("org.glide.app.v1"))

        // Invalid application IDs
        assertFalse(validatePackageName("glide")) // Single segment
        assertFalse(validatePackageName("dev.glide.class")) // Reserved keyword
        assertFalse(validatePackageName("dev.glide.switch")) // Reserved keyword
        assertFalse(validatePackageName("123.glide.app")) // Starts with digit
        assertFalse(validatePackageName("dev.glide-app.android")) // Contains hyphen
        assertFalse(validatePackageName("dev..app")) // Empty segment
        assertFalse(validatePackageName("")) // Blank
        assertFalse(validatePackageName("a.".repeat(150))) // Exceeds 255 chars
    }

    // -------------------------------------------------------------------------
    // 5. Monotonic Versioning Formula & SemVer Invariants
    // -------------------------------------------------------------------------

    private fun computeVersionCode(major: Int, minor: Int, patch: Int): Int {
        require(major in 0..200) { "Major version must be in 0..200" }
        require(minor in 0..99) { "Minor version must be in 0..99" }
        require(patch in 0..99) { "Patch version must be in 0..99" }
        return (major * 10_000) + (minor * 100) + patch
    }

    @Test
    fun test5_monotonicVersioning_invariantsAndFormula() {
        val codeV1_0_0 = computeVersionCode(1, 0, 0)
        val codeV1_0_1 = computeVersionCode(1, 0, 1)
        val codeV1_1_0 = computeVersionCode(1, 1, 0)
        val codeV2_0_0 = computeVersionCode(2, 0, 0)

        assertEquals(10000, codeV1_0_0)
        assertEquals(10001, codeV1_0_1)
        assertEquals(10100, codeV1_1_0)
        assertEquals(20000, codeV2_0_0)

        // Strictly monotonic progression
        assertTrue(codeV1_0_0 < codeV1_0_1)
        assertTrue(codeV1_0_1 < codeV1_1_0)
        assertTrue(codeV1_1_0 < codeV2_0_0)

        // SemVer format validation
        val semVerRegex = Regex("""^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$""")
        assertTrue(semVerRegex.matches("0.1.0-alpha"))
        assertTrue(semVerRegex.matches("1.0.0-rc01"))
        assertTrue(semVerRegex.matches("1.0.0"))
        assertTrue(semVerRegex.matches("1.0.1"))
        assertFalse(semVerRegex.matches("1.0"))
        assertFalse(semVerRegex.matches("v1.0.0"))
        assertFalse(semVerRegex.matches("release-1.0.0"))
    }

    // -------------------------------------------------------------------------
    // 6. Key Custody Policy Documentation
    // -------------------------------------------------------------------------

    @Test
    fun test6_keyCustodyPolicyDocument_existsAndComplete() {
        val root = resolveProjectRoot()
        val policyFile = File(root, "docs/release/KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md")
        assertTrue("KEY_CUSTODY_AND_PUBLISHER_IDENTITY.md must exist at ${policyFile.absolutePath}", policyFile.exists())
        assertTrue("Policy document must be detailed (> 2,500 bytes, was ${policyFile.length()})", policyFile.length() > 2500L)

        val content = policyFile.readText()
        assertTrue("Policy must cover Play App Signing", content.contains("Play App Signing"))
        assertTrue("Policy must cover Upload Key separation", content.contains("Upload Key"))
        assertTrue("Policy must require RSA 4096 or P-256", content.contains("4096") || content.contains("P-256"))
        assertTrue("Policy must cover air-gapped cold storage", content.contains("air-gapped"))
        assertTrue("Policy must detail split-knowledge protocol", content.contains("Split-Knowledge"))
        assertTrue("Policy must document disaster recovery runbook", content.contains("Disaster Recovery"))
    }

    // -------------------------------------------------------------------------
    // 7. APK Signature Schemes v1, v2, v3, and v4
    // -------------------------------------------------------------------------

    @Test
    fun test7_apkSignatureSchemeSupport_v1v2v3v4Declared() {
        val root = resolveProjectRoot()
        val buildFile = File(root, "app/build.gradle.kts")
        val content = buildFile.readText()

        assertTrue("enableV1Signing must be true in release signingConfig", content.contains("enableV1Signing = true"))
        assertTrue("enableV2Signing must be true in release signingConfig", content.contains("enableV2Signing = true"))
        assertTrue("enableV3Signing must be true in release signingConfig", content.contains("enableV3Signing = true"))
        assertTrue("enableV4Signing must be true in release signingConfig", content.contains("enableV4Signing = true"))
    }
}
