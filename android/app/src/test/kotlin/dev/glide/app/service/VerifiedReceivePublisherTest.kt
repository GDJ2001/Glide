package dev.glide.app.service

import dev.glide.app.ui.transfer.ExportUiStatus
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.transfer.VerifiedItemUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.data.export.ExportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedReceivePublisherTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `only committed verified files publish after completion`() = runTest {
        val file = temp.newFile("verified.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val state = completed(listOf(item("one", "key-one")))
        val saved = mutableListOf<String>()

        val result = publishVerifiedReceive(
            state,
            resolve = { key -> file.takeIf { key == "key-one" } },
            save = { source, entry ->
                assertEquals(file, source)
                ExportResult.Success("content://saved/one", entry.displayName, source.length())
            },
            onSaved = saved::add
        )

        assertEquals(ExportUiStatus.Success("one.txt"), result)
        assertEquals(listOf("one"), saved)
    }

    @Test
    fun `partial save stops on failure and retains verified private files`() = runTest {
        val file = temp.newFile("verified.bin").apply { writeBytes(byteArrayOf(1)) }
        val state = completed(listOf(item("one", "key-one"), item("two", "key-two")))
        val saved = mutableListOf<String>()
        val result = publishVerifiedReceive(
            state,
            resolve = { file },
            save = { _, entry ->
                if (entry.itemId == "two") ExportResult.Failure("Storage full")
                else ExportResult.Success("content://saved/one", entry.displayName, 1)
            },
            onSaved = saved::add
        )

        assertTrue(result is ExportUiStatus.Failed)
        assertEquals(listOf("one"), saved)
        assertTrue(file.exists())
    }

    private fun item(id: String, key: String) = VerifiedItemUiState(
        itemId = id,
        displayName = "$id.txt",
        mimeType = "text/plain",
        sizeBytes = 1,
        committedFileKey = key
    )

    private fun completed(items: List<VerifiedItemUiState>) = TransferUiState.Completed(
        sessionId = "receive-1",
        role = DeviceRole.Receiver,
        peerName = "Peer",
        totalBytes = items.size.toLong(),
        itemName = items.first().displayName,
        itemCount = items.size,
        verifiedItems = items
    )
}
