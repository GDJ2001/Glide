package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem

data class PagedOffer(
    val begin: OfferBeginBody,
    val pages: List<OfferPageBody>,
    val end: OfferEndBody
)

object OfferPaginator {
    // Safe envelope size boundary before splitting to next page (must be comfortably under 16,384 bytes)
    private const val SAFE_PAGE_PAYLOAD_CEILING_BYTES: Int = 14_000

    fun paginate(
        batch: TransferBatch,
        effectiveLimits: EffectiveLimits = EffectiveLimits(),
        maxItemsPerPage: Int = ProtocolLimits.MAX_OFFER_PAGE_ITEMS,
        safePagePayloadCeilingBytes: Int = SAFE_PAGE_PAYLOAD_CEILING_BYTES,
        allowResumeMetadata: Boolean = false
    ): PagedOffer {
        require(batch.items.isNotEmpty()) { "Cannot paginate an empty batch" }
        require(maxItemsPerPage in 1..ProtocolLimits.MAX_OFFER_PAGE_ITEMS) {
            "maxItemsPerPage must be in 1..${ProtocolLimits.MAX_OFFER_PAGE_ITEMS}"
        }

        val pages = mutableListOf<OfferPageBody>()
        var currentPageItems = mutableListOf<OfferItemDto>()
        var currentPageEstimatedBytes = 256 // Base JSON envelope overhead
        val currentItems = mutableListOf<TransferItem>()
        val includeResumeDigests = allowResumeMetadata && batch.items.all { it.digest != null }

        for (item in batch.items) {
            val carriesResumeMetadata =
                item.resumeChunkSize != null ||
                    item.resumeChunkRootHashHex != null
            if (carriesResumeMetadata &&
                !allowResumeMetadata
            ) {
                throw ProtocolViolationException(
                    TransferFailure.UnsupportedProtocol(
                        "Offer item carries resume metadata without negotiated resume-v1"
                    )
                )
            }

            val itemDto = OfferItemDto(
                itemId = item.id,
                name = item.name,
                mime = item.mimeType,
                sizeBytes = DecimalByteCount.serialize(item.sizeBytes),
                relativePath = item.relativePath,
                rootHash =
                    item.resumeChunkRootHashHex,
                chunkSize =
                    item.resumeChunkSize
            )

            val itemEstimatedBytes = ProtocolCodec.encodeBody(itemDto).toString().encodeToByteArray().size + 1
            fun extensionFits(items: List<TransferItem>): Boolean =
                try {
                    val extensions = ResumeOfferMetadata.encodePage(items, includeResumeDigests)
                    extensions == null ||
                        extensions.toString().encodeToByteArray().size <= ProtocolCodec.MAX_EXTENSIONS_BYTES
                } catch (_: ProtocolViolationException) {
                    false
                }

            // If page is full by item count OR would exceed safe payload ceiling, start new page
            if (currentPageItems.size >= maxItemsPerPage ||
                (currentItems.isNotEmpty() && !extensionFits(currentItems + item)) ||
                (currentPageItems.isNotEmpty() && currentPageEstimatedBytes + itemEstimatedBytes > safePagePayloadCeilingBytes)
            ) {
                pages.add(
                    OfferPageBody(
                        batchId = batch.id,
                        pageIndex = pages.size,
                        items = currentPageItems.toList()
                    )
                )
                currentPageItems = mutableListOf()
                currentPageEstimatedBytes = 256
                currentItems.clear()
            }

            if (currentItems.isEmpty() && !extensionFits(listOf(item))) {
                throw ProtocolViolationException(
                    TransferFailure.LimitExceeded("Item metadata exceeds the offer page budget")
                )
            }
            currentPageItems.add(itemDto)
            currentItems.add(item)
            currentPageEstimatedBytes += itemEstimatedBytes
        }

        if (currentPageItems.isNotEmpty()) {
            pages.add(
                OfferPageBody(
                    batchId = batch.id,
                    pageIndex = pages.size,
                    items = currentPageItems.toList()
                )
            )
        }

        if (pages.size > 1000) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded("Offer page count ${pages.size} exceeds maximum 1000 pages")
            )
        }

        val begin = OfferBeginBody(
            batchId = batch.id,
            itemCount = batch.items.size,
            totalBytes = DecimalByteCount.serialize(batch.totalBytes),
            pageCount = pages.size
        )

        val end = OfferEndBody(
            batchId = batch.id
        )

        return PagedOffer(
            begin = begin,
            pages = pages.toList(),
            end = end
        )
    }
}
