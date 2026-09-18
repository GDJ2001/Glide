package dev.glide.app.discovery

import dev.glide.app.service.TransferRuntime
import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.DiscoveryViewModel
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.app.ui.discovery.ReceiveReadinessViewModel
import dev.glide.core.domain.BulkRef
import dev.glide.core.domain.PeerDevice
import dev.glide.core.domain.PeerId
import dev.glide.core.ports.AdvertisingEvent
import dev.glide.core.ports.BulkProgress
import dev.glide.core.ports.ConnectionEvent
import dev.glide.core.ports.ControlMessage
import dev.glide.core.ports.DiscoveryEvent
import dev.glide.core.ports.IncomingStream
import dev.glide.core.ports.InputStreamProvider
import dev.glide.core.ports.TransferTransport
import dev.glide.transport.nearby.NearbyAvailabilityChecker
import dev.glide.transport.nearby.NearbyHardwareStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * HARD-006 production discovery/permission behavioral tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionDiscoveryControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeNearbyTransport : TransferTransport {
        val discoveryEvents = MutableSharedFlow<DiscoveryEvent>(extraBufferCapacity = 16)
        val advertisingEvents = MutableSharedFlow<AdvertisingEvent>(extraBufferCapacity = 16)

        var discoveryStarts = 0
        var discoveryStops = 0
        var advertisingStarts = 0
        var advertisingStops = 0
        var lastAdvertisingName: String? = null

        override suspend fun startDiscovery(serviceId: String): Flow<DiscoveryEvent> {
            discoveryStarts++
            return discoveryEvents.asSharedFlow()
        }

        override suspend fun stopDiscovery() {
            discoveryStops++
        }

        override suspend fun startAdvertising(
            serviceId: String,
            localDeviceName: String
        ): Flow<AdvertisingEvent> {
            advertisingStarts++
            lastAdvertisingName = localDeviceName
            return advertisingEvents.asSharedFlow()
        }

        override suspend fun stopAdvertising() {
            advertisingStops++
        }

        override suspend fun initiateConnection(peerId: PeerId, localDeviceName: String) {}
        override suspend fun acceptConnection(peerId: PeerId) {}
        override suspend fun rejectConnection(peerId: PeerId) {}
        override fun observeConnectionEvents(): Flow<ConnectionEvent> = emptyFlow()
        override suspend fun sendControl(peerId: PeerId, payload: ByteArray) {}
        override fun observeControl(): Flow<ControlMessage> = emptyFlow()
        override suspend fun sendStream(
            peerId: PeerId,
            bulkRef: BulkRef,
            streamSource: InputStreamProvider
        ): Flow<BulkProgress> = emptyFlow()
        override fun observeIncomingStream(): Flow<IncomingStream> = emptyFlow()
        override suspend fun cancelPayload(bulkRef: BulkRef) {}
        override suspend fun disconnect(peerId: PeerId) {}
    }

    @Test
    fun permissionDenial_blocksDiscoveryAndAdvertising_withoutTouchingRadio() = runTest(dispatcher) {
        val transport = FakeNearbyTransport()
        var permissionGranted = false
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = { transport },
            permissionChecker = { permissionGranted },
            apiLevelProvider = { 34 }
        )

        assertFalse(controller.startDiscovery())
        assertTrue(controller.discoveryState.value is DiscoveryUiState.Blocked)
        assertEquals(
            BlockedReason.PermissionsRequired,
            (controller.discoveryState.value as DiscoveryUiState.Blocked).reason
        )
        assertEquals(0, transport.discoveryStarts)

        assertFalse(controller.startAdvertising("Receiver"))
        assertTrue(controller.receiveReadinessState.value is ReceiveReadinessUiState.Blocked)
        assertEquals(
            BlockedReason.PermissionsRequired,
            (controller.receiveReadinessState.value as ReceiveReadinessUiState.Blocked).reason
        )
        assertEquals(0, transport.advertisingStarts)

        permissionGranted = true
        assertTrue(controller.startDiscovery())
        testScheduler.runCurrent()
        assertEquals(1, transport.discoveryStarts)
    }

    @Test
    fun grantedDiscovery_emitsStablePeers_marksLoss_andStopsRadio() = runTest(dispatcher) {
        val transport = FakeNearbyTransport()
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = { transport }
        )

        assertTrue(controller.startDiscovery())
        testScheduler.runCurrent()
        assertEquals(1, transport.discoveryStarts)

        val peer = PeerDevice(PeerId("endpoint-1"), "Pixel 9 Pro")
        transport.discoveryEvents.emit(DiscoveryEvent.PeerAppeared(peer))
        testScheduler.runCurrent()

        val searching = controller.discoveryState.value as DiscoveryUiState.Searching
        assertEquals(1, searching.peers.size)
        assertEquals(peer.id, searching.peers.single().id)
        assertTrue(searching.peers.single().isAvailable)

        transport.discoveryEvents.emit(DiscoveryEvent.PeerLost(peer.id))
        testScheduler.runCurrent()

        val lost = controller.discoveryState.value as DiscoveryUiState.Searching
        assertEquals(1, lost.peers.size)
        assertFalse(lost.peers.single().isAvailable)

        controller.stopDiscovery()
        testScheduler.runCurrent()
        assertTrue(transport.discoveryStops >= 1)
    }

    @Test
    fun hardwareBlock_preventsRadioStart_evenWithPermissions() = runTest(dispatcher) {
        val transport = FakeNearbyTransport()
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = { transport },
            hardwareChecker = NearbyAvailabilityChecker {
                NearbyHardwareStatus.BluetoothDisabled
            }
        )

        assertFalse(controller.startDiscovery())
        assertEquals(0, transport.discoveryStarts)
        val state = controller.discoveryState.value as DiscoveryUiState.Blocked
        assertEquals(BlockedReason.BluetoothDisabled, state.reason)
    }

    @Test
    fun advertising_emitsIncomingConnectionWithExactAuthenticationDigits() = runTest(dispatcher) {
        val transport = FakeNearbyTransport()
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = { transport }
        )

        assertTrue(controller.startAdvertising("Glide Receiver"))
        testScheduler.runCurrent()
        assertEquals(1, transport.advertisingStarts)
        assertEquals("Glide Receiver", transport.lastAdvertisingName)

        val peer = PeerDevice(PeerId("sender-1"), "Galaxy S25")
        transport.advertisingEvents.emit(
            AdvertisingEvent.ConnectionInitiated(peer, "849201")
        )
        testScheduler.runCurrent()

        val incoming = controller.receiveReadinessState.value as
            ReceiveReadinessUiState.IncomingConnection
        assertEquals(peer.id, incoming.peer.id)
        assertEquals(peer.displayName, incoming.peer.displayName)
        assertEquals("849201", incoming.authDigits)

        controller.stopAdvertising()
        testScheduler.runCurrent()
        assertTrue(transport.advertisingStops >= 1)
    }

    @Test
    fun discoveryTimeout_stopsRadio_andSurfacesTimedOutState() = runTest(dispatcher) {
        val transport = FakeNearbyTransport()
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = { transport }
        )

        assertTrue(controller.startDiscovery())
        testScheduler.runCurrent()

        advanceTimeBy(120_000L)
        testScheduler.runCurrent()

        assertTrue(controller.discoveryState.value is DiscoveryUiState.TimedOut)
        assertTrue(transport.discoveryStops >= 1)
    }

    @Test
    fun serviceSessionTransport_isRecreatedWhenForegroundServiceScopeChanges() = runTest(dispatcher) {
        val created = mutableListOf<FakeNearbyTransport>()
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = {
                FakeNearbyTransport().also(created::add)
            }
        )
        val serviceScopeA = CoroutineScope(SupervisorJob() + dispatcher)
        val serviceScopeB = CoroutineScope(SupervisorJob() + dispatcher)

        val first = controller.acquireSessionTransport(serviceScopeA)
        val same = controller.acquireSessionTransport(serviceScopeA)
        val second = controller.acquireSessionTransport(serviceScopeB)

        assertTrue(first === same)
        assertFalse(first === second)
        assertEquals(2, created.size)

        serviceScopeA.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        serviceScopeB.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun runtimeBackedViewModels_observeOneProductionDiscoveryAuthority() = runTest(dispatcher) {
        val transport = FakeNearbyTransport()
        val controller = ProductionDiscoveryController.forTesting(
            runtimeScope = backgroundScope,
            transportFactory = { transport }
        )
        val runtime = TransferRuntime.forTesting(discoveryController = controller)
        val discoveryViewModel = DiscoveryViewModel(runtime)
        val receiveViewModel = ReceiveReadinessViewModel(runtime)

        assertTrue(discoveryViewModel.startDiscovery())
        testScheduler.runCurrent()
        assertEquals(1, transport.discoveryStarts)

        val peer = PeerDevice(PeerId("endpoint-runtime"), "Pixel 10 Pro")
        transport.discoveryEvents.emit(DiscoveryEvent.PeerAppeared(peer))
        testScheduler.runCurrent()

        val discoveryState = discoveryViewModel.uiState.value as DiscoveryUiState.Searching
        assertEquals(peer.id, discoveryState.peers.single().id)

        assertTrue(receiveViewModel.startAdvertising("Runtime Receiver"))
        testScheduler.runCurrent()
        assertEquals(1, transport.advertisingStarts)
        assertTrue(receiveViewModel.uiState.value is ReceiveReadinessUiState.Advertising)
    }
}
