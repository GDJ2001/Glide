package dev.glide.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpenerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun openFile_missingFile_returnsFileNotFound() {
        val nonExistent = File(tempFolder.root, "does_not_exist.bin")
        val result = FileOpener.openFile(null, nonExistent)
        assertEquals(FileOpenResult.FileNotFound, result)
    }

    @Test
    fun openFile_directory_returnsFileNotFound() {
        val dir = tempFolder.newFolder("sub_dir")
        val result = FileOpener.openFile(null, dir)
        assertEquals(FileOpenResult.FileNotFound, result)
    }

    @Test
    fun openFile_existingFileWithNullContext_returnsError() {
        val validFile = tempFolder.newFile("valid.txt")
        validFile.writeText("hello")
        val result = FileOpener.openFile(null, validFile)
        assertEquals(FileOpenResult.Error("Context unavailable"), result)
    }

    @Test
    fun resolveReceivedFile_existingInDirectory_returnsFile() {
        val receivedDir = tempFolder.newFolder("received")
        val file = File(receivedDir, "transfer-key-12345")
        file.writeText("Verified payload content")

        val resolved = FileOpener.resolveReceivedFile(receivedDir, "transfer-key-12345")
        assertNotNull(resolved)
        assertEquals(file.absolutePath, resolved?.absolutePath)
        assertTrue(resolved!!.exists())
    }

    @Test
    fun resolveReceivedFile_missingKey_returnsNull() {
        val receivedDir = tempFolder.newFolder("received")
        val resolved = FileOpener.resolveReceivedFile(receivedDir, "unknown-key")
        assertNull(resolved)
    }

    @Test
    fun resolveReceivedFile_directoryMatchesKey_returnsNull() {
        val receivedDir = tempFolder.newFolder("received")
        File(receivedDir, "some-directory").mkdirs()

        val resolved = FileOpener.resolveReceivedFile(receivedDir, "some-directory")
        assertNull(resolved)
    }

    @Test
    fun resolveReceivedFile_absolutePath_resolvesIfFileExists() {
        val receivedDir = tempFolder.newFolder("received")
        val externalFile = tempFolder.newFile("other-file.txt")
        externalFile.writeText("external content")

        val resolved = FileOpener.resolveReceivedFile(receivedDir, externalFile.absolutePath)
        assertNotNull(resolved)
        assertEquals(externalFile.absolutePath, resolved?.absolutePath)
    }
}
