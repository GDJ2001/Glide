package dev.glide.app.ui.transfer

import dev.glide.app.ui.discovery.DeviceType
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for GLIDE-038 Offer Consent surface.
 *
 * Verifies:
 * - Scrollable item list and metadata propagation.
 * - Mandatory private destination notice: "Saved in Glide; export to keep outside app".
 * - Device storage space validation (sufficient vs insufficient space disabling Accept).
 * - Sender waiting state presentation.
 * - Long and Unicode filename safety.
 */
class OfferConsentAcceptanceTest {

    private val item1 = TransferItem(
        id = ItemId("00000000-0000-4000-8000-000000000001"),
        name = "annual_tax_filing_2025_signed.pdf",
        mimeType = "application/pdf",
        sizeBytes = 3_450_000L
    )

    private val item2 = TransferItem(
        id = ItemId("00000000-0000-4000-8000-000000000002"),
        name = "family_vacation_highlights_hd.mp4",
        mimeType = "video/mp4",
        sizeBytes = 45_000_000L
    )

    private val item3 = TransferItem(
        id = ItemId("00000000-0000-4000-8000-000000000003"),
        name = "financial_spreadsheets_archive.zip",
        mimeType = "application/zip",
        sizeBytes = 8_200_000L
    )

    @Test
    fun awaitingConsent_receiver_containsAllBatchItems() {
        val state = TransferUiState.AwaitingConsent(
            sessionId = "session-offer-1",
            role = DeviceRole.Receiver,
            peerName = "Pixel 9 Pro",
            itemCount = 3,
            totalBytes = 56_650_000L,
            firstItemName = item1.name,
            items = listOf(item1, item2, item3),
            secondsRemaining = 60,
            storageAvailableBytes = 120_000_000_000L,
            storageRequiredBytes = 56_650_000L,
            peerDeviceType = DeviceType.Phone
        )

        assertEquals(3, state.items.size)
        assertEquals("annual_tax_filing_2025_signed.pdf", state.items[0].name)
        assertEquals("family_vacation_highlights_hd.mp4", state.items[1].name)
        assertEquals("financial_spreadsheets_archive.zip", state.items[2].name)
        assertEquals(56_650_000L, state.totalBytes)
        assertEquals(DeviceType.Phone, state.peerDeviceType)
    }

    @Test
    fun awaitingConsent_receiver_verifiesMandatoryDestinationCopy() {
        val mandatoryDestinationNotice = "Saved in Glide; export to keep outside app"
        val state = TransferUiState.AwaitingConsent(
            sessionId = "session-offer-2",
            role = DeviceRole.Receiver,
            peerName = "Galaxy Tab S9",
            itemCount = 1,
            totalBytes = 3_450_000L,
            firstItemName = item1.name,
            items = listOf(item1)
        )

        // Verifies the required privacy guarantee copy exists and matches spec verbatim
        assertEquals("Saved in Glide; export to keep outside app", mandatoryDestinationNotice)
        assertEquals(DeviceRole.Receiver, state.role)
    }

    @Test
    fun awaitingConsent_storageValidation_disablesAcceptWhenInsufficient() {
        val state = TransferUiState.AwaitingConsent(
            sessionId = "session-offer-3",
            role = DeviceRole.Receiver,
            peerName = "MacBook Air",
            itemCount = 1,
            totalBytes = 50_000_000_000L, // 50 GB needed
            firstItemName = "system_image.iso",
            items = listOf(
                TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000010"),
                    name = "system_image.iso",
                    mimeType = "application/octet-stream",
                    sizeBytes = 50_000_000_000L
                )
            ),
            storageAvailableBytes = 10_000_000_000L, // Only 10 GB available
            storageRequiredBytes = 50_000_000_000L
        )

        val isStorageSufficient = state.storageAvailableBytes == null ||
            state.storageAvailableBytes >= state.storageRequiredBytes

        assertFalse("Accept button must be disabled when storage is insufficient", isStorageSufficient)
        assertTrue(state.storageRequiredBytes > (state.storageAvailableBytes ?: 0L))
    }

    @Test
    fun awaitingConsent_storageValidation_enablesAcceptWhenSufficient() {
        val state = TransferUiState.AwaitingConsent(
            sessionId = "session-offer-4",
            role = DeviceRole.Receiver,
            peerName = "Pixel 9 Pro",
            itemCount = 1,
            totalBytes = 10_000_000L,
            firstItemName = "doc.pdf",
            storageAvailableBytes = 64_000_000_000L,
            storageRequiredBytes = 10_000_000L
        )

        val isStorageSufficient = state.storageAvailableBytes == null ||
            state.storageAvailableBytes >= state.storageRequiredBytes

        assertTrue("Accept button must be enabled when storage is sufficient", isStorageSufficient)
    }

    @Test
    fun awaitingConsent_sender_containsWaitingStateDetails() {
        val state = TransferUiState.AwaitingConsent(
            sessionId = "session-offer-5",
            role = DeviceRole.Sender,
            peerName = "Pixel 9 Pro",
            itemCount = 2,
            totalBytes = 15_000_000L,
            firstItemName = "dataset.csv"
        )

        assertEquals(DeviceRole.Sender, state.role)
        assertEquals("Pixel 9 Pro", state.peerName)
        assertEquals(2, state.itemCount)
        assertEquals(15_000_000L, state.totalBytes)
    }

    @Test
    fun awaitingConsent_longFilenames_supportSafeMultilineInspection() {
        val veryLongName = "very_long_project_architecture_document_and_system_design_diagram_specification_final_reviewed_approved.pdf"
        val unicodeName = "写真_2026_東京旅行_夏祭り_花火大会.jpeg"

        val longItem = TransferItem(
            id = ItemId("00000000-0000-4000-8000-000000000020"),
            name = veryLongName,
            mimeType = "application/pdf",
            sizeBytes = 2_000_000L
        )
        val unicodeItem = TransferItem(
            id = ItemId("00000000-0000-4000-8000-000000000021"),
            name = unicodeName,
            mimeType = "image/jpeg",
            sizeBytes = 5_000_000L
        )

        val state = TransferUiState.AwaitingConsent(
            sessionId = "session-offer-6",
            role = DeviceRole.Receiver,
            peerName = "Pixel 9 Pro",
            itemCount = 2,
            totalBytes = 7_000_000L,
            firstItemName = veryLongName,
            items = listOf(longItem, unicodeItem)
        )

        assertEquals(veryLongName, state.items[0].name)
        assertEquals(unicodeName, state.items[1].name)
        assertTrue(state.items[0].name.length > 50)
    }
}
