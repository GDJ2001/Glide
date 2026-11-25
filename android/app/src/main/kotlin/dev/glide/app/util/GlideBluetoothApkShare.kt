package dev.glide.app.util

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun glideBluetoothApkFileName(versionName: String): String {
    val sanitized = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "1" }
    return "Glide_v$sanitized.apk"
}

internal fun isBluetoothShareHandler(packageName: String, activityName: String): Boolean =
    packageName.contains("bluetooth", ignoreCase = true) ||
        activityName.contains("bluetooth", ignoreCase = true)

internal suspend fun prepareBluetoothShare(context: Context): Intent? = withContext(Dispatchers.IO) {
    val app = context.applicationInfo
    check(app.splitSourceDirs.isNullOrEmpty())

    val directory = File(context.cacheDir, "shares").apply { mkdirs() }
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val version = info.versionName ?: PackageInfoCompat.getLongVersionCode(info).toString()
    val target = File(directory, glideBluetoothApkFileName(version))
    val source = File(app.publicSourceDir)
    val temp = File.createTempFile("glide-", ".part", directory)
    try {
        source.inputStream().use { input -> temp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        check(temp.length() > 0 && temp.length() == source.length())
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } finally {
        temp.delete()
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)

    // AOSP BluetoothOppLauncherActivity rejects application/vnd.android.package-archive in
    // UNACCEPTABLE_SHARE_OUTBOUND_TYPES while accepting application/zip / */* and preserving the
    // .apk filename over OBEX transfer.
    val mimeFallbacks = listOf("application/vnd.android.package-archive", "application/zip", "*/*")
    for (mime in mimeFallbacks) {
        val candidate = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Glide APK", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = querySendHandlers(context.packageManager, candidate).firstOrNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@firstOrNull false
            isBluetoothShareHandler(activityInfo.packageName, activityInfo.name)
        }
        if (resolved?.activityInfo != null) {
            candidate.component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
            return@withContext candidate
        }
    }

    val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Glide APK", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Intent.createChooser(fallbackIntent, "Share Glide APK")
}

@Suppress("DEPRECATION")
private fun querySendHandlers(pm: PackageManager, intent: Intent) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        pm.queryIntentActivities(intent, 0)
    }
