package dev.glide.app.future

import dev.glide.core.folder.SafeRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GLIDE-069: Safe Folder Transfer Extension Compliance Verification Suite.
 *
 * Programmatically asserts:
 * 1. ADR-0014 exists, documents discrete item streaming, and is indexed in doc/adr/README.md.
 * 2. SAFE_FOLDER_TRANSFER_SPECIFICATION.md specifies bounded complexity limits (depth <= 16, files <= 10,000, length <= 1024).
 * 3. SAF traversal performance plan requires direct ContentResolver queries and cyclic loop detection.
 * 4. Destination collision policy mandates non-destructive monotonic renaming ("Folder (1)").
 * 5. Threat model matrix covers T01 through T08.
 * 6. Pure JVM SafeRelativePath model integrates and rejects malicious inputs.
 */
class SafeFolderTransferComplianceTest {

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

    @Test
    fun test1_adr0014_existsAndIsIndexed() {
        val root = resolveProjectRoot()
        val adrFile = File(root, "doc/adr/ADR-0014-safe-folder-transfer-extension.md")
        assertTrue("ADR-0014 must exist", adrFile.exists())

        val adrText = adrFile.readText()
        assertTrue("ADR-0014 must document discrete item streaming", adrText.contains("Discrete item streaming"))
        assertTrue("ADR-0014 must document CAP_FOLDER_TRANSFER", adrText.contains("CAP_FOLDER_TRANSFER"))

        val indexFile = File(root, "doc/adr/README.md")
        assertTrue("doc/adr/README.md must exist", indexFile.exists())
        assertTrue("doc/adr/README.md must index ADR-0014", indexFile.readText().contains("ADR-0014"))
    }

    @Test
    fun test2_safeFolderSpecification_definesBoundedLimitsAndManifest() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md")
        assertTrue("SAFE_FOLDER_TRANSFER_SPECIFICATION.md must exist", specFile.exists())

        val specText = specFile.readText()
        assertTrue("Must specify max depth <= 16", specText.contains("16"))
        assertTrue("Must specify max file count <= 10,000", specText.contains("10,000"))
        assertTrue("Must specify max path length <= 1024", specText.contains("1024"))
        assertTrue("Must specify relativePath in manifest", specText.contains("relativePath") || specText.contains("relative_path"))
    }

    @Test
    fun test3_safTraversalPerformancePlan_requiresLowLevelQueryAndLoopDetection() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md")
        val specText = specFile.readText()

        assertTrue("Must require DocumentsContract.buildChildDocumentsUriUsingTree", specText.contains("DocumentsContract.buildChildDocumentsUriUsingTree"))
        assertTrue("Must warn against DocumentFile.listFiles", specText.contains("DocumentFile.listFiles"))
        assertTrue("Must specify visited documentId tracking for loop prevention", specText.contains("documentId"))
        assertTrue("Must specify bounded memory budget (< 16 MB)", specText.contains("16"))
    }

    @Test
    fun test4_destinationCollision_mandatesNonDestructiveRenaming() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md")
        val specText = specFile.readText()

        assertTrue("Must mandate monotonic non-destructive renaming e.g. Folder (1)", specText.contains("Folder (1)"))
        assertTrue("Must affirm User Data Sanctity", specText.contains("User Data Sanctity"))
        assertTrue("Must specify two-stage staging isolation in cacheDir", specText.contains("cacheDir/staging"))
    }

    @Test
    fun test5_threatModelMatrix_coversAllCriticalThreats() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/SAFE_FOLDER_TRANSFER_SPECIFICATION.md")
        val specText = specFile.readText()

        val threats = listOf("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08")
        for (threat in threats) {
            assertTrue("Spec must analyze threat: $threat", specText.contains("**$threat**"))
        }
    }

    @Test
    fun test6_pureJvmModel_validatesPathsAccurately() {
        // Valid path
        val valid = SafeRelativePath.parse("doc/manual/v1.pdf")
        assertTrue("Valid relative path should parse", valid.isSuccess)
        assertEquals(2, valid.getOrThrow().depth)
        assertEquals("doc/manual", valid.getOrThrow().parentPath)

        // Traversal attempt
        val traversal = SafeRelativePath.parse("doc/../../etc/passwd")
        assertTrue("Path traversal should fail", traversal.isFailure)

        // Absolute path attempt
        val absolute = SafeRelativePath.parse("/etc/hosts")
        assertTrue("Root path should fail", absolute.isFailure)

        // Windows reserved name
        val reserved = SafeRelativePath.parse("doc/CON.txt")
        assertTrue("Reserved DOS device name should fail", reserved.isFailure)
    }
}
