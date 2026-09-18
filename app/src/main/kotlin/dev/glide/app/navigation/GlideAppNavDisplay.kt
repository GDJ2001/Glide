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
                    onNavigateToSettings = {
                        backStack.add(GlideRoute.Settings)
                    },
                    activeSessionId = activeSessionId,
                    onNavigateToTransfer = { sid ->
                        backStack.add(GlideRoute.Transfer(sid))
                    }
                )
            }
            entry<GlideRoute.Selection> { route ->
                val webShareViewModel = androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.web.WebShareViewModel>()
                val webShareState by webShareViewModel.uiState.collectAsStateWithLifecycle()
                var showWebShareSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

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
                    },
                    onShareViaWeb = { items ->
                        webShareViewModel.startSharing(items)
                        showWebShareSheet = true
                    }
                )

                if (showWebShareSheet) {
                    dev.glide.app.ui.web.WebShareSheet(
                        uiState = webShareState,
                        onStopShare = { webShareViewModel.stopSharing() },
                        onDismiss = {
                            webShareViewModel.dismiss()
                            showWebShareSheet = false
                        }
                    )
                }
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
                var showQrSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

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
                    if (transferRuntime == null) {
                        qrViewModel.showLiveReceiverError(
                            "Production LAN runtime is unavailable."
                        )
                        showQrSheet = true
                    } else if (lanPermissions.isEmpty() || hasLanPermissions()) {
                        qrViewModel.showLiveReceiverStarting()
                        showQrSheet = true
                        dev.glide.app.service.TransferService.startLanReceiver(
                            context = context,
                            localDeviceName = "Glide Device"
                        )
                    } else {
                        lanPermissionLauncher.launch(lanPermissions.toTypedArray())
                    }
                }

                androidx.compose.runtime.LaunchedEffect(initialReceiveState, transferRuntime) {
                    if (initialReceiveState != null) {
                        viewModel.setUiStateForTesting(initialReceiveState)
                    } else if (transferRuntime != null) {
                        startAdvertisingAtPointOfUse()
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
                        dev.glide.app.lan.LanReceiverRuntimeState.Starting -> {
                            qrViewModel.showLiveReceiverStarting()
                            showQrSheet = true
                        }
                        is dev.glide.app.lan.LanReceiverRuntimeState.Ready -> {
                            qrViewModel.showLiveReceiverInvitation(
                                invitation = state.invitation,
                                onRevoke = {
                                    transferRuntime?.dispatch(
                                        dev.glide.app.service.TransferRuntimeCommand.StopLanReceiver
                                    )
                                }
                            )
                            showQrSheet = true
                        }
                        is dev.glide.app.lan.LanReceiverRuntimeState.Error -> {
                            qrViewModel.showLiveReceiverError(
                                message = state.message,
                                canRetry = state.canRetry
                            )
                            showQrSheet = true
                        }
                        is dev.glide.app.lan.LanReceiverRuntimeState.Connected -> {
                            qrViewModel.clearLiveReceiverPresentation()
                            showQrSheet = false
                            if (backStack.lastOrNull() !is GlideRoute.Transfer) {
                                backStack.add(
                                    GlideRoute.Transfer(
                                        sessionId = "lan-receive-${state.peer.id.value}"
                                    )
                                )
                            }
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
                    onShowQrCode = startLanReceiverAtPointOfUse
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
                val openFileFeedback by viewModel.openFileFeedback.collectAsStateWithLifecycle()
                val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                TransferScreen(
                    route = route,
                    uiState = uiState,
                    pairingState = pairingState,
                    openFileFeedback = openFileFeedback,
                    exportStatus = exportStatus,
                    onAcceptConsent = { viewModel.acceptConsent() },
                    onRejectConsent = { viewModel.rejectConsent() },
                    onCancelTransfer = { viewModel.cancelTransfer() },
                    onConfirmMatch = { peerId -> viewModel.confirmPairing(peerId) },
                    onRejectMatch = { peerId -> viewModel.rejectPairing(peerId) },
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
                val trustedPeerDao = androidx.compose.runtime.remember(customTrustedPeerDao, context) {
                    customTrustedPeerDao ?: run {
                        val db = dev.glide.app.di.DatabaseModule.provideGlideDatabase(context)
                        db.trustedPeerDao()
                    }
                }
                val factory = androidx.compose.runtime.remember(settingsStore, fileManager, trustedPeerDao) {
                    dev.glide.app.ui.settings.SettingsViewModel.provideFactory(settingsStore, fileManager, trustedPeerDao)
                }
                val viewModel: dev.glide.app.ui.settings.SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                androidx.compose.runtime.LaunchedEffect(initialSettingsState) {
                    if (initialSettingsState != null) {
                        viewModel.setUiStateForTesting(initialSettingsState)
                    }
                }

                dev.glide.app.ui.settings.SettingsScreen(
                    viewModel = viewModel,
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
                val lanPermissions = androidx.compose.runtime.remember {
                    PermissionPolicy.lanTransferPermissions()
                }
                var pendingInvitation by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<dev.glide.core.pairing.LanInvitation?>(null)
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
                    }
                }
                val lanPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val invitation = pendingInvitation
                    pendingInvitation = null
                    if (hasLanPermissions() && invitation != null) {
                        startLanSender(invitation)
                    }
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    viewModel.startScanning()
                }

                androidx.compose.runtime.LaunchedEffect(viewModel, route.batchId) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is dev.glide.app.ui.pairing.QrPairingEvent.ConnectConfirmed -> {
                                if (lanPermissions.isEmpty() || hasLanPermissions()) {
                                    startLanSender(event.invitation)
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
                    }
                )
            }
        }
    )
    } // end GlideAdaptiveScaffold content
}
