package dev.glide.app.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPackageInstallerComparisonTest {
    private val signerA = "a".repeat(64)
    private val signerB = "b".repeat(64)

    @Test
    fun evaluatesAllFiveReceiverComparisonStatesAndAbiConflict() {
        val newApp = AppPackageInstaller.evaluateOfferComparison(
            offeredVersionName = "1.2.0",
            offeredVersionCode = 12,
            offeredSignerHex = signerA,
            offeredAbis = listOf("arm64-v8a"),
            supportedAbis = arrayOf("arm64-v8a"),
            installedVersionName = null,
            installedVersionCode = null,
            installedCompatibleSigners = emptySet()
        )
        assertEquals(AppVersionState.NewApp, newApp.state)
        assertEquals("New app · v1.2.0", newApp.badge)
        assertFalse(newApp.isWarning)

        val update = AppPackageInstaller.evaluateOfferComparison(
            offeredVersionName = "2.0.0",
            offeredVersionCode = 20,
            offeredSignerHex = signerA,
            offeredAbis = emptyList(),
            supportedAbis = arrayOf("arm64-v8a"),
            installedVersionName = "1.0.0",
            installedVersionCode = 10,
            installedCompatibleSigners = setOf(signerA)
        )
        assertEquals(AppVersionState.UpdateAvailable, update.state)
        assertEquals("Update available (v1.0.0 → v2.0.0)", update.badge)
        assertFalse(update.isWarning)

        val latest = AppPackageInstaller.evaluateOfferComparison(
            offeredVersionName = "2.0.0",
            offeredVersionCode = 20,
            offeredSignerHex = signerA,
            offeredAbis = emptyList(),
            supportedAbis = arrayOf("arm64-v8a"),
            installedVersionName = "2.0.0",
            installedVersionCode = 20,
            installedCompatibleSigners = setOf(signerA)
        )
        assertEquals(AppVersionState.AlreadyLatest, latest.state)
        assertEquals("Already on latest version", latest.badge)
        assertFalse(latest.isWarning)

        val older = AppPackageInstaller.evaluateOfferComparison(
            offeredVersionName = "1.0.0",
            offeredVersionCode = 10,
            offeredSignerHex = signerA,
            offeredAbis = emptyList(),
            supportedAbis = arrayOf("arm64-v8a"),
            installedVersionName = "2.0.0",
            installedVersionCode = 20,
            installedCompatibleSigners = setOf(signerA)
        )
        assertEquals(AppVersionState.OlderVersion, older.state)
        assertEquals("Older version than installed", older.badge)
        assertTrue(older.isWarning)

        val conflict = AppPackageInstaller.evaluateOfferComparison(
            offeredVersionName = "2.0.0",
            offeredVersionCode = 20,
            offeredSignerHex = signerB,
            offeredAbis = emptyList(),
            supportedAbis = arrayOf("arm64-v8a"),
            installedVersionName = "1.0.0",
            installedVersionCode = 10,
            installedCompatibleSigners = setOf(signerA)
        )
        assertEquals(AppVersionState.SignatureConflict, conflict.state)
        assertEquals("Signature conflict — uninstall existing app first", conflict.badge)
        assertTrue(conflict.isWarning)

        val abiConflict = AppPackageInstaller.evaluateOfferComparison(
            offeredVersionName = "2.0.0",
            offeredVersionCode = 20,
            offeredSignerHex = signerA,
            offeredAbis = listOf("armeabi-v7a"),
            supportedAbis = arrayOf("x86_64"),
            installedVersionName = null,
            installedVersionCode = null,
            installedCompatibleSigners = emptySet()
        )
        assertEquals(AppVersionState.IncompatibleAbi, abiConflict.state)
        assertTrue(abiConflict.isWarning)
    }
}
