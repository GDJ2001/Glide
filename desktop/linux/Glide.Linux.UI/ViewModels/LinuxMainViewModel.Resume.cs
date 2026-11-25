using System.Collections.ObjectModel;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Transport.Sockets;

namespace Glide.Linux.UI.ViewModels;

public sealed partial class LinuxMainViewModel
{
    private readonly DesktopResumeStore _resumeStore = new();
    public ObservableCollection<DesktopResumeEntry> ResumeEntries { get; } = new();
    public AsyncRelayCommand ResumeInterruptedTransferCommand { get; }
    public AsyncRelayCommand DiscardInterruptedTransferCommand { get; }
    public bool HasResumeEntries => ResumeEntries.Count != 0;

    private DesktopReceiveRequest ResumeLocalRequest() => new(
        new QuarantineStagingStore(destinationRoot: DownloadDirectory), LocalDeviceName: DeviceName,
        TrustManager: _trustManager, ResumeStore: _resumeStore);

    private async Task RefreshResumeEntriesAsync()
    {
        try
        {
            var entries = await Task.Run(() => DesktopResumeEntry.LoadAsync(_resumeStore, _trustManager));
            OnUi(() =>
            {
                ResumeEntries.Clear();
                foreach (var entry in entries) ResumeEntries.Add(entry);
                OnPropertyChanged(nameof(HasResumeEntries));
            });
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    private async Task UseResumeEntryAsync(object? parameter, bool discard)
    {
        if (parameter is not DesktopResumeEntry entry || _runtime.IsBusy) return;
        try
        {
            if (discard)
                await _resumeStore.PurgeOriginalSessionAsync(new SessionId(entry.Batch.Manifest.SessionId), _lifetimeCts.Token);
            else if (_trustManager?.GetTrustedPeer(entry.Batch.PeerFingerprintHex) is { } peer)
            {
                ActivePage = LinuxPage.Activity;
                _mdnsDiscovery.QueryPeers();
                await Task.Delay(450, _lifetimeCts.Token);
                var candidates = TrustedEndpointResolver.ResolveCandidates(peer, _mdnsDiscovery.Snapshot.Peers);
                var result = await _runtime.ResumeTrustedAsync(new DesktopResumeRequest(entry.Batch,
                    ResumeLocalRequest(), candidates), this, _lifetimeCts.Token);
                StatusMessage = result.Message;
            }
            await RefreshResumeEntriesAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            StatusMessage = "Could not open the retained transfer.";
        }
    }

    private async Task HandleResumeConnectionAsync(TlsSocketConnection connection, Profile2Message.SessionOpen open,
        CancellationToken cancellationToken)
    {
        await using (connection.ConfigureAwait(false))
        {
            var result = await _runtime.ReceiveTrustedResumeAsync(connection, open, ResumeLocalRequest(), this, cancellationToken);
            OnUi(() => StatusMessage = result.Message);
        }
        await RefreshResumeEntriesAsync();
    }

    private async Task RemoveResumeAuthorityAsync(string fingerprint)
    {
        try
        {
            await _resumeStore.PurgePeerAsync(fingerprint);
            await RefreshResumeEntriesAsync();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }
}
