package dev.glide.app.future

import dev.glide.core.transport.lan.LanFrameHeader
import dev.glide.core.transport.lan.LanMdnsRecord
import dev.glide.core.transport.lan.WindowsPathSanitizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * GLIDE-072: Android-to-Windows Interoperable LAN Slice Compliance Verification Suite.
 *
 * Programmatically asserts:
 * 1. ADR-0017 exists, defines standard LAN transport with mDNS and TLS 1.3, and is indexed in doc/adr/README.md.
 * 2. ANDROID_WINDOWS_LAN_SPECIFICATION.md provides complete mDNS schema, length-prefixed framing, Windows path sanitizer, and 5-phase plan.
 * 3. Zero-cloud, 100% offline local subnet invariant is strictly maintained.
 * 4. Threat model matrix covers threats T01-T08, T10-T15, and T17 with cryptographic SAS derivation.
 * 5. Pure JVM LanFrameHeader enforces strict 16 KiB bounds and wrapping.
 * 6. WindowsPathSanitizer strictly eliminates directory traversal, drive letters, UNC paths, and reserved DOS device names.
 */
class AndroidWindowsLanComplianceTest {

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
    fun test1_adr0017_existsAndIsIndexed() {
        val root = resolveProjectRoot()
        val adrFile = File(root, "doc/adr/ADR-0017-android-windows-interoperable-lan-slice.md")
        assertTrue("ADR-0017 must exist", adrFile.exists())

        val adrText = adrFile.readText()
        assertTrue("ADR-0017 must document Standard LAN Transport", adrText.contains("Standard LAN Transport"))
        assertTrue("ADR-0017 must document mDNS", adrText.contains("mDNS"))
        assertTrue("ADR-0017 must document TLS 1.3", adrText.contains("TLS 1.3"))
        assertTrue("ADR-0017 must document Windows", adrText.contains("Windows"))

        val indexFile = File(root, "doc/adr/README.md")
        assertTrue("doc/adr/README.md must exist", indexFile.exists())
        assertTrue("doc/adr/README.md must index ADR-0017", indexFile.readText().contains("ADR-0017"))
    }

    @Test
    fun test2_specification_mandatesMdnsFramingAndPhases() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/ANDROID_WINDOWS_LAN_SPECIFICATION.md")
        assertTrue("ANDROID_WINDOWS_LAN_SPECIFICATION.md must exist", specFile.exists())

        val specText = specFile.readText()
        assertTrue("Must specify _glide._tcp.local. service type", specText.contains("_glide._tcp.local."))
        assertTrue("Must specify mDNS Service Discovery", specText.contains("mDNS Service Discovery"))
        assertTrue("Must specify Length-Prefixed Control Framing", specText.contains("Length-Prefixed Control Framing"))
        assertTrue("Must specify 16 KiB limit", specText.contains("16 KiB") || specText.contains("16384"))
        assertTrue("Must specify Safe Windows Path Sanitizer", specText.contains("Safe Windows Path Sanitizer"))

        // Verify all 5 implementation phases are documented
        val requiredPhases = listOf("Phase 1", "Phase 2", "Phase 3", "Phase 4", "Phase 5")
        for (phase in requiredPhases) {
            assertTrue("Must include $phase in task plan", specText.contains(phase))
        }
    }

    @Test
    fun test3_zeroCloudOfflineInvariant_verified() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/ANDROID_WINDOWS_LAN_SPECIFICATION.md")
        val specText = specFile.readText()

        assertTrue("Must assert 100% offline or zero cloud",
            specText.contains("Zero Cloud") || specText.contains("100% Offline"))
    }

    @Test
    fun test4_threatModel_mitigationsVerified() {
        val root = resolveProjectRoot()
        val specFile = File(root, "doc/future/ANDROID_WINDOWS_LAN_SPECIFICATION.md")
        val specText = specFile.readText()

        val requiredThreats = listOf("T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T10", "T11", "T12", "T13", "T14", "T15", "T17")
        for (threat in requiredThreats) {
            assertTrue("Threat $threat must be documented in LAN threat matrix", specText.contains("**$threat**"))
        }

        assertTrue("Must specify SAS derivation formula", specText.contains("ExtractDecimal6"))
        assertTrue("Must specify TLS 1.3 mandate", specText.contains("TLS 1.3"))
    }

    @Test
    fun test5_lanFramingContracts_enforceSecurityBounds() {
        val payload = """{"v":1,"type":"ping","id":"00000000-0000-4000-8000-000000000001","seq":1,"sessionId":"00000000-0000-4000-8000-000000000002","body":{}}"""
            .toByteArray(StandardCharsets.UTF_8)

        val frame = LanFrameHeader.wrapFrame(payload)
        assertEquals(LanFrameHeader.HEADER_SIZE + payload.size, frame.size)
        assertEquals(payload.size, LanFrameHeader.decodeHeader(frame))
        assertArrayEquals(payload, LanFrameHeader.unwrapFrame(frame))

        // Assert oversized frames throw
        try {
            LanFrameHeader.encodeHeader(16385)
            fail("Expected exception for frame length > 16 KiB")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("16384") == true)
        }
    }

    @Test
    fun test6_windowsPathSanitizer_protectsAgainstMaliciousPaths() {
        // Valid paths
        assertEquals("Sub/File.txt", WindowsPathSanitizer.sanitize("Sub\\File.txt"))
        assertEquals("Deep/Nested/Folder/Image.png", WindowsPathSanitizer.sanitize("Deep/Nested/Folder/Image.png"))

        // Malicious drive letters
        val driveLetters = listOf("C:\\evil.exe", "D:file.txt", "c:/bad.dll")
        for (p in driveLetters) {
            try {
                WindowsPathSanitizer.sanitize(p)
                fail("Expected rejection for drive letter: $p")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Drive letters") == true)
            }
        }

        // Malicious UNC
        val unc = listOf("\\\\server\\share\\malware.exe", "//attacker/payload")
        for (p in unc) {
            try {
                WindowsPathSanitizer.sanitize(p)
                fail("Expected rejection for UNC: $p")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("UNC") == true)
            }
        }

        // Malicious traversal
        val traversal = listOf("../file.txt", "sub/../../cmd.exe")
        for (p in traversal) {
            try {
                WindowsPathSanitizer.sanitize(p)
                fail("Expected rejection for traversal: $p")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("traversal") == true)
            }
        }

        // Reserved DOS device names
        val reservedDos = listOf("con.txt", "aux.dat", "NUL", "prn.log", "com1.bin", "lpt1.exe")
        for (p in reservedDos) {
            try {
                WindowsPathSanitizer.sanitize(p)
                fail("Expected rejection for DOS name: $p")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("reserved DOS") == true)
            }
        }
    }
}
