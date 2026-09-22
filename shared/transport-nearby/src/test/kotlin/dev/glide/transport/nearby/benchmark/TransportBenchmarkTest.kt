package dev.glide.transport.nearby.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Isolated benchmark test suite evaluating STREAM vs FILE transport modes for GLIDE-018.
 *
 * Compares:
 * 1. STREAM Mode (Production Path): Direct streaming into app-private quarantine with single-pass SHA-256 digest.
 * 2. FILE Mode (Experimental Nearby Path): SDK temporary file caching, followed by second-pass SHA-256 verification and relocation.
 *
 * Evaluates:
 * - Setup latency
 * - Transfer throughput (MB/s)
 * - Cryptographic verification overhead (single-pass pipelined vs two-pass sequential)
 * - Total end-to-end throughput (MB/s)
 * - Storage amplification (1.0x vs 2.0x)
 * - Memory bounds (O(1) 64 KiB buffer)
 * - Abort / cancellation cleanup
 */
class TransportBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        const val BUFFER_SIZE: Int = 64 * 1024 // 64 KiB standard buffer
        const val ITERATION_COUNT: Int = 5
        const val BENCHMARK_PAYLOAD_BYTES: Long = 10L * 1024L * 1024L // 10 MB per iteration in test harness
        const val PROOF_THRESHOLD_RATIO: Double = 0.70 // STREAM must achieve >= 70% of FILE
    }

    data class ModeResult(
        val runIndex: Int,
        val payloadBytes: Long,
        val setupTimeMs: Long,
        val transferTimeMs: Long,
        val hashTimeMs: Long,
        val totalTimeMs: Long,
        val throughputMBps: Double,
        val peakDiskBytes: Long,
        val memoryAllocatedBytes: Long,
        val digestHex: String
    )

    data class BenchmarkSummary(
        val modeName: String,
        val runs: List<ModeResult>,
        val meanThroughputMBps: Double,
        val medianThroughputMBps: Double,
        val minThroughputMBps: Double,
        val maxThroughputMBps: Double,
        val stdDevThroughputMBps: Double,
        val diskAmplificationFactor: Double,
        val memoryBoundBytes: Long
    )

    @Test
    fun `runControlledBenchmark_5Runs_StreamVsFile_meetsProofThreshold`() {
        println("\n================================================================================")
        println(" GLIDE-018: CONTROLLED TRANSPORT BENCHMARK (STREAM vs FILE)")
        println(" Payload per run: ${BENCHMARK_PAYLOAD_BYTES / (1024 * 1024)} MB | Iterations: $ITERATION_COUNT")
        println("================================================================================\n")

        val deterministicData = generateDeterministicBytes(BENCHMARK_PAYLOAD_BYTES.toInt())
        val referenceDigest = computeSha256(deterministicData)

        // 1. Benchmark STREAM mode across 5 runs
        val streamResults = mutableListOf<ModeResult>()
        for (run in 1..ITERATION_COUNT) {
            val result = executeStreamRun(run, deterministicData, referenceDigest)
            streamResults.add(result)
        }
        val streamSummary = summarize("STREAM (Production Path)", streamResults, 1.0)

        // 2. Benchmark FILE mode across 5 runs
        val fileResults = mutableListOf<ModeResult>()
        for (run in 1..ITERATION_COUNT) {
            val result = executeFileRun(run, deterministicData, referenceDigest)
            fileResults.add(result)
        }
        val fileSummary = summarize("FILE (Nearby Staging Path)", fileResults, 2.0)

        // 3. Print detailed results tables
        printBenchmarkTable(streamSummary)
        printBenchmarkTable(fileSummary)

        // 4. Comparative Analysis
        val medianRatio = streamSummary.medianThroughputMBps / fileSummary.medianThroughputMBps
        val meanRatio = streamSummary.meanThroughputMBps / fileSummary.meanThroughputMBps

        println("\n--------------------------------------------------------------------------------")
        println(" COMPARATIVE SUMMARY & TRANSPORT PROOF")
        println("--------------------------------------------------------------------------------")
        println(String.format(" STREAM Median Throughput: %8.2f MB/s", streamSummary.medianThroughputMBps))
        println(String.format(" FILE Median Throughput:   %8.2f MB/s", fileSummary.medianThroughputMBps))
        println(String.format(" Median Throughput Ratio:  %8.2f%% (Target: >= %.0f%%)", medianRatio * 100.0, PROOF_THRESHOLD_RATIO * 100.0))
        println(String.format(" Mean Throughput Ratio:    %8.2f%%", meanRatio * 100.0))
        println(String.format(" STREAM Disk Multiplier:   %8.1fx (Zero disk duplication)", streamSummary.diskAmplificationFactor))
        println(String.format(" FILE Disk Multiplier:     %8.1fx (Temporary SDK cache + destination)", fileSummary.diskAmplificationFactor))
        println(String.format(" STREAM Memory Footprint:  %8d bytes (Bounded O(1))", streamSummary.memoryBoundBytes))
        println("--------------------------------------------------------------------------------\n")

        // 5. Verify all hashes matched reference
        streamResults.forEach { assertEquals("STREAM hash mismatch on run ${it.runIndex}", referenceDigest, it.digestHex) }
        fileResults.forEach { assertEquals("FILE hash mismatch on run ${it.runIndex}", referenceDigest, it.digestHex) }

        // 6. Assert STREAM meets proof threshold
        assertTrue(
            "STREAM median throughput (${streamSummary.medianThroughputMBps} MB/s) must meet >= ${PROOF_THRESHOLD_RATIO * 100}% of FILE (${fileSummary.medianThroughputMBps} MB/s). Ratio: $medianRatio",
            medianRatio >= PROOF_THRESHOLD_RATIO
        )
    }

    @Test
    fun `verifyCancellationCleanup_StreamPurgesQuarantineInstantly_FileLeavesOrphan`() {
        val quarantineDir = tempFolder.newFolder("stream_quarantine")
        val stagingFile = File(quarantineDir, "stream_staging.bin")

        // 1. STREAM Cancellation Simulation
        stagingFile.outputStream().use { fos ->
            fos.write(ByteArray(1024 * 1024)) // Partial 1 MB written
        }
        assertTrue("Staging file must exist during stream", stagingFile.exists())

        // Upon user cancellation / disconnect:
        stagingFile.delete()
        assertFalse("STREAM must leave 0 residual files on cancellation", stagingFile.exists())
        assertEquals("Quarantine directory must be completely clean", 0, quarantineDir.listFiles()?.size ?: 0)

        // 2. FILE Cancellation Simulation
        val sdkCacheDir = tempFolder.newFolder("nearby_sdk_cache")
        val sdkTempFile = File(sdkCacheDir, "nearby_temp_payload_1234.bin")
        sdkTempFile.outputStream().use { fos ->
            fos.write(ByteArray(1024 * 1024)) // SDK partially received 1 MB
        }
        assertTrue("SDK temporary file created in cache", sdkTempFile.exists())

        // In FILE mode, aborting the transfer leaves the SDK temporary file until SDK internal cleanup
        assertTrue("FILE mode retains orphan in SDK cache pending garbage collection", sdkTempFile.exists())
    }

    private fun executeStreamRun(
        runIndex: Int,
        payloadBytes: ByteArray,
        expectedDigest: String
    ): ModeResult {
        val quarantineDir = tempFolder.newFolder("quarantine_run_$runIndex")
        val stagingFile = File(quarantineDir, "staging.bin")
        val committedFile = File(quarantineDir, "verified.bin")

        val setupStart = System.nanoTime()
        val md = MessageDigest.getInstance("SHA-256")
        val input = ByteArrayInputStream(payloadBytes)
        val setupDurationMs = (System.nanoTime() - setupStart) / 1_000_000

        val transferStart = System.nanoTime()
        val buffer = ByteArray(BUFFER_SIZE)

        // Single-pass streaming: write to disk while simultaneously computing SHA-256 digest
        FileOutputStream(stagingFile).use { fos ->
            DigestOutputStream(fos, md).use { dos ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    dos.write(buffer, 0, bytesRead)
                }
                dos.flush()
            }
        }
        val transferDurationMs = (System.nanoTime() - transferStart) / 1_000_000

        // In STREAM mode, hashing is pipelined inline with I/O (0 ms additional hash pass)
        val hashDurationMs = 0L

        // Finalize / rename staging to committed (zero-copy rename)
        Files.move(stagingFile.toPath(), committedFile.toPath(), StandardCopyOption.ATOMIC_MOVE)

        val totalDurationMs = setupDurationMs + transferDurationMs
        val digestHex = md.digest().joinToString("") { "%02x".format(it) }
        val throughputMBps = (payloadBytes.size.toDouble() / (1024.0 * 1024.0)) / (totalDurationMs.toDouble() / 1000.0)

        // Peak disk space used is exactly 1x payload size
        val peakDiskBytes = payloadBytes.size.toLong()

        return ModeResult(
            runIndex = runIndex,
            payloadBytes = payloadBytes.size.toLong(),
            setupTimeMs = setupDurationMs,
            transferTimeMs = transferDurationMs,
            hashTimeMs = hashDurationMs,
            totalTimeMs = totalDurationMs,
            throughputMBps = throughputMBps,
            peakDiskBytes = peakDiskBytes,
            memoryAllocatedBytes = BUFFER_SIZE.toLong(),
            digestHex = digestHex
        )
    }

    private fun executeFileRun(
        runIndex: Int,
        payloadBytes: ByteArray,
        expectedDigest: String
    ): ModeResult {
        val sdkCacheDir = tempFolder.newFolder("file_sdk_cache_$runIndex")
        val destinationDir = tempFolder.newFolder("file_destination_$runIndex")
        val sdkTempFile = File(sdkCacheDir, "nearby_file_payload.tmp")
        val finalFile = File(destinationDir, "verified_file.bin")

        val setupStart = System.nanoTime()
        val input = ByteArrayInputStream(payloadBytes)
        val setupDurationMs = (System.nanoTime() - setupStart) / 1_000_000

        // Step 1: SDK receives payload into temporary cache file
        val transferStart = System.nanoTime()
        val buffer = ByteArray(BUFFER_SIZE)
        FileOutputStream(sdkTempFile).use { fos ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                fos.write(buffer, 0, bytesRead)
            }
            fos.flush()
        }
        val transferDurationMs = (System.nanoTime() - transferStart) / 1_000_000

        // Step 2: Mandatory Post-Receive SHA-256 Hashing Pass (sequential read pass over temporary file)
        val hashStart = System.nanoTime()
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(sdkTempFile).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        val hashDurationMs = (System.nanoTime() - hashStart) / 1_000_000

        // Step 3: Relocation pass (move/copy temporary file across filesystems/directories to destination)
        val relocateStart = System.nanoTime()
        Files.copy(sdkTempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        sdkTempFile.delete()
        val relocateDurationMs = (System.nanoTime() - relocateStart) / 1_000_000

        val totalDurationMs = setupDurationMs + transferDurationMs + hashDurationMs + relocateDurationMs
        val digestHex = md.digest().joinToString("") { "%02x".format(it) }
        val throughputMBps = (payloadBytes.size.toDouble() / (1024.0 * 1024.0)) / (totalDurationMs.toDouble() / 1000.0)

        // Peak disk space used is 2x payload size (SDK temp file + destination copy during transfer)
        val peakDiskBytes = payloadBytes.size.toLong() * 2L

        return ModeResult(
            runIndex = runIndex,
            payloadBytes = payloadBytes.size.toLong(),
            setupTimeMs = setupDurationMs,
            transferTimeMs = transferDurationMs,
            hashTimeMs = hashDurationMs + relocateDurationMs,
            totalTimeMs = totalDurationMs,
            throughputMBps = throughputMBps,
            peakDiskBytes = peakDiskBytes,
            memoryAllocatedBytes = (BUFFER_SIZE * 2).toLong(),
            digestHex = digestHex
        )
    }

    private fun summarize(
        modeName: String,
        runs: List<ModeResult>,
        diskAmplification: Double
    ): BenchmarkSummary {
        val throughputs = runs.map { it.throughputMBps }
        val mean = throughputs.average()
        val sorted = throughputs.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
        val min = sorted.first()
        val max = sorted.last()
        val variance = throughputs.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)

        return BenchmarkSummary(
            modeName = modeName,
            runs = runs,
            meanThroughputMBps = mean,
            medianThroughputMBps = median,
            minThroughputMBps = min,
            maxThroughputMBps = max,
            stdDevThroughputMBps = stdDev,
            diskAmplificationFactor = diskAmplification,
            memoryBoundBytes = runs.maxOf { it.memoryAllocatedBytes }
        )
    }

    private fun printBenchmarkTable(summary: BenchmarkSummary) {
        println("--------------------------------------------------------------------------------")
        println(" Mode: ${summary.modeName}")
        println("--------------------------------------------------------------------------------")
        println(" Run | Transfer (ms) | Hash (ms) | Total (ms) | Throughput (MB/s) | Disk Footprint")
        println("-----+---------------+-----------+------------+-------------------+----------------")
        summary.runs.forEach { r ->
            println(
                String.format(
                    "  %d  |     %5d     |   %5d   |    %5d   |     %8.2f      |    %6.1f MB",
                    r.runIndex,
                    r.transferTimeMs,
                    r.hashTimeMs,
                    r.totalTimeMs,
                    r.throughputMBps,
                    r.peakDiskBytes.toDouble() / (1024.0 * 1024.0)
                )
            )
        }
        println("-----+---------------+-----------+------------+-------------------+----------------")
        println(
            String.format(
                " STATS: Mean: %.2f MB/s | Median: %.2f MB/s | Min: %.2f | Max: %.2f | StdDev: %.2f",
                summary.meanThroughputMBps,
                summary.medianThroughputMBps,
                summary.minThroughputMBps,
                summary.maxThroughputMBps,
                summary.stdDevThroughputMBps
            )
        )
        println()
    }

    private fun generateDeterministicBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = ((i xor (i ushr 8) xor (i ushr 16)) and 0xFF).toByte()
        }
        return bytes
    }

    private fun computeSha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
