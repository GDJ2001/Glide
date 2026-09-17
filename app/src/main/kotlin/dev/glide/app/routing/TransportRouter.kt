package dev.glide.app.routing

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.glide.app.permission.PermissionPolicy
import dev.glide.core.discovery.PeerCandidate
import dev.glide.core.ports.TransferTransport
import dev.glide.core.routing.RouteCandidate
import dev.glide.core.routing.RouteFilterContext
import dev.glide.core.routing.RouteSelectionResult
import dev.glide.core.routing.RouteSelector
import dev.glide.core.routing.SelectedRoute
import dev.glide.core.transport.TransportType
import dev.glide.transport.lan.LanTransferTransport
import dev.glide.transport.lan.LanTransportFactory
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.nearby.AndroidNearbyAvailabilityChecker
import dev.glide.transport.nearby.NearbyAvailabilityChecker
import dev.glide.transport.nearby.NearbyHardwareStatus
import dev.glide.transport.nearby.NearbyTransferTransport
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic transport router coordinating between discovery candidates, runtime environmental
 * capabilities, permission states, and transport session creation (LAN-016, Section 10, ADR-0018).
 *
 * Enforces:
 * - Section-10 ordered pipeline: implemented adapter -> platform API/GMS -> user permissions ->
 *   protocol/profile -> eligible network -> reachability probe.
 * - Scanned QR invitation route selection: only pinned LAN route selected; Nearby fallback prohibited.
 * - Single active route lock: once file transfer begins, mid-file or mid-session route switching
 *   is strictly prohibited (`RouteSelector.assertRouteLocked`).
 * - Unused transport radio suspension during active transfer to eliminate cross-radio interference.
 */
@Singleton
class TransportRouter @Inject constructor(
    @ApplicationContext private val context: Context? = null,
    private val lanTransportFactory: LanTransportFactory? = null,
    private val lanNetworkScope: LanNetworkScope? = null,
    private val nearbyAvailabilityChecker: NearbyAvailabilityChecker? = null,
    private val permissionChecker: ((String) -> Boolean)? = null,
    private val apiLevelProvider: (() -> Int)? = null,
    private val nearbyTransportFactory: ((CoroutineScope) -> TransferTransport)? = null
) {

    private val lock = Any()
    private var activeRoute: SelectedRoute? = null
    private var activeTransport: TransferTransport? = null

    /**
     * Builds the [RouteFilterContext] representing the current runtime environment and permissions.
     */
    fun buildFilterContext(): RouteFilterContext {
        val apiLevel = apiLevelProvider?.invoke() ?: Build.VERSION.SDK_INT

        val isGmsAvailable = checkGmsAvailable()
        val isNearbyPermitted = checkNearbyPermissions(apiLevel)
        val isLocalNetworkPermitted = checkLocalNetworkPermissions(apiLevel)
        val isNetworkEligible = checkNetworkEligible()

        val implemented = mutableSetOf<TransportType>()
        implemented.add(TransportType.GMS_NEARBY_P2P)
        if (lanTransportFactory != null && dev.glide.app.config.LanFeatureConfig.isLanTransportEnabled) {
            implemented.add(TransportType.LAN_NSD_TLS)
        }

        return RouteFilterContext(
            hasGms = isGmsAvailable,
            apiLevel = apiLevel,
            hasNearbyPermission = isNearbyPermitted,
            hasLocalNetworkPermission = isLocalNetworkPermitted,
            activeNetworkEligible = isNetworkEligible,
            implementedTransports = implemented
        )
    }

    /**
     * Evaluates a discovered [PeerCandidate] against Section 10 routing policy.
     * Pre-transfer evaluation only; does not mutate active transfer state.
     */
    fun evaluateRoute(
        candidate: PeerCandidate,
        nowMs: Long = System.currentTimeMillis()
    ): RouteSelectionResult {
        synchronized(lock) {
            val filterContext = buildFilterContext()
            return RouteSelector.selectRoute(
                candidate = candidate,
                context = filterContext,
                nowMs = nowMs
            )
        }
    }

    /**
     * Resolves and constructs a [TransferTransport] instance bound to the [selectedRoute].
     *
     * Invariants:
     * - Locks the route for the duration of the transfer attempt.
     * - If an active transfer route is already locked, enforces [RouteSelector.assertRouteLocked]
     *   to prevent mid-session transport switching.
     */
    fun resolveTransport(
        selectedRoute: SelectedRoute,
        coroutineScope: CoroutineScope
    ): TransferTransport {
        synchronized(lock) {
            val currentActive = activeRoute
            if (currentActive != null) {
                RouteSelector.assertRouteLocked(currentActive, selectedRoute)
                val existing = activeTransport
                if (existing != null) return existing
            }

            activeRoute = selectedRoute

            val transport: TransferTransport = when (selectedRoute.transportType) {
                TransportType.GMS_NEARBY_P2P -> {
                    nearbyTransportFactory?.invoke(coroutineScope)
                        ?: NearbyTransferTransport.create(
                            checkNotNull(context) { "Context is required to create NearbyTransferTransport" },
                            coroutineScope
                        )
                }

                TransportType.LAN_NSD_TLS -> {
                    checkNotNull(lanTransportFactory) {
                        "LanTransportFactory is not available on this platform (requires API 29+)"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        lanTransportFactory.createTransport(coroutineScope = coroutineScope)
                    } else {
                        throw IllegalStateException("LAN transport requires Android API 29+ (current: ${Build.VERSION.SDK_INT})")
                    }
                }

                else -> throw IllegalArgumentException("Unsupported transport type: ${selectedRoute.transportType}")
            }

            activeTransport = transport
            return transport
        }
    }

    /**
     * Releases the active route lock and resets active transport tracking upon session completion,
     * failure, or user cancellation.
     */
    fun releaseActiveRoute() {
        synchronized(lock) {
            activeRoute = null
            activeTransport = null
        }
    }

    /**
     * Returns the currently locked route, if an active transfer is in progress.
     */
    fun getActiveRoute(): SelectedRoute? {
        synchronized(lock) {
            return activeRoute
        }
    }

    private fun checkGmsAvailable(): Boolean {
        return if (nearbyAvailabilityChecker != null) {
            val status = nearbyAvailabilityChecker.checkHardwareStatus()
            status !is NearbyHardwareStatus.PlayServicesUnavailable
        } else {
            val ctx = context ?: return false
            try {
                val availabilityChecker = AndroidNearbyAvailabilityChecker(ctx)
                val status = availabilityChecker.checkHardwareStatus()
                status !is NearbyHardwareStatus.PlayServicesUnavailable
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun checkNearbyPermissions(apiLevel: Int): Boolean {
        val required = PermissionPolicy.nearbyTransferPermissions(apiLevel)
        return required.all { perm -> isPermissionGranted(perm) }
    }

    private fun checkLocalNetworkPermissions(apiLevel: Int): Boolean {
        return if (apiLevel >= 37) {
            isPermissionGranted(PermissionPolicy.PERMISSION_ACCESS_LOCAL_NETWORK)
        } else {
            true
        }
    }

    private fun checkNetworkEligible(): Boolean {
        val scope = lanNetworkScope ?: return false
        return when (scope.networkState.value) {
            is LanNetworkState.Available -> true
            is LanNetworkState.NoNetwork, is LanNetworkState.VpnActive, is LanNetworkState.Closed -> false
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        if (permissionChecker != null) {
            return permissionChecker.invoke(permission)
        }
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }
}
