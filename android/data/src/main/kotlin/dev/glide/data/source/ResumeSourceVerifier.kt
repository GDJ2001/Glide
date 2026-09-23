package dev.glide.data.source

import dev.glide.core.domain.ProtocolLimits
import dev.glide.core.domain.SourceHandle
import dev.glide.core.ports.SourceAccess
import dev.glide.core.resume.ContentDigestTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Re-readable source authority used by HARD-022 before Android acts as a resume sender.
 *
 * A URI/handle alone is never treated as proof that the bytes are unchanged. Every resume attempt
 * re-validates current source permission/readability and hashes the complete current source with a
 * bounded chunk buffer. The retained journal may be used only when size, source identity, chunk
 * root and whole-file SHA-256 still match.
 */
class ResumeSourceVerifier(
    private val sourceAccess: SourceAccess,
    private val ioDispatcher:
        CoroutineDispatcher =
        Dispatchers.IO
) {

    suspend fun snapshot(
        handle: SourceHandle,
        chunkSize: Int =
            DEFAULT_CHUNK_SIZE
    ): ResumeSourceSnapshot =
        withContext(ioDispatcher) {
            require(
                chunkSize in
                    MIN_CHUNK_SIZE..
                    MAX_CHUNK_SIZE
            ) {
                "Resume chunk size must be in " +
                    "$MIN_CHUNK_SIZE..$MAX_CHUNK_SIZE"
            }

            val before =
                sourceAccess
                    .validateSource(handle)
            require(
                before.sizeBytes in
                    1..ProtocolLimits
                        .MAX_ITEM_BYTES
            ) {
                "Only non-empty bounded sources can use true resume"
            }

            val expectedChunks =
                (
                    before.sizeBytes +
                        chunkSize - 1L
                    ) / chunkSize
            require(
                expectedChunks <=
                    MAX_CHUNK_COUNT
            ) {
                "Resume chunk manifest exceeds the bounded chunk-count policy"
            }

            val chunkHashes =
                ArrayList<ByteArray>(
                    expectedChunks.toInt()
                )
            val wholeDigest =
                MessageDigest.getInstance(
                    "SHA-256"
                )
            var totalRead = 0L

            sourceAccess
                .openSource(handle)
                .use { input ->
                    val buffer =
                        ByteArray(
                            chunkSize
                        )

                    while (totalRead <
                        before.sizeBytes
                    ) {
                        coroutineContext
                            .ensureActive()

                        val remaining =
                            before.sizeBytes -
                                totalRead
                        val wanted =
                            minOf(
                                chunkSize.toLong(),
                                remaining
                            ).toInt()
                        val read =
                            readExactlyOrEof(
                                input,
                                buffer,
                                wanted
                            )
                        require(read == wanted) {
                            "Source ended before its declared size during resume verification"
                        }

                        val chunkDigest =
                            MessageDigest
                                .getInstance(
                                    "SHA-256"
                                )
                        chunkDigest.update(
                            buffer,
                            0,
                            read
                        )
                        wholeDigest.update(
                            buffer,
                            0,
                            read
                        )
                        chunkHashes.add(
                            chunkDigest.digest()
                        )
                        totalRead +=
                            read.toLong()
                    }

                    require(
                        input.read() == -1
                    ) {
                        "Source grew beyond its declared size during resume verification"
                    }
                }

            val after =
                sourceAccess
                    .validateSource(handle)
            require(
                after.sizeBytes ==
                    before.sizeBytes
            ) {
                "Source size changed during resume verification"
            }

            val digestTree =
                ContentDigestTree(
                    chunkHashes
                )

            ResumeSourceSnapshot(
                sourceIdentity =
                    sourceIdentity(handle),
                totalBytes =
                    before.sizeBytes,
                chunkSize =
                    chunkSize,
                chunkRootHashHex =
                    digestTree.rootHashHex,
                chunkHashesHex =
                    chunkHashes.map(
                        ::toHex
                    ),
                wholeFileSha256Hex =
                    toHex(
                        wholeDigest.digest()
                    )
            )
        }

    suspend fun revalidate(
        handle: SourceHandle,
        expectedSourceIdentity: String,
        expectedTotalBytes: Long,
        expectedChunkSize: Int,
        expectedChunkRootHashHex: String,
        expectedWholeFileSha256Hex: String
    ): ResumeSourceRevalidationResult {
        val snapshot =
            try {
                snapshot(
                    handle,
                    expectedChunkSize
                )
            } catch (error: Throwable) {
                if (error is
                    kotlinx.coroutines
                        .CancellationException
                ) {
                    throw error
                }
                return ResumeSourceRevalidationResult.Unavailable(
                        error.message
                            ?: "Source is unavailable"
                    )
            }

        return if (
            snapshot.sourceIdentity ==
                expectedSourceIdentity &&
            snapshot.totalBytes ==
                expectedTotalBytes &&
            snapshot.chunkRootHashHex ==
                expectedChunkRootHashHex &&
            snapshot.wholeFileSha256Hex ==
                expectedWholeFileSha256Hex
        ) {
            ResumeSourceRevalidationResult.Unchanged(snapshot)
        } else {
            ResumeSourceRevalidationResult.Changed(snapshot)
        }
    }

    private fun readExactlyOrEof(
        input: InputStream,
        buffer: ByteArray,
        wanted: Int
    ): Int {
        var offset = 0
        while (offset < wanted) {
            val read =
                input.read(
                    buffer,
                    offset,
                    wanted - offset
                )
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            offset += read
        }
        return offset
    }

    private fun sourceIdentity(
        handle: SourceHandle
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )
        digest.update(
            SOURCE_IDENTITY_DOMAIN
                .toByteArray(
                    Charsets.UTF_8
                )
        )
        digest.update(
            byteArrayOf(0)
        )
        digest.update(
            handle.value
                .toByteArray(
                    Charsets.UTF_8
                )
        )
        return toHex(
            digest.digest()
        )
    }

    private fun toHex(
        value: ByteArray
    ): String =
        value.joinToString("") {
            "%02x".format(it)
        }

    companion object {
        const val MIN_CHUNK_SIZE =
            64 * 1024
        const val DEFAULT_CHUNK_SIZE =
            1024 * 1024
        const val MAX_CHUNK_SIZE =
            16 * 1024 * 1024
        const val MAX_CHUNK_COUNT =
            1_048_576L
        private const val
            SOURCE_IDENTITY_DOMAIN =
            "GLIDE-ANDROID-SOURCE-V1"
    }
}

data class ResumeSourceSnapshot(
    val sourceIdentity: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val chunkRootHashHex: String,
    val chunkHashesHex:
        List<String>,
    val wholeFileSha256Hex: String
) {
    fun digestTree():
        ContentDigestTree =
        ContentDigestTree(
            chunkHashesHex.map {
                hexToBytes(it)
            }
        )

    private fun hexToBytes(
        value: String
    ): ByteArray {
        require(
            value.length == 64 &&
                value.all {
                    it in '0'..'9' ||
                        it in 'a'..'f'
                }
        ) {
            "Chunk digest must be canonical lowercase SHA-256"
        }

        return ByteArray(
            value.length / 2
        ) { index ->
            value.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16)
                .toByte()
        }
    }
}

sealed interface ResumeSourceRevalidationResult {
    data class Unchanged(
        val snapshot:
            ResumeSourceSnapshot
    ) : ResumeSourceRevalidationResult

    data class Changed(
        val current:
            ResumeSourceSnapshot
    ) : ResumeSourceRevalidationResult

    data class Unavailable(
        val reason: String
    ) : ResumeSourceRevalidationResult
}
