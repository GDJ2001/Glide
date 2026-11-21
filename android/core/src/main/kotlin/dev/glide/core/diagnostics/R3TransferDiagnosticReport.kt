package dev.glide.core.diagnostics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

object R3DiagnosticConstants {
    const val SCHEMA_VERSION = "glide-r3-diagnostic-v1"
    const val SAMPLE_SCHEMA_VERSION = "glide-r3-sample-v1"
    const val UNAVAILABLE = "unavailable"
    const val MAX_LOG_FILE_BYTES = 5L * 1024L * 1024L // 5 MiB
    const val MAX_LOG_FILES = 4
    const val MAX_ONE_SECOND_SAMPLES = 10_800 // 3 hours @ 1 Hz
    const val MAX_FIVE_SECOND_WINDOWS = 2_160 // 3 hours / 5s
    const val MAX_TERMINAL_MESSAGE_LENGTH = 512
    const val DECIMAL_BYTES_PER_MB = 1_000_000.0
    const val BYTES_PER_MIB = 1_048_576.0
}

@Serializable(with = R3Double.Serializer::class)
sealed interface R3Double {
    data class Available(val value: Double) : R3Double
    data object Unavailable : R3Double

    object Serializer : KSerializer<R3Double> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("R3Double", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: R3Double) {
            when (value) {
                is Available -> encoder.encodeDouble(value.value)
                Unavailable -> encoder.encodeString(R3DiagnosticConstants.UNAVAILABLE)
            }
        }

        override fun deserialize(decoder: Decoder): R3Double {
            val jsonDecoder = decoder as? JsonDecoder
            if (jsonDecoder != null) {
                val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
                if (primitive != null) {
                    if (primitive.isString && primitive.content == R3DiagnosticConstants.UNAVAILABLE) {
                        return Unavailable
                    }
                    primitive.doubleOrNull?.let { return Available(it) }
                }
            }
            val raw = decoder.decodeString()
            return if (raw == R3DiagnosticConstants.UNAVAILABLE) {
                Unavailable
            } else {
                raw.toDoubleOrNull()?.let { Available(it) } ?: Unavailable
            }
        }
    }

    companion object {
        fun of(value: Double?): R3Double =
            if (value != null && value.isFinite()) Available(value) else Unavailable
    }
}

@Serializable(with = R3Long.Serializer::class)
sealed interface R3Long {
    data class Available(val value: Long) : R3Long
    data object Unavailable : R3Long

    object Serializer : KSerializer<R3Long> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("R3Long", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: R3Long) {
            when (value) {
                is Available -> encoder.encodeLong(value.value)
                Unavailable -> encoder.encodeString(R3DiagnosticConstants.UNAVAILABLE)
            }
        }

        override fun deserialize(decoder: Decoder): R3Long {
            val jsonDecoder = decoder as? JsonDecoder
            if (jsonDecoder != null) {
                val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
                if (primitive != null) {
                    if (primitive.isString && primitive.content == R3DiagnosticConstants.UNAVAILABLE) {
                        return Unavailable
                    }
                    primitive.longOrNull?.let { return Available(it) }
                }
            }
            val raw = decoder.decodeString()
            return if (raw == R3DiagnosticConstants.UNAVAILABLE) {
                Unavailable
            } else {
                raw.toLongOrNull()?.let { Available(it) } ?: Unavailable
            }
        }
    }

    companion object {
        fun of(value: Long?): R3Long = if (value != null) Available(value) else Unavailable
    }
}

@Serializable(with = R3Int.Serializer::class)
sealed interface R3Int {
    data class Available(val value: Int) : R3Int
    data object Unavailable : R3Int

    object Serializer : KSerializer<R3Int> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("R3Int", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: R3Int) {
            when (value) {
                is Available -> encoder.encodeInt(value.value)
                Unavailable -> encoder.encodeString(R3DiagnosticConstants.UNAVAILABLE)
            }
        }

        override fun deserialize(decoder: Decoder): R3Int {
            val jsonDecoder = decoder as? JsonDecoder
            if (jsonDecoder != null) {
                val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
                if (primitive != null) {
                    if (primitive.isString && primitive.content == R3DiagnosticConstants.UNAVAILABLE) {
                        return Unavailable
                    }
                    primitive.intOrNull?.let { return Available(it) }
                }
            }
            val raw = decoder.decodeString()
            return if (raw == R3DiagnosticConstants.UNAVAILABLE) {
                Unavailable
            } else {
                raw.toIntOrNull()?.let { Available(it) } ?: Unavailable
            }
        }
    }

    companion object {
        fun of(value: Int?): R3Int = if (value != null) Available(value) else Unavailable
    }
}

@Serializable(with = R3Boolean.Serializer::class)
sealed interface R3Boolean {
    data class Available(val value: Boolean) : R3Boolean
    data object Unavailable : R3Boolean

    object Serializer : KSerializer<R3Boolean> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("R3Boolean", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: R3Boolean) {
            when (value) {
                is Available -> encoder.encodeBoolean(value.value)
                Unavailable -> encoder.encodeString(R3DiagnosticConstants.UNAVAILABLE)
            }
        }

        override fun deserialize(decoder: Decoder): R3Boolean {
            val jsonDecoder = decoder as? JsonDecoder
            if (jsonDecoder != null) {
                val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
                if (primitive != null) {
                    if (primitive.isString && primitive.content == R3DiagnosticConstants.UNAVAILABLE) {
                        return Unavailable
                    }
                    primitive.booleanOrNull?.let { return Available(it) }
                }
            }
            val raw = decoder.decodeString()
            return if (raw == R3DiagnosticConstants.UNAVAILABLE) {
                Unavailable
            } else {
                raw.toBooleanStrictOrNull()?.let { Available(it) } ?: Unavailable
            }
        }
    }

    companion object {
        fun of(value: Boolean?): R3Boolean = if (value != null) Available(value) else Unavailable
    }
}

@Serializable
data class R3PlatformInfo(
    val osFamily: String,
    val osVersion: String,
    val architecture: String,
    val executionEnvironment: String,
    val deviceModel: String
)

@Serializable
data class R3HardwareInfo(
    val cpuModel: String = R3DiagnosticConstants.UNAVAILABLE,
    val logicalCores: R3Int = R3Int.Unavailable,
    val totalRamMiB: R3Double = R3Double.Unavailable
)

@Serializable
data class R3RuntimeInfo(
    val runtimeName: String,
    val runtimeVersion: String,
    val buildConfiguration: String,
    val gcMode: String = R3DiagnosticConstants.UNAVAILABLE,
    val aotOrBaselineProfile: String = R3DiagnosticConstants.UNAVAILABLE
)

@Serializable
data class R3NetworkLinkInfo(
    val transport: String = R3DiagnosticConstants.UNAVAILABLE,
    val interfaceName: String = R3DiagnosticConstants.UNAVAILABLE,
    val linkType: String = R3DiagnosticConstants.UNAVAILABLE,
    val negotiatedLinkMbps: R3Double = R3Double.Unavailable,
    val mtuBytes: R3Int = R3Int.Unavailable,
    val wifiRssiDbm: R3Int = R3Int.Unavailable,
    val wifiFrequencyMhz: R3Int = R3Int.Unavailable,
    val localAddressClass: String = R3DiagnosticConstants.UNAVAILABLE,
    val peerAddressClass: String = R3DiagnosticConstants.UNAVAILABLE,
    val tcpRetransmitsDelta: R3Long = R3Long.Unavailable,
    val outboundPacketErrorsDelta: R3Long = R3Long.Unavailable,
    val receivedPacketErrorsDelta: R3Long = R3Long.Unavailable
)

@Serializable
data class R3StorageInfo(
    val stagingLocation: String = R3DiagnosticConstants.UNAVAILABLE,
    val stagingVolumeOrMount: String = R3DiagnosticConstants.UNAVAILABLE,
    val stagingFilesystem: String = R3DiagnosticConstants.UNAVAILABLE,
    val stagingStorageType: String = R3DiagnosticConstants.UNAVAILABLE,
    val stagingFreeBytesBefore: R3Long = R3Long.Unavailable,
    val destinationLocation: String = R3DiagnosticConstants.UNAVAILABLE,
    val destinationVolumeOrMount: String = R3DiagnosticConstants.UNAVAILABLE,
    val destinationFilesystem: String = R3DiagnosticConstants.UNAVAILABLE,
    val destinationStorageType: String = R3DiagnosticConstants.UNAVAILABLE,
    val destinationFreeBytesBefore: R3Long = R3Long.Unavailable,
    val sameVolumeStagingEligible: R3Boolean = R3Boolean.Unavailable,
    val commitStrategy: String = R3DiagnosticConstants.UNAVAILABLE
)

@Serializable
data class R3DatasetInfo(
    val datasetId: String = R3DiagnosticConstants.UNAVAILABLE,
    val contentProfile: String = R3DiagnosticConstants.UNAVAILABLE,
    val itemCount: Int = 0,
    val declaredTotalBytes: Long = 0L,
    val datasetSha256: String = R3DiagnosticConstants.UNAVAILABLE,
    val manifestSha256: String = R3DiagnosticConstants.UNAVAILABLE
)

@Serializable
data class R3IoOptions(
    val chunkBufferBytes: R3Int = R3Int.Unavailable,
    val queueBufferCount: R3Int = R3Int.Unavailable,
    val maxProcessTransferBufferBytes: R3Int = R3Int.Unavailable,
    val writeCoalesceBufferBytes: R3Int = R3Int.Unavailable,
    val maxWriteBatchDelayMs: R3Int = R3Int.Unavailable,
    val enableCoalescedFraming: R3Boolean = R3Boolean.Unavailable,
    val enableBoundedPipeline: R3Boolean = R3Boolean.Unavailable,
    val discardStorageForBenchmark: R3Boolean = R3Boolean.Unavailable
)

@Serializable
data class R3TestConfiguration(
    val role: String,
    val direction: String,
    val pairingMode: String = R3DiagnosticConstants.UNAVAILABLE,
    val benchmarkPath: String = R3DiagnosticConstants.UNAVAILABLE,
    val benchmarkMode: String = "production-default",
    val runType: String = "interactive",
    val repetitionIndex: Int = 1,
    val repetitionTotal: Int = 1,
    val uiState: String = "visible",
    val powerSource: String = R3DiagnosticConstants.UNAVAILABLE,
    val ioOptions: R3IoOptions = R3IoOptions()
)

@Serializable
data class R3OutcomeInfo(
    val status: String,
    val success: Boolean,
    val rejected: Boolean,
    val cancelled: Boolean,
    val errorCategory: String = "None",
    val terminalMessage: String = "",
    val verifiedItemCount: Int = 0,
    val verifiedBytes: Long = 0L,
    val sha256Verified: R3Boolean = R3Boolean.Unavailable,
    val quarantineCleanAfterTerminal: R3Boolean = R3Boolean.Unavailable
)

@Serializable
data class R3PhaseDurationsMs(
    val totalWallMs: R3Double = R3Double.Unavailable,
    val preparationMs: R3Double = R3Double.Unavailable,
    val discoveryMs: R3Double = R3Double.Unavailable,
    val connectionMs: R3Double = R3Double.Unavailable,
    val authenticationMs: R3Double = R3Double.Unavailable,
    val userConsentMs: R3Double = R3Double.Unavailable,
    val payloadTransferMs: R3Double = R3Double.Unavailable,
    val verificationMs: R3Double = R3Double.Unavailable,
    val savingMs: R3Double = R3Double.Unavailable,
    val finalAcknowledgementMs: R3Double = R3Double.Unavailable,
    val acceptanceToVerifiedMs: R3Double = R3Double.Unavailable
)

@Serializable
data class R3ByteCounts(
    val declaredTotalBytes: Long = 0L,
    val payloadBytesTransferred: Long = 0L,
    val stagingBytesWritten: R3Long = R3Long.Unavailable,
    val verifiedBytes: Long = 0L
)

@Serializable
data class R3FiveSecondRateWindow(
    val windowIndex: Int,
    val startElapsedSec: Double,
    val endElapsedSec: Double,
    val bytes: Long,
    val rateMBps: Double
)

@Serializable
data class R3ThroughputInfo(
    val averagePayloadRateMBps: R3Double = R3Double.Unavailable,
    val acceptanceToVerifiedRateMBps: R3Double = R3Double.Unavailable,
    val oneSecondRatesMBps: List<Double> = emptyList(),
    val fiveSecondRateWindowsMBps: List<R3FiveSecondRateWindow> = emptyList(),
    val firstSteadyQuarterRateMBps: R3Double = R3Double.Unavailable,
    val finalSteadyQuarterRateMBps: R3Double = R3Double.Unavailable,
    val finalToFirstQuarterRatio: R3Double = R3Double.Unavailable
)

@Serializable
data class R3PipelineTimingsMs(
    val sourceReadMs: R3Double = R3Double.Unavailable,
    val hashComputeMs: R3Double = R3Double.Unavailable,
    val tlsWriteMs: R3Double = R3Double.Unavailable,
    val tlsReadMs: R3Double = R3Double.Unavailable,
    val stagingWriteMs: R3Double = R3Double.Unavailable,
    val verificationReadMs: R3Double = R3Double.Unavailable,
    val commitMs: R3Double = R3Double.Unavailable
)

@Serializable
data class R3ResourceUsage(
    val cpuPercentAvg: R3Double = R3Double.Unavailable,
    val cpuPercentPeak: R3Double = R3Double.Unavailable,
    val managedHeapBaselineMiB: R3Double = R3Double.Unavailable,
    val managedHeapPeakMiB: R3Double = R3Double.Unavailable,
    val managedHeapDeltaMiB: R3Double = R3Double.Unavailable,
    val managedAllocatedTotalMB: R3Double = R3Double.Unavailable,
    val managedAllocatedRateAvgMBPerSec: R3Double = R3Double.Unavailable,
    val nativeOrPrivateMemoryBaselineMiB: R3Double = R3Double.Unavailable,
    val nativeOrPrivateMemoryPeakMiB: R3Double = R3Double.Unavailable,
    val workingSetPeakMiB: R3Double = R3Double.Unavailable,
    val gcPauseTotalMs: R3Double = R3Double.Unavailable,
    val gcPausePercentPeak: R3Double = R3Double.Unavailable,
    val gen0Collections: R3Int = R3Int.Unavailable,
    val gen1Collections: R3Int = R3Int.Unavailable,
    val gen2Collections: R3Int = R3Int.Unavailable,
    val threadPoolPendingWorkItemsPeak: R3Int = R3Int.Unavailable
)

@Serializable
data class R3QueueOccupancy(
    val senderQueueDepthMax: R3Int = R3Int.Unavailable,
    val receiverQueueDepthMax: R3Int = R3Int.Unavailable,
    val activePooledBuffersPeak: R3Int = R3Int.Unavailable,
    val activePooledBytesPeak: R3Long = R3Long.Unavailable
)

@Serializable
data class R3DiskActivity(
    val processReadIoBytes: R3Long = R3Long.Unavailable,
    val processWriteIoBytes: R3Long = R3Long.Unavailable,
    val processReadMBpsAvg: R3Double = R3Double.Unavailable,
    val processWriteMBpsAvg: R3Double = R3Double.Unavailable,
    val volumeReadLatencyMsAvg: R3Double = R3Double.Unavailable,
    val volumeWriteLatencyMsAvg: R3Double = R3Double.Unavailable
)

@Serializable
data class R3CancellationMetrics(
    val cancellationRequested: Boolean = false,
    val uiCancelLatencyMs: R3Double = R3Double.Unavailable,
    val resourceReleaseLatencyMs: R3Double = R3Double.Unavailable
)

@Serializable
data class R3AndroidConditions(
    val thermalStatusStart: String = R3DiagnosticConstants.UNAVAILABLE,
    val thermalStatusPeak: String = R3DiagnosticConstants.UNAVAILABLE,
    val thermalStatusEnd: String = R3DiagnosticConstants.UNAVAILABLE,
    val thermalHeadroom: R3Double = R3Double.Unavailable,
    val batteryLevelPercentStart: R3Int = R3Int.Unavailable,
    val batteryLevelPercentEnd: R3Int = R3Int.Unavailable,
    val isCharging: R3Boolean = R3Boolean.Unavailable,
    val batterySaverEnabled: R3Boolean = R3Boolean.Unavailable,
    val batteryTemperatureCelsiusStart: R3Double = R3Double.Unavailable,
    val batteryTemperatureCelsiusEnd: R3Double = R3Double.Unavailable
)

@Serializable
data class R3LinuxConditions(
    val kernelRelease: String = R3DiagnosticConstants.UNAVAILABLE,
    val distro: String = R3DiagnosticConstants.UNAVAILABLE,
    val virtualization: String = R3DiagnosticConstants.UNAVAILABLE,
    val cgroupMemoryLimitBytes: R3Long = R3Long.Unavailable,
    val networkBridgeOrNatMode: String = R3DiagnosticConstants.UNAVAILABLE,
    val stagingMountOptions: String = R3DiagnosticConstants.UNAVAILABLE,
    val destinationMountOptions: String = R3DiagnosticConstants.UNAVAILABLE
)

@Serializable
data class R3TransferDiagnosticReport(
    val schemaVersion: String = R3DiagnosticConstants.SCHEMA_VERSION,
    val attemptId: String,
    val timestampUtc: String,
    val sourceCommit: String = R3DiagnosticConstants.UNAVAILABLE,
    val artifactSha256: String = R3DiagnosticConstants.UNAVAILABLE,
    val appVersion: String,
    val platform: R3PlatformInfo,
    val hardware: R3HardwareInfo = R3HardwareInfo(),
    val runtime: R3RuntimeInfo,
    val networkLink: R3NetworkLinkInfo = R3NetworkLinkInfo(),
    val storage: R3StorageInfo = R3StorageInfo(),
    val dataset: R3DatasetInfo = R3DatasetInfo(),
    val testConfiguration: R3TestConfiguration,
    val outcome: R3OutcomeInfo,
    val phaseDurationsMs: R3PhaseDurationsMs = R3PhaseDurationsMs(),
    val bytes: R3ByteCounts = R3ByteCounts(),
    val throughput: R3ThroughputInfo = R3ThroughputInfo(),
    val pipelineTimingsMs: R3PipelineTimingsMs = R3PipelineTimingsMs(),
    val resources: R3ResourceUsage = R3ResourceUsage(),
    val queueOccupancy: R3QueueOccupancy = R3QueueOccupancy(),
    val diskActivity: R3DiskActivity = R3DiskActivity(),
    val cancellation: R3CancellationMetrics = R3CancellationMetrics(),
    val androidConditions: R3AndroidConditions = R3AndroidConditions(),
    val linuxConditions: R3LinuxConditions = R3LinuxConditions()
) {
    fun sanitized(): R3TransferDiagnosticReport = R3DiagnosticSanitizer.sanitizeReport(this)

    fun toJson(pretty: Boolean = true): String =
        if (pretty) R3DiagnosticJson.pretty.encodeToString(serializer(), sanitized())
        else R3DiagnosticJson.compact.encodeToString(serializer(), sanitized())
}

@Serializable
data class R3DiagnosticSample(
    val schemaVersion: String = R3DiagnosticConstants.SAMPLE_SCHEMA_VERSION,
    val timestampUtc: String,
    val attemptId: String,
    val buildCommit: String = R3DiagnosticConstants.UNAVAILABLE,
    val direction: String,
    val phase: String,
    val elapsedSeconds: Double,
    val preparationMs: R3Double = R3Double.Unavailable,
    val discoveryMs: R3Double = R3Double.Unavailable,
    val connectionMs: R3Double = R3Double.Unavailable,
    val authenticationMs: R3Double = R3Double.Unavailable,
    val consentWaitMs: R3Double = R3Double.Unavailable,
    val payloadTransferMs: R3Double = R3Double.Unavailable,
    val verificationMs: R3Double = R3Double.Unavailable,
    val savingMs: R3Double = R3Double.Unavailable,
    val commitMs: R3Double = R3Double.Unavailable,
    val finalAcknowledgementMs: R3Double = R3Double.Unavailable,
    val acceptanceToVerifiedMs: R3Double = R3Double.Unavailable,
    val declaredTotalBytes: Long = 0L,
    val payloadBytesTransferred: Long = 0L,
    val stagingBytesWritten: R3Long = R3Long.Unavailable,
    val verifiedBytes: Long = 0L,
    val unsmoothedPayloadRateMBps: R3Double = R3Double.Unavailable,
    val averagePayloadRateMBps: R3Double = R3Double.Unavailable,
    val fiveSecondWindowRateMBps: R3Double = R3Double.Unavailable,
    val acceptanceToVerifiedRateMBps: R3Double = R3Double.Unavailable,
    val senderQueueDepth: R3Int = R3Int.Unavailable,
    val receiverQueueDepth: R3Int = R3Int.Unavailable,
    val cpuPercent: R3Double = R3Double.Unavailable,
    val managedAllocatedMBPerSec: R3Double = R3Double.Unavailable,
    val managedHeapMiB: R3Double = R3Double.Unavailable,
    val nativeMemoryMiB: R3Double = R3Double.Unavailable,
    val gcPauseMs: R3Double = R3Double.Unavailable,
    val gcCount: R3Int = R3Int.Unavailable,
    val processReadBytes: R3Long = R3Long.Unavailable,
    val processWriteBytes: R3Long = R3Long.Unavailable,
    val thermalStatus: String = R3DiagnosticConstants.UNAVAILABLE,
    val batteryLevelPercent: R3Int = R3Int.Unavailable,
    val isTerminal: Boolean = false,
    val terminalStatus: String = ""
) {
    fun toJsonLine(): String = R3DiagnosticJson.compact.encodeToString(
        serializer(),
        R3DiagnosticSanitizer.sanitizeSample(this)
    )
}

object R3DiagnosticJson {
    val pretty: Json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    val compact: Json = Json {
        encodeDefaults = true
        prettyPrint = false
        ignoreUnknownKeys = true
    }
}

object R3DiagnosticSanitizer {
    private val glideUriRegex = Regex("""glide://pair/\S+""", RegexOption.IGNORE_CASE)
    private val secretKvRegex = Regex(
        """\b(token|secret|pin|password|passcode|spki|privatekey|private_key)\b(\s*[:=]\s*)([^\s,;'"&]+)""",
        RegexOption.IGNORE_CASE
    )
    private val uriQueryRegex = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://[^\s?#]+)\?([^\s#]+)""")
    private val unixHomeRegex = Regex("""/(?:home|Users)/[^/\s\\]+""")
    private val windowsHomeRegex = Regex("""[A-Za-z]:\\Users\\[^\\\s/]+""", RegexOption.IGNORE_CASE)

    fun sanitize(text: String?, maxLength: Int = R3DiagnosticConstants.MAX_TERMINAL_MESSAGE_LENGTH): String {
        if (text.isNullOrBlank()) return ""
        var cleaned = glideUriRegex.replace(text, "glide://pair/[REDACTED]")
        cleaned = secretKvRegex.replace(cleaned) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        cleaned = uriQueryRegex.replace(cleaned) { match ->
            "${match.groupValues[1]}?[REDACTED]"
        }
        cleaned = unixHomeRegex.replace(cleaned, "~")
        cleaned = windowsHomeRegex.replace(cleaned, "~")
        return if (cleaned.length > maxLength) {
            cleaned.take(maxLength)
        } else {
            cleaned
        }
    }

    fun sanitizePath(path: String?): String {
        if (path.isNullOrBlank()) return R3DiagnosticConstants.UNAVAILABLE
        val sanitized = sanitize(path, maxLength = 512)
        return if (sanitized.isBlank()) R3DiagnosticConstants.UNAVAILABLE else sanitized
    }

    fun sanitizeReport(report: R3TransferDiagnosticReport): R3TransferDiagnosticReport =
        report.copy(
            sourceCommit = sanitize(report.sourceCommit, 128).ifBlank { R3DiagnosticConstants.UNAVAILABLE },
            artifactSha256 = sanitize(report.artifactSha256, 128).ifBlank { R3DiagnosticConstants.UNAVAILABLE },
            storage = report.storage.copy(
                stagingLocation = sanitizePath(report.storage.stagingLocation),
                destinationLocation = sanitizePath(report.storage.destinationLocation)
            ),
            dataset = report.dataset.copy(
                datasetId = sanitize(report.dataset.datasetId, 128).ifBlank { R3DiagnosticConstants.UNAVAILABLE }
            ),
            outcome = report.outcome.copy(
                terminalMessage = sanitize(
                    report.outcome.terminalMessage,
                    R3DiagnosticConstants.MAX_TERMINAL_MESSAGE_LENGTH
                )
            ),
            throughput = report.throughput.copy(
                oneSecondRatesMBps = report.throughput.oneSecondRatesMBps
                    .take(R3DiagnosticConstants.MAX_ONE_SECOND_SAMPLES),
                fiveSecondRateWindowsMBps = report.throughput.fiveSecondRateWindowsMBps
                    .take(R3DiagnosticConstants.MAX_FIVE_SECOND_WINDOWS)
            )
        )

    fun sanitizeSample(sample: R3DiagnosticSample): R3DiagnosticSample =
        sample.copy(
            buildCommit = sanitize(sample.buildCommit, 128).ifBlank { R3DiagnosticConstants.UNAVAILABLE },
            terminalStatus = sanitize(
                sample.terminalStatus,
                R3DiagnosticConstants.MAX_TERMINAL_MESSAGE_LENGTH
            )
        )
}

object R3ThroughputCalculator {

    fun round2(value: Double): Double =
        if (value.isFinite()) (value * 100.0).roundToLong() / 100.0 else 0.0

    fun round3(value: Double): Double =
        if (value.isFinite()) (value * 1000.0).roundToLong() / 1000.0 else 0.0

    /**
     * Builds bounded 5-second rate windows from 1-second incremental byte counts.
     */
    fun computeFiveSecondWindows(
        oneSecondByteDeltas: List<Long>,
        maxWindows: Int = R3DiagnosticConstants.MAX_FIVE_SECOND_WINDOWS
    ): List<R3FiveSecondRateWindow> {
        if (oneSecondByteDeltas.size < 5) return emptyList()
        val windows = mutableListOf<R3FiveSecondRateWindow>()
        var windowIdx = 0
        var i = 0
        while (i + 5 <= oneSecondByteDeltas.size && windows.size < maxWindows) {
            var sumBytes = 0L
            for (j in i until i + 5) {
                sumBytes += oneSecondByteDeltas[j].coerceAtLeast(0L)
            }
            val rateMBps = round2(sumBytes / (5.0 * R3DiagnosticConstants.DECIMAL_BYTES_PER_MB))
            windows.add(
                R3FiveSecondRateWindow(
                    windowIndex = windowIdx,
                    startElapsedSec = (i).toDouble(),
                    endElapsedSec = (i + 5).toDouble(),
                    bytes = sumBytes,
                    rateMBps = rateMBps
                )
            )
            windowIdx++
            i += 5
        }
        return windows
    }

    /**
     * Computes (firstSteadyQuarterRateMBps, finalSteadyQuarterRateMBps, finalToFirstQuarterRatio).
     */
    fun computeSteadyQuarters(
        oneSecondRatesMBps: List<Double>
    ): Triple<R3Double, R3Double, R3Double> {
        if (oneSecondRatesMBps.size < 4) {
            return Triple(R3Double.Unavailable, R3Double.Unavailable, R3Double.Unavailable)
        }
        val quarterLen = oneSecondRatesMBps.size / 4
        if (quarterLen <= 0) {
            return Triple(R3Double.Unavailable, R3Double.Unavailable, R3Double.Unavailable)
        }
        val firstAvg = oneSecondRatesMBps.subList(0, quarterLen).average()
        val finalAvg = oneSecondRatesMBps.subList(oneSecondRatesMBps.size - quarterLen, oneSecondRatesMBps.size).average()
        if (!firstAvg.isFinite() || !finalAvg.isFinite() || firstAvg <= 0.0) {
            return Triple(R3Double.Unavailable, R3Double.Unavailable, R3Double.Unavailable)
        }
        val ratio = round3(finalAvg / firstAvg)
        return Triple(
            R3Double.Available(round2(firstAvg)),
            R3Double.Available(round2(finalAvg)),
            R3Double.Available(ratio)
        )
    }
}
