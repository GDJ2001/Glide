package dev.glide.app

import dev.glide.app.navigation.GlideRoute
import dev.glide.app.ui.transfer.TransferUiState
import dev.glide.app.ui.discovery.DiscoveryUiState
import dev.glide.app.ui.discovery.ReceiveReadinessUiState
import dev.glide.app.ui.history.HistoryUiState
import dev.glide.app.ui.pairing.PairingUiState
import dev.glide.app.ui.settings.SettingsUiState
import dev.glide.designsystem.DevicePostureInfo

/**
 * Encapsulates UI and runtime state overrides used for automated testing,
 * screenshot generation, and design-system validation.
 */
data class LaunchOverrides(
    val debugRoute: GlideRoute? = null,
    val debugUiState: TransferUiState? = null,
    val debugDiscoveryState: DiscoveryUiState? = null,
    val debugReceiveState: ReceiveReadinessUiState? = null,
    val debugPairingState: PairingUiState? = null,
    val debugHistoryState: HistoryUiState? = null,
    val debugSettingsState: SettingsUiState? = null,
    val darkThemeOverride: Boolean? = null,
    val dynamicColorOverride: Boolean? = null,
    val reducedMotionOverride: Boolean? = null,
    val postureOverride: DevicePostureInfo? = null
)
