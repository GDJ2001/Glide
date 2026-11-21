using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Channels;
using Glide.Core.Storage;

namespace Glide.Desktop.Diagnostics;

/// <summary>
/// Lightweight value wrapper that emits the JSON string "unavailable" when a counter
/// or metric cannot be observed on the current host/runtime.
/// </summary>
[JsonConverter(typeof(MetricValueJsonConverterFactory))]
public readonly record struct MetricValue<T>
{
    private readonly T _value;

    private MetricValue(T value, bool isAvailable)
    {
        _value = value;
        IsAvailable = isAvailable;
    }

    public bool IsAvailable { get; }

    public T Value => IsAvailable
        ? _value
        : throw new InvalidOperationException("Metric value is unavailable.");

    public static MetricValue<T> Unavailable => default;

    public static MetricValue<T> Available(T value)
    {
        if (value is null)
        {
            return Unavailable;
        }

        if (value is double d && (double.IsNaN(d) || double.IsInfinity(d)))
        {
            return Unavailable;
        }

        if (value is float f && (float.IsNaN(f) || float.IsInfinity(f)))
        {
            return Unavailable;
        }

        if (value is string s &&
            (string.IsNullOrWhiteSpace(s) || string.Equals(s.Trim(), "unavailable", StringComparison.OrdinalIgnoreCase)))
        {
            return Unavailable;
        }

        return new MetricValue<T>(value, true);
    }

    public T ValueOr(T fallback) => IsAvailable ? _value : fallback;

    public bool TryGetValue(out T value)
    {
        value = _value;
        return IsAvailable;
    }

    public static implicit operator MetricValue<T>(T value) => Available(value);

    public override string ToString() => IsAvailable ? _value?.ToString() ?? "unavailable" : "unavailable";
}

public sealed class MetricValueJsonConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type typeToConvert) =>
        typeToConvert.IsGenericType && typeToConvert.GetGenericTypeDefinition() == typeof(MetricValue<>);

    public override JsonConverter CreateConverter(Type typeToConvert, JsonSerializerOptions options)
    {
        Type innerType = typeToConvert.GetGenericArguments()[0];
        Type converterType = typeof(MetricValueJsonConverter<>).MakeGenericType(innerType);
        return (JsonConverter)Activator.CreateInstance(converterType)!;
    }

    private sealed class MetricValueJsonConverter<T> : JsonConverter<MetricValue<T>>
    {
        public override MetricValue<T> Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            if (reader.TokenType == JsonTokenType.Null)
            {
                return MetricValue<T>.Unavailable;
            }

            if (reader.TokenType == JsonTokenType.String)
            {
                string? text = reader.GetString();
                if (string.IsNullOrWhiteSpace(text) ||
                    string.Equals(text, "unavailable", StringComparison.OrdinalIgnoreCase))
                {
                    return MetricValue<T>.Unavailable;
                }

                if (typeof(T) == typeof(string))
                {
                    return MetricValue<T>.Available((T)(object)text);
                }
            }

            T? deserialized = JsonSerializer.Deserialize<T>(ref reader, options);
            return deserialized is null ? MetricValue<T>.Unavailable : MetricValue<T>.Available(deserialized);
        }

        public override void Write(Utf8JsonWriter writer, MetricValue<T> value, JsonSerializerOptions options)
        {
            if (!value.IsAvailable)
            {
                writer.WriteStringValue("unavailable");
                return;
            }

            JsonSerializer.Serialize(writer, value.Value, options);
        }
    }
}

/// <summary>
/// One-second or terminal diagnostic sample emitted by <see cref="IDesktopTransferAttemptDiagnostics"/>.
/// Strictly excludes file contents, pairing secrets, tokens, PINs, and invitation links.
/// Rates use decimal megabytes per second (1 MB = 1,000,000 bytes).
/// </summary>
public sealed record DesktopTransferDiagnosticSample(
    [property: JsonPropertyName("timestampUtc")] string TimestampUtc,
    [property: JsonPropertyName("attemptId")] string AttemptId,
    [property: JsonPropertyName("buildCommit")] string BuildCommit,
    [property: JsonPropertyName("direction")] string Direction,
    [property: JsonPropertyName("phase")] string Phase,
    [property: JsonPropertyName("elapsedSeconds")] double ElapsedSeconds,
    [property: JsonPropertyName("preparationMs")] double PreparationMs,
    [property: JsonPropertyName("consentWaitMs")] double ConsentWaitMs,
    [property: JsonPropertyName("payloadTransferMs")] double PayloadTransferMs,
    [property: JsonPropertyName("verificationMs")] double VerificationMs,
    [property: JsonPropertyName("commitMs")] double CommitMs,
    [property: JsonPropertyName("acceptanceToVerifiedMs")] double AcceptanceToVerifiedMs,
    [property: JsonPropertyName("declaredTotalBytes")] long DeclaredTotalBytes,
    [property: JsonPropertyName("payloadBytesTransferred")] long PayloadBytesTransferred,
    [property: JsonPropertyName("stagingBytesWritten")] long StagingBytesWritten,
    [property: JsonPropertyName("verifiedBytes")] long VerifiedBytes,
    [property: JsonPropertyName("unsmoothedPayloadRateMBps")] double UnsmoothedPayloadRateMBps,
    [property: JsonPropertyName("averagePayloadRateMBps")] double AveragePayloadRateMBps,
    [property: JsonPropertyName("acceptanceToVerifiedRateMBps")] double AcceptanceToVerifiedRateMBps,
    [property: JsonPropertyName("sourceReadMs")] double SourceReadMs,
    [property: JsonPropertyName("hashComputeMs")] double HashComputeMs,
    [property: JsonPropertyName("tlsWriteMs")] double TlsWriteMs,
    [property: JsonPropertyName("tlsReadMs")] double TlsReadMs,
    [property: JsonPropertyName("stagingWriteMs")] double StagingWriteMs,
    [property: JsonPropertyName("verificationReadMs")] double VerificationReadMs,
    [property: JsonPropertyName("senderQueueDepth")] int SenderQueueDepth,
    [property: JsonPropertyName("senderQueueMaxDepth")] int SenderQueueMaxDepth,
    [property: JsonPropertyName("receiverQueueDepth")] int ReceiverQueueDepth,
    [property: JsonPropertyName("receiverQueueMaxDepth")] int ReceiverQueueMaxDepth,
    [property: JsonPropertyName("activePooledBuffers")] int ActivePooledBuffers,
    [property: JsonPropertyName("activePooledBytes")] long ActivePooledBytes,
    [property: JsonPropertyName("cpuPercent")] MetricValue<double> CpuPercent,
    [property: JsonPropertyName("managedAllocatedMBPerSec")] double ManagedAllocatedMBPerSec,
    [property: JsonPropertyName("managedHeapMiB")] double ManagedHeapMiB,
    [property: JsonPropertyName("workingSetMiB")] double WorkingSetMiB,
    [property: JsonPropertyName("gcPauseMs")] double GcPauseMs,
    [property: JsonPropertyName("gcPausePercent")] double GcPausePercent,
    [property: JsonPropertyName("gen0Collections")] int Gen0Collections,
    [property: JsonPropertyName("gen1Collections")] int Gen1Collections,
    [property: JsonPropertyName("gen2Collections")] int Gen2Collections,
    [property: JsonPropertyName("threadPoolPendingWorkItems")] long ThreadPoolPendingWorkItems,
    [property: JsonPropertyName("isTerminal")] bool IsTerminal,
    [property: JsonPropertyName("terminalStatus")] string? TerminalStatus = null,
    [property: JsonPropertyName("schemaVersion")] string SchemaVersion = "glide-r3-sample-v1",
    [property: JsonPropertyName("discoveryMs")] MetricValue<double> DiscoveryMs = default,
    [property: JsonPropertyName("connectionMs")] MetricValue<double> ConnectionMs = default,
    [property: JsonPropertyName("authenticationMs")] MetricValue<double> AuthenticationMs = default,
    [property: JsonPropertyName("finalAcknowledgementMs")] MetricValue<double> FinalAcknowledgementMs = default,
    [property: JsonPropertyName("savingMs")] double SavingMs = 0.0,
    [property: JsonPropertyName("fiveSecondWindowRateMBps")] MetricValue<double> FiveSecondWindowRateMBps = default,
    [property: JsonPropertyName("nativeMemoryMiB")] MetricValue<double> NativeMemoryMiB = default,
    [property: JsonPropertyName("processReadBytes")] MetricValue<long> ProcessReadBytes = default,
    [property: JsonPropertyName("processWriteBytes")] MetricValue<long> ProcessWriteBytes = default,
    [property: JsonPropertyName("thermalStatus")] MetricValue<string> ThermalStatus = default,
    [property: JsonPropertyName("batteryLevelPercent")] MetricValue<int> BatteryLevelPercent = default);

public sealed record DesktopAttemptMetadata(
    string? PairingMode = null,
    string? StagingLocation = null,
    string? DestinationLocation = null,
    bool? SameVolumeStagingEligible = null,
    string? CommitStrategy = null,
    string? LocalAddress = null,
    string? PeerAddress = null,
    int? ItemCount = null,
    string? DatasetId = null,
    string? ContentProfile = null,
    string? DatasetSha256 = null,
    string? ManifestSha256 = null,
    DesktopTransferIoOptions? IoOptions = null,
    string? TransportName = null);

public sealed record DesktopR3ReportOptions(
    string? BenchmarkPath = null,
    string? BenchmarkMode = null,
    string? RunType = null,
    int RepetitionIndex = 1,
    int RepetitionTotal = 1,
    string? UiState = null,
    string? ExecutionEnvironmentOverride = null,
    string? DatasetIdOverride = null,
    string? ContentProfileOverride = null,
    string? DatasetSha256Override = null,
    string? ManifestSha256Override = null,
    bool? Sha256VerifiedOverride = null,
    bool? QuarantineCleanAfterTerminalOverride = null,
    double? UiCancelLatencyMsOverride = null,
    double? ResourceReleaseLatencyMsOverride = null);

public sealed record R3DiagnosticReport(
    [property: JsonPropertyName("schemaVersion")] string SchemaVersion,
    [property: JsonPropertyName("attemptId")] string AttemptId,
    [property: JsonPropertyName("timestampUtc")] string TimestampUtc,
    [property: JsonPropertyName("sourceCommit")] string SourceCommit,
    [property: JsonPropertyName("artifactSha256")] MetricValue<string> ArtifactSha256,
    [property: JsonPropertyName("appVersion")] string AppVersion,
    [property: JsonPropertyName("platform")] R3PlatformInfo Platform,
    [property: JsonPropertyName("hardware")] R3HardwareInfo Hardware,
    [property: JsonPropertyName("runtime")] R3RuntimeInfo Runtime,
    [property: JsonPropertyName("networkLink")] R3NetworkLinkInfo NetworkLink,
    [property: JsonPropertyName("storage")] R3StorageInfo Storage,
    [property: JsonPropertyName("dataset")] R3DatasetInfo Dataset,
    [property: JsonPropertyName("testConfiguration")] R3TestConfigurationInfo TestConfiguration,
    [property: JsonPropertyName("outcome")] R3OutcomeInfo Outcome,
    [property: JsonPropertyName("phaseDurationsMs")] R3PhaseDurationsInfo PhaseDurationsMs,
    [property: JsonPropertyName("bytes")] R3BytesInfo Bytes,
    [property: JsonPropertyName("throughput")] R3ThroughputInfo Throughput,
    [property: JsonPropertyName("pipelineTimingsMs")] R3PipelineTimingsInfo PipelineTimingsMs,
    [property: JsonPropertyName("resources")] R3ResourcesInfo Resources,
    [property: JsonPropertyName("queueOccupancy")] R3QueueOccupancyInfo QueueOccupancy,
    [property: JsonPropertyName("diskActivity")] R3DiskActivityInfo DiskActivity,
    [property: JsonPropertyName("cancellation")] R3CancellationInfo Cancellation,
    [property: JsonPropertyName("androidConditions")] R3AndroidConditionsInfo AndroidConditions,
    [property: JsonPropertyName("linuxConditions")] R3LinuxConditionsInfo LinuxConditions)
{
    public const string CurrentSchemaVersion = "glide-r3-diagnostic-v1";

    private static readonly JsonSerializerOptions s_indentedJsonOptions = new() { WriteIndented = true };

    public string ToJson(bool indented = true)
    {
        string raw = indented
            ? JsonSerializer.Serialize(this, s_indentedJsonOptions)
            : JsonSerializer.Serialize(this);
        return BoundedTransferDiagnosticSink.SanitizeDiagnosticText(raw);
    }
}

public sealed record R3PlatformInfo(
    [property: JsonPropertyName("osFamily")] string OsFamily,
    [property: JsonPropertyName("osVersion")] string OsVersion,
    [property: JsonPropertyName("architecture")] string Architecture,
    [property: JsonPropertyName("executionEnvironment")] string ExecutionEnvironment,
    [property: JsonPropertyName("deviceModel")] string DeviceModel);

public sealed record R3HardwareInfo(
    [property: JsonPropertyName("cpuModel")] MetricValue<string> CpuModel,
    [property: JsonPropertyName("logicalCores")] int LogicalCores,
    [property: JsonPropertyName("totalRamMiB")] MetricValue<double> TotalRamMiB);

public sealed record R3RuntimeInfo(
    [property: JsonPropertyName("runtimeName")] string RuntimeName,
    [property: JsonPropertyName("runtimeVersion")] string RuntimeVersion,
    [property: JsonPropertyName("buildConfiguration")] string BuildConfiguration,
    [property: JsonPropertyName("gcMode")] MetricValue<string> GcMode,
    [property: JsonPropertyName("aotOrBaselineProfile")] MetricValue<string> AotOrBaselineProfile);

public sealed record R3NetworkLinkInfo(
    [property: JsonPropertyName("transport")] string Transport,
    [property: JsonPropertyName("interfaceName")] MetricValue<string> InterfaceName,
    [property: JsonPropertyName("linkType")] MetricValue<string> LinkType,
    [property: JsonPropertyName("negotiatedLinkMbps")] MetricValue<double> NegotiatedLinkMbps,
    [property: JsonPropertyName("mtuBytes")] MetricValue<int> MtuBytes,
    [property: JsonPropertyName("wifiRssiDbm")] MetricValue<int> WifiRssiDbm,
    [property: JsonPropertyName("wifiFrequencyMhz")] MetricValue<int> WifiFrequencyMhz,
    [property: JsonPropertyName("localAddressClass")] MetricValue<string> LocalAddressClass,
    [property: JsonPropertyName("peerAddressClass")] MetricValue<string> PeerAddressClass,
    [property: JsonPropertyName("tcpRetransmitsDelta")] MetricValue<long> TcpRetransmitsDelta,
    [property: JsonPropertyName("outboundPacketErrorsDelta")] MetricValue<long> OutboundPacketErrorsDelta,
    [property: JsonPropertyName("receivedPacketErrorsDelta")] MetricValue<long> ReceivedPacketErrorsDelta);

public sealed record R3StorageInfo(
    [property: JsonPropertyName("stagingLocation")] MetricValue<string> StagingLocation,
    [property: JsonPropertyName("stagingVolumeOrMount")] MetricValue<string> StagingVolumeOrMount,
    [property: JsonPropertyName("stagingFilesystem")] MetricValue<string> StagingFilesystem,
    [property: JsonPropertyName("stagingStorageType")] MetricValue<string> StagingStorageType,
    [property: JsonPropertyName("stagingFreeBytesBefore")] MetricValue<long> StagingFreeBytesBefore,
    [property: JsonPropertyName("destinationLocation")] MetricValue<string> DestinationLocation,
    [property: JsonPropertyName("destinationVolumeOrMount")] MetricValue<string> DestinationVolumeOrMount,
    [property: JsonPropertyName("destinationFilesystem")] MetricValue<string> DestinationFilesystem,
    [property: JsonPropertyName("destinationStorageType")] MetricValue<string> DestinationStorageType,
    [property: JsonPropertyName("destinationFreeBytesBefore")] MetricValue<long> DestinationFreeBytesBefore,
    [property: JsonPropertyName("sameVolumeStagingEligible")] MetricValue<bool> SameVolumeStagingEligible,
    [property: JsonPropertyName("commitStrategy")] MetricValue<string> CommitStrategy);

public sealed record R3DatasetInfo(
    [property: JsonPropertyName("datasetId")] string DatasetId,
    [property: JsonPropertyName("contentProfile")] string ContentProfile,
    [property: JsonPropertyName("itemCount")] int ItemCount,
    [property: JsonPropertyName("declaredTotalBytes")] long DeclaredTotalBytes,
    [property: JsonPropertyName("datasetSha256")] MetricValue<string> DatasetSha256,
    [property: JsonPropertyName("manifestSha256")] MetricValue<string> ManifestSha256);

public sealed record R3IoOptionsInfo(
    [property: JsonPropertyName("chunkBufferBytes")] int ChunkBufferBytes,
    [property: JsonPropertyName("queueBufferCount")] int QueueBufferCount,
    [property: JsonPropertyName("maxProcessTransferBufferBytes")] long MaxProcessTransferBufferBytes,
    [property: JsonPropertyName("writeCoalesceBufferBytes")] int WriteCoalesceBufferBytes,
    [property: JsonPropertyName("maxWriteBatchDelayMs")] int MaxWriteBatchDelayMs,
    [property: JsonPropertyName("enableCoalescedFraming")] bool EnableCoalescedFraming,
    [property: JsonPropertyName("enableBoundedPipeline")] bool EnableBoundedPipeline,
    [property: JsonPropertyName("discardStorageForBenchmark")] bool DiscardStorageForBenchmark);

public sealed record R3TestConfigurationInfo(
    [property: JsonPropertyName("role")] string Role,
    [property: JsonPropertyName("direction")] string Direction,
    [property: JsonPropertyName("pairingMode")] string PairingMode,
    [property: JsonPropertyName("benchmarkPath")] MetricValue<string> BenchmarkPath,
    [property: JsonPropertyName("benchmarkMode")] string BenchmarkMode,
    [property: JsonPropertyName("runType")] string RunType,
    [property: JsonPropertyName("repetitionIndex")] int RepetitionIndex,
    [property: JsonPropertyName("repetitionTotal")] int RepetitionTotal,
    [property: JsonPropertyName("uiState")] string UiState,
    [property: JsonPropertyName("powerSource")] MetricValue<string> PowerSource,
    [property: JsonPropertyName("ioOptions")] R3IoOptionsInfo IoOptions);

public sealed record R3OutcomeInfo(
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("success")] bool Success,
    [property: JsonPropertyName("rejected")] bool Rejected,
    [property: JsonPropertyName("cancelled")] bool Cancelled,
    [property: JsonPropertyName("errorCategory")] string ErrorCategory,
    [property: JsonPropertyName("terminalMessage")] string TerminalMessage,
    [property: JsonPropertyName("verifiedItemCount")] int VerifiedItemCount,
    [property: JsonPropertyName("verifiedBytes")] long VerifiedBytes,
    [property: JsonPropertyName("sha256Verified")] bool Sha256Verified,
    [property: JsonPropertyName("quarantineCleanAfterTerminal")] MetricValue<bool> QuarantineCleanAfterTerminal);

public sealed record R3PhaseDurationsInfo(
    [property: JsonPropertyName("totalWallMs")] double TotalWallMs,
    [property: JsonPropertyName("preparationMs")] double PreparationMs,
    [property: JsonPropertyName("discoveryMs")] MetricValue<double> DiscoveryMs,
    [property: JsonPropertyName("connectionMs")] MetricValue<double> ConnectionMs,
    [property: JsonPropertyName("authenticationMs")] MetricValue<double> AuthenticationMs,
    [property: JsonPropertyName("userConsentMs")] double UserConsentMs,
    [property: JsonPropertyName("payloadTransferMs")] double PayloadTransferMs,
    [property: JsonPropertyName("verificationMs")] double VerificationMs,
    [property: JsonPropertyName("savingMs")] double SavingMs,
    [property: JsonPropertyName("finalAcknowledgementMs")] MetricValue<double> FinalAcknowledgementMs,
    [property: JsonPropertyName("acceptanceToVerifiedMs")] double AcceptanceToVerifiedMs);

public sealed record R3BytesInfo(
    [property: JsonPropertyName("declaredTotalBytes")] long DeclaredTotalBytes,
    [property: JsonPropertyName("payloadBytesTransferred")] long PayloadBytesTransferred,
    [property: JsonPropertyName("stagingBytesWritten")] long StagingBytesWritten,
    [property: JsonPropertyName("verifiedBytes")] long VerifiedBytes);

public sealed record R3FiveSecondRateWindow(
    [property: JsonPropertyName("windowIndex")] int WindowIndex,
    [property: JsonPropertyName("startElapsedSec")] double StartElapsedSec,
    [property: JsonPropertyName("endElapsedSec")] double EndElapsedSec,
    [property: JsonPropertyName("bytes")] long Bytes,
    [property: JsonPropertyName("rateMBps")] double RateMBps);

public sealed record R3ThroughputInfo(
    [property: JsonPropertyName("averagePayloadRateMBps")] double AveragePayloadRateMBps,
    [property: JsonPropertyName("acceptanceToVerifiedRateMBps")] double AcceptanceToVerifiedRateMBps,
    [property: JsonPropertyName("oneSecondRatesMBps")] IReadOnlyList<double> OneSecondRatesMBps,
    [property: JsonPropertyName("fiveSecondRateWindowsMBps")] IReadOnlyList<R3FiveSecondRateWindow> FiveSecondRateWindowsMBps,
    [property: JsonPropertyName("firstSteadyQuarterRateMBps")] MetricValue<double> FirstSteadyQuarterRateMBps,
    [property: JsonPropertyName("finalSteadyQuarterRateMBps")] MetricValue<double> FinalSteadyQuarterRateMBps,
    [property: JsonPropertyName("finalToFirstQuarterRatio")] MetricValue<double> FinalToFirstQuarterRatio);

public sealed record R3PipelineTimingsInfo(
    [property: JsonPropertyName("sourceReadMs")] MetricValue<double> SourceReadMs,
    [property: JsonPropertyName("hashComputeMs")] MetricValue<double> HashComputeMs,
    [property: JsonPropertyName("tlsWriteMs")] MetricValue<double> TlsWriteMs,
    [property: JsonPropertyName("tlsReadMs")] MetricValue<double> TlsReadMs,
    [property: JsonPropertyName("stagingWriteMs")] MetricValue<double> StagingWriteMs,
    [property: JsonPropertyName("verificationReadMs")] MetricValue<double> VerificationReadMs,
    [property: JsonPropertyName("commitMs")] MetricValue<double> CommitMs);

public sealed record R3ResourcesInfo(
    [property: JsonPropertyName("cpuPercentAvg")] MetricValue<double> CpuPercentAvg,
    [property: JsonPropertyName("cpuPercentPeak")] MetricValue<double> CpuPercentPeak,
    [property: JsonPropertyName("managedHeapBaselineMiB")] double ManagedHeapBaselineMiB,
    [property: JsonPropertyName("managedHeapPeakMiB")] double ManagedHeapPeakMiB,
    [property: JsonPropertyName("managedHeapDeltaMiB")] double ManagedHeapDeltaMiB,
    [property: JsonPropertyName("managedAllocatedTotalMB")] double ManagedAllocatedTotalMB,
    [property: JsonPropertyName("managedAllocatedRateAvgMBPerSec")] double ManagedAllocatedRateAvgMBPerSec,
    [property: JsonPropertyName("nativeOrPrivateMemoryBaselineMiB")] MetricValue<double> NativeOrPrivateMemoryBaselineMiB,
    [property: JsonPropertyName("nativeOrPrivateMemoryPeakMiB")] MetricValue<double> NativeOrPrivateMemoryPeakMiB,
    [property: JsonPropertyName("workingSetPeakMiB")] double WorkingSetPeakMiB,
    [property: JsonPropertyName("gcPauseTotalMs")] double GcPauseTotalMs,
    [property: JsonPropertyName("gcPausePercentPeak")] double GcPausePercentPeak,
    [property: JsonPropertyName("gen0Collections")] int Gen0Collections,
    [property: JsonPropertyName("gen1Collections")] int Gen1Collections,
    [property: JsonPropertyName("gen2Collections")] int Gen2Collections,
    [property: JsonPropertyName("threadPoolPendingWorkItemsPeak")] long ThreadPoolPendingWorkItemsPeak);

public sealed record R3QueueOccupancyInfo(
    [property: JsonPropertyName("senderQueueDepthMax")] int SenderQueueDepthMax,
    [property: JsonPropertyName("receiverQueueDepthMax")] int ReceiverQueueDepthMax,
    [property: JsonPropertyName("activePooledBuffersPeak")] int ActivePooledBuffersPeak,
    [property: JsonPropertyName("activePooledBytesPeak")] long ActivePooledBytesPeak);

public sealed record R3DiskActivityInfo(
    [property: JsonPropertyName("processReadIoBytes")] MetricValue<long> ProcessReadIoBytes,
    [property: JsonPropertyName("processWriteIoBytes")] MetricValue<long> ProcessWriteIoBytes,
    [property: JsonPropertyName("processReadMBpsAvg")] MetricValue<double> ProcessReadMBpsAvg,
    [property: JsonPropertyName("processWriteMBpsAvg")] MetricValue<double> ProcessWriteMBpsAvg,
    [property: JsonPropertyName("volumeReadLatencyMsAvg")] MetricValue<double> VolumeReadLatencyMsAvg,
    [property: JsonPropertyName("volumeWriteLatencyMsAvg")] MetricValue<double> VolumeWriteLatencyMsAvg);

public sealed record R3CancellationInfo(
    [property: JsonPropertyName("cancellationRequested")] bool CancellationRequested,
    [property: JsonPropertyName("uiCancelLatencyMs")] MetricValue<double> UiCancelLatencyMs,
    [property: JsonPropertyName("resourceReleaseLatencyMs")] MetricValue<double> ResourceReleaseLatencyMs);

public sealed record R3AndroidConditionsInfo(
    [property: JsonPropertyName("thermalStatusStart")] MetricValue<string> ThermalStatusStart,
    [property: JsonPropertyName("thermalStatusPeak")] MetricValue<string> ThermalStatusPeak,
    [property: JsonPropertyName("thermalStatusEnd")] MetricValue<string> ThermalStatusEnd,
    [property: JsonPropertyName("thermalHeadroom")] MetricValue<double> ThermalHeadroom,
    [property: JsonPropertyName("batteryLevelPercentStart")] MetricValue<int> BatteryLevelPercentStart,
    [property: JsonPropertyName("batteryLevelPercentEnd")] MetricValue<int> BatteryLevelPercentEnd,
    [property: JsonPropertyName("isCharging")] MetricValue<bool> IsCharging,
    [property: JsonPropertyName("batterySaverEnabled")] MetricValue<bool> BatterySaverEnabled,
    [property: JsonPropertyName("batteryTemperatureCelsiusStart")] MetricValue<double> BatteryTemperatureCelsiusStart,
    [property: JsonPropertyName("batteryTemperatureCelsiusEnd")] MetricValue<double> BatteryTemperatureCelsiusEnd);

public sealed record R3LinuxConditionsInfo(
    [property: JsonPropertyName("kernelRelease")] MetricValue<string> KernelRelease,
    [property: JsonPropertyName("distro")] MetricValue<string> Distro,
    [property: JsonPropertyName("virtualization")] MetricValue<string> Virtualization,
    [property: JsonPropertyName("cgroupMemoryLimitBytes")] MetricValue<long> CgroupMemoryLimitBytes,
    [property: JsonPropertyName("networkBridgeOrNatMode")] MetricValue<string> NetworkBridgeOrNatMode,
    [property: JsonPropertyName("stagingMountOptions")] MetricValue<string> StagingMountOptions,
    [property: JsonPropertyName("destinationMountOptions")] MetricValue<string> DestinationMountOptions);

public interface IDesktopTransferDiagnostics
{
    IDesktopTransferAttemptDiagnostics BeginAttempt(
        DesktopTransferDirection direction,
        long declaredTotalBytes,
        string? attemptId = null);
}

public interface IDesktopTransferAttemptDiagnostics : IDisposable, IAsyncDisposable
{
    string AttemptId { get; }
    string BuildCommit { get; }
    void SetDeclaredTotalBytes(long declaredTotalBytes);
    void SetAttemptMetadata(DesktopAttemptMetadata metadata);
    void RecordDiscoveryDurationTicks(long stopwatchTicks);
    void RecordConnectionDurationTicks(long stopwatchTicks);
    void RecordAuthenticationDurationTicks(long stopwatchTicks);
    void RecordFinalAcknowledgementDurationTicks(long stopwatchTicks);
    void RecordCancellation(long requestedTimestamp, long uiUpdatedTimestamp, long resourcesReleasedTimestamp);
    void OnPhaseChanged(DesktopTransferPhase phase);
    void OnConsentCompleted();
    void AddSourceRead(long bytes, long elapsedStopwatchTicks);
    void AddHashCompute(long elapsedStopwatchTicks);
    void AddTlsWrite(long payloadBytes, long elapsedStopwatchTicks);
    void AddTlsRead(long payloadBytes, long elapsedStopwatchTicks);
    void AddStagingWrite(long bytes, long elapsedStopwatchTicks);
    void AddVerificationRead(long bytes, long elapsedStopwatchTicks);
    void AddCommit(long elapsedStopwatchTicks);
    void UpdateQueueOccupancy(
        int senderDepth,
        int senderMaxDepth,
        int receiverDepth,
        int receiverMaxDepth,
        int activePooledBuffers,
        long activePooledBytes);
    void OnItemVerified(long bytes);
    DesktopTransferDiagnosticSample CaptureSnapshot(bool isTerminal = false, string? terminalStatus = null);
    DesktopTransferDiagnosticSample Complete(DesktopTransferResult result, string? terminalOverride = null);
    R3DiagnosticReport BuildR3Report(DesktopTransferResult result, DesktopR3ReportOptions? options = null);
    IReadOnlyList<DesktopTransferDiagnosticSample> GetRecordedSamples();
}

/// <summary>
/// Bounded 1 Hz background diagnostic sink for desktop transfer benchmarking and root-cause analysis.
/// Never blocks the transfer pipeline when writing to disk.
/// </summary>
public sealed class BoundedTransferDiagnosticSink : IDesktopTransferDiagnostics, IAsyncDisposable, IDisposable
{
    public const string DiagnosticLogPathEnvVar = "GLIDE_DIAGNOSTIC_LOG_PATH";
    public const string BuildCommitEnvVar = "GLIDE_BUILD_COMMIT";
    public const long DefaultMaxLogFileBytes = 5L * 1024 * 1024; // 5 MiB
    public const int DefaultMaxLogFiles = 4;
    private const int BoundedQueueCapacity = 256;

    private readonly string? _logFilePath;
    private readonly long _maxFileBytes;
    private readonly int _maxFileCount;
    private readonly string _buildCommit;
    private readonly Channel<string>? _writeChannel;
    private readonly Task? _writerTask;
    private readonly CancellationTokenSource _disposeCts = new();
    private bool _disposed;

    public BoundedTransferDiagnosticSink(
        string? logFilePath = null,
        string? buildCommit = null,
        long maxFileBytes = DefaultMaxLogFileBytes,
        int maxFileCount = DefaultMaxLogFiles)
    {
        _logFilePath = string.IsNullOrWhiteSpace(logFilePath)
            ? Environment.GetEnvironmentVariable(DiagnosticLogPathEnvVar)
            : logFilePath;
        _maxFileBytes = Math.Max(4096, maxFileBytes);
        _maxFileCount = Math.Clamp(maxFileCount, 1, 16);
        _buildCommit = ResolveBuildCommit(buildCommit);

        if (!string.IsNullOrWhiteSpace(_logFilePath))
        {
            string fullPath = Path.GetFullPath(_logFilePath);
            string? dir = Path.GetDirectoryName(fullPath);
            if (!string.IsNullOrWhiteSpace(dir))
            {
                Directory.CreateDirectory(dir);
            }

            _writeChannel = Channel.CreateBounded<string>(new BoundedChannelOptions(BoundedQueueCapacity)
            {
                SingleReader = true,
                SingleWriter = false,
                FullMode = BoundedChannelFullMode.DropOldest
            });
            _writerTask = Task.Run(() => RunFileWriterAsync(fullPath, _writeChannel.Reader, _maxFileBytes, _maxFileCount));
        }
    }

    public string? LogFilePath => _logFilePath;

    public static string GetDefaultRotatingLogPath()
    {
        string? envPath = Environment.GetEnvironmentVariable(DiagnosticLogPathEnvVar);
        if (!string.IsNullOrWhiteSpace(envPath))
        {
            return Path.GetFullPath(envPath);
        }

        if (OperatingSystem.IsLinux())
        {
            string? xdgState = Environment.GetEnvironmentVariable("XDG_STATE_HOME");
            string baseDir = !string.IsNullOrWhiteSpace(xdgState)
                ? xdgState
                : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "state");
            string preferred = Path.Combine(baseDir, "glide", "logs", "glide-diagnostics.jsonl");
            string legacyAppData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Glide", "logs", "glide-diagnostics.jsonl");
            if (!File.Exists(preferred) && File.Exists(legacyAppData))
            {
                return legacyAppData;
            }
            return preferred;
        }

        string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(appData))
        {
            appData = Path.GetTempPath();
        }

        return Path.Combine(appData, "Glide", "logs", "glide-diagnostics.jsonl");
    }

    public static IDesktopTransferDiagnostics CreateDefault() =>
        new BoundedTransferDiagnosticSink();

    public static BoundedTransferDiagnosticSink CreateDefaultRotating() =>
        new BoundedTransferDiagnosticSink(GetDefaultRotatingLogPath());

    public IDesktopTransferAttemptDiagnostics BeginAttempt(
        DesktopTransferDirection direction,
        long declaredTotalBytes,
        string? attemptId = null)
    {
        string id = string.IsNullOrWhiteSpace(attemptId)
            ? Guid.NewGuid().ToString("N")
            : attemptId;
        return new AttemptDiagnostics(this, id, _buildCommit, direction, declaredTotalBytes);
    }

    public void RecordEvent(string category, string message, string? technicalDetail = null)
    {
        if (!IsWriting) return;
        var payload = new
        {
            timestampUtc = DateTimeOffset.UtcNow.ToString("O"),
            buildCommit = _buildCommit,
            type = "event",
            category = SanitizeDiagnosticText(category),
            message = SanitizeDiagnosticText(message),
            detail = string.IsNullOrWhiteSpace(technicalDetail) ? null : SanitizeDiagnosticText(technicalDetail)
        };
        EnqueueLine(JsonSerializer.Serialize(payload));
    }

    public static string SanitizeDiagnosticText(string? input)
    {
        if (string.IsNullOrEmpty(input))
        {
            return string.Empty;
        }

        string s = input;
        s = System.Text.RegularExpressions.Regex.Replace(
            s,
            @"glide://pair/[^\s""']+",
            "glide://pair/[REDACTED]",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        s = System.Text.RegularExpressions.Regex.Replace(
            s,
            @"([?&])(token|secret|pin|password|spki|privatekey|code)=[^&\s""']+",
            "$1$2=[REDACTED]",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        s = System.Text.RegularExpressions.Regex.Replace(
            s,
            @"(token|secret|pin|password|spki|privatekey)\s*[:=]\s*[^\s,;""']+",
            "$1=[REDACTED]",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        s = System.Text.RegularExpressions.Regex.Replace(
            s,
            @"Authorization\s*:\s*[^\r\n""']+",
            "Authorization: [REDACTED]",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        return s;
    }

    public static string SanitizePathForDiagnostics(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return "unavailable";
        }

        string s = path.Trim();
        string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (!string.IsNullOrWhiteSpace(userProfile) &&
            s.StartsWith(userProfile, StringComparison.OrdinalIgnoreCase))
        {
            string suffix = s[userProfile.Length..];
            s = string.IsNullOrEmpty(suffix) ? "~" : "~" + suffix;
        }

        s = System.Text.RegularExpressions.Regex.Replace(
            s,
            @"^[A-Za-z]:\\Users\\[^\\/\r\n]+",
            "~",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        s = System.Text.RegularExpressions.Regex.Replace(
            s,
            @"^/(?:home|Users)/[^/\r\n]+",
            "~");
        return SanitizeDiagnosticText(s);
    }

    public static string TruncateMessage(string? message, int maxLength = 512)
    {
        string sanitized = SanitizeDiagnosticText(message);
        if (sanitized.Length <= maxLength)
        {
            return sanitized;
        }

        return sanitized[..maxLength];
    }

    public static async Task WriteR3ReportFileAsync(
        string outputPath,
        R3DiagnosticReport report,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(outputPath);
        ArgumentNullException.ThrowIfNull(report);
        string fullPath = Path.GetFullPath(outputPath);
        string? dir = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrWhiteSpace(dir))
        {
            Directory.CreateDirectory(dir);
        }

        await File.WriteAllTextAsync(fullPath, report.ToJson(indented: true), cancellationToken).ConfigureAwait(false);
    }

    public static IReadOnlyList<R3FiveSecondRateWindow> ComputeFiveSecondRateWindows(
        IReadOnlyList<(double ElapsedSec, long Bytes)> points,
        int maxWindows = 2160)
    {
        if (points == null || points.Count < 2 || maxWindows <= 0)
        {
            return Array.Empty<R3FiveSecondRateWindow>();
        }

        double maxSec = points[^1].ElapsedSec;
        if (maxSec < 5.0)
        {
            return Array.Empty<R3FiveSecondRateWindow>();
        }

        var windows = new List<R3FiveSecondRateWindow>();
        double start = 0.0;
        int windowIdx = 0;

        while (start + 5.0 <= maxSec + 1e-6 && windows.Count < maxWindows)
        {
            double end = start + 5.0;
            long bStart = InterpolateBytesAt(points, start);
            long bEnd = InterpolateBytesAt(points, end);
            long delta = Math.Max(0L, bEnd - bStart);
            double rate = (delta / 5.0) / 1_000_000.0;
            windows.Add(new R3FiveSecondRateWindow(
                WindowIndex: windowIdx++,
                StartElapsedSec: Math.Round(start, 2),
                EndElapsedSec: Math.Round(end, 2),
                Bytes: delta,
                RateMBps: Math.Round(rate, 2)));
            start = end;
        }

        if (maxSec - start >= 1.0 && windows.Count < maxWindows)
        {
            double end = maxSec;
            double dur = end - start;
            long bStart = InterpolateBytesAt(points, start);
            long bEnd = points[^1].Bytes;
            long delta = Math.Max(0L, bEnd - bStart);
            double rate = dur > 0.001 ? (delta / dur) / 1_000_000.0 : 0.0;
            windows.Add(new R3FiveSecondRateWindow(
                WindowIndex: windowIdx,
                StartElapsedSec: Math.Round(start, 2),
                EndElapsedSec: Math.Round(end, 2),
                Bytes: delta,
                RateMBps: Math.Round(rate, 2)));
        }

        return windows;
    }

    public static (MetricValue<double> FirstQuarter, MetricValue<double> FinalQuarter, MetricValue<double> Ratio) ComputeSteadyQuarterRates(
        IReadOnlyList<(double ElapsedSec, long Bytes)> points,
        long totalBytes)
    {
        if (points == null || points.Count < 2 || totalBytes <= 0)
        {
            return (MetricValue<double>.Unavailable, MetricValue<double>.Unavailable, MetricValue<double>.Unavailable);
        }

        double totalSec = points[^1].ElapsedSec;
        if (totalSec <= 0.001 || points[^1].Bytes <= 0)
        {
            return (MetricValue<double>.Unavailable, MetricValue<double>.Unavailable, MetricValue<double>.Unavailable);
        }

        long effectiveTotal = Math.Min(totalBytes, points[^1].Bytes);
        double q1Bytes = effectiveTotal * 0.25;
        double q3Bytes = effectiveTotal * 0.75;

        double? t25 = InterpolateTimeAt(points, q1Bytes);
        double? t75 = InterpolateTimeAt(points, q3Bytes);
        if (!t25.HasValue || !t75.HasValue || t25.Value <= 0.0001 || totalSec - t75.Value <= 0.0001)
        {
            return (MetricValue<double>.Unavailable, MetricValue<double>.Unavailable, MetricValue<double>.Unavailable);
        }

        double firstRate = (q1Bytes / t25.Value) / 1_000_000.0;
        double finalBytes = effectiveTotal - q3Bytes;
        double finalDur = totalSec - t75.Value;
        double finalRate = (finalBytes / finalDur) / 1_000_000.0;
        if (firstRate <= 0.0001)
        {
            return (MetricValue<double>.Available(Math.Round(firstRate, 2)),
                MetricValue<double>.Available(Math.Round(finalRate, 2)),
                MetricValue<double>.Unavailable);
        }

        double ratio = finalRate / firstRate;
        return (
            MetricValue<double>.Available(Math.Round(firstRate, 2)),
            MetricValue<double>.Available(Math.Round(finalRate, 2)),
            MetricValue<double>.Available(Math.Round(ratio, 3)));
    }

    private static long InterpolateBytesAt(IReadOnlyList<(double ElapsedSec, long Bytes)> points, double targetSec)
    {
        if (targetSec <= points[0].ElapsedSec) return points[0].Bytes;
        if (targetSec >= points[^1].ElapsedSec) return points[^1].Bytes;

        for (int i = 1; i < points.Count; i++)
        {
            if (points[i].ElapsedSec >= targetSec)
            {
                double t0 = points[i - 1].ElapsedSec;
                double t1 = points[i].ElapsedSec;
                long b0 = points[i - 1].Bytes;
                long b1 = points[i].Bytes;
                double span = t1 - t0;
                if (span <= 1e-9) return b1;
                double frac = Math.Clamp((targetSec - t0) / span, 0.0, 1.0);
                return b0 + (long)Math.Round((b1 - b0) * frac);
            }
        }

        return points[^1].Bytes;
    }

    private static double? InterpolateTimeAt(IReadOnlyList<(double ElapsedSec, long Bytes)> points, double targetBytes)
    {
        if (targetBytes <= points[0].Bytes) return points[0].ElapsedSec;
        if (targetBytes > points[^1].Bytes) return null;

        for (int i = 1; i < points.Count; i++)
        {
            if (points[i].Bytes >= targetBytes)
            {
                double t0 = points[i - 1].ElapsedSec;
                double t1 = points[i].ElapsedSec;
                long b0 = points[i - 1].Bytes;
                long b1 = points[i].Bytes;
                long span = b1 - b0;
                if (span <= 0) return t1;
                double frac = Math.Clamp((targetBytes - b0) / (double)span, 0.0, 1.0);
                return t0 + ((t1 - t0) * frac);
            }
        }

        return null;
    }

    public static async Task<string> ExportSanitizedDiagnosticBundleAsync(
        string destinationFilePath,
        string? primaryLogFilePath = null,
        string? extraSummaryJson = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationFilePath);
        string fullDest = Path.GetFullPath(destinationFilePath);
        string? destDir = Path.GetDirectoryName(fullDest);
        if (!string.IsNullOrWhiteSpace(destDir))
        {
            Directory.CreateDirectory(destDir);
        }

        string logBase = string.IsNullOrWhiteSpace(primaryLogFilePath)
            ? GetDefaultRotatingLogPath()
            : Path.GetFullPath(primaryLogFilePath);

        await using var outStream = new FileStream(
            fullDest,
            FileMode.Create,
            FileAccess.Write,
            FileShare.Read,
            bufferSize: 16_384,
            useAsync: true);
        await using var writer = new StreamWriter(outStream);

        var header = new
        {
            exportedAtUtc = DateTimeOffset.UtcNow.ToString("O"),
            buildCommit = ResolveBuildCommit(),
            osDescription = System.Runtime.InteropServices.RuntimeInformation.OSDescription,
            frameworkDescription = System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription,
            summary = string.IsNullOrWhiteSpace(extraSummaryJson) ? null : SanitizeDiagnosticText(extraSummaryJson)
        };
        await writer.WriteLineAsync(JsonSerializer.Serialize(header)).ConfigureAwait(false);

        for (int idx = DefaultMaxLogFiles - 1; idx >= 0; idx--)
        {
            cancellationToken.ThrowIfCancellationRequested();
            string candidate = idx == 0 ? logBase : $"{logBase}.{idx}";
            if (!File.Exists(candidate))
            {
                continue;
            }

            try
            {
                await using var inStream = new FileStream(
                    candidate,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete,
                    bufferSize: 16_384,
                    useAsync: true);
                using var reader = new StreamReader(inStream);
                while (await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false) is { } line)
                {
                    await writer.WriteLineAsync(SanitizeDiagnosticText(line)).ConfigureAwait(false);
                }
            }
            catch
            {
                // Best-effort export of active log files
            }
        }

        await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
        return fullDest;
    }

    internal bool IsWriting => !_disposed && _writeChannel != null;

    internal void EnqueueLine(string jsonLine)
    {
        if (_disposed || _writeChannel == null) return;
        _writeChannel.Writer.TryWrite(SanitizeDiagnosticText(jsonLine));
    }

    private static async Task RunFileWriterAsync(
        string filePath,
        ChannelReader<string> reader,
        long maxFileBytes,
        int maxFileCount)
    {
        FileStream? stream = null;
        StreamWriter? writer = null;
        try
        {
            void OpenLogStream()
            {
                stream = new FileStream(
                    filePath,
                    FileMode.Append,
                    FileAccess.Write,
                    FileShare.ReadWrite | FileShare.Delete,
                    bufferSize: 16_384,
                    useAsync: true);
                writer = new StreamWriter(stream);
            }

            void RotateLogsIfNeeded(int upcomingBytes)
            {
                if (stream == null)
                {
                    OpenLogStream();
                }

                if (stream!.Length + upcomingBytes <= maxFileBytes)
                {
                    return;
                }

                writer?.Dispose();
                stream?.Dispose();
                writer = null;
                stream = null;

                if (maxFileCount <= 1)
                {
                    try { File.Delete(filePath); } catch { }
                }
                else
                {
                    string oldest = $"{filePath}.{maxFileCount - 1}";
                    try { if (File.Exists(oldest)) File.Delete(oldest); } catch { }

                    for (int i = maxFileCount - 2; i >= 1; i--)
                    {
                        string src = $"{filePath}.{i}";
                        string dst = $"{filePath}.{i + 1}";
                        try { if (File.Exists(src)) File.Move(src, dst, overwrite: true); } catch { }
                    }

                    try { if (File.Exists(filePath)) File.Move(filePath, $"{filePath}.1", overwrite: true); } catch { }
                }

                OpenLogStream();
            }

            OpenLogStream();

            await foreach (string line in reader.ReadAllAsync().ConfigureAwait(false))
            {
                int byteEstimate = System.Text.Encoding.UTF8.GetByteCount(line) + 2;
                RotateLogsIfNeeded(byteEstimate);
                if (writer != null)
                {
                    await writer.WriteLineAsync(line).ConfigureAwait(false);
                    await writer.FlushAsync().ConfigureAwait(false);
                }
            }
        }
        catch
        {
            // Diagnostic logging must never crash the host process.
        }
        finally
        {
            try { writer?.Dispose(); } catch { }
            try { stream?.Dispose(); } catch { }
        }
    }

    public static string ResolveBuildCommit(string? explicitCommit = null)
    {
        if (!string.IsNullOrWhiteSpace(explicitCommit))
        {
            return explicitCommit.Trim();
        }

        string? envCommit = Environment.GetEnvironmentVariable(BuildCommitEnvVar);
        if (!string.IsNullOrWhiteSpace(envCommit))
        {
            return envCommit.Trim();
        }

        string? infoVersion = typeof(BoundedTransferDiagnosticSink).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?
            .InformationalVersion;
        if (!string.IsNullOrWhiteSpace(infoVersion))
        {
            int plusIndex = infoVersion.IndexOf('+');
            if (plusIndex >= 0 && plusIndex + 1 < infoVersion.Length)
            {
                string commitPart = infoVersion[(plusIndex + 1)..];
                return commitPart.Length > 12 ? commitPart[..12] : commitPart;
            }
            return infoVersion;
        }

        return "1.0.0";
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _writeChannel?.Writer.TryComplete();
        _disposeCts.Cancel();
        _disposeCts.Dispose();
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        _writeChannel?.Writer.TryComplete();
        if (_writerTask != null)
        {
            try
            {
                await _writerTask.WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
            }
            catch
            {
                // Ignore timeout on shutdown
            }
        }
        _disposeCts.Cancel();
        _disposeCts.Dispose();
    }

    internal static class R3PlatformCollector
    {
        private static readonly Lazy<CachedHostSnapshot> s_cachedHost = new(CollectHostSnapshot);

        [StructLayout(LayoutKind.Sequential)]
        private struct IoCounters
        {
            public ulong ReadOperationCount;
            public ulong WriteOperationCount;
            public ulong OtherOperationCount;
            public ulong ReadTransferCount;
            public ulong WriteTransferCount;
            public ulong OtherTransferCount;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetProcessIoCounters(IntPtr hProcess, out IoCounters lpIoCounters);

        public sealed record CachedHostSnapshot(
            MetricValue<string> ArtifactSha256,
            string AppVersion,
            string SourceCommit,
            R3PlatformInfo PlatformBase,
            R3HardwareInfo Hardware,
            R3RuntimeInfo Runtime,
            R3LinuxConditionsInfo LinuxBase);

        public readonly record struct ProcessIoSnapshot(bool Available, long ReadBytes, long WriteBytes);
        public readonly record struct NetworkSnapshot(
            string InterfaceName,
            string LinkType,
            MetricValue<double> NegotiatedLinkMbps,
            MetricValue<int> MtuBytes,
            MetricValue<long> TcpRetransmits,
            MetricValue<long> OutboundErrors,
            MetricValue<long> ReceivedErrors);

        public static CachedHostSnapshot GetHostSnapshot() => s_cachedHost.Value;

        public static ProcessIoSnapshot TryReadProcessIo()
        {
            try
            {
                if (OperatingSystem.IsWindows())
                {
                    using var proc = Process.GetCurrentProcess();
                    if (GetProcessIoCounters(proc.Handle, out IoCounters counters))
                    {
                        return new ProcessIoSnapshot(
                            true,
                            (long)Math.Min(long.MaxValue, counters.ReadTransferCount),
                            (long)Math.Min(long.MaxValue, counters.WriteTransferCount));
                    }
                }
                else if (OperatingSystem.IsLinux() && File.Exists("/proc/self/io"))
                {
                    long? readBytes = null;
                    long? writeBytes = null;
                    foreach (string line in File.ReadLines("/proc/self/io"))
                    {
                        if (line.StartsWith("read_bytes:", StringComparison.Ordinal))
                        {
                            if (long.TryParse(line.AsSpan("read_bytes:".Length).Trim(), out long rb))
                                readBytes = rb;
                        }
                        else if (line.StartsWith("write_bytes:", StringComparison.Ordinal))
                        {
                            if (long.TryParse(line.AsSpan("write_bytes:".Length).Trim(), out long wb))
                                writeBytes = wb;
                        }
                    }

                    if (readBytes.HasValue && writeBytes.HasValue)
                    {
                        return new ProcessIoSnapshot(true, readBytes.Value, writeBytes.Value);
                    }
                }
            }
            catch
            {
                // Best-effort process IO counter reading
            }

            return default;
        }

        public static (MetricValue<double> NativePrivateMiB, double WorkingSetMiB) TryReadMemory()
        {
            double workingSetMiB = Math.Round(Environment.WorkingSet / (1024.0 * 1024.0), 2);
            try
            {
                using var proc = Process.GetCurrentProcess();
                double privateMiB = proc.PrivateMemorySize64 / (1024.0 * 1024.0);
                double managedMiB = GC.GetTotalMemory(forceFullCollection: false) / (1024.0 * 1024.0);
                double nativeMiB = Math.Max(0.0, privateMiB - managedMiB);
                return (MetricValue<double>.Available(Math.Round(nativeMiB, 2)), workingSetMiB);
            }
            catch
            {
                return (MetricValue<double>.Unavailable, workingSetMiB);
            }
        }

        public static NetworkSnapshot TryCaptureNetworkSnapshot(string? localAddress)
        {
            string ifaceName = "unavailable";
            string linkType = "unavailable";
            MetricValue<double> speedMbps = MetricValue<double>.Unavailable;
            MetricValue<int> mtu = MetricValue<int>.Unavailable;
            MetricValue<long> retransmits = MetricValue<long>.Unavailable;
            MetricValue<long> outErr = MetricValue<long>.Unavailable;
            MetricValue<long> inErr = MetricValue<long>.Unavailable;

            try
            {
                IPAddress? parsedLocal = null;
                if (!string.IsNullOrWhiteSpace(localAddress))
                {
                    IPAddress.TryParse(localAddress.Trim(), out parsedLocal);
                }

                bool wantLoopback = parsedLocal != null && IPAddress.IsLoopback(parsedLocal);
                NetworkInterface[] allNics = NetworkInterface.GetAllNetworkInterfaces();
                NetworkInterface? chosen = null;

                if (parsedLocal != null)
                {
                    foreach (NetworkInterface ni in allNics)
                    {
                        try
                        {
                            if (ni.OperationalStatus != OperationalStatus.Up) continue;
                            foreach (UnicastIPAddressInformation uni in ni.GetIPProperties().UnicastAddresses)
                            {
                                if (uni.Address.Equals(parsedLocal))
                                {
                                    chosen = ni;
                                    break;
                                }
                            }
                            if (chosen != null) break;
                        }
                        catch { }
                    }
                }

                chosen ??= allNics.FirstOrDefault(ni =>
                    ni.OperationalStatus == OperationalStatus.Up &&
                    (wantLoopback
                        ? ni.NetworkInterfaceType == NetworkInterfaceType.Loopback
                        : ni.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                          ni.NetworkInterfaceType != NetworkInterfaceType.Tunnel));

                chosen ??= allNics.FirstOrDefault(ni => ni.OperationalStatus == OperationalStatus.Up);

                if (chosen != null)
                {
                    ifaceName = SanitizeDiagnosticText(chosen.Name);
                    linkType = chosen.NetworkInterfaceType switch
                    {
                        NetworkInterfaceType.Loopback => "loopback",
                        NetworkInterfaceType.Wireless80211 => "wifi",
                        NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetT => "ethernet",
                        _ => chosen.Description.Contains("vEthernet", StringComparison.OrdinalIgnoreCase) ||
                             chosen.Description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) ||
                             chosen.Description.Contains("Virtual", StringComparison.OrdinalIgnoreCase)
                            ? "virtual-vswitch"
                            : chosen.NetworkInterfaceType.ToString().ToLowerInvariant()
                    };

                    if (chosen.Speed > 0)
                    {
                        speedMbps = MetricValue<double>.Available(Math.Round(chosen.Speed / 1_000_000.0, 2));
                    }

                    try
                    {
                        IPv4InterfaceProperties? v4 = chosen.GetIPProperties().GetIPv4Properties();
                        if (v4 != null && v4.Mtu > 0)
                        {
                            mtu = MetricValue<int>.Available(v4.Mtu);
                        }
                    }
                    catch { }

                    try
                    {
                        IPInterfaceStatistics stats = chosen.GetIPStatistics();
                        outErr = MetricValue<long>.Available(stats.OutgoingPacketsWithErrors);
                        inErr = MetricValue<long>.Available(stats.IncomingPacketsWithErrors);
                    }
                    catch { }
                }
            }
            catch { }

            try
            {
                TcpStatistics tcpStats = IPGlobalProperties.GetIPGlobalProperties().GetTcpIPv4Statistics();
                retransmits = MetricValue<long>.Available(tcpStats.SegmentsResent);
            }
            catch { }

            return new NetworkSnapshot(ifaceName, linkType, speedMbps, mtu, retransmits, outErr, inErr);
        }

        public static string ClassifyIpAddress(string? address)
        {
            if (string.IsNullOrWhiteSpace(address) || !IPAddress.TryParse(address.Trim(), out IPAddress? ip))
            {
                return "unavailable";
            }

            if (IPAddress.IsLoopback(ip)) return "loopback";
            if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();

            byte[] bytes = ip.GetAddressBytes();
            if (ip.AddressFamily == AddressFamily.InterNetwork && bytes.Length == 4)
            {
                if (bytes[0] == 169 && bytes[1] == 254) return "link-local";
                if (bytes[0] == 10) return "rfc1918";
                if (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) return "rfc1918";
                if (bytes[0] == 192 && bytes[1] == 168) return "rfc1918";
                return "public";
            }

            if (ip.IsIPv6LinkLocal) return "link-local";
            if (ip.IsIPv6UniqueLocal) return "rfc1918";
            return "public";
        }

        public static (MetricValue<string> VolumeOrMount, MetricValue<string> Filesystem, MetricValue<string> StorageType, MetricValue<long> FreeBytes) InspectStoragePath(string? path)
        {
            if (string.IsNullOrWhiteSpace(path))
            {
                return (MetricValue<string>.Unavailable, MetricValue<string>.Unavailable, MetricValue<string>.Unavailable, MetricValue<long>.Unavailable);
            }

            try
            {
                string full = Path.GetFullPath(path);
                if (OperatingSystem.IsLinux() && File.Exists("/proc/mounts"))
                {
                    string bestMount = "/";
                    string bestFs = "unavailable";
                    foreach (string line in File.ReadLines("/proc/mounts"))
                    {
                        string[] parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                        if (parts.Length < 3) continue;
                        string mp = parts[1].Replace("\\040", " ");
                        if ((full == mp || full.StartsWith(mp == "/" ? "/" : mp + "/", StringComparison.Ordinal)) &&
                            mp.Length >= bestMount.Length)
                        {
                            bestMount = mp;
                            bestFs = parts[2];
                        }
                    }

                    long? free = null;
                    try
                    {
                        var di = new DriveInfo(full);
                        free = di.AvailableFreeSpace;
                    }
                    catch { }

                    return (
                        MetricValue<string>.Available(bestMount),
                        MetricValue<string>.Available(bestFs),
                        MetricValue<string>.Available(bestFs is "9p" or "drvfs" ? "virtual-disk" : "unavailable"),
                        free.HasValue ? MetricValue<long>.Available(free.Value) : MetricValue<long>.Unavailable);
                }

                string? root = Path.GetPathRoot(full);
                if (!string.IsNullOrWhiteSpace(root))
                {
                    var drive = new DriveInfo(root);
                    return (
                        MetricValue<string>.Available(drive.Name),
                        MetricValue<string>.Available(drive.DriveFormat),
                        MetricValue<string>.Unavailable,
                        MetricValue<long>.Available(drive.AvailableFreeSpace));
                }
            }
            catch { }

            return (MetricValue<string>.Unavailable, MetricValue<string>.Unavailable, MetricValue<string>.Unavailable, MetricValue<long>.Unavailable);
        }

        public static MetricValue<string> TryReadLinuxMountOptions(string? path)
        {
            if (!OperatingSystem.IsLinux() || string.IsNullOrWhiteSpace(path) || !File.Exists("/proc/mounts"))
            {
                return MetricValue<string>.Unavailable;
            }

            try
            {
                string full = Path.GetFullPath(path);
                string bestMount = string.Empty;
                string bestOpts = "unavailable";
                foreach (string line in File.ReadLines("/proc/mounts"))
                {
                    string[] parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length < 4) continue;
                    string mp = parts[1].Replace("\\040", " ");
                    if ((full == mp || full.StartsWith(mp == "/" ? "/" : mp + "/", StringComparison.Ordinal)) &&
                        mp.Length >= bestMount.Length)
                    {
                        bestMount = mp;
                        bestOpts = parts[3];
                    }
                }
                return MetricValue<string>.Available(bestOpts);
            }
            catch
            {
                return MetricValue<string>.Unavailable;
            }
        }

        private static CachedHostSnapshot CollectHostSnapshot()
        {
            MetricValue<string> artifactSha = MetricValue<string>.Unavailable;
            try
            {
                string? exePath = Environment.ProcessPath;
                if (!string.IsNullOrWhiteSpace(exePath) && File.Exists(exePath))
                {
                    using var fs = new FileStream(exePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                    artifactSha = MetricValue<string>.Available(Convert.ToHexStringLower(SHA256.HashData(fs)));
                }
            }
            catch { }

            string osFamily = OperatingSystem.IsWindows() ? "windows"
                : OperatingSystem.IsLinux() ? "linux"
                : OperatingSystem.IsMacOS() ? "macos"
                : "unknown";

            string osVersion = RuntimeInformation.OSDescription.Trim();
            string distro = "unavailable";
            string kernelRelease = "unavailable";
            string virtualization = "unavailable";
            MetricValue<long> cgroupMem = MetricValue<long>.Unavailable;
            string execEnv = "physical";

            if (OperatingSystem.IsLinux())
            {
                try
                {
                    if (File.Exists("/proc/version"))
                    {
                        string procVer = File.ReadAllText("/proc/version");
                        if (procVer.Contains("microsoft", StringComparison.OrdinalIgnoreCase) ||
                            procVer.Contains("WSL", StringComparison.OrdinalIgnoreCase))
                        {
                            execEnv = "wsl2";
                            virtualization = "wsl2";
                        }
                    }

                    if (File.Exists("/proc/sys/kernel/osrelease"))
                    {
                        kernelRelease = File.ReadAllText("/proc/sys/kernel/osrelease").Trim();
                    }

                    if (File.Exists("/etc/os-release"))
                    {
                        foreach (string line in File.ReadLines("/etc/os-release"))
                        {
                            if (line.StartsWith("PRETTY_NAME=", StringComparison.Ordinal))
                            {
                                distro = line["PRETTY_NAME=".Length..].Trim().Trim('"');
                                osVersion = distro;
                                break;
                            }
                        }
                    }

                    if (virtualization == "unavailable" && File.Exists("/sys/class/dmi/id/product_name"))
                    {
                        string prod = File.ReadAllText("/sys/class/dmi/id/product_name").Trim();
                        if (prod.Contains("VirtualBox", StringComparison.OrdinalIgnoreCase))
                        {
                            execEnv = "vm-virtualbox";
                            virtualization = "virtualbox";
                        }
                        else if (prod.Contains("KVM", StringComparison.OrdinalIgnoreCase) ||
                                 prod.Contains("QEMU", StringComparison.OrdinalIgnoreCase) ||
                                 prod.Contains("VMware", StringComparison.OrdinalIgnoreCase))
                        {
                            execEnv = "vm";
                            virtualization = prod;
                        }
                    }

                    if (File.Exists("/sys/fs/cgroup/memory.max"))
                    {
                        string rawMax = File.ReadAllText("/sys/fs/cgroup/memory.max").Trim();
                        if (long.TryParse(rawMax, out long cgBytes))
                        {
                            cgroupMem = MetricValue<long>.Available(cgBytes);
                        }
                    }
                }
                catch { }
            }

            MetricValue<string> cpuModel = MetricValue<string>.Unavailable;
            try
            {
                if (OperatingSystem.IsWindows())
                {
                    using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
                    string? regName = key?.GetValue("ProcessorNameString") as string;
                    if (!string.IsNullOrWhiteSpace(regName))
                    {
                        cpuModel = MetricValue<string>.Available(regName.Trim());
                    }
                    else
                    {
                        string? envCpu = Environment.GetEnvironmentVariable("PROCESSOR_IDENTIFIER");
                        if (!string.IsNullOrWhiteSpace(envCpu))
                        {
                            cpuModel = MetricValue<string>.Available(envCpu.Trim());
                        }
                    }
                }
                else if (OperatingSystem.IsLinux() && File.Exists("/proc/cpuinfo"))
                {
                    foreach (string line in File.ReadLines("/proc/cpuinfo"))
                    {
                        if (line.StartsWith("model name", StringComparison.OrdinalIgnoreCase))
                        {
                            int colon = line.IndexOf(':');
                            if (colon >= 0 && colon + 1 < line.Length)
                            {
                                cpuModel = MetricValue<string>.Available(line[(colon + 1)..].Trim());
                                break;
                            }
                        }
                    }
                }
            }
            catch { }

            MetricValue<double> totalRamMiB = MetricValue<double>.Unavailable;
            try
            {
                long totalMemBytes = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes;
                if (totalMemBytes > 0)
                {
                    totalRamMiB = MetricValue<double>.Available(Math.Round(totalMemBytes / (1024.0 * 1024.0), 1));
                }
            }
            catch { }

            string buildConfig =
#if DEBUG
                "Debug";
#else
                "Release";
#endif

            string appVersion = typeof(BoundedTransferDiagnosticSink).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
            string commit = ResolveFullSourceCommit();

            return new CachedHostSnapshot(
                ArtifactSha256: artifactSha,
                AppVersion: appVersion,
                SourceCommit: commit,
                PlatformBase: new R3PlatformInfo(
                    OsFamily: osFamily,
                    OsVersion: osVersion,
                    Architecture: RuntimeInformation.ProcessArchitecture.ToString(),
                    ExecutionEnvironment: execEnv,
                    DeviceModel: Environment.MachineName),
                Hardware: new R3HardwareInfo(
                    CpuModel: cpuModel,
                    LogicalCores: Environment.ProcessorCount,
                    TotalRamMiB: totalRamMiB),
                Runtime: new R3RuntimeInfo(
                    RuntimeName: ".NET",
                    RuntimeVersion: RuntimeInformation.FrameworkDescription,
                    BuildConfiguration: buildConfig,
                    GcMode: MetricValue<string>.Available(GCSettings.IsServerGC ? "Server" : "Workstation"),
                    AotOrBaselineProfile: MetricValue<string>.Available(RuntimeFeature.IsDynamicCodeSupported ? "R2R/JIT" : "AOT")),
                LinuxBase: new R3LinuxConditionsInfo(
                    KernelRelease: MetricValue<string>.Available(kernelRelease),
                    Distro: MetricValue<string>.Available(distro),
                    Virtualization: MetricValue<string>.Available(virtualization),
                    CgroupMemoryLimitBytes: cgroupMem,
                    NetworkBridgeOrNatMode: MetricValue<string>.Unavailable,
                    StagingMountOptions: MetricValue<string>.Unavailable,
                    DestinationMountOptions: MetricValue<string>.Unavailable));
        }

        private static string ResolveFullSourceCommit()
        {
            string? envCommit = Environment.GetEnvironmentVariable(BuildCommitEnvVar);
            if (!string.IsNullOrWhiteSpace(envCommit))
            {
                return envCommit.Trim();
            }

            string? infoVersion = typeof(BoundedTransferDiagnosticSink).Assembly
                .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?
                .InformationalVersion;
            if (!string.IsNullOrWhiteSpace(infoVersion))
            {
                int plusIndex = infoVersion.IndexOf('+');
                if (plusIndex >= 0 && plusIndex + 1 < infoVersion.Length)
                {
                    return infoVersion[(plusIndex + 1)..].Trim();
                }
            }

            return ResolveBuildCommit();
        }
    }

    private sealed class AttemptDiagnostics : IDesktopTransferAttemptDiagnostics
    {
        private readonly BoundedTransferDiagnosticSink _sink;
        private readonly DesktopTransferDirection _direction;
        private long _declaredTotalBytes;
        private readonly long _startTimestamp;
        private readonly List<DesktopTransferDiagnosticSample> _samples = new(128);
        private readonly List<(double ElapsedSec, long Bytes)> _payloadProgressPoints = new(128);
        private readonly object _sampleLock = new();
        private readonly Timer _sampleTimer;

        private DesktopAttemptMetadata _metadata = new();
        private DesktopTransferPhase _currentPhase = DesktopTransferPhase.Preparing;
        private long _lastPhaseChangeTimestamp;
        private long _consentCompletedTimestamp;
        private long _payloadStartTimestamp;
        private long _payloadEndTimestamp;
        private long _verifiedCompletedTimestamp;

        private long _discoveryTicks;
        private bool _hasDiscoveryTicks;
        private long _connectionTicks;
        private bool _hasConnectionTicks;
        private long _authenticationTicks;
        private bool _hasAuthenticationTicks;
        private long _finalAcknowledgementTicks;
        private bool _hasFinalAcknowledgementTicks;

        private bool _cancellationRecorded;
        private long _cancelRequestedTimestamp;
        private long _cancelUiUpdatedTimestamp;
        private long _cancelResourcesReleasedTimestamp;

        private long _preparationTicks;
        private long _consentWaitTicks;
        private long _payloadTransferTicks;
        private long _verificationTicks;
        private long _commitPhaseTicks;

        private long _payloadBytesTransferred;
        private long _stagingBytesWritten;
        private long _verifiedBytes;

        private long _sourceReadTicks;
        private long _hashComputeTicks;
        private long _tlsWriteTicks;
        private long _tlsReadTicks;
        private long _stagingWriteTicks;
        private long _verificationReadTicks;
        private long _commitTicks;

        private int _senderQueueDepth;
        private int _senderQueueMaxDepth;
        private int _receiverQueueDepth;
        private int _receiverQueueMaxDepth;
        private int _activePooledBuffers;
        private long _activePooledBytes;

        private long _previousSampleTimestamp;
        private long _previousSamplePayloadBytes;
        private long _previousAllocatedBytes;
        private TimeSpan _previousCpuTime;
        private readonly TimeSpan _initialCpuTime;
        private readonly bool _cpuAvailable;
        private TimeSpan _previousGcPause;
        private readonly TimeSpan _initialGcPause;
        private readonly long _initialAllocatedBytes;
        private readonly double _managedHeapBaselineMiB;
        private double _managedHeapPeakMiB;
        private readonly MetricValue<double> _nativeMemoryBaselineMiB;
        private double _nativeMemoryPeakMiB;
        private bool _hasNativePeak;
        private double _workingSetPeakMiB;
        private double _cpuPercentPeak;
        private bool _hasCpuPeak;
        private double _gcPausePercentPeak;
        private long _threadPoolPendingPeak;
        private readonly int _initialGen0;
        private readonly int _initialGen1;
        private readonly int _initialGen2;
        private readonly R3PlatformCollector.ProcessIoSnapshot _initialIo;
        private readonly R3PlatformCollector.NetworkSnapshot _initialNet;
        private MetricValue<long> _stagingFreeBytesBefore = MetricValue<long>.Unavailable;
        private MetricValue<long> _destinationFreeBytesBefore = MetricValue<long>.Unavailable;
        private int _completed;

        public string AttemptId { get; }
        public string BuildCommit { get; }

        public AttemptDiagnostics(
            BoundedTransferDiagnosticSink sink,
            string attemptId,
            string buildCommit,
            DesktopTransferDirection direction,
            long declaredTotalBytes)
        {
            _sink = sink;
            AttemptId = attemptId;
            BuildCommit = buildCommit;
            _direction = direction;
            _declaredTotalBytes = declaredTotalBytes;

            _startTimestamp = Stopwatch.GetTimestamp();
            _lastPhaseChangeTimestamp = _startTimestamp;
            _previousSampleTimestamp = _startTimestamp;
            _initialAllocatedBytes = GC.GetTotalAllocatedBytes(precise: false);
            _previousAllocatedBytes = _initialAllocatedBytes;
            _managedHeapBaselineMiB = Math.Round(GC.GetTotalMemory(forceFullCollection: false) / (1024.0 * 1024.0), 2);
            _managedHeapPeakMiB = _managedHeapBaselineMiB;
            (_nativeMemoryBaselineMiB, _workingSetPeakMiB) = R3PlatformCollector.TryReadMemory();
            if (_nativeMemoryBaselineMiB.IsAvailable)
            {
                _nativeMemoryPeakMiB = _nativeMemoryBaselineMiB.Value;
                _hasNativePeak = true;
            }

            _initialGcPause = GC.GetTotalPauseDuration();
            _previousGcPause = _initialGcPause;
            _initialGen0 = GC.CollectionCount(0);
            _initialGen1 = GC.CollectionCount(1);
            _initialGen2 = GC.CollectionCount(2);
            _threadPoolPendingPeak = ThreadPool.PendingWorkItemCount;
            _initialIo = R3PlatformCollector.TryReadProcessIo();
            _initialNet = R3PlatformCollector.TryCaptureNetworkSnapshot(null);

            try
            {
                using var proc = Process.GetCurrentProcess();
                _initialCpuTime = proc.TotalProcessorTime;
                _previousCpuTime = _initialCpuTime;
                _cpuAvailable = true;
            }
            catch
            {
                _initialCpuTime = TimeSpan.Zero;
                _previousCpuTime = TimeSpan.Zero;
                _cpuAvailable = false;
            }

            _sampleTimer = new Timer(
                static state => ((AttemptDiagnostics)state!).EmitPeriodicSample(),
                this,
                dueTime: TimeSpan.FromSeconds(1),
                period: TimeSpan.FromSeconds(1));
        }

        public void SetDeclaredTotalBytes(long declaredTotalBytes)
        {
            Interlocked.Exchange(ref _declaredTotalBytes, declaredTotalBytes);
        }

        public void SetAttemptMetadata(DesktopAttemptMetadata metadata)
        {
            ArgumentNullException.ThrowIfNull(metadata);
            lock (_sampleLock)
            {
                _metadata = new DesktopAttemptMetadata(
                    PairingMode: metadata.PairingMode ?? _metadata.PairingMode,
                    StagingLocation: metadata.StagingLocation ?? _metadata.StagingLocation,
                    DestinationLocation: metadata.DestinationLocation ?? _metadata.DestinationLocation,
                    SameVolumeStagingEligible: metadata.SameVolumeStagingEligible ?? _metadata.SameVolumeStagingEligible,
                    CommitStrategy: metadata.CommitStrategy ?? _metadata.CommitStrategy,
                    LocalAddress: metadata.LocalAddress ?? _metadata.LocalAddress,
                    PeerAddress: metadata.PeerAddress ?? _metadata.PeerAddress,
                    ItemCount: metadata.ItemCount ?? _metadata.ItemCount,
                    DatasetId: metadata.DatasetId ?? _metadata.DatasetId,
                    ContentProfile: metadata.ContentProfile ?? _metadata.ContentProfile,
                    DatasetSha256: metadata.DatasetSha256 ?? _metadata.DatasetSha256,
                    ManifestSha256: metadata.ManifestSha256 ?? _metadata.ManifestSha256,
                    IoOptions: metadata.IoOptions ?? _metadata.IoOptions,
                    TransportName: metadata.TransportName ?? _metadata.TransportName);

                if (!_stagingFreeBytesBefore.IsAvailable && !string.IsNullOrWhiteSpace(_metadata.StagingLocation))
                {
                    _stagingFreeBytesBefore = R3PlatformCollector.InspectStoragePath(_metadata.StagingLocation).FreeBytes;
                }
                if (!_destinationFreeBytesBefore.IsAvailable && !string.IsNullOrWhiteSpace(_metadata.DestinationLocation))
                {
                    _destinationFreeBytesBefore = R3PlatformCollector.InspectStoragePath(_metadata.DestinationLocation).FreeBytes;
                }
            }
        }

        public void RecordDiscoveryDurationTicks(long stopwatchTicks)
        {
            if (stopwatchTicks < 0) return;
            lock (_sampleLock)
            {
                _discoveryTicks += stopwatchTicks;
                _hasDiscoveryTicks = true;
            }
        }

        public void RecordConnectionDurationTicks(long stopwatchTicks)
        {
            if (stopwatchTicks < 0) return;
            lock (_sampleLock)
            {
                _connectionTicks += stopwatchTicks;
                _hasConnectionTicks = true;
            }
        }

        public void RecordAuthenticationDurationTicks(long stopwatchTicks)
        {
            if (stopwatchTicks < 0) return;
            lock (_sampleLock)
            {
                _authenticationTicks += stopwatchTicks;
                _hasAuthenticationTicks = true;
            }
        }

        public void RecordFinalAcknowledgementDurationTicks(long stopwatchTicks)
        {
            if (stopwatchTicks < 0) return;
            lock (_sampleLock)
            {
                _finalAcknowledgementTicks += stopwatchTicks;
                _hasFinalAcknowledgementTicks = true;
            }
        }

        public void RecordCancellation(long requestedTimestamp, long uiUpdatedTimestamp, long resourcesReleasedTimestamp)
        {
            if (requestedTimestamp <= 0) return;
            lock (_sampleLock)
            {
                _cancellationRecorded = true;
                _cancelRequestedTimestamp = requestedTimestamp;
                _cancelUiUpdatedTimestamp = uiUpdatedTimestamp;
                _cancelResourcesReleasedTimestamp = resourcesReleasedTimestamp;
            }
        }

        public void OnPhaseChanged(DesktopTransferPhase phase)
        {
            long now = Stopwatch.GetTimestamp();
            lock (_sampleLock)
            {
                AccumulateCurrentPhaseLocked(now);
                _currentPhase = phase;
                _lastPhaseChangeTimestamp = now;

                if (phase == DesktopTransferPhase.Transferring && _payloadStartTimestamp == 0)
                {
                    _payloadStartTimestamp = now;
                    if (_consentCompletedTimestamp == 0)
                    {
                        _consentCompletedTimestamp = now;
                    }
                    if (_payloadProgressPoints.Count == 0)
                    {
                        _payloadProgressPoints.Add((0.0, 0L));
                    }
                }
                else if (_currentPhase != DesktopTransferPhase.Transferring && _payloadStartTimestamp != 0)
                {
                    _payloadEndTimestamp = now;
                    RecordPayloadPointLocked(now, Interlocked.Read(ref _payloadBytesTransferred));
                }
            }
        }

        public void OnConsentCompleted()
        {
            long now = Stopwatch.GetTimestamp();
            lock (_sampleLock)
            {
                if (_consentCompletedTimestamp == 0)
                {
                    _consentCompletedTimestamp = now;
                }
            }
        }

        private void RecordPayloadPointLocked(long now, long payloadBytes)
        {
            if (_payloadStartTimestamp == 0 || _payloadProgressPoints.Count >= 10_800) return;
            double sec = Math.Max(0.0, Stopwatch.GetElapsedTime(_payloadStartTimestamp, now).TotalSeconds);
            if (_payloadProgressPoints.Count > 0 && sec < _payloadProgressPoints[^1].ElapsedSec)
            {
                sec = _payloadProgressPoints[^1].ElapsedSec;
            }
            _payloadProgressPoints.Add((sec, Math.Max(0L, payloadBytes)));
        }

        private void AccumulateCurrentPhaseLocked(long now)
        {
            long delta = Math.Max(0, now - _lastPhaseChangeTimestamp);
            switch (_currentPhase)
            {
                case DesktopTransferPhase.Preparing:
                case DesktopTransferPhase.Queued:
                    _preparationTicks += delta;
                    break;
                case DesktopTransferPhase.AwaitingConsent:
                    _consentWaitTicks += delta;
                    break;
                case DesktopTransferPhase.Transferring:
                    _payloadTransferTicks += delta;
                    _payloadEndTimestamp = now;
                    break;
                case DesktopTransferPhase.Verifying:
                case DesktopTransferPhase.WaitingForConfirmation:
                    _verificationTicks += delta;
                    break;
                case DesktopTransferPhase.Saving:
                    _commitPhaseTicks += delta;
                    break;
            }
            _lastPhaseChangeTimestamp = now;
        }

        public void AddSourceRead(long bytes, long elapsedStopwatchTicks)
        {
            Interlocked.Add(ref _sourceReadTicks, elapsedStopwatchTicks);
        }

        public void AddHashCompute(long elapsedStopwatchTicks)
        {
            Interlocked.Add(ref _hashComputeTicks, elapsedStopwatchTicks);
        }

        public void AddTlsWrite(long payloadBytes, long elapsedStopwatchTicks)
        {
            if (payloadBytes > 0)
            {
                Interlocked.Add(ref _payloadBytesTransferred, payloadBytes);
            }
            Interlocked.Add(ref _tlsWriteTicks, elapsedStopwatchTicks);
        }

        public void AddTlsRead(long payloadBytes, long elapsedStopwatchTicks)
        {
            if (payloadBytes > 0)
            {
                Interlocked.Add(ref _payloadBytesTransferred, payloadBytes);
            }
            Interlocked.Add(ref _tlsReadTicks, elapsedStopwatchTicks);
        }

        public void AddStagingWrite(long bytes, long elapsedStopwatchTicks)
        {
            if (bytes > 0)
            {
                Interlocked.Add(ref _stagingBytesWritten, bytes);
            }
            Interlocked.Add(ref _stagingWriteTicks, elapsedStopwatchTicks);
        }

        public void AddVerificationRead(long bytes, long elapsedStopwatchTicks)
        {
            Interlocked.Add(ref _verificationReadTicks, elapsedStopwatchTicks);
        }

        public void AddCommit(long elapsedStopwatchTicks)
        {
            Interlocked.Add(ref _commitTicks, elapsedStopwatchTicks);
        }

        public void UpdateQueueOccupancy(
            int senderDepth,
            int senderMaxDepth,
            int receiverDepth,
            int receiverMaxDepth,
            int activePooledBuffers,
            long activePooledBytes)
        {
            Volatile.Write(ref _senderQueueDepth, senderDepth);
            UpdateMax(ref _senderQueueMaxDepth, senderMaxDepth);
            UpdateMax(ref _senderQueueMaxDepth, senderDepth);
            Volatile.Write(ref _receiverQueueDepth, receiverDepth);
            UpdateMax(ref _receiverQueueMaxDepth, receiverMaxDepth);
            UpdateMax(ref _receiverQueueMaxDepth, receiverDepth);
            Volatile.Write(ref _activePooledBuffers, activePooledBuffers);
            Interlocked.Exchange(ref _activePooledBytes, activePooledBytes);
        }

        private static void UpdateMax(ref int target, int candidate)
        {
            int current = Volatile.Read(ref target);
            while (candidate > current)
            {
                int prev = Interlocked.CompareExchange(ref target, candidate, current);
                if (prev == current) break;
                current = prev;
            }
        }

        public void OnItemVerified(long bytes)
        {
            Interlocked.Add(ref _verifiedBytes, bytes);
            Interlocked.Exchange(ref _verifiedCompletedTimestamp, Stopwatch.GetTimestamp());
        }

        private void EmitPeriodicSample()
        {
            if (Volatile.Read(ref _completed) != 0) return;
            try
            {
                DesktopTransferDiagnosticSample sample = CaptureSnapshot(isTerminal: false, terminalStatus: null);
                if (_sink.IsWriting)
                {
                    _sink.EnqueueLine(JsonSerializer.Serialize(sample));
                }
            }
            catch
            {
                // Never throw on background timer
            }
        }

        public DesktopTransferDiagnosticSample CaptureSnapshot(bool isTerminal = false, string? terminalStatus = null)
        {
            long now = Stopwatch.GetTimestamp();
            lock (_sampleLock)
            {
                AccumulateCurrentPhaseLocked(now);

                double windowSeconds = Math.Max(0.001, Stopwatch.GetElapsedTime(_previousSampleTimestamp, now).TotalSeconds);
                long payloadBytes = Interlocked.Read(ref _payloadBytesTransferred);
                long stagingBytes = Interlocked.Read(ref _stagingBytesWritten);
                long verifiedBytes = Interlocked.Read(ref _verifiedBytes);

                if (_currentPhase == DesktopTransferPhase.Transferring || (isTerminal && _payloadStartTimestamp != 0))
                {
                    RecordPayloadPointLocked(now, payloadBytes);
                }

                long deltaPayloadBytes = Math.Max(0, payloadBytes - _previousSamplePayloadBytes);
                double unsmoothedMBps = (deltaPayloadBytes / windowSeconds) / 1_000_000.0;

                double payloadTransferMs = TicksToMs(_payloadTransferTicks);
                double averagePayloadMBps = payloadTransferMs > 0.1
                    ? (payloadBytes / (payloadTransferMs / 1000.0)) / 1_000_000.0
                    : 0.0;

                long acceptStart = _consentCompletedTimestamp != 0
                    ? _consentCompletedTimestamp
                    : (_payloadStartTimestamp != 0 ? _payloadStartTimestamp : _startTimestamp);
                long verifiedEnd = _verifiedCompletedTimestamp != 0 ? _verifiedCompletedTimestamp : now;
                double acceptanceToVerifiedMs = Math.Max(0.0, Stopwatch.GetElapsedTime(acceptStart, verifiedEnd).TotalMilliseconds);
                long effectiveCompletedBytes = verifiedBytes > 0 ? verifiedBytes : payloadBytes;
                double acceptanceToVerifiedMBps = acceptanceToVerifiedMs > 0.1
                    ? (effectiveCompletedBytes / (acceptanceToVerifiedMs / 1000.0)) / 1_000_000.0
                    : 0.0;

                long allocatedNow = GC.GetTotalAllocatedBytes(precise: false);
                long deltaAllocated = Math.Max(0, allocatedNow - _previousAllocatedBytes);
                double allocatedMBPerSec = (deltaAllocated / windowSeconds) / 1_000_000.0;

                TimeSpan gcPauseNow = GC.GetTotalPauseDuration();
                double totalGcPauseMs = (gcPauseNow - _initialGcPause).TotalMilliseconds;
                double windowGcPauseSeconds = Math.Max(0.0, (gcPauseNow - _previousGcPause).TotalSeconds);
                double gcPausePercent = Math.Clamp((windowGcPauseSeconds / windowSeconds) * 100.0, 0.0, 100.0);
                if (gcPausePercent > _gcPausePercentPeak) _gcPausePercentPeak = gcPausePercent;

                MetricValue<double> cpuPercent = MetricValue<double>.Unavailable;
                if (_cpuAvailable)
                {
                    try
                    {
                        using var proc = Process.GetCurrentProcess();
                        TimeSpan cpuNow = proc.TotalProcessorTime;
                        double cpuDeltaSeconds = Math.Max(0.0, (cpuNow - _previousCpuTime).TotalSeconds);
                        double cpuVal = Math.Round((cpuDeltaSeconds / windowSeconds) * 100.0, 2);
                        cpuPercent = MetricValue<double>.Available(cpuVal);
                        if (!_hasCpuPeak || cpuVal > _cpuPercentPeak)
                        {
                            _cpuPercentPeak = cpuVal;
                            _hasCpuPeak = true;
                        }
                        _previousCpuTime = cpuNow;
                    }
                    catch
                    {
                        cpuPercent = MetricValue<double>.Unavailable;
                    }
                }

                double managedHeapMiB = Math.Round(GC.GetTotalMemory(forceFullCollection: false) / (1024.0 * 1024.0), 2);
                if (managedHeapMiB > _managedHeapPeakMiB) _managedHeapPeakMiB = managedHeapMiB;

                (MetricValue<double> nativeMemMiB, double workingSetMiB) = R3PlatformCollector.TryReadMemory();
                if (nativeMemMiB.IsAvailable && (!_hasNativePeak || nativeMemMiB.Value > _nativeMemoryPeakMiB))
                {
                    _nativeMemoryPeakMiB = nativeMemMiB.Value;
                    _hasNativePeak = true;
                }
                if (workingSetMiB > _workingSetPeakMiB) _workingSetPeakMiB = workingSetMiB;

                long pendingWorkItems = ThreadPool.PendingWorkItemCount;
                if (pendingWorkItems > _threadPoolPendingPeak) _threadPoolPendingPeak = pendingWorkItems;

                R3PlatformCollector.ProcessIoSnapshot currentIo = R3PlatformCollector.TryReadProcessIo();
                MetricValue<long> readDelta = currentIo.Available && _initialIo.Available
                    ? MetricValue<long>.Available(Math.Max(0L, currentIo.ReadBytes - _initialIo.ReadBytes))
                    : MetricValue<long>.Unavailable;
                MetricValue<long> writeDelta = currentIo.Available && _initialIo.Available
                    ? MetricValue<long>.Available(Math.Max(0L, currentIo.WriteBytes - _initialIo.WriteBytes))
                    : MetricValue<long>.Unavailable;

                double savingMs = Math.Round(Math.Max(TicksToMs(_commitPhaseTicks), TicksToMs(Interlocked.Read(ref _commitTicks))), 2);

                MetricValue<double> fiveSecondWindowRate = MetricValue<double>.Unavailable;
                if (_payloadProgressPoints.Count >= 2)
                {
                    double pNow = _payloadProgressPoints[^1].ElapsedSec;
                    if (pNow >= 5.0)
                    {
                        long b5Ago = InterpolateBytesAt(_payloadProgressPoints, pNow - 5.0);
                        long bNow = _payloadProgressPoints[^1].Bytes;
                        fiveSecondWindowRate = MetricValue<double>.Available(Math.Round((Math.Max(0L, bNow - b5Ago) / 5.0) / 1_000_000.0, 2));
                    }
                }

                _previousSampleTimestamp = now;
                _previousSamplePayloadBytes = payloadBytes;
                _previousAllocatedBytes = allocatedNow;
                _previousGcPause = gcPauseNow;

                var sample = new DesktopTransferDiagnosticSample(
                    TimestampUtc: DateTimeOffset.UtcNow.ToString("O"),
                    AttemptId: AttemptId,
                    BuildCommit: BuildCommit,
                    Direction: _direction.ToString(),
                    Phase: _currentPhase.ToString(),
                    ElapsedSeconds: Math.Round(Stopwatch.GetElapsedTime(_startTimestamp, now).TotalSeconds, 3),
                    PreparationMs: Math.Round(TicksToMs(_preparationTicks), 2),
                    ConsentWaitMs: Math.Round(TicksToMs(_consentWaitTicks), 2),
                    PayloadTransferMs: Math.Round(payloadTransferMs, 2),
                    VerificationMs: Math.Round(TicksToMs(_verificationTicks), 2),
                    CommitMs: savingMs,
                    AcceptanceToVerifiedMs: Math.Round(acceptanceToVerifiedMs, 2),
                    DeclaredTotalBytes: _declaredTotalBytes,
                    PayloadBytesTransferred: payloadBytes,
                    StagingBytesWritten: stagingBytes,
                    VerifiedBytes: verifiedBytes,
                    UnsmoothedPayloadRateMBps: Math.Round(unsmoothedMBps, 2),
                    AveragePayloadRateMBps: Math.Round(averagePayloadMBps, 2),
                    AcceptanceToVerifiedRateMBps: Math.Round(acceptanceToVerifiedMBps, 2),
                    SourceReadMs: Math.Round(TicksToMs(Interlocked.Read(ref _sourceReadTicks)), 2),
                    HashComputeMs: Math.Round(TicksToMs(Interlocked.Read(ref _hashComputeTicks)), 2),
                    TlsWriteMs: Math.Round(TicksToMs(Interlocked.Read(ref _tlsWriteTicks)), 2),
                    TlsReadMs: Math.Round(TicksToMs(Interlocked.Read(ref _tlsReadTicks)), 2),
                    StagingWriteMs: Math.Round(TicksToMs(Interlocked.Read(ref _stagingWriteTicks)), 2),
                    VerificationReadMs: Math.Round(TicksToMs(Interlocked.Read(ref _verificationReadTicks)), 2),
                    SenderQueueDepth: Volatile.Read(ref _senderQueueDepth),
                    SenderQueueMaxDepth: Volatile.Read(ref _senderQueueMaxDepth),
                    ReceiverQueueDepth: Volatile.Read(ref _receiverQueueDepth),
                    ReceiverQueueMaxDepth: Volatile.Read(ref _receiverQueueMaxDepth),
                    ActivePooledBuffers: Volatile.Read(ref _activePooledBuffers),
                    ActivePooledBytes: Interlocked.Read(ref _activePooledBytes),
                    CpuPercent: cpuPercent,
                    ManagedAllocatedMBPerSec: Math.Round(allocatedMBPerSec, 2),
                    ManagedHeapMiB: managedHeapMiB,
                    WorkingSetMiB: workingSetMiB,
                    GcPauseMs: Math.Round(totalGcPauseMs, 2),
                    GcPausePercent: Math.Round(gcPausePercent, 2),
                    Gen0Collections: GC.CollectionCount(0) - _initialGen0,
                    Gen1Collections: GC.CollectionCount(1) - _initialGen1,
                    Gen2Collections: GC.CollectionCount(2) - _initialGen2,
                    ThreadPoolPendingWorkItems: pendingWorkItems,
                    IsTerminal: isTerminal,
                    TerminalStatus: terminalStatus,
                    SchemaVersion: "glide-r3-sample-v1",
                    DiscoveryMs: _hasDiscoveryTicks ? MetricValue<double>.Available(Math.Round(TicksToMs(_discoveryTicks), 2)) : MetricValue<double>.Unavailable,
                    ConnectionMs: _hasConnectionTicks ? MetricValue<double>.Available(Math.Round(TicksToMs(_connectionTicks), 2)) : MetricValue<double>.Unavailable,
                    AuthenticationMs: _hasAuthenticationTicks ? MetricValue<double>.Available(Math.Round(TicksToMs(_authenticationTicks), 2)) : MetricValue<double>.Unavailable,
                    FinalAcknowledgementMs: _hasFinalAcknowledgementTicks ? MetricValue<double>.Available(Math.Round(TicksToMs(_finalAcknowledgementTicks), 2)) : MetricValue<double>.Unavailable,
                    SavingMs: savingMs,
                    FiveSecondWindowRateMBps: fiveSecondWindowRate,
                    NativeMemoryMiB: nativeMemMiB,
                    ProcessReadBytes: readDelta,
                    ProcessWriteBytes: writeDelta,
                    ThermalStatus: MetricValue<string>.Unavailable,
                    BatteryLevelPercent: MetricValue<int>.Unavailable);

                if (_samples.Count < 10_800)
                {
                    _samples.Add(sample);
                }

                return sample;
            }
        }

        public DesktopTransferDiagnosticSample Complete(DesktopTransferResult result, string? terminalOverride = null)
        {
            _sampleTimer.Change(Timeout.Infinite, Timeout.Infinite);
            Interlocked.Exchange(ref _completed, 1);

            DesktopTransferPhase terminalPhase = result.Success
                ? DesktopTransferPhase.Completed
                : result.Cancelled
                    ? DesktopTransferPhase.Cancelled
                    : DesktopTransferPhase.Failed;
            OnPhaseChanged(terminalPhase);

            string status = terminalOverride
                ?? (result.Success
                    ? "Completed"
                    : result.Cancelled
                        ? "Cancelled"
                        : result.Rejected
                            ? "Rejected"
                            : "Failed");

            DesktopTransferDiagnosticSample terminalSample = CaptureSnapshot(isTerminal: true, terminalStatus: status);
            if (_sink.IsWriting)
            {
                _sink.EnqueueLine(JsonSerializer.Serialize(terminalSample));
            }
            return terminalSample;
        }

        public R3DiagnosticReport BuildR3Report(DesktopTransferResult result, DesktopR3ReportOptions? options = null)
        {
            long endTimestamp = Stopwatch.GetTimestamp();
            R3PlatformCollector.CachedHostSnapshot host = R3PlatformCollector.GetHostSnapshot();

            lock (_sampleLock)
            {
                DesktopTransferDiagnosticSample last = _samples.Count > 0
                    ? _samples[^1]
                    : CaptureSnapshot(isTerminal: true);

                double totalWallMs = Math.Round(Math.Max(0.0, Stopwatch.GetElapsedTime(_startTimestamp, endTimestamp).TotalMilliseconds), 2);
                double wallSeconds = Math.Max(0.001, totalWallMs / 1000.0);

                // Compute 1s rates from payload samples
                var oneSecondRates = new List<double>();
                for (int i = 1; i < _payloadProgressPoints.Count; i++)
                {
                    double dt = _payloadProgressPoints[i].ElapsedSec - _payloadProgressPoints[i - 1].ElapsedSec;
                    long db = Math.Max(0L, _payloadProgressPoints[i].Bytes - _payloadProgressPoints[i - 1].Bytes);
                    if (dt >= 0.25)
                    {
                        oneSecondRates.Add(Math.Round((db / dt) / 1_000_000.0, 2));
                    }
                }
                if (oneSecondRates.Count == 0 && last.PayloadBytesTransferred > 0 && last.PayloadTransferMs > 0.1)
                {
                    oneSecondRates.Add(last.AveragePayloadRateMBps);
                }

                IReadOnlyList<R3FiveSecondRateWindow> fiveSecWindows = ComputeFiveSecondRateWindows(_payloadProgressPoints);
                (MetricValue<double> q1Rate, MetricValue<double> q4Rate, MetricValue<double> qRatio) =
                    ComputeSteadyQuarterRates(_payloadProgressPoints, Math.Max(_declaredTotalBytes, last.PayloadBytesTransferred));

                // Resources & CPU average
                long allocTotalBytes = Math.Max(0L, GC.GetTotalAllocatedBytes(precise: false) - _initialAllocatedBytes);
                double allocTotalMB = Math.Round(allocTotalBytes / 1_000_000.0, 2);
                double allocAvgRate = Math.Round((allocTotalBytes / wallSeconds) / 1_000_000.0, 2);

                MetricValue<double> cpuAvg = MetricValue<double>.Unavailable;
                if (_cpuAvailable)
                {
                    try
                    {
                        using var proc = Process.GetCurrentProcess();
                        double totalCpuSec = Math.Max(0.0, (proc.TotalProcessorTime - _initialCpuTime).TotalSeconds);
                        cpuAvg = MetricValue<double>.Available(Math.Round((totalCpuSec / wallSeconds) * 100.0, 2));
                    }
                    catch { }
                }

                // Process I/O
                R3PlatformCollector.ProcessIoSnapshot endIo = R3PlatformCollector.TryReadProcessIo();
                MetricValue<long> procReadIo = endIo.Available && _initialIo.Available
                    ? MetricValue<long>.Available(Math.Max(0L, endIo.ReadBytes - _initialIo.ReadBytes))
                    : MetricValue<long>.Unavailable;
                MetricValue<long> procWriteIo = endIo.Available && _initialIo.Available
                    ? MetricValue<long>.Available(Math.Max(0L, endIo.WriteBytes - _initialIo.WriteBytes))
                    : MetricValue<long>.Unavailable;
                MetricValue<double> procReadRate = procReadIo.IsAvailable
                    ? MetricValue<double>.Available(Math.Round((procReadIo.Value / wallSeconds) / 1_000_000.0, 2))
                    : MetricValue<double>.Unavailable;
                MetricValue<double> procWriteRate = procWriteIo.IsAvailable
                    ? MetricValue<double>.Available(Math.Round((procWriteIo.Value / wallSeconds) / 1_000_000.0, 2))
                    : MetricValue<double>.Unavailable;

                // Network delta
                R3PlatformCollector.NetworkSnapshot endNet = R3PlatformCollector.TryCaptureNetworkSnapshot(_metadata.LocalAddress);
                MetricValue<long> retransDelta = endNet.TcpRetransmits.IsAvailable && _initialNet.TcpRetransmits.IsAvailable
                    ? MetricValue<long>.Available(Math.Max(0L, endNet.TcpRetransmits.Value - _initialNet.TcpRetransmits.Value))
                    : MetricValue<long>.Unavailable;
                MetricValue<long> outErrDelta = endNet.OutboundErrors.IsAvailable && _initialNet.OutboundErrors.IsAvailable
                    ? MetricValue<long>.Available(Math.Max(0L, endNet.OutboundErrors.Value - _initialNet.OutboundErrors.Value))
                    : MetricValue<long>.Unavailable;
                MetricValue<long> inErrDelta = endNet.ReceivedErrors.IsAvailable && _initialNet.ReceivedErrors.IsAvailable
                    ? MetricValue<long>.Available(Math.Max(0L, endNet.ReceivedErrors.Value - _initialNet.ReceivedErrors.Value))
                    : MetricValue<long>.Unavailable;

                string localAddrClass = R3PlatformCollector.ClassifyIpAddress(_metadata.LocalAddress);
                string peerAddrClass = R3PlatformCollector.ClassifyIpAddress(_metadata.PeerAddress);
                string execEnv = options?.ExecutionEnvironmentOverride
                    ?? (string.Equals(localAddrClass, "loopback", StringComparison.Ordinal) &&
                        string.Equals(peerAddrClass, "loopback", StringComparison.Ordinal)
                        ? "loopback"
                        : host.PlatformBase.ExecutionEnvironment);

                string transport = _metadata.TransportName
                    ?? (string.Equals(execEnv, "loopback", StringComparison.Ordinal) ? "loopback" : "lan-tls13");

                var stagingInfo = R3PlatformCollector.InspectStoragePath(_metadata.StagingLocation);
                var destInfo = R3PlatformCollector.InspectStoragePath(_metadata.DestinationLocation);
                MetricValue<bool> sameVol = _metadata.SameVolumeStagingEligible.HasValue
                    ? MetricValue<bool>.Available(_metadata.SameVolumeStagingEligible.Value)
                    : (stagingInfo.VolumeOrMount.IsAvailable && destInfo.VolumeOrMount.IsAvailable
                        ? MetricValue<bool>.Available(string.Equals(stagingInfo.VolumeOrMount.Value, destInfo.VolumeOrMount.Value, StringComparison.OrdinalIgnoreCase))
                        : MetricValue<bool>.Unavailable);

                bool discardStorage = _metadata.IoOptions?.DiscardStorageForBenchmark ?? false;
                string commitStrategy = _metadata.CommitStrategy
                    ?? (discardStorage ? "DiscardBenchmark" : "unavailable");

                DesktopTransferIoOptions io = _metadata.IoOptions ?? DesktopTransferIoOptions.Default;

                string outcomeStatus = result.Success
                    ? "Completed"
                    : result.Cancelled
                        ? "Cancelled"
                        : result.Rejected
                            ? "Rejected"
                            : "Failed";

                bool shaVerified = options?.Sha256VerifiedOverride ?? result.Success;

                MetricValue<double> uiCancelMs = options?.UiCancelLatencyMsOverride.HasValue == true
                    ? MetricValue<double>.Available(Math.Round(options.UiCancelLatencyMsOverride.Value, 2))
                    : (_cancellationRecorded && _cancelUiUpdatedTimestamp >= _cancelRequestedTimestamp
                        ? MetricValue<double>.Available(Math.Round(Stopwatch.GetElapsedTime(_cancelRequestedTimestamp, _cancelUiUpdatedTimestamp).TotalMilliseconds, 2))
                        : MetricValue<double>.Unavailable);
                MetricValue<double> releaseMs = options?.ResourceReleaseLatencyMsOverride.HasValue == true
                    ? MetricValue<double>.Available(Math.Round(options.ResourceReleaseLatencyMsOverride.Value, 2))
                    : (_cancellationRecorded && _cancelResourcesReleasedTimestamp >= _cancelRequestedTimestamp
                        ? MetricValue<double>.Available(Math.Round(Stopwatch.GetElapsedTime(_cancelRequestedTimestamp, _cancelResourcesReleasedTimestamp).TotalMilliseconds, 2))
                        : MetricValue<double>.Unavailable);

                R3LinuxConditionsInfo linuxConditions = OperatingSystem.IsLinux()
                    ? host.LinuxBase with
                    {
                        StagingMountOptions = R3PlatformCollector.TryReadLinuxMountOptions(_metadata.StagingLocation),
                        DestinationMountOptions = R3PlatformCollector.TryReadLinuxMountOptions(_metadata.DestinationLocation)
                    }
                    : new R3LinuxConditionsInfo(
                        MetricValue<string>.Unavailable,
                        MetricValue<string>.Unavailable,
                        MetricValue<string>.Unavailable,
                        MetricValue<long>.Unavailable,
                        MetricValue<string>.Unavailable,
                        MetricValue<string>.Unavailable,
                        MetricValue<string>.Unavailable);

                return new R3DiagnosticReport(
                    SchemaVersion: R3DiagnosticReport.CurrentSchemaVersion,
                    AttemptId: AttemptId,
                    TimestampUtc: DateTimeOffset.UtcNow.ToString("O"),
                    SourceCommit: string.IsNullOrWhiteSpace(BuildCommit) || BuildCommit == "1.0.0"
                        ? host.SourceCommit
                        : BuildCommit,
                    ArtifactSha256: host.ArtifactSha256,
                    AppVersion: host.AppVersion,
                    Platform: host.PlatformBase with { ExecutionEnvironment = execEnv },
                    Hardware: host.Hardware,
                    Runtime: host.Runtime,
                    NetworkLink: new R3NetworkLinkInfo(
                        Transport: transport,
                        InterfaceName: MetricValue<string>.Available(endNet.InterfaceName),
                        LinkType: MetricValue<string>.Available(endNet.LinkType),
                        NegotiatedLinkMbps: endNet.NegotiatedLinkMbps,
                        MtuBytes: endNet.MtuBytes,
                        WifiRssiDbm: MetricValue<int>.Unavailable,
                        WifiFrequencyMhz: MetricValue<int>.Unavailable,
                        LocalAddressClass: MetricValue<string>.Available(localAddrClass),
                        PeerAddressClass: MetricValue<string>.Available(peerAddrClass),
                        TcpRetransmitsDelta: retransDelta,
                        OutboundPacketErrorsDelta: outErrDelta,
                        ReceivedPacketErrorsDelta: inErrDelta),
                    Storage: new R3StorageInfo(
                        StagingLocation: string.IsNullOrWhiteSpace(_metadata.StagingLocation)
                            ? MetricValue<string>.Unavailable
                            : MetricValue<string>.Available(SanitizePathForDiagnostics(_metadata.StagingLocation)),
                        StagingVolumeOrMount: stagingInfo.VolumeOrMount,
                        StagingFilesystem: stagingInfo.Filesystem,
                        StagingStorageType: stagingInfo.StorageType,
                        StagingFreeBytesBefore: _stagingFreeBytesBefore.IsAvailable ? _stagingFreeBytesBefore : stagingInfo.FreeBytes,
                        DestinationLocation: string.IsNullOrWhiteSpace(_metadata.DestinationLocation)
                            ? MetricValue<string>.Unavailable
                            : MetricValue<string>.Available(SanitizePathForDiagnostics(_metadata.DestinationLocation)),
                        DestinationVolumeOrMount: destInfo.VolumeOrMount,
                        DestinationFilesystem: destInfo.Filesystem,
                        DestinationStorageType: destInfo.StorageType,
                        DestinationFreeBytesBefore: _destinationFreeBytesBefore.IsAvailable ? _destinationFreeBytesBefore : destInfo.FreeBytes,
                        SameVolumeStagingEligible: sameVol,
                        CommitStrategy: MetricValue<string>.Available(commitStrategy)),
                    Dataset: new R3DatasetInfo(
                        DatasetId: SanitizeDiagnosticText(options?.DatasetIdOverride ?? _metadata.DatasetId ?? $"batch-{_metadata.ItemCount ?? 1}-items"),
                        ContentProfile: options?.ContentProfileOverride ?? _metadata.ContentProfile ?? "production-user",
                        ItemCount: _metadata.ItemCount ?? (result.VerifiedCount > 0 ? result.VerifiedCount : 1),
                        DeclaredTotalBytes: _declaredTotalBytes,
                        DatasetSha256: MetricValue<string>.Available(options?.DatasetSha256Override ?? _metadata.DatasetSha256 ?? "unavailable"),
                        ManifestSha256: MetricValue<string>.Available(options?.ManifestSha256Override ?? _metadata.ManifestSha256 ?? "unavailable")),
                    TestConfiguration: new R3TestConfigurationInfo(
                        Role: _direction == DesktopTransferDirection.Send ? "sender" : "receiver",
                        Direction: _direction.ToString(),
                        PairingMode: _metadata.PairingMode ?? "qr-v1",
                        BenchmarkPath: MetricValue<string>.Available(options?.BenchmarkPath ?? "unavailable"),
                        BenchmarkMode: options?.BenchmarkMode ?? "production-default",
                        RunType: options?.RunType ?? "interactive",
                        RepetitionIndex: Math.Max(1, options?.RepetitionIndex ?? 1),
                        RepetitionTotal: Math.Max(1, options?.RepetitionTotal ?? 1),
                        UiState: options?.UiState ?? "interactive",
                        PowerSource: MetricValue<string>.Unavailable,
                        IoOptions: new R3IoOptionsInfo(
                            ChunkBufferBytes: io.ChunkBufferBytes,
                            QueueBufferCount: io.QueueBufferCount,
                            MaxProcessTransferBufferBytes: io.MaxProcessTransferBufferBytes,
                            WriteCoalesceBufferBytes: io.WriteCoalesceBufferBytes,
                            MaxWriteBatchDelayMs: (int)Math.Round(io.MaxWriteBatchDelay.TotalMilliseconds),
                            EnableCoalescedFraming: io.EnableCoalescedFraming,
                            EnableBoundedPipeline: io.EnableBoundedPipeline,
                            DiscardStorageForBenchmark: io.DiscardStorageForBenchmark)),
                    Outcome: new R3OutcomeInfo(
                        Status: outcomeStatus,
                        Success: result.Success,
                        Rejected: result.Rejected,
                        Cancelled: result.Cancelled,
                        ErrorCategory: result.ErrorCategory.ToString(),
                        TerminalMessage: TruncateMessage(result.Message, 512),
                        VerifiedItemCount: result.VerifiedCount,
                        VerifiedBytes: result.VerifiedBytes,
                        Sha256Verified: shaVerified,
                        QuarantineCleanAfterTerminal: options?.QuarantineCleanAfterTerminalOverride.HasValue == true
                            ? MetricValue<bool>.Available(options.QuarantineCleanAfterTerminalOverride.Value)
                            : MetricValue<bool>.Unavailable),
                    PhaseDurationsMs: new R3PhaseDurationsInfo(
                        TotalWallMs: totalWallMs,
                        PreparationMs: last.PreparationMs,
                        DiscoveryMs: last.DiscoveryMs,
                        ConnectionMs: last.ConnectionMs,
                        AuthenticationMs: last.AuthenticationMs,
                        UserConsentMs: last.ConsentWaitMs,
                        PayloadTransferMs: last.PayloadTransferMs,
                        VerificationMs: last.VerificationMs,
                        SavingMs: last.SavingMs,
                        FinalAcknowledgementMs: last.FinalAcknowledgementMs,
                        AcceptanceToVerifiedMs: last.AcceptanceToVerifiedMs),
                    Bytes: new R3BytesInfo(
                        DeclaredTotalBytes: _declaredTotalBytes,
                        PayloadBytesTransferred: last.PayloadBytesTransferred,
                        StagingBytesWritten: last.StagingBytesWritten,
                        VerifiedBytes: last.VerifiedBytes),
                    Throughput: new R3ThroughputInfo(
                        AveragePayloadRateMBps: last.AveragePayloadRateMBps,
                        AcceptanceToVerifiedRateMBps: last.AcceptanceToVerifiedRateMBps,
                        OneSecondRatesMBps: oneSecondRates,
                        FiveSecondRateWindowsMBps: fiveSecWindows,
                        FirstSteadyQuarterRateMBps: q1Rate,
                        FinalSteadyQuarterRateMBps: q4Rate,
                        FinalToFirstQuarterRatio: qRatio),
                    PipelineTimingsMs: new R3PipelineTimingsInfo(
                        SourceReadMs: _direction == DesktopTransferDirection.Send
                            ? MetricValue<double>.Available(last.SourceReadMs)
                            : MetricValue<double>.Unavailable,
                        HashComputeMs: MetricValue<double>.Available(last.HashComputeMs),
                        TlsWriteMs: _direction == DesktopTransferDirection.Send
                            ? MetricValue<double>.Available(last.TlsWriteMs)
                            : MetricValue<double>.Unavailable,
                        TlsReadMs: _direction == DesktopTransferDirection.Receive
                            ? MetricValue<double>.Available(last.TlsReadMs)
                            : MetricValue<double>.Unavailable,
                        StagingWriteMs: _direction == DesktopTransferDirection.Receive
                            ? MetricValue<double>.Available(last.StagingWriteMs)
                            : MetricValue<double>.Unavailable,
                        VerificationReadMs: _direction == DesktopTransferDirection.Receive
                            ? MetricValue<double>.Available(last.VerificationReadMs)
                            : MetricValue<double>.Unavailable,
                        CommitMs: _direction == DesktopTransferDirection.Receive
                            ? MetricValue<double>.Available(last.CommitMs)
                            : MetricValue<double>.Unavailable),
                    Resources: new R3ResourcesInfo(
                        CpuPercentAvg: cpuAvg,
                        CpuPercentPeak: _hasCpuPeak ? MetricValue<double>.Available(_cpuPercentPeak) : cpuAvg,
                        ManagedHeapBaselineMiB: _managedHeapBaselineMiB,
                        ManagedHeapPeakMiB: _managedHeapPeakMiB,
                        ManagedHeapDeltaMiB: Math.Round(_managedHeapPeakMiB - _managedHeapBaselineMiB, 2),
                        ManagedAllocatedTotalMB: allocTotalMB,
                        ManagedAllocatedRateAvgMBPerSec: allocAvgRate,
                        NativeOrPrivateMemoryBaselineMiB: _nativeMemoryBaselineMiB,
                        NativeOrPrivateMemoryPeakMiB: _hasNativePeak ? MetricValue<double>.Available(_nativeMemoryPeakMiB) : _nativeMemoryBaselineMiB,
                        WorkingSetPeakMiB: _workingSetPeakMiB,
                        GcPauseTotalMs: last.GcPauseMs,
                        GcPausePercentPeak: Math.Round(_gcPausePercentPeak, 2),
                        Gen0Collections: last.Gen0Collections,
                        Gen1Collections: last.Gen1Collections,
                        Gen2Collections: last.Gen2Collections,
                        ThreadPoolPendingWorkItemsPeak: _threadPoolPendingPeak),
                    QueueOccupancy: new R3QueueOccupancyInfo(
                        SenderQueueDepthMax: last.SenderQueueMaxDepth,
                        ReceiverQueueDepthMax: last.ReceiverQueueMaxDepth,
                        ActivePooledBuffersPeak: last.ActivePooledBuffers,
                        ActivePooledBytesPeak: last.ActivePooledBytes),
                    DiskActivity: new R3DiskActivityInfo(
                        ProcessReadIoBytes: procReadIo,
                        ProcessWriteIoBytes: procWriteIo,
                        ProcessReadMBpsAvg: procReadRate,
                        ProcessWriteMBpsAvg: procWriteRate,
                        VolumeReadLatencyMsAvg: MetricValue<double>.Unavailable,
                        VolumeWriteLatencyMsAvg: MetricValue<double>.Unavailable),
                    Cancellation: new R3CancellationInfo(
                        CancellationRequested: result.Cancelled || _cancellationRecorded,
                        UiCancelLatencyMs: uiCancelMs,
                        ResourceReleaseLatencyMs: releaseMs),
                    AndroidConditions: new R3AndroidConditionsInfo(
                        ThermalStatusStart: MetricValue<string>.Unavailable,
                        ThermalStatusPeak: MetricValue<string>.Unavailable,
                        ThermalStatusEnd: MetricValue<string>.Unavailable,
                        ThermalHeadroom: MetricValue<double>.Unavailable,
                        BatteryLevelPercentStart: MetricValue<int>.Unavailable,
                        BatteryLevelPercentEnd: MetricValue<int>.Unavailable,
                        IsCharging: MetricValue<bool>.Unavailable,
                        BatterySaverEnabled: MetricValue<bool>.Unavailable,
                        BatteryTemperatureCelsiusStart: MetricValue<double>.Unavailable,
                        BatteryTemperatureCelsiusEnd: MetricValue<double>.Unavailable),
                    LinuxConditions: linuxConditions);
            }
        }

        public IReadOnlyList<DesktopTransferDiagnosticSample> GetRecordedSamples()
        {
            lock (_sampleLock)
            {
                return _samples.ToList();
            }
        }

        private static double TicksToMs(long stopwatchTicks) =>
            (stopwatchTicks * 1000.0) / Stopwatch.Frequency;

        public void Dispose()
        {
            _sampleTimer.Dispose();
        }

        public ValueTask DisposeAsync()
        {
            _sampleTimer.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
