package dev.glide.app.hardening

import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.DiscoveryViewModel
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.app.ui.discovery.ReceiveReadinessViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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

        assertTrue(
            "A transport-less production DiscoveryViewModel must expose Blocked instead of pretending that live discovery is running.",
            viewModel.uiState.value is DiscoveryUiState.Blocked
        )
    }

    @Test
    fun receiveReadiness_withoutProductionAdvertisingDependency_mustFailClosed() = runTest(dispatcher) {
        val viewModel = ReceiveReadinessViewModel()

        assertTrue(
            "A transport-less production ReceiveReadinessViewModel must expose Blocked instead of claiming the device is advertising.",
            viewModel.uiState.value is ReceiveReadinessUiState.Blocked
        )
    }
}
