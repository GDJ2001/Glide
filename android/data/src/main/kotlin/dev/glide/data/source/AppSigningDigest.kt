package dev.glide.data.source

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** Canonical APK signing certificate SHA-256 digests shared by sender and receiver. */
object AppSigningDigest {
    @Suppress("DEPRECATION")
    fun signingCertSha256Hex(info: PackageInfo): String? {
        val current = currentSignatures(info)
        if (current.isEmpty()) return null
        return canonicalDigest(current)
    }

    @Suppress("DEPRECATION")
    fun allCompatibleDigests(info: PackageInfo): Set<String> {
        val result = linkedSetOf<String>()
        val current = currentSignatures(info)
        if (current.isNotEmpty()) {
            result += canonicalDigest(current)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val details = info.signingInfo
            if (details != null && !details.hasMultipleSigners()) {
                details.signingCertificateHistory.orEmpty().forEach { sig ->
                    result += sha256Hex(sig.toByteArray())
                }
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun currentSignatures(info: PackageInfo): List<Signature> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return raw.orEmpty().toList()
    }

    private fun canonicalDigest(signatures: List<Signature>): String {
        if (signatures.size == 1) {
            return sha256Hex(signatures[0].toByteArray())
        }
        val sorted = signatures.map { sha256Hex(it.toByteArray()) }.sorted().joinToString(",")
        return sha256Hex(sorted.encodeToByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
