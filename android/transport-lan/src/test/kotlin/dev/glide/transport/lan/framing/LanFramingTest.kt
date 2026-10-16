package dev.glide.transport.lan.framing
 
import dev.glide.core.domain.BulkRef
import dev.glide.core.transport.lan.LanRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class LanFramingTest {

    private val testBulkRef = BulkRef("00000000-0000-4000-8000-000000000001")

    @Test
    fun testRoundTripAllRecordKinds() {
        val out = ByteArrayOutputStream()
        val writer = LanRecordWriter(out)

        val bootstrapPayload = """{"v":1,"role":"sender"}""".toByteArray(Charsets.UTF_8)
        val resultPayload = """{"v":1,"status":"accepted"}""".toByteArray(Charsets.UTF_8)
        val controlPayload = """{"type":"HELLO"}""".toByteArray(Charsets.UTF_8)
        val dataChunk = "File data chunk 12345".toByteArray(Charsets.UTF_8)
        val actualBytes = 21L

        writer.writeBootstrap(bootstrapPayload)
        writer.writeBootstrapResult(resultPayload)
        writer.writeControl(controlPayload)
        writer.writeData(testBulkRef, dataChunk)
        writer.writeStreamEnd(testBulkRef, actualBytes)

        val input = ByteArrayInputStream(out.toByteArray())

        val r1 = LanRecordReader.readRecord(input)
        assertTrue(r1 is LanRecord.Bootstrap)
        assertArrayEquals(bootstrapPayload, (r1 as LanRecord.Bootstrap).jsonBytes)

        val r2 = LanRecordReader.readRecord(input)
        assertTrue(r2 is LanRecord.BootstrapResult)
        assertArrayEquals(resultPayload, (r2 as LanRecord.BootstrapResult).jsonBytes)

        val r3 = LanRecordReader.readRecord(input)
        assertTrue(r3 is LanRecord.Control)
        assertArrayEquals(controlPayload, (r3 as LanRecord.Control).payload)

        val r4 = LanRecordReader.readRecord(input)
        assertTrue(r4 is LanRecord.Data)
        assertEquals(testBulkRef, (r4 as LanRecord.Data).bulkRef)
        assertArrayEquals(dataChunk, r4.chunk)

        val r5 = LanRecordReader.readRecord(input)
        assertTrue(r5 is LanRecord.StreamEnd)
        assertEquals(testBulkRef, (r5 as LanRecord.StreamEnd).bulkRef)
        assertEquals(actualBytes, r5.actualBytes)
    }

    @Test
    fun coalescedDataWritesKeepTheExactProfile1BytesAndControlOrder() {
        val chunks = listOf(
            ByteArray(65520) { it.toByte() },
            ByteArray(65520) { (it * 3).toByte() },
            ByteArray(65520) { (it * 7).toByte() },
            ByteArray(42) { 0x5a.toByte() }
        )
        val control = "{\"type\":\"ITEM_END\"}".toByteArray()
        val expected = ByteArrayOutputStream()
        val actual = ByteArrayOutputStream()
        val legacy = LanRecordWriter(expected)
        val coalesced = LanRecordWriter(actual, coalesceDataWrites = true)

        for (chunk in chunks) {
            legacy.writeData(testBulkRef, chunk)
            coalesced.writeData(testBulkRef, chunk)
        }
        legacy.writeStreamEnd(testBulkRef, chunks.sumOf { it.size }.toLong())
        coalesced.writeStreamEnd(testBulkRef, chunks.sumOf { it.size }.toLong())
        legacy.writeControl(control)
        coalesced.writeControl(control)

        assertArrayEquals(expected.toByteArray(), actual.toByteArray())
        val input = ByteArrayInputStream(actual.toByteArray())
        for (chunk in chunks) {
            val record = LanRecordReader.readRecord(input) as LanRecord.Data
            assertEquals(testBulkRef, record.bulkRef)
            assertArrayEquals(chunk, record.chunk)
        }
        assertTrue(LanRecordReader.readRecord(input) is LanRecord.StreamEnd)
        assertArrayEquals(control, (LanRecordReader.readRecord(input) as LanRecord.Control).payload)
    }

    @Test
    fun testOneByteChunkedReading() {
        val out = ByteArrayOutputStream()
        val writer = LanRecordWriter(out)
        val controlPayload = "Framed Control Message Chunked".toByteArray(Charsets.UTF_8)
        writer.writeControl(controlPayload)

        val rawBytes = out.toByteArray()

        // Wrap in an InputStream that yields at most 1 byte per read()
        val oneByteStream = object : InputStream() {
            var index = 0
            override fun read(): Int {
                return if (index < rawBytes.size) rawBytes[index++].toInt() and 0xFF else -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= rawBytes.size) return -1
                val byte = rawBytes[index++].toInt() and 0xFF
                b[off] = byte.toByte()
                return 1
            }
        }

        val record = LanRecordReader.readRecord(oneByteStream)
        assertTrue(record is LanRecord.Control)
        assertArrayEquals(controlPayload, (record as LanRecord.Control).payload)
    }

    @Test
    fun testOversizedControlThrowsFramingException() {
        val header = ByteArray(5)
        header[0] = 0x10.toByte() // CONTROL
        val badLen = 20_000 // exceeds max 16,384
        header[1] = ((badLen shr 24) and 0xFF).toByte()
        header[2] = ((badLen shr 16) and 0xFF).toByte()
        header[3] = ((badLen shr 8) and 0xFF).toByte()
        header[4] = (badLen and 0xFF).toByte()

        val input = ByteArrayInputStream(header + ByteArray(badLen))
        assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(input)
        }
    }

    @Test
    fun testUnknownRecordKindThrowsFramingException() {
        val header = ByteArray(5)
        header[0] = 0x99.toByte() // Unknown kind
        header[4] = 10

        val input = ByteArrayInputStream(header + ByteArray(10))
        assertThrows(LanFramingException::class.java) {
            LanRecordReader.readRecord(input)
        }
    }

    @Test
    fun testTruncatedHeaderThrowsEofException() {
        val partialHeader = ByteArray(3) // only 3 of 5 bytes
        val input = ByteArrayInputStream(partialHeader)
        assertThrows(EOFException::class.java) {
            LanRecordReader.readRecord(input)
        }
    }
}
