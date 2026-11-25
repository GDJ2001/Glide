package dev.glide.app.ingress

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.glide.app.MainActivity
import dev.glide.app.navigation.GlideRoute
import dev.glide.app.navigation.QrScanPurpose
import dev.glide.app.service.TransferService

internal object GlideEntryPoint {
    private const val SEND = "dev.glide.app.shortcut.SEND"
    private const val RECEIVE = "dev.glide.app.shortcut.RECEIVE"
    private const val SCAN = "dev.glide.app.shortcut.SCAN"

    fun routeFor(action: String?): GlideRoute? = when (action) {
        SEND -> GlideRoute.Selection()
        RECEIVE -> GlideRoute.ReceiveReadiness
        SCAN -> GlideRoute.QrScanner(purpose = QrScanPurpose.UNIVERSAL)
        else -> null
    }

    fun sendIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(SEND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun sendPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, sendIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun receiveIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(RECEIVE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun receivePendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1, receiveIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun viewTransferIntent(context: Context, sessionId: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(TransferService.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun viewTransferPendingIntent(context: Context, sessionId: String): PendingIntent =
        PendingIntent.getActivity(
            context, 2, viewTransferIntent(context, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
