using System.Text.Json;
using Glide.Core.Models;

namespace Glide.Core.Storage;

public sealed record DesktopAcceptedResumeBatch(
    ResumeBatchManifest Manifest,
    string ManifestDigest,
    string Role,
    string PeerFingerprintHex,
    int VerifiedCount,
    long ExpiresAtUnixMs);

public sealed partial class DesktopResumeStore
{
    private const int MaxBatchJournalBytes = 16 * 1024 * 1024;
    private string BatchesRoot => Path.Combine(_root, "batches");

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
                PersistJsonUnsafe(BatchJournalPath(sessionId), batch with { VerifiedCount = verified });
            return true;
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
                (batch.Role == DesktopResumeJournalRoles.Sender && batch.VerifiedCount != 0))
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
