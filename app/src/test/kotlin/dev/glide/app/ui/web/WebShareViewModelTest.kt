package dev.glide.app.ui.web

import dev.glide.transport.lan.web.OfferedWebItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class WebShareViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: WebShareViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = WebShareViewModel(
            networkScope = null,
            qrBitmapGenerator = { null }
        )
    }

    @After
    fun tearDown() {
        viewModel.stopSharing()
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(WebShareUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun startSharing_emptyItems_emitsError() {
        viewModel.startSharing(emptyList<OfferedWebItem>())
        val state = viewModel.uiState.value
        assertTrue(state is WebShareUiState.Error)
        assertEquals("No files selected to share.", (state as WebShareUiState.Error).message)
    }

    @Test
    fun startSharing_validItems_emitsActiveState() = runTest {
        val payload = "Test Web Share Payload".toByteArray(Charsets.UTF_8)
        val items = listOf(
            OfferedWebItem(
                itemId = "item-1",
                fileName = "photo.jpg",
                fileSize = payload.size.toLong(),
                contentSupplier = { ByteArrayInputStream(payload) }
            )
        )

        viewModel.startSharing(items, ttlSeconds = 60)

        val state = viewModel.uiState.value
        assertTrue("State must be Active", state is WebShareUiState.Active)
        val active = state as WebShareUiState.Active
        assertEquals(1, active.offeredItemCount)
        assertEquals(payload.size.toLong(), active.totalBytes)
        assertEquals(60, active.secondsRemaining)
        assertTrue(active.onboardingUrl.startsWith("http://"))
        assertTrue(active.onboardingUrl.contains("?token="))

        // Advance 10 seconds
        advanceTimeBy(10_000)
        testScheduler.runCurrent()
        val after10s = viewModel.uiState.value as WebShareUiState.Active
        assertEquals(50, after10s.secondsRemaining)

        // Stop sharing
        viewModel.stopSharing()
        assertEquals(WebShareUiState.Stopped, viewModel.uiState.value)
    }

    @Test
    fun dismiss_resetsStateToIdle() {
        viewModel.startSharing(
            listOf(
                OfferedWebItem(
                    itemId = "item-1",
                    fileName = "doc.pdf",
                    fileSize = 100L,
                    contentSupplier = { ByteArrayInputStream(ByteArray(100)) }
                )
            )
        )
        assertTrue(viewModel.uiState.value is WebShareUiState.Active)

        viewModel.dismiss()
        assertEquals(WebShareUiState.Idle, viewModel.uiState.value)
    }
}
