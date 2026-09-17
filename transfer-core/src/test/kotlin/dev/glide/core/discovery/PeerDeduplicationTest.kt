package dev.glide.core.discovery

import dev.glide.core.routing.RouteCandidate
import dev.glide.core.transport.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PeerDeduplicationTest {

    @Test
    fun testSameNameCollisionsStayDistinctCandidates() {
        val obs1 = PeerObservation(
            observationId = "obs-1",
            source = ObservationSource.NEARBY,
            label = "Alice's Phone",
            platform = DeclaredPlatform.ANDROID,
            endpointHandle = "nearby-endpoint-AAA",
            observedAtMs = 1000L
        )
        val obs2 = PeerObservation(
            observationId = "obs-2",
            source = ObservationSource.NEARBY,
            label = "Alice's Phone", // Same label!
            platform = DeclaredPlatform.ANDROID,
            endpointHandle = "nearby-endpoint-BBB", // Different endpoint!
            observedAtMs = 1000L
        )

        var map = CandidateDeduplicator.ingestObservation(emptyMap(), obs1)
        map = CandidateDeduplicator.ingestObservation(map, obs2)

        assertEquals("Name collision must produce 2 distinct candidates", 2, map.size)
        assertTrue(map.containsKey("nearby:nearby-endpoint-AAA"))
        assertTrue(map.containsKey("nearby:nearby-endpoint-BBB"))
    }

    @Test
    fun testRepeatedNsdUpdatesMergeIntoSameCandidate() {
        val obs1 = PeerObservation(
            observationId = "nsd-1",
            source = ObservationSource.LAN_NSD,
            label = "Bob's Workstation",
            platform = DeclaredPlatform.WINDOWS,
            endpointHandle = "192.168.1.50:42424",
            networkHandle = "wlan0",
            generation = 1L,
            observedAtMs = 1000L
        )
        val obs2 = PeerObservation(
            observationId = "nsd-2",
            source = ObservationSource.LAN_NSD,
            label = "Bob's Workstation",
            platform = DeclaredPlatform.WINDOWS,
            endpointHandle = "192.168.1.50:42424", // Same instance & network
            networkHandle = "wlan0",
            generation = 2L,
            observedAtMs = 2000L
        )

        var map = CandidateDeduplicator.ingestObservation(emptyMap(), obs1)
        assertEquals(1, map.size)

        map = CandidateDeduplicator.ingestObservation(map, obs2)
        assertEquals("Repeated NSD updates on same endpoint/network must merge into 1 candidate", 1, map.size)

        val candidate = map.values.first()
        assertEquals("Bob's Workstation", candidate.displayName)
        assertEquals(2, candidate.observations.size)
    }

    @Test
    fun testLosingOneObservationRetainsCandidateWithRemainingObservations() {
        val obs1 = PeerObservation(
            observationId = "obs-A",
            source = ObservationSource.NEARBY,
            label = "Device X",
            endpointHandle = "handle-1",
            observedAtMs = 1000L
        )
        val obs2 = PeerObservation(
            observationId = "obs-B",
            source = ObservationSource.NEARBY,
            label = "Device X",
            endpointHandle = "handle-1",
            observedAtMs = 2000L
        )

        var map = CandidateDeduplicator.ingestObservation(emptyMap(), obs1)
        map = CandidateDeduplicator.ingestObservation(map, obs2)
        assertEquals(1, map.size)
        assertEquals(2, map.values.first().observations.size)

        // Remove obs-A
        map = CandidateDeduplicator.removeObservation(map, "obs-A")
        assertEquals(1, map.size)
        assertEquals(1, map.values.first().observations.size)
        assertEquals("obs-B", map.values.first().observations[0].observationId)

        // Remove obs-B
        map = CandidateDeduplicator.removeObservation(map, "obs-B")
        assertEquals("Candidate should be removed once all observations are lost", 0, map.size)
    }

    @Test
    fun testNearbyAndLanStaySeparateWithoutCryptographicProof() {
        val nearbyObs = PeerObservation(
            observationId = "nearby-1",
            source = ObservationSource.NEARBY,
            label = "Shared Name",
            endpointHandle = "nearby-endpoint-1",
            observedAtMs = 1000L
        )
        val lanObs = PeerObservation(
            observationId = "lan-1",
            source = ObservationSource.LAN_NSD,
            label = "Shared Name",
            endpointHandle = "192.168.1.100:49152",
            networkHandle = "wlan0",
            observedAtMs = 1000L
        )

        var map = CandidateDeduplicator.ingestObservation(emptyMap(), nearbyObs)
        map = CandidateDeduplicator.ingestObservation(map, lanObs)

        assertEquals("Nearby and LAN must remain distinct candidates without cryptographic proof", 2, map.size)
        val keys = map.keys.toList()
        assertTrue(keys.any { it.startsWith("nearby:") })
        assertTrue(keys.any { it.startsWith("lan-nsd:") })
    }

    @Test
    fun testMergeProvenLanCandidate_onlyWhenPinMatches() {
        val qrObs = PeerObservation(
            observationId = "qr-inv-1",
            source = ObservationSource.QR_INVITATION,
            label = "Verified Server",
            endpointHandle = "192.168.1.200:49152",
            observedAtMs = 1000L
        )
        val qrRoute = RouteCandidate(
            routeId = "route-qr-1",
            transportType = TransportType.LAN_NSD_TLS,
            endpointHandle = "192.168.1.200:49152"
        )
        val qrCandidate = PeerCandidate(
            uiKey = "qr:qr-inv-1",
            displayName = "Verified Server",
            observations = listOf(qrObs),
            routes = listOf(qrRoute),
            verificationStatus = CandidateVerificationStatus.PINNED_INVITATION
        )

        val lanObs = PeerObservation(
            observationId = "lan-nsd-1",
            source = ObservationSource.LAN_NSD,
            label = "Discovered Server",
            endpointHandle = "192.168.1.200:49152",
            observedAtMs = 1500L
        )
        val lanCandidate = PeerCandidate(
            uiKey = "lan-nsd:local:192.168.1.200:49152",
            displayName = "Discovered Server",
            observations = listOf(lanObs),
            routes = emptyList(),
            verificationStatus = CandidateVerificationStatus.UNVERIFIED
        )

        // When pin matches
        val merged = CandidateDeduplicator.mergeProvenLanCandidate(
            qrCandidate = qrCandidate,
            lanCandidate = lanCandidate,
            spkiSha256Match = true
        )
        assertNotNull(merged)
        assertEquals("qr:qr-inv-1", merged?.uiKey)
        assertEquals(CandidateVerificationStatus.PINNED_INVITATION, merged?.verificationStatus)
        assertEquals(2, merged?.observations?.size)

        // When pin mismatches
        val failedMerge = CandidateDeduplicator.mergeProvenLanCandidate(
            qrCandidate = qrCandidate,
            lanCandidate = lanCandidate,
            spkiSha256Match = false
        )
        assertNull(failedMerge)
    }

    @Test
    fun testLabelLengthBoundsEnforced() {
        val oversizedLabel = "A".repeat(33)
        try {
            PeerObservation(
                observationId = "obs-oversized",
                source = ObservationSource.NEARBY,
                label = oversizedLabel,
                endpointHandle = "endpoint-1",
                observedAtMs = 1000L
            )
            fail("Expected rejection for label > 32 characters")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maximum limit of 32") == true)
        }
    }
}
