package dev.glide.app.ui.settings

import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.StorageSummary

/**
 * Diagnostics information for local device-to-device transport radios and permissions.
 */
data class NearbyDiagnosticsState(
    val bluetoothEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val permissionsGranted: Boolean = true,
    val radioBand: String = "2.4 GHz / 5 GHz Dual Band",
    val gmsVersion: String = "Nearby Connections v1.0 (P2P Direct)"
)

/**
 * UI State for the Settings surface including theme, private storage, privacy disclosures, and about metadata.
 */
data class SettingsUiState(
    val settings: GlideSettings = GlideSettings(),
    val storageSummary: StorageSummary = StorageSummary(0, 0L, 0),
    val isLoading: Boolean = false,
    val isEditingDeviceName: Boolean = false,
    val deviceNameInput: String = "",
    val deviceNameError: String? = null,
    val isDeleteAllConfirmOpen: Boolean = false,
    val isDeleting: Boolean = false,
    val feedbackMessage: String? = null,
    val isNearbyDiagnosticsOpen: Boolean = false,
    val isLicensesDialogOpen: Boolean = false,
    val nearbyDiagnostics: NearbyDiagnosticsState = NearbyDiagnosticsState(),
    val appVersionName: String = "1.0.0-dev",
    val appBuildNumber: String = "1"
)
