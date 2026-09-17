package dev.glide.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.glide.app.ingress.IngressOutcome
import dev.glide.app.ingress.ShareIngressHandler
import dev.glide.app.ingress.ShareIngressParser
import dev.glide.app.ingress.ShareIngressResult
import dev.glide.app.navigation.GlideAppNavDisplay
import dev.glide.app.navigation.GlideRoute
import dev.glide.app.service.TransferRuntime
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.core.domain.DeviceRole
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.SettingsStore
import dev.glide.core.ports.ThemePreference
import dev.glide.designsystem.GlideTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import javax.inject.Inject

/**
 * Data class representing alert dialog feedback for Share Sheet ingress.
 */
data class IngressDialogState(
    val title: String,
    val message: String
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var transferRuntime: TransferRuntime

    private var lastHandledFingerprint: String? = null
    private val incomingOutcomeFlow = MutableStateFlow<IngressOutcome?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debugState = intent?.getStringExtra("debug_state")
        val debugTheme = intent?.getStringExtra("debug_theme")
        val debugPosture = intent?.getStringExtra("debug_posture")

        var debugDiscoveryState: dev.glide.app.ui.discovery.DiscoveryUiState? = null
        var debugReceiveState: dev.glide.app.ui.discovery.ReceiveReadinessUiState? = null
        var debugPairingState: dev.glide.app.ui.pairing.PairingUiState? = null
        var debugHistoryState: dev.glide.app.ui.history.HistoryUiState? = null
        var debugSettingsState: dev.glide.app.ui.settings.SettingsUiState? = null

        // 1. Determine debug-state overrides for automated testing and screenshot capture
        val (debugRoute, debugUiState) = when {
            isDebuggable && debugState == "completed_receiver" -> {
                val receivedDir = File(filesDir, "received").apply { if (!exists()) mkdirs() }
                val sampleFile = File(receivedDir, "sample_document.txt").apply {
                    if (!exists()) writeText("Verified received file content in Glide quarantine.")
                }
                GlideRoute.Transfer("sample-session") to TransferUiState.Completed(
                    sessionId = "sample-session",
                    role = DeviceRole.Receiver,
                    peerName = "Nearby Sender",
                    totalBytes = sampleFile.length(),
                    itemName = "sample_document.txt",
                    committedFileKey = sampleFile.name
                )
            }
            isDebuggable && (debugState == "history" || debugState == "history_empty") -> {
                debugHistoryState = dev.glide.app.ui.history.HistoryUiState(
                    filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                    sessions = emptyList(),
                    isLoading = false
                )
                GlideRoute.History() to null
            }
            isDebuggable && debugState == "history_populated" -> {
                val now = System.currentTimeMillis()
                val session1 = dev.glide.app.ui.history.HistorySessionItem(
                    sessionId = "history-session-1",
                    role = DeviceRole.Sender,
                    peerLabel = "MacBook Pro M3",
                    startedAtMs = now - 180_000L,
                    endedAtMs = now - 170_000L,
                    outcome = "Completed",
                    itemCount = 1,
                    totalBytes = 15_200_000L
                )
                val session2 = dev.glide.app.ui.history.HistorySessionItem(
                    sessionId = "history-session-2",
                    role = DeviceRole.Receiver,
                    peerLabel = "Pixel 10 Pro XL",
                    startedAtMs = now - 3_600_000L,
                    endedAtMs = now - 3_550_000L,
                    outcome = "Completed",
                    itemCount = 3,
                    totalBytes = 142_500_000L
                )
                val session3 = dev.glide.app.ui.history.HistorySessionItem(
                    sessionId = "history-session-3",
                    role = DeviceRole.Receiver,
                    peerLabel = "VeryLongDeviceNameFromNearbyDiscoveryLabRoom102B_Android17",
                    startedAtMs = now - 86_400_000L,
                    endedAtMs = now - 86_395_000L,
                    outcome = "Failed",
                    itemCount = 2,
                    totalBytes = 8_400_000L
                )
                debugHistoryState = dev.glide.app.ui.history.HistoryUiState(
                    filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                    sessions = listOf(session1, session2, session3),
                    isLoading = false
                )
                GlideRoute.History() to null
            }
            isDebuggable && debugState == "history_detail_available" -> {
                val now = System.currentTimeMillis()
                val session = dev.glide.app.ui.history.HistorySessionItem(
                    sessionId = "session-available",
                    role = DeviceRole.Receiver,
                    peerLabel = "Galaxy Tab S10+",
                    startedAtMs = now - 120_000L,
                    endedAtMs = now - 110_000L,
                    outcome = "Completed",
                    itemCount = 2,
                    totalBytes = 52_000_000L
                )
                val item1 = dev.glide.app.ui.history.HistoryItemDetail(
                    itemId = "item-avail-1",
                    ordinal = 0,
                    displayName = "project_milestone_deck_final.key",
                    mimeType = "application/octet-stream",
                    offeredBytes = 40_000_000L,
                    actualBytes = 40_000_000L,
                    outcome = "Verified",
                    isFileAvailable = true
                )
                val item2 = dev.glide.app.ui.history.HistoryItemDetail(
                    itemId = "item-avail-2",
                    ordinal = 1,
                    displayName = "brand_guidelines_2026_vector.pdf",
                    mimeType = "application/pdf",
                    offeredBytes = 12_000_000L,
                    actualBytes = 12_000_000L,
                    outcome = "Verified",
                    isFileAvailable = true
                )
                debugHistoryState = dev.glide.app.ui.history.HistoryUiState(
                    filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                    sessions = listOf(session),
                    selectedSessionDetail = dev.glide.app.ui.history.HistorySessionDetail(session, listOf(item1, item2))
                )
                GlideRoute.History() to null
            }
            isDebuggable && debugState == "history_detail_missing" -> {
                val now = System.currentTimeMillis()
                val session = dev.glide.app.ui.history.HistorySessionItem(
                    sessionId = "session-missing",
                    role = DeviceRole.Receiver,
                    peerLabel = "Pixel 9 Pro",
                    startedAtMs = now - 300_000L,
                    endedAtMs = now - 290_000L,
                    outcome = "Completed",
                    itemCount = 1,
                    totalBytes = 120_000_000L
                )
                val item1 = dev.glide.app.ui.history.HistoryItemDetail(
                    itemId = "item-missing-1",
                    ordinal = 0,
                    displayName = "deleted_after_export_demo.mp4",
                    mimeType = "video/mp4",
                    offeredBytes = 120_000_000L,
                    actualBytes = 120_000_000L,
                    outcome = "Verified",
                    isFileAvailable = false
                )
                debugHistoryState = dev.glide.app.ui.history.HistoryUiState(
                    filter = dev.glide.app.ui.history.HistoryFilter.ALL,
                    sessions = listOf(session),
                    selectedSessionDetail = dev.glide.app.ui.history.HistorySessionDetail(session, listOf(item1))
                )
                GlideRoute.History() to null
            }
            isDebuggable && (debugState == "settings" || debugState == "settings_main") -> {
                debugSettingsState = dev.glide.app.ui.settings.SettingsUiState(
                    settings = GlideSettings(deviceName = "Pixel 10 Pro"),
                    storageSummary = dev.glide.core.ports.StorageSummary(totalFiles = 0, totalBytes = 0L, activeFiles = 0)
                )
                GlideRoute.Settings to null
            }
            isDebuggable && debugState == "settings_storage_uninstall" -> {
                debugSettingsState = dev.glide.app.ui.settings.SettingsUiState(
                    settings = GlideSettings(deviceName = "Pixel 10 Pro"),
                    storageSummary = dev.glide.core.ports.StorageSummary(totalFiles = 4, totalBytes = 68_400_000L, activeFiles = 1)
                )
                GlideRoute.Settings to null
            }
            isDebuggable && debugState == "settings_diagnostics_licenses" -> {
                debugSettingsState = dev.glide.app.ui.settings.SettingsUiState(
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
                GlideRoute.Settings to null
            }
            isDebuggable && debugState == "share_test_single" -> {
                val cacheShareDir = File(cacheDir, "shares").apply { if (!exists()) mkdirs() }
                val testFile = File(cacheShareDir, "sample_share.pdf").apply {
                    if (!exists()) writeText("%PDF-1.4 sample shared document content")
                }
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", testFile)
                GlideRoute.Selection(listOf(uri.toString())) to null
            }
            isDebuggable && debugState == "share_test_multiple" -> {
                val cacheShareDir = File(cacheDir, "shares").apply { if (!exists()) mkdirs() }
                val file1 = File(cacheShareDir, "vacation_1.jpg").apply { if (!exists()) writeText("JPEG DATA 1") }
                val file2 = File(cacheShareDir, "vacation_2.jpg").apply { if (!exists()) writeText("JPEG DATA 2") }
                val uri1 = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file1)
                val uri2 = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file2)
                GlideRoute.Selection(listOf(uri1.toString(), uri2.toString())) to null
            }
            isDebuggable && debugState == "share_test_busy" -> {
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
                GlideRoute.Transfer("active-transfer-session") to state
            }
            isDebuggable && debugState == "service_transfer_active" -> {
                val sid = "fgs-live-session"
                dev.glide.app.service.TransferService.startService(this, sid, "Pixel 9 Pro")
                GlideRoute.Transfer(sid) to TransferUiState.Transferring(
                    sessionId = sid,
                    role = DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemName = "system_backup.tar.gz",
                    transferredBytes = 32000000L,
                    totalBytes = 85000000L,
                    progressFraction = 0.38f,
                    speedBytesPerSec = 7500000L
                )
            }
            isDebuggable && debugState == "service_notification_redacted" -> {
                val sid = "fgs-redacted-session"
                kotlinx.coroutines.runBlocking {
                    dev.glide.app.di.DatabaseModule.provideSettingsStore(applicationContext).updateNotificationDetails(false)
                }
                dev.glide.app.service.TransferService.startService(this, sid, "Pixel 9 Pro")
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
                GlideRoute.Transfer(sid) to state
            }
            isDebuggable && debugState == "service_notification_detailed" -> {
                val sid = "fgs-detailed-session"
                kotlinx.coroutines.runBlocking {
                    dev.glide.app.di.DatabaseModule.provideSettingsStore(applicationContext).updateNotificationDetails(true)
                }
                dev.glide.app.service.TransferService.startService(this, sid, "Pixel 9 Pro")
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
                GlideRoute.Transfer(sid) to state
            }
            isDebuggable && debugState == "service_retry_interrupted" -> {
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
                GlideRoute.Transfer(sid) to state
            }
            isDebuggable && debugState == "service_retry_unconfirmed" -> {
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
                GlideRoute.Transfer(sid) to state
            }
            isDebuggable && debugState == "service_reconciliation_audit" -> {
                val sid = java.util.UUID.randomUUID().toString()
                val completedItemId = java.util.UUID.randomUUID().toString()
                val interruptedItemId = java.util.UUID.randomUUID().toString()
                val db = dev.glide.app.di.DatabaseModule.provideGlideDatabase(applicationContext)
                val receiveStore = dev.glide.app.di.DatabaseModule.provideReceiveStore(
                    applicationContext,
                    db.receiveJournalDao(),
                    db.receivedFileDao()
                )
                val reconciler = dev.glide.app.di.DatabaseModule.provideProcessDeathReconciler(
                    receiveStore,
                    db.sessionDao(),
                    db.itemDao(),
                    db.receivedFileDao()
                )

                kotlinx.coroutines.runBlocking {
                    // Seed uncompleted session
                    db.sessionDao().insert(
                        dev.glide.data.db.SessionEntity(
                            sessionId = sid,
                            batchId = java.util.UUID.randomUUID().toString(),
                            role = "Receiver",
                            peerLabel = "Pixel 8 Pro",
                            startedAtMs = System.currentTimeMillis() - 60000L
                        )
                    )
                    // Item 1: completed and verified
                    val payload1 = "Verified document survived crash".toByteArray()
                    val key1 = java.util.UUID.randomUUID().toString()
                    java.io.File(receiveStore.receivedDir, key1).writeBytes(payload1)
                    db.receivedFileDao().insertOrUpdate(
                        dev.glide.data.db.ReceivedFileEntity(
                            fileKey = key1,
                            originatingItemId = completedItemId,
                            displayName = "financial_report.pdf",
                            mime = "application/pdf",
                            length = payload1.size.toLong(),
                            digest = dev.glide.core.domain.Sha256Digest.compute(payload1).value,
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
                            digest = dev.glide.core.domain.Sha256Digest.compute(payload1).value,
                            outcome = "Completed"
                        )
                    )
                    // Item 2: interrupted in-flight
                    val partFile = java.io.File(receiveStore.stagingDir, "$interruptedItemId.part")
                    partFile.writeBytes("corrupted partial stream".toByteArray())
                    db.receiveJournalDao().insertOrUpdate(
                        dev.glide.data.db.ReceiveJournalEntity(
                            itemId = interruptedItemId,
                            sessionId = sid,
                            stagingKey = "$interruptedItemId.part",
                            finalKey = java.util.UUID.randomUUID().toString(),
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

                    // Execute recovery
                    val summary = reconciler.reconcile()
                    android.util.Log.i("GlideAudit", "Reconciliation summary: $summary")
                }
                GlideRoute.History(sid) to null
            }
            isDebuggable && debugState == "service_maintenance_audit" -> {
                val db = dev.glide.app.di.DatabaseModule.provideGlideDatabase(applicationContext)
                val receiveStore = dev.glide.app.di.DatabaseModule.provideReceiveStore(
                    applicationContext,
                    db.receiveJournalDao(),
                    db.receivedFileDao()
                )
                val historyStore = dev.glide.app.di.DatabaseModule.provideHistoryStore(
                    db.sessionDao(),
                    db.itemDao(),
                    db.receivedFileDao()
                )
                val cleaner = dev.glide.app.di.DatabaseModule.provideOrphanMaintenanceCleaner(
                    applicationContext,
                    receiveStore,
                    db.receiveJournalDao(),
                    db.receivedFileDao(),
                    historyStore
                )

                kotlinx.coroutines.runBlocking {
                    val now = System.currentTimeMillis()
                    val cutoff25h = now - (25L * 60L * 60L * 1000L)
                    val cutoff1h = now - (1L * 60L * 60L * 1000L)

                    // 1. Seed receiving/ with old orphan .part, recent .part, and active .part
                    val receivingDir = receiveStore.stagingDir.apply { if (!exists()) mkdirs() }
                    File(receivingDir, "old_orphan.part").apply {
                        writeText("old orphan partial")
                        setLastModified(cutoff25h)
                    }
                    File(receivingDir, "recent_orphan.part").apply {
                        writeText("recent orphan partial")
                        setLastModified(cutoff1h)
                    }
                    val activeSessionId = java.util.UUID.randomUUID().toString()
                    val activeItemId = java.util.UUID.randomUUID().toString()
                    File(receivingDir, "$activeItemId.part").apply {
                        writeText("active session partial")
                        setLastModified(cutoff25h)
                    }
                    db.receiveJournalDao().insertOrUpdate(
                        dev.glide.data.db.ReceiveJournalEntity(
                            itemId = activeItemId,
                            sessionId = activeSessionId,
                            stagingKey = "$activeItemId.part",
                            finalKey = java.util.UUID.randomUUID().toString(),
                            displayName = "active_transfer.mp4",
                            mimeType = "video/mp4",
                            expectedBytes = 1000L,
                            phase = dev.glide.data.db.JournalPhases.WRITING,
                            updatedAtMs = cutoff25h
                        )
                    )

                    // 2. Seed staging_sources/ with old staged source and recent staged source
                    val stagingSourcesDir = File(cacheDir, "staging_sources").apply { if (!exists()) mkdirs() }
                    File(stagingSourcesDir, "src_stage_old.staged").apply {
                        writeText("old staged source content")
                        setLastModified(cutoff25h)
                    }
                    File(stagingSourcesDir, "src_stage_recent.staged").apply {
                        writeText("recent staged source content")
                        setLastModified(cutoff1h)
                    }

                    // 3. Seed received_files with missing disk file
                    val missingFileKey = java.util.UUID.randomUUID().toString()
                    db.receivedFileDao().insertOrUpdate(
                        dev.glide.data.db.ReceivedFileEntity(
                            fileKey = missingFileKey,
                            originatingItemId = java.util.UUID.randomUUID().toString(),
                            displayName = "ghost_file.pdf",
                            mime = "application/pdf",
                            length = 5000L,
                            digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            receivedAtMs = cutoff25h
                        )
                    )

                    // 4. Seed old terminal history session (> 90 days)
                    val oldSessionId = java.util.UUID.randomUUID().toString()
                    val oldStartedAt = now - (95L * 24L * 60L * 60L * 1000L)
                    db.sessionDao().insert(
                        dev.glide.data.db.SessionEntity(
                            sessionId = oldSessionId,
                            batchId = java.util.UUID.randomUUID().toString(),
                            role = "Receiver",
                            peerLabel = "Ancient Phone",
                            startedAtMs = oldStartedAt,
                            endedAtMs = oldStartedAt + 5000L,
                            outcome = "Completed"
                        )
                    )

                    // 5. Run maintenance with activeSessionSupplier pointing to activeSessionId
                    val result = cleaner.runMaintenance(
                        activeSessionSupplier = { activeSessionId }
                    )
                    android.util.Log.i("GlideMaintenanceAudit", "Maintenance result: $result")
                }
                GlideRoute.History() to null
            }
            isDebuggable && (debugState == "theme_light" || debugState == "theme_dark" || debugState == "theme_dynamic") -> {
                GlideRoute.Home to null
            }
            isDebuggable && debugState == "theme_settings" -> {
                GlideRoute.Settings to null
            }
            isDebuggable && debugState == "home_active_transfer" -> {
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
                GlideRoute.Home to null
            }
            isDebuggable && debugState == "discovery_searching" -> {
                debugDiscoveryState = dev.glide.app.ui.discovery.DiscoveryUiState.Searching(peers = emptyList(), secondsElapsed = 8)
                GlideRoute.Discovery() to null
            }
            isDebuggable && debugState == "discovery_peers" -> {
                val p1 = dev.glide.app.ui.discovery.DiscoveredPeer(
                    id = dev.glide.core.domain.PeerId("peer-pixel-9"),
                    displayName = "Pixel 9 Pro",
                    deviceType = dev.glide.app.ui.discovery.DeviceType.Phone,
                    isAvailable = true,
                    isSelected = true
                )
                val p2 = dev.glide.app.ui.discovery.DiscoveredPeer(
                    id = dev.glide.core.domain.PeerId("peer-galaxy-tab"),
                    displayName = "Galaxy Tab S9",
                    deviceType = dev.glide.app.ui.discovery.DeviceType.Tablet,
                    isAvailable = true
                )
                val p3 = dev.glide.app.ui.discovery.DiscoveredPeer(
                    id = dev.glide.core.domain.PeerId("peer-macbook"),
                    displayName = "MacBook Air",
                    deviceType = dev.glide.app.ui.discovery.DeviceType.Computer,
                    isAvailable = true
                )
                debugDiscoveryState = dev.glide.app.ui.discovery.DiscoveryUiState.Searching(
                    peers = listOf(p1, p2, p3),
                    secondsElapsed = 14,
                    selectedPeerId = p1.id
                )
                GlideRoute.Discovery() to null
            }
            isDebuggable && debugState == "discovery_blocked" -> {
                debugDiscoveryState = dev.glide.app.ui.discovery.DiscoveryUiState.Blocked(
                    dev.glide.app.ui.discovery.BlockedReason.BluetoothDisabled
                )
                GlideRoute.Discovery() to null
            }
            isDebuggable && debugState == "discovery_timeout" -> {
                debugDiscoveryState = dev.glide.app.ui.discovery.DiscoveryUiState.TimedOut()
                GlideRoute.Discovery() to null
            }
            isDebuggable && debugState == "receive_readiness" -> {
                debugReceiveState = dev.glide.app.ui.discovery.ReceiveReadinessUiState.Advertising(
                    localDeviceName = "Pixel 10 Pro",
                    secondsRemaining = 114
                )
                GlideRoute.ReceiveReadiness to null
            }
            isDebuggable && debugState == "verification_comparing" -> {
                debugPairingState = dev.glide.app.ui.pairing.PairingUiState.Verifying(
                    peerId = dev.glide.core.domain.PeerId("peer-pixel-9"),
                    peerName = "Pixel 9 Pro",
                    authDigits = "482916",
                    secondsRemaining = 54,
                    localConfirmed = false,
                    localDeviceName = "Pixel 10 Pro",
                    isSameNamePeer = false,
                    deviceType = dev.glide.app.ui.discovery.DeviceType.Phone
                )
                GlideRoute.Transfer("verif-session-1") to null
            }
            isDebuggable && debugState == "verification_same_name" -> {
                debugPairingState = dev.glide.app.ui.pairing.PairingUiState.Verifying(
                    peerId = dev.glide.core.domain.PeerId("ep-f8a2-same"),
                    peerName = "Pixel 10 Pro",
                    authDigits = "730514",
                    secondsRemaining = 48,
                    localConfirmed = false,
                    localDeviceName = "Pixel 10 Pro",
                    isSameNamePeer = true,
                    deviceType = dev.glide.app.ui.discovery.DeviceType.Phone
                )
                GlideRoute.Transfer("verif-session-2") to null
            }
            isDebuggable && debugState == "verification_waiting_peer" -> {
                debugPairingState = dev.glide.app.ui.pairing.PairingUiState.Verifying(
                    peerId = dev.glide.core.domain.PeerId("peer-macbook"),
                    peerName = "MacBook Air",
                    authDigits = "8914",
                    secondsRemaining = 38,
                    localConfirmed = true,
                    localDeviceName = "Pixel 10 Pro",
                    isSameNamePeer = false,
                    deviceType = dev.glide.app.ui.discovery.DeviceType.Computer
                )
                GlideRoute.Transfer("verif-session-3") to null
            }
            isDebuggable && debugState == "offer_incoming_single" -> {
                val singleItem = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000001"),
                    name = "holiday_photos_archive.zip",
                    mimeType = "application/zip",
                    sizeBytes = 4_500_000L
                )
                val state = dev.glide.app.ui.transfer.TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-1",
                    role = dev.glide.core.domain.DeviceRole.Receiver,
                    peerName = "Pixel 9 Pro",
                    itemCount = 1,
                    totalBytes = 4_500_000L,
                    firstItemName = singleItem.name,
                    items = listOf(singleItem),
                    secondsRemaining = 58,
                    storageAvailableBytes = 45_000_000_000L,
                    storageRequiredBytes = 4_500_000L,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("offer-session-1") to state
            }
            isDebuggable && debugState == "offer_incoming_multi" -> {
                val item1 = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000002"),
                    name = "quarterly_financial_report_final_v3_reviewed.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 12_400_000L
                )
                val item2 = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000003"),
                    name = "project_roadmap_and_milestones_presentation.pptx",
                    mimeType = "application/vnd.ms-powerpoint",
                    sizeBytes = 18_100_000L
                )
                val item3 = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000004"),
                    name = "camera_demo_recording_4k_sample.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 12_000_000L
                )
                val state = dev.glide.app.ui.transfer.TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-2",
                    role = dev.glide.core.domain.DeviceRole.Receiver,
                    peerName = "Galaxy Tab S9",
                    itemCount = 3,
                    totalBytes = 42_500_000L,
                    firstItemName = item1.name,
                    items = listOf(item1, item2, item3),
                    secondsRemaining = 52,
                    storageAvailableBytes = 38_000_000_000L,
                    storageRequiredBytes = 42_500_000L,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("offer-session-2") to state
            }
            isDebuggable && debugState == "offer_insufficient_storage" -> {
                val largeItem = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000005"),
                    name = "developer_disk_image_installer_full.iso",
                    mimeType = "application/octet-stream",
                    sizeBytes = 25_000_000_000L
                )
                val state = dev.glide.app.ui.transfer.TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-3",
                    role = dev.glide.core.domain.DeviceRole.Receiver,
                    peerName = "MacBook Air",
                    itemCount = 1,
                    totalBytes = 25_000_000_000L,
                    firstItemName = largeItem.name,
                    items = listOf(largeItem),
                    secondsRemaining = 45,
                    storageAvailableBytes = 10_000_000_000L,
                    storageRequiredBytes = 25_000_000_000L,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Computer
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("offer-session-3") to state
            }
            isDebuggable && debugState == "offer_sender_waiting" -> {
                val state = dev.glide.app.ui.transfer.TransferUiState.AwaitingConsent(
                    sessionId = "offer-session-4",
                    role = dev.glide.core.domain.DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemCount = 2,
                    totalBytes = 18_400_000L,
                    firstItemName = "dataset_part1.csv",
                    items = listOf(
                        dev.glide.core.domain.TransferItem(
                            id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000006"),
                            name = "dataset_part1.csv",
                            mimeType = "text/csv",
                            sizeBytes = 9_200_000L
                        ),
                        dev.glide.core.domain.TransferItem(
                            id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000007"),
                            name = "dataset_part2.csv",
                            mimeType = "text/csv",
                            sizeBytes = 9_200_000L
                        )
                    ),
                    secondsRemaining = 50,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("offer-session-4") to state
            }
            isDebuggable && debugState == "transfer_active_single" -> {
                val state = dev.glide.app.ui.transfer.TransferUiState.Transferring(
                    sessionId = "active-single-session",
                    role = dev.glide.core.domain.DeviceRole.Sender,
                    peerName = "Pixel 9 Pro",
                    itemName = "project_presentation_deck.key",
                    transferredBytes = 18_000_000L,
                    totalBytes = 40_000_000L,
                    progressFraction = 0.45f,
                    speedBytesPerSec = 14_200_000L,
                    itemIndex = 1,
                    itemCount = 1,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("active-single-session") to state
            }
            isDebuggable && debugState == "transfer_active_multi" -> {
                val state = dev.glide.app.ui.transfer.TransferUiState.Transferring(
                    sessionId = "active-multi-session",
                    role = dev.glide.core.domain.DeviceRole.Receiver,
                    peerName = "Galaxy Tab S9",
                    itemName = "brand_assets_vector.zip",
                    transferredBytes = 62_000_000L,
                    totalBytes = 100_000_000L,
                    progressFraction = 0.62f,
                    speedBytesPerSec = 18_500_000L,
                    itemIndex = 2,
                    itemCount = 4,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("active-multi-session") to state
            }
            isDebuggable && debugState == "transfer_verifying" -> {
                val state = dev.glide.app.ui.transfer.TransferUiState.Verifying(
                    sessionId = "verifying-session",
                    role = dev.glide.core.domain.DeviceRole.Receiver,
                    peerName = "Galaxy Tab S9",
                    itemName = "brand_assets_vector.zip",
                    itemIndex = 2,
                    itemCount = 4,
                    totalBytes = 100_000_000L,
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("verifying-session") to state
            }
            isDebuggable && debugState == "transfer_completed_receiver" -> {
                val receivedDir = File(filesDir, "received").apply { if (!exists()) mkdirs() }
                val sampleFile = File(receivedDir, "project_presentation_deck.key").apply {
                    if (!exists()) writeText("Simulated verified received presentation deck content.")
                }
                val itemA = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000010"),
                    name = "project_presentation_deck.key",
                    mimeType = "application/octet-stream",
                    sizeBytes = 40_000_000L
                )
                val itemB = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000011"),
                    name = "brand_assets_vector.zip",
                    mimeType = "application/zip",
                    sizeBytes = 100_000_000L
                )
                val state = dev.glide.app.ui.transfer.TransferUiState.Completed(
                    sessionId = "completed-receiver-session",
                    role = dev.glide.core.domain.DeviceRole.Receiver,
                    peerName = "Pixel 9 Pro",
                    totalBytes = 140_000_000L,
                    itemName = "project_presentation_deck.key",
                    committedFileKey = sampleFile.name,
                    itemCount = 2,
                    items = listOf(itemA, itemB),
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Phone
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("completed-receiver-session") to state
            }
            isDebuggable && debugState == "transfer_completed_sender" -> {
                val item = dev.glide.core.domain.TransferItem(
                    id = dev.glide.core.domain.ItemId("00000000-0000-4000-8000-000000000012"),
                    name = "contract_agreement_signed.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 2_400_000L
                )
                val state = dev.glide.app.ui.transfer.TransferUiState.Completed(
                    sessionId = "completed-sender-session",
                    role = dev.glide.core.domain.DeviceRole.Sender,
                    peerName = "MacBook Air",
                    totalBytes = 2_400_000L,
                    itemName = item.name,
                    itemCount = 1,
                    items = listOf(item),
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Computer
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("completed-sender-session") to state
            }
            isDebuggable && debugState == "transfer_interrupted_partial" -> {
                val state = dev.glide.app.ui.transfer.TransferUiState.Failed(
                    sessionId = "interrupted-partial-session",
                    role = dev.glide.core.domain.DeviceRole.Sender,
                    message = "Connection to peer lost during transfer.",
                    code = "TRANSFER_INTERRUPTED",
                    isRecoverable = true,
                    isInterrupted = true,
                    isReceiptUnconfirmed = false,
                    unfinishedCount = 2,
                    totalCount = 4,
                    canRetry = true,
                    peerName = "Galaxy Tab S9",
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Tablet
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("interrupted-partial-session") to state
            }
            isDebuggable && debugState == "transfer_receipt_unconfirmed" -> {
                val state = dev.glide.app.ui.transfer.TransferUiState.Failed(
                    sessionId = "receipt-unconfirmed-session",
                    role = dev.glide.core.domain.DeviceRole.Sender,
                    message = "Connection lost while awaiting receiver confirmation.",
                    code = "DELIVERY_UNCONFIRMED",
                    isRecoverable = true,
                    isInterrupted = true,
                    isReceiptUnconfirmed = true,
                    unfinishedCount = 1,
                    totalCount = 2,
                    canRetry = true,
                    peerName = "MacBook Air",
                    peerDeviceType = dev.glide.app.ui.discovery.DeviceType.Computer
                )
                transferRuntime.setTransferStateForTesting(state)
                GlideRoute.Transfer("receipt-unconfirmed-session") to state
            }
            isDebuggable && debugState == "motion_reduced_discovery" -> {
                GlideRoute.Discovery() to null
            }
            isDebuggable && debugState == "motion_reduced_receive" -> {
                GlideRoute.ReceiveReadiness to null
            }
            isDebuggable && debugState == "motion_reduced_settings" -> {
                GlideRoute.Settings to null
            }
            else -> null to null
        }

        // 2. Evaluate cold-start share intent or notification session if no debug override active
        var initialDialogState: IngressDialogState? = null
        val initialRoute = debugRoute ?: run {
            val outcome = ShareIngressHandler.handleLaunchIntent(
                intent = intent,
                isRecreating = savedInstanceState != null,
                isSessionActive = transferRuntime.isSessionActive.value,
                activeSessionId = transferRuntime.activeSessionId.value
            )
            when (outcome) {
                is IngressOutcome.Route -> {
                    ShareIngressHandler.tryTakePersistableUriPermissions(contentResolver, outcome.urisToPersist)
                    outcome.route
                }
                is IngressOutcome.ActiveSessionBusy -> {
                    initialDialogState = IngressDialogState(outcome.dialogTitle, outcome.dialogMessage)
                    val sid = outcome.activeSessionId ?: "active"
                    GlideRoute.Transfer(sid)
                }
                is IngressOutcome.ShowDialog -> {
                    initialDialogState = IngressDialogState(outcome.title, outcome.message)
                    GlideRoute.Home
                }
                is IngressOutcome.NoOp -> {
                    GlideRoute.Home
                }
            }
        }

        setContent {
            val settings by (if (::settingsStore.isInitialized) settingsStore.settingsFlow else flowOf(GlideSettings()))
                .collectAsStateWithLifecycle(initialValue = GlideSettings())

            val context = androidx.compose.ui.platform.LocalContext.current
            val composeHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val glideHaptics = remember(context, composeHaptic) {
                dev.glide.app.ui.haptics.AndroidGlideHaptics(context, composeHaptic)
            }

            val darkTheme = when {
                isDebuggable && (debugTheme == "dark" || debugState == "theme_dark") -> true
                isDebuggable && (debugTheme == "light" || debugState == "theme_light") -> false
                else -> when (settings.theme) {
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                }
            }

            val dynamicColor = when {
                isDebuggable && (debugTheme == "dynamic" || debugState == "theme_dynamic") -> true
                isDebuggable && debugTheme == "nodynamic" -> false
                else -> settings.dynamicColor
            }

            val reducedMotion = when {
                isDebuggable && debugState?.startsWith("motion_reduced") == true -> true
                isDebuggable && debugState == "motion_normal" -> false
                else -> dev.glide.app.ui.motion.ReducedMotionResolver.isReducedMotionActive(
                    settings.reducedMotion,
                    context
                )
            }

            val postureInfo = remember(debugPosture) {
                when {
                    isDebuggable && debugPosture == "book" -> dev.glide.designsystem.DevicePostureInfo(
                        posture = dev.glide.designsystem.DevicePosture.Book,
                        hinge = dev.glide.designsystem.HingeBounds(
                            leftDp = 415.dp,
                            topDp = 0.dp,
                            rightDp = 435.dp,
                            bottomDp = 900.dp,
                            isOccluding = true,
                            isSeparating = true,
                            orientation = dev.glide.designsystem.HingeOrientation.Vertical
                        )
                    )
                    isDebuggable && debugPosture == "tabletop" -> dev.glide.designsystem.DevicePostureInfo(
                        posture = dev.glide.designsystem.DevicePosture.Tabletop,
                        hinge = dev.glide.designsystem.HingeBounds(
                            leftDp = 0.dp,
                            topDp = 440.dp,
                            rightDp = 800.dp,
                            bottomDp = 460.dp,
                            isOccluding = true,
                            isSeparating = true,
                            orientation = dev.glide.designsystem.HingeOrientation.Horizontal
                        )
                    )
                    isDebuggable && debugPosture == "flat" -> dev.glide.designsystem.DevicePostureInfo(
                        posture = dev.glide.designsystem.DevicePosture.Flat,
                        hinge = null
                    )
                    else -> dev.glide.designsystem.DevicePostureInfo(
                        posture = dev.glide.designsystem.DevicePosture.Normal,
                        hinge = null
                    )
                }
            }

            GlideTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
                reducedMotion = reducedMotion,
                haptics = glideHaptics
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.glide.designsystem.LocalDevicePosture provides postureInfo
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                    val latestOutcome by incomingOutcomeFlow.collectAsStateWithLifecycle()
                    var incomingRoute by remember { mutableStateOf<GlideRoute?>(null) }
                    var ingressDialog by remember { mutableStateOf<IngressDialogState?>(initialDialogState) }

                    LaunchedEffect(latestOutcome) {
                        when (val outcome = latestOutcome) {
                            is IngressOutcome.Route -> {
                                ShareIngressHandler.tryTakePersistableUriPermissions(contentResolver, outcome.urisToPersist)
                                incomingRoute = outcome.route
                            }
                            is IngressOutcome.ActiveSessionBusy -> {
                                val sid = outcome.activeSessionId ?: "active"
                                incomingRoute = GlideRoute.Transfer(sid)
                                ingressDialog = IngressDialogState(outcome.dialogTitle, outcome.dialogMessage)
                            }
                            is IngressOutcome.ShowDialog -> {
                                ingressDialog = IngressDialogState(outcome.title, outcome.message)
                            }
                            is IngressOutcome.NoOp, null -> {
                                // No action
                            }
                        }
                    }

                    GlideAppNavDisplay(
                        transferRuntime = transferRuntime,
                        initialRoute = initialRoute,
                        incomingRoute = incomingRoute,
                        initialUiState = debugUiState,
                        initialPairingState = debugPairingState,
                        initialDiscoveryState = debugDiscoveryState,
                        initialReceiveState = debugReceiveState,
                        initialHistoryState = debugHistoryState,
                        initialSettingsState = debugSettingsState
                    )

                    ingressDialog?.let { dialog ->
                        AlertDialog(
                            onDismissRequest = { ingressDialog = null },
                            title = { Text(text = dialog.title) },
                            text = { Text(text = dialog.message) },
                            confirmButton = {
                                Button(onClick = { ingressDialog = null }) {
                                    Text("OK")
                                }
                            }
                        )
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val (outcome, fingerprint) = ShareIngressHandler.handleNewIntent(
            intent = intent,
            isSessionActive = transferRuntime.isSessionActive.value,
            activeSessionId = transferRuntime.activeSessionId.value,
            lastHandledFingerprint = lastHandledFingerprint
        )
        lastHandledFingerprint = fingerprint
        if (outcome !is IngressOutcome.NoOp) {
            incomingOutcomeFlow.value = outcome
        }
    }
}
