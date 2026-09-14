package dev.glide.core.folder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GLIDE-069: Pure JVM unit tests verifying [SafeRelativePath] security and normalization.
 */
class SafeRelativePathTest {

    @Test
    fun validSimplePath_parsesCorrectly() {
        val result = SafeRelativePath.parse("document.pdf")
        assertTrue("Valid simple path should succeed", result.isSuccess)
        val path = result.getOrThrow()
        assertEquals("document.pdf", path.normalizedPath)
        assertEquals("document.pdf", path.fileName)
        assertEquals(0, path.depth)
        assertNull(path.parentPath)
    }

    @Test
    fun validNestedPath_calculatesCorrectDepthAndParent() {
        val result = SafeRelativePath.parse("photos/vacation/summer/beach.jpg")
        assertTrue("Valid nested path should succeed", result.isSuccess)
        val path = result.getOrThrow()
        assertEquals("photos/vacation/summer/beach.jpg", path.normalizedPath)
        assertEquals("beach.jpg", path.fileName)
        assertEquals(3, path.depth)
        assertEquals("photos/vacation/summer", path.parentPath)
    }

    @Test
    fun pathWithParentTraversal_isRejected() {
        val badPaths = listOf(
            "../secret.txt",
            "photos/../../etc/passwd",
            "photos/..",
            "..",
            "a/b/../../../c"
        )
        for (bad in badPaths) {
            val result = SafeRelativePath.parse(bad)
            assertTrue("Path with '..' must be rejected: $bad", result.isFailure)
        }
    }

    @Test
    fun pathWithCurrentDirTraversal_isRejected() {
        val badPaths = listOf(
            "./secret.txt",
            "photos/./image.jpg",
            ".",
            "a/b/./c"
        )
        for (bad in badPaths) {
            val result = SafeRelativePath.parse(bad)
            assertTrue("Path with '.' must be rejected: $bad", result.isFailure)
        }
    }

    @Test
    fun rootAndDrivePaths_areRejected() {
        val badPaths = listOf(
            "/system/bin/sh",
            "\\Windows\\System32",
            "C:/boot.ini",
            "D:\\secret.txt",
            "/photos/image.jpg"
        )
        for (bad in badPaths) {
            val result = SafeRelativePath.parse(bad)
            assertTrue("Root or drive path must be rejected: $bad", result.isFailure)
        }
    }

    @Test
    fun forbiddenCharactersAndNullBytes_areRejected() {
        val badPaths = listOf(
            "photos/image\u0000.jpg",
            "photos/bad:name.jpg",
            "photos/star*.png",
            "photos/question?.txt",
            "photos/pipe|file.txt",
            "photos/less<more.txt",
            "photos/quote\"file.txt"
        )
        for (bad in badPaths) {
            val result = SafeRelativePath.parse(bad)
            assertTrue("Path with forbidden character/null byte must be rejected: $bad", result.isFailure)
        }
    }

    @Test
    fun windowsReservedNames_areRejected() {
        val badPaths = listOf(
            "CON",
            "con.txt",
            "photos/PRN/report.pdf",
            "AUX.dat",
            "COM1",
            "LPT2.png"
        )
        for (bad in badPaths) {
            val result = SafeRelativePath.parse(bad)
            assertTrue("Reserved DOS/Windows device name must be rejected: $bad", result.isFailure)
        }
    }

    @Test
    fun depthExceedingLimit_isRejected() {
        // 17 directory levels + 1 file = depth 17 (> 16)
        val deepSegments = (1..17).map { "dir$it" } + "file.txt"
        val deepPath = deepSegments.joinToString("/")
        val result = SafeRelativePath.parse(deepPath)
        assertTrue("Depth > 16 must be rejected", result.isFailure)

        // Exactly 16 directory levels + 1 file = depth 16 (permitted)
        val okSegments = (1..16).map { "dir$it" } + "file.txt"
        val okPath = okSegments.joinToString("/")
        val okResult = SafeRelativePath.parse(okPath)
        assertTrue("Depth <= 16 must be permitted", okResult.isSuccess)
    }

    @Test
    fun lengthExceedingLimit_isRejected() {
        val longSegment = "a".repeat(1025)
        val result = SafeRelativePath.parse(longSegment)
        assertTrue("Path length > 1024 must be rejected", result.isFailure)
    }
}
