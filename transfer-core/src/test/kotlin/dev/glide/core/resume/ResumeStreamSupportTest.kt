package dev.glide.core.resume

import dev.glide.core.domain.SourceHandle
import dev.glide.core.ports.SourceAccess
import dev.glide.core.ports.SourceMetadata
import dev.glide.core.stream.StreamHasher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class ResumeStreamSupportTest {

    private val handle =
        SourceHandle(
            "resume-source"
        )

    @Test
    fun openRemainingSource_positionsExactlyAndBoundsSuffix() =
        runBlocking {
            val bytes =
                ByteArray(100) {
                    it.toByte()
                }
            val access =
                ByteArraySourceAccess(
                    bytes,
                    declaredSize =
                        bytes.size.toLong()
                )

            val remaining =
                ResumeStreamSupport
                    .openRemainingSource(
                        sourceAccess =
                            access,
                        handle = handle,
                        expectedTotalBytes =
                            100L,
                        startByteOffset =
                            40L,
                        skipBufferSize = 7
                    )

            assertEquals(
                60L,
                remaining.remainingBytes
            )
            assertArrayEquals(
                bytes.copyOfRange(
                    40,
                    100
                ),
                remaining.stream
                    .readBytes()
            )
            assertTrue(
                remaining.stream
                    .proveEof()
            )
        }

    @Test
    fun openRemainingSource_rejectsChangedDeclaredSize() =
        runBlocking {
            val access =
                ByteArraySourceAccess(
                    ByteArray(100),
                    declaredSize = 99L
                )

            try {
                ResumeStreamSupport
                    .openRemainingSource(
                        sourceAccess =
                            access,
                        handle = handle,
                        expectedTotalBytes =
                            100L,
                        startByteOffset =
                            40L
                    )
                fail(
                    "Expected changed source size rejection"
                )
            } catch (
                _: IllegalArgumentException
            ) {
                // Expected.
            }
        }

    @Test
    fun skipExactly_earlyEofFailsClosed() =
        runBlocking {
            try {
                ResumeStreamSupport
                    .skipExactly(
                        ByteArrayInputStream(
                            ByteArray(5)
                        ),
                        bytes = 6L,
                        bufferSize = 2
                    )
                fail(
                    "Expected early EOF"
                )
            } catch (
                _: EOFException
            ) {
                // Expected.
            }
        }

    @Test
    fun copyAndHashWithChunks_canContinueAtAcceptedChunkIndex() =
        runBlocking {
            val bytes =
                "AAAABBBB"
                    .toByteArray()
            val indices =
                mutableListOf<Long>()
            val chunks =
                mutableListOf<
                    ByteArray
                >()

            val result =
                StreamHasher
                    .copyAndHashWithChunks(
                        inputStream =
                            ByteArrayInputStream(
                                bytes
                            ),
                        outputStream = null,
                        expectedBytes =
                            bytes.size
                                .toLong(),
                        chunkSize = 4,
                        startChunkIndex = 2L,
                        bufferSize = 3,
                        onChunk = {
                            index,
                            data ->
                            indices += index
                            chunks += data
                        }
                    )

            assertEquals(
                listOf(2L, 3L),
                indices
            )
            assertArrayEquals(
                "AAAA".toByteArray(),
                chunks[0]
            )
            assertArrayEquals(
                "BBBB".toByteArray(),
                chunks[1]
            )
            assertEquals(
                8L,
                result.sizeBytes
            )
        }

    private class ByteArraySourceAccess(
        private val bytes: ByteArray,
        private val declaredSize: Long
    ) : SourceAccess {

        override suspend fun validateSource(
            handle: SourceHandle
        ): SourceMetadata =
            SourceMetadata(
                name = "resume.bin",
                mimeType =
                    "application/octet-stream",
                sizeBytes =
                    declaredSize
            )

        override suspend fun openSource(
            handle: SourceHandle
        ): InputStream =
            ByteArrayInputStream(
                bytes
            )
    }
}
