package dev.glide.app.debug

import android.content.Context
import dev.glide.app.di.DatabaseModule
import dev.glide.app.service.TransferRuntime
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.PairingUiState
import dev.glide.app.ui.history.HistoryItemDetail
import dev.glide.app.ui.history.HistorySessionDetail
import dev.glide.app.ui.history.HistorySessionItem
import dev.glide.app.ui.history.HistoryUiState
import dev.glide.app.ui.settings.SettingsUiState
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.core.domain.PeerId
import dev.glide.app.ui.discovery.DiscoveredPeer
import dev.glide.app.ui.discovery.DeviceType
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.app.ui.pairing.PairingUiState.PeerDeviceType
import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferItem
import dev.glide.app.navigation.GlideRoute
import dev.glide.core.ports.StorageSummary
import dev.glide.core.ports.GlideSettings
import java.io.File
import java.util.UUID

/**
 * Debug-only fixtures for automated testing and screenshot capture (HARD-031).
 *
 * This class is only available in the debug source set. All debug-state composition
 * is isolated here so the production MainActivity remains free of test scaffolding.
 */
object DebugFixtures {

    data class DebugComposition(
        val route: GlideRoute,
        val transferState: TransferUiState? = null,
        val pairingState: PairingUiState? = null,
        val discoveryState: DiscoveryUiState? = null,
        val receiveState: ReceiveReadinessUiState? = null,
        val historyState: HistoryUiState? = null,
        val settingsState: SettingsUiState? = null
    )

    /**
     * Resolves debug-state overrides for automated testing and screenshot capture.
     */
    fun resolveDebugComposition(
        context: Context,
        transferRuntime: TransferRuntime,
        isDebuggable: Boolean,
        debugState: String?,
        debugTheme: String?,
        debugPosture: String?
    ): DebugComposition {
        if (!isDebuggable || debugState == null) {
            return DebugComposition(route = GlideRoute.Home)
        }

        return when (debugState) {
            "completed_receiver" -> {
                val receivedDir = File(context.filesDir, "received").apply { if (!exists()) mkdirs() }
                val sampleFile = File(receivedDir, "sample_document.txt").apply {
                    if (!exists()) writeText("Verified received file content in Glide quarantine.")
                }
                DebugComposition(
                    route = GlideRoute.Transfer("sample-session"),
                    transferState = TransferUiState.Completed(
                        sessionId = "sample-session",
                        role = DeviceRole.Receiver,
                        peerName = "Nearby Sender",
                        totalBytes = sampleFile.length(),
                        itemName = "sample_document.txt",
                        committedFileKey = sampleFile.name
                    )
                )
            }
            "history" -> {
                DebugComposition(
                    route = GlideRoute.History(),
                    historyState = HistoryUiState(
                        filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                        sessions = emptyList(),
                        isLoading = false
                    )
                )
            }
            "history_empty" -> {
                DebugComposition(
                    route = GlideRoute.History(),
                    historyState = HistoryUiState(
                        filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                        sessions = emptyList(),
                        isLoading = false
                    )
                )
            }
            "history_populated" -> {
                val now = System.currentTimeMillis()
                val session1 = HistorySessionItem(
                    sessionId = "history-session-1",
                    role = DeviceRole.Sender,
                    peerLabel = "MacBook Pro M3",
                    startedAtMs = now - 180_000L,
                    endedAtMs = now - 170_000L,
                    outcome = "Completed",
                    itemCount = 1,
                    totalBytes = 15_200_000L
                )
                val session2 = HistorySessionItem(
                    sessionId = "history-session-2",
                    role = DeviceRole.Receiver,
                    peerLabel = "Pixel 10 Pro XL",
                    startedAtMs = now - 3_600_000L,
                    endedAtMs = now - 3_550_000L,
                    outcome = "Completed",
                    itemCount = 3,
                    totalBytes = 142_500_000L
                )
                val session3 = HistorySessionItem(
                    sessionId = "history-session-3",
                    role = DeviceRole.Receiver,
                    peerLabel = "VeryLongDeviceNameFromNearbyDiscoveryLabRoom102B_Android17",
                    startedAtMs = now - 86_400_000L,
                    endedAtMs = now - 86_395_000L,
                    outcome = "Failed",
                    itemCount = 2,
                    totalBytes = 8_400_000L
                )
                DebugComposition(
                    route = GlideRoute.History(),
                    historyState = HistoryUiState(
                        filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                        sessions = listOf(session1, session2, session3),
                        isLoading = false
                    )
                )
            }
            "history_detail_available" -> {
                val now = System.currentTimeMillis()
                val session = HistorySessionItem(
                    sessionId = "session-available",
                    role = DeviceRole.Receiver,
                    peerLabel = "Galaxy Tab S10+",
                    startedAtMs = now - 120_000L,
                    endedAtMs = now - 110_000L,
                    outcome = "Completed",
                    itemCount = 2,
                    totalBytes = 52_000_000L
                )
                val item1 = HistoryItemDetail(
                    itemId = "item-avail-1",
                    ordinal = 0,
                    displayName = "project_milestone_deck_final.key",
                    mimeType = "application/octet-stream",
                    offeredBytes = 40_000_000L,
                    actualBytes = 40_000_000L,
                    outcome = "Verified",
                    isFileAvailable = true
                )
                val item2 = HistoryItemDetail(
                    itemId = "item-avail-2",
                    ordinal = 1,
                    displayName = "brand_guidelines_2026_vector.pdf",
                    mimeType = "application/pdf",
                    offeredBytes = 12_000_000L,
                    actualBytes = 12_000_000L,
                    outcome = "Verified",
                    isFileAvailable = true
                )
                DebugComposition(
                    route = GlideRoute.History(),
                    historyState = HistoryUiState(
                        filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                        sessions = listOf(session),
                        selectedSessionDetail = HistorySessionDetail(session, listOf(item1, item2))
                    )
                )
            }
            "history_detail_missing" -> {
                val now = System.currentTimeMillis()
                val session = HistorySessionItem(
                    sessionId = "session-missing",
                    role = DeviceRole.Receiver,
                    peerLabel = "Pixel 9 Pro",
                    startedAtMs = now - 300_000L,
                    endedAtMs = now - 290_000L,
                    outcome = "Completed",
                    itemCount = 1,
                    totalBytes = 120_000_000L
                )
                val item1 = HistoryItemDetail(
                    itemId = "item-missing-1",
                    ordinal = 0,
                    displayName = "deleted_after_export_demo.mp4",
                    mimeType = "video/mp4",
                    offeredBytes = 120_000_000L,
                    actualBytes = 120_000_000L,
                    outcome = "Verified",
                    isFileAvailable = false
                )
                DebugComposition(
                    route = GlideRoute.History(),
                    historyState = HistoryUiState(
                        filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                        sessions = listOf(session),
                        selectedSessionDetail = HistorySessionDetail(session, listOf(item1))
                    )
                )
            }
            "settings", "settings_main" -> {
                DebugComposition(
                    route = GlideRoute.Settings,
                    settingsState = SettingsUiState(
                        settings = GlideSettings(deviceName = "Pixel 10 Pro"),
                        storageSummary = StorageSummary(totalFiles = 0, totalBytes = 0L, activeFiles = 0)
                    )
                )
            }
            "settings_storage_uninstall" -> {
                DebugComposition(
                    route = GlideRoute.Settings,
                    settingsState = SettingsUiState(
                        settings = GlideSettings(deviceName = "Pixel 10 Pro"),
                        storageSummary = StorageSummary(totalFiles = 4, totalBytes = 68_400_000L, activeFiles = 1)
                    )
                )
            }
            "settings_diagnostics_licenses" -> {
                DebugComposition(
                    route = GlideRoute.Settings,
                    settingsState = SettingsUiState(
                        settings = GlideSettings(deviceName = "Pixel 10 Pro"),
                        isNearbyDiagnosticsOpen = true,
                        nearbyDiagnostics = dev.glide.app.ui.settings.NearbyDiagnosticsState(
                            bluetoothEnabled = true,
                            wifiEnabled = true,
                            permissionsGranted = true,
                            radioBand = "5 GHz / Wi-Fi Direct (High-Speed)",
                            gmsVersion = "Nearby Connections v1.0 (Direct Socket P2P)"
                        )
                    )
                )
            }
            "share_test_single" -> {
                val cacheShareDir = File(context.cacheDir, "shares").apply { if (!exists()) mkdirs() }
                val testFile = File(cacheShareDir, "sample_share.pdf").apply {
                    if (!exists()) writeText("%PDF-1.4 sample shared document content")
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", testFile)
                DebugComposition(
                    route = GlideRoute.Selection(listOf(uri.toString()))
                )
            }
            "share_test_multiple" -> {
                val cacheShareDir = File(context.cacheDir, "shares").apply { if (!exists()) mkdirs() }
                val file1 = File(cacheShareDir, "vacation_1.jpg").apply { if (!exists()) writeText("JPEG DATA 1") }
                val file2 = File(cacheShareDir, "vacation_2.jpg").apply { if (!exists()) writeText("JPEG DATA 2") }
                val uri1 = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file1)
                val uri2 = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file2)
                DebugComposition(
                    route = GlideRoute.Selection(listOf(uri1.toString(), uri2.toString()))
                )
            }
            "share_test_busy" -> {
                val state = TransferUiState.Transferring(
                    sessionId = "active-transfer-session",
                    role = DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemName = "large_dataset.zip",
                    transferredBytes = 14500000L,
                    totalBytes = 45000000L,
                    progressFraction = 0.32f,
                    speedBytesPerSec = 5200000L
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("active-transfer-session"),
                    transferState = state
                )
            }
            "service_transfer_active" -> {
                val sid = "fgs-live-session"
                dev.glide.app.service.TransferService.startService(context, sid, "Pixel 9 Pro")
                DebugComposition(
                    route = GlideRoute.Transfer(sid),
                    transferState = TransferUiState.Transferring(
                        sessionId = sid,
                        role = DeviceRole.Sender,
                        peerName = "Pixel 9 Pro",
                        itemName = "system_backup.tar.gz",
                        transferredBytes = 32000000L,
                        totalBytes = 85000000L,
                        progressFraction = 0.38f,
                        speedBytesPerSec = 7500000L
                    )
                )
            }
            "service_notification_redacted" -> {
                val sid = "fgs-redacted-session"
                kotlinx.coroutines.runBlocking {
                    DatabaseModule.provideSettingsStore(context).updateNotificationDetails(false)
                }
                dev.glide.app.service.TransferService.startService(context, sid, "Pixel 9 Pro")
                val state = TransferUiState.Transferring(
                    sessionId = sid,
                    role = DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemName = "secret_tax_returns.pdf",
                    transferredBytes = 4500000L,
                    totalBytes = 12000000L,
                    progressFraction = 0.375f,
                    speedBytesPerSec = 1500000L
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer(sid),
                    transferState = state
                )
            }
            "service_notification_detailed" -> {
                val sid = "fgs-detailed-session"
                kotlinx.coroutines.runBlocking {
                    DatabaseModule.provideSettingsStore(context).updateNotificationDetails(true)
                }
                dev.glide.app.service.TransferService.startService(context, sid, "Pixel 9 Pro")
                val state = TransferUiState.Transferring(
                    sessionId = sid,
                    role = DeviceRole.Receiver,
                    peerName = "Pixel 9 Pro",
                    itemName = "family_vacation_4k.mp4",
                    transferredBytes = 240000000L,
                    totalBytes = 500000000L,
                    progressFraction = 0.48f,
                    speedBytesPerSec = 8200000L
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer(sid),
                    transferState = state
                )
            }
            "service_retry_interrupted" -> {
                val sid = "retry-interrupted-session"
                val state = TransferUiState.Failed(
                    sessionId = sid,
                    role = DeviceRole.Sender,
                    message = "Connection to peer lost during transfer.",
                    code = "PEER_LOST",
                    isRecoverable = true,
                    isInterrupted = true,
                    isReceiptUnconfirmed = false,
                    unfinishedCount = 2,
                    totalCount = 3,
                    canRetry = true,
                    peerName = "Pixel 9 Pro"
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer(sid),
                    transferState = state
                )
            }
            "service_retry_unconfirmed" -> {
                val sid = "retry-unconfirmed-session"
                val state = TransferUiState.Failed(
                    sessionId = sid,
                    role = DeviceRole.Sender,
                    message = "Connection lost while awaiting receiver confirmation.",
                    code = "DELIVERY_UNCONFIRMED",
                    isRecoverable = true,
                    isInterrupted = true,
                    isReceiptUnconfirmed = true,
                    unfinishedCount = 1,
                    totalCount = 2,
                    canRetry = true,
                    peerName = "Pixel 9 Pro"
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer(sid),
                    transferState = state
                )
            }
            "service_reconciliation_audit" -> {
                val sid = UUID.randomUUID().toString()
                val completedItemId = UUID.randomUUID().toString()
                val interruptedItemId = UUID.randomUUID().toString()
                val db = DatabaseModule.provideGlideDatabase(context)
                val receiveStore = DatabaseModule.provideReceiveStore(
                    context,
                    db.receiveJournalDao(),
                    db.receivedFileDao()
                )
                val reconciler = DatabaseModule.provideProcessDeathReconciler(
                    receiveStore,
                    db.sessionDao(),
                    db.itemDao(),
                    db.receivedFileDao()
                )

                kotlinx.coroutines.runBlocking {
                    db.sessionDao().insert(
                        dev.glide.data.db.SessionEntity(
                            sessionId = sid,
                            batchId = UUID.randomUUID().toString(),
                            role = "Receiver",
                            peerLabel = "Pixel 8 Pro",
                            startedAtMs = System.currentTimeMillis() - 60000L
                        )
                    )
                    val payload1 = "Verified document survived crash".toByteArray()
                    val key1 = UUID.randomUUID().toString()
                    java.io.File(receiveStore.receivedDir, key1).writeBytes(payload1)
                    db.receivedFileDao().insertOrUpdate(
                        dev.glide.data.db.ReceivedFileEntity(
                            fileKey = key1,
                            originatingItemId = completedItemId,
                            displayName = "financial_report.pdf",
                            mime = "application/pdf",
                            length = payload1.size.toLong(),
                            digest = Sha256Digest.compute(payload1).value,
                            receivedAtMs = System.currentTimeMillis() - 50000L
                        )
                    )
                    db.itemDao().insert(
                        dev.glide.data.db.ItemEntity(
                            itemId = completedItemId,
                            sessionId = sid,
                            ordinal = 0,
                            displayName = "financial_report.pdf",
                            mime = "application/pdf",
                            offeredBytes = payload1.size.toLong(),
                            actualBytes = payload1.size.toLong(),
                            digest = Sha256Digest.compute(payload1).value,
                            outcome = "Completed"
                        )
                    )
                    val partFile = java.io.File(receiveStore.stagingDir, "$interruptedItemId.part")
                    partFile.writeBytes("corrupted partial stream".toByteArray())
                    db.receiveJournalDao().insertOrUpdate(
                        dev.glide.data.db.ReceiveJournalEntity(
                            itemId = interruptedItemId,
                            sessionId = sid,
                            stagingKey = "$interruptedItemId.part",
                            finalKey = UUID.randomUUID().toString(),
                            displayName = "video_recording.mp4",
                            mimeType = "video/mp4",
                            expectedBytes = 50000L,
                            phase = dev.glide.data.db.JournalPhases.WRITING,
                            updatedAtMs = System.currentTimeMillis() - 40000L
                        )
                    )
                    db.itemDao().insert(
                        dev.glide.data.db.ItemEntity(
                            itemId = interruptedItemId,
                            sessionId = sid,
                            ordinal = 1,
                            displayName = "video_recording.mp4",
                            mime = "video/mp4",
                            offeredBytes = 50000L
                        )
                    )
                    val summary = reconciler.reconcile()
                    android.util.Log.i("GlideAudit", "Reconciliation summary: $summary")
                }
                DebugComposition(route = GlideRoute.History(sid))
            }
            "service_maintenance_audit" -> {
                val db = DatabaseModule.provideGlideDatabase(context)
                val receiveStore = DatabaseModule.provideReceiveStore(
                    context,
                    db.receiveJournalDao(),
                    db.receivedFileDao()
                )
                val historyStore = DatabaseModule.provideHistoryStore(
                    db.sessionDao(),
                    db.itemDao(),
                    db.receivedFileDao()
                )
                val resumableStagingStore = DatabaseModule.provideResumableStagingStore(
                    context,
                    db.resumableSessionDao()
                )
                val cleaner = DatabaseModule.provideOrphanMaintenanceCleaner(
                    context,
                    receiveStore,
                    db.receiveJournalDao(),
                    db.receivedFileDao(),
                    historyStore,
                    resumableStagingStore
                )

                kotlinx.coroutines.runBlocking {
                    val now = System.currentTimeMillis()
                    val cutoff25h = now - (25L * 60L * 60L * 1000L)
                    val cutoff1h = now - (1L * 60L * 60L * 1000L)
                    val receivingDir = receiveStore.stagingDir.apply { if (!exists()) mkdirs() }
                    File(receivingDir, "old_orphan.part").apply {
                        writeText("old orphan partial")
                        setLastModified(cutoff25h)
                    }
                    File(receivingDir, "recent_orphan.part").apply {
                        writeText("recent orphan partial")
                        setLastModified(cutoff1h)
                    }
                    val activeSessionId = UUID.randomUUID().toString()
                    val activeItemId = UUID.randomUUID().toString()
                    File(receivingDir, "$activeItemId.part").apply {
                        writeText("active session partial")
                        setLastModified(cutoff25h)
                    }
                    db.receiveJournalDao().insertOrUpdate(
                        dev.glide.data.db.ReceiveJournalEntity(
                            itemId = activeItemId,
                            sessionId = activeSessionId,
                            stagingKey = "$activeItemId.part",
                            finalKey = UUID.randomUUID().toString(),
                            displayName = "active_transfer.mp4",
                            mimeType = "video/mp4",
                            expectedBytes = 1000L,
                            phase = dev.glide.data.db.JournalPhases.WRITING,
                            updatedAtMs = cutoff25h
                        )
                    )
                    val stagingSourcesDir = File(context.cacheDir, "staging_sources").apply { if (!exists()) mkdirs() }
                    File(stagingSourcesDir, "src_stage_old.staged").apply {
                        writeText("old staged source content")
                        setLastModified(cutoff25h)
                    }
                    File(stagingSourcesDir, "src_stage_recent.staged").apply {
                        writeText("recent staged source content")
                        setLastModified(cutoff1h)
                    }
                    val missingFileKey = UUID.randomUUID().toString()
                    db.receivedFileDao().insertOrUpdate(
                        dev.glide.data.db.ReceivedFileEntity(
                            fileKey = missingFileKey,
                            originatingItemId = UUID.randomUUID().toString(),
                            displayName = "ghost_file.pdf",
                            mime = "application/pdf",
                            length = 5000L,
                            digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            receivedAtMs = cutoff25h
                        )
                    )
                    val oldSessionId = UUID.randomUUID().toString()
                    val oldStartedAt = now - (95L * 24L * 60L * 60L * 1000L)
                    db.sessionDao().insert(
                        dev.glide.data.db.SessionEntity(
                            sessionId = oldSessionId,
                            batchId = UUID.randomUUID().toString(),
                            role = "Receiver",
                            peerLabel = "Ancient Phone",
                            startedAtMs = oldStartedAt,
                            endedAtMs = oldStartedAt + 5000L,
                            outcome = "Completed"
                        )
                    )
                    val result = cleaner.runMaintenance(activeSessionSupplier = { activeSessionId })
                    android.util.Log.i("GlideMaintenanceAudit", "Maintenance result: $result")
                }
                DebugComposition(route = GlideRoute.History())
            }
            "theme_light", "theme_dark", "theme_dynamic" -> {
                DebugComposition(route = GlideRoute.Home)
            }
            "theme_settings" -> {
                DebugComposition(route = GlideRoute.Settings)
            }
            "home_active_transfer" -> {
                transferRuntime.setTransferStateForTesting(
                    TransferUiState.Transferring(
                        sessionId = "active-transfer-session-88",
                        role = DeviceRole.Sender,
                        peerName = "Nearby device",
                        itemName = "active-transfer.bin",
                        transferredBytes = 1L,
                        totalBytes = 2L,
                        progressFraction = 0.5f
                    )
                )
                DebugComposition(route = GlideRoute.Home)
            }
            "discovery_searching" -> {
                DebugComposition(
                    route = GlideRoute.Discovery(),
                    discoveryState = DiscoveryUiState.Searching(peers = emptyList(), secondsElapsed = 8)
                )
            }
            "discovery_peers" -> {
                val p1 = DiscoveredPeer(
                    id = PeerId("peer-pixel-9"),
                    displayName = "Pixel 9 Pro",
                    deviceType = DeviceType.Phone,
                    isAvailable = true,
                    isSelected = true
                )
                val p2 = DiscoveredPeer(
                    id = PeerId("peer-galaxy-tab"),
                    displayName = "Galaxy Tab S9",
                    deviceType = DeviceType.Tablet,
                    isAvailable = true
                )
                val p3 = DiscoveredPeer(
                    id = PeerId("peer-macbook"),
                    displayName = "MacBook Air",
                    deviceType = DeviceType.Computer,
                    isAvailable = true
                )
                DebugComposition(
                    route = GlideRoute.Discovery(),
                    discoveryState = DiscoveryUiState.Searching(
                        peers = listOf(p1, p2, p3),
                        secondsElapsed = 14,
                        selectedPeerId = p1.id
                    )
                )
            }
            "discovery_blocked" -> {
                DebugComposition(
                    route = GlideRoute.Discovery(),
                    discoveryState = DiscoveryUiState.Blocked(
                        dev.glide.app.ui.discovery.BlockedReason.BluetoothDisabled
                    )
                )
            }
            "discovery_timeout" -> {
                DebugComposition(
                    route = GlideRoute.Discovery(),
                    discoveryState = DiscoveryUiState.TimedOut()
                )
            }
            "receive_readiness" -> {
                DebugComposition(
                    route = GlideRoute.ReceiveReadiness,
                    receiveState = ReceiveReadinessUiState.Advertising(
                        localDeviceName = "Pixel 10 Pro",
                        secondsRemaining = 114
                    )
                )
            }
            "verification_comparing" -> {
                DebugComposition(
                    route = GlideRoute.Transfer("verif-session-1"),
                    pairingState = PairingUiState.Verifying(
                        peerId = PeerId("peer-pixel-9"),
                        peerName = "Pixel 9 Pro",
                        authDigits = "482916",
                        secondsRemaining = 54,
                        localConfirmed = false,
                        localDeviceName = "Pixel 10 Pro",
                        isSameNamePeer = false,
                        deviceType = DeviceType.Phone
                    )
                )
            }
            "verification_same_name" -> {
                DebugComposition(
                    route = GlideRoute.Transfer("verif-session-2"),
                    pairingState = PairingUiState.Verifying(
                        peerId = PeerId("ep-f8a2-same"),
                        peerName = "Pixel 10 Pro",
                        authDigits = "730514",
                        secondsRemaining = 48,
                        localConfirmed = false,
                        localDeviceName = "Pixel 10 Pro",
                        isSameNamePeer = true,
                        deviceType = DeviceType.Phone
                    )
                )
            }
            "verification_waiting_peer" -> {
                DebugComposition(
                    route = GlideRoute.Transfer("verif-session-3"),
                    pairingState = PairingUiState.Verifying(
                        peerId = PeerId("peer-macbook"),
                        peerName = "MacBook Air",
                        authDigits = "8914",
                        secondsRemaining = 38,
                        localConfirmed = true,
                        localDeviceName = "Pixel 10 Pro",
                        isSameNamePeer = false,
                        deviceType = DeviceType.Computer
                    )
                )
            }
            "offer_incoming_single" -> {
                val singleItem = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000001"),
                    name = "holiday_photos_archive.zip",
                    mimeType = "application/zip",
                    sizeBytes = 4_500_000L
                )
                val state = TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-1",
                    role = DeviceRole.Receiver,
                    peerName = "Pixel 9 Pro",
                    itemCount = 1,
                    totalBytes = 4_500_000L,
                    firstItemName = singleItem.name,
                    items = listOf(singleItem),
                    secondsRemaining = 58,
                    storageAvailableBytes = 45_000_000_000L,
                    storageRequiredBytes = 4_500_000L,
                    peerDeviceType = DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("offer-session-1"),
                    transferState = state
                )
            }
            "offer_incoming_multi" -> {
                val item1 = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000002"),
                    name = "quarterly_financial_report_final_v3_reviewed.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 12_400_000L
                )
                val item2 = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000003"),
                    name = "project_roadmap_and_milestones_presentation.pptx",
                    mimeType = "application/vnd.ms-powerpoint",
                    sizeBytes = 18_100_000L
                )
                val item3 = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000004"),
                    name = "camera_demo_recording_4k_sample.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 12_000_000L
                )
                val state = TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-2",
                    role = DeviceRole.Receiver,
                    peerName = "Galaxy Tab S9",
                    itemCount = 3,
                    totalBytes = 42_500_000L,
                    firstItemName = item1.name,
                    items = listOf(item1, item2, item3),
                    secondsRemaining = 52,
                    storageAvailableBytes = 38_000_000_000L,
                    storageRequiredBytes = 42_500_000L,
                    peerDeviceType = DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("offer-session-2"),
                    transferState = state
                )
            }
            "offer_insufficient_storage" -> {
                val largeItem = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000005"),
                    name = "developer_disk_image_installer_full.iso",
                    mimeType = "application/octet-stream",
                    sizeBytes = 25_000_000_000L
                )
                val state = TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-3",
                    role = DeviceRole.Receiver,
                    peerName = "MacBook Air",
                    itemCount = 1,
                    totalBytes = 25_000_000_000L,
                    firstItemName = largeItem.name,
                    items = listOf(largeItem),
                    secondsRemaining = 45,
                    storageAvailableBytes = 10_000_000_000L,
                    storageRequiredBytes = 25_000_000_000L,
                    peerDeviceType = DeviceType.Computer
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("offer-session-3"),
                    transferState = state
                )
            }
            "offer_sender_waiting" -> {
                val state = TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-4",
                    role = DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemCount = 2,
                    totalBytes = 18_400_000L,
                    firstItemName = "dataset_part1.csv",
                    items = listOf(
                        TransferItem(
                            id = ItemId("00000000-0000-4000-8000-000000000006"),
                            name = "dataset_part1.csv",
                            mimeType = "text/csv",
                            sizeBytes = 9_200_000L
                        ),
                        TransferItem(
                            id = ItemId("00000000-0000-4000-8000-000000000007"),
                            name = "dataset_part2.csv",
                            mimeType = "text/csv",
                            sizeBytes = 9_200_000L
                        )
                    ),
                    secondsRemaining = 50,
                    peerDeviceType = DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("offer-session-4"),
                    transferState = state
                )
            }
            "transfer_active_single" -> {
                val state = TransferUiState.Transferring(
                    sessionId = "active-single-session",
                    role = DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemName = "project_presentation_deck.key",
                    transferredBytes = 18_000_000L,
                    totalBytes = 40_000_000L,
                    progressFraction = 0.45f,
                    speedBytesPerSec = 14_200_000L,
                    itemIndex = 1,
                    itemCount = 1,
                    peerDeviceType = DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("active-single-session"),
                    transferState = state
                )
            }
            "transfer_active_multi" -> {
                val state = TransferUiState.Transferring(
                    sessionId = "active-multi-session",
                    role = DeviceRole.Receiver,
                    peerName = "Galaxy Tab S9",
                    itemName = "brand_assets_vector.zip",
                    transferredBytes = 62_000_000L,
                    totalBytes = 100_000_000L,
                    progressFraction = 0.62f,
                    speedBytesPerSec = 18_500_000L,
                    itemIndex = 2,
                    itemCount = 4,
                    peerDeviceType = DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("active-multi-session"),
                    transferState = state
                )
            }
            "transfer_verifying" -> {
                val state = TransferUiState.Verifying(
                    sessionId = "verifying-session",
                    role = DeviceRole.Receiver,
                    peerName = "Galaxy Tab S9",
                    itemName = "brand_assets_vector.zip",
                    itemIndex = 2,
                    itemCount = 4,
                    totalBytes = 100_000_000L,
                    peerDeviceType = DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("verifying-session"),
                    transferState = state
                )
            }
            "transfer_completed_receiver" -> {
                val receivedDir = File(context.filesDir, "received").apply { if (!exists()) mkdirs() }
                val sampleFile = File(receivedDir, "project_presentation_deck.key").apply {
                    if (!exists()) writeText("Simulated verified received presentation deck content.")
                }
                val itemA = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000010"),
                    name = "project_presentation_deck.key",
                    mimeType = "application/octet-stream",
                    sizeBytes = 40_000_000L
                )
                val itemB = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000011"),
                    name = "brand_assets_vector.zip",
                    mimeType = "application/zip",
                    sizeBytes = 100_000_000L
                )
                val state = TransferUiState.Completed(
                    sessionId = "completed-receiver-session",
                    role = DeviceRole.Receiver,
                    peerName = "Pixel 9 Pro",
                    totalBytes = 140_000_000L,
                    itemName = "project_presentation_deck.key",
                    committedFileKey = sampleFile.name,
                    itemCount = 2,
                    items = listOf(itemA, itemB),
                    peerDeviceType = DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("completed-receiver-session"),
                    transferState = state
                )
            }
            "transfer_completed_sender" -> {
                val item = TransferItem(
                    id = ItemId("00000000-0000-4000-8000-000000000012"),
                    name = "contract_agreement_signed.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 2_400_000L
                )
                val state = TransferUiState.Completed(
                    sessionId = "completed-sender-session",
                    role = DeviceRole.Sender,
                    peerName = "MacBook Air",
                    totalBytes = 2_400_000L,
                    itemName = item.name,
                    itemCount = 1,
                    items = listOf(item),
                    peerDeviceType = DeviceType.Computer
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("completed-sender-session"),
                    transferState = state
                )
            }
            "transfer_interrupted_partial" -> {
                val state = TransferUiState.Failed(
                    sessionId = "interrupted-partial-session",
                    role = DeviceRole.Sender,
                    message = "Connection to peer lost during transfer.",
                    code = "TRANSFER_INTERRUPTED",
                    isRecoverable = true,
                    isInterrupted = true,
                    isReceiptUnconfirmed = false,
                    unfinishedCount = 2,
                    totalCount = 4,
                    canRetry = true,
                    peerName = "Galaxy Tab S9",
                    peerDeviceType = DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("interrupted-partial-session"),
                    transferState = state
                )
            }
            "transfer_receipt_unconfirmed" -> {
                val state = TransferUiState.Failed(
                    sessionId = "receipt-unconfirmed-session",
                    role = DeviceRole.Sender,
                    message = "Connection lost while awaiting receiver confirmation.",
                    code = "DELIVERY_UNCONFIRMED",
                    isRecoverable = true,
                    isInterrupted = true,
                    isReceiptUnconfirmed = true,
                    unfinishedCount = 1,
                    totalCount = 2,
                    canRetry = true,
                    peerName = "MacBook Air",
                    peerDeviceType = DeviceType.Computer
                )
                transferRuntime.setTransferStateForTesting(state)
                DebugComposition(
                    route = GlideRoute.Transfer("receipt-unconfirmed-session"),
                    transferState = state
                )
            }
            "motion_reduced_discovery" -> {
                DebugComposition(route = GlideRoute.Discovery())
            }
            "motion_reduced_receive" -> {
                DebugComposition(route = GlideRoute.ReceiveReadiness)
            }
            "motion_reduced_settings" -> {
                DebugComposition(route = GlideRoute.Settings)
            }
            else -> {
                DebugComposition(route = GlideRoute.Home)
            }
        }
    }
}
