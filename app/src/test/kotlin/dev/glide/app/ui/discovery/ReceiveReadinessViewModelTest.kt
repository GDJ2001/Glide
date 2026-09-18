package dev.glide.app.ui.discovery

import dev.glide.core.domain.PeerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveReadinessViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noArgViewModel_failsClosedWithoutProductionRuntime() = runTest(testDispatcher) {
        val viewModel = ReceiveReadinessViewModel()
        assertTrue(viewModel.uiState.value is ReceiveReadinessUiState.Blocked)
        assertFalse(viewModel.startAdvertising("Test Device"))
    }

    @Test
    fun explicitTestHarness_canRepresentAdvertisingState() = runTest(testDispatcher) {
        val viewModel = ReceiveReadinessViewModel.forTesting(
            ReceiveReadinessUiState.Advertising(
                localDeviceName = "Test Device",
                secondsRemaining = 120
            )
        )

        val initial = viewModel.uiState.value
        assertTrue(initial is ReceiveReadinessUiState.Advertising)
        initial as ReceiveReadinessUiState.Advertising
        assertEquals("Test Device", initial.localDeviceName)
        assertEquals(120, initial.secondsRemaining)
    }

    @Test
    fun testHelper_connectionInitiated_surfacesExactDigitsAndPeer() = runTest(testDispatcher) {
        val viewModel = ReceiveReadinessViewModel.forTesting()

        val peer = DiscoveredPeer(
            id = PeerId("sender-peer-1"),
            displayName = "Pixel 9 Pro"
        )
        val realSdkDigits = "849201"

        viewModel.onConnectionInitiated(peer, realSdkDigits)

        val state = viewModel.uiState.value
        assertTrue(state is ReceiveReadinessUiState.IncomingConnection)
        state as ReceiveReadinessUiState.IncomingConnection
        assertEquals("sender-peer-1", state.peer.id.value)
        assertEquals("Pixel 9 Pro", state.peer.displayName)
        assertEquals(realSdkDigits, state.authDigits)
    }

    @Test
    fun setBlocked_setsRequestedReason() = runTest(testDispatcher) {
        val viewModel = ReceiveReadinessViewModel.forTesting()
        viewModel.setBlocked(BlockedReason.BluetoothDisabled)

        val state = viewModel.uiState.value
        assertTrue(state is ReceiveReadinessUiState.Blocked)
        assertEquals(
            BlockedReason.BluetoothDisabled,
            (state as ReceiveReadinessUiState.Blocked).reason
        )
    }
}
