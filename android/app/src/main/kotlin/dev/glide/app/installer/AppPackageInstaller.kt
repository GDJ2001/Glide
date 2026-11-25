package dev.glide.app.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.glide.app.util.FileOpener
import dev.glide.core.domain.AppTransferMetadata
import dev.glide.core.domain.ProtocolLimits
import dev.glide.data.source.AppSigningDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

internal enum class AppVersionState {
    NewApp,
    UpdateAvailable,
    AlreadyLatest,
    OlderVersion,
    SignatureConflict,
    IncompatibleAbi
}

internal object AppPackageInstaller {
    const val APK = "application/vnd.android.package-archive"
    const val SPLITS = "application/vnd.android.apks"
    private val splitName = Regex("[A-Za-z0-9_.-]{1,120}\\.apk")
    private val apkInspectionDispatcher = Dispatchers.IO.limitedParallelism(2)

    data class OfferComparison(
        val state: AppVersionState,
        val badge: String,
        val isWarning: Boolean = false
    )

    data class Inspection(
        val badge: String,
        val installLabel: String?,
        val isWarning: Boolean = false
    )

    fun supports(mime: String, name: String): Boolean =
        mime == APK || mime == SPLITS || name.endsWith(".apk", true) || name.endsWith(".apks", true)

    fun isSplitBundle(mime: String, name: String): Boolean =
        mime == SPLITS || name.endsWith(".apks", true)

    internal fun evaluateOfferComparison(
        offeredVersionName: String,
        offeredVersionCode: Long,
        offeredSignerHex: String,
        offeredAbis: List<String>,
        supportedAbis: Array<String>,
        installedVersionName: String?,
        installedVersionCode: Long?,
        installedCompatibleSigners: Set<String>
    ): OfferComparison {
        if (offeredAbis.isNotEmpty() && offeredAbis.none { it in supportedAbis }) {
            return OfferComparison(AppVersionState.IncompatibleAbi, "Incompatible CPU ABI", isWarning = true)
        }
        if (installedVersionCode == null) {
            return OfferComparison(AppVersionState.NewApp, "New app · v$offeredVersionName")
        }
        if (offeredSignerHex !in installedCompatibleSigners) {
            return OfferComparison(
                AppVersionState.SignatureConflict,
                "Signature conflict — uninstall existing app first",
                isWarning = true
            )
        }
        val installedLabel = installedVersionName ?: installedVersionCode.toString()
        return when {
            offeredVersionCode > installedVersionCode ->
                OfferComparison(AppVersionState.UpdateAvailable, "Update available (v$installedLabel → v$offeredVersionName)")
            offeredVersionCode == installedVersionCode ->
                OfferComparison(AppVersionState.AlreadyLatest, "Already on latest version")
            else ->
                OfferComparison(AppVersionState.OlderVersion, "Older version than installed", isWarning = true)
        }
    }

    suspend fun compareOffer(context: Context, app: AppTransferMetadata): OfferComparison = withContext(Dispatchers.IO) {
        val installed = installedPackage(context, app.packageName)
        evaluateOfferComparison(
            offeredVersionName = app.versionName,
            offeredVersionCode = app.versionCode,
            offeredSignerHex = app.signingCertSha256Hex,
            offeredAbis = app.nativeAbis,
            supportedAbis = Build.SUPPORTED_ABIS,
            installedVersionName = installed?.versionName,
            installedVersionCode = installed?.let { PackageInfoCompat.getLongVersionCode(it) },
            installedCompatibleSigners = installed?.let { AppSigningDigest.allCompatibleDigests(it) }.orEmpty()
        )
    }

    suspend fun inspectVerified(context: Context, key: String, name: String): Inspection =
        withContext(apkInspectionDispatcher) {
            val source = checkNotNull(FileOpener.resolveReceivedFile(context, key))
            check(source.isFile && source.length() in 1..ProtocolLimits.MAX_ITEM_BYTES)
            if (name.endsWith(".apks", true)) {
                ZipFile(source).use { archive ->
                    val base = requireNotNull(validateSplitEntries(archive).singleOrNull { it.name == "base.apk" })
                    val temporary = File.createTempFile("glide-inspect-", ".apk", context.cacheDir)
                    try {
                        archive.getInputStream(base).use { input ->
                            temporary.outputStream().use { copyExact(input, it, base.size) }
                        }
                        inspectBase(context, readBase(context, temporary))
                    } finally {
                        temporary.delete()
                    }
                }
            } else {
                inspectBase(context, readBase(context, source))
            }
        }

    suspend fun install(context: Context, key: String, digest: String, name: String): String =
        withContext(Dispatchers.IO) {
            check(context.packageManager.canRequestPackageInstalls()) { "Allow Glide to install apps first." }
            val source = checkNotNull(FileOpener.resolveReceivedFile(context, key)) { "The verified file is unavailable." }
            check(source.isFile && source.length() in 1..ProtocolLimits.MAX_ITEM_BYTES) { "Invalid app package size." }
            check(digest.matches(Regex("[0-9a-f]{64}")) && hashFile(source) == digest) {
                "The saved app package has changed."
            }
            if (name.endsWith(".apks", true)) {
                ZipFile(source).use { archive ->
                    val entries = validateSplitEntries(archive)
                    val bytes = entries.sumOf { it.size }
                    val base = requireNotNull(entries.singleOrNull { it.name == "base.apk" })
                    val baseFile = File.createTempFile("glide-install-", ".apk", context.cacheDir)
                    try {
                        archive.getInputStream(base).use { stream ->
                            baseFile.outputStream().use { copyExact(stream, it, base.size) }
                        }
                        val app = validateBase(context, baseFile)
                        session(context, bytes, app.packageName) { installer ->
                            for (entry in entries) {
                                archive.getInputStream(entry).use { writeApk(installer, entry.name, entry.size, it) }
                            }
                        }
                    } finally {
                        baseFile.delete()
                    }
                }
            } else {
                val app = validateBase(context, source)
                session(context, source.length(), app.packageName) { installer ->
                    source.inputStream().use { writeApk(installer, "base.apk", source.length(), it) }
                }
            }
            "Confirm the installation in Android."
        }

    private fun validateSplitEntries(archive: ZipFile): List<java.util.zip.ZipEntry> {
        require(archive.size() in 2..64)
        val entries = archive.entries().asSequence().toList()
        require(entries.map { it.name }.toSet().size == entries.size)
        require(entries.all {
            !it.isDirectory && splitName.matches(it.name) && ".." !in it.name &&
                it.size in 1..ProtocolLimits.MAX_ITEM_BYTES
        })
        require(entries.sumOf { it.size } <= ProtocolLimits.MAX_ITEM_BYTES)
        return entries
    }

    private suspend fun session(
        context: Context,
        size: Long,
        packageName: String,
        stage: suspend (PackageInstaller.Session) -> Unit
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(size)
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                stage(session)
                val status = Intent(context, InstallStatusReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, id, status, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(callback.intentSender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(id)
        }
    }

    private suspend fun writeApk(session: PackageInstaller.Session, name: String, size: Long, input: InputStream) {
        session.openWrite(name, 0, size).use { output ->
            copyExact(input, output, size)
            session.fsync(output)
        }
    }

    private suspend fun copyExact(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= size)
            output.write(buffer, 0, read)
        }
        require(count == size)
    }

    private suspend fun hashFile(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun readBase(context: Context, file: File): PackageInfo =
        requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, signingFlags())) {
            "Android couldn’t read this app package."
        }

    private fun validateBase(context: Context, file: File): PackageInfo = readBase(context, file).also {
        val inspection = inspectBase(context, it)
        require(inspection.installLabel != null) { inspection.badge }
    }

    private fun inspectBase(context: Context, app: PackageInfo): Inspection {
        if (app.packageName == context.packageName) {
            return Inspection("Use your original install source to update Glide.", null, isWarning = true)
        }
        val incomingCanonical = AppSigningDigest.signingCertSha256Hex(app)
            ?: return Inspection("This package has no recognized signature.", null, isWarning = true)
        val incomingCompatible = AppSigningDigest.allCompatibleDigests(app)
        val incoming = PackageInfoCompat.getLongVersionCode(app)
        val version = app.versionName ?: incoming.toString()
        val installed = installedPackage(context, app.packageName)
            ?: return Inspection("New app · v$version", "Install")
        val installedCompatible = AppSigningDigest.allCompatibleDigests(installed)
        val compatible = incomingCanonical in installedCompatible ||
            installedCompatible.any { it in incomingCompatible }
        if (!compatible) {
            return Inspection("Signature conflict — uninstall existing app first", null, isWarning = true)
        }
        val current = PackageInfoCompat.getLongVersionCode(installed)
        val currentVer = installed.versionName ?: current.toString()
        return when {
            incoming > current ->
                Inspection("Update available (v$currentVer → v$version)", "Update")
            incoming == current ->
                Inspection("Already on latest version", "Reinstall")
            else ->
                Inspection("Older version than installed", null, isWarning = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(context: Context, name: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(name, signingFlags())
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES
}
