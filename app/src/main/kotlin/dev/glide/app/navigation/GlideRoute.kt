package dev.glide.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface GlideRoute : NavKey {
    @Serializable
    data object Home : GlideRoute

    @Serializable
    data class Selection(val initialUris: List<String> = emptyList()) : GlideRoute

    @Serializable
    data class Discovery(val batchId: String? = null, val sourceHandles: List<String> = emptyList()) : GlideRoute

    @Serializable
    data object ReceiveReadiness : GlideRoute

    @Serializable
    data class Transfer(val sessionId: String = "session-demo", val batchId: String? = null) : GlideRoute

    @Serializable
    data class History(val initialSessionId: String? = null) : GlideRoute

    @Serializable
    data object Settings : GlideRoute

    @Serializable
    data class QrScanner(val batchId: String? = null) : GlideRoute
}
