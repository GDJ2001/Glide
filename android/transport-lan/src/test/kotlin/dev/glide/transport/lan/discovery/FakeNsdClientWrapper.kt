package dev.glide.transport.lan.discovery

import java.util.concurrent.CopyOnWriteArrayList

class FakeNsdClientWrapper : NsdClientWrapper {

    data class RegistrationCall(
        val serviceName: String,
        val serviceType: String,
        val port: Int,
        val attributes: Map<String, String>,
        val callback: NsdRegistrationCallback
    )

    data class DiscoveryCall(
        val serviceType: String,
        val callback: NsdDiscoveryCallback
    )

    val registeredServices = CopyOnWriteArrayList<RegistrationCall>()
    val activeDiscoveries = CopyOnWriteArrayList<DiscoveryCall>()
    val resolveQueue = CopyOnWriteArrayList<Pair<NsdDiscoveredService, NsdResolveCallback>>()

    var autoResolveService: ((NsdDiscoveredService) -> NsdResolvedService?)? = null

    override fun registerService(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
        callback: NsdRegistrationCallback
    ) {
        val call = RegistrationCall(serviceName, serviceType, port, attributes, callback)
        registeredServices.add(call)
        callback.onServiceRegistered(serviceName)
    }

    override fun unregisterService(callback: NsdRegistrationCallback) {
        registeredServices.removeIf { it.callback == callback }
        callback.onServiceUnregistered()
    }

    override fun discoverServices(
        serviceType: String,
        callback: NsdDiscoveryCallback
    ) {
        val call = DiscoveryCall(serviceType, callback)
        activeDiscoveries.add(call)
        callback.onDiscoveryStarted(serviceType)
    }

    override fun stopServiceDiscovery(callback: NsdDiscoveryCallback) {
        val removed = activeDiscoveries.removeIf { it.callback == callback }
        if (removed) {
            callback.onDiscoveryStopped("_glide._tcp")
        }
    }

    override fun resolveService(
        service: NsdDiscoveredService,
        callback: NsdResolveCallback
    ) {
        val auto = autoResolveService?.invoke(service)
        if (auto != null) {
            callback.onServiceResolved(auto)
        } else {
            resolveQueue.add(service to callback)
        }
    }

    fun simulateServiceFound(service: NsdDiscoveredService) {
        for (discovery in activeDiscoveries) {
            discovery.callback.onServiceFound(service)
        }
    }

    fun simulateServiceLost(service: NsdDiscoveredService) {
        for (discovery in activeDiscoveries) {
            discovery.callback.onServiceLost(service)
        }
    }

    fun flushResolveQueue(resolved: NsdResolvedService) {
        val item = resolveQueue.firstOrNull { it.first.serviceName == resolved.serviceName }
        if (item != null) {
            resolveQueue.remove(item)
            item.second.onServiceResolved(resolved)
        }
    }

    fun failResolveQueue(serviceName: String, errorCode: Int) {
        val item = resolveQueue.firstOrNull { it.first.serviceName == serviceName }
        if (item != null) {
            resolveQueue.remove(item)
            item.second.onResolveFailed(errorCode)
        }
    }
}
