package dev.glide.core.protocol

import dev.glide.core.domain.ItemId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Optional, ordered digest metadata for an authenticated resumable offer page. */
internal object ResumeOfferMetadata {
    private const val KEY = "resumeDigests"
    private const val ACCEPTED_MANIFEST = "resumeManifestDigest"

    fun encodeAcceptance(digest: Sha256Digest): JsonObject =
        JsonObject(mapOf(ACCEPTED_MANIFEST to JsonPrimitive(digest.value)))

    fun decodeAcceptance(extensions: JsonObject?): Sha256Digest? =
        extensions?.get(ACCEPTED_MANIFEST)?.let(::parseDigest)

    fun encodePage(items: List<TransferItem>, includeResumeDigests: Boolean): JsonObject? {
        val apps = items.mapNotNull { item ->
            item.appMetadata?.let { item.id to it }?.takeIf { pair ->
                AppOfferMetadata.encode(mapOf(pair)) != null
            }
        }.toMap()
        val appMetadata = AppOfferMetadata.encode(apps)
        if (apps.isNotEmpty() && appMetadata == null) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("App metadata exceeds the offer page budget")
            )
        }
        return if (includeResumeDigests) encode(appMetadata, items.map { it.digest }) else appMetadata
    }

    fun encode(
        appMetadata: JsonObject?,
        digests: List<Sha256Digest?>
    ): JsonObject? {
        if (digests.all { it == null }) return appMetadata
        check(digests.all { it != null }) { "Resume digests must cover the whole page" }
        return JsonObject(appMetadata.orEmpty() + (KEY to JsonArray(
            digests.map { JsonPrimitive(requireNotNull(it).value) }
        )))
    }

    fun decode(extensions: JsonObject?, page: OfferPageBody): Map<ItemId, Sha256Digest> {
        val raw = extensions?.get(KEY) ?: return emptyMap()
        if (raw !is JsonArray || raw.size != page.items.size) invalid()
        return page.items.zip(raw).associate { (item, value) -> item.itemId to parseDigest(value) }
    }

    private fun parseDigest(value: kotlinx.serialization.json.JsonElement): Sha256Digest {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        return try {
            Sha256Digest(text)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw ProtocolViolationException(
        TransferFailure.MalformedMessage("Invalid resume offer digest metadata")
    )
}
