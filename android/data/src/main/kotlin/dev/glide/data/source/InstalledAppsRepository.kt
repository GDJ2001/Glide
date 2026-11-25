package dev.glide.data.source

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.glide.core.domain.AppTransferMetadata
import dev.glide.core.domain.ProtocolLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.GregorianCalendar
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class InstalledAppsRepository(context: Context) {
    private val context = context.applicationContext
    private val packages = this.context.packageManager

    data class App(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val updatedAtMs: Long,
        val system: Boolean,
        val split: Boolean,
        val sizeBytes: Long = 0L
    )

    data class Export(
        val file: File,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val metadata: AppTransferMetadata?
    )

    data class InspectedAppSource(
        val packageName: String,
        val files: List<File>,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val versionCode: Long,
        val updatedAtMs: Long,
        val metadata: AppTransferMetadata?
    )

    @Suppress("DEPRECATION")
    suspend fun list(): List<App> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packages.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { packageName ->
                try {
                    val info = packages.getPackageInfo(packageName, 0)
                    val app = info.applicationInfo ?: return@mapNotNull null
                    val base = app.publicSourceDir?.let(::File)
                    val splits = app.splitPublicSourceDirs.orEmpty().map(::File)
                    val totalSize = (listOfNotNull(base) + splits)
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    App(
                        packageName = packageName,
                        label = packages.getApplicationLabel(app).toString(),
                        versionName = info.versionName ?: PackageInfoCompat.getLongVersionCode(info).toString(),
                        versionCode = PackageInfoCompat.getLongVersionCode(info),
                        updatedAtMs = info.lastUpdateTime,
                        system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        split = splits.isNotEmpty(),
                        sizeBytes = totalSize
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { app: App -> app.label }
                .thenBy { it.packageName })
    }

    suspend fun buildMetadata(app: App): AppTransferMetadata? = withContext(Dispatchers.IO) {
        runCatching { inspectByPackageName(app.packageName).metadata }.getOrNull()
    }

    @Suppress("DEPRECATION")
    suspend fun inspectSingleApk(packageName: String): InspectedAppSource = withContext(Dispatchers.IO) {
        val inspected = inspectByPackageName(packageName)
        require(inspected.files.size == 1) { "Split APK app requires glide-app-bundle://" }
        inspected
    }

    suspend fun prepareSplitBundle(packageName: String): Export = withContext(Dispatchers.IO) {
        val inspected = inspectByPackageName(packageName)
        val splits = inspected.files.drop(1)
        val extension = if (splits.isEmpty()) ".apk" else ".apks"
        val directory = File(context.cacheDir, "staged_apks").apply { mkdirs() }
        val target = File(directory, "${inspected.packageName}-${inspected.versionCode}-${inspected.updatedAtMs}$extension")
        if (target.isFile && target.length() in 1..ProtocolLimits.MAX_ITEM_BYTES) {
            return@withContext Export(
                file = target,
                name = inspected.displayName,
                mimeType = inspected.mimeType,
                sizeBytes = target.length(),
                metadata = inspected.metadata
            )
        }
        val appStub = App(
            packageName = inspected.packageName,
            label = packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString(),
            versionName = inspected.metadata?.versionName ?: inspected.versionCode.toString(),
            versionCode = inspected.versionCode,
            updatedAtMs = inspected.updatedAtMs,
            system = false,
            split = splits.isNotEmpty(),
            sizeBytes = inspected.sizeBytes
        )
        export(appStub)
    }

    @Suppress("DEPRECATION")
    private fun inspectByPackageName(packageName: String): InspectedAppSource {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val info: PackageInfo = packages.getPackageInfo(packageName, flags)
        val installed = checkNotNull(info.applicationInfo)
        val versionCode = PackageInfoCompat.getLongVersionCode(info)
        val versionName = info.versionName ?: versionCode.toString()
        val label = packages.getApplicationLabel(installed).toString()
        val base = File(installed.publicSourceDir)
        val splits = installed.splitPublicSourceDirs.orEmpty().map(::File).sortedBy { it.name }
        val files = listOf(base) + splits
        require(files.size <= 64) { "This app has too many APK splits." }
        val totalBytes = files.sumOf {
            val size = it.length()
            check(it.isFile && size in 1..ProtocolLimits.MAX_ITEM_BYTES) { "An installed APK is unavailable." }
            size
        }
        require(totalBytes <= ProtocolLimits.MAX_ITEM_BYTES) { "This app exceeds the file size limit." }
        val cert = AppSigningDigest.signingCertSha256Hex(info)
        val metadata = cert?.let { digest ->
            val abis = files.flatMap { file ->
                ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .mapNotNull { entry -> nativeLibrary.matchEntire(entry.name)?.groupValues?.get(1) }
                        .distinct().take(9).toList()
                }
            }.distinct().sorted()
            runCatching {
                AppTransferMetadata(packageName, versionCode, versionName, splits.isNotEmpty(), digest, abis)
            }.getOrNull()
        }
        val extension = if (splits.isEmpty()) ".apk" else ".apks"
        val cleanLabel = FilenameSanitizer.sanitize(label).take(100)
        val cleanVersion = FilenameSanitizer.sanitize(versionName).take(32)
        val displayName = "${cleanLabel}_v$cleanVersion$extension"
        val mimeType = if (splits.isEmpty()) "application/vnd.android.package-archive" else "application/vnd.android.apks"
        return InspectedAppSource(
            packageName = packageName,
            files = files,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = totalBytes,
            versionCode = versionCode,
            updatedAtMs = info.lastUpdateTime,
            metadata = metadata
        )
    }

    @Suppress("DEPRECATION")
    suspend fun export(app: App): Export = withContext(Dispatchers.IO) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val info = packages.getPackageInfo(app.packageName, flags)
        check(PackageInfoCompat.getLongVersionCode(info) == app.versionCode &&
            info.lastUpdateTime == app.updatedAtMs) { "This app changed. Reopen the Apps tab and select it again." }
        val installed = checkNotNull(info.applicationInfo)
        val base = File(installed.publicSourceDir)
        val splits = installed.splitPublicSourceDirs.orEmpty().map(::File).sortedBy { it.name }
        val files = listOf(base) + splits
        val metadata = AppSigningDigest.signingCertSha256Hex(info)?.let { cert ->
            val abis = files.flatMap { file ->
                ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .mapNotNull { entry -> nativeLibrary.matchEntire(entry.name)?.groupValues?.get(1) }
                        .distinct().take(9).toList()
                }
            }.distinct().sorted()
            runCatching {
                AppTransferMetadata(app.packageName, app.versionCode, app.versionName,
                    splits.isNotEmpty(), cert, abis)
            }.getOrNull()
        }
        require(files.size <= 64) { "This app has too many APK splits." }
        val inputBytes = files.sumOf {
            val size = it.length()
            check(it.isFile && size in 1..ProtocolLimits.MAX_ITEM_BYTES) { "An installed APK is unavailable." }
            size
        }
        require(inputBytes <= ProtocolLimits.MAX_ITEM_BYTES) { "This app exceeds the file size limit." }
        val extension = if (splits.isEmpty()) ".apk" else ".apks"
        val label = FilenameSanitizer.sanitize(app.label).take(100)
        val version = FilenameSanitizer.sanitize(app.versionName).take(32)
        val name = "${label}_v$version$extension"
        val directory = File(context.cacheDir, "staged_apks").apply { mkdirs() }
        val target = File(directory, "${app.packageName}-${app.versionCode}-${app.updatedAtMs}$extension")
        val temporary = File.createTempFile("app-", ".part", directory)
        try {
            if (splits.isEmpty()) {
                base.inputStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        copyChecked(input, output, inputBytes)
                    }
                }
            } else {
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    for ((index, file) in files.withIndex()) {
                        val crc = CRC32()
                        val size = file.length()
                        file.inputStream().use { copyChecked(it, null, size, crc) }
                        val entry = ZipEntry(if (index == 0) "base.apk" else "split_$index.apk").apply {
                            method = ZipEntry.STORED
                            this.size = size
                            compressedSize = size
                            this.crc = crc.value
                            time = GregorianCalendar(2000, 0, 1).timeInMillis
                        }
                        zip.putNextEntry(entry)
                        file.inputStream().use { copyChecked(it, zip, size) }
                        zip.closeEntry()
                    }
                }
            }
            val after = packages.getPackageInfo(app.packageName, 0)
            check(after.lastUpdateTime == app.updatedAtMs &&
                PackageInfoCompat.getLongVersionCode(after) == app.versionCode) {
                "This app changed while preparing it. Select it again."
            }
            val exportedBytes = temporary.length()
            require(exportedBytes in 1..ProtocolLimits.MAX_ITEM_BYTES) { "This app exceeds the file size limit." }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            val mimeType = if (splits.isEmpty()) "application/vnd.android.package-archive" else "application/vnd.android.apks"
            Export(target, name, mimeType, exportedBytes, metadata)
        } finally {
            temporary.delete()
        }
    }

    private suspend fun copyChecked(input: InputStream, output: OutputStream?, expected: Long, crc: CRC32? = null) {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            check(bytes <= expected) { "An installed APK changed during export." }
            crc?.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        check(bytes == expected) { "An installed APK changed during export." }
    }

    private companion object {
        val nativeLibrary = Regex("lib/([a-zA-Z0-9_-]{1,32})/[^/]+\\.so")
    }
}
