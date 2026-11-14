package dev.glide.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.glide.core.ports.DeviceNameValidator
import dev.glide.core.ports.GlideSettings
import dev.glide.core.ports.ReducedMotionPreference
import dev.glide.core.ports.SettingsStore
import dev.glide.core.ports.ThemePreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Production implementation of [SettingsStore] using AndroidX DataStore Preferences.
 *
 * Implements specifications in docs/architecture/STATE_AND_DATA_MODEL.md:
 * - Defaults: deviceName="Glide device", theme=SYSTEM, dynamicColor=false,
 *   reducedMotion=SYSTEM, notificationDetails=false, keepScreenAwake=false.
 * - Settings writes expose failure; no silent success on disk error.
 * - Device names validated via [DeviceNameValidator].
 */
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SettingsStore {

    companion object {
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_REDUCED_MOTION = stringPreferencesKey("reduced_motion")
        val KEY_NOTIFICATION_DETAILS = booleanPreferencesKey("notification_details")
        val KEY_KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val KEY_KEEP_RECEIVING_IN_BACKGROUND = booleanPreferencesKey("keep_receiving_in_background")
        val KEY_RECEIVED_FILES_TREE_URI = stringPreferencesKey("received_files_tree_uri")
    }

    override val settingsFlow: Flow<GlideSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapPreferencesToSettings(preferences)
        }

    override suspend fun getSettings(): GlideSettings = withContext(ioDispatcher) {
        settingsFlow.first()
    }

    override suspend fun updateDeviceName(name: String): Result<Unit> = withContext(ioDispatcher) {
        val validation = DeviceNameValidator.validate(name)
        if (validation.isFailure) {
            return@withContext Result.failure(
                validation.exceptionOrNull() ?: IllegalArgumentException("Invalid device name")
            )
        }
        val validatedName = validation.getOrThrow()

        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_DEVICE_NAME] = validatedName
            }
            Unit
        }
    }

    override suspend fun updateTheme(theme: ThemePreference): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_THEME] = theme.name
            }
            Unit
        }
    }

    override suspend fun updateDynamicColor(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_DYNAMIC_COLOR] = enabled
            }
            Unit
        }
    }

    override suspend fun updateReducedMotion(motion: ReducedMotionPreference): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_REDUCED_MOTION] = motion.name
            }
            Unit
        }
    }

    override suspend fun updateNotificationDetails(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_NOTIFICATION_DETAILS] = enabled
            }
            Unit
        }
    }

    override suspend fun updateKeepScreenAwake(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_KEEP_SCREEN_AWAKE] = enabled
            }
            Unit
        }
    }

    override suspend fun updateKeepReceivingInBackground(enabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[KEY_KEEP_RECEIVING_IN_BACKGROUND] = enabled
            }
            Unit
        }
    }

    override suspend fun updateReceivedFilesTreeUri(uri: String?): Result<Unit> = withContext(ioDispatcher) {
        if (uri != null && (!uri.startsWith("content://com.android.externalstorage.documents/") || uri.length > 4096)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid folder selection"))
        }
        runCatching {
            dataStore.edit { preferences ->
                if (uri == null) preferences.remove(KEY_RECEIVED_FILES_TREE_URI)
                else preferences[KEY_RECEIVED_FILES_TREE_URI] = uri
            }
            Unit
        }
    }

    private fun mapPreferencesToSettings(preferences: Preferences): GlideSettings {
        val deviceName = preferences[KEY_DEVICE_NAME] ?: GlideSettings.DEFAULT_DEVICE_NAME

        val themeStr = preferences[KEY_THEME]
        val theme = themeStr?.let {
            runCatching { ThemePreference.valueOf(it) }.getOrDefault(ThemePreference.SYSTEM)
        } ?: ThemePreference.SYSTEM

        val dynamicColor = preferences[KEY_DYNAMIC_COLOR] ?: false

        val motionStr = preferences[KEY_REDUCED_MOTION]
        val reducedMotion = motionStr?.let {
            runCatching { ReducedMotionPreference.valueOf(it) }.getOrDefault(ReducedMotionPreference.SYSTEM)
        } ?: ReducedMotionPreference.SYSTEM

        val notificationDetails = preferences[KEY_NOTIFICATION_DETAILS] ?: false
        val keepScreenAwake = preferences[KEY_KEEP_SCREEN_AWAKE] ?: false

        return GlideSettings(
            deviceName = deviceName,
            theme = theme,
            dynamicColor = dynamicColor,
            reducedMotion = reducedMotion,
            notificationDetails = notificationDetails,
            keepScreenAwake = keepScreenAwake,
            keepReceivingInBackground = preferences[KEY_KEEP_RECEIVING_IN_BACKGROUND] ?: false,
            receivedFilesTreeUri = preferences[KEY_RECEIVED_FILES_TREE_URI]
        )
    }
}
