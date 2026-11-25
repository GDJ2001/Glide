package dev.glide.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortFileTypeFormatterTest {
    @Test
    fun knownExtensionsAreCaseIndependentAndPreserveSplitPackageType() {
        assertEquals("APKS", ShortFileTypeFormatter.format("backup.ApKs", "application/zip"))
        assertEquals("JPG", ShortFileTypeFormatter.format("photo.JPEG", null))
        assertEquals("XLSX", ShortFileTypeFormatter.format("budget.xlsx", "application/octet-stream"))
        assertEquals("ZIP", ShortFileTypeFormatter.format("folder.zip", null))
    }

    @Test
    fun usesKnownMimeTypesWithoutAFilenameExtension() {
        assertEquals("APK", ShortFileTypeFormatter.format("installer", "application/vnd.android.package-archive"))
        assertEquals("PDF", ShortFileTypeFormatter.format("document", " application/pdf; charset=UTF-8"))
        assertEquals("MP4", ShortFileTypeFormatter.format("video", "video/mp4"))
        assertEquals("MP3", ShortFileTypeFormatter.format("audio", "audio/mpeg"))
    }

    @Test
    fun neverShowsUnknownMimeStringsOrSuffixes() {
        assertEquals("FILE", ShortFileTypeFormatter.format("app.unknown", "application/octet-stream"))
        assertEquals("FILE", ShortFileTypeFormatter.format("download", null))
    }
}
