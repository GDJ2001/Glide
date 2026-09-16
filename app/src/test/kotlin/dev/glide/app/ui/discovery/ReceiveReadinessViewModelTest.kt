package dev.glide.app.ui.discovery

import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun initialAdvertisingState_startsCountdown() = runTest(testDispatcher) {
        val viewModel = ReceiveReadinessViewModel()
        viewModel.startAdvertising("Test Device")
        val initial = viewModel.uiState.value
        assertTrue(initial is ReceiveReadinessUiState.Advertising)
        assertEquals(120, (initial as ReceiveReadinessUiState.Advertising).secondsRemaining)

        advanceTimeBy(5000L)
        testScheduler.runCurrent()
        val updated = viewModel.uiState.value as ReceiveReadinessUiState.Advertising
        assertEquals(115, updated.secondsRemaining)
        viewModel.stopAdvertising()
    }

    @Test
    fun liveAdvertisingEvent_connectionInitiated_surfacesRealDigitsAndPeer() = runTest(testDispatcher) {
        val advertisingFlow = MutableSharedFlow<AdvertisingEvent>(replay = 1)
        var stopped = false

        val viewModel = ReceiveReadinessViewModel(
            advertisingFlowProvider = { advertisingFlow },
            onStopAdvertisingCallback = { stopped = true }
        )
        viewModel.startAdvertising("Test Device")
        testScheduler.runCurrent()

        val peer = PeerDevice(PeerId("sender-peer-1"), "Pixel 9 Pro")
        val realSdkDigits = "849201"

        advertisingFlow.emit(AdvertisingEvent.ConnectionInitiated(peer, realSdkDigits))
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is ReceiveReadinessUiState.IncomingConnection)
        val incoming = state as ReceiveReadinessUiState.IncomingConnection
        assertEquals("sender-peer-1", incoming.peer.id.value)
        assertEquals("Pixel 9 Pro", incoming.peer.displayName)
        assertEquals(realSdkDigits, incoming.authDigits)

        viewModel.stopAdvertising()
        assertTrue(stopped)
    }

    @Test
    fun setBlocked_cancelsAdvertisingAndSetsState() = runTest(testDispatcher) {
        var stopped = false
        val viewModel = ReceiveReadinessViewModel(
            onStopAdvertisingCallback = { stopped = true }
        )
        viewModel.setBlocked(BlockedReason.BluetoothDisabled)

        val state = viewModel.uiState.value
        assertTrue(state is ReceiveReadinessUiState.Blocked)
        assertEquals(BlockedReason.BluetoothDisabled, (state as ReceiveReadinessUiState.Blocked).reason)
        assertTrue(stopped)
    }
}
