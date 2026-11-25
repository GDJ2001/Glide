package dev.glide.core.resume

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.glide.core.pairing.Profile2Message
import dev.glide.core.pairing.Profile2WireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ResumeBatchManifestTest {
    @Serializable
    private data class Fixture(val manifest: ResumeBatchManifest, val sha256: String)

    @Test
    fun matchesSharedDigestAndBindsItemOrder() {
        val file = fixture("resume_batch_manifest_v1.json")
        val fixture = Json.decodeFromString<Fixture>(file.readText())
        assertEquals(fixture.sha256, fixture.manifest.digest().value)
        val reversed = fixture.manifest.copy(items = fixture.manifest.items.reversed())
        assertNotEquals(fixture.sha256, reversed.digest().value)
    }

    @Test
    fun roundTripsSharedTrustedResumeOpening() {
        val wire = fixture("profile2_trusted_resume_open.json").readText().trim().encodeToByteArray()
        val parsed = Profile2WireCodec.decode(wire) as Profile2Message.SessionOpen
        assertEquals("receiver", parsed.initiatorRole)
        assertArrayEquals(wire, Profile2WireCodec.encode(parsed))
    }

    @Test
    fun onlyMatchingOrderedVerifiedReceiptsAdvanceTheBatch() {
        val manifest = Json.decodeFromString<Fixture>(fixture("resume_batch_manifest_v1.json").readText()).manifest
        val first = manifest.items[0]
        val second = manifest.items[1]
        assertThrows(IllegalArgumentException::class.java) {
            manifest.recordVerifiedItem(0, second.id, second.sizeBytes, second.digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.recordVerifiedItem(0, first.id, first.sizeBytes, second.digest)
        }
        assertEquals(1, manifest.recordVerifiedItem(0, first.id, first.sizeBytes, first.digest))
        assertEquals(1, manifest.recordVerifiedItem(1, first.id, first.sizeBytes, first.digest))
        assertEquals(2, manifest.recordVerifiedItem(1, second.id, second.sizeBytes, second.digest))
    }

    private fun fixture(name: String): File =
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .map { File(it, "assets/protocol/fixtures/$name") }
            .first { it.isFile }
}
