package dev.glide.app.ingress

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.glide.core.domain.ProtocolLimits

/**
 * Result of validating incoming Android Share Sheet intents.
 */
sealed interface ShareIngressResult {

    /**
     * Valid file sharing intent containing one or more distinct content URIs.
     */
    data class Success(val uris: List<Uri>, val uriStrings: List<String>) : ShareIngressResult

    /**
     * Rejection because text/link sharing is outside V1 scope.
     */
    data class UnsupportedType(val message: String) : ShareIngressResult

    /**
     * Rejection because URI scheme is not 'content://' (e.g., file:// or web URLs).
     */
    data class InvalidScheme(val uriString: String, val scheme: String?) : ShareIngressResult

    /**
     * Rejection because batch ceiling is exceeded.
     */
    data class BatchLimitExceeded(val count: Int, val max: Int) : ShareIngressResult

    /**
     * Error parsing intent or extracting content stream.
     */
    data class Error(val message: String) : ShareIngressResult

    /**
     * The intent is not a share intent (e.g. MAIN, VIEW).
     */
    data object Ignored : ShareIngressResult
}

/**
 * Pure, validated parser for Android Share Sheet ingress (GLIDE-026).
 *
 * Implements specifications in docs/android/STORAGE_AND_BACKGROUND.md and
 * docs/design/GLIDE_USER_FLOWS.md:
 * - Restricts to granted content URIs.
 * - Rejects unsupported text/URL shares with a clear explanation.
 * - Rejects non-content URIs (file://, http://, etc.) for security against SSRF and local file leaks (T10–T15).
 * - Deduplicates identical URI references within request.
 * - Bounded count enforcement (ProtocolLimits.MAX_ITEMS_PER_BATCH).
 * - Defends against malformed parcelables or intent tampering.
 */
object ShareIngressParser {

    fun parse(intent: Intent?): ShareIngressResult {
        if (intent == null) return ShareIngressResult.Ignored
        val action = intent.action ?: return ShareIngressResult.Ignored

        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return ShareIngressResult.Ignored
        }

        return try {
            val extractedUriStrings = mutableListOf<String>()

            // 1. Check ClipData first (Android framework attaches granted permissions via ClipData)
            val clipData: ClipData? = intent.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val item = clipData.getItemAt(i)
                    item?.uri?.let { extractedUriStrings.add(it.toString()) }
                }
            }

            // 2. Check EXTRA_STREAM based on action
            if (action == Intent.ACTION_SEND) {
                val streamUri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    val str = streamUri.toString()
                    if (!extractedUriStrings.contains(str)) {
                        extractedUriStrings.add(str)
                    }
                }
            } else if (action == Intent.ACTION_SEND_MULTIPLE) {
                val streamUris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                if (streamUris != null) {
                    for (uri in streamUris) {
                        if (uri != null) {
                            val str = uri.toString()
                            if (!extractedUriStrings.contains(str)) {
                                extractedUriStrings.add(str)
                            }
                        }
                    }
                }
            }

            val textExtra = intent.getStringExtra(Intent.EXTRA_TEXT)
            val mimeType = intent.type

            parseFromData(
                action = action,
                mimeType = mimeType,
                textExtra = textExtra,
                rawUriStrings = extractedUriStrings
            )
        } catch (e: Exception) {
            ShareIngressResult.Error("Failed to parse share intent: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Pure validation logic separated from framework Parcelable extraction.
     */
    fun parseFromData(
        action: String?,
        mimeType: String?,
        textExtra: String?,
        rawUriStrings: List<String>
    ): ShareIngressResult {
        if (action == null || (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE)) {
            return ShareIngressResult.Ignored
        }

        // Check if no stream URIs were provided
        if (rawUriStrings.isEmpty()) {
            if (!textExtra.isNullOrBlank() || mimeType?.startsWith("text/") == true) {
                return ShareIngressResult.UnsupportedType(
                    "Text and link sharing is not supported in Glide V1. Please share files (photos, videos, documents, etc.)."
                )
            }
            return ShareIngressResult.Error("No file stream provided in share intent.")
        }

        // Validate URI schemes - strictly 'content://' allowed (threat model T10-T15)
        for (uriStr in rawUriStrings) {
            val scheme = extractScheme(uriStr)
            if (!"content".equals(scheme, ignoreCase = true)) {
                return ShareIngressResult.InvalidScheme(
                    uriString = uriStr,
                    scheme = scheme
                )
            }
        }

        // Deduplicate identical URIs while preserving original sequence
        val distinctUris = rawUriStrings.distinct()

        // Enforce batch count ceiling
        if (distinctUris.size > ProtocolLimits.MAX_ITEMS_PER_BATCH) {
            return ShareIngressResult.BatchLimitExceeded(
                count = distinctUris.size,
                max = ProtocolLimits.MAX_ITEMS_PER_BATCH
            )
        }

        val parsedUris = distinctUris.mapNotNull {
            try {
                Uri.parse(it)
            } catch (_: Exception) {
                null
            }
        }

        return ShareIngressResult.Success(uris = parsedUris, uriStrings = distinctUris)
    }

    private fun extractScheme(uriString: String): String? {
        val colonIndex = uriString.indexOf(':')
        if (colonIndex <= 0) return null
        return uriString.substring(0, colonIndex).lowercase()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableExtraCompat(
        intent: Intent,
        extraName: String
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(extraName, T::class.java)
        } else {
            intent.getParcelableExtra(extraName) as? T
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayListExtraCompat(
        intent: Intent,
        extraName: String
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(extraName, T::class.java)
        } else {
            intent.getParcelableArrayListExtra(extraName)
        }
    }
}
