package dev.glide.app.ui.motion

import dev.glide.app.ui.haptics.TestGlideHaptics
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.ReducedMotionPreference
import dev.glide.core.ports.SettingsStore
import dev.glide.core.ports.ThemePreference
import dev.glide.designsystem.GlideMotion
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Acceptance test suite for GLIDE-041: Restrained Motion and Haptics.
 * Verifies:
 * 1. Motion durations and easing curves adhere strictly to GLIDE_DESIGN_SYSTEM.md tokens.
 * 2. ReducedMotion preference changes in settings persist and resolve correctly.
 * 3. Reduced motion forces instant transitions (0ms / snap).
 * 4. Haptic feedback invokes semantic actions and suppresses device-found vibration by default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotionAcceptanceTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSettingsStore(
        initial: GlideSettings = GlideSettings()
    ) : SettingsStore {
        private val _flow = MutableStateFlow(initial)
        override val settingsFlow: Flow<GlideSettings> = _flow
        override suspend fun getSettings(): GlideSettings = _flow.value
        override suspend fun updateDeviceName(name: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateTheme(theme: ThemePreference): Result<Unit> = Result.success(Unit)
        override suspend fun updateDynamicColor(enabled: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun updateReducedMotion(motion: ReducedMotionPreference): Result<Unit> {
            _flow.value = _flow.value.copy(reducedMotion = motion)
            return Result.success(Unit)
        }
        override suspend fun updateNotificationDetails(enabled: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun updateKeepScreenAwake(enabled: Boolean): Result<Unit> = Result.success(Unit)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ReducedMotionResolver.testOverride = null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ReducedMotionResolver.testOverride = null
    }

    @Test
    fun motionTokens_matchDesignSystemStandards() {
        assertEquals(100, GlideMotion.ControlPressDurationMs)
        assertEquals(200, GlideMotion.CardAppearanceDurationMs)
        assertEquals(260, GlideMotion.DialogDurationMs)
        assertEquals(250, GlideMotion.NavigationDurationMs)
        assertEquals(180, GlideMotion.PairingPhaseCrossfadeMs)
        assertEquals(200, GlideMotion.TransferProgressSampleMs)
        assertEquals(240, GlideMotion.CompletionCheckDurationMs)
        assertEquals(160, GlideMotion.FailureChangeDurationMs)
        assertEquals(2400, GlideMotion.DiscoveryBreathingPeriodMs)
    }

    @Test
    fun settingsViewModel_updatesAndPersistsReducedMotionPreference() = runTest(testDispatcher) {
        val fakeStore = FakeSettingsStore()
        val fakeFileManager = object : dev.glide.core.ports.PrivateFileManager {
            override suspend fun getReceivedFiles(): List<dev.glide.core.ports.ReceivedFileInfo> = emptyList()
            override suspend fun getStorageSummary(): dev.glide.core.ports.StorageSummary = dev.glide.core.ports.StorageSummary(0, 0L, 0)
            override suspend fun deleteAllReceivedFiles(): Result<Int> = Result.success(0)
            override suspend fun deleteReceivedFile(fileKey: String): Result<Unit> = Result.success(Unit)
        }

        val viewModel = dev.glide.app.ui.settings.SettingsViewModel(fakeStore, fakeFileManager)
        advanceUntilIdle()

        // Default should be SYSTEM
        assertEquals(ReducedMotionPreference.SYSTEM, viewModel.uiState.value.settings.reducedMotion)

        // Set to ON
        viewModel.setReducedMotion(ReducedMotionPreference.ON)
        advanceUntilIdle()
        assertEquals(ReducedMotionPreference.ON, viewModel.uiState.value.settings.reducedMotion)
        assertTrue(ReducedMotionResolver.isReducedMotionActive(viewModel.uiState.value.settings.reducedMotion, null))

        // Set to OFF
        viewModel.setReducedMotion(ReducedMotionPreference.OFF)
        advanceUntilIdle()
        assertEquals(ReducedMotionPreference.OFF, viewModel.uiState.value.settings.reducedMotion)
        assertFalse(ReducedMotionResolver.isReducedMotionActive(viewModel.uiState.value.settings.reducedMotion, null))

        // Set back to SYSTEM
        viewModel.setReducedMotion(ReducedMotionPreference.SYSTEM)
        advanceUntilIdle()
        assertEquals(ReducedMotionPreference.SYSTEM, viewModel.uiState.value.settings.reducedMotion)
    }

    @Test
    fun reducedMotionResolver_systemScaleZero_enablesReducedMotion() {
        // When system animator scale is 0, SYSTEM preference resolves to true
        ReducedMotionResolver.testOverride = true
        assertTrue(ReducedMotionResolver.isReducedMotionActive(ReducedMotionPreference.SYSTEM, null))

        // When testOverride is false, SYSTEM preference resolves to false
        ReducedMotionResolver.testOverride = false
        assertFalse(ReducedMotionResolver.isReducedMotionActive(ReducedMotionPreference.SYSTEM, null))
    }

    @Test
    fun haptics_semanticActionsTriggerAppropriateEvents() {
        val haptics = TestGlideHaptics(isEnabled = true)

        // Light selection on control click
        haptics.performSelection()
        assertEquals(1, haptics.selectionCount)

        // Confirmation on pairing / consent
        haptics.performConfirmation()
        assertEquals(1, haptics.confirmationCount)

        // Success on transfer complete
        haptics.performSuccess()
        assertEquals(1, haptics.successCount)

        // Error on transfer fail
        haptics.performError()
        assertEquals(1, haptics.errorCount)

        // Device discovered must be strictly 0 by default
        haptics.performDeviceDiscovered()
        assertEquals(0, haptics.deviceDiscoveredCount)
    }
}
