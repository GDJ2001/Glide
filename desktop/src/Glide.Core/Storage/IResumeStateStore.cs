using Glide.Core.Models;

namespace Glide.Core.Storage;

public sealed record ResumableTransferState(
    SessionId SessionId,
    ItemId ItemId,
    long VerifiedContiguousBytes,
    int ChunkSizeBytes,
    string MerkleRootHex,
    DateTimeOffset UpdatedAtUtc);

/// <summary>
/// Port interface for chunk-verified resumable transfer state.
/// Isolates resume persistence from <see cref="QuarantineStagingStore"/> and the transfer engine.
/// </summary>
public interface IResumeStateStore
{
    bool TryGetResumableTransfer(
        SessionId sessionId,
        ItemId itemId,
        out ResumableTransferState? state);

    ValueTask RecordVerifiedChunkAsync(
        SessionId sessionId,
        ItemId itemId,
        int chunkIndex,
        ReadOnlyMemory<byte> chunkSha256,
        long verifiedContiguousBytes,
        CancellationToken cancellationToken = default);

    ValueTask PurgeSessionAsync(
        SessionId sessionId,
        CancellationToken cancellationToken = default);

    int PurgeExpired();
}

public sealed class NullResumeStateStore : IResumeStateStore
{
    public static NullResumeStateStore Instance { get; } = new();

    public bool TryGetResumableTransfer(
        SessionId sessionId,
        ItemId itemId,
        out ResumableTransferState? state)
    {
        state = null;
        return false;
    }

    public ValueTask RecordVerifiedChunkAsync(
        SessionId sessionId,
        ItemId itemId,
        int chunkIndex,
        ReadOnlyMemory<byte> chunkSha256,
        long verifiedContiguousBytes,
        CancellationToken cancellationToken = default) =>
        ValueTask.CompletedTask;

    public ValueTask PurgeSessionAsync(
        SessionId sessionId,
        CancellationToken cancellationToken = default) =>
        ValueTask.CompletedTask;

    public int PurgeExpired() => 0;
}
