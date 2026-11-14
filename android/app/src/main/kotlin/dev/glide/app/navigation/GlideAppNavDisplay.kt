package dev.glide.app.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.glide.app.ui.HomeScreen
import dev.glide.app.ui.TransferScreen
import dev.glide.app.ui.selection.SelectionScreen
import dev.glide.app.ui.transfer.VerifiedItemUiState
import dev.glide.app.service.TransferRuntime
import dev.glide.app.permission.PermissionPolicy
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.glide.designsystem.GlideMotion
import dev.glide.designsystem.LocalReducedMotion
import dev.glide.designsystem.rememberWindowSizeClass
import dev.glide.app.navigation.adaptive.GlideAdaptiveScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlideAppNavDisplay(
    modifier: Modifier = Modifier,
    transferRuntime: TransferRuntime? = null,
    persistentTrustManager: dev.glide.core.identity.PersistentTrustManager? = null,
    initialRoute: GlideRoute = GlideRoute.Home,
    incomingRoute: GlideRoute? = null,
    initialUiState: dev.glide.app.ui.transfer.TransferUiState? = null,
    initialPairingState: dev.glide.app.ui.pairing.PairingUiState? = null,
    initialDiscoveryState: dev.glide.app.ui.discovery.DiscoveryUiState? = null,
    initialReceiveState: dev.glide.app.ui.discovery.ReceiveReadinessUiState? = null,
    customHistoryStore: dev.glide.core.ports.HistoryStore? = null,
    customSettingsStore: dev.glide.core.ports.SettingsStore? = null,
    customFileManager: dev.glide.core.ports.PrivateFileManager? = null,
    customTrustedPeerDao: dev.glide.data.db.TrustedPeerDao? = null,
    initialHistoryState: dev.glide.app.ui.history.HistoryUiState? = null,
    initialSettingsState: dev.glide.app.ui.settings.SettingsUiState? = null
) {
    val backStack = rememberNavBackStack(initialRoute)
    val isReducedMotion = LocalReducedMotion.current
    val windowSizeClass = rememberWindowSizeClass()
    val currentRoute = backStack.filterIsInstance<GlideRoute>().lastOrNull() ?: initialRoute

    // Route an incoming offer once, regardless of the page showing when the
    // authenticated desktop connects. Later manual navigation is respected.
    if (transferRuntime != null) {
        val incomingState by transferRuntime.transferState.collectAsStateWithLifecycle()
        var lastIncomingRoute by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        androidx.compose.runtime.LaunchedEffect(incomingState) {
            if (incomingState is dev.glide.app.ui.transfer.TransferUiState.AwaitingConsent &&
                incomingState.role == dev.glide.core.domain.DeviceRole.Receiver &&
                incomingState.sessionId != lastIncomingRoute) {
                lastIncomingRoute = incomingState.sessionId
                if (backStack.lastOrNull() !is GlideRoute.Transfer) {
                    backStack.add(GlideRoute.Transfer(incomingState.sessionId))
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(incomingRoute) {
        if (incomingRoute != null && incomingRoute != backStack.last()) {
            backStack.add(incomingRoute)
        }
    }

    GlideAdaptiveScaffold(
        windowSizeClass = windowSizeClass,
        currentRoute = currentRoute,
        onNavigateTopLevel = { targetRoute ->
            // Clear to the initial route then navigate to target (tab-style)
            while (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
            if (targetRoute !is GlideRoute.Home) {
                backStack.add(targetRoute)
            }
        },
        modifier = modifier
    ) { _ ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }
            },
            transitionSpec = {
                if (isReducedMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { (it * 0.15f).toInt() },
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.EnterEasing)
                    ) + fadeIn(
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.EnterEasing)
                    )) togetherWith (slideOutHorizontally(
                        targetOffsetX = { (-it * 0.15f).toInt() },
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.ExitEasing)
                    ) + fadeOut(
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.ExitEasing)
                    ))
                }
            },
            popTransitionSpec = {
                if (isReducedMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { (-it * 0.15f).toInt() },
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.EnterEasing)
                    ) + fadeIn(
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.EnterEasing)
                    )) togetherWith (slideOutHorizontally(
                        targetOffsetX = { (it * 0.15f).toInt() },
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.ExitEasing)
                    ) + fadeOut(
                        animationSpec = tween(GlideMotion.NavigationDurationMs, easing = GlideMotion.ExitEasing)
                    ))
                }
            },

        entryProvider = entryProvider {
            entry<GlideRoute.Home> {
                val context = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.runtime.LaunchedEffect(transferRuntime) {
                    transferRuntime?.refreshPersistentTrustState()
                }
                val pairedDevicesFlow = androidx.compose.runtime.remember(transferRuntime, customTrustedPeerDao, context) {
                    if (transferRuntime != null) {
                        transferRuntime.trustState.map { it.trustedPeers }
                    } else {
                        val dao = customTrustedPeerDao ?: dev.glide.app.di.DatabaseModule.provideGlideDatabase(context).trustedPeerDao()
                        dao.observeAllPeers().map { peers ->
                            peers.map { peer ->
                                dev.glide.core.domain.TrustedPeerRecord(
                                    peerFingerprintHex = peer.peerFingerprint.lowercase(),
                                    publicKeyDerHex = peer.publicKeyDer.lowercase(),
                                    displayName = peer.displayName,
                                    pairedAtMs = peer.pairedAtMs,
                                    lastSeenAtMs = peer.lastSeenAtMs
                                )
                            }
                        }
                    }
                }
                val pairedDevices by pairedDevicesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                val historyStore = androidx.compose.runtime.remember(customHistoryStore, context) {
                    customHistoryStore ?: run {
                        val database = dev.glide.app.di.DatabaseModule.provideGlideDatabase(context)
                        dev.glide.data.store.RoomHistoryStore(
                            sessionDao = database.sessionDao(),
                            itemDao = database.itemDao(),
                            receivedFileDao = database.receivedFileDao()
                        )
                    }
                }
                val recentSessions by historyStore.observeSessions(limit = 5)
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val activeSessionId = if (transferRuntime != null) {
                    val isSessionActive by transferRuntime.isSessionActive.collectAsStateWithLifecycle()
                    val currentActiveSessionId by transferRuntime.activeSessionId.collectAsStateWithLifecycle()
                    currentActiveSessionId.takeIf { isSessionActive }
                } else {
                    null
                }
                HomeScreen(
                    onNavigateToSend = {
                        backStack.add(GlideRoute.Selection())
                    },
                    onNavigateToReceive = {
                        backStack.add(GlideRoute.ReceiveReadiness)
                    },
                    onNavigateToHistory = {
                        backStack.add(GlideRoute.History())
                    },
                    activeSessionId = activeSessionId,
                    onNavigateToTransfer = { sid ->
                        backStack.add(GlideRoute.Transfer(sid))
                    },
                    pairedDevices = pairedDevices,
                    onPairDevice = {
                        backStack.add(GlideRoute.QrScanner(purpose = QrScanPurpose.PAIR_ONLY))
                    },
                    onSelectPairedDevice = { peer ->
                        backStack.add(GlideRoute.Selection())
                    },
                    recentActivities = recentSessions
                        .filter { it.outcome != null }
                        .map { session ->
                        dev.glide.app.ui.RecentActivity(
                            sessionId = session.sessionId.value,
                            role = session.role,
                            peerLabel = session.peerLabel,
                            startedAtMs = session.startedAtMs,
                            outcome = session.outcome,
                            itemCount = session.itemCount,
                            totalBytes = session.totalBytes
                        )
                    },
                    onOpenRecentActivity = { sessionId ->
                        backStack.add(GlideRoute.History(initialSessionId = sessionId))
                    }
                )
            }
            entry<GlideRoute.Selection> { route ->
                SelectionScreen(
                    initialUris = route.initialUris.map { android.net.Uri.parse(it) },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onProceedToSend = { handle ->
                        backStack.add(GlideRoute.Discovery(sourceHandles = listOf(handle.value)))
                    },
                    onProceedToSendBatch = { batch ->
                        val sourceHandles = dev.glide.app.ui.selection.SelectedTransferStore.getSourceMap(batch.id).values.map { it.value }
                        backStack.add(GlideRoute.Discovery(batchId = batch.id.value, sourceHandles = sourceHandles))
                    }
                )
            }
            entry<GlideRoute.Discovery> { route ->
                val viewModel = if (transferRuntime != null) {
                    val runtimeFactory = androidx.compose.runtime.remember(transferRuntime) {
                        object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                return dev.glide.app.ui.discovery.DiscoveryViewModel(transferRuntime) as T
                            }
                        }
                    }
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.discovery.DiscoveryViewModel>(
                        factory = runtimeFactory
                    )
                } else {
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.discovery.DiscoveryViewModel>()
                }

                val context = androidx.compose.ui.platform.LocalContext.current
                val nearbyPermissions = androidx.compose.runtime.remember {
                    PermissionPolicy.nearbyTransferPermissions()
                }
                val hasNearbyPermissions: () -> Boolean = {
                    nearbyPermissions.all { permission ->
                        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    }
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    if (hasNearbyPermissions()) {
                        viewModel.startDiscovery()
                    } else {
                        viewModel.onPermissionDenied()
                    }
                }
                val startDiscoveryAtPointOfUse: () -> Unit = {
                    if (nearbyPermissions.isEmpty() || hasNearbyPermissions()) {
                        viewModel.startDiscovery()
                    } else {
                        permissionLauncher.launch(nearbyPermissions.toTypedArray())
                    }
                }

                androidx.compose.runtime.LaunchedEffect(initialDiscoveryState, transferRuntime) {
                    if (initialDiscoveryState != null) {
                        viewModel.setUiStateForTesting(initialDiscoveryState)
                    } else if (transferRuntime != null) {
                        startDiscoveryAtPointOfUse()
                    }
                }

                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                dev.glide.app.ui.discovery.DiscoveryScreen(
                    uiState = uiState,
                    onBack = {
                        viewModel.stopDiscovery()
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onSelectPeer = { peerId ->
                        val peer = (uiState as? dev.glide.app.ui.discovery.DiscoveryUiState.Searching)
                            ?.peers
                            ?.firstOrNull { it.id == peerId }
                        if (peer != null) {
                            viewModel.selectPeer(peerId) { selectedId ->
                                viewModel.stopDiscovery()
                                dev.glide.app.service.TransferService.startNearbySenderPairing(
                                    context = context,
                                    peerId = selectedId,
                                    peerName = peer.displayName,
                                    selectionId = route.batchId?.let { dev.glide.core.domain.BatchId(it) }
                                )
                                backStack.add(
                                    GlideRoute.Transfer(
                                        sessionId = "nearby-send-${selectedId.value}",
                                        batchId = route.batchId
                                    )
                                )
                            }
                        }
                    },
                    onRetry = startDiscoveryAtPointOfUse,
                    onScanQrClicked = {
                        viewModel.stopDiscovery()
                        backStack.add(GlideRoute.QrScanner(batchId = route.batchId))
                    }
                )
            }
            entry<GlideRoute.ReceiveReadiness> {
                val receiveSettings by (customSettingsStore?.settingsFlow
                    ?: kotlinx.coroutines.flow.flowOf(dev.glide.core.ports.GlideSettings()))
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
                val receiveScope = androidx.compose.runtime.rememberCoroutineScope()
                val viewModel = if (transferRuntime != null) {
                    val runtimeFactory = androidx.compose.runtime.remember(transferRuntime) {
                        object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                return dev.glide.app.ui.discovery.ReceiveReadinessViewModel(transferRuntime) as T
                            }
                        }
                    }
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.discovery.ReceiveReadinessViewModel>(
                        factory = runtimeFactory
                    )
                } else {
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.discovery.ReceiveReadinessViewModel>()
                }
                val qrViewModel = androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.pairing.QrPairingViewModel>()
                var showQrSheet by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

                val context = androidx.compose.ui.platform.LocalContext.current
                val nearbyPermissions = androidx.compose.runtime.remember {
                    PermissionPolicy.nearbyTransferPermissions()
                }
                val hasNearbyPermissions: () -> Boolean = {
                    nearbyPermissions.all { permission ->
                        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    }
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    if (hasNearbyPermissions()) {
                        if (transferRuntime != null) {
                            dev.glide.app.service.TransferService.startNearbyReceiver(
                                context = context,
                                localDeviceName = "Glide Device"
                            )
                        } else {
                            viewModel.startAdvertising("Glide Device")
                        }
                    } else {
                        viewModel.onPermissionDenied()
                    }
                }
                val startAdvertisingAtPointOfUse: () -> Unit = {
                    if (nearbyPermissions.isEmpty() || hasNearbyPermissions()) {
                        if (transferRuntime != null) {
                            dev.glide.app.service.TransferService.startNearbyReceiver(
                                context = context,
                                localDeviceName = "Glide Device"
                            )
                        } else {
                            viewModel.startAdvertising("Glide Device")
                        }
                    } else {
                        permissionLauncher.launch(nearbyPermissions.toTypedArray())
                    }
                }

                val lanPermissions = androidx.compose.runtime.remember {
                    PermissionPolicy.lanTransferPermissions()
                }
                val hasLanPermissions: () -> Boolean = {
                    lanPermissions.all { permission ->
                        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    }
                }
                val lanPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    if (hasLanPermissions() && transferRuntime != null) {
                        qrViewModel.showLiveReceiverStarting()
                        showQrSheet = true
                        dev.glide.app.service.TransferService.startLanReceiver(
                            context = context,
                            localDeviceName = "Glide Device"
                        )
                    } else {
                        qrViewModel.showLiveReceiverError(
                            "Local network permission is required to create a LAN pairing code."
                        )
                        showQrSheet = true
                    }
                }
                val startLanReceiverAtPointOfUse: () -> Unit = {
                    viewModel.stopAdvertising()
                    if (transferRuntime == null || initialReceiveState != null) {
                        qrViewModel.startReceiving(
                            deviceName = "Glide Device",
                            endpoints = listOf(dev.glide.core.pairing.LanEndpoint("192.168.1.42", 8443)),
                            spkiSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        )
                        showQrSheet = true
                    } else if (lanPermissions.isEmpty() || hasLanPermissions()) {
                        qrViewModel.showLiveReceiverStarting()
                        showQrSheet = true
                        dev.glide.app.service.TransferService.startLanReceiver(
                            context = context,
                            localDeviceName = "Glide Device"
                        )
                        if (transferRuntime.isServiceAttached()) {
                            transferRuntime.dispatch(
                                dev.glide.app.service.TransferRuntimeCommand.StartLanReceiver("Glide Device")
                            )
                        }
                    } else {
                        lanPermissionLauncher.launch(lanPermissions.toTypedArray())
                    }
                }

                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val qrState by qrViewModel.receiverState.collectAsStateWithLifecycle()
                val lanReceiverState = if (transferRuntime != null) {
                    val runtimeLanState by transferRuntime.lanReceiverState.collectAsStateWithLifecycle()
                    runtimeLanState
                } else {
                    dev.glide.app.lan.LanReceiverRuntimeState.Idle
                }

                androidx.compose.runtime.LaunchedEffect(initialReceiveState, transferRuntime) {
                    if (initialReceiveState != null) {
                        viewModel.setUiStateForTesting(initialReceiveState)
                    } else if (transferRuntime != null &&
                        lanReceiverState is dev.glide.app.lan.LanReceiverRuntimeState.Idle &&
                        !showQrSheet
                    ) {
                        startAdvertisingAtPointOfUse()
                    }
                }
                val pairingState = if (transferRuntime != null) {
                    val runtimePairingState by transferRuntime.pairingState.collectAsStateWithLifecycle()
                    runtimePairingState
                } else {
                    dev.glide.app.ui.pairing.PairingUiState.Idle
                }

                androidx.compose.runtime.LaunchedEffect(pairingState) {
                    val verifying = pairingState as? dev.glide.app.ui.pairing.PairingUiState.Verifying
                    if (verifying != null && backStack.lastOrNull() !is GlideRoute.Transfer) {
                        backStack.add(
                            GlideRoute.Transfer(
                                sessionId = "nearby-receive-${verifying.peerId.value}"
                            )
                        )
                    }
                }

                androidx.compose.runtime.LaunchedEffect(lanReceiverState) {
                    when (val state = lanReceiverState) {
                        dev.glide.app.lan.LanReceiverRuntimeState.Idle -> Unit
                        dev.glide.app.lan.LanReceiverRuntimeState.Available -> Unit
                        dev.glide.app.lan.LanReceiverRuntimeState.Starting -> {
                            if (showQrSheet) qrViewModel.showLiveReceiverStarting()
                        }
                        is dev.glide.app.lan.LanReceiverRuntimeState.Ready -> {
                            if (showQrSheet) {
                                qrViewModel.showLiveReceiverInvitation(
                                    invitation = state.invitation,
                                    onRevoke = {
                                        transferRuntime?.dispatch(
                                            dev.glide.app.service.TransferRuntimeCommand.RevokeLanReceiverInvitation
                                        )
                                    }
                                )
                            }
                        }
                        is dev.glide.app.lan.LanReceiverRuntimeState.Error -> {
                            if (showQrSheet) {
                                qrViewModel.showLiveReceiverError(
                                    message = state.message,
                                    canRetry = state.canRetry
                                )
                            }
                        }
                        is dev.glide.app.lan.LanReceiverRuntimeState.Connected -> {
                            qrViewModel.clearLiveReceiverPresentation()
                            showQrSheet = false
                            // The global offer observer opens Transfer when consent is needed.
                        }
                    }
                }

                dev.glide.app.ui.discovery.ReceiveReadinessScreen(
                    uiState = uiState,
                    onStopReceiving = {
                        viewModel.stopAdvertising()
                        if (transferRuntime != null) {
                            dev.glide.app.service.TransferService.stopService(context)
                        }
                        qrViewModel.stopReceiving()
                        showQrSheet = false
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onRestartReceiving = startAdvertisingAtPointOfUse,
                    onShowQrCode = startLanReceiverAtPointOfUse,
                    keepReceivingInBackground = receiveSettings.keepReceivingInBackground,
                    onKeepReceivingInBackgroundChanged = if (customSettingsStore == null) null else { enabled ->
                        receiveScope.launch {
                            customSettingsStore.updateKeepReceivingInBackground(enabled)
                        }
                    },
                    onScanPcQr = {
                        viewModel.stopAdvertising()
                        backStack.add(
                            GlideRoute.QrScanner(
                                purpose = QrScanPurpose.RECEIVE_FROM_SENDER
                            )
                        )
                    }
                )

                if (showQrSheet) {
                    dev.glide.app.ui.pairing.QrInvitationSheet(
                        uiState = qrState,
                        onRefresh = startLanReceiverAtPointOfUse,
                        onDismiss = {
                            qrViewModel.stopReceiving()
                            showQrSheet = false
                            startAdvertisingAtPointOfUse()
                        }
                    )
                }
            }
            entry<GlideRoute.Transfer> { route ->
                val viewModel = if (transferRuntime != null) {
                    val runtimeFactory = androidx.compose.runtime.remember(transferRuntime) {
                        object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                return dev.glide.app.ui.transfer.TransferViewModel(transferRuntime) as T
                            }
                        }
                    }
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.transfer.TransferViewModel>(
                        factory = runtimeFactory
                    )
                } else {
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.transfer.TransferViewModel>()
                }
                androidx.compose.runtime.LaunchedEffect(initialUiState) {
                    if (initialUiState != null) {
                        viewModel.setUiStateForTesting(initialUiState)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(initialPairingState) {
                    if (initialPairingState != null) {
                        viewModel.setPairingStateForTesting(initialPairingState)
                    }
                }
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
                val trustState by viewModel.trustState.collectAsStateWithLifecycle()
                val openFileFeedback by viewModel.openFileFeedback.collectAsStateWithLifecycle()
                val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current

                androidx.compose.runtime.LaunchedEffect(route, uiState) {
                    if (uiState is dev.glide.app.ui.transfer.TransferUiState.Completed &&
                        uiState.role == dev.glide.core.domain.DeviceRole.Sender
                    ) {
                        val sourceUris = route.batchId?.let { batchId ->
                            dev.glide.app.ui.selection.SelectedTransferStore.get(
                                dev.glide.core.domain.BatchId(batchId)
                            )?.sourceMap?.values?.map { it.value }
                        }.orEmpty()
                        returnToFileSelectionAfterSend(backStack, route, sourceUris)
                    }
                }
                TransferScreen(
                    route = route,
                    uiState = uiState,
                    pairingState = pairingState,
                    trustState = trustState,
                    openFileFeedback = openFileFeedback,
                    exportStatus = exportStatus,
                    onAcceptConsent = { viewModel.acceptConsent() },
                    onRejectConsent = { viewModel.rejectConsent() },
                    onCancelTransfer = { viewModel.cancelTransfer() },
                    onConfirmMatch = { peerId -> viewModel.confirmPairing(peerId) },
                    onRejectMatch = { peerId -> viewModel.rejectPairing(peerId) },
                    onScanNewCode = {
                        restartQrScanAfterPairingFailure(backStack, route)
                    },
                    onApproveTrust = { viewModel.approvePendingTrust() },
                    onRejectTrust = { viewModel.rejectPendingTrust() },
                    onOpenFile = { viewModel.openReceivedFile(context) },
                    onExportFile = { viewModel.exportReceivedFile(context) },
                    onShareFile = { viewModel.shareAllVerifiedItems(context) },
                    onOpenItem = { item -> viewModel.openVerifiedItem(context, item) },
                    onShareItem = { item -> viewModel.shareVerifiedItem(context, item) },
                    onExportItem = { item -> viewModel.exportVerifiedItem(context, item) },
                    onExportAll = { viewModel.exportAllVerifiedItems(context) },
                    onShareAll = { viewModel.shareAllVerifiedItems(context) },
                    onRetryTransfer = {
                        val remainingUris = viewModel.getUnfinishedSourceUris()
                        if (remainingUris.isNotEmpty()) {
                            backStack.add(GlideRoute.Selection(remainingUris))
                        } else {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        }
                    },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    }
                )
            }
            entry<GlideRoute.History> { route ->
                val context = androidx.compose.ui.platform.LocalContext.current
                val historyStore = androidx.compose.runtime.remember(customHistoryStore, context) {
                    customHistoryStore ?: run {
                        val database = dev.glide.app.di.DatabaseModule.provideGlideDatabase(context)
                        dev.glide.data.store.RoomHistoryStore(
                            sessionDao = database.sessionDao(),
                            itemDao = database.itemDao(),
                            receivedFileDao = database.receivedFileDao()
                        )
                    }
                }
                val factory = androidx.compose.runtime.remember(historyStore) {
                    dev.glide.app.ui.history.HistoryViewModel.provideFactory(historyStore)
                }
                val viewModel: dev.glide.app.ui.history.HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                androidx.compose.runtime.LaunchedEffect(initialHistoryState) {
                    if (initialHistoryState != null) {
                        viewModel.setUiStateForTesting(initialHistoryState)
                    }
                }

                dev.glide.app.ui.history.HistoryScreen(
                    viewModel = viewModel,
                    initialSessionId = route.initialSessionId,
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    }
                )
            }
            entry<GlideRoute.Settings> {
                val context = androidx.compose.ui.platform.LocalContext.current
                val settingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val fileManager = androidx.compose.runtime.remember(customFileManager, context) {
                    customFileManager ?: run {
                        val db = dev.glide.app.di.DatabaseModule.provideGlideDatabase(context)
                        dev.glide.app.di.DatabaseModule.providePrivateFileManager(
                            context = context,
                            receivedFileDao = db.receivedFileDao(),
                            receiveJournalDao = db.receiveJournalDao(),
                            sessionDao = db.sessionDao()
                        )
                    }
                }
                val trustedPeerDao = androidx.compose.runtime.remember(
                    customTrustedPeerDao,
                    context,
                    transferRuntime
                ) {
                    customTrustedPeerDao ?: if (transferRuntime == null) {
                        val db = dev.glide.app.di.DatabaseModule.provideGlideDatabase(context)
                        db.trustedPeerDao()
                    } else {
                        null
                    }
                }
                val (appVersionName, appBuildNumber) = androidx.compose.runtime.remember(context) {
                    try {
                        val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            context.packageManager.getPackageInfo(
                                context.packageName,
                                android.content.pm.PackageManager.PackageInfoFlags.of(0)
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        }
                        val vName = pInfo.versionName ?: "1.0.0"
                        val vCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo).toString()
                        vName to vCode
                    } catch (e: Exception) {
                        "1.0.0" to "1"
                    }
                }
                val factory = androidx.compose.runtime.remember(
                    settingsStore,
                    fileManager,
                    trustedPeerDao,
                    transferRuntime,
                    appVersionName,
                    appBuildNumber
                ) {
                    dev.glide.app.ui.settings.SettingsViewModel.provideFactory(
                        settingsStore = settingsStore,
                        fileManager = fileManager,
                        trustedPeerDao = trustedPeerDao,
                        transferRuntime = transferRuntime,
                        appVersionName = appVersionName,
                        appBuildNumber = appBuildNumber
                    )
                }
                val viewModel: dev.glide.app.ui.settings.SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                androidx.compose.runtime.LaunchedEffect(initialSettingsState) {
                    if (initialSettingsState != null) {
                        viewModel.setUiStateForTesting(initialSettingsState)
                    }
                }

                dev.glide.app.ui.settings.SettingsScreen(
                    viewModel = viewModel,
                    onPairDevice = {
                        backStack.add(GlideRoute.QrScanner(purpose = QrScanPurpose.PAIR_ONLY))
                    },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    }
                )
            }
            entry<GlideRoute.QrScanner> { route ->
                val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.pairing.QrPairingViewModel>()
                val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                val pairOnlyViewModel = if (route.purpose == QrScanPurpose.PAIR_ONLY && persistentTrustManager != null) {
                    val factory = androidx.compose.runtime.remember(context, persistentTrustManager) {
                        dev.glide.app.ui.pairing.PairOnlyViewModel.factory(context, persistentTrustManager)
                    }
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.pairing.PairOnlyViewModel>(factory = factory)
                } else null
                val pairOnlyState = pairOnlyViewModel?.state?.collectAsStateWithLifecycle()?.value
                    ?: dev.glide.app.ui.pairing.PairOnlyUiState.Idle
                val lanPermissions = androidx.compose.runtime.remember {
                    PermissionPolicy.lanTransferPermissions()
                }
                var pendingInvitation by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<dev.glide.core.pairing.LanInvitation?>(null)
                }
                var pendingPairOnly by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<dev.glide.core.pairing.LanPairV2Invitation?>(null)
                }
                val hasLanPermissions: () -> Boolean = {
                    lanPermissions.all { permission ->
                        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    }
                }
                val startLanSender: (dev.glide.core.pairing.LanInvitation) -> Unit = { invitation ->
                    if (transferRuntime != null) {
                        dev.glide.app.service.TransferService.startLanSenderPairing(
                            context = context,
                            invitation = invitation,
                            selectionId = route.batchId?.let { dev.glide.core.domain.BatchId(it) }
                        )
                        backStack.add(
                            GlideRoute.Transfer(
                                sessionId = "send-lan-${invitation.invitationId}",
                                batchId = route.batchId
                            )
                        )
                    } else {
                        viewModel.onConnectionBlocked("Glide could not start pairing. Return home and try again.")
                    }
                }
                val startLanReceiver: (dev.glide.core.pairing.LanInvitation) -> Unit = { invitation ->
                    if (transferRuntime != null) {
                        dev.glide.app.service.TransferService.startLanReceiverPairing(
                            context = context,
                            invitation = invitation
                        )
                        backStack.add(
                            GlideRoute.Transfer(
                                sessionId = "receive-lan-${invitation.invitationId}"
                            )
                        )
                    } else {
                        viewModel.onConnectionBlocked("Glide could not start receiving. Return home and try again.")
                    }
                }
                val lanPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val invitation = pendingInvitation
                    val pairInvitation = pendingPairOnly
                    pendingInvitation = null
                    pendingPairOnly = null
                    if (hasLanPermissions() && invitation != null) {
                        if (route.purpose == QrScanPurpose.RECEIVE_FROM_SENDER) {
                            startLanReceiver(invitation)
                        } else {
                            startLanSender(invitation)
                        }
                    } else if (hasLanPermissions() && pairInvitation != null) {
                        pairOnlyViewModel?.start(pairInvitation)
                    } else if (invitation != null) {
                        viewModel.onConnectionBlocked("Local network permission is needed to connect. Allow it in Settings, then try again.")
                    } else if (pairInvitation != null) {
                        viewModel.onConnectionBlocked("Local network permission is needed to pair. Allow it in Settings, then try again.")
                    }
                }

                androidx.compose.runtime.LaunchedEffect(viewModel, route.scanAttemptId, route.purpose) {
                    viewModel.startScanning(pairOnly = route.purpose == QrScanPurpose.PAIR_ONLY)
                }

                androidx.compose.runtime.LaunchedEffect(viewModel, route.batchId, route.purpose, pairOnlyViewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is dev.glide.app.ui.pairing.QrPairingEvent.PairOnlyConfirmed -> {
                                if (pairOnlyViewModel == null) {
                                    viewModel.onConnectionBlocked("Pairing is unavailable. Restart Glide and try again.")
                                } else if (lanPermissions.isEmpty() || hasLanPermissions()) {
                                    pairOnlyViewModel.start(event.invitation)
                                } else {
                                    pendingPairOnly = event.invitation
                                    lanPermissionLauncher.launch(lanPermissions.toTypedArray())
                                }
                            }
                            is dev.glide.app.ui.pairing.QrPairingEvent.ConnectConfirmed -> {
                                if (lanPermissions.isEmpty() || hasLanPermissions()) {
                                    if (route.purpose == QrScanPurpose.RECEIVE_FROM_SENDER) {
                                        startLanReceiver(event.invitation)
                                    } else {
                                        startLanSender(event.invitation)
                                    }
                                } else {
                                    pendingInvitation = event.invitation
                                    lanPermissionLauncher.launch(lanPermissions.toTypedArray())
                                }
                            }
                            else -> {}
                        }
                    }
                }

                dev.glide.app.ui.pairing.QrScannerScreen(
                    uiState = scannerState,
                    onCameraUnavailable = viewModel::onCameraUnavailable,
                    onQrScanned = { rawText ->
                        viewModel.onQrCodeScanned(rawText)
                    },
                    onInvitationPasted = { rawText ->
                        viewModel.onInvitationPasted(rawText)
                    },
                    onConfirmConnection = {
                        viewModel.confirmConnection()
                    },
                    onCancelConfirmation = {
                        viewModel.cancelConfirmation()
                    },
                    onRetryScan = {
                        viewModel.retryScan()
                    },
                    onClose = {
                        viewModel.stopScanning()
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    scanPurposeLabel = when (route.purpose) {
                        QrScanPurpose.RECEIVE_FROM_SENDER -> "Scan PC QR to receive"
                        QrScanPurpose.PAIR_ONLY -> "Scan pair-only QR"
                        else -> "Scan receiving device QR"
                    }
                )
                when (val state = pairOnlyState) {
                    is dev.glide.app.ui.pairing.PairOnlyUiState.Approval ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pairOnlyViewModel?.decline() },
                            title = { androidx.compose.material3.Text("Pair with ${state.peerName}?") },
                            text = { androidx.compose.material3.Text("Save this verified device for future sends?") },
                            confirmButton = { androidx.compose.material3.Button(onClick = { pairOnlyViewModel?.approve() }) { androidx.compose.material3.Text("Approve") } },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { pairOnlyViewModel?.decline() }) { androidx.compose.material3.Text("Decline") } }
                        )
                    is dev.glide.app.ui.pairing.PairOnlyUiState.Complete ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pairOnlyViewModel?.dismiss() },
                            title = { androidx.compose.material3.Text("Paired with ${state.peerName}") },
                            confirmButton = { androidx.compose.material3.Button(onClick = {
                                pairOnlyViewModel?.dismiss()
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            }) { androidx.compose.material3.Text("Done") } }
                        )
                    is dev.glide.app.ui.pairing.PairOnlyUiState.Failure ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pairOnlyViewModel?.dismiss(); viewModel.retryScan() },
                            title = { androidx.compose.material3.Text("Pairing failed") },
                            text = { androidx.compose.material3.Text(state.message) },
                            confirmButton = { androidx.compose.material3.Button(onClick = {
                                pairOnlyViewModel?.dismiss()
                                viewModel.retryScan()
                            }) { androidx.compose.material3.Text("Try again") } }
                        )
                    else -> Unit
                }
            }
        }
    )
    } // end GlideAdaptiveScaffold content
}
