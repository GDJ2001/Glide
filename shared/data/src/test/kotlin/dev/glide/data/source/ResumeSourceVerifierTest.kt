package dev.glide.data.source

import dev.glide.core.domain.SourceHandle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ResumeSourceVerifierTest {

    private val handle =
        SourceHandle(
            "content://dev.glide.test/source/42"
        )

    @Test
    fun snapshot_streamsBoundedChunks_andRevalidationMatchesCurrentSource() =
        runTest {
            var payload =
                ByteArray(
                    ResumeSourceVerifier
                        .MIN_CHUNK_SIZE +
                        17
                ) { index ->
                    (index % 251)
                        .toByte()
                }
            val resolver =
                FakeContentResolverWrapper()
                    .apply {
                        metadataResult =
                            SourceQueryMetadata(
                                displayName =
                                    "resume.bin",
                                sizeBytes =
                                    payload.size
                                        .toLong()
                            )
                        mimeType =
                            "application/octet-stream"
                        streamProvider = {
                            ByteArrayInputStream(
                                payload
                            )
                        }
                    }
            val verifier =
                ResumeSourceVerifier(
                    AndroidSourceAccess(
                        resolver
                    )
                )

            val snapshot =
                verifier.snapshot(
                    handle,
                    ResumeSourceVerifier
                        .MIN_CHUNK_SIZE
                )

            assertEquals(
                2,
                snapshot.chunkHashesHex
                    .size
            )
            assertEquals(
                payload.size.toLong(),
                snapshot.totalBytes
            )
            assertEquals(
                64,
                snapshot.sourceIdentity
                    .length
            )
            assertEquals(
                64,
                snapshot.chunkRootHashHex
                    .length
            )
            assertEquals(
                64,
                snapshot.wholeFileSha256Hex
                    .length
            )
            assertEquals(
                snapshot.chunkRootHashHex,
                snapshot.digestTree()
                    .rootHashHex
            )

            val unchanged =
                verifier.revalidate(
                    handle =
                        handle,
                    expectedSourceIdentity =
                        snapshot.sourceIdentity,
                    expectedTotalBytes =
                        snapshot.totalBytes,
                    expectedChunkSize =
                        snapshot.chunkSize,
                    expectedChunkRootHashHex =
                        snapshot.chunkRootHashHex,
                    expectedWholeFileSha256Hex =
                        snapshot
                            .wholeFileSha256Hex
                )
            assertTrue(
                unchanged is
                    ResumeSourceRevalidationResult.Unchanged
            )

            payload =
                payload.copyOf()
                    .also {
                        it[0] =
                            (it[0].toInt() xor 0x7f)
                                .toByte()
                    }

            val changed =
                verifier.revalidate(
                    handle =
                        handle,
                    expectedSourceIdentity =
                        snapshot.sourceIdentity,
                    expectedTotalBytes =
                        snapshot.totalBytes,
                    expectedChunkSize =
                        snapshot.chunkSize,
                    expectedChunkRootHashHex =
                        snapshot.chunkRootHashHex,
                    expectedWholeFileSha256Hex =
                        snapshot
                            .wholeFileSha256Hex
                )
            assertTrue(
                changed is
                    ResumeSourceRevalidationResult.Changed
            )
            val changedSnapshot =
                (changed as
                    ResumeSourceRevalidationResult.Changed)
                    .current
            assertNotEquals(
                snapshot.chunkRootHashHex,
                changedSnapshot
                    .chunkRootHashHex
            )
        }

    @Test
    fun revalidate_revokedPermission_returnsUnavailable() =
        runTest {
            val payload =
                ByteArray(
                    ResumeSourceVerifier
                        .MIN_CHUNK_SIZE
                ) { 3 }
            val resolver =
                FakeContentResolverWrapper()
                    .apply {
                        metadataResult =
                            SourceQueryMetadata(
                                displayName =
                                    "resume.bin",
                                sizeBytes =
                                    payload.size
                                        .toLong()
                            )
                        mimeType =
                            "application/octet-stream"
                        streamProvider = {
                            ByteArrayInputStream(
                                payload
                            )
                        }
                    }
            val verifier =
                ResumeSourceVerifier(
                    AndroidSourceAccess(
                        resolver
                    )
                )
            val snapshot =
                verifier.snapshot(
                    handle,
                    ResumeSourceVerifier
                        .MIN_CHUNK_SIZE
                )

            resolver.streamProvider = {
                throw SecurityException(
                    "grant revoked"
                )
            }

            val result =
                verifier.revalidate(
                    handle =
                        handle,
                    expectedSourceIdentity =
                        snapshot.sourceIdentity,
                    expectedTotalBytes =
                        snapshot.totalBytes,
                    expectedChunkSize =
                        snapshot.chunkSize,
                    expectedChunkRootHashHex =
                        snapshot.chunkRootHashHex,
                    expectedWholeFileSha256Hex =
                        snapshot
                            .wholeFileSha256Hex
                )

            assertTrue(
                result is
                    ResumeSourceRevalidationResult.Unavailable
            )
        }

    @Test
    fun snapshot_rejectsSourceGrowthBeyondDeclaredLength() =
        runTest {
            val declared =
                ResumeSourceVerifier
                    .MIN_CHUNK_SIZE
            val payload =
                ByteArray(
                    declared + 1
                ) { 9 }
            val resolver =
                FakeContentResolverWrapper()
                    .apply {
                        metadataResult =
                            SourceQueryMetadata(
                                displayName =
                                    "growing.bin",
                                sizeBytes =
                                    declared.toLong()
                            )
                        mimeType =
                            "application/octet-stream"
                        streamProvider = {
                            ByteArrayInputStream(
                                payload
                            )
                        }
                    }
            val verifier =
                ResumeSourceVerifier(
                    AndroidSourceAccess(
                        resolver
                    )
                )

            var failed = false
            try {
                verifier.snapshot(
                    handle,
                    ResumeSourceVerifier
                        .MIN_CHUNK_SIZE
                )
            } catch (_: IllegalArgumentException) {
                failed = true
            }

            assertTrue(
                "Source growth must fail the resume snapshot",
                failed
            )
        }
}
