package dev.glide.app

import android.app.Activity
import android.content.Intent
import dev.glide.app.service.TransferRuntime

/**
 * Benchmark implementation of [DebugLaunchHarness].
 * Strictly no-op to match production release performance.
 */
object DebugLaunchHarness {
    @Suppress("UNUSED_PARAMETER")
    fun prepareLaunchOverrides(
        activity: Activity,
        intent: Intent?,
        transferRuntime: TransferRuntime
    ): LaunchOverrides = LaunchOverrides()
}
