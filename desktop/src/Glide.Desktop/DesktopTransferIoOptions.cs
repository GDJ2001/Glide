namespace Glide.Desktop;

/// <summary>
/// Bounded I/O and pipeline configuration for <see cref="DesktopTransferEngine"/>.
/// Defaults implement the 1 Gbps streaming contract: 16 buffers of ~64 KiB per active queue,
/// 8 MiB process-wide application buffer cap, and 256 KiB / 5 ms write coalescing.
/// </summary>
public sealed record DesktopTransferIoOptions
{
    public const int DefaultChunkBufferBytes = 65_520;
    public const int DefaultQueueBufferCount = 16;
    public const long DefaultMaxProcessTransferBufferBytes = 8L * 1024 * 1024; // 8 MiB
    public const int DefaultWriteCoalesceBufferBytes = 256 * 1024; // 256 KiB
    public static readonly TimeSpan DefaultMaxWriteBatchDelay = TimeSpan.FromMilliseconds(5);

    public static DesktopTransferIoOptions Default { get; } = new();

    /// <summary>
    /// Legacy pre-optimization sequential pipeline configuration used for baseline A/B benchmarking.
    /// </summary>
    public static DesktopTransferIoOptions LegacyBaseline { get; } = new()
    {
        EnableCoalescedFraming = false,
        EnableBoundedPipeline = false
    };

    public int ChunkBufferBytes { get; init; } = DefaultChunkBufferBytes;

    public int QueueBufferCount { get; init; } = DefaultQueueBufferCount;

    public long MaxProcessTransferBufferBytes { get; init; } = DefaultMaxProcessTransferBufferBytes;

    public int WriteCoalesceBufferBytes { get; init; } = DefaultWriteCoalesceBufferBytes;

    public TimeSpan MaxWriteBatchDelay { get; init; } = DefaultMaxWriteBatchDelay;

    public bool EnableCoalescedFraming { get; init; } = true;

    public bool EnableBoundedPipeline { get; init; } = true;

    /// <summary>
    /// Benchmark-only mode (Path 2): executes full TLS 1.3 + LAN Record framing + control protocol
    /// while bypassing receiver disk writes and file commit. Never enabled in production.
    /// </summary>
    public bool DiscardStorageForBenchmark { get; init; } = false;
}
