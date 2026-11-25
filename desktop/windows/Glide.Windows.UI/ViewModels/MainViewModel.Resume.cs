using System.Collections.ObjectModel;
using System.IO;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Transport.Sockets;
using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

public partial class MainViewModel
{
    private readonly DesktopResumeStore _resumeStore = new();
    public ObservableCollection<DesktopResumeEntry> ResumeEntries { get; } = new();
    public RelayCommand ResumeInterruptedTransferCommand { get; }
    public RelayCommand DiscardInterruptedTransferCommand { get; }
    public bool HasResumeEntries => ResumeEntries.Count != 0;

    private DesktopReceiveRequest ResumeLocalRequest() => new(
        new QuarantineStagingStore(destinationRoot: ReceiveDirectory), LocalDeviceName: Environment.MachineName,
        TrustManager: _trustManager, ResumeStore: _resumeStore);

    private async Task RefreshResumeEntriesAsync()
    {
        try
        {
            var entries = await Task.Run(() => DesktopResumeEntry.LoadAsync(_resumeStore, _trustManager));
            RunOnUi(() =>
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
        if (parameter is not DesktopResumeEntry entry || _transferRuntime.IsBusy) return;
        try
        {
            if (discard)
                await _resumeStore.PurgeOriginalSessionAsync(new SessionId(entry.Batch.Manifest.SessionId));
            else if (_trustManager?.GetTrustedPeer(entry.Batch.PeerFingerprintHex) is { } peer)
            {
                CurrentTab = "Activity";
                _mdnsService.QueryPeers();
                await Task.Delay(450);
                var candidates = TrustedEndpointResolver.ResolveCandidates(peer, _mdnsService.Snapshot.Peers);
                var result = await _transferRuntime.ResumeTrustedAsync(new DesktopResumeRequest(entry.Batch,
                    ResumeLocalRequest(), candidates), this);
                StatusMessage = result.Message;
            }
            await RefreshResumeEntriesAsync();
        }
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
            var result = await _transferRuntime.ReceiveTrustedResumeAsync(connection, open, ResumeLocalRequest(),
                this, cancellationToken);
            RunOnUi(() => StatusMessage = result.Message);
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
