package dev.glide.app.tile

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.glide.app.ingress.GlideEntryPoint

class GlideQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(GlideEntryPoint.sendPendingIntent(this))
            } else {
                openOnOlderAndroid()
            }
        }
    }

    // PendingIntent-based opening is unavailable on Android 13 and earlier.
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openOnOlderAndroid() = startActivityAndCollapse(GlideEntryPoint.sendIntent(this))
}
