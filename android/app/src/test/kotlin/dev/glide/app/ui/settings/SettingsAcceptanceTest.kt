package dev.glide.app.ui.settings

import dev.glide.core.ports.DeviceNameValidator
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.PrivateFileManager
import dev.glide.core.ports.ReceivedFileInfo
import dev.glide.core.ports.ReducedMotionPreference
import dev.glide.core.ports.SettingsStore
import dev.glide.core.ports.StorageSummary
import dev.glide.core.ports.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAcceptanceTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSettingsStore(
        initial: GlideSettings = GlideSettings()
    ) : SettingsStore {
        private val _flow = MutableStateFlow(initial)
        override val settingsFlow: Flow<GlideSettings> = _flow

        override suspend fun getSettings(): GlideSettings = _flow.value

        override suspend fun updateDeviceName(name: String): Result<Unit> {
            val validation = DeviceNameValidator.validate(name)
            return validation.map { validName ->
                _flow.value = _flow.value.copy(deviceName = validName)
            }
        }

        override suspend fun updateTheme(theme: ThemePreference): Result<Unit> {
            _flow.value = _flow.value.copy(theme = theme)
            return Result.success(Unit)
        }

        override suspend fun updateDynamicColor(enabled: Boolean): Result<Unit> {
            _flow.value = _flow.value.copy(dynamicColor = enabled)
            return Result.success(Unit)
        }

        override suspend fun updateReducedMotion(motion: ReducedMotionPreference): Result<Unit> {
            _flow.value = _flow.value.copy(reducedMotion = motion)
            return Result.success(Unit)
        }

        override suspend fun updateNotificationDetails(enabled: Boolean): Result<Unit> {
            _flow.value = _flow.value.copy(notificationDetails = enabled)
            return Result.success(Unit)
        }

        override suspend fun updateKeepScreenAwake(enabled: Boolean): Result<Unit> {
            _flow.value = _flow.value.copy(keepScreenAwake = enabled)
            return Result.success(Unit)
        }
    }

    private class FakeFileManager : PrivateFileManager {
        val files = mutableListOf<ReceivedFileInfo>()
        var deleteCallCount = 0

        override suspend fun getStorageSummary(): StorageSummary {
            val totalBytes = files.sumOf { it.sizeBytes }
            val activeCount = files.count { it.isActive }
            return StorageSummary(
                totalFiles = files.size,
                totalBytes = totalBytes,
                activeFiles = activeCount
            )
        }

        override suspend fun getReceivedFiles(): List<ReceivedFileInfo> = files.toList()

        override suspend fun deleteAllReceivedFiles(): Result<Int> {
            deleteCallCount++
            val toDelete = files.filter { !it.isActive }
            val count = toDelete.size
            files.removeAll(toDelete)
            return Result.success(count)
        }

        override suspend fun deleteReceivedFile(fileKey: String): Result<Unit> {
            files.removeAll { it.fileKey == fileKey }
            return Result.success(Unit)
        }
    }

    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var fileManager: FakeFileManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsStore = FakeSettingsStore()
        fileManager = FakeFileManager()
        viewModel = SettingsViewModel(settingsStore, fileManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_hasExpectedDefaultsAndMetadata() = runTest(testDispatcher) {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("1.0.0", state.appVersionName)
        assertEquals("1", state.appBuildNumber)
        assertFalse(state.isNearbyDiagnosticsOpen)
        assertFalse(state.isLicensesDialogOpen)
        assertFalse(state.isPrivacyPolicyDialogOpen)
        assertNotNull(state.nearbyDiagnostics)
        assertTrue(state.nearbyDiagnostics.bluetoothEnabled)
        assertTrue(state.nearbyDiagnostics.wifiEnabled)
        assertTrue(state.nearbyDiagnostics.permissionsGranted)
    }

    @Test
    fun deviceNameValidation_handlesValidAndInvalidInputs() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.startEditDeviceName()
        assertTrue(viewModel.uiState.value.isEditingDeviceName)

        // Invalid empty name
        viewModel.onDeviceNameInputChange("   ")
        assertNotNull(viewModel.uiState.value.deviceNameError)

        // Invalid long name (>32 chars)
        viewModel.onDeviceNameInputChange("A".repeat(33))
        assertNotNull(viewModel.uiState.value.deviceNameError)

        // Valid name
        viewModel.onDeviceNameInputChange("Pixel 10 Pro")
        assertNull(viewModel.uiState.value.deviceNameError)
        viewModel.saveDeviceName()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEditingDeviceName)
        assertEquals("Pixel 10 Pro", viewModel.uiState.value.settings.deviceName)
    }

    @Test
    fun themeAndAppearancePreferences_updateSuccessfully() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setTheme(ThemePreference.DARK)
        advanceUntilIdle()
        assertEquals(ThemePreference.DARK, viewModel.uiState.value.settings.theme)

        viewModel.setDynamicColor(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.settings.dynamicColor)

        viewModel.setReducedMotion(ReducedMotionPreference.ON)
        advanceUntilIdle()
        assertEquals(ReducedMotionPreference.ON, viewModel.uiState.value.settings.reducedMotion)
    }

    @Test
    fun privacyAndBehaviorToggles_updateCorrectly() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setNotificationDetails(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.settings.notificationDetails)

        viewModel.setKeepScreenAwake(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.settings.keepScreenAwake)
    }

    @Test
    fun storageManagement_protectsActiveTransfersDuringDeleteAll() = runTest(testDispatcher) {
        fileManager.files.add(ReceivedFileInfo("key1", "document.pdf", 1_000_000L, 1000L, isActive = false))
        fileManager.files.add(ReceivedFileInfo("key2", "active_in_flight.zip", 5_000_000L, 1000L, isActive = true))

        viewModel.refreshStorageSummary()
        advanceUntilIdle()

        var summary = viewModel.uiState.value.storageSummary
        assertEquals(2, summary.totalFiles)
        assertEquals(6_000_000L, summary.totalBytes)
        assertEquals(1, summary.activeFiles)

        // Open delete confirmation
        viewModel.requestDeleteAllFiles()
        assertTrue(viewModel.uiState.value.isDeleteAllConfirmOpen)

        // Confirm delete
        viewModel.confirmDeleteAllFiles()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDeleteAllConfirmOpen)
        assertEquals(1, fileManager.deleteCallCount)

        // Active file preserved
        summary = viewModel.uiState.value.storageSummary
        assertEquals(1, summary.totalFiles)
        assertEquals(5_000_000L, summary.totalBytes)
        assertEquals("active_in_flight.zip", fileManager.files[0].displayName)
    }

    @Test
    fun nearbyDiagnosticsDialog_opensAndDismissesWithAccurateState() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isNearbyDiagnosticsOpen)

        viewModel.showNearbyDiagnostics()
        assertTrue(viewModel.uiState.value.isNearbyDiagnosticsOpen)

        val diag = viewModel.uiState.value.nearbyDiagnostics
        assertTrue(diag.bluetoothEnabled)
        assertTrue(diag.wifiEnabled)
        assertTrue(diag.permissionsGranted)
        assertEquals("2.4 GHz / 5 GHz Dual Band", diag.radioBand)
        assertEquals("Nearby Connections v1.0 (P2P Direct)", diag.gmsVersion)

        viewModel.dismissNearbyDiagnostics()
        assertFalse(viewModel.uiState.value.isNearbyDiagnosticsOpen)
    }

    @Test
    fun openSourceLicensesDialog_opensAndDismisses() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLicensesDialogOpen)

        viewModel.showLicensesDialog()
        assertTrue(viewModel.uiState.value.isLicensesDialogOpen)

        viewModel.dismissLicensesDialog()
        assertFalse(viewModel.uiState.value.isLicensesDialogOpen)
    }

    @Test
    fun privacyPolicyDialog_opensAndDismisses() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPrivacyPolicyDialogOpen)

        viewModel.showPrivacyPolicyDialog()
        assertTrue(viewModel.uiState.value.isPrivacyPolicyDialogOpen)

        viewModel.dismissPrivacyPolicyDialog()
        assertFalse(viewModel.uiState.value.isPrivacyPolicyDialogOpen)
    }
}
