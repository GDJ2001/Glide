package dev.glide.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.glide.app.service.TransferRuntime
import dev.glide.core.ports.DeviceNameValidator
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.PrivateFileManager
import dev.glide.core.ports.ReducedMotionPreference
import dev.glide.core.ports.SettingsStore
import dev.glide.core.ports.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.glide.data.db.TrustedPeerDao
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val fileManager: PrivateFileManager,
    private val trustedPeerDao: TrustedPeerDao? = null,
    private val transferRuntime: TransferRuntime? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(isLoading = true))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var isTestingOverride = false

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                if (!isTestingOverride) {
                    _uiState.update { it.copy(settings = settings, isLoading = false) }
                }
            }
        }
        if (transferRuntime != null) {
            viewModelScope.launch {
                transferRuntime.refreshPersistentTrustState()
                transferRuntime.trustState.collect { trust ->
                    if (!isTestingOverride) {
                        _uiState.update {
                            it.copy(
                                trustedPeers = trust.trustedPeers
                            )
                        }
                    }
                }
            }
        } else {
            viewModelScope.launch {
                trustedPeerDao?.observeAllPeers()?.collect { peers ->
                    if (!isTestingOverride) {
                        _uiState.update { state ->
                            state.copy(
                                trustedPeers = peers.map { peer ->
                                    dev.glide.core.domain.TrustedPeerRecord(
                                        peerFingerprintHex =
                                            peer.peerFingerprint.lowercase(),
                                        publicKeyDerHex =
                                            peer.publicKeyDer.lowercase(),
                                        displayName = peer.displayName,
                                        pairedAtMs = peer.pairedAtMs,
                                        lastSeenAtMs = peer.lastSeenAtMs
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        refreshStorageSummary()
    }

    fun refreshStorageSummary() {
        if (isTestingOverride) return
        viewModelScope.launch {
            val summary = fileManager.getStorageSummary()
            if (!isTestingOverride) {
                _uiState.update { it.copy(storageSummary = summary) }
            }
        }
    }

    fun setReceivedFilesFolder(treeUri: String?) {
        viewModelScope.launch {
            val result = settingsStore.updateReceivedFilesTreeUri(treeUri)
            _uiState.update {
                it.copy(feedbackMessage = if (result.isSuccess) {
                    if (treeUri == null) "Save location reset to Download/Glide" else "Save location updated"
                } else {
                    result.exceptionOrNull()?.message ?: "Could not update save location"
                })
            }
        }
    }

    fun showSaveLocationError(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
    }

    fun startEditDeviceName() {
        _uiState.update {
            it.copy(
                isEditingDeviceName = true,
                deviceNameInput = it.settings.deviceName,
                deviceNameError = null
            )
        }
    }

    fun onDeviceNameInputChange(input: String) {
        val validation = DeviceNameValidator.validate(input)
        _uiState.update {
            it.copy(
                deviceNameInput = input,
                deviceNameError = validation.exceptionOrNull()?.message
            )
        }
    }

    fun saveDeviceName() {
        val input = _uiState.value.deviceNameInput
        val validation = DeviceNameValidator.validate(input)
        if (validation.isFailure) {
            _uiState.update {
                it.copy(deviceNameError = validation.exceptionOrNull()?.message ?: "Invalid device name")
            }
            return
        }

        viewModelScope.launch {
            val result = settingsStore.updateDeviceName(validation.getOrThrow())
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isEditingDeviceName = false,
                        deviceNameError = null,
                        feedbackMessage = "Device name updated"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        deviceNameError = result.exceptionOrNull()?.message ?: "Failed to save device name"
                    )
                }
            }
        }
    }

    fun cancelEditDeviceName() {
        _uiState.update {
            it.copy(
                isEditingDeviceName = false,
                deviceNameInput = "",
                deviceNameError = null
            )
        }
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch {
            val result = settingsStore.updateTheme(theme)
            if (result.isFailure) {
                _uiState.update { it.copy(feedbackMessage = "Failed to update theme") }
            }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            val result = settingsStore.updateDynamicColor(enabled)
            if (result.isFailure) {
                _uiState.update { it.copy(feedbackMessage = "Failed to update dynamic color") }
            }
        }
    }

    fun setReducedMotion(motion: ReducedMotionPreference) {
        viewModelScope.launch {
            val result = settingsStore.updateReducedMotion(motion)
            if (result.isFailure) {
                _uiState.update { it.copy(feedbackMessage = "Failed to update reduced motion") }
            }
        }
    }

    fun setNotificationDetails(enabled: Boolean) {
        viewModelScope.launch {
            val result = settingsStore.updateNotificationDetails(enabled)
            if (result.isFailure) {
                _uiState.update { it.copy(feedbackMessage = "Failed to update notification settings") }
            }
        }
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        viewModelScope.launch {
            val result = settingsStore.updateKeepScreenAwake(enabled)
            if (result.isFailure) {
                _uiState.update { it.copy(feedbackMessage = "Failed to update screen awake setting") }
            }
        }
    }

    fun requestDeleteAllFiles() {
        _uiState.update { it.copy(isDeleteAllConfirmOpen = true) }
    }

    fun dismissDeleteAllFilesDialog() {
        _uiState.update { it.copy(isDeleteAllConfirmOpen = false) }
    }

    fun confirmDeleteAllFiles() {
        _uiState.update { it.copy(isDeleteAllConfirmOpen = false, isDeleting = true) }
        viewModelScope.launch {
            val result = fileManager.deleteAllReceivedFiles()
            val summary = fileManager.getStorageSummary()
            if (result.isSuccess) {
                val deletedCount = result.getOrThrow()
                val message = if (deletedCount > 0) {
                    if (summary.activeFiles > 0) {
                        "Deleted $deletedCount files. ${summary.activeFiles} active transfer files were preserved."
                    } else {
                        "Deleted $deletedCount received files."
                    }
                } else if (summary.activeFiles > 0) {
                    "No files deleted. All ${summary.activeFiles} files are currently in active transfers."
                } else {
                    "No received files to delete."
                }
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        storageSummary = summary,
                        feedbackMessage = message
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        storageSummary = summary,
                        feedbackMessage = "Failed to delete files: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun showNearbyDiagnostics() {
        _uiState.update { it.copy(isNearbyDiagnosticsOpen = true) }
    }

    fun dismissNearbyDiagnostics() {
        _uiState.update { it.copy(isNearbyDiagnosticsOpen = false) }
    }

    fun showLicensesDialog() {
        _uiState.update { it.copy(isLicensesDialogOpen = true) }
    }

    fun dismissLicensesDialog() {
        _uiState.update { it.copy(isLicensesDialogOpen = false) }
    }

    fun showTrustedDevicesDialog() {
        _uiState.update { it.copy(isTrustedDevicesDialogOpen = true) }
    }

    fun dismissTrustedDevicesDialog() {
        _uiState.update { it.copy(isTrustedDevicesDialogOpen = false) }
    }

    fun revokeTrustedPeer(fingerprint: String) {
        viewModelScope.launch {
            val revoked = if (transferRuntime != null) {
                transferRuntime.revokeTrustedPeerNow(fingerprint)
            } else {
                val dao = trustedPeerDao
                if (dao == null) {
                    false
                } else {
                    dao.deleteByFingerprint(fingerprint)
                    true
                }
            }

            if (revoked) {
                _uiState.update { state ->
                    state.copy(
                        trustedPeers = state.trustedPeers.filterNot {
                            it.peerFingerprintHex == fingerprint
                        },
                        feedbackMessage = "Device trust revoked"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        feedbackMessage = "Failed to revoke device trust"
                    )
                }
            }
        }
    }

    internal fun setUiStateForTesting(state: SettingsUiState) {
        isTestingOverride = true
        _uiState.value = state
    }

    companion object {
        fun provideFactory(
            settingsStore: SettingsStore,
            fileManager: PrivateFileManager,
            trustedPeerDao: TrustedPeerDao? = null,
            transferRuntime: TransferRuntime? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    settingsStore,
                    fileManager,
                    trustedPeerDao,
                    transferRuntime
                ) as T
            }
        }
    }
}
