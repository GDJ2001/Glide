package dev.glide.app.ui.failure

import dev.glide.app.ui.discovery.BlockedReason
import dev.glide.app.ui.pairing.QrScanErrorReason
import dev.glide.core.domain.FailureCategory
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification test suite for Actionable Discovery, Pairing & Transfer Errors (LAN-020).
 *
 * Enforces Section 20 requirements:
 * 1. Exact user wording matching the Section 20 taxonomy.
 * 2. Zero raw exception exposure: stack traces, internal exception names, IP addresses, and file paths
 *    must never appear in user-facing title or wording.
 * 3. Honest network/AP isolation guidance without false promises of automated router fixes.
 * 4. Distinct recoverable vs non-recoverable action labels.
 */
class ActionableErrorMapperTest {

    @Test
    fun blockedReason_mapsAccuratelyToSection20Wording() {
        val bluetooth = ActionableErrorMapper.fromBlockedReason(BlockedReason.BluetoothDisabled)
        assertEquals("Bluetooth is turned off", bluetooth.title)
        assertEquals("Open Bluetooth settings", bluetooth.primaryActionLabel)
        assertEquals("Check again", bluetooth.secondaryActionLabel)

        val wifi = ActionableErrorMapper.fromBlockedReason(BlockedReason.WifiDisabled)
        assertEquals("Wi-Fi is turned off", wifi.title)
        assertEquals("Open Wi-Fi settings", wifi.primaryActionLabel)

        val lanPerm = ActionableErrorMapper.fromBlockedReason(BlockedReason.LanPermissionDenied)
        assertEquals("Local network access denied", lanPerm.title)
        assertEquals("Allow local-network access to connect; Settings after permanent denial", lanPerm.userWording)
        assertEquals("Open App settings", lanPerm.primaryActionLabel)

        val noNetwork = ActionableErrorMapper.fromBlockedReason(BlockedReason.NoLocalNetwork)
        assertEquals("No local network available", noNetwork.title)
        assertEquals("Connect both devices to a reachable local network; Retry", noNetwork.userWording)
        assertEquals("Open Wi-Fi settings", noNetwork.primaryActionLabel)
        assertEquals("Retry", noNetwork.secondaryActionLabel)

        val vpn = ActionableErrorMapper.fromBlockedReason(BlockedReason.VpnActive)
        assertEquals("VPN is active", vpn.title)
        assertEquals("Open VPN settings", vpn.primaryActionLabel)
        assertNotNull(vpn.troubleshootingHint)

        val perms = ActionableErrorMapper.fromBlockedReason(BlockedReason.PermissionsRequired)
        assertEquals("Permissions required", perms.title)
        assertEquals("Open App settings", perms.primaryActionLabel)
    }

    @Test
    fun qrScanErrorReason_mapsAccuratelyToSection20Wording() {
        val invalidFormat = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.InvalidFormat)
        assertEquals("Invalid QR code", invalidFormat.title)
        assertEquals("This code is not a valid Glide transfer invitation.", invalidFormat.userWording)
        assertEquals("Paste invitation", invalidFormat.secondaryActionLabel)

        val expired = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.Expired)
        assertEquals("Invitation expired", expired.title)
        assertEquals("This code has expired or was used. Ask the receiver to refresh it", expired.userWording)

        val incompatible = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.IncompatibleVersion)
        assertEquals("Incompatible version", incompatible.title)
        assertEquals("Devices use incompatible Glide versions; no insecure fallback", incompatible.userWording)
        assertFalse(incompatible.isRecoverable)

        val noEndpoints = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.NoValidEndpoints)
        assertEquals("No reachable network address", noEndpoints.title)
        assertEquals("Connect both devices to a reachable local network; Retry", noEndpoints.userWording)

        val authMismatch = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.AuthenticationMismatch)
        assertEquals("Verification failed", authMismatch.title)
        assertEquals("Could not verify this device. Scan a new code from its screen", authMismatch.userWording)
        assertEquals("Scan new code", authMismatch.primaryActionLabel)

        val timeout = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.ConnectionTimeout)
        assertEquals("Connection timed out", timeout.title)
        val hint = timeout.troubleshootingHint
        assertNotNull("Must include network isolation troubleshooting guidance", hint)
        assertTrue(hint!!.contains("AP Isolation") || hint.contains("Client Isolation"))

        val busy = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.PeerBusy)
        assertEquals("Device is busy", busy.title)
        assertEquals("Device is already transferring; try again when it finishes", busy.userWording)

        val offLink = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.OffLinkDestination)
        assertEquals("Devices are on different local networks", offLink.title)
        assertEquals("Connect this phone and the other device to the same Wi-Fi or local network, then create a new QR code.", offLink.userWording)
        assertEquals("Open Wi-Fi settings", offLink.primaryActionLabel)
        assertEquals("Scan new code", offLink.secondaryActionLabel)
        assertEquals("If Glide is running in VirtualBox, set its network adapter to Bridged Adapter.", offLink.troubleshootingHint)
    }

    @Test
    fun lanConnectionFailure_mapsAccuratelyToSection20Wording() {
        val offLink = ActionableErrorMapper.fromLanConnectionFailure(dev.glide.transport.lan.LanConnectionFailure.OffLinkDestination())
        assertEquals("Devices are on different local networks", offLink.title)
        assertEquals("Connect this phone and the other device to the same Wi-Fi or local network, then create a new QR code.", offLink.userWording)
        assertEquals("Open Wi-Fi settings", offLink.primaryActionLabel)
        assertEquals("Scan new code", offLink.secondaryActionLabel)
        assertEquals("If Glide is running in VirtualBox, set its network adapter to Bridged Adapter.", offLink.troubleshootingHint)

        val timeout = ActionableErrorMapper.fromLanConnectionFailure(dev.glide.transport.lan.LanConnectionFailure.Timeout())
        assertEquals("Connection timed out", timeout.title)
        assertEquals("Connection timed out; check receiver and network isolation", timeout.userWording)

        val auth = ActionableErrorMapper.fromLanConnectionFailure(dev.glide.transport.lan.LanConnectionFailure.AuthenticationFailed())
        assertEquals("Verification failed", auth.title)
        assertEquals("Could not verify this device. Scan a new code from its screen", auth.userWording)

        val network = ActionableErrorMapper.fromLanConnectionFailure(dev.glide.transport.lan.LanConnectionFailure.NetworkUnavailable())
        assertEquals("No local network available", network.title)
        assertEquals("Connect both devices to a reachable local network; Retry", network.userWording)
    }

    @Test
    fun transferFailure_mapsAccuratelyToSection20Wording() {
        val authMismatch = ActionableErrorMapper.fromTransferFailure(TransferFailure.AuthenticationMismatch())
        assertEquals("Could not verify this device. Scan a new code from its screen", authMismatch.userWording)

        val timeout = ActionableErrorMapper.fromTransferFailure(TransferFailure.Timeout("transfer"))
        assertEquals("Connection timed out; check receiver and network isolation", timeout.userWording)

        val busy = ActionableErrorMapper.fromTransferFailure(TransferFailure.Busy())
        assertEquals("Device is already transferring; try again when it finishes", busy.userWording)

        val sourceUnavailable = ActionableErrorMapper.fromTransferFailure(TransferFailure.SourceUnavailable())
        assertEquals("This file is no longer accessible", sourceUnavailable.userWording)

        val storage = ActionableErrorMapper.fromTransferFailure(TransferFailure.InsufficientStorage(1000L, 500L))
        assertEquals("Free space or choose an available destination", storage.userWording)

        val interrupted = ActionableErrorMapper.fromTransferFailure(TransferFailure.TransferInterrupted())
        assertEquals("Transfer interrupted. Verified files are kept", interrupted.userWording)

        val integrity = ActionableErrorMapper.fromTransferFailure(
            TransferFailure.IntegrityMismatch(Sha256Digest("0000000000000000000000000000000000000000000000000000000000000000"),
                Sha256Digest("1111111111111111111111111111111111111111111111111111111111111111"))
        )
        assertEquals("This file could not be verified and was not saved as complete", integrity.userWording)

        val unconfirmed = ActionableErrorMapper.fromTransferFailure(TransferFailure.DeliveryUnconfirmed())
        assertEquals("Delivery could not be confirmed. Check the receiving device before resending", unconfirmed.userWording)

        val malformed = ActionableErrorMapper.fromTransferFailure(TransferFailure.MalformedMessage())
        assertEquals("Connection stopped because the data was invalid", malformed.userWording)
    }

    @Test
    fun sanitizeText_stripsRawExceptionsIpAddressesAndPaths() {
        val rawErrorWithIp = "java.net.ConnectException: Failed to connect to 192.168.1.55:4242"
        val sanitizedIp = ActionableErrorMapper.sanitizeText(rawErrorWithIp)
        assertFalse("Must not contain raw IP address", sanitizedIp.contains("192.168.1.55"))
        assertFalse("Must not contain java.net. prefix", sanitizedIp.contains("java.net."))
        assertTrue("Contains localized descriptor", sanitizedIp.contains("[local network address]"))

        val rawErrorWithPath = "File read failed at /shared/data/user/0/dev.glide/files/staging/item_secret_123.tmp"
        val sanitizedPath = ActionableErrorMapper.sanitizeText(rawErrorWithPath)
        assertFalse("Must not contain absolute storage path", sanitizedPath.contains("/shared/data/user/0"))
        assertTrue("Contains safe file location placeholder", sanitizedPath.contains("[file location]"))

        val rawWindowsPath = "Error writing to C:\\Users\\Administrator\\AppData\\Local\\secret.dat"
        val sanitizedWinPath = ActionableErrorMapper.sanitizeText(rawWindowsPath)
        assertFalse("Must not contain Windows user path", sanitizedWinPath.contains("Administrator"))
        assertTrue("Contains safe file location placeholder", sanitizedWinPath.contains("[file location]"))

        val rawRejected = "Destination address validation failed: Rejected(code=OFF_LINK_REJECTED, reason=Target 10.0.2.15 is off-link)"
        val sanitizedRejected = ActionableErrorMapper.sanitizeText(rawRejected)
        assertFalse("Must not contain raw IP", sanitizedRejected.contains("10.0.2.15"))
        assertFalse("Must not contain Rejected(...)", sanitizedRejected.contains("Rejected("))
        assertFalse("Must not contain OFF_LINK_REJECTED", sanitizedRejected.contains("OFF_LINK_REJECTED"))
    }

    @Test
    fun apIsolationGuidance_doesNotMakeFalsePromisesOfAutomaticRouterConfiguration() {
        val timeoutError = ActionableErrorMapper.fromQrScanErrorReason(QrScanErrorReason.ConnectionTimeout)
        val hint = timeoutError.troubleshootingHint
        assertNotNull(hint)
        // Must explain AP isolation
        assertTrue(hint!!.contains("AP Isolation") || hint.contains("Client Isolation"))
        // Must NOT promise that Glide can automatically configure the router
        assertFalse(hint.contains("Glide will configure"))
        assertFalse(hint.contains("automatic router fix"))
    }
}
