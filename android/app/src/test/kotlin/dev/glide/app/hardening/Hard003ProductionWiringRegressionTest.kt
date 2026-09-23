package dev.glide.app.hardening

import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.DiscoveryViewModel
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.app.ui.discovery.ReceiveReadinessViewModel
import dev.glide.app.ui.pairing.QrPairingViewModel
import dev.glide.app.ui.pairing.QrReceiverUiState
import dev.glide.core.pairing.LanEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Red-before-fix regressions for the disconnected Android production composition identified by HARD-003.
 *
 * A ViewModel created without a real production transport/discovery dependency must fail closed. It must
 * never advertise "searching" or "ready to receive" when no discovery/advertising implementation exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Hard003ProductionWiringRegressionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun discoveryViewModel_withoutProductionDiscoveryDependency_mustFailClosed() = runTest(dispatcher) {
        val viewModel = DiscoveryViewModel()
        try {
            assertTrue(
                "A transport-less production DiscoveryViewModel must expose Blocked instead of pretending that live discovery is running.",
                viewModel.uiState.value is DiscoveryUiState.Blocked
            )
        } finally {
            // Cancel the ViewModel's timer even when this intentional regression assertion fails.
            viewModel.setBlocked(BlockedReason.TransportError("HARD-003 test cleanup"))
        }
    }

    @Test
    fun receiveReadiness_withoutProductionAdvertisingDependency_mustFailClosed() = runTest(dispatcher) {
        val viewModel = ReceiveReadinessViewModel()
        try {
            assertTrue(
                "A transport-less production ReceiveReadinessViewModel must expose Blocked instead of claiming the device is advertising.",
                viewModel.uiState.value is ReceiveReadinessUiState.Blocked
            )
        } finally {
            viewModel.setBlocked(BlockedReason.TransportError("HARD-003 test cleanup"))
        }
    }

    @Test
    fun qrPairing_withoutLiveListenerAuthority_mustNotMintReadyInvitationFromCallerSuppliedEndpoint() = runTest(dispatcher) {
        val viewModel = QrPairingViewModel(qrBitmapGenerator = { null })
        try {
            viewModel.startReceiving(
                deviceName = "HARD-003 Receiver",
                endpoints = listOf(LanEndpoint(address = "192.168.1.50", port = 4242)),
                spkiSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                networkGeneration = 0L,
                ttlSeconds = 120
            )

            assertFalse(
                "A receive QR must be minted only from a listener/runtime that proves the endpoint and SPKI are live; arbitrary caller-supplied placeholder values must not produce Ready state.",
                viewModel.receiverState.value is QrReceiverUiState.Ready
            )
        } finally {
            viewModel.stopReceiving()
        }
    }
}
