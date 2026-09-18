package dev.glide.core.protocol

import dev.glide.core.domain.EffectiveLimits
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferFailure
import dev.glide.core.domain.TransferItem
import dev.glide.core.domain.TransferOffer
import java.nio.charset.StandardCharsets

class OfferAssembler(
    val begin: OfferBeginBody,
    val effectiveLimits: EffectiveLimits = EffectiveLimits()
) {
    private var expectedPageIndex: Int = 0
    private val items = mutableListOf<TransferItem>()
    private val seenItemIds = mutableSetOf<ItemId>()
    private var accumulatedMetadataBytes: Long = 0L
    private var accumulatedTotalBytes: Long = 0L
    private var isFinished: Boolean = false

    val currentItemCount: Int get() = items.size
    val currentTotalBytes: Long get() = accumulatedTotalBytes
    val currentPageCount: Int get() = expectedPageIndex

    fun addPage(page: OfferPageBody) {
        check(!isFinished) { "Cannot add page: offer assembly has already been completed" }

        if (page.batchId != begin.batchId) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "OFFER_PAGE batchId '${page.batchId}' does not match OFFER_BEGIN '${begin.batchId}'"
                )
            )
        }

        if (page.pageIndex != expectedPageIndex) {
            throw ProtocolViolationException(
                TransferFailure.ProtocolOrder(
                    "Out of order OFFER_PAGE: expected page index $expectedPageIndex, got ${page.pageIndex}"
                )
            )
        }

        if (page.pageIndex >= begin.pageCount) {
            throw ProtocolViolationException(
                TransferFailure.ProtocolOrder(
                    "OFFER_PAGE index ${page.pageIndex} exceeds declared pageCount ${begin.pageCount}"
                )
            )
        }

        if (page.items.isEmpty() || page.items.size > ProtocolLimits.MAX_OFFER_PAGE_ITEMS) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded(
                    "OFFER_PAGE item count must be in 1..${ProtocolLimits.MAX_OFFER_PAGE_ITEMS}, got: ${page.items.size}"
                )
            )
        }

        if (items.size + page.items.size > effectiveLimits.maxItems) {
            throw ProtocolViolationException(
                TransferFailure.LimitExceeded(
                    "Total items ${items.size + page.items.size} exceeds negotiated maxItems ${effectiveLimits.maxItems}"
                )
            )
        }

        for (itemDto in page.items) {
            if (!seenItemIds.add(itemDto.itemId)) {
                throw ProtocolViolationException(
                    TransferFailure.MalformedMessage("Duplicate itemId '${itemDto.itemId}' detected in batch")
                )
            }

            val sizeBytes = try {
                DecimalByteCount.parse(itemDto.sizeBytes)
            } catch (e: Exception) {
                throw ProtocolViolationException(
                    failure = TransferFailure.MalformedMessage("Invalid sizeBytes '${itemDto.sizeBytes}' for item '${itemDto.name}'"),
                    cause = e
                )
            }

            if (sizeBytes > effectiveLimits.maxItemBytes) {
                throw ProtocolViolationException(
                    TransferFailure.LimitExceeded(
                        "Item '${itemDto.name}' size $sizeBytes exceeds negotiated maxItemBytes ${effectiveLimits.maxItemBytes}"
                    )
                )
            }

            accumulatedTotalBytes += sizeBytes
            if (accumulatedTotalBytes > effectiveLimits.maxBatchBytes) {
                throw ProtocolViolationException(
                    TransferFailure.LimitExceeded(
                        "Accumulated batch total bytes $accumulatedTotalBytes exceeds negotiated maxBatchBytes ${effectiveLimits.maxBatchBytes}"
                    )
                )
            }

            val nameBytes = itemDto.name.toByteArray(StandardCharsets.UTF_8).size
            val mimeBytes = itemDto.mime.toByteArray(StandardCharsets.UTF_8).size
            val resumeMetadataBytes =
                if (itemDto.rootHash != null) {
                    96
                } else {
                    0
                }
            val itemMetadataEstimate =
                36 +
                    nameBytes +
                    mimeBytes +
                    itemDto.sizeBytes.length +
                    resumeMetadataBytes +
                    32
            accumulatedMetadataBytes += itemMetadataEstimate
            if (accumulatedMetadataBytes > ProtocolLimits.MAX_OFFER_METADATA_BYTES) {
                throw ProtocolViolationException(
                    TransferFailure.LimitExceeded(
                        "Accumulated offer metadata exceeds ${ProtocolLimits.MAX_OFFER_METADATA_BYTES} bytes limit"
                    )
                )
            }

            val domainItem = TransferItem(
                id = itemDto.itemId,
                name = itemDto.name,
                mimeType = itemDto.mime,
                sizeBytes = sizeBytes,
                relativePath = itemDto.relativePath,
                resumeChunkSize =
                    itemDto.chunkSize,
                resumeChunkRootHashHex =
                    itemDto.rootHash
            )
            items.add(domainItem)
        }

        expectedPageIndex++
    }

    fun finish(end: OfferEndBody): TransferOffer {
        check(!isFinished) { "Offer assembly has already been completed" }
        isFinished = true

        if (end.batchId != begin.batchId) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "OFFER_END batchId '${end.batchId}' does not match OFFER_BEGIN '${begin.batchId}'"
                )
            )
        }

        if (expectedPageIndex != begin.pageCount) {
            throw ProtocolViolationException(
                TransferFailure.ProtocolOrder(
                    "Premature OFFER_END: received $expectedPageIndex pages out of declared ${begin.pageCount}"
                )
            )
        }

        if (items.size != begin.itemCount) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "Item count mismatch: received ${items.size} items, but OFFER_BEGIN declared ${begin.itemCount}"
                )
            )
        }

        val expectedTotalBytes = DecimalByteCount.parse(begin.totalBytes)
        if (accumulatedTotalBytes != expectedTotalBytes) {
            throw ProtocolViolationException(
                TransferFailure.MalformedMessage(
                    "Total bytes mismatch: accumulated sum is $accumulatedTotalBytes bytes, but OFFER_BEGIN declared $expectedTotalBytes bytes"
                )
            )
        }

        val batch = TransferBatch(
            id = begin.batchId,
            items = items.toList()
        )
        return TransferOffer(batch = batch)
    }
}
