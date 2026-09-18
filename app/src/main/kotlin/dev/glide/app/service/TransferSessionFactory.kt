package dev.glide.app.service

import dev.glide.core.coordinator.TransferCoordinator
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.PeerId
import dev.glide.core.domain.SessionId
import dev.glide.core.domain.SourceHandle
import dev.glide.core.domain.TransferBatch
import dev.glide.core.identity.PersistentTrustManager
import dev.glide.core.identity.VerifiedPeerIdentity
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.TransferTransport
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory responsible for composing [TransferCoordinator] instances with application-level
 * storage, history, and source ports under a caller-provided service execution scope (LAN-003).
 */
@Singleton
class TransferSessionFactory @Inject constructor(
    val sourceAccess: SourceAccess,
    val receiveStore: ReceiveStore,
    val historyStore: HistoryStore
) {
    /**
     * Constructs a sender-role [TransferCoordinator] bound to an explicit [sessionId], batch, and source map.
     */
    fun createSenderSession(
        sessionId: SessionId,
        peerId: PeerId,
        peerDisplayName: String = "Glide Device",
        senderBatch: TransferBatch,
        sourceMap: Map<ItemId, SourceHandle>,
        transport: TransferTransport,
        scope: CoroutineScope,
        persistentTrustManager: PersistentTrustManager? = null,
        expectedPeerFingerprintHex: String? = null,
        onVerifiedPeerIdentity: ((VerifiedPeerIdentity) -> Unit)? = null
    ): TransferCoordinator {
        return TransferCoordinator(
            sessionId = sessionId,
            role = DeviceRole.Sender,
            peerId = peerId,
            localDisplayName = peerDisplayName,
            transport = transport,
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            historyStore = historyStore,
            scope = scope,
            senderBatch = senderBatch,
            sourceMap = sourceMap,
            persistentTrustManager =
                persistentTrustManager,
            expectedPeerFingerprintHex =
                expectedPeerFingerprintHex,
            onVerifiedPeerIdentity =
                onVerifiedPeerIdentity
        )
    }

    /**
     * Constructs a receiver-role [TransferCoordinator].
     * If [sessionId] is omitted or null, the coordinator will adopt and immutably bind the first
     * authenticated HELLO envelope's sessionId once (LAN-003 Nearby receiver pattern).
     */
    fun createReceiverSession(
        peerId: PeerId,
        peerDisplayName: String = "Glide Device",
        sessionId: SessionId? = null,
        transport: TransferTransport,
        scope: CoroutineScope,
        persistentTrustManager: PersistentTrustManager? = null,
        expectedPeerFingerprintHex: String? = null,
        onVerifiedPeerIdentity: ((VerifiedPeerIdentity) -> Unit)? = null
    ): TransferCoordinator {
        return TransferCoordinator(
            sessionId = sessionId,
            role = DeviceRole.Receiver,
            peerId = peerId,
            localDisplayName = peerDisplayName,
            transport = transport,
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            historyStore = historyStore,
            scope = scope,
            persistentTrustManager =
                persistentTrustManager,
            expectedPeerFingerprintHex =
                expectedPeerFingerprintHex,
            onVerifiedPeerIdentity =
                onVerifiedPeerIdentity
        )
    }
}
