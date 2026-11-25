package dev.glide.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal suspend fun prepareBluetoothShare(context: Context): Intent? = withContext(Dispatchers.IO) {
    val app = context.applicationInfo
    check(app.splitSourceDirs.isNullOrEmpty())
    val intent = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
    val handler = listOf("com.android.bluetooth", "com.google.android.bluetooth").firstOrNull { name ->
        intent.setPackage(name).resolveActivity(context.packageManager) != null
    } ?: return@withContext null
    val directory = File(context.cacheDir, "shares").apply { mkdirs() }
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val version = (info.versionName ?: PackageInfoCompat.getLongVersionCode(info).toString())
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val target = File(directory, "Glide_v$version.apk")
    val source = File(app.publicSourceDir)
    val temp = File.createTempFile("glide-", ".part", directory)
    try {
        source.inputStream().use { input -> temp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        check(temp.length() > 0 && temp.length() == source.length())
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temp.delete()
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    intent.setPackage(handler)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .apply { clipData = ClipData.newRawUri("Glide APK", uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
