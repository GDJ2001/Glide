using System.Text.Json;
using Glide.Core.Models;

namespace Glide.Core.Storage;

public sealed record DesktopAcceptedResumeBatch(
    ResumeBatchManifest Manifest,
    string ManifestDigest,
    string Role,
    string PeerFingerprintHex,
    int VerifiedCount,
    long ExpiresAtUnixMs,
    string? PendingCommitPath = null);

public sealed partial class DesktopResumeStore
{
    private const int MaxBatchJournalBytes = 16 * 1024 * 1024;
    private string BatchesRoot => Path.Combine(_root, "batches");

    public async Task<IReadOnlyList<DesktopAcceptedResumeBatch>> ListAcceptedBatchesAsync(
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            long now = _nowUnixMs();
            var active = new List<DesktopAcceptedResumeBatch>();
            if (!Directory.Exists(BatchesRoot) || (File.GetAttributes(BatchesRoot) & FileAttributes.ReparsePoint) != 0)
                return active;
            foreach (string path in Directory.EnumerateFiles(BatchesRoot, "*.json"))
            {
                cancellationToken.ThrowIfCancellationRequested();
                string name = Path.GetFileNameWithoutExtension(path);
                if (!IdentifierValidation.IsValidUuidV4(name))
                    continue;
                try
                {
                    var batch = ReadAcceptedBatchUnsafe(new SessionId(name));
                    if (batch != null && batch.ExpiresAtUnixMs > now && batch.VerifiedCount < batch.Manifest.Items.Count)
                        active.Add(batch);
                }
                catch (Exception error) when (error is IOException or JsonException or ArgumentException) { }
            }
            return active.OrderByDescending(batch => batch.ExpiresAtUnixMs).ToArray();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SaveAcceptedBatchAsync(
        ResumeBatchManifest manifest, string role, CancellationToken cancellationToken = default)
    {
        string digest = manifest.Digest();
        string peer = BatchPeerFingerprint(manifest, role);
        var sessionId = new SessionId(manifest.SessionId);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            long now = _nowUnixMs();
            var existing = ReadAcceptedBatchUnsafe(sessionId);
            if (existing is not null)
            {
                if (existing.Role != role || existing.ManifestDigest != digest || existing.ExpiresAtUnixMs <= now)
                    throw new InvalidDataException("Conflicting accepted resume batch.");
                return;
            }

            if (OperatingSystem.IsWindows())
                Directory.CreateDirectory(BatchesRoot);
            else
                Directory.CreateDirectory(BatchesRoot,
                    UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);

            if ((File.GetAttributes(BatchesRoot) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Resume batch directory is a link.");
            PersistJsonUnsafe(BatchJournalPath(sessionId),
                new DesktopAcceptedResumeBatch(manifest, digest, role, peer, 0, checked(now + MaxTtlMilliseconds)));
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<DesktopAcceptedResumeBatch?> FindAcceptedBatchAsync(
        SessionId sessionId, string role, string peerFingerprintHex, string manifestDigest,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var batch = ReadAcceptedBatchUnsafe(sessionId);
            return batch is not null && batch.ExpiresAtUnixMs > _nowUnixMs() && batch.Role == role
                && batch.PeerFingerprintHex == peerFingerprintHex && batch.ManifestDigest == manifestDigest
                ? batch : null;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<bool> RecordVerifiedBatchCommitAsync(
        SessionId sessionId, ItemId itemId, long sizeBytes, string digest,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var batch = ReadAcceptedBatchUnsafe(sessionId);
            if (batch is null || batch.ExpiresAtUnixMs <= _nowUnixMs() ||
                batch.Role != DesktopResumeJournalRoles.Receiver)
                return false;
            int verified = batch.Manifest.RecordVerifiedItem(batch.VerifiedCount, itemId, sizeBytes, digest);
            if (verified != batch.VerifiedCount)
                PersistJsonUnsafe(BatchJournalPath(sessionId), batch with { VerifiedCount = verified, PendingCommitPath = null });
            return true;
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Records the verified publication candidate before the no-overwrite rename.</summary>
    public async Task RecordBatchPublishCandidateAsync(SessionId sessionId, ItemId itemId, string destination,
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var batch = ReadAcceptedBatchUnsafe(sessionId);
            if (batch == null || batch.Role != DesktopResumeJournalRoles.Receiver ||
                batch.VerifiedCount >= batch.Manifest.Items.Count ||
                batch.Manifest.Items[batch.VerifiedCount].Id != itemId.Value ||
                batch.ExpiresAtUnixMs <= _nowUnixMs())
                throw new InvalidDataException("The resume batch no longer authorizes this commit.");
            PersistJsonUnsafe(BatchJournalPath(sessionId),
                batch with { PendingCommitPath = Path.GetFullPath(destination) });
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Reconciles a process interruption between publication and receipt persistence.</summary>
    public async Task<DesktopAcceptedResumeBatch?> RecoverBatchPublishAsync(SessionId sessionId,
        string destinationRoot, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var batch = ReadAcceptedBatchUnsafe(sessionId);
            if (batch?.PendingCommitPath is not { } target || batch.ExpiresAtUnixMs <= _nowUnixMs() ||
                batch.Role != DesktopResumeJournalRoles.Receiver || batch.VerifiedCount >= batch.Manifest.Items.Count)
                return batch;
            string relative = Path.GetRelativePath(destinationRoot, target);
            if (Path.IsPathRooted(relative) || relative is "." or ".." ||
                relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal))
                return batch;
            string? candidate = target;
            var comparer = OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
            try
            {
                while (candidate != null)
                {
                    if ((File.GetAttributes(candidate) & FileAttributes.ReparsePoint) != 0)
                        return batch;
                    if (comparer.Equals(candidate, Path.TrimEndingDirectorySeparator(destinationRoot)))
                        break;
                    candidate = Path.GetDirectoryName(candidate);
                }
                if (candidate == null)
                    return batch;
                var item = batch.Manifest.Items[batch.VerifiedCount];
                if (new FileInfo(target).Length != item.SizeBytes ||
                    await HashFileHexAsync(target, cancellationToken).ConfigureAwait(false) != item.Digest)
                    return batch;
                batch = batch with { VerifiedCount = batch.VerifiedCount + 1, PendingCommitPath = null };
                PersistJsonUnsafe(BatchJournalPath(sessionId), batch);
                var storedItem = ReadKnownJournalUnsafe(sessionId, new ItemId(item.Id));
                if (storedItem != null)
                    PurgeJournalUnsafe(storedItem);
                return batch;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                return batch;
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    private string BatchJournalPath(SessionId sessionId) => Path.Combine(BatchesRoot, sessionId.Value + ".json");

    private DesktopAcceptedResumeBatch? ReadAcceptedBatchUnsafe(SessionId sessionId)
    {
        if (!Directory.Exists(BatchesRoot))
            return null;
        if ((File.GetAttributes(BatchesRoot) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Resume batch directory is a link.");
        string path = BatchJournalPath(sessionId);
        try
        {
            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Resume batch journal is a link.");
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (stream.Length > MaxBatchJournalBytes)
                throw new InvalidDataException("Resume batch journal exceeds the size limit.");
            var batch = JsonSerializer.Deserialize<DesktopAcceptedResumeBatch>(stream, _json);
            if (batch?.Manifest is null || batch.Manifest.SessionId != sessionId.Value ||
                batch.Manifest.Digest() != batch.ManifestDigest ||
                BatchPeerFingerprint(batch.Manifest, batch.Role) != batch.PeerFingerprintHex ||
                batch.VerifiedCount < 0 || batch.VerifiedCount > batch.Manifest.Items.Count ||
                (batch.Role == DesktopResumeJournalRoles.Sender && (batch.VerifiedCount != 0 || batch.PendingCommitPath != null)) ||
                (batch.PendingCommitPath != null && !Path.IsPathFullyQualified(batch.PendingCommitPath)))
                throw new InvalidDataException("Invalid accepted resume batch journal.");
            return batch;
        }
        catch (FileNotFoundException)
        {
            return null;
        }
    }

    private bool TryPurgeAcceptedBatchUnsafe(SessionId sessionId)
    {
        if (!Directory.Exists(BatchesRoot) || (File.GetAttributes(BatchesRoot) & FileAttributes.ReparsePoint) != 0)
            return false;
        string path = BatchJournalPath(sessionId);
        if (!File.Exists(path))
            return false;
        File.Delete(path);
        return true;
    }

    private int PurgeAcceptedBatchesUnsafe(Func<DesktopAcceptedResumeBatch, bool> shouldPurge)
    {
        if (!Directory.Exists(BatchesRoot) || (File.GetAttributes(BatchesRoot) & FileAttributes.ReparsePoint) != 0)
            return 0;
        int deleted = 0;
        foreach (string path in Directory.EnumerateFiles(BatchesRoot, "*.json"))
        {
            string name = Path.GetFileNameWithoutExtension(path);
            if (!IdentifierValidation.IsValidUuidV4(name))
                continue;
            var session = new SessionId(name);
            bool purge;
            try
            {
                var batch = ReadAcceptedBatchUnsafe(session);
                purge = batch is not null && shouldPurge(batch);
            }
            catch (Exception error) when (error is IOException or JsonException or ArgumentException)
            {
                purge = true;
            }
            if (purge && TryPurgeAcceptedBatchUnsafe(session))
                deleted++;
        }
        return deleted;
    }

    private static string BatchPeerFingerprint(ResumeBatchManifest manifest, string role) => role switch
    {
        DesktopResumeJournalRoles.Sender => manifest.ReceiverFingerprint,
        DesktopResumeJournalRoles.Receiver => manifest.SenderFingerprint,
        _ => throw new ArgumentException("Invalid resume journal role.", nameof(role))
    };
}
