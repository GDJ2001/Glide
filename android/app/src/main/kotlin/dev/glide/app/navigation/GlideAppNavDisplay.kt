package dev.glide.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
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
import dev.glide.app.connectivity.ConnectionKind
import dev.glide.app.ui.discovery.ConnectionSetupRecovery
import dev.glide.app.ui.discovery.rememberConnectionSetup
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
    incomingRouteNonce: Long = 0L,
    initialUiState: dev.glide.app.ui.transfer.TransferUiState? = null,
    initialAutoSaveStatus: dev.glide.app.ui.transfer.ExportUiStatus? = null,
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

    androidx.compose.runtime.LaunchedEffect(incomingRoute, incomingRouteNonce) {
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
                val homeSettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val homeSettings by homeSettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
                val isSessionActive = if (transferRuntime != null) {
                    val active by transferRuntime.isSessionActive.collectAsStateWithLifecycle()
                    active
                } else {
                    false
                }
                val currentActiveSessionId = if (transferRuntime != null) {
                    val sid by transferRuntime.activeSessionId.collectAsStateWithLifecycle()
                    sid
                } else {
                    null
                }
                val activeSessionId = currentActiveSessionId.takeIf { isSessionActive }
                var retainedEntries by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<List<dev.glide.app.ui.history.RetainedResumeEntryUi>>(emptyList())
                }
                val homeScope = androidx.compose.runtime.rememberCoroutineScope()
                if (transferRuntime != null) {
                    val homeTrust by transferRuntime.trustState.collectAsStateWithLifecycle()
                    val retainedVersion by transferRuntime.retainedTransfersVersion.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(isSessionActive, homeTrust.trustedPeers, retainedVersion) {
                        if (!isSessionActive) {
                            retainedEntries = transferRuntime.listRetainedTransfers().map {
                                dev.glide.app.ui.history.RetainedResumeEntryUi(it.sessionId, it.label)
                            }
                        }
                    }
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
                    retainedEntries = retainedEntries,
                    onResumeRetained = { originalSessionId ->
                        dev.glide.app.service.TransferService.startRetainedResume(
                            context,
                            originalSessionId,
                            homeSettings.deviceName
                        )
                        backStack.add(GlideRoute.Transfer(resumingBatch = true))
                    },
                    onDiscardRetained = { originalSessionId ->
                        if (transferRuntime != null) {
                            homeScope.launch {
                                transferRuntime.discardRetainedTransfer(originalSessionId)
                                retainedEntries = transferRuntime.listRetainedTransfers().map {
                                    dev.glide.app.ui.history.RetainedResumeEntryUi(it.sessionId, it.label)
                                }
                            }
                        }
                    },
                    pairedDevices = pairedDevices,
                    onPairDevice = {
                        backStack.add(GlideRoute.QrScanner(purpose = QrScanPurpose.PAIR_ONLY))
                    },
                    onSelectPairedDevice = { peer ->
                        backStack.add(
                            GlideRoute.Selection(
                                pairedPeerFingerprintHex = peer.peerFingerprintHex
                            )
                        )
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
                val context = androidx.compose.ui.platform.LocalContext.current
                val setup = rememberConnectionSetup()
                val selectionSettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val selectionSettings by selectionSettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
                val sendBatch: (dev.glide.core.domain.BatchId) -> Unit = { batchId ->
                    val pendingInvite = route.pendingLanInvitation?.let { encoded ->
                        runCatching { dev.glide.core.pairing.InvitationCodec.decode(encoded) }.getOrNull()
                    }
                    if (pendingInvite != null && transferRuntime != null) {
                        dev.glide.app.service.TransferService.startLanSenderPairing(
                            context = context,
                            invitation = pendingInvite,
                            selectionId = batchId,
                            localDeviceName = selectionSettings.deviceName
                        )
                        backStack.add(
                            GlideRoute.Transfer(
                                sessionId = "send-lan-${pendingInvite.invitationId}",
                                batchId = batchId.value
                            )
                        )
                    } else {
                        val sourceHandles = dev.glide.app.ui.selection.SelectedTransferStore
                            .getSourceMap(batchId).values.map { it.value }
                        backStack.add(nextRouteForPreparedSend(
                            batchId = batchId,
                            pairedPeerFingerprintHex = route.pairedPeerFingerprintHex,
                            sourceHandles = sourceHandles,
                            startTrustedSender = { selectedBatch, fingerprint ->
                                dev.glide.app.service.TransferService.startTrustedSender(
                                    context = context,
                                    selectionId = selectedBatch,
                                    peerFingerprintHex = fingerprint,
                                    localDeviceName = selectionSettings.deviceName
                                )
                            }
                        ))
                    }
                }
                val proceedWithBatch: (dev.glide.core.domain.BatchId) -> Unit = { batchId ->
                    if (route.pendingLanInvitation != null || route.pairedPeerFingerprintHex != null) {
                        setup.start(ConnectionKind.Lan) { sendBatch(batchId) }
                    } else {
                        sendBatch(batchId)
                    }
                }
                ConnectionSetupRecovery(setup)
                SelectionScreen(
                    initialUris = route.initialUris.map { android.net.Uri.parse(it) },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onProceedToSend = { handle ->
                        val activeBatchId = dev.glide.app.ui.selection.SelectedTransferStore.getActive()?.batch?.id
                        if (activeBatchId != null) {
                            proceedWithBatch(activeBatchId)
                        } else if (route.pairedPeerFingerprintHex == null) {
                            backStack.add(GlideRoute.Discovery(sourceHandles = listOf(handle.value)))
                        }
                    },
                    onProceedToSendBatch = { batch ->
                        proceedWithBatch(batch.id)
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
                val setup = rememberConnectionSetup()
                val discoveryIsVisible = currentRoute == route
                LifecycleStartEffect(viewModel, discoveryIsVisible, transferRuntime) {
                    if (discoveryIsVisible && initialDiscoveryState == null) {
                        if (transferRuntime?.discoveryState?.value is dev.glide.app.ui.discovery.DiscoveryUiState.Searching) {
                            viewModel.startDiscovery()
                        }
                        transferRuntime?.dispatch(dev.glide.app.service.TransferRuntimeCommand.StartLanPeerDiscovery)
                    }
                    onStopOrDispose {
                        if (discoveryIsVisible) {
                            viewModel.stopDiscovery()
                            transferRuntime?.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanPeerDiscovery)
                        }
                    }
                }
                val startDiscoveryAtPointOfUse: () -> Unit = {
                    setup.start(ConnectionKind.Nearby, viewModel::setBlocked) { viewModel.startDiscovery() }
                }
                val discoveryScope = androidx.compose.runtime.rememberCoroutineScope()
                var isDiscoveryHotspotActive by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                var hasStartedSetup by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                LifecycleResumeEffect(transferRuntime, discoveryIsVisible) {
                    if (discoveryIsVisible && initialDiscoveryState == null && transferRuntime != null) {
                        discoveryScope.launch {
                            val hotspotOn = dev.glide.app.connectivity.isPhoneHotspotActive(context)
                            isDiscoveryHotspotActive = hotspotOn
                            if (hotspotOn) {
                                viewModel.setBlocked(dev.glide.app.ui.discovery.BlockedReason.WifiDisabled)
                            }
                        }
                    }
                    onPauseOrDispose {}
                }
                androidx.compose.runtime.LaunchedEffect(initialDiscoveryState, transferRuntime, currentRoute == route) {
                    if (initialDiscoveryState != null) {
                        viewModel.setUiStateForTesting(initialDiscoveryState)
                    } else if (transferRuntime != null && currentRoute == route) {
                        transferRuntime.dispatch(dev.glide.app.service.TransferRuntimeCommand.StartLanPeerDiscovery)
                        val hotspotOn = dev.glide.app.connectivity.isPhoneHotspotActive(context)
                        isDiscoveryHotspotActive = hotspotOn
                        if (!hasStartedSetup && !hotspotOn) {
                            hasStartedSetup = true
                            startDiscoveryAtPointOfUse()
                        } else if (hotspotOn) {
                            viewModel.setBlocked(dev.glide.app.ui.discovery.BlockedReason.WifiDisabled)
                        }
                    }
                }

                val discoverySettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val discoverySettings by discoverySettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lanPeers = if (transferRuntime != null) {
                    val discovered by transferRuntime.onlineLanPeers.collectAsStateWithLifecycle()
                    discovered
                } else {
                    emptyList()
                }
                dev.glide.app.ui.discovery.DiscoveryScreen(
                    uiState = uiState,
                    lanPeers = lanPeers,
                    isHotspotActive = isDiscoveryHotspotActive,
                    onUseHotspot = {
                        viewModel.stopDiscovery()
                        transferRuntime?.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanPeerDiscovery)
                        backStack.add(GlideRoute.QrScanner(batchId = route.batchId))
                    },
                    onBack = {
                        viewModel.stopDiscovery()
                        transferRuntime?.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanPeerDiscovery)
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onSelectPeer = { peerId ->
                        val trustedFingerprint = transferRuntime?.resolveTrustedLanPeerFingerprint(peerId)
                        if (trustedFingerprint != null) {
                            val resolvedBatchId = route.batchId
                                ?: dev.glide.app.ui.selection.SelectedTransferStore.getActive()?.batch?.id?.value
                            if (resolvedBatchId != null) {
                                viewModel.stopDiscovery()
                                transferRuntime.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanPeerDiscovery)
                                dev.glide.app.service.TransferService.startTrustedSender(
                                    context = context,
                                    selectionId = dev.glide.core.domain.BatchId(resolvedBatchId),
                                    peerFingerprintHex = trustedFingerprint,
                                    localDeviceName = discoverySettings.deviceName
                                )
                                backStack.add(
                                    GlideRoute.Transfer(
                                        sessionId = "send-trusted-${trustedFingerprint.take(12)}",
                                        batchId = resolvedBatchId,
                                        pairedPeerFingerprintHex = trustedFingerprint
                                    )
                                )
                            }
                        } else {
                            val peer = (uiState as? dev.glide.app.ui.discovery.DiscoveryUiState.Searching)
                                ?.peers
                                ?.firstOrNull { it.id == peerId }
                            if (peer != null) {
                                viewModel.selectPeer(peerId) { selectedId ->
                                    viewModel.stopDiscovery()
                                    transferRuntime?.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanPeerDiscovery)
                                    dev.glide.app.service.TransferService.startNearbySenderPairing(
                                        context = context,
                                        peerId = selectedId,
                                        peerName = peer.displayName,
                                        selectionId = route.batchId?.let { dev.glide.core.domain.BatchId(it) },
                                        localDeviceName = discoverySettings.deviceName
                                    )
                                    backStack.add(
                                        GlideRoute.Transfer(
                                            sessionId = "nearby-send-${selectedId.value}",
                                            batchId = route.batchId
                                        )
                                    )
                                }
                            }
                        }
                    },
                    onRetry = startDiscoveryAtPointOfUse,
                    onResolveBlocker = { reason ->
                        setup.openSettings(ConnectionKind.Nearby, reason, viewModel::setBlocked) { viewModel.startDiscovery() }
                    },
                    onScanQrClicked = {
                        viewModel.stopDiscovery()
                        transferRuntime?.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanPeerDiscovery)
                        backStack.add(GlideRoute.QrScanner(batchId = route.batchId))
                    }
                )
            }
            entry<GlideRoute.ReceiveReadiness> {
                val context = androidx.compose.ui.platform.LocalContext.current
                val receiveSettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val receiveSettings by receiveSettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
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

                val setup = rememberConnectionSetup()
                val startAdvertising: () -> Unit = {
                    val localName = receiveSettings.deviceName
                    if (transferRuntime != null) {
                        dev.glide.app.service.TransferService.startNearbyReceiver(context, localName)
                    } else {
                        viewModel.startAdvertising(localName)
                    }
                }
                val startAdvertisingAtPointOfUse: () -> Unit = {
                    setup.start(ConnectionKind.Nearby, viewModel::setBlocked, startAdvertising)
                }
                val startLanReceiverAtPointOfUse: () -> Unit = startLanReceiver@ {
                    if (setup.pending != null) return@startLanReceiver
                    viewModel.stopAdvertising()
                    val localName = receiveSettings.deviceName
                    if (transferRuntime == null || initialReceiveState != null) {
                        qrViewModel.startReceiving(
                            deviceName = localName,
                            endpoints = listOf(dev.glide.core.pairing.LanEndpoint("192.168.1.42", 8443)),
                            spkiSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        )
                        showQrSheet = true
                    } else {
                        if (dev.glide.transport.lan.network.AndroidHotspotConnections.host != null) showQrSheet = true
                        setup.start(ConnectionKind.Lan) {
                            qrViewModel.showLiveReceiverStarting()
                            showQrSheet = true
                            dev.glide.app.service.TransferService.startLanReceiver(context, receiveSettings.deviceName)
                        }
                    }
                }
                ConnectionSetupRecovery(setup, kind = ConnectionKind.Lan) {
                    dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                    showQrSheet = false
                }

                val hotspotScope = androidx.compose.runtime.rememberCoroutineScope()
                var hotspotStatus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                var startingHotspot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                var showManualHotspotFallback by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                val startHotspot: () -> Unit = {
                    if (!startingHotspot && transferRuntime != null) {
                        startingHotspot = true
                        hotspotStatus = "Starting hotspot…"
                        hotspotScope.launch {
                            try {
                                val host = dev.glide.transport.lan.network.AndroidHotspotConnections.createHost(context)
                                if (host == null) {
                                    hotspotStatus = "Glide couldn’t start a hotspot automatically. Use Android hotspot settings below."
                                    showManualHotspotFallback = true
                                } else {
                                    hotspotStatus = null
                                    showManualHotspotFallback = false
                                    transferRuntime.dispatch(dev.glide.app.service.TransferRuntimeCommand.StopLanReceiver)
                                    startLanReceiverAtPointOfUse()
                                }
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                hotspotStatus = "Glide couldn’t start a hotspot automatically. Use Android hotspot settings below."
                                showManualHotspotFallback = true
                            } finally {
                                startingHotspot = false
                            }
                        }
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

                var isReceiveHotspotActive by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                var hasStartedReceiveSetup by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(initialReceiveState, transferRuntime) {
                    if (initialReceiveState != null) {
                        viewModel.setUiStateForTesting(initialReceiveState)
                    } else if (transferRuntime != null && !showQrSheet && !hasStartedReceiveSetup) {
                        hasStartedReceiveSetup = true
                        val hotspotOn = dev.glide.app.connectivity.isPhoneHotspotActive(context)
                        isReceiveHotspotActive = hotspotOn
                        if (hotspotOn) {
                            viewModel.setBlocked(dev.glide.app.ui.discovery.BlockedReason.WifiDisabled)
                            if (dev.glide.transport.lan.network.AndroidHotspotConnections.host != null) {
                                startLanReceiverAtPointOfUse()
                            } else {
                                dev.glide.app.service.TransferService.startLanReceiver(
                                    context = context,
                                    localDeviceName = receiveSettings.deviceName
                                )
                            }
                        } else {
                            startAdvertisingAtPointOfUse()
                        }
                    }
                }
                LifecycleResumeEffect(transferRuntime, showQrSheet) {
                    if (initialReceiveState == null && transferRuntime != null && !showQrSheet) {
                        hotspotScope.launch {
                            val hotspotOn = dev.glide.app.connectivity.isPhoneHotspotActive(context)
                            isReceiveHotspotActive = hotspotOn
                            if (hotspotOn) {
                                viewModel.setBlocked(dev.glide.app.ui.discovery.BlockedReason.WifiDisabled)
                                val currentLan = transferRuntime.lanReceiverState.value
                                if (currentLan !is dev.glide.app.lan.LanReceiverRuntimeState.Ready &&
                                    currentLan !is dev.glide.app.lan.LanReceiverRuntimeState.Starting &&
                                    currentLan !is dev.glide.app.lan.LanReceiverRuntimeState.Connected
                                ) {
                                    dev.glide.app.service.TransferService.startLanReceiver(
                                        context = context,
                                        localDeviceName = receiveSettings.deviceName
                                    )
                                }
                            }
                        }
                    }
                    onPauseOrDispose {}
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
                                    hotspotQr = dev.glide.transport.lan.network.AndroidHotspotConnections.host?.let { host ->
                                        runCatching {
                                            dev.glide.core.pairing.HotspotInvitationCodec.encode(
                                                dev.glide.core.pairing.HotspotInvitation(host.ssid, host.passphrase, state.invitation)
                                            )
                                        }.getOrNull()
                                    },
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
                    onResolveBlocker = { reason ->
                        setup.openSettings(ConnectionKind.Nearby, reason, viewModel::setBlocked, startAdvertising)
                    },
                    onShowQrCode = startLanReceiverAtPointOfUse,
                    onStartHotspot = if (transferRuntime == null) null else {
                        {
                            if (!startingHotspot) {
                                setup.start(ConnectionKind.Hotspot, onBlocked = {
                                    showManualHotspotFallback = true
                                }, onReady = startHotspot)
                            }
                        }
                    },
                    hotspotStatus = hotspotStatus,
                    showManualHotspotFallback = showManualHotspotFallback,
                    onDismissManualHotspotFallback = { showManualHotspotFallback = false },
                    isHotspotActive = isReceiveHotspotActive,
                    onUseHotspotInstead = {
                        if (!startingHotspot) {
                            showManualHotspotFallback = true
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
                            if (dev.glide.transport.lan.network.AndroidHotspotConnections.host != null) {
                                dev.glide.app.service.TransferService.stopLanReceiver(context)
                            }
                            dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                            startAdvertising()
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
                    if (initialUiState != null &&
                        viewModel.uiState.value is dev.glide.app.ui.transfer.TransferUiState.Idle
                    ) {
                        viewModel.setUiStateForTesting(initialUiState)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(initialAutoSaveStatus) {
                    if (initialAutoSaveStatus != null &&
                        viewModel.exportStatus.value is dev.glide.app.ui.transfer.ExportUiStatus.Idle
                    ) {
                        transferRuntime?.setAutoSaveStatusForTesting(initialAutoSaveStatus)
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
                val transferSettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val transferSettings by transferSettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())

                androidx.compose.runtime.LaunchedEffect(route, uiState) {
                    if (!route.resumingBatch && uiState is dev.glide.app.ui.transfer.TransferUiState.Completed &&
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
                    onPauseTransfer = { viewModel.pauseTransfer() },
                    onResumeTransfer = {
                        val paused = uiState as? dev.glide.app.ui.transfer.TransferUiState.Paused
                        if (paused != null) {
                            dev.glide.app.service.TransferService.startRetainedResume(
                                context,
                                paused.resumableSessionId,
                                transferSettings.deviceName
                            )
                        } else {
                            viewModel.resumeTransfer()
                        }
                    },
                    onCancelTransfer = { viewModel.cancelTransfer() },
                    onConfirmMatch = { peerId -> viewModel.confirmPairing(peerId) },
                    onRejectMatch = { peerId -> viewModel.rejectPairing(peerId) },
                    onScanNewCode = {
                        if (route.pairedPeerFingerprintHex != null) {
                            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                        } else {
                            restartQrScanAfterPairingFailure(backStack, route)
                        }
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
                    onOpenFolder = { rel -> viewModel.openInFileManager(context, rel) },
                    onRetryTransfer = {
                        val remainingUris = viewModel.getUnfinishedSourceUris()
                        if (remainingUris.isNotEmpty()) {
                            backStack.add(
                                GlideRoute.Selection(
                                    initialUris = remainingUris,
                                    pairedPeerFingerprintHex = route.pairedPeerFingerprintHex
                                )
                            )
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

                val historySettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val historySettings by historySettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
                dev.glide.app.ui.history.HistoryScreen(
                    viewModel = viewModel,
                    initialSessionId = route.initialSessionId,
                    resumePanel = {
                        if (transferRuntime != null)
                            dev.glide.app.ui.history.RetainedTransfersPanel(transferRuntime) { original ->
                                dev.glide.app.service.TransferService.startRetainedResume(
                                    context,
                                    original,
                                    historySettings.deviceName
                                )
                                backStack.add(GlideRoute.Transfer(resumingBatch = true))
                            }
                    },
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
                    appBuildNumber,
                    context
                ) {
                    dev.glide.app.ui.settings.SettingsViewModel.provideFactory(
                        settingsStore = settingsStore,
                        fileManager = fileManager,
                        trustedPeerDao = trustedPeerDao,
                        transferRuntime = transferRuntime,
                        appVersionName = appVersionName,
                        appBuildNumber = appBuildNumber,
                        diagnosticsProvider = { dev.glide.app.connectivity.readNearbyDiagnostics(context) }
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
                val pairOnlyViewModel = if ((route.purpose == QrScanPurpose.PAIR_ONLY ||
                        route.purpose == QrScanPurpose.UNIVERSAL) && persistentTrustManager != null) {
                    val factory = androidx.compose.runtime.remember(context, persistentTrustManager) {
                        dev.glide.app.ui.pairing.PairOnlyViewModel.factory(context, persistentTrustManager)
                    }
                    androidx.lifecycle.viewmodel.compose.viewModel<dev.glide.app.ui.pairing.PairOnlyViewModel>(factory = factory)
                } else null
                val pairOnlyState = pairOnlyViewModel?.state?.collectAsStateWithLifecycle()?.value
                    ?: dev.glide.app.ui.pairing.PairOnlyUiState.Idle
                val setup = rememberConnectionSetup()
                ConnectionSetupRecovery(setup) {
                    dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                    viewModel.retryScan()
                }
                val qrSettingsStore = androidx.compose.runtime.remember(customSettingsStore, context) {
                    customSettingsStore ?: dev.glide.app.di.DatabaseModule.provideSettingsStore(context)
                }
                val qrSettings by qrSettingsStore.settingsFlow
                    .collectAsStateWithLifecycle(initialValue = dev.glide.core.ports.GlideSettings())
                val startLanSender: (dev.glide.core.pairing.LanInvitation) -> Unit = { invitation ->
                    if (transferRuntime != null) {
                        dev.glide.app.service.TransferService.startLanSenderPairing(
                            context = context,
                            invitation = invitation,
                            selectionId = route.batchId?.let { dev.glide.core.domain.BatchId(it) },
                            localDeviceName = qrSettings.deviceName
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
                            invitation = invitation,
                            localDeviceName = qrSettings.deviceName
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
                androidx.compose.runtime.LaunchedEffect(viewModel, route.scanAttemptId, route.purpose) {
                    viewModel.startScanning(pairOnly = route.purpose == QrScanPurpose.PAIR_ONLY)
                }

                androidx.compose.runtime.LaunchedEffect(viewModel, route.batchId, route.purpose, pairOnlyViewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is dev.glide.app.ui.pairing.QrPairingEvent.PairOnlyConfirmed -> {
                                if (pairOnlyViewModel == null) {
                                    viewModel.onConnectionBlocked("Pairing is unavailable. Restart Glide and try again.")
                                } else {
                                    setup.start(ConnectionKind.Lan) { pairOnlyViewModel.start(event.invitation) }
                                }
                            }
                            is dev.glide.app.ui.pairing.QrPairingEvent.ConnectConfirmed -> {
                                val resolvedBatchId = route.batchId
                                    ?: dev.glide.app.ui.selection.SelectedTransferStore.getActive()?.batch?.id?.value
                                if (route.purpose == QrScanPurpose.UNIVERSAL && resolvedBatchId == null) {
                                    backStack.add(
                                        GlideRoute.Selection(
                                            pendingLanInvitation = dev.glide.core.pairing.InvitationCodec.encode(event.invitation)
                                        )
                                    )
                                } else {
                                    setup.start(ConnectionKind.Lan) {
                                        if (route.purpose == QrScanPurpose.RECEIVE_FROM_SENDER) {
                                            startLanReceiver(event.invitation)
                                        } else {
                                            startLanSender(event.invitation)
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }

                var hotspotInvitationUri by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf<String?>(null)
                }
                val hotspotInvitation = androidx.compose.runtime.remember(hotspotInvitationUri) {
                    hotspotInvitationUri?.let {
                        runCatching {
                            dev.glide.core.pairing.HotspotInvitationCodec.decode(
                                it,
                                clockSkewGraceSeconds = 300L
                            )
                        }.getOrNull()
                    }
                }
                val handleInvitationText: (String) -> Unit = { rawText ->
                    val value = rawText.trim()
                    if (route.purpose != QrScanPurpose.PAIR_ONLY &&
                        value.startsWith(dev.glide.core.pairing.HotspotInvitationCodec.PREFIX)) {
                        if (hotspotInvitation == null) {
                            val decoded = runCatching {
                                dev.glide.core.pairing.HotspotInvitationCodec.decode(
                                    value,
                                    clockSkewGraceSeconds = 300L
                                )
                            }.getOrNull()
                            if (decoded == null) {
                                viewModel.onConnectionBlocked("This hotspot code is invalid or has expired.")
                            } else {
                                hotspotInvitationUri = value
                            }
                        }
                    } else viewModel.onQrCodeScanned(rawText)
                }
                hotspotInvitation?.let { hotspot ->
                    dev.glide.app.ui.pairing.HotspotJoinDialog(
                        invitation = hotspot,
                        onReady = {
                            hotspotInvitationUri = null
                            viewModel.onQrCodeScanned(dev.glide.core.pairing.InvitationCodec.encode(hotspot.pairing))
                        },
                        onDismiss = {
                            hotspotInvitationUri = null
                            dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                            viewModel.retryScan()
                        }
                    )
                }

                dev.glide.app.ui.pairing.QrScannerScreen(
                    uiState = scannerState,
                    onCameraUnavailable = viewModel::onCameraUnavailable,
                    onQrScanned = handleInvitationText,
                    onInvitationPasted = { rawText ->
                        if (rawText.trim().startsWith(dev.glide.core.pairing.HotspotInvitationCodec.PREFIX)) {
                            handleInvitationText(rawText)
                        } else viewModel.onInvitationPasted(rawText)
                    },
                    onConfirmConnection = {
                        viewModel.confirmConnection()
                    },
                    onCancelConfirmation = {
                        dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                        viewModel.cancelConfirmation()
                    },
                    onRetryScan = {
                        dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                        viewModel.retryScan()
                    },
                    onClose = {
                        dev.glide.transport.lan.network.AndroidHotspotConnections.closeUnclaimed()
                        viewModel.stopScanning()
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    scanPurposeLabel = when (route.purpose) {
                        QrScanPurpose.RECEIVE_FROM_SENDER -> "Scan PC QR to receive"
                        QrScanPurpose.PAIR_ONLY -> "Scan pair-only QR"
                        QrScanPurpose.UNIVERSAL -> "Scan Glide QR code"
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
