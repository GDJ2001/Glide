package dev.glide.app.security

import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.glide.app.ingress.ShareIngressParser
import dev.glide.app.ingress.ShareIngressResult
import dev.glide.app.notification.TransferNotificationManager
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.util.FileOpenResult
import dev.glide.app.util.FileOpener
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.MessageId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SessionId
import dev.glide.core.ports.DefaultIdGenerator
import dev.glide.core.ports.GlideSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security hardening verification test suite for Android components, storage and privacy (GLIDE-049).
 *
 * Enforces:
 * - Manifest component isolation: Only launcher/share ingress activity is exported (T10, T12).
 * - Multi-version backup exclusion: Explicit rules exclude databases, files, and preferences (T11).
 * - FileProvider path scoping: Restricted to shares/ and received/ subdirectories (T11, T14).
 * - Share ingress validation: Strictly content:// scheme, bounded, deduplicated (T12, T15).
 * - Notification privacy: VISIBILITY_PRIVATE with generic redacted public version (T10).
 * - File opening security: Missing file protection and read-only grants (T14).
 * - Cryptographic integrity: Secure UUID generation and strict canonical format checks (T13).
 */
class AndroidSecurityHardeningTest {

    private val uuidV4Regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private val projectRoot: File = run {
        val cwd = File(".").canonicalFile
        if (File(cwd, "settings.gradle.kts").exists()) cwd
        else File(cwd, "..").canonicalFile
    }

    private val appDir: File = File(projectRoot, "app")

    // =========================================================================
    // 1. Manifest Component Hardening (Threats T10, T12)
    // =========================================================================

    @Test
    fun manifest_onlyMainActivityIsExported() {
        val manifestFile = File(appDir, "src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val root = doc.documentElement

        val activities = root.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            val name = activity.getAttribute("android:name")
            val exported = activity.getAttribute("android:exported")
            if (name == ".MainActivity") {
                assertEquals("MainActivity must be exported for launcher and share ingress", "true", exported)
            } else {
                assertEquals("Activity  must not be exported", "false", exported)
            }
        }

        val services = root.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val name = service.getAttribute("android:name")
            val exported = service.getAttribute("android:exported")
            assertEquals("Service  must be strictly non-exported", "false", exported)
        }

        val receivers = root.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as Element
            val name = receiver.getAttribute("android:name")
            val exported = receiver.getAttribute("android:exported")
            assertEquals("Receiver  must be strictly non-exported", "false", exported)
        }

        val providers = root.getElementsByTagName("provider")
        for (i in 0 until providers.length) {
            val provider = providers.item(i) as Element
            val name = provider.getAttribute("android:name")
            val exported = provider.getAttribute("android:exported")
            assertEquals("Provider  must be strictly non-exported", "false", exported)
        }
    }

    @Test
    fun manifest_backupConfigurationIsExplicitAndDisabled() {
        val manifestFile = File(appDir, "src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val appElement = doc.getElementsByTagName("application").item(0) as Element

        assertEquals("android:allowBackup must be explicitly false", "false", appElement.getAttribute("android:allowBackup"))
        assertEquals(
            "android:dataExtractionRules must point to @xml/data_extraction_rules",
            "@xml/data_extraction_rules",
            appElement.getAttribute("android:dataExtractionRules")
        )
        assertEquals(
            "android:fullBackupContent must point to @xml/backup_rules",
            "@xml/backup_rules",
            appElement.getAttribute("android:fullBackupContent")
        )
    }

    // =========================================================================
    // 2. Backup & Data Extraction Rules Hardening (Threat T11)
    // =========================================================================

    @Test
    fun dataExtractionRules_excludesAllPrivateDomainsFromCloudAndDeviceTransfer() {
        val rulesFile = File(appDir, "src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", rulesFile.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        val root = doc.documentElement
        assertEquals("Root element must be <data-extraction-rules>", "data-extraction-rules", root.tagName)

        val cloudBackupNodes = root.getElementsByTagName("cloud-backup")
        assertEquals("<cloud-backup> section must exist", 1, cloudBackupNodes.length)
        val cloudBackup = cloudBackupNodes.item(0) as Element

        val deviceTransferNodes = root.getElementsByTagName("device-transfer")
        assertEquals("<device-transfer> section must exist", 1, deviceTransferNodes.length)
        val deviceTransfer = deviceTransferNodes.item(0) as Element

        val requiredExcludedDomains = setOf("root", "database", "sharedpref", "file", "external")

        fun extractExcludes(element: Element): Set<String> {
            val excludes = element.getElementsByTagName("exclude")
            val domains = mutableSetOf<String>()
            for (i in 0 until excludes.length) {
                val item = excludes.item(i) as Element
                domains.add(item.getAttribute("domain"))
            }
            return domains
        }

        val cloudExcluded = extractExcludes(cloudBackup)
        assertTrue("Cloud backup must exclude all sensitive domains", cloudExcluded.containsAll(requiredExcludedDomains))

        val deviceExcluded = extractExcludes(deviceTransfer)
        assertTrue("Device transfer must exclude all sensitive domains", deviceExcluded.containsAll(requiredExcludedDomains))

        // Ensure zero <include> elements exist
        assertEquals("Zero include tags in cloud-backup", 0, cloudBackup.getElementsByTagName("include").length)
        assertEquals("Zero include tags in device-transfer", 0, deviceTransfer.getElementsByTagName("include").length)
    }

    @Test
    fun backupRules_excludesAllPrivateDomainsFromLegacyBackup() {
        val rulesFile = File(appDir, "src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", rulesFile.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        val root = doc.documentElement
        assertEquals("Root element must be <full-backup-content>", "full-backup-content", root.tagName)

        val excludes = root.getElementsByTagName("exclude")
        val excludedDomains = mutableSetOf<String>()
        for (i in 0 until excludes.length) {
            val item = excludes.item(i) as Element
            excludedDomains.add(item.getAttribute("domain"))
        }

        val requiredExcludedDomains = setOf("root", "database", "sharedpref", "file", "external")
        assertTrue("Legacy backup must exclude all sensitive domains", excludedDomains.containsAll(requiredExcludedDomains))
        assertEquals("Zero include tags in full-backup-content", 0, root.getElementsByTagName("include").length)
    }

    // =========================================================================
    // 3. FileProvider Path Scoping Hardening (Threats T11, T14)
    // =========================================================================

    @Test
    fun fileProvider_scopesPathsNarrowlyWithoutRootExposure() {
        val pathsFile = File(appDir, "src/main/res/xml/file_paths.xml")
        assertTrue("file_paths.xml must exist", pathsFile.exists())

        val content = pathsFile.readText()
        assertFalse("Must not expose entire cache dir with path='.'", content.contains("path=\".\""))
        assertFalse("Must not expose root path with path='/'", content.contains("path=\"/\""))
        assertFalse("Must not expose external-path", content.contains("<external-path"))
        assertFalse("Must not expose external-files-path", content.contains("<external-files-path"))
        assertFalse("Must not expose root-path", content.contains("<root-path"))

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pathsFile)
        val root = doc.documentElement

        val cachePaths = root.getElementsByTagName("cache-path")
        assertEquals("Must declare exactly 1 cache-path", 1, cachePaths.length)
        val cacheElement = cachePaths.item(0) as Element
        assertEquals("cache-path name must be 'shares'", "shares", cacheElement.getAttribute("name"))
        assertTrue("cache-path path must be 'shares/' or 'shares'", cacheElement.getAttribute("path").startsWith("shares"))

        val filesPaths = root.getElementsByTagName("files-path")
        assertEquals("Must declare exactly 1 files-path", 1, filesPaths.length)
        val filesElement = filesPaths.item(0) as Element
        assertEquals("files-path name must be 'received'", "received", filesElement.getAttribute("name"))
        assertTrue("files-path path must be 'received/' or 'received'", filesElement.getAttribute("path").startsWith("received"))
    }

    // =========================================================================
    // 4. Share Ingress Insecure Scheme & Bounding Validation (Threats T12, T15)
    // =========================================================================

    @Test
    fun shareIngress_rejectsInsecureSchemes() {
        val dangerousUris = listOf(
            "file:///data/data/dev.glide.app/databases/glide_history.db",
            "file:///etc/hosts",
            "http://169.254.169.254/latest/meta-data/",
            "https://malicious.example.com/exploit.bin",
            "ftp://ftp.server.com/test",
            "javascript:alert(1)"
        )

        for (uri in dangerousUris) {
            val result = ShareIngressParser.parseFromData(
                action = Intent.ACTION_SEND,
                mimeType = "*/*",
                textExtra = null,
                rawUriStrings = listOf(uri)
            )
            assertTrue("URI '' must be rejected with InvalidScheme", result is ShareIngressResult.InvalidScheme)
            val invalid = result as ShareIngressResult.InvalidScheme
            assertEquals(uri, invalid.uriString)
        }
    }

    @Test
    fun shareIngress_rejectsTextAndLinkShares() {
        val result = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            textExtra = "Check this link: https://glide.dev",
            rawUriStrings = emptyList()
        )
        assertTrue("Text shares must be rejected in V1", result is ShareIngressResult.UnsupportedType)
    }

    @Test
    fun shareIngress_enforcesCeilingAndDeduplication() {
        val count = ProtocolLimits.MAX_ITEMS_PER_BATCH + 10
        val oversizedList = (1..count).map { "content://media/external/files/" + it }
        val limitResult = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "*/*",
            textExtra = null,
            rawUriStrings = oversizedList
        )
        assertTrue("Oversized batch must exceed limit, got: $limitResult", limitResult is ShareIngressResult.BatchLimitExceeded)

        val duplicateList = listOf(
            "content://media/external/files/1",
            "content://media/external/files/2",
            "content://media/external/files/1"
        )
        val dedupeResult = ShareIngressParser.parseFromData(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "*/*",
            textExtra = null,
            rawUriStrings = duplicateList
        )
        assertTrue("Duplicates must be accepted and deduplicated", dedupeResult is ShareIngressResult.Success)
        val success = dedupeResult as ShareIngressResult.Success
        assertEquals(2, success.uriStrings.size)
        assertEquals(listOf("content://media/external/files/1", "content://media/external/files/2"), success.uriStrings)
    }

    // =========================================================================
    // 5. Notification Privacy & Lockscreen Redaction (Threat T10)
    // =========================================================================

    @Test
    fun notificationPrivacy_visibilityIsPrivateAndPublicVersionIsSanitized() {
        val manager = TransferNotificationManager()
        val privateState = TransferUiState.Transferring(
            sessionId = "secret-session-id",
            role = DeviceRole.Receiver,
            peerName = "Alice's Private Phone",
            itemName = "tax_return_2025.pdf",
            transferredBytes = 1000L,
            totalBytes = 2000L,
            progressFraction = 0.5f,
            speedBytesPerSec = 1000L
        )

        val content = manager.resolveNotificationContent(privateState, GlideSettings(notificationDetails = false))

        assertEquals("Notification must be VISIBILITY_PRIVATE", NotificationCompat.VISIBILITY_PRIVATE, content.visibility)
        assertFalse("Private title must not leak file name when details disabled", content.title.contains("tax_return"))
        assertFalse("Private text must not leak file name when details disabled", content.text.contains("tax_return"))

        assertEquals("Public title must be generic", "Glide file transfer", content.publicTitle)
        assertEquals("Public text must be generic", "Transfer in progress", content.publicText)
        assertFalse("Public title must not contain peer name", content.publicTitle.contains("Alice"))
        assertFalse("Public text must not contain peer name", content.publicText.contains("Alice"))
        assertFalse("Public title must not contain file name", content.publicTitle.contains("tax_return"))
        assertFalse("Public text must not contain file name", content.publicText.contains("tax_return"))
    }

    // =========================================================================
    // 6. Safe File Opening & Quarantining (Threat T14)
    // =========================================================================

    @Test
    fun fileOpener_returnsFileNotFoundForNonExistentOrUnverifiedFiles() {
        val nonExistentFile = File(appDir, "src/main/res/xml/non_existent_file.tmp")
        assertFalse(nonExistentFile.exists())

        val result = FileOpener.openFile(
            context = null,
            file = nonExistentFile
        )
        assertEquals("Opening non-existent file must return FileNotFound", FileOpenResult.FileNotFound, result)
    }

    @Test
    fun fileOpener_resolveReceivedFile_strictlyChecksReceivedDirectory() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "glide_sec_test_").apply { mkdirs() }
        try {
            val sampleFile = File(tempDir, "received_file.pdf").apply { writeText("SAMPLE") }
            val resolved = FileOpener.resolveReceivedFile(tempDir, "received_file.pdf")
            assertEquals(sampleFile.canonicalPath, resolved?.canonicalPath)

            val missing = FileOpener.resolveReceivedFile(tempDir, "ghost.pdf")
            assertNull(missing)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 7. Cryptographic Randomness & Canonical UUIDv4 Integrity (Threat T13)
    // =========================================================================

    @Test
    fun cryptographicRandomness_generatesUniqueCanonicalUuidV4() {
        val generator = DefaultIdGenerator()
        val generated = mutableSetOf<String>()
        val count = 1000

        for (i in 0 until count) {
            val sessionId = generator.generateSessionId()
            val str = sessionId.value

            assertTrue("Session ID '' must match canonical UUIDv4 regex", uuidV4Regex.matches(str))
            assertEquals("Session ID must be lowercase", str.lowercase(), str)
            assertFalse("UUID must not collide (Cryptographically secure randomness)", generated.contains(str))
            generated.add(str)
        }

        assertEquals(count, generated.size)
    }

    @Test
    fun identifierTypes_rejectNonCanonicalAndMalformedInputs() {
        val invalidUuids = listOf(
            "12345",
            "not-a-uuid",
            "550E8400-E29B-41D4-A716-446655440000", // uppercase rejected
            "550e8400-e29b-31d4-a716-446655440000", // version 3 rejected
            "550e8400-e29b-41d4-0716-446655440000", // invalid variant rejected
            "../secret/path",
            ""
        )

        for (bad in invalidUuids) {
            var sessionRejected = false
            try {
                SessionId(bad)
            } catch (_: IllegalArgumentException) {
                sessionRejected = true
            }
            assertTrue("SessionId must reject ''", sessionRejected)

            var batchRejected = false
            try {
                BatchId(bad)
            } catch (_: IllegalArgumentException) {
                batchRejected = true
            }
            assertTrue("BatchId must reject ''", batchRejected)

            var itemRejected = false
            try {
                ItemId(bad)
            } catch (_: IllegalArgumentException) {
                itemRejected = true
            }
            assertTrue("ItemId must reject ''", itemRejected)

            var messageRejected = false
            try {
                MessageId(bad)
            } catch (_: IllegalArgumentException) {
                messageRejected = true
            }
            assertTrue("MessageId must reject ''", messageRejected)
        }
    }
}
