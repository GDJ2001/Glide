package dev.glide.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideBluetoothApkShareTest {
    @Test
    fun apkFileNameIsStrictlyApkAndNeverZip() {
        val name = glideBluetoothApkFileName("1.4.2-beta+01")
        assertTrue(name.startsWith("Glide_v"))
        assertTrue(name.endsWith(".apk"))
        assertFalse(name.endsWith(".zip"))
    }

    @Test
    fun matchesBluetoothShareHandlersAcrossAospGoogleAndOems() {
        assertTrue(isBluetoothShareHandler("com.android.bluetooth", "com.android.bluetooth.opp.BluetoothOppLauncherActivity"))
        assertTrue(isBluetoothShareHandler("com.google.android.bluetooth", "com.android.bluetooth.opp.BluetoothOppLauncherActivity"))
        assertTrue(isBluetoothShareHandler("com.samsung.android.app.sharelive", "com.samsung.bluetooth.OppActivity"))
        assertFalse(isBluetoothShareHandler("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher"))
    }
}
