package dev.glide.app.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestPermissionTest {

    private fun findManifest(): File {
        // Find app source manifest
        val sourceManifest = File("src/main/AndroidManifest.xml")
        if (sourceManifest.exists()) return sourceManifest

        val moduleManifest = File("android/app/src/main/AndroidManifest.xml")
        if (moduleManifest.exists()) return moduleManifest

        var current: File? = File(".").canonicalFile
        while (current != null) {
            val candidate = File(current, "android/app/src/main/AndroidManifest.xml")
            if (candidate.exists()) return candidate
            current = current.parentFile
        }

        throw IllegalStateException("AndroidManifest.xml not found from current working directory: ${File(".").absolutePath}")
    }

    @Test
    fun manifest_declares_all_required_nearby_permissions() {
        val manifestContent = findManifest().readText()

        assertTrue(manifestContent.contains("android.permission.INTERNET"))
        assertTrue(manifestContent.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(manifestContent.contains("android.permission.BLUETOOTH"))
        assertTrue(manifestContent.contains("android.permission.BLUETOOTH_ADMIN"))
        assertTrue(manifestContent.contains("android.permission.ACCESS_WIFI_STATE"))
        assertTrue(manifestContent.contains("android.permission.CHANGE_WIFI_STATE"))
        assertTrue(manifestContent.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(manifestContent.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(manifestContent.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(manifestContent.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertTrue(manifestContent.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifestContent.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(manifestContent.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(manifestContent.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifestContent.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifestContent.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    }

    @Test
    fun manifest_uses_neverForLocation_flag() {
        val manifestContent = findManifest().readText()

        assertTrue(manifestContent.contains("neverForLocation"))
    }

    @Test
    fun manifest_strictly_forbids_broad_storage_and_sensitive_permissions() {
        val manifestContent = findManifest().readText()

        // Storage permissions forbidden per ADR-0007 / PERMISSION_MATRIX.md
        assertFalse(manifestContent.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifestContent.contains("WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifestContent.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifestContent.contains("READ_MEDIA_IMAGES"))
        assertFalse(manifestContent.contains("READ_MEDIA_VIDEO"))
        assertFalse(manifestContent.contains("READ_MEDIA_AUDIO"))

        // Sensitive / unused permissions forbidden
        assertFalse(manifestContent.contains("ACCESS_BACKGROUND_LOCATION"))
        assertFalse(manifestContent.contains("RECORD_AUDIO"))
        assertFalse(manifestContent.contains("READ_CONTACTS"))
        assertFalse(manifestContent.contains("RECEIVE_BOOT_COMPLETED"))

        // Camera is permitted only for QR scanning, and camera hardware MUST be optional
        assertTrue(manifestContent.contains("android.permission.CAMERA"))
        assertTrue(manifestContent.contains("""android:name="android.hardware.camera""""))
        assertTrue(manifestContent.contains("""android:required="false""""))
    }
}
