package dev.glide.app.tile

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.glide.app.ingress.GlideEntryPoint
import dev.glide.app.service.TransferRuntime
import javax.inject.Inject

@AndroidEntryPoint
class GlideQuickSettingsTileService : TileService() {

    @Inject
    lateinit var transferRuntime: TransferRuntime

    override fun onStartListening() {
        super.onStartListening()
        val isActive = ::transferRuntime.isInitialized && transferRuntime.isSessionActive.value
        qsTile?.let { tile ->
            tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (isActive) "Transfer active" else "Receive files"
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val activeSession = if (::transferRuntime.isInitialized && transferRuntime.isSessionActive.value) {
            transferRuntime.activeSessionId.value
        } else {
            null
        }
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = if (activeSession != null) {
                    GlideEntryPoint.viewTransferPendingIntent(this, activeSession)
                } else {
                    GlideEntryPoint.receivePendingIntent(this)
                }
                startActivityAndCollapse(pending)
            } else {
                openOnOlderAndroid(activeSession)
            }
        }
    }

    // PendingIntent-based opening is unavailable on Android 13 and earlier.
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openOnOlderAndroid(activeSessionId: String?) {
        val intent = if (activeSessionId != null) {
            GlideEntryPoint.viewTransferIntent(this, activeSessionId)
        } else {
            GlideEntryPoint.receiveIntent(this)
        }
        startActivityAndCollapse(intent)
    }
}
