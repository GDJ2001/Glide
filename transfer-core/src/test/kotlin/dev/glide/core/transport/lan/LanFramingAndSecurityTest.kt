package dev.glide.core.transport.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class LanFramingAndSecurityTest {

    @Test
    fun testMdnsRecordAttributes() {
        val attrs = LanMdnsRecord.createTxtAttributes(
            version = 1,
            deviceName = "Bob's Workstation",
            deviceType = "desktop",
            certFingerprintPrefix = "a1b2c3d4e5f6",
            capabilityMask = 0x07
        )

        val record = LanMdnsRecord(
            serviceName = "Glide-Desktop-1234",
            host = "192.168.1.50",
            port = 42424,
            attributes = attrs
        )

        assertEquals(1, record.protocolVersion)
        assertEquals("Bob's Workstation", record.deviceName)
        assertEquals("desktop", record.deviceType)
        assertEquals("a1b2c3d4", record.certFingerprintPrefix) // Capped at 8 chars
        assertEquals(0x07, record.capabilityMask)
    }

    @Test
    fun testLanFrameHeader_encodeAndDecodeValid() {
        val payload = """{"v":1,"type":"ping","id":"00000000-0000-4000-8000-000000000001","seq":1,"sessionId":"00000000-0000-4000-8000-000000000002","body":{}}"""
            .toByteArray(StandardCharsets.UTF_8)

        val wrapped = LanFrameHeader.wrapFrame(payload)
        assertEquals(LanFrameHeader.HEADER_SIZE + payload.size, wrapped.size)

        val decodedLength = LanFrameHeader.decodeHeader(wrapped)
        assertEquals(payload.size, decodedLength)

        val unwrapped = LanFrameHeader.unwrapFrame(wrapped)
        assertArrayEquals(payload, unwrapped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testLanFrameHeader_rejectsOversizedPayload() {
        val oversized = ByteArray(16385) // 1 byte over 16 KiB limit
        LanFrameHeader.encodeHeader(oversized.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testLanFrameHeader_rejectsOversizedDecodedLength() {
        // Construct header with 20000 bytes
        val fakeHeader = byteArrayOf(0x00, 0x00, 0x4E.toByte(), 0x20.toByte())
        LanFrameHeader.decodeHeader(fakeHeader)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testLanFrameHeader_rejectsZeroLengthPayload() {
        LanFrameHeader.encodeHeader(0)
    }

    @Test
    fun testLanFrameHeader_goldenEnvelopeWrapping() {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/hello.json")
            ?: error("Fixture fixtures/hello.json not found")
        val goldenBytes = stream.readAllBytes()

        val wrapped = LanFrameHeader.wrapFrame(goldenBytes)
        val unwrapped = LanFrameHeader.unwrapFrame(wrapped)
        assertArrayEquals(goldenBytes, unwrapped)
    }

    @Test
    fun testWindowsPathSanitizer_validRelativePaths() {
        assertEquals("file.txt", WindowsPathSanitizer.sanitize("file.txt"))
        assertEquals("Documents/Projects/report.pdf", WindowsPathSanitizer.sanitize("Documents\\Projects\\report.pdf"))
        assertEquals("Sub/Folder/Image.png", WindowsPathSanitizer.sanitize("Sub/Folder/Image.png"))
    }

    @Test
    fun testWindowsPathSanitizer_rejectsDriveLetters() {
        val malicious = listOf("C:\\Windows\\System32\\cmd.exe", "D:file.txt", "c:/evil.dll")
        for (path in malicious) {
            try {
                WindowsPathSanitizer.sanitize(path)
                fail("Expected rejection for drive letter: $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Drive letters") == true)
            }
        }
    }

    @Test
    fun testWindowsPathSanitizer_rejectsUNCPaths() {
        val unc = listOf("\\\\192.168.1.1\\share\\malware.exe", "//server/share/file")
        for (path in unc) {
            try {
                WindowsPathSanitizer.sanitize(path)
                fail("Expected rejection for UNC: $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("UNC") == true)
            }
        }
    }

    @Test
    fun testWindowsPathSanitizer_rejectsTraversal() {
        val traversal = listOf("../file.txt", "sub/../../secret.dat", "sub/./file.txt")
        for (path in traversal) {
            try {
                WindowsPathSanitizer.sanitize(path)
                fail("Expected rejection for traversal: $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("traversal") == true)
            }
        }
    }

    @Test
    fun testWindowsPathSanitizer_rejectsReservedDosNames() {
        val dosNames = listOf("con.txt", "aux.dat", "NUL", "prn.log", "com1.bin", "folder/lpt3/file")
        for (path in dosNames) {
            try {
                WindowsPathSanitizer.sanitize(path)
                fail("Expected rejection for DOS name: $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("reserved DOS") == true)
            }
        }
    }

    @Test
    fun testWindowsPathSanitizer_rejectsIllegalCharacters() {
        val illegal = listOf("file:name.txt", "wild*card.png", "query?param.dat", "<pipe>|test.txt")
        for (path in illegal) {
            try {
                WindowsPathSanitizer.sanitize(path)
                fail("Expected rejection for illegal chars: $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("illegal Windows") == true)
            }
        }
    }
}
