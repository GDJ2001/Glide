package dev.glide.core.ports

import kotlinx.coroutines.flow.Flow
import java.text.Normalizer

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ReducedMotionPreference {
    SYSTEM,
    ON,
    OFF
}

data class GlideSettings(
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColor: Boolean = false,
    val reducedMotion: ReducedMotionPreference = ReducedMotionPreference.SYSTEM,
    val notificationDetails: Boolean = false,
    val keepScreenAwake: Boolean = false,
    /** Persisted SAF tree grant for an optional public save destination. Null uses Download/Glide. */
    val receivedFilesTreeUri: String? = null
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "Glide device"
        const val MAX_DEVICE_NAME_LENGTH = 32
    }
}

object DeviceNameValidator {
    private val BIDI_AND_CONTROL_REGEX = Regex("[\\p{Cntrl}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /**
     * Validates and normalizes a candidate device name.
     * - Normalizes to Unicode NFC.
     * - Strips bidirectional and ISO control characters.
     * - Trims leading and trailing whitespace.
     * - Enforces length between 1 and 32 characters.
     *
     * @return Result.success with normalized name, or Result.failure with descriptive exception.
     */
    fun validate(input: String?): Result<String> {
        if (input == null) {
            return Result.failure(IllegalArgumentException("Device name cannot be null"))
        }

        // 1. NFC normalization
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFC)

        // 2. Strip bidi control and ISO control characters
        val stripped = normalized.replace(BIDI_AND_CONTROL_REGEX, "")

        // 3. Trim whitespace
        val trimmed = stripped.trim()

        // 4. Validate non-empty
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Device name cannot be blank"))
        }

        // 5. Length constraint
        if (trimmed.length > GlideSettings.MAX_DEVICE_NAME_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Device name cannot exceed ${GlideSettings.MAX_DEVICE_NAME_LENGTH} characters (was ${trimmed.length})")
            )
        }

        return Result.success(trimmed)
    }
}

interface SettingsStore {
    val settingsFlow: Flow<GlideSettings>
    suspend fun getSettings(): GlideSettings
    suspend fun updateDeviceName(name: String): Result<Unit>
    suspend fun updateTheme(theme: ThemePreference): Result<Unit>
    suspend fun updateDynamicColor(enabled: Boolean): Result<Unit>
    suspend fun updateReducedMotion(motion: ReducedMotionPreference): Result<Unit>
    suspend fun updateNotificationDetails(enabled: Boolean): Result<Unit>
    suspend fun updateKeepScreenAwake(enabled: Boolean): Result<Unit>
    suspend fun updateReceivedFilesTreeUri(uri: String?): Result<Unit> =
        Result.failure(UnsupportedOperationException("Custom receive folders are unavailable"))
}

data class ReceivedFileInfo(
    val fileKey: String,
    val displayName: String,
    val sizeBytes: Long,
    val receivedAtMs: Long,
    val isActive: Boolean = false
)

data class StorageSummary(
    val totalFiles: Int,
    val totalBytes: Long,
    val activeFiles: Int
)

interface PrivateFileManager {
    suspend fun getStorageSummary(): StorageSummary
    suspend fun getReceivedFiles(): List<ReceivedFileInfo>
    suspend fun deleteReceivedFile(fileKey: String): Result<Unit>
    suspend fun deleteAllReceivedFiles(): Result<Int>
}
