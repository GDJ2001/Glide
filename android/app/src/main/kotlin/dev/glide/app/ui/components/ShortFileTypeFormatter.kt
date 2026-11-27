package dev.glide.app.ui.components

import java.util.Locale

internal object ShortFileTypeFormatter {
    private val extensions = setOf(
        "APK", "APKS", "XAPK", "PDF", "JPG", "PNG", "GIF", "WEBP", "HEIC", "SVG",
        "MP4", "MOV", "MKV", "WEBM", "AVI", "M4V", "3GP",
        "MP3", "WAV", "FLAC", "M4A", "OGG", "AAC", "OPUS",
        "ZIP", "7Z", "RAR", "TAR", "GZ", "ISO", "IMG",
        "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX", "ODT", "ODS", "ODP", "RTF",
        "TXT", "SRT", "ASS", "VTT", "MD", "LOG", "CSV", "JSON", "XML", "YAML", "YML",
        "HTML", "HTM", "EPUB", "ICS", "VCF", "KT", "JAVA", "PY", "JS", "TS"
    )

    fun format(fileName: String, mimeType: String?): String {
        val extension = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        if (extension == "JPEG") return "JPG"
        if (extension in extensions) return extension
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return when (mime) {
            "application/vnd.android.package-archive" -> "APK"
            "application/pdf" -> "PDF"
            "application/zip" -> "ZIP"
            "application/msword" -> "DOC"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX"
            "image/jpeg" -> "JPG"
            "audio/mpeg" -> "MP3"
            "audio/mp4" -> "M4A"
            "text/plain" -> "TXT"
            else -> mime?.substringAfter('/')?.uppercase(Locale.ROOT)?.takeIf { it in extensions } ?: "FILE"
        }
    }
}
