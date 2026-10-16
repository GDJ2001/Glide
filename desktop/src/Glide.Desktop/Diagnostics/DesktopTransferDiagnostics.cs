using System.Diagnostics;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Channels;

namespace Glide.Desktop.Diagnostics;

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
    [property: JsonPropertyName("cpuPercent")] double CpuPercent,
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
    [property: JsonPropertyName("terminalStatus")] string? TerminalStatus = null);

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
    private const int BoundedQueueCapacity = 256;

    private readonly string? _logFilePath;
    private readonly string _buildCommit;
    private readonly Channel<string>? _writeChannel;
    private readonly Task? _writerTask;
    private readonly CancellationTokenSource _disposeCts = new();
    private bool _disposed;

    public BoundedTransferDiagnosticSink(string? logFilePath = null, string? buildCommit = null)
    {
        _logFilePath = string.IsNullOrWhiteSpace(logFilePath)
            ? Environment.GetEnvironmentVariable(DiagnosticLogPathEnvVar)
            : logFilePath;
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
            _writerTask = Task.Run(() => RunFileWriterAsync(fullPath, _writeChannel.Reader));
        }
    }

    public static IDesktopTransferDiagnostics CreateDefault() =>
        new BoundedTransferDiagnosticSink();

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

    internal bool IsWriting => !_disposed && _writeChannel != null;

    internal void EnqueueLine(string jsonLine)
    {
        if (_disposed || _writeChannel == null) return;
        _writeChannel.Writer.TryWrite(jsonLine);
    }

    private static async Task RunFileWriterAsync(string filePath, ChannelReader<string> reader)
    {
        try
        {
            await using var stream = new FileStream(
                filePath,
                FileMode.Append,
                FileAccess.Write,
                FileShare.ReadWrite,
                bufferSize: 16_384,
                useAsync: true);
            await using var writer = new StreamWriter(stream);

            await foreach (string line in reader.ReadAllAsync().ConfigureAwait(false))
            {
                await writer.WriteLineAsync(line).ConfigureAwait(false);
                await writer.FlushAsync().ConfigureAwait(false);
            }
        }
        catch
        {
            // Diagnostic logging must never crash the host process.
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

    private sealed class AttemptDiagnostics : IDesktopTransferAttemptDiagnostics
    {
        private readonly BoundedTransferDiagnosticSink _sink;
        private readonly DesktopTransferDirection _direction;
        private long _declaredTotalBytes;
        private readonly long _startTimestamp;
        private readonly List<DesktopTransferDiagnosticSample> _samples = new(128);
        private readonly object _sampleLock = new();
        private readonly Timer _sampleTimer;

        private DesktopTransferPhase _currentPhase = DesktopTransferPhase.Preparing;
        private long _lastPhaseChangeTimestamp;
        private long _consentCompletedTimestamp;
        private long _payloadStartTimestamp;
        private long _payloadEndTimestamp;
        private long _verifiedCompletedTimestamp;

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
        private TimeSpan _previousGcPause;
        private readonly TimeSpan _initialGcPause;
        private readonly int _initialGen0;
        private readonly int _initialGen1;
        private readonly int _initialGen2;
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
            _previousAllocatedBytes = GC.GetTotalAllocatedBytes(precise: false);
            _initialGcPause = GC.GetTotalPauseDuration();
            _previousGcPause = _initialGcPause;
            _initialGen0 = GC.CollectionCount(0);
            _initialGen1 = GC.CollectionCount(1);
            _initialGen2 = GC.CollectionCount(2);

            try
            {
                using var proc = Process.GetCurrentProcess();
                _previousCpuTime = proc.TotalProcessorTime;
            }
            catch
            {
                _previousCpuTime = TimeSpan.Zero;
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
                }
                else if (_currentPhase != DesktopTransferPhase.Transferring && _payloadStartTimestamp != 0)
                {
                    _payloadEndTimestamp = now;
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

                double cpuPercent = 0.0;
                try
                {
                    using var proc = Process.GetCurrentProcess();
                    TimeSpan cpuNow = proc.TotalProcessorTime;
                    double cpuDeltaSeconds = Math.Max(0.0, (cpuNow - _previousCpuTime).TotalSeconds);
                    cpuPercent = Math.Round((cpuDeltaSeconds / windowSeconds) * 100.0, 2);
                    _previousCpuTime = cpuNow;
                }
                catch
                {
                    cpuPercent = -1.0;
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
                    CommitMs: Math.Round(Math.Max(TicksToMs(_commitPhaseTicks), TicksToMs(Interlocked.Read(ref _commitTicks))), 2),
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
                    ManagedHeapMiB: Math.Round(GC.GetTotalMemory(forceFullCollection: false) / (1024.0 * 1024.0), 2),
                    WorkingSetMiB: Math.Round(Environment.WorkingSet / (1024.0 * 1024.0), 2),
                    GcPauseMs: Math.Round(totalGcPauseMs, 2),
                    GcPausePercent: Math.Round(gcPausePercent, 2),
                    Gen0Collections: GC.CollectionCount(0) - _initialGen0,
                    Gen1Collections: GC.CollectionCount(1) - _initialGen1,
                    Gen2Collections: GC.CollectionCount(2) - _initialGen2,
                    ThreadPoolPendingWorkItems: ThreadPool.PendingWorkItemCount,
                    IsTerminal: isTerminal,
                    TerminalStatus: terminalStatus);

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
