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
import dev.glide.core.resume.ResumeAuthorityLookup
import dev.glide.core.resume.ResumeReconnectPlan
import dev.glide.core.resume.ResumeRequestAuthority
import dev.glide.core.resume.ResumeSenderReadySource
import dev.glide.core.resume.ResumeSourcePreflight
import dev.glide.data.db.ResumableSessionEntity
import dev.glide.data.source.ResumeSourceRevalidationResult
import dev.glide.data.store.ResumeReceiverRevalidationResult
import dev.glide.data.store.ResumeSenderRevalidationResult
import dev.glide.data.source.ResumeSourceVerifier
import dev.glide.data.store.ResumableStagingStore
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Factory responsible for composing [TransferCoordinator] instances with application-level
 * storage, history, and source ports under a caller-provided service execution scope (LAN-003).
 */
@Singleton
class TransferSessionFactory(
    val sourceAccess: SourceAccess,
    val receiveStore: ReceiveStore,
    val historyStore: HistoryStore,
    val resumeSourceVerifier:
        ResumeSourceVerifier? = null,
    val resumableStagingStore:
        ResumableStagingStore? = null
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
                onVerifiedPeerIdentity,
            resumeSourcePreflight =
                resumeSourceVerifier
                    ?.let { verifier ->
                        { handle ->
                            val snapshot =
                                verifier.snapshot(
                                    handle
                                )
                            ResumeSourcePreflight(
                                sourceIdentity =
                                    snapshot
                                        .sourceIdentity,
                                totalBytes =
                                    snapshot.totalBytes,
                                chunkSize =
                                    snapshot.chunkSize,
                                chunkRootHashHex =
                                    snapshot
                                        .chunkRootHashHex,
                                wholeFileSha256Hex =
                                    snapshot
                                        .wholeFileSha256Hex
                            )
                        }
                    },
            persistSenderResumeAuthority =
                resumableStagingStore
                    ?.let { store ->
                        {
                            originalSessionId,
                            item,
                            handle,
                            peerFingerprintHex,
                            snapshot ->
                            store.initSenderSession(
                                originalSessionId =
                                    originalSessionId
                                        .value,
                                itemId =
                                    item.id.value,
                                itemName =
                                    item.name,
                                peerFingerprintHex =
                                    peerFingerprintHex,
                                sourceHandle =
                                    handle.value,
                                sourceIdentity =
                                    snapshot
                                        .sourceIdentity,
                                totalBytes =
                                    snapshot
                                        .totalBytes,
                                chunkSize =
                                    snapshot
                                        .chunkSize,
                                chunkRootHashHex =
                                    snapshot
                                        .chunkRootHashHex,
                                wholeFileSha256Hex =
                                    snapshot
                                        .wholeFileSha256Hex
                            )
                            Unit
                        }
                    },
            enableTrueResumeCapability =
                false
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
                onVerifiedPeerIdentity,
            persistReceiverResumeAuthority =
                resumableStagingStore
                    ?.let { store ->
                        {
                            originalSessionId,
                            item,
                            peerFingerprintHex ->
                            val chunkSize =
                                item.resumeChunkSize
                                    ?: throw IllegalArgumentException(
                                        "Resume chunk size is missing"
                                    )
                            val rootHash =
                                item
                                    .resumeChunkRootHashHex
                                    ?: throw IllegalArgumentException(
                                        "Resume chunk root is missing"
                                    )
                            store.initReceiverSession(
                                originalSessionId =
                                    originalSessionId
                                        .value,
                                itemId =
                                    item.id.value,
                                itemName =
                                    item.name,
                                peerFingerprintHex =
                                    peerFingerprintHex,
                                totalBytes =
                                    item.sizeBytes,
                                chunkSize =
                                    chunkSize,
                                chunkRootHashHex =
                                    rootHash
                            )
                            Unit
                        }
                    },
            recordReceiverResumeChunk =
                resumableStagingStore
                    ?.let { store ->
                        {
                            originalSessionId,
                            itemId,
                            chunkIndex,
                            chunkData ->
                            store.recordReceiverChunk(
                                originalSessionId =
                                    originalSessionId
                                        .value,
                                itemId =
                                    itemId.value,
                                chunkIndex =
                                    chunkIndex,
                                chunkData =
                                    chunkData
                            )
                        }
                    },
            purgeResumeItemAuthority =
                resumableStagingStore
                    ?.let { store ->
                        {
                            originalSessionId,
                            itemId ->
                            store.purgeSession(
                                originalSessionId =
                                    originalSessionId
                                        .value,
                                itemId =
                                    itemId.value
                            )
                        }
                    },
            enableTrueResumeCapability =
                false
        )
    }

    /**
     * Constructs a sender-side reconnect coordinator for exactly one retained resumable item.
     * Ordinary sender sessions never call this method and keep resume-v1 disabled.
     */
    fun createResumeSenderSession(
        activeSessionId: SessionId,
        originalSessionId: SessionId,
        itemId: ItemId,
        peerId: PeerId,
        peerDisplayName: String,
        expectedPeerFingerprintHex: String,
        transport: TransferTransport,
        scope: CoroutineScope,
        persistentTrustManager:
            PersistentTrustManager
    ): TransferCoordinator {
        val verifier =
            resumeSourceVerifier
                ?: throw IllegalStateException(
                    "Resume source verifier is unavailable"
                )
        val store =
            resumableStagingStore
                ?: throw IllegalStateException(
                    "Durable resume store is unavailable"
                )

        return TransferCoordinator(
            sessionId =
                activeSessionId,
            role = DeviceRole.Sender,
            peerId = peerId,
            localDisplayName =
                peerDisplayName,
            transport = transport,
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            historyStore = historyStore,
            scope = scope,
            persistentTrustManager =
                persistentTrustManager,
            expectedPeerFingerprintHex =
                expectedPeerFingerprintHex,
            resumeReconnectPlan =
                ResumeReconnectPlan.Sender(
                    originalSessionId =
                        originalSessionId,
                    itemId = itemId,
                    expectedPeerFingerprintHex =
                        expectedPeerFingerprintHex
                ),
            loadSenderResumeAuthority = {
                requestedOriginalSessionId,
                requestedItemId,
                authenticatedPeerFingerprintHex ->
                val journal =
                    store.getJournal(
                        requestedOriginalSessionId
                            .value,
                        requestedItemId.value
                    )
                if (journal == null ||
                    journal.journalRole !=
                        ResumableSessionEntity
                            .ROLE_SENDER
                ) {
                    ResumeAuthorityLookup
                        .Rejected(
                            dev.glide.core.protocol
                                .ResumeRejectCodes
                                .JOURNAL_MISMATCH
                        )
                } else if (
                    System.currentTimeMillis() >=
                        journal.expiresAtMs
                ) {
                    store.purgeSession(
                        requestedOriginalSessionId
                            .value,
                        requestedItemId.value
                    )
                    ResumeAuthorityLookup
                        .Rejected(
                            dev.glide.core.protocol
                                .ResumeRejectCodes
                                .EXPIRED
                        )
                } else if (
                    journal.peerFingerprintHex !=
                        authenticatedPeerFingerprintHex ||
                    persistentTrustManager
                        .trustedPeerByFingerprint(
                            authenticatedPeerFingerprintHex
                        ) == null
                ) {
                    ResumeAuthorityLookup
                        .Rejected(
                            dev.glide.core.protocol
                                .ResumeRejectCodes
                                .UNAUTHORIZED
                        )
                } else {
                    val handleText =
                        journal.sourceHandle
                    val sourceIdentity =
                        journal.sourceIdentity
                    val wholeFileSha256Hex =
                        journal
                            .wholeFileSha256Hex
                    if (handleText == null ||
                        sourceIdentity == null ||
                        wholeFileSha256Hex ==
                            null
                    ) {
                        ResumeAuthorityLookup
                            .Rejected(
                                dev.glide.core.protocol
                                    .ResumeRejectCodes
                                    .JOURNAL_MISMATCH
                            )
                    } else {
                        val handle =
                            try {
                                SourceHandle(
                                    handleText
                                )
                            } catch (
                                _: IllegalArgumentException
                            ) {
                                null
                            }

                        if (handle == null) {
                            ResumeAuthorityLookup
                                .Rejected(
                                    dev.glide.core.protocol
                                        .ResumeRejectCodes
                                        .JOURNAL_MISMATCH
                                )
                        } else {
                            when (
                                val source =
                                    verifier.revalidate(
                                        handle =
                                            handle,
                                        expectedSourceIdentity =
                                            sourceIdentity,
                                        expectedTotalBytes =
                                            journal
                                                .totalBytes,
                                        expectedChunkSize =
                                            journal
                                                .chunkSize,
                                        expectedChunkRootHashHex =
                                            journal
                                                .chunkRootHashHex,
                                        expectedWholeFileSha256Hex =
                                            wholeFileSha256Hex
                                    )
                            ) {
                                is ResumeSourceRevalidationResult.Unavailable ->
                                    ResumeAuthorityLookup
                                        .Rejected(
                                            dev.glide.core.protocol
                                                .ResumeRejectCodes
                                                .FILE_CHANGED
                                        )

                                is ResumeSourceRevalidationResult.Changed ->
                                    ResumeAuthorityLookup
                                        .Rejected(
                                            dev.glide.core.protocol
                                                .ResumeRejectCodes
                                                .FILE_CHANGED
                                        )

                                is ResumeSourceRevalidationResult.Unchanged -> {
                                    val snapshot =
                                        source.snapshot
                                    when (
                                        val reconciled =
                                            store
                                                .reconcileSenderForResume(
                                                    originalSessionId =
                                                        requestedOriginalSessionId
                                                            .value,
                                                    itemId =
                                                        requestedItemId
                                                            .value,
                                                    authenticatedPeerFingerprintHex =
                                                        authenticatedPeerFingerprintHex,
                                                    peerStillTrusted =
                                                        true,
                                                    sourceStillAvailable =
                                                        true,
                                                    currentSourceIdentity =
                                                        snapshot
                                                            .sourceIdentity,
                                                    currentTotalBytes =
                                                        snapshot
                                                            .totalBytes,
                                                    currentChunkRootHashHex =
                                                        snapshot
                                                            .chunkRootHashHex,
                                                    currentWholeFileSha256Hex =
                                                        snapshot
                                                            .wholeFileSha256Hex
                                                )
                                    ) {
                                        is ResumeSenderRevalidationResult.Ready ->
                                            ResumeAuthorityLookup
                                                .Ready(
                                                    ResumeSenderReadySource(
                                                        authority =
                                                            ResumeRequestAuthority
                                                                .SenderSourceAuthority(
                                                                    originalSessionId =
                                                                        requestedOriginalSessionId,
                                                                    itemId =
                                                                        requestedItemId,
                                                                    chunkSize =
                                                                        reconciled
                                                                            .chunkSize,
                                                                    totalSizeBytes =
                                                                        reconciled
                                                                            .totalBytes,
                                                                    chunkRootHashHex =
                                                                        reconciled
                                                                            .chunkRootHashHex
                                                                ),
                                                        sourceHandle =
                                                            SourceHandle(
                                                                reconciled
                                                                    .sourceHandle
                                                            ),
                                                        wholeFileSha256Hex =
                                                            reconciled
                                                                .wholeFileSha256Hex
                                                    )
                                                )

                                        ResumeSenderRevalidationResult.Expired ->
                                            ResumeAuthorityLookup
                                                .Rejected(
                                                    dev.glide.core.protocol
                                                        .ResumeRejectCodes
                                                        .EXPIRED
                                                )

                                        ResumeSenderRevalidationResult.Unauthorized ->
                                            ResumeAuthorityLookup
                                                .Rejected(
                                                    dev.glide.core.protocol
                                                        .ResumeRejectCodes
                                                        .UNAUTHORIZED
                                                )

                                        ResumeSenderRevalidationResult.JournalMismatch ->
                                            ResumeAuthorityLookup
                                                .Rejected(
                                                    dev.glide.core.protocol
                                                        .ResumeRejectCodes
                                                        .JOURNAL_MISMATCH
                                                )

                                        ResumeSenderRevalidationResult.SourceUnavailable,
                                        ResumeSenderRevalidationResult.SourceChanged ->
                                            ResumeAuthorityLookup
                                                .Rejected(
                                                    dev.glide.core.protocol
                                                        .ResumeRejectCodes
                                                        .FILE_CHANGED
                                                )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            enableTrueResumeCapability =
                true
        )
    }

    /**
     * Constructs a receiver-side reconnect coordinator from a revalidated retained prefix.
     */
    fun createResumeReceiverSession(
        activeSessionId: SessionId,
        originalSessionId: SessionId,
        itemId: ItemId,
        peerId: PeerId,
        peerDisplayName: String,
        expectedPeerFingerprintHex: String,
        transport: TransferTransport,
        scope: CoroutineScope,
        persistentTrustManager:
            PersistentTrustManager
    ): TransferCoordinator {
        val store =
            resumableStagingStore
                ?: throw IllegalStateException(
                    "Durable resume store is unavailable"
                )

        return TransferCoordinator(
            sessionId =
                activeSessionId,
            role = DeviceRole.Receiver,
            peerId = peerId,
            localDisplayName =
                peerDisplayName,
            transport = transport,
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            historyStore = historyStore,
            scope = scope,
            persistentTrustManager =
                persistentTrustManager,
            expectedPeerFingerprintHex =
                expectedPeerFingerprintHex,
            resumeReconnectPlan =
                ResumeReconnectPlan.Receiver(
                    originalSessionId =
                        originalSessionId,
                    itemId = itemId,
                    expectedPeerFingerprintHex =
                        expectedPeerFingerprintHex
                ),
            loadReceiverResumeAuthority = {
                requestedOriginalSessionId,
                requestedItemId,
                authenticatedPeerFingerprintHex ->
                val peerStillTrusted =
                    persistentTrustManager
                        .trustedPeerByFingerprint(
                            authenticatedPeerFingerprintHex
                        ) != null

                when (
                    val reconciled =
                        store.reconcileReceiverForResume(
                            originalSessionId =
                                requestedOriginalSessionId
                                    .value,
                            itemId =
                                requestedItemId
                                    .value,
                            authenticatedPeerFingerprintHex =
                                authenticatedPeerFingerprintHex,
                            peerStillTrusted =
                                peerStillTrusted
                        )
                ) {
                    is ResumeReceiverRevalidationResult.Ready ->
                        ResumeAuthorityLookup
                            .Ready(
                                ResumeRequestAuthority
                                    .ReceiverJournal(
                                        originalSessionId =
                                            requestedOriginalSessionId,
                                        itemId =
                                            requestedItemId,
                                        verifiedChunkCount =
                                            reconciled
                                                .verifiedChunks,
                                        contiguousByteOffset =
                                            reconciled
                                                .contiguousByteOffset,
                                        chunkSize =
                                            reconciled
                                                .chunkSize,
                                        totalSizeBytes =
                                            reconciled
                                                .totalBytes,
                                        chunkRootHashHex =
                                            reconciled
                                                .chunkRootHashHex
                                    )
                            )

                    ResumeReceiverRevalidationResult.NotFound,
                    ResumeReceiverRevalidationResult.JournalMismatch ->
                        ResumeAuthorityLookup
                            .Rejected(
                                dev.glide.core.protocol
                                    .ResumeRejectCodes
                                    .JOURNAL_MISMATCH
                            )

                    ResumeReceiverRevalidationResult.Expired ->
                        ResumeAuthorityLookup
                            .Rejected(
                                dev.glide.core.protocol
                                    .ResumeRejectCodes
                                    .EXPIRED
                            )

                    ResumeReceiverRevalidationResult.Unauthorized ->
                        ResumeAuthorityLookup
                            .Rejected(
                                dev.glide.core.protocol
                                    .ResumeRejectCodes
                                    .UNAUTHORIZED
                            )
                }
            },
            enableTrueResumeCapability =
                true
        )
    }
}
