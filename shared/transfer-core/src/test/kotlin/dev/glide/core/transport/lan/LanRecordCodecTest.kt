package dev.glide.core.transport.lan

import dev.glide.core.domain.BulkRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class LanRecordCodecTest {

    companion object {
        private const val TEST_INVITATION_ID = "00000000-0000-4000-8000-000000000001"
        private const val TEST_SESSION_ID = "00000000-0000-4000-8000-000000000002"
        private const val TEST_TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
        private const val TEST_INVITATION_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private const val TEST_BULK_REF = "00000000-0000-4000-8000-000000000001"

        @BeforeClass
        @JvmStatic
        fun ensureGoldenFixtures() {
            val fixturesDir = File("src/test/resources/fixtures/lan")
            if (!fixturesDir.exists()) {
                fixturesDir.mkdirs()
            }

            // 1. BOOTSTRAP
            val bootstrapMsg = LanBootstrapMessage(
                v = 1,
                invitationId = TEST_INVITATION_ID,
                token = TEST_TOKEN,
                invitationHash = TEST_INVITATION_HASH,
                sessionId = TEST_SESSION_ID,
                role = "sender"
            )
            val bootstrapRecord = LanRecord.Bootstrap(LanBootstrapCodec.encodeBootstrap(bootstrapMsg))
            writeFixtureIfAbsent(File(fixturesDir, "bootstrap.bin"), LanRecordCodec.encode(bootstrapRecord))

            // 2. BOOTSTRAP_RESULT (accepted)
            val acceptedResult = LanBootstrapResult.accepted(TEST_SESSION_ID)
            val acceptedRecord = LanRecord.BootstrapResult(LanBootstrapCodec.encodeResult(acceptedResult))
            writeFixtureIfAbsent(File(fixturesDir, "bootstrap_result_accepted.bin"), LanRecordCodec.encode(acceptedRecord))

            // 3. BOOTSTRAP_RESULT (rejected)
            val rejectedResult = LanBootstrapResult.rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED)
            val rejectedRecord = LanRecord.BootstrapResult(LanBootstrapCodec.encodeResult(rejectedResult))
            writeFixtureIfAbsent(File(fixturesDir, "bootstrap_result_rejected.bin"), LanRecordCodec.encode(rejectedRecord))

            // 4. CONTROL (wrapping fixtures/hello.json)
            val helloStream = LanRecordCodecTest::class.java.classLoader?.getResourceAsStream("fixtures/hello.json")
                ?: error("fixtures/hello.json not found")
            val helloBytes = helloStream.readAllBytes()
            val controlRecord = LanRecord.Control(helloBytes)
            writeFixtureIfAbsent(File(fixturesDir, "control_hello.bin"), LanRecordCodec.encode(controlRecord))

            // 5. DATA (UUID + chunk)
            val sampleChunk = "Glide-LAN-Profile-1-Binary-Chunk".toByteArray(StandardCharsets.UTF_8)
            val dataRecord = LanRecord.Data(BulkRef(TEST_BULK_REF), sampleChunk)
            writeFixtureIfAbsent(File(fixturesDir, "data_chunk.bin"), LanRecordCodec.encode(dataRecord))

            // 6. STREAM_END (empty file, actualBytes = 0)
            val streamEndEmpty = LanRecord.StreamEnd(BulkRef(TEST_BULK_REF), 0L)
            writeFixtureIfAbsent(File(fixturesDir, "stream_end_empty.bin"), LanRecordCodec.encode(streamEndEmpty))

            // 7. STREAM_END (non-empty file, actualBytes = 32)
            val streamEndNonEmpty = LanRecord.StreamEnd(BulkRef(TEST_BULK_REF), 32L)
            writeFixtureIfAbsent(File(fixturesDir, "stream_end_nonempty.bin"), LanRecordCodec.encode(streamEndNonEmpty))
        }

        private fun writeFixtureIfAbsent(file: File, bytes: ByteArray) {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
    }

    private fun loadFixture(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
        val raw = if (stream != null) {
            stream.readAllBytes()
        } else {
            val file = File("src/test/resources/$path")
            if (file.exists()) {
                file.readBytes()
            } else {
                error("Fixture not found: $path")
            }
        }
        return if (path.endsWith(".json")) {
            String(raw, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        } else {
            raw
        }
    }

    @Test
    fun testGoldenBootstrapRecord() {
        val bytes = loadFixture("fixtures/lan/bootstrap.bin")
        assertEquals(0x01.toByte(), bytes[0])

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.Bootstrap)
        val bootstrap = record as LanRecord.Bootstrap

        val msg = LanBootstrapCodec.decodeBootstrap(bootstrap.jsonBytes)
        assertEquals(1, msg.v)
        assertEquals(TEST_INVITATION_ID, msg.invitationId)
        assertEquals(TEST_TOKEN, msg.token)
        assertEquals(TEST_INVITATION_HASH, msg.invitationHash)
        assertEquals(TEST_SESSION_ID, msg.sessionId)
        assertEquals("sender", msg.role)

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testGoldenBootstrapResultAccepted() {
        val bytes = loadFixture("fixtures/lan/bootstrap_result_accepted.bin")
        assertEquals(0x02.toByte(), bytes[0])

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.BootstrapResult)
        val result = record as LanRecord.BootstrapResult

        val decoded = LanBootstrapCodec.decodeResult(result.jsonBytes)
        assertEquals(1, decoded.v)
        assertEquals("accepted", decoded.status)
        assertEquals(TEST_SESSION_ID, decoded.sessionId)
        assertTrue(decoded.isAccepted)

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testGoldenBootstrapResultRejected() {
        val bytes = loadFixture("fixtures/lan/bootstrap_result_rejected.bin")
        assertEquals(0x02.toByte(), bytes[0])

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.BootstrapResult)
        val result = record as LanRecord.BootstrapResult

        val decoded = LanBootstrapCodec.decodeResult(result.jsonBytes)
        assertEquals(1, decoded.v)
        assertEquals("rejected", decoded.status)
        assertEquals("AUTHENTICATION_FAILED", decoded.code)
        assertFalse(decoded.isAccepted)

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testGoldenControlHelloRecord() {
        val bytes = loadFixture("fixtures/lan/control_hello.bin")
        assertEquals(0x10.toByte(), bytes[0])

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.Control)
        val control = record as LanRecord.Control

        val helloFixture = loadFixture("fixtures/hello.json")
        assertArrayEquals(helloFixture, control.payload)

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testGoldenDataChunkRecord() {
        val bytes = loadFixture("fixtures/lan/data_chunk.bin")
        assertEquals(0x20.toByte(), bytes[0])

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.Data)
        val data = record as LanRecord.Data

        assertEquals(TEST_BULK_REF, data.bulkRef.value)
        assertEquals("Glide-LAN-Profile-1-Binary-Chunk", String(data.chunk, StandardCharsets.UTF_8))

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testGoldenStreamEndEmptyRecord() {
        val bytes = loadFixture("fixtures/lan/stream_end_empty.bin")
        assertEquals(0x21.toByte(), bytes[0])
        assertEquals(LanRecordCodec.HEADER_SIZE + 24, bytes.size)

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.StreamEnd)
        val end = record as LanRecord.StreamEnd

        assertEquals(TEST_BULK_REF, end.bulkRef.value)
        assertEquals(0L, end.actualBytes)

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testGoldenStreamEndNonEmptyRecord() {
        val bytes = loadFixture("fixtures/lan/stream_end_nonempty.bin")
        assertEquals(0x21.toByte(), bytes[0])
        assertEquals(LanRecordCodec.HEADER_SIZE + 24, bytes.size)

        val record = LanRecordCodec.decode(bytes)
        assertTrue(record is LanRecord.StreamEnd)
        val end = record as LanRecord.StreamEnd

        assertEquals(TEST_BULK_REF, end.bulkRef.value)
        assertEquals(32L, end.actualBytes)

        val reEncoded = LanRecordCodec.encode(record)
        assertArrayEquals(bytes, reEncoded)
    }

    @Test
    fun testOneByteIncrementalFeeding() {
        val fixtures = listOf(
            "fixtures/lan/bootstrap.bin",
            "fixtures/lan/bootstrap_result_accepted.bin",
            "fixtures/lan/bootstrap_result_rejected.bin",
            "fixtures/lan/control_hello.bin",
            "fixtures/lan/data_chunk.bin",
            "fixtures/lan/stream_end_empty.bin",
            "fixtures/lan/stream_end_nonempty.bin"
        )

        for (path in fixtures) {
            val bytes = loadFixture(path)
            val accumulator = LanRecordAccumulator()
            val emitted = mutableListOf<LanRecord>()

            for (i in bytes.indices) {
                val singleByte = byteArrayOf(bytes[i])
                emitted.addAll(accumulator.feed(singleByte))
            }

            assertEquals("Expected exactly 1 record from 1-byte feeding for $path", 1, emitted.size)
            val reEncoded = LanRecordCodec.encode(emitted[0])
            assertArrayEquals("Record re-encoded bytes must match original for $path", bytes, reEncoded)
        }
    }

    @Test
    fun testCoalescedFramesInSingleChunk() {
        val f1 = loadFixture("fixtures/lan/bootstrap.bin")
        val f2 = loadFixture("fixtures/lan/control_hello.bin")
        val f3 = loadFixture("fixtures/lan/data_chunk.bin")
        val f4 = loadFixture("fixtures/lan/stream_end_nonempty.bin")

        val coalesced = ByteArray(f1.size + f2.size + f3.size + f4.size)
        var offset = 0
        System.arraycopy(f1, 0, coalesced, offset, f1.size); offset += f1.size
        System.arraycopy(f2, 0, coalesced, offset, f2.size); offset += f2.size
        System.arraycopy(f3, 0, coalesced, offset, f3.size); offset += f3.size
        System.arraycopy(f4, 0, coalesced, offset, f4.size)

        val accumulator = LanRecordAccumulator()
        val emitted = accumulator.feed(coalesced)

        assertEquals(4, emitted.size)
        assertTrue(emitted[0] is LanRecord.Bootstrap)
        assertTrue(emitted[1] is LanRecord.Control)
        assertTrue(emitted[2] is LanRecord.Data)
        assertTrue(emitted[3] is LanRecord.StreamEnd)
    }

    @Test
    fun testHeaderTruncations() {
        val fullFrame = loadFixture("fixtures/lan/control_hello.bin")

        for (truncLen in 0 until LanRecordCodec.HEADER_SIZE) {
            val truncated = fullFrame.copyOfRange(0, truncLen)
            try {
                LanRecordCodec.decode(truncated)
                fail("Expected IllegalArgumentException for truncated header of length $truncLen")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("less than minimum header size") == true ||
                    e.message?.contains("Insufficient bytes") == true)
            }

            val inStream = ByteArrayInputStream(truncated)
            try {
                LanRecordCodec.readRecord(inStream)
                fail("Expected EOFException for stream truncated at $truncLen bytes")
            } catch (e: EOFException) {
                // Expected
            }
        }
    }

    @Test
    fun testSignedLengthOverflowRejected() {
        // Construct header with kind = CONTROL (0x10) and length = 0x80000001 (highest bit set)
        val badHeader = byteArrayOf(0x10, 0x80.toByte(), 0x00, 0x00, 0x01)
        try {
            LanRecordCodec.parseHeader(badHeader)
            fail("Expected signed-length overflow rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Signed length overflow") == true)
        }
    }

    @Test
    fun testHugeDeclaredLengthRejectedBeforeAllocation() {
        // Construct header with length 1,000,000 (> 65536 ceiling)
        val badHeader = byteArrayOf(0x10, 0x00, 0x0F, 0x42.toByte(), 0x40.toByte())
        try {
            LanRecordCodec.parseHeader(badHeader)
            fail("Expected rejection for huge declared length")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds maximum ceiling") == true)
        }
    }

    @Test
    fun testKindSpecificBoundsEnforced() {
        // BOOTSTRAP declared with 3000 bytes (exceeds 2048 max)
        val badBootstrapHeader = byteArrayOf(0x01, 0x00, 0x00, 0x0B, 0xB8.toByte())
        try {
            LanRecordCodec.parseHeader(badBootstrapHeader)
            fail("Expected rejection for BOOTSTRAP exceeding 2048 bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("out of bounds for kind BOOTSTRAP") == true)
        }

        // BOOTSTRAP_RESULT declared with 600 bytes (exceeds 512 max)
        val badResultHeader = byteArrayOf(0x02, 0x00, 0x00, 0x02, 0x58.toByte())
        try {
            LanRecordCodec.parseHeader(badResultHeader)
            fail("Expected rejection for BOOTSTRAP_RESULT exceeding 512 bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("out of bounds for kind BOOTSTRAP_RESULT") == true)
        }

        // CONTROL declared with 17000 bytes (exceeds 16384 max)
        val badControlHeader = byteArrayOf(0x10, 0x00, 0x00, 0x42, 0x68.toByte())
        try {
            LanRecordCodec.parseHeader(badControlHeader)
            fail("Expected rejection for CONTROL exceeding 16384 bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("out of bounds for kind CONTROL") == true)
        }

        // STREAM_END declared with 25 bytes (!= 24)
        val badStreamEndHeader = byteArrayOf(0x21, 0x00, 0x00, 0x00, 0x19)
        try {
            LanRecordCodec.parseHeader(badStreamEndHeader)
            fail("Expected rejection for STREAM_END != 24 bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("out of bounds for kind STREAM_END") == true)
        }
    }

    @Test
    fun testUnsupportedKindRejected() {
        val unsupportedKinds = listOf(0x00.toByte(), 0x03.toByte(), 0x0F.toByte(), 0x11.toByte(), 0x22.toByte(), 0x30.toByte(), 0xFF.toByte())
        for (kind in unsupportedKinds) {
            val header = byteArrayOf(kind, 0x00, 0x00, 0x00, 0x10)
            try {
                LanRecordCodec.parseHeader(header)
                fail("Expected rejection for unsupported kind 0x%02X".format(kind))
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Unsupported record kind") == true)
            }
        }
    }

    @Test
    fun testDataEmptyChunkRejected() {
        try {
            LanRecord.Data(BulkRef(TEST_BULK_REF), ByteArray(0))
            fail("Expected rejection for empty DATA chunk")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("chunk size 0 out of bounds") == true)
        }
    }

    @Test
    fun testDataOversizedChunkRejected() {
        try {
            LanRecord.Data(BulkRef(TEST_BULK_REF), ByteArray(65521))
            fail("Expected rejection for chunk exceeding 65520 bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("out of bounds") == true)
        }
    }

    @Test
    fun testInterleavedControlAndData() {
        val chunk = "ChunkA".toByteArray()
        val data1 = LanRecord.Data(BulkRef(TEST_BULK_REF), chunk)
        val ctrl = LanRecord.Control("""{"type":"ping"}""".toByteArray())
        val data2 = LanRecord.Data(BulkRef(TEST_BULK_REF), "ChunkB".toByteArray())

        val streamBytes = LanRecordCodec.encode(data1) + LanRecordCodec.encode(ctrl) + LanRecordCodec.encode(data2)

        val accumulator = LanRecordAccumulator()
        val emitted = accumulator.feed(streamBytes)

        assertEquals(3, emitted.size)
        assertTrue(emitted[0] is LanRecord.Data)
        assertEquals("ChunkA", String((emitted[0] as LanRecord.Data).chunk))
        assertTrue(emitted[1] is LanRecord.Control)
        assertTrue(emitted[2] is LanRecord.Data)
        assertEquals("ChunkB", String((emitted[2] as LanRecord.Data).chunk))
    }

    @Test
    fun testUuidNetworkByteOrder() {
        // Test standard RFC 4122 vector: 00000000-0000-4000-8000-000000000001
        val uuid = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val networkBytes = LanUuidCodec.toNetworkBytes(uuid)

        val expectedBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00,
            0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01
        )
        assertArrayEquals(expectedBytes, networkBytes)

        val decodedUuid = LanUuidCodec.fromNetworkBytes(networkBytes)
        assertEquals(uuid, decodedUuid)

        val bulkRef = BulkRef("00000000-0000-4000-8000-000000000001")
        val bulkRefBytes = LanUuidCodec.bulkRefToNetworkBytes(bulkRef)
        assertArrayEquals(expectedBytes, bulkRefBytes)

        val decodedBulkRef = LanUuidCodec.networkBytesToBulkRef(bulkRefBytes)
        assertEquals(bulkRef, decodedBulkRef)
    }

    @Test
    fun testReadRecordFromStream() {
        val record = LanRecord.Control("hello".toByteArray())
        val frame = LanRecordCodec.encode(record)

        val inStream = ByteArrayInputStream(frame)
        val decoded = LanRecordCodec.readRecord(inStream)
        assertEquals(record, decoded)

        // Reading past EOF should throw EOFException cleanly
        try {
            LanRecordCodec.readRecord(inStream)
            fail("Expected clean EOFException")
        } catch (e: EOFException) {
            assertTrue(e.message?.contains("cleanly") == true)
        }
    }
}
