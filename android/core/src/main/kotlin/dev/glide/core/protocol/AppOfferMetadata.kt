package dev.glide.core.protocol

import dev.glide.core.domain.AppTransferMetadata
import dev.glide.core.domain.ItemId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object AppOfferMetadata {
    private const val MAX_BYTES = 900
    private const val KEY = "appMeta"

    fun encode(items: Map<ItemId, AppTransferMetadata>): JsonObject? {
        if (items.isEmpty()) return null
        val result = buildJsonObject {
            put(KEY, buildJsonObject {
                for ((id, app) in items) {
                    put(id.value, buildJsonObject {
                        put("pkg", app.packageName)
                        put("vc", app.versionCode)
                        put("vn", app.versionName)
                        put("split", app.isSplitBundle)
                        put("cert", app.signingCertSha256Hex)
                        put("abis", JsonArray(app.nativeAbis.map(::JsonPrimitive)))
                    })
                }
            })
        }
        return result.takeIf { it.toString().encodeToByteArray().size <= MAX_BYTES }
    }

    fun decode(extensions: JsonObject?, page: OfferPageBody): Map<ItemId, AppTransferMetadata> {
        if (extensions == null || extensions.toString().encodeToByteArray().size > MAX_BYTES) return emptyMap()
        val entries = extensions[KEY] as? JsonObject ?: return emptyMap()
        return page.items.mapNotNull { item ->
            val raw = entries[item.itemId.value] as? JsonObject ?: return@mapNotNull null
            runCatching {
                item.itemId to AppTransferMetadata(
                    raw.getValue("pkg").jsonPrimitive.content,
                    raw.getValue("vc").jsonPrimitive.long,
                    raw.getValue("vn").jsonPrimitive.content,
                    raw.getValue("split").jsonPrimitive.boolean,
                    raw.getValue("cert").jsonPrimitive.content,
                    raw.getValue("abis").jsonArray.map { it.jsonPrimitive.content }
                )
            }.getOrNull()
        }.toMap()
    }
}
