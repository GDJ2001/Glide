package dev.glide.app.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSharingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shareFile_missingFile_returnsFileNotFound() {
        val nonExistent = File(tempFolder.root, "missing.bin")
        val result = FileSharing.shareFile(null, nonExistent)
        assertEquals(FileOpenResult.FileNotFound, result)
    }

    @Test
    fun shareFile_directory_returnsFileNotFound() {
        val dir = tempFolder.newFolder("sub")
        val result = FileSharing.shareFile(null, dir)
        assertEquals(FileOpenResult.FileNotFound, result)
    }

    @Test
    fun shareFile_nullContext_returnsError() {
        val file = tempFolder.newFile("shared.txt")
        file.writeText("content")
        val result = FileSharing.shareFile(null, file)
        assertEquals(FileOpenResult.Error("Context unavailable"), result)
    }

    @Test
    fun shareFiles_emptyList_returnsFileNotFound() {
        val result = FileSharing.shareFiles(null, emptyList<File>())
        assertEquals(FileOpenResult.FileNotFound, result)
    }

    @Test
    fun shareFiles_oneFileMissing_returnsFileNotFound() {
        val file1 = tempFolder.newFile("f1.txt")
        val missing = File(tempFolder.root, "nonexistent.txt")
        val result = FileSharing.shareFiles(null, listOf(file1 to "f1.txt", missing to "missing.txt"))
        assertEquals(FileOpenResult.FileNotFound, result)
    }

    @Test
    fun shareFiles_validFilesNullContext_returnsError() {
        val file1 = tempFolder.newFile("f1.txt")
        val file2 = tempFolder.newFile("f2.txt")
        val result = FileSharing.shareFiles(null, listOf(file1 to "f1.txt", file2 to "f2.txt"))
        assertEquals(FileOpenResult.Error("Context unavailable"), result)
    }
}
