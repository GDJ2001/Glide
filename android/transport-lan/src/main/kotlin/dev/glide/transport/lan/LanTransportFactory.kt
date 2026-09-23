package dev.glide.transport.lan

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dev.glide.transport.lan.network.LanNetworkScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Factory for creating [LanTransferTransport] instances bound to Android network scope (LAN-013).
 */
@RequiresApi(Build.VERSION_CODES.Q)
open class LanTransportFactory(
    private val context: Context?
) {
    /**
     * Creates a fresh [LanTransferTransport] instance.
     *
     * @param allowLoopbackForTesting If true, loopback addresses are allowed (for local on-device smoke tests).
     * @param coroutineScope Scope owning socket accept and read jobs.
     */
    open fun createTransport(
        allowLoopbackForTesting: Boolean = false,
        coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ): LanTransferTransport {
        val networkScope = LanNetworkScope(context)
        return LanTransferTransport(
            networkScope = networkScope,
            allowLoopbackForTesting = allowLoopbackForTesting,
            coroutineScope = coroutineScope
        )
    }
}
