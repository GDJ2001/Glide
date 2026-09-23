package dev.glide.app.ui.pairing

import dev.glide.core.pairing.InvitationCodec
import dev.glide.core.pairing.LanEndpoint
import dev.glide.core.pairing.LanInvitation
import dev.glide.transport.lan.pairing.AuthorityClock
import dev.glide.transport.lan.pairing.InvitationAuthority
import dev.glide.core.transport.lan.LanBootstrapMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class QrPairingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testEndpoints = listOf(
        LanEndpoint(address = "192.168.1.50", port = 4242)
    )
    private val testSpki = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private var simulatedEpochMs = 1_700_000_000_000L

    private val fakeClock = object : AuthorityClock {
        override fun epochMillis(): Long = simulatedEpochMs
        override fun monotonicNanos(): Long = simulatedEpochMs * 1_000_000L
    }

    private lateinit var authority: InvitationAuthority
    private lateinit var viewModel: QrPairingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authority = InvitationAuthority(clock = fakeClock)
        viewModel = QrPairingViewModel(
            invitationAuthority = authority,
            clock = fakeClock,
            qrBitmapGenerator = { null } // Stub out bitmap generation for pure JVM tests
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStartReceiving_mintsValidInvitation_andStartsCountdown() = runTest(testDispatcher) {
        viewModel.startReceiving(
            deviceName = "Pixel 8 Pro",
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            networkGeneration = 1L,
            ttlSeconds = 120
        )

        val state = viewModel.receiverState.value
        assertTrue("State should be Ready", state is QrReceiverUiState.Ready)
        val ready = state as QrReceiverUiState.Ready

        assertEquals("Pixel 8 Pro", ready.localDeviceName)
        assertEquals(120, ready.secondsRemaining)
        assertEquals("192.168.1.50:4242", ready.endpointsSummary)

        val active = authority.getActiveInvitation()
        assertNotNull("Authority must have active invitation", active)
        assertEquals(ready.invitation.invitationId, active?.invitationId)
        assertEquals(ready.invitation.token, active?.token)
    }

    @Test
    fun testCountdown_ticksEverySecond_expiresAtZero_andRevokesInvitation() = runTest(testDispatcher) {
        viewModel.startReceiving(
            deviceName = "Pixel 8 Pro",
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            networkGeneration = 1L,
            ttlSeconds = 3 // Use 3 seconds for test
        )

        val initial = viewModel.receiverState.value as QrReceiverUiState.Ready
        assertEquals(3, initial.secondsRemaining)

        // Advance 1 second
        advanceTimeBy(1001L)
        val tick1 = viewModel.receiverState.value as QrReceiverUiState.Ready
        assertEquals(2, tick1.secondsRemaining)

        // Advance 1 second
        advanceTimeBy(1000L)
        val tick2 = viewModel.receiverState.value as QrReceiverUiState.Ready
        assertEquals(1, tick2.secondsRemaining)

        // Advance final second to 0
        advanceTimeBy(1000L)
        val finalState = viewModel.receiverState.value
        assertEquals("State must become Expired", QrReceiverUiState.Expired, finalState)

        // Invitation must be revoked in authority
        assertNull("Authority active invitation must be revoked on expiration", authority.getActiveInvitation())
    }

    @Test
    fun testRefreshInvitation_revokesOldInvitation_andMintsNewIdAndToken() = runTest(testDispatcher) {
        viewModel.startReceiving(
            deviceName = "Pixel 8 Pro",
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            networkGeneration = 1L,
            ttlSeconds = 120
        )

        val initial = viewModel.receiverState.value as QrReceiverUiState.Ready
        val inv1 = initial.invitation

        // Refresh invitation
        viewModel.refreshInvitation(
            deviceName = "Pixel 8 Pro",
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            networkGeneration = 1L,
            ttlSeconds = 120
        )

        val refreshed = viewModel.receiverState.value as QrReceiverUiState.Ready
        val inv2 = refreshed.invitation

        assertNotEquals("Invitation ID must rotate on refresh", inv1.invitationId, inv2.invitationId)
        assertNotEquals("Token must rotate on refresh", inv1.token, inv2.token)
        assertEquals(120, refreshed.secondsRemaining)

        // Old invitation is revoked in authority
        val bootstrap1 = LanBootstrapMessage(
            v = 1,
            invitationId = inv1.invitationId,
            token = inv1.token,
            invitationHash = inv1.invitationHash,
            sessionId = UUID.randomUUID().toString(),
            role = "sender"
        )
        val result1 = authority.consume(bootstrap1, currentNetworkGeneration = 1L, isBusy = false)
        assertFalse("Old invitation must be rejected after refresh", result1.isAccepted)

        // New invitation succeeds
        val bootstrap2 = LanBootstrapMessage(
            v = 1,
            invitationId = inv2.invitationId,
            token = inv2.token,
            invitationHash = inv2.invitationHash,
            sessionId = UUID.randomUUID().toString(),
            role = "sender"
        )
        val result2 = authority.consume(bootstrap2, currentNetworkGeneration = 1L, isBusy = false)
        assertTrue("Refreshed invitation must be accepted by authority", result2.isAccepted)
    }

    @Test
    fun testStopReceiving_revokesActiveInvitation_andCancelsJobs() = runTest(testDispatcher) {
        viewModel.startReceiving(
            deviceName = "Pixel 8 Pro",
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            networkGeneration = 1L,
            ttlSeconds = 120
        )

        assertNotNull(authority.getActiveInvitation())
        viewModel.stopReceiving()

        assertEquals(QrReceiverUiState.Idle, viewModel.receiverState.value)
        assertNull("Active invitation must be revoked when receiver is stopped", authority.getActiveInvitation())
    }

    @Test
    fun testNetworkChangedOrBackgrounded_immediatelyRevokesActiveInvitation() = runTest(testDispatcher) {
        viewModel.startReceiving(
            deviceName = "Pixel 8 Pro",
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            networkGeneration = 1L,
            ttlSeconds = 120
        )

        viewModel.onNetworkChangedOrBackgrounded()

        assertEquals(QrReceiverUiState.Expired, viewModel.receiverState.value)
        assertNull("Active invitation must be revoked on network drop/backgrounding", authority.getActiveInvitation())
    }

    @Test
    fun testScanAndPaste_haveIdenticalValidation() = runTest(testDispatcher) {
        val validInvitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            deviceName = "Receiver Device",
            ttlSeconds = 120
        )
        val uriString = InvitationCodec.encode(validInvitation)

        // 1. Scan via camera
        viewModel.onQrCodeScanned(uriString)
        val scanState = viewModel.scannerState.value
        assertTrue("Camera scan must yield ConfirmationRequired", scanState is QrScannerUiState.ConfirmationRequired)
        assertEquals(validInvitation.invitationId, (scanState as QrScannerUiState.ConfirmationRequired).invitation.invitationId)

        // Reset
        viewModel.cancelConfirmation()
        assertEquals(QrScannerUiState.Scanning(torchEnabled = false, hasFlashlight = false), viewModel.scannerState.value)

        // 2. Paste identical string
        viewModel.onInvitationPasted(uriString)
        val pasteState = viewModel.scannerState.value
        assertTrue("Paste must yield ConfirmationRequired identically", pasteState is QrScannerUiState.ConfirmationRequired)
        assertEquals(validInvitation.invitationId, (pasteState as QrScannerUiState.ConfirmationRequired).invitation.invitationId)
    }

    @Test
    fun testValidScan_entersConfirmationRequired_doesNotAutoConnect() = runTest(testDispatcher) {
        val validInvitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            deviceName = "Receiver Device",
            ttlSeconds = 120
        )

        viewModel.onQrCodeScanned(InvitationCodec.encode(validInvitation))

        val state = viewModel.scannerState.value
        assertTrue(state is QrScannerUiState.ConfirmationRequired)
        val confirmation = state as QrScannerUiState.ConfirmationRequired
        assertEquals("Receiver Device", confirmation.invitation.name)
        assertEquals("192.168.1.50", confirmation.invitation.endpoints.first().address)
        assertEquals(4242, confirmation.invitation.endpoints.first().port)
    }

    @Test
    fun testConfirmConnection_transitionsToConnecting_andEmitsEvent() = runTest(testDispatcher) {
        val validInvitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            deviceName = "Receiver Device",
            ttlSeconds = 120
        )

        viewModel.onQrCodeScanned(InvitationCodec.encode(validInvitation))
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.events.first()
        }
        viewModel.confirmConnection()

        val state = viewModel.scannerState.value
        assertTrue("State must become Connecting after explicit user confirmation", state is QrScannerUiState.Connecting)

        val event = eventDeferred.await()
        assertTrue("Event must be ConnectConfirmed", event is QrPairingEvent.ConnectConfirmed)
        assertEquals(validInvitation.invitationId, (event as QrPairingEvent.ConnectConfirmed).invitation.invitationId)
    }

    @Test
    fun testCancelConfirmation_returnsToScanning() = runTest(testDispatcher) {
        val validInvitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            deviceName = "Receiver Device",
            ttlSeconds = 120
        )

        viewModel.startScanning(hasFlashlight = true)
        viewModel.onQrCodeScanned(InvitationCodec.encode(validInvitation))
        assertTrue(viewModel.scannerState.value is QrScannerUiState.ConfirmationRequired)

        viewModel.cancelConfirmation()
        val state = viewModel.scannerState.value
        assertTrue("Cancelling confirmation must return to Scanning state", state is QrScannerUiState.Scanning)
        assertTrue((state as QrScannerUiState.Scanning).hasFlashlight)
    }

    @Test
    fun testExpiredInvitation_scannedOrPasted_yieldsExpiredError() = runTest(testDispatcher) {
        // Mint invitation with short TTL
        val invitation = authority.mintInvitation(
            endpoints = testEndpoints,
            spkiSha256 = testSpki,
            deviceName = "Receiver Device",
            ttlSeconds = 10
        )

        // Advance clock past expiration
        simulatedEpochMs += 15_000L

        viewModel.onQrCodeScanned(InvitationCodec.encode(invitation))

        val state = viewModel.scannerState.value
        assertTrue("Expired invitation must yield Error state", state is QrScannerUiState.Error)
        assertEquals(QrScanErrorReason.Expired, (state as QrScannerUiState.Error).reason)
    }

    @Test
    fun testInvalidFormat_scannedOrPasted_yieldsInvalidFormatError() = runTest(testDispatcher) {
        viewModel.onQrCodeScanned("not-a-valid-invitation-uri")
        val state = viewModel.scannerState.value
        assertTrue(state is QrScannerUiState.Error)
        assertEquals(QrScanErrorReason.InvalidFormat, (state as QrScannerUiState.Error).reason)
    }

    @Test
    fun testInvalidUriPrefix_yieldsInvalidFormatError() = runTest(testDispatcher) {
        viewModel.onQrCodeScanned("http://example.com/not-glide-pair")
        val state = viewModel.scannerState.value
        assertTrue(state is QrScannerUiState.Error)
        assertEquals(QrScanErrorReason.InvalidFormat, (state as QrScannerUiState.Error).reason)
    }

    @Test
    fun testCameraPermissionDenied_setsTypedError() = runTest(testDispatcher) {
        viewModel.onCameraPermissionDenied()
        val state = viewModel.scannerState.value
        assertTrue(state is QrScannerUiState.Error)
        assertEquals(QrScanErrorReason.CameraPermissionDenied, (state as QrScannerUiState.Error).reason)
    }

    @Test
    fun testCameraUnavailable_setsTypedError() = runTest(testDispatcher) {
        viewModel.onCameraUnavailable()
        val state = viewModel.scannerState.value
        assertTrue(state is QrScannerUiState.Error)
        assertEquals(QrScanErrorReason.CameraUnavailable, (state as QrScannerUiState.Error).reason)
    }

    @Test
    fun testRetryScan_returnsToScanning() = runTest(testDispatcher) {
        viewModel.onCameraUnavailable()
        assertTrue(viewModel.scannerState.value is QrScannerUiState.Error)

        viewModel.retryScan()
        assertTrue(viewModel.scannerState.value is QrScannerUiState.Scanning)
    }
}
