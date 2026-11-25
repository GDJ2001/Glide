package dev.glide.app.ingress

import dev.glide.app.navigation.GlideRoute
import dev.glide.app.navigation.QrScanPurpose
import dev.glide.app.service.TransferService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlideEntryPointTest {
    @Test
    fun scanShortcutUsesUniversalQrMode() {
        assertEquals(GlideRoute.Selection(), GlideEntryPoint.routeFor("dev.glide.app.shortcut.SEND"))
        assertEquals(GlideRoute.ReceiveReadiness, GlideEntryPoint.routeFor("dev.glide.app.shortcut.RECEIVE"))
        assertEquals(
            GlideRoute.QrScanner(purpose = QrScanPurpose.UNIVERSAL),
            GlideEntryPoint.routeFor("dev.glide.app.shortcut.SCAN")
        )
    }

    @Test
    fun selectionRoutePreservesPendingLanInvitationAndSeparateCompletionChannel() {
        val route = GlideRoute.Selection(pendingLanInvitation = "glide://pair/v1#sample")
        assertEquals("glide://pair/v1#sample", route.pendingLanInvitation)
        assertNotEquals(TransferService.NOTIFICATION_CHANNEL_ID, TransferService.COMPLETION_NOTIFICATION_CHANNEL_ID)
    }
}
