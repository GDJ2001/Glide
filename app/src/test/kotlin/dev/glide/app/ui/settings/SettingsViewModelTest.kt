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
import java.io.FileNotFoundException

class FakeTestSettingsStore(
    initialSettings: GlideSettings = GlideSettings()
) : SettingsStore {
    private val _settingsState = MutableStateFlow(initialSettings)
    override val settingsFlow: Flow<GlideSettings> = _settingsState

    var shouldFailWrites: Boolean = false

    override suspend fun getSettings(): GlideSettings = _settingsState.value

    override suspend fun updateDeviceName(name: String): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        val validation = DeviceNameValidator.validate(name)
        return validation.map { validatedName ->
            _settingsState.value = _settingsState.value.copy(deviceName = validatedName)
        }
    }

    override suspend fun updateTheme(theme: ThemePreference): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(theme = theme)
        return Result.success(Unit)
    }

    override suspend fun updateDynamicColor(enabled: Boolean): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(dynamicColor = enabled)
        return Result.success(Unit)
    }

    override suspend fun updateReducedMotion(motion: ReducedMotionPreference): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(reducedMotion = motion)
        return Result.success(Unit)
    }

    override suspend fun updateNotificationDetails(enabled: Boolean): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(notificationDetails = enabled)
        return Result.success(Unit)
    }

    override suspend fun updateKeepScreenAwake(enabled: Boolean): Result<Unit> {
        if (shouldFailWrites) return Result.failure(java.io.IOException("Disk write failure"))
        _settingsState.value = _settingsState.value.copy(keepScreenAwake = enabled)
        return Result.success(Unit)
    }
}

class FakeTestPrivateFileManager(
    initialFiles: List<ReceivedFileInfo> = emptyList()
) : PrivateFileManager {
    private val files = initialFiles.toMutableList()

    fun setFiles(newFiles: List<ReceivedFileInfo>) {
        files.clear()
        files.addAll(newFiles)
    }

    override suspend fun getStorageSummary(): StorageSummary {
        val totalFiles = files.size
        val totalBytes = files.sumOf { it.sizeBytes }
        val activeFiles = files.count { it.isActive }
        return StorageSummary(
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            activeFiles = activeFiles
        )
    }

    override suspend fun getReceivedFiles(): List<ReceivedFileInfo> {
        return files.toList()
    }

    override suspend fun deleteReceivedFile(fileKey: String): Result<Unit> {
        val file = files.find { it.fileKey == fileKey }
            ?: return Result.failure(FileNotFoundException("File not found: $fileKey"))
        if (file.isActive) {
            return Result.failure(IllegalStateException("Cannot delete active file: ${file.displayName}"))
        }
        files.remove(file)
        return Result.success(Unit)
    }

    override suspend fun deleteAllReceivedFiles(): Result<Int> {
        val inactive = files.filter { !it.isActive }
        val count = inactive.size
        files.removeAll(inactive)
        return Result.success(count)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsStore: FakeTestSettingsStore
    private lateinit var fileManager: FakeTestPrivateFileManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsStore = FakeTestSettingsStore()
        fileManager = FakeTestPrivateFileManager(
            initialFiles = listOf(
                ReceivedFileInfo("key1", "photo.jpg", 1024L, 1000L, isActive = false),
                ReceivedFileInfo("key2", "doc.pdf", 2048L, 2000L, isActive = false)
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(settingsStore, fileManager)
    }

    @Test
    fun `initial state loads settings and storage summary`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(GlideSettings.DEFAULT_DEVICE_NAME, state.settings.deviceName)
        assertEquals(ThemePreference.SYSTEM, state.settings.theme)
        assertEquals(2, state.storageSummary.totalFiles)
        assertEquals(3072L, state.storageSummary.totalBytes)
        assertEquals(0, state.storageSummary.activeFiles)
    }

    @Test
    fun `editing device name updates state and persists valid name`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startEditDeviceName()
        assertTrue(vm.uiState.value.isEditingDeviceName)
        assertEquals("Glide device", vm.uiState.value.deviceNameInput)

        vm.onDeviceNameInputChange("Pixel 10 Pro")
        assertEquals("Pixel 10 Pro", vm.uiState.value.deviceNameInput)
        assertNull(vm.uiState.value.deviceNameError)

        vm.saveDeviceName()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isEditingDeviceName)
        assertEquals("Pixel 10 Pro", vm.uiState.value.settings.deviceName)
        assertEquals("Pixel 10 Pro", settingsStore.getSettings().deviceName)
        assertNotNull(vm.uiState.value.feedbackMessage)
    }

    @Test
    fun `invalid device name shows error and does not save`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startEditDeviceName()

        // 1. Blank name
        vm.onDeviceNameInputChange("   ")
        assertNotNull(vm.uiState.value.deviceNameError)
        vm.saveDeviceName()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEditingDeviceName) // Dialog stays open
        assertEquals("Glide device", settingsStore.getSettings().deviceName)

        // 2. Name exceeding 32 chars
        val longName = "A".repeat(33)
        vm.onDeviceNameInputChange(longName)
        assertNotNull(vm.uiState.value.deviceNameError)
        vm.saveDeviceName()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEditingDeviceName)
        assertEquals("Glide device", settingsStore.getSettings().deviceName)

        // Cancel resets dialog
        vm.cancelEditDeviceName()
        assertFalse(vm.uiState.value.isEditingDeviceName)
    }

    @Test
    fun `updating theme and toggles updates preferences`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setTheme(ThemePreference.DARK)
        advanceUntilIdle()
        assertEquals(ThemePreference.DARK, settingsStore.getSettings().theme)

        vm.setDynamicColor(true)
        advanceUntilIdle()
        assertTrue(settingsStore.getSettings().dynamicColor)

        vm.setReducedMotion(ReducedMotionPreference.ON)
        advanceUntilIdle()
        assertEquals(ReducedMotionPreference.ON, settingsStore.getSettings().reducedMotion)

        vm.setNotificationDetails(true)
        advanceUntilIdle()
        assertTrue(settingsStore.getSettings().notificationDetails)

        vm.setKeepScreenAwake(true)
        advanceUntilIdle()
        assertTrue(settingsStore.getSettings().keepScreenAwake)
    }

    @Test
    fun `delete all files dialog open and dismiss does not delete files`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.requestDeleteAllFiles()
        assertTrue(vm.uiState.value.isDeleteAllConfirmOpen)

        vm.dismissDeleteAllFilesDialog()
        assertFalse(vm.uiState.value.isDeleteAllConfirmOpen)

        // Files remain intact
        assertEquals(2, fileManager.getReceivedFiles().size)
    }

    @Test
    fun `confirm delete all files deletes inactive files and updates summary`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.requestDeleteAllFiles()
        vm.confirmDeleteAllFiles()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isDeleteAllConfirmOpen)
        assertFalse(vm.uiState.value.isDeleting)
        assertEquals(0, vm.uiState.value.storageSummary.totalFiles)
        assertEquals(0L, vm.uiState.value.storageSummary.totalBytes)
        assertEquals(0, fileManager.getReceivedFiles().size)
        assertTrue(vm.uiState.value.feedbackMessage?.contains("Deleted 2") == true)
    }

    @Test
    fun `confirm delete all files preserves active transfer files`() = runTest(testDispatcher) {
        fileManager.setFiles(
            listOf(
                ReceivedFileInfo("key1", "done.txt", 100L, 1000L, isActive = false),
                ReceivedFileInfo("key2", "active.bin", 500L, 2000L, isActive = true)
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.storageSummary.totalFiles)
        assertEquals(1, vm.uiState.value.storageSummary.activeFiles)

        vm.confirmDeleteAllFiles()
        advanceUntilIdle()

        // 1 active file remains
        assertEquals(1, fileManager.getReceivedFiles().size)
        assertEquals("active.bin", fileManager.getReceivedFiles()[0].displayName)
        assertEquals(1, vm.uiState.value.storageSummary.totalFiles)
        assertTrue(vm.uiState.value.feedbackMessage?.contains("active transfer files were preserved") == true)
    }

    @Test
    fun `trusted devices dialog open and dismiss toggles state`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isTrustedDevicesDialogOpen)

        vm.showTrustedDevicesDialog()
        assertTrue(vm.uiState.value.isTrustedDevicesDialogOpen)

        vm.dismissTrustedDevicesDialog()
        assertFalse(vm.uiState.value.isTrustedDevicesDialogOpen)
    }

    @Test
    fun `observing trusted peers updates ui state and revoking peer deletes from dao`() = runTest(testDispatcher) {
        val peer = dev.glide.data.db.TrustedPeerEntity(
            peerFingerprint = "abcd1234ef567890abcd1234ef567890abcd1234ef567890abcd1234ef567890",
            publicKeyDer = "3059301306072a8648ce3d020106082a8648ce3d03010703420004",
            displayName = "Pixel 8 Pro",
            pairedAtMs = 1700000000000L,
            lastSeenAtMs = 1700000000000L
        )
        val peerDao = FakeTestTrustedPeerDao(listOf(peer))
        val vm = SettingsViewModel(settingsStore, fileManager, peerDao)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.trustedPeers.size)
        assertEquals("Pixel 8 Pro", vm.uiState.value.trustedPeers[0].displayName)

        vm.revokeTrustedPeer(peer.peerFingerprint)
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.trustedPeers.size)
        assertEquals("Device trust revoked", vm.uiState.value.feedbackMessage)
        assertEquals(0, peerDao.getPeerCount())
    }
    @Test
    fun `production settings trust uses runtime authority instead of dao`() =
        runTest(testDispatcher) {
            val peer = dev.glide.data.db.TrustedPeerEntity(
                peerFingerprint =
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                publicKeyDer = "3059",
                displayName = "Trusted Laptop",
                pairedAtMs = 1_000L,
                lastSeenAtMs = 2_000L
            )
            val peerDao =
                FakeTestTrustedPeerDao(
                    listOf(peer)
                )
            val identity =
                dev.glide.data.identity.JvmDeviceIdentityKeyStore()
            identity.getFingerprint()
            val trustManager =
                dev.glide.core.identity.PersistentTrustManager(
                    identityStore = identity,
                    trustedPeerStore =
                        dev.glide.data.identity.RoomTrustedPeerStore(
                            peerDao
                        ),
                    nowMs = { 5_000L }
                )
            val runtime =
                dev.glide.app.service.TransferRuntime.forTesting(
                    persistentTrustManager =
                        trustManager
                )
            val vm = SettingsViewModel(
                settingsStore = settingsStore,
                fileManager = fileManager,
                trustedPeerDao = null,
                transferRuntime = runtime
            )

            advanceUntilIdle()
            assertEquals(
                1,
                vm.uiState.value.trustedPeers.size
            )
            assertEquals(
                "Trusted Laptop",
                vm.uiState.value.trustedPeers.single().displayName
            )

            vm.revokeTrustedPeer(peer.peerFingerprint)
            advanceUntilIdle()

            assertEquals(
                0,
                peerDao.getPeerCount()
            )
            assertTrue(
                vm.uiState.value.trustedPeers.isEmpty()
            )
            assertEquals(
                "Device trust revoked",
                vm.uiState.value.feedbackMessage
            )
        }
}

class FakeTestTrustedPeerDao(
    initialPeers: List<dev.glide.data.db.TrustedPeerEntity> = emptyList()
) : dev.glide.data.db.TrustedPeerDao {
    private val peersFlow = MutableStateFlow(initialPeers)

    override suspend fun insertOrUpdate(peer: dev.glide.data.db.TrustedPeerEntity) {
        val current = peersFlow.value.filter { it.peerFingerprint != peer.peerFingerprint }.toMutableList()
        current.add(peer)
        peersFlow.value = current
    }

    override suspend fun getByFingerprint(fingerprint: String): dev.glide.data.db.TrustedPeerEntity? {
        return peersFlow.value.find { it.peerFingerprint == fingerprint }
    }

    override suspend fun getAllPeers(): List<dev.glide.data.db.TrustedPeerEntity> = peersFlow.value

    override fun observeAllPeers(): Flow<List<dev.glide.data.db.TrustedPeerEntity>> = peersFlow

    override suspend fun updateLastSeen(fingerprint: String, lastSeenAtMs: Long) {
        val current = peersFlow.value.toMutableList()
        val index = current.indexOfFirst { it.peerFingerprint == fingerprint }
        if (index >= 0) {
            current[index] = current[index].copy(lastSeenAtMs = lastSeenAtMs)
            peersFlow.value = current
        }
    }

    override suspend fun deletePeer(peer: dev.glide.data.db.TrustedPeerEntity) {
        deleteByFingerprint(peer.peerFingerprint)
    }

    override suspend fun deleteByFingerprint(fingerprint: String) {
        peersFlow.value = peersFlow.value.filter { it.peerFingerprint != fingerprint }
    }

    override suspend fun deleteAll() {
        peersFlow.value = emptyList()
    }

    override suspend fun getPeerCount(): Int = peersFlow.value.size
}

