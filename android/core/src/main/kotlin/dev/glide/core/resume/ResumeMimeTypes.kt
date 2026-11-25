package dev.glide.core.resume

internal fun resumeMimeTypeForName(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.lastIndex) return "application/octet-stream"
    return when (name.substring(dot + 1).lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "apks" -> "application/vnd.android.apks"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
