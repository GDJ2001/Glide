package dev.glide.transport.lan.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovered service instance observed on local network before resolution (LAN-014, Section 11).
 */
data class NsdDiscoveredService(
    val serviceName: String,
    val serviceType: String
)

/**
 * Resolved service instance with resolved host addresses, port, and TXT attributes (LAN-014, Section 11).
 */
data class NsdResolvedService(
    val serviceName: String,
    val serviceType: String,
    val host: InetAddress?,
    val hostAddresses: List<InetAddress> = host?.let { listOf(it) } ?: emptyList(),
    val port: Int,
    val attributes: Map<String, ByteArray>
)

interface NsdRegistrationCallback {
    fun onServiceRegistered(registeredName: String)
    fun onRegistrationFailed(errorCode: Int)
    fun onServiceUnregistered()
    fun onUnregistrationFailed(errorCode: Int)
}

interface NsdDiscoveryCallback {
    fun onDiscoveryStarted(serviceType: String)
    fun onDiscoveryStopped(serviceType: String)
    fun onServiceFound(service: NsdDiscoveredService)
    fun onServiceLost(service: NsdDiscoveredService)
    fun onStartDiscoveryFailed(errorCode: Int)
    fun onStopDiscoveryFailed(errorCode: Int)
}

interface NsdResolveCallback {
    fun onServiceResolved(service: NsdResolvedService)
    fun onResolveFailed(errorCode: Int)
}

/**
 * Testable abstraction over Android [NsdManager] for scoped registration, discovery, and resolution (LAN-014).
 */
interface NsdClientWrapper {
    fun registerService(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
        callback: NsdRegistrationCallback
    )

    fun unregisterService(callback: NsdRegistrationCallback)

    fun discoverServices(
        serviceType: String,
        callback: NsdDiscoveryCallback
    )

    fun stopServiceDiscovery(callback: NsdDiscoveryCallback)

    fun resolveService(
        service: NsdDiscoveredService,
        callback: NsdResolveCallback
    )
}

/**
 * Production implementation backed by platform [NsdManager].
 */
class AndroidNsdClientWrapper(context: Context) : NsdClientWrapper {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val activeRegistrationListeners = ConcurrentHashMap<NsdRegistrationCallback, NsdManager.RegistrationListener>()
    private val activeDiscoveryListeners = ConcurrentHashMap<NsdDiscoveryCallback, NsdManager.DiscoveryListener>()

    override fun registerService(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
        callback: NsdRegistrationCallback
    ) {
        val manager = nsdManager ?: run {
            callback.onRegistrationFailed(NsdManager.FAILURE_INTERNAL_ERROR)
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
            for ((k, v) in attributes) {
                setAttribute(k, v)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                callback.onServiceRegistered(registeredService.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                activeRegistrationListeners.remove(callback)
                callback.onRegistrationFailed(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                activeRegistrationListeners.remove(callback)
                callback.onServiceUnregistered()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                activeRegistrationListeners.remove(callback)
                callback.onUnregistrationFailed(errorCode)
            }
        }

        activeRegistrationListeners[callback] = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            activeRegistrationListeners.remove(callback)
            callback.onRegistrationFailed(NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    override fun unregisterService(callback: NsdRegistrationCallback) {
        val listener = activeRegistrationListeners.remove(callback) ?: return
        try {
            nsdManager?.unregisterService(listener)
        } catch (_: Exception) {}
    }

    override fun discoverServices(
        serviceType: String,
        callback: NsdDiscoveryCallback
    ) {
        val manager = nsdManager ?: run {
            callback.onStartDiscoveryFailed(NsdManager.FAILURE_INTERNAL_ERROR)
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                callback.onDiscoveryStarted(regType)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                activeDiscoveryListeners.remove(callback)
                callback.onDiscoveryStopped(serviceType)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                callback.onServiceFound(
                    NsdDiscoveredService(
                        serviceName = serviceInfo.serviceName,
                        serviceType = serviceInfo.serviceType
                    )
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                callback.onServiceLost(
                    NsdDiscoveredService(
                        serviceName = serviceInfo.serviceName,
                        serviceType = serviceInfo.serviceType
                    )
                )
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                activeDiscoveryListeners.remove(callback)
                callback.onStartDiscoveryFailed(errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                activeDiscoveryListeners.remove(callback)
                callback.onStopDiscoveryFailed(errorCode)
            }
        }

        activeDiscoveryListeners[callback] = listener
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            activeDiscoveryListeners.remove(callback)
            callback.onStartDiscoveryFailed(NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    override fun stopServiceDiscovery(callback: NsdDiscoveryCallback) {
        val listener = activeDiscoveryListeners.remove(callback) ?: return
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (_: Exception) {}
    }

    override fun resolveService(
        service: NsdDiscoveredService,
        callback: NsdResolveCallback
    ) {
        val manager = nsdManager ?: run {
            callback.onResolveFailed(NsdManager.FAILURE_INTERNAL_ERROR)
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = service.serviceName
            this.serviceType = service.serviceType
        }

        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val hostAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        resolvedInfo.hostAddresses
                    } catch (_: NoSuchMethodError) {
                        listOfNotNull(resolvedInfo.host)
                    }
                } else {
                    listOfNotNull(resolvedInfo.host)
                }

                callback.onServiceResolved(
                    NsdResolvedService(
                        serviceName = resolvedInfo.serviceName,
                        serviceType = resolvedInfo.serviceType,
                        host = resolvedInfo.host,
                        hostAddresses = hostAddresses,
                        port = resolvedInfo.port,
                        attributes = resolvedInfo.attributes ?: emptyMap()
                    )
                )
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                callback.onResolveFailed(errorCode)
            }
        }

        try {
            manager.resolveService(serviceInfo, listener)
        } catch (e: Exception) {
            callback.onResolveFailed(NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }
}
