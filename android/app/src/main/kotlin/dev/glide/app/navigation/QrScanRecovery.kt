package dev.glide.app.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import java.util.UUID

/** Return to a fresh QR scanner after a failed authenticated connection. */
internal fun restartQrScanAfterPairingFailure(
    backStack: NavBackStack<NavKey>,
    failedRoute: GlideRoute.Transfer,
    scanAttemptId: String = UUID.randomUUID().toString()
): GlideRoute.QrScanner? {
    if (backStack.lastOrNull() != failedRoute) return null

    val previousScanner = backStack.getOrNull(backStack.lastIndex - 1) as? GlideRoute.QrScanner
    val scanner = previousScanner ?: GlideRoute.QrScanner(
        batchId = failedRoute.batchId,
        purpose = if (failedRoute.batchId == null) QrScanPurpose.RECEIVE_FROM_SENDER
                  else QrScanPurpose.SEND_TO_RECEIVER
    )

    backStack.removeAt(backStack.lastIndex)
    if (previousScanner != null) backStack.removeAt(backStack.lastIndex)

    return scanner.copy(scanAttemptId = scanAttemptId).also(backStack::add)
}
