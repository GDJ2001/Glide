package dev.glide.transport.lan.discovery

import dev.glide.core.ports.DiscoveryEvent
import dev.glide.transport.lan.network.LanNetworkScope
import dev.glide.transport.lan.network.LanNetworkState
import dev.glide.transport.lan.network.SubnetPrefix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class NsdDiscoveryAdapterTest {

    private val testPresence = "0123456789abcdef0123456789abcdef"

    private fun createNetworkScopeStub(
        activeSubnets: List<SubnetPrefix> = listOf(
            SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24)
        ),
        generation: Long = 1L
    ): LanNetworkScope {
        val scope = LanNetworkScope(null, null)
        scope.updateNetworkStateForTesting(
            LanNetworkState.Available(
                network = null,
                localAddresses = listOf(InetAddress.getByName("192.168.1.10")),
                subnets = activeSubnets,
                generation = generation
            )
        )
        return scope
    }

    @Test
    fun testStartAndStopAdvertising(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub()
        val adapter = NsdDiscoveryAdapter(
            clientWrapper = fakeClient,
            networkScope = scope
        )

        adapter.startAdvertising(
            deviceName = "Alice's Device",
            localPort = 42424,
            presenceId = testPresence
        )

        assertEquals(1, fakeClient.registeredServices.size)
        val registration = fakeClient.registeredServices[0]
        assertEquals(42424, registration.port)
        assertEquals(NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE, registration.serviceType)
        assertTrue(registration.serviceName.startsWith("Glide-"))

        // Check attributes
        assertEquals("1", registration.attributes["v"])
        assertEquals("1", registration.attributes["tv"])
        assertEquals("1", registration.attributes["q"])
        assertEquals(testPresence, registration.attributes["p"])
        assertEquals("Alice's Device", registration.attributes["dn"])
        assertEquals(LanTxtRecord.DEVICE_TYPE_PHONE, registration.attributes["dt"])

        // Stop advertising
        adapter.stopAdvertising()
        assertEquals(0, fakeClient.registeredServices.size)

        adapter.close()
    }

    @Test
    fun trustedListenerAdvertisesCurrentPortAndReconnectCapability(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub()
        val adapter = NsdDiscoveryAdapter(clientWrapper = fakeClient, networkScope = scope)

        adapter.startAdvertising(
            deviceName = "Paired phone",
            localPort = 49152,
            presenceId = testPresence,
            supportsTrustedReconnect = true
        )

        val registration = fakeClient.registeredServices.single()
        assertEquals(49152, registration.port)
        assertEquals("1", registration.attributes["tr"])
        assertEquals(null, registration.attributes["pair"])
        assertEquals(testPresence, registration.attributes["p"])
        adapter.close()
    }

    @Test
    fun testDiscoveryResolvesAndEmitsPeerAppeared(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub()
        val adapter = NsdDiscoveryAdapter(
            clientWrapper = fakeClient,
            networkScope = scope
        )

        val discoveryFlow = adapter.startDiscovery()
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            discoveryFlow.collect { events.add(it) }
        }

        val onLinkHost = InetAddress.getByName("192.168.1.50")
        val txtRecord = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = testPresence,
            dn = "Bob's Pixel",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )

        val service = NsdDiscoveredService("Glide-bob001", NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE)
        val resolvedService = NsdResolvedService(
            serviceName = service.serviceName,
            serviceType = service.serviceType,
            host = onLinkHost,
            port = 50001,
            attributes = LanTxtCodec.encode(txtRecord)
        )

        fakeClient.autoResolveService = { discovered ->
            if (discovered.serviceName == service.serviceName) resolvedService else null
        }

        fakeClient.simulateServiceFound(service)

        // Wait for PeerAppeared
        val appeared = withTimeout(5_000) {
            while (events.none { it is DiscoveryEvent.PeerAppeared }) {
                kotlinx.coroutines.delay(20)
            }
            events.first { it is DiscoveryEvent.PeerAppeared } as DiscoveryEvent.PeerAppeared
        }

        assertEquals("lan-Glide-bob001", appeared.peer.id.value)
        assertEquals("Bob's Pixel", appeared.peer.displayName)

        val cached = adapter.getCachedObservations()
        assertEquals(1, cached.size)
        assertEquals("192.168.1.50:50001", cached[0].endpointHandle)

        job.cancel()
        adapter.close()
    }

    @Test
    fun testServiceLostRemovesObservationAndEmitsPeerLost(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub()
        val adapter = NsdDiscoveryAdapter(
            clientWrapper = fakeClient,
            networkScope = scope
        )

        val discoveryFlow = adapter.startDiscovery()
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            discoveryFlow.collect { events.add(it) }
        }

        val onLinkHost = InetAddress.getByName("192.168.1.50")
        val txtRecord = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = testPresence,
            dn = "Charlie Phone",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )

        val service = NsdDiscoveredService("Glide-charlie", NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE)
        val resolvedService = NsdResolvedService(
            serviceName = service.serviceName,
            serviceType = service.serviceType,
            host = onLinkHost,
            port = 50002,
            attributes = LanTxtCodec.encode(txtRecord)
        )

        fakeClient.autoResolveService = { resolvedService }
        fakeClient.simulateServiceFound(service)

        withTimeout(5_000) {
            while (events.none { it is DiscoveryEvent.PeerAppeared }) {
                kotlinx.coroutines.delay(20)
            }
        }

        assertEquals(1, adapter.getCachedObservations().size)

        // Simulate service lost
        fakeClient.simulateServiceLost(service)

        val lost = withTimeout(5_000) {
            while (events.none { it is DiscoveryEvent.PeerLost }) {
                kotlinx.coroutines.delay(20)
            }
            events.first { it is DiscoveryEvent.PeerLost } as DiscoveryEvent.PeerLost
        }

        assertEquals("lan-Glide-charlie", lost.peerId.value)
        assertEquals(0, adapter.getCachedObservations().size)

        job.cancel()
        adapter.close()
    }

    @Test
    fun testOffLinkResolvedAddressIsRejected(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub(
            activeSubnets = listOf(SubnetPrefix(InetAddress.getByName("192.168.1.0"), 24))
        )
        val adapter = NsdDiscoveryAdapter(
            clientWrapper = fakeClient,
            networkScope = scope,
            allowLoopbackForTesting = false
        )

        val discoveryFlow = adapter.startDiscovery()
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            discoveryFlow.collect { events.add(it) }
        }

        // Off-link address (public WAN IP 8.8.8.8)
        val offLinkHost = InetAddress.getByName("8.8.8.8")
        val txtRecord = LanTxtRecord(
            v = 1,
            tv = 1,
            q = 1,
            p = testPresence,
            dn = "Attacker Router",
            dt = LanTxtRecord.DEVICE_TYPE_PHONE
        )

        val service = NsdDiscoveredService("Glide-wan-peer", NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE)
        val resolvedService = NsdResolvedService(
            serviceName = service.serviceName,
            serviceType = service.serviceType,
            host = offLinkHost,
            port = 50003,
            attributes = LanTxtCodec.encode(txtRecord)
        )

        fakeClient.autoResolveService = { resolvedService }
        fakeClient.simulateServiceFound(service)

        // Give it a short delay
        kotlinx.coroutines.delay(200)

        // Should be rejected by on-link validator
        assertEquals(0, events.size)
        assertEquals(0, adapter.getCachedObservations().size)

        job.cancel()
        adapter.close()
    }

    @Test
    fun testCorruptTxtRecordIsDropped(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub()
        val adapter = NsdDiscoveryAdapter(
            clientWrapper = fakeClient,
            networkScope = scope
        )

        val discoveryFlow = adapter.startDiscovery()
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch {
            discoveryFlow.collect { events.add(it) }
        }

        val onLinkHost = InetAddress.getByName("192.168.1.50")
        val service = NsdDiscoveredService("Glide-corrupt", NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE)

        // Attributes missing required fields
        val corruptAttrs = mapOf("v" to "1".toByteArray(StandardCharsets.US_ASCII))
        val resolvedService = NsdResolvedService(
            serviceName = service.serviceName,
            serviceType = service.serviceType,
            host = onLinkHost,
            port = 50004,
            attributes = corruptAttrs
        )

        fakeClient.autoResolveService = { resolvedService }
        fakeClient.simulateServiceFound(service)

        kotlinx.coroutines.delay(200)

        assertEquals(0, events.size)
        assertEquals(0, adapter.getCachedObservations().size)

        job.cancel()
        adapter.close()
    }

    @Test
    fun testCacheCapacityEvictsOldestWhenMax64Reached(): Unit = runBlocking {
        val fakeClient = FakeNsdClientWrapper()
        val scope = createNetworkScopeStub()
        val adapter = NsdDiscoveryAdapter(
            clientWrapper = fakeClient,
            networkScope = scope
        )

        val discoveryFlow = adapter.startDiscovery()
        val job = launch {
            discoveryFlow.collect {}
        }

        val onLinkHost = InetAddress.getByName("192.168.1.50")

        fakeClient.autoResolveService = { discovered ->
            val txtRecord = LanTxtRecord(
                v = 1,
                tv = 1,
                q = 1,
                p = testPresence,
                dn = discovered.serviceName,
                dt = LanTxtRecord.DEVICE_TYPE_PHONE
            )
            NsdResolvedService(
                serviceName = discovered.serviceName,
                serviceType = discovered.serviceType,
                host = onLinkHost,
                port = 50000,
                attributes = LanTxtCodec.encode(txtRecord)
            )
        }

        // Add 65 services sequentially so order and eviction are strictly deterministic
        for (i in 1..65) {
            val service = NsdDiscoveredService("Glide-node-$i", NsdDiscoveryAdapter.DEFAULT_SERVICE_TYPE)
            fakeClient.simulateServiceFound(service)
            withTimeout(5_000) {
                while (adapter.getCachedObservations().none { it.label == "Glide-node-$i" }) {
                    kotlinx.coroutines.delay(5)
                }
            }
        }

        val cached = adapter.getCachedObservations()
        assertEquals(64, cached.size)
        // First node (node-1) should have been evicted by the 65th
        assertTrue(cached.none { it.label == "Glide-node-1" })
        assertTrue(cached.any { it.label == "Glide-node-65" })

        job.cancel()
        adapter.close()
    }
}
