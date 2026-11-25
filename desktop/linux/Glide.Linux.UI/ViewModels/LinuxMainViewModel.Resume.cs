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
    private bool _isStartingResume;
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
                string? activePausedSession = Transfer.IsPaused ? Transfer.PausedSessionId : null;
                ResumeEntries.Clear();
                foreach (var entry in entries)
                {
                    if (activePausedSession != null &&
                        string.Equals(entry.Batch.Manifest.SessionId, activePausedSession, StringComparison.Ordinal))
                    {
                        continue;
                    }
                    ResumeEntries.Add(entry);
                }
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
            else
                await ResumeAcceptedBatchAsync(entry.Batch);
            await RefreshResumeEntriesAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            StatusMessage = "Could not open the retained transfer.";
        }
    }

    private async Task ResumeBatchBySessionIdAsync(string sessionId)
    {
        if (_isStartingResume || _runtime.IsBusy) return;
        try
        {
            var batches = await Task.Run(() => _resumeStore.ListAcceptedBatchesAsync(_lifetimeCts.Token));
            var match = batches.FirstOrDefault(batch =>
                string.Equals(batch.Manifest.SessionId, sessionId, StringComparison.Ordinal));
            if (match == null)
            {
                _runtime.ClearPausedState("The paused transfer is no longer available.");
                await RefreshResumeEntriesAsync();
                return;
            }
            await ResumeAcceptedBatchAsync(match);
            await RefreshResumeEntriesAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            StatusMessage = "Could not resume the transfer.";
        }
    }

    private async Task ResumeAcceptedBatchAsync(DesktopAcceptedResumeBatch batch)
    {
        if (_isStartingResume || _runtime.IsBusy) return;
        if (_trustManager?.GetTrustedPeer(batch.PeerFingerprintHex) is not { } peer)
        {
            StatusMessage = "The paired device is no longer trusted.";
            return;
        }
        _isStartingResume = true;
        try
        {
            ActivePage = LinuxPage.Activity;
            StatusMessage = $"Reconnecting to {peer.DisplayName}…";
            _mdnsDiscovery.Start(enableBrowsing: true);
            IReadOnlyList<TrustedEndpointCandidate> candidates = Array.Empty<TrustedEndpointCandidate>();
            for (int attempt = 0; attempt < 6; attempt++)
            {
                _mdnsDiscovery.QueryPeers();
                await Task.Delay(450, _lifetimeCts.Token);
                candidates = TrustedEndpointResolver.ResolveCandidates(peer, _mdnsDiscovery.Snapshot.Peers);
                if (candidates.Any(c => !c.IsSavedEndpoint)) break;
            }
            var result = await _runtime.ResumeTrustedAsync(new DesktopResumeRequest(batch,
                ResumeLocalRequest(), candidates), this, _lifetimeCts.Token);
            StatusMessage = result.Message;
        }
        finally
        {
            _isStartingResume = false;
        }
    }

    private async Task CancelPausedTransferAsync(string sessionId)
    {
        try
        {
            if (IdentifierValidation.IsValidUuidV4(sessionId))
                await _resumeStore.PurgeOriginalSessionAsync(new SessionId(sessionId), _lifetimeCts.Token);
            _runtime.ClearPausedState();
            StatusMessage = "Transfer cancelled.";
            await RefreshResumeEntriesAsync();
        }
        catch (OperationCanceledException) { }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            StatusMessage = "Could not cancel the paused transfer.";
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
