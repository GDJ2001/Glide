package dev.glide.core.protocol

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import java.nio.charset.StandardCharsets

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

            val nameBytes = item.name.toByteArray(StandardCharsets.UTF_8).size
            val mimeBytes = item.mimeType.toByteArray(StandardCharsets.UTF_8).size
            val relPathBytes = item.relativePath?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
            val resumeMetadataBytes =
                if (itemDto.rootHash != null) {
                    96
                } else {
                    0
                }
            val itemEstimatedBytes =
                36 +
                    nameBytes +
                    mimeBytes +
                    itemDto.sizeBytes.length +
                    (if (relPathBytes > 0) relPathBytes + 20 else 0) +
                    resumeMetadataBytes +
                    32

            // If page is full by item count OR would exceed safe payload ceiling, start new page
            if (currentPageItems.size >= maxItemsPerPage ||
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
            }

            currentPageItems.add(itemDto)
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
