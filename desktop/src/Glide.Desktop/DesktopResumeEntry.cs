using Glide.Core.Security;
using Glide.Core.Storage;

namespace Glide.Desktop;

public sealed record DesktopResumeEntry(DesktopAcceptedResumeBatch Batch, string Label)
{
    public static async Task<IReadOnlyList<DesktopResumeEntry>> LoadAsync(DesktopResumeStore store,
        DesktopPersistentTrustManager? trust, CancellationToken cancellationToken = default)
    {
        if (trust == null)
            return [];
        await store.PurgeExpiredAsync(cancellationToken).ConfigureAwait(false);
        var batches = await store.ListAcceptedBatchesAsync(cancellationToken).ConfigureAwait(false);
        return batches.Select(batch =>
            {
                var peer = trust.GetTrustedPeer(batch.PeerFingerprintHex);
                var direction = batch.Role == DesktopResumeJournalRoles.Sender ? "Send to" : "Receive from";
                return peer == null ? null : new DesktopResumeEntry(batch,
                    $"{direction} {peer.DisplayName} · {batch.Manifest.Items.Count} files");
            })
            .OfType<DesktopResumeEntry>().ToArray();
    }
}
