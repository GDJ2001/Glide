package dev.glide.app.hardening

import dev.glide.app.config.LanFeatureConfig
import dev.glide.app.lan.LanReceiverRuntimeState
import dev.glide.app.lan.ProductionLanController
import dev.glide.app.service.TransferRuntime
import dev.glide.app.service.TransferRuntimeCommand
import dev.glide.app.service.TransferSessionFactory
import dev.glide.app.ui.selection.SelectedTransferStore
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.CommittedFileHandle
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.StagingHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.domain.TransferItem
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ItemRecord
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SessionRecord
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceMetadata
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HARD-008 proofs that LAN QR pairing enters the same service-owned TransferRuntime
 * coordinator path used by other production transports.
 */
class Hard008LanRuntimeHandoffTest {

    private class TestSourceAccess : SourceAccess {
        override suspend fun validateSource(handle: SourceHandle): SourceMetadata =
            SourceMetadata("payload.bin", "application/octet-stream", 32L)

        override suspend fun openSource(handle: SourceHandle): InputStream =
            ByteArrayInputStream(ByteArray(32) { it.toByte() })
    }

    private class TestReceiveStore : ReceiveStore {
        override suspend fun hasAvailableSpace(requiredBytes: Long): Boolean = true

        override suspend fun prepareStaging(
            sessionId: SessionId,
            itemId: ItemId,
            expectedBytes: Long,
            displayName: String,
            mimeType: String,
            relativePath: String?
        ): StagingHandle = StagingHandle("staging-${itemId.value}")

        override suspend fun openStagingOutput(handle: StagingHandle): OutputStream =
            ByteArrayOutputStream()

        override suspend fun commitVerified(
            handle: StagingHandle,
            digest: Sha256Digest
        ): CommittedFileHandle = CommittedFileHandle("committed-${handle.value}")

        override suspend fun discardStaging(handle: StagingHandle) {}
    }

    private class TestHistoryStore : HistoryStore {
        override suspend fun recordSession(record: SessionRecord) {}
        override suspend fun recordItem(record: ItemRecord) {}
        override suspend fun updateSessionOutcome(
            sessionId: SessionId,
            outcome: String,
            endedAtMs: Long
        ) {}
        override suspend fun getSession(sessionId: SessionId): SessionRecord? = null
    }

    @Before
    fun setUp() {
        SelectedTransferStore.resetForTesting()
        LanFeatureConfig.reset()
    }

    @After
    fun tearDown() {
        SelectedTransferStore.resetForTesting()
        LanFeatureConfig.reset()
    }

    @Test
    fun scannedInvitation_startsAuthenticatedServiceOwnedSenderCoordinatorWithPreparedBatch() =
        runBlocking {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val receiverController = controller()
            val senderController = controller()
            val runtime = TransferRuntime.forTesting(
                sessionFactory = sessionFactory(),
                lanController = senderController
            ).also { it.attachServiceScope(owner) }

            try {
                assertTrue(
                    receiverController.startReceiver(
                        ownerScope = owner,
                        deviceName = "QR Receiver",
                        onConnected = { _, _ -> }
                    )
                )
                val invitation = withTimeout(10_000) {
                    receiverController.receiverState
                        .filterIsInstance<LanReceiverRuntimeState.Ready>()
                        .first()
                        .invitation
                }

                val batchId = storePreparedBatch()

                assertTrue(
                    runtime.dispatch(
                        TransferRuntimeCommand.StartLanSenderPairing(
                            selectionId = batchId,
                            invitation = invitation,
                            localDeviceName = "QR Sender"
                        )
                    )
                )

                val coordinator = withTimeout(10_000) {
                    while (runtime.activeCoordinatorForTesting() == null) {
                        delay(20)
                    }
                    runtime.activeCoordinatorForTesting()
                }

                assertNotNull(coordinator)
                assertEquals(DeviceRole.Sender, coordinator!!.role)
                assertEquals(2, coordinator.senderBatch?.items?.size)
                assertEquals(2, coordinator.sourceMap.size)
            } finally {
                runtime.detachServiceScope()
                receiverController.stopAll()
                senderController.stopAll()
                owner.cancel()
            }
        }

    @Test
    fun liveReceiverQr_bootstrapStartsServiceOwnedReceiverCoordinator() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiverController = controller()
        val senderController = controller()
        val runtime = TransferRuntime.forTesting(
            sessionFactory = sessionFactory(),
            lanController = receiverController
        ).also { it.attachServiceScope(owner) }

        try {
            assertTrue(
                runtime.dispatch(
                    TransferRuntimeCommand.StartLanReceiver("Runtime Receiver")
                )
            )

            val invitation = withTimeout(10_000) {
                runtime.lanReceiverState
                    .filterIsInstance<LanReceiverRuntimeState.Ready>()
                    .first()
                    .invitation
            }

            assertTrue(
                senderController.startSender(
                    ownerScope = owner,
                    invitation = invitation,
                    localDeviceName = "Runtime Sender",
                    onConnected = { _, _ -> },
                    onFailure = { reason ->
                        throw AssertionError("Unexpected LAN sender failure: $reason")
                    }
                )
            )

            val coordinator = withTimeout(10_000) {
                while (runtime.activeCoordinatorForTesting() == null) {
                    delay(20)
                }
                runtime.activeCoordinatorForTesting()
            }

            assertNotNull(coordinator)
            assertEquals(DeviceRole.Receiver, coordinator!!.role)
            assertTrue(runtime.lanReceiverState.value is LanReceiverRuntimeState.Connected)
        } finally {
            runtime.detachServiceScope()
            senderController.stopAll()
            receiverController.stopAll()
            owner.cancel()
        }
    }

    private fun sessionFactory(): TransferSessionFactory =
        TransferSessionFactory(
            sourceAccess = TestSourceAccess(),
            receiveStore = TestReceiveStore(),
            historyStore = TestHistoryStore()
        )

    private fun storePreparedBatch(): BatchId {
        val firstId = ItemId(UUID.randomUUID().toString())
        val secondId = ItemId(UUID.randomUUID().toString())
        val batchId = BatchId(UUID.randomUUID().toString())
        val batch = TransferBatch(
            id = batchId,
            items = listOf(
                TransferItem(
                    id = firstId,
                    name = "one.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = 32L
                ),
                TransferItem(
                    id = secondId,
                    name = "two.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = 32L
                )
            )
        )
        SelectedTransferStore.store(
            batch = batch,
            sourceMap = mapOf(
                firstId to SourceHandle("content://hard008/one"),
                secondId to SourceHandle("content://hard008/two")
            )
        )
        return batchId
    }

    private fun controller(): ProductionLanController =
        ProductionLanController.forTesting(
            networkScopeFactory = ::availableLoopbackScope
        )

    private fun availableLoopbackScope(): LanNetworkScope {
        val address = InetAddress.getByName("127.0.0.1")
        return LanNetworkScope(null).also { scope ->
            scope.updateNetworkStateForTesting(
                LanNetworkState.Available(
                    network = null,
                    localAddresses = listOf(address),
                    subnets = listOf(SubnetPrefix(address, 8)),
                    generation = 1L
                )
            )
        }
    }
}
