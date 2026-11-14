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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import dev.glide.app.permission.PermissionPolicy
import dev.glide.app.service.TransferService
import dev.glide.app.lan.LanReceiverRuntimeState
import dagger.hilt.android.AndroidEntryPoint
import dev.glide.app.ingress.IngressOutcome
import dev.glide.app.ingress.ShareIngressHandler
import dev.glide.app.navigation.GlideAppNavDisplay
import dev.glide.app.navigation.GlideRoute
import dev.glide.app.service.TransferRuntime
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.SettingsStore
import dev.glide.core.ports.ThemePreference
import dev.glide.designsystem.GlideTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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

    @Inject
    lateinit var persistentTrustManager: dev.glide.core.identity.PersistentTrustManager

    private var lastHandledFingerprint: String? = null
    @Volatile private var keepReceivingInBackground = false
    private val incomingOutcomeFlow = MutableStateFlow<IngressOutcome?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                keepReceivingInBackground = settings.keepReceivingInBackground
            }
        }

        // Foreground availability follows the Activity, not the Receive or QR screen.
        // The service owns the socket so an incoming authenticated offer can be handled
        // from Home, History or Settings. A terminal transfer may tear down the service;
        // the resulting Idle state re-establishes availability while still foregrounded.
        lifecycleScope.launch {
            transferRuntime.refreshPersistentTrustState()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(persistentTrustManager.trustedPeers, transferRuntime.lanReceiverState) {
                    peers, receiver -> peers.isNotEmpty() && receiver is LanReceiverRuntimeState.Idle
                }.collect { shouldStart ->
                    if (shouldStart && !transferRuntime.isSessionActive.value) {
                        delay(250)
                        val permitted = PermissionPolicy.lanTransferPermissions().all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                        if (permitted &&
                            transferRuntime.lanReceiverState.value is LanReceiverRuntimeState.Idle &&
                            !transferRuntime.isSessionActive.value) {
                            TransferService.startLanReceiver(this@MainActivity)
                        }
                    }
                }
            }
        }

        val overrides = DebugLaunchHarness.prepareLaunchOverrides(
            activity = this,
            intent = intent,
            transferRuntime = transferRuntime
        )

        // Evaluate cold-start share intent or notification session if no debug override active
        var initialDialogState: IngressDialogState? = null
        val initialRoute = overrides.debugRoute ?: run {
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
                overrides.darkThemeOverride != null -> overrides.darkThemeOverride
                else -> when (settings.theme) {
                    ThemePreference.SYSTEM -> isSystemInDarkTheme()
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                }
            }

            val dynamicColor = when {
                overrides.dynamicColorOverride != null -> overrides.dynamicColorOverride
                else -> settings.dynamicColor
            }

            val reducedMotion = when {
                overrides.reducedMotionOverride != null -> overrides.reducedMotionOverride
                else -> dev.glide.app.ui.motion.ReducedMotionResolver.isReducedMotionActive(
                    settings.reducedMotion,
                    context
                )
            }

            val postureInfo = overrides.postureOverride ?: dev.glide.designsystem.DevicePostureInfo(
                posture = dev.glide.designsystem.DevicePosture.Normal,
                hinge = null
            )

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
                            persistentTrustManager = persistentTrustManager,
                            customSettingsStore = settingsStore,
                            initialRoute = initialRoute,
                            incomingRoute = incomingRoute,
                            initialUiState = overrides.debugUiState,
                            initialPairingState = overrides.debugPairingState,
                            initialDiscoveryState = overrides.debugDiscoveryState,
                            initialReceiveState = overrides.debugReceiveState,
                            initialHistoryState = overrides.debugHistoryState,
                            initialSettingsState = overrides.debugSettingsState
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

    override fun onStop() {
        super.onStop()
        if (!keepReceivingInBackground) TransferService.stopIdleLanReceiver(this)
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
