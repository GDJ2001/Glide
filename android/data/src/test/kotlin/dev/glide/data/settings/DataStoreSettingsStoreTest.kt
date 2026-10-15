package dev.glide.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.ReducedMotionPreference
import dev.glide.core.ports.ThemePreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createStore(scope: TestScope): Pair<DataStoreSettingsStore, () -> Unit> {
        val testFile = java.io.File(tempFolder.root, "test_settings_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile }
        )
        val store = DataStoreSettingsStore(dataStore, testDispatcher)
        return store to { if (testFile.exists()) testFile.delete() }
    }

    @Test
    fun `default settings are returned initially`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val settings = store.getSettings()
            assertEquals(GlideSettings.DEFAULT_DEVICE_NAME, settings.deviceName)
            assertEquals(ThemePreference.SYSTEM, settings.theme)
            assertFalse(settings.dynamicColor)
            assertEquals(ReducedMotionPreference.SYSTEM, settings.reducedMotion)
            assertFalse(settings.notificationDetails)
            assertFalse(settings.keepScreenAwake)
            assertEquals(null, settings.receivedFilesTreeUri)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating device name persists and emits new value`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val result = store.updateDeviceName("Pixel 10 Pro")
            assertTrue(result.isSuccess)

            val updated = store.getSettings()
            assertEquals("Pixel 10 Pro", updated.deviceName)

            val fromFlow = store.settingsFlow.first()
            assertEquals("Pixel 10 Pro", fromFlow.deviceName)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `received files folder persists and can return to Download Glide default`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FGlide"
            assertTrue(store.updateReceivedFilesTreeUri(tree).isSuccess)
            assertEquals(tree, store.getSettings().receivedFilesTreeUri)
            assertTrue(store.updateReceivedFilesTreeUri("file:///unsafe").isFailure)
            assertEquals(tree, store.getSettings().receivedFilesTreeUri)
            assertTrue(store.updateReceivedFilesTreeUri(null).isSuccess)
            assertEquals(null, store.getSettings().receivedFilesTreeUri)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating device name with invalid or long input fails`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val longName = "A".repeat(33)
            val resultLong = store.updateDeviceName(longName)
            assertTrue(resultLong.isFailure)
            assertTrue(resultLong.exceptionOrNull() is IllegalArgumentException)

            val resultBlank = store.updateDeviceName("   ")
            assertTrue(resultBlank.isFailure)
            assertTrue(resultBlank.exceptionOrNull() is IllegalArgumentException)

            // Value remains default
            assertEquals(GlideSettings.DEFAULT_DEVICE_NAME, store.getSettings().deviceName)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating theme preference persists`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val res1 = store.updateTheme(ThemePreference.DARK)
            assertTrue("res1 should succeed: ${res1.exceptionOrNull()}", res1.isSuccess)
            assertEquals(ThemePreference.DARK, store.getSettings().theme)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating dynamic color persists`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val result = store.updateDynamicColor(true)
            assertTrue(result.isSuccess)
            assertTrue(store.getSettings().dynamicColor)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating reduced motion persists`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val result = store.updateReducedMotion(ReducedMotionPreference.ON)
            assertTrue(result.isSuccess)
            assertEquals(ReducedMotionPreference.ON, store.getSettings().reducedMotion)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating notification details persists`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val result = store.updateNotificationDetails(true)
            assertTrue(result.isSuccess)
            assertTrue(store.getSettings().notificationDetails)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `updating keep screen awake persists`() = runTest(testDispatcher) {
        val (store, cleanup) = createStore(this)
        try {
            val result = store.updateKeepScreenAwake(true)
            assertTrue(result.isSuccess)
            assertTrue(store.getSettings().keepScreenAwake)
        } finally {
            cleanup()
        }
    }
}
