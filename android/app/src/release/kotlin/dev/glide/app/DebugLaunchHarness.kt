package dev.glide.app

import android.app.Activity
import android.content.Intent
import dev.glide.app.service.TransferRuntime

/**
 * Release implementation of [DebugLaunchHarness].
 * Strictly no-op: returns empty overrides and compiles zero debug-seeding logic into production DEX.
 */
object DebugLaunchHarness {
    @Suppress("UNUSED_PARAMETER")
    fun prepareLaunchOverrides(
        activity: Activity,
        intent: Intent?,
        transferRuntime: TransferRuntime
    ): LaunchOverrides = LaunchOverrides()
}
