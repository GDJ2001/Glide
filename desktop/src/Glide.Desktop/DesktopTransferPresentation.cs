using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Glide.Desktop;

/// <summary>Framework-independent, session-only desktop feedback. Verified history stays in its existing store.</summary>
public sealed class DesktopTransferPresentation : INotifyPropertyChanged
{
    private DesktopTransferSnapshot _snapshot = DesktopTransferSnapshot.Idle;
    private Guid _runId;
    private bool _navigated;
    private string _currentFile = "";
    private double _progress;
    private string _bytes = "";
    private string _rate = "";
    private string? _receivedFolder;
    public event PropertyChangedEventHandler? PropertyChanged;
    public ObservableCollection<DesktopTransferSessionResult> PreviousResults { get; } = [];
    public bool HasPreviousResults => PreviousResults.Count > 0;
    public bool IsActive => _snapshot.IsActive;
    public bool HasResult => _snapshot.Result is not null;
    public bool HasTransfer => IsActive || HasResult;
    public bool IsEmpty => !HasTransfer;
    public string PeerName => _snapshot.PeerName;
    public string DirectionText => _snapshot.Direction == DesktopTransferDirection.Receive ? "Receiving" : "Sending";
    public string Title => HasResult ? ResultTitle(_snapshot.Result!) : $"{DirectionText} · {PeerName}";
    public string PhaseText => _snapshot.Phase switch
    {
        DesktopTransferPhase.Preparing => "Connecting…",
        DesktopTransferPhase.AwaitingConsent => "Waiting for approval…",
        DesktopTransferPhase.Transferring => "Transferring…",
        DesktopTransferPhase.Verifying => "Verifying files…",
        _ => ""
    };
    public bool IsIndeterminate => IsActive && _snapshot.Phase is DesktopTransferPhase.Preparing or DesktopTransferPhase.AwaitingConsent or DesktopTransferPhase.Verifying;
    public string CurrentFile => _currentFile;
    public double Progress => _progress;
    public string BytesText => _bytes;
    public string RateText => _rate;
    public bool IsSuccess => _snapshot.Result?.Success == true;
    public bool IsFailure => HasResult && !IsSuccess && !_snapshot.Result!.Cancelled &&
        _snapshot.Result.ErrorCategory != TransferErrorCategory.UserRejected;
    public bool IsNeutralResult => HasResult && !IsSuccess && !IsFailure;
    public bool CanRetry => IsFailure && _snapshot.Direction == DesktopTransferDirection.Send &&
        _snapshot.Result!.ErrorCategory is TransferErrorCategory.Network or TransferErrorCategory.Storage or
            TransferErrorCategory.Protocol or TransferErrorCategory.Unknown;
    public bool CanOpenFolder => HasResult && _snapshot.Direction == DesktopTransferDirection.Receive &&
        _snapshot.Result!.VerifiedCount > 0 && !string.IsNullOrWhiteSpace(_receivedFolder);
    public string? ReceivedFolder => _receivedFolder;
    public string ResultSummary => !HasResult ? "" : _snapshot.Result!.Success
        ? $"{_snapshot.Result.VerifiedCount} file(s) verified · {FormatBytes(_snapshot.Result.VerifiedBytes)}"
        : _snapshot.Result.Cancelled ? "The transfer was cancelled."
        : _snapshot.Result.ErrorCategory == TransferErrorCategory.UserRejected ? "The file offer was declined."
        : _snapshot.Result.ErrorCategory switch
        {
            TransferErrorCategory.Network => "Connection interrupted. Keep both devices open and on the same network, then try again.",
            TransferErrorCategory.Storage => "Check the source files, receiving folder, and available space, then try again.",
            TransferErrorCategory.Security => "Device or file verification failed. Check the device and pair again if its identity changed.",
            TransferErrorCategory.LimitExceeded => "The selection exceeds a transfer limit. Choose fewer or smaller files.",
            TransferErrorCategory.Protocol => "The devices could not complete this transfer. Check that both have the current Glide build.",
            _ => "The transfer could not finish. Review the details and try again."
        };
    public string VerifiedSummary => HasResult && !IsSuccess && _snapshot.Result!.VerifiedCount > 0
        ? $"{_snapshot.Result.VerifiedCount} file(s) verified · {FormatBytes(_snapshot.Result.VerifiedBytes)}" : "";
    public bool HasVerifiedSummary => VerifiedSummary.Length > 0;
    public string Details => _snapshot.Result?.Message ?? "";
    public bool HasDetails => HasResult && !IsSuccess && Details.Length > 0;

    /// <returns>True once when this operation should open Activity.</returns>
    public bool ApplySnapshot(DesktopTransferSnapshot snapshot, bool navigateOnPreparation = false)
    {
        if (snapshot.RunId != _runId)
        {
            if (_snapshot.Result is { Success: false } oldResult)
            {
                PreviousResults.Insert(0, new(ResultTitle(oldResult), _snapshot.PeerName, ResultSummary));
                // Bounded session memory; this is not persistent verified history.
                if (PreviousResults.Count > 100) PreviousResults.RemoveAt(100);
            }
            _runId = snapshot.RunId;
            _navigated = false;
            _currentFile = _bytes = _rate = "";
            _progress = 0;
            _receivedFolder = null;
        }
        _snapshot = snapshot;
        if (IsSuccess) _progress = 100;
        bool navigate = !_navigated && (snapshot.Phase == DesktopTransferPhase.Transferring ||
            (navigateOnPreparation && snapshot.Phase == DesktopTransferPhase.Preparing));
        if (navigate) _navigated = true;
        Changed();
        return navigate;
    }

    public void ApplyEvent(TransferEvent ev)
    {
        switch (ev)
        {
            case TransferEvent.ProgressUpdated p:
                _currentFile = p.Progress.ItemName;
                _progress = p.Progress.Fraction * 100;
                _bytes = p.TransferredBytesText;
                _rate = p.RateText;
                break;
            case TransferEvent.ItemStatusChanged item: _currentFile = item.ItemName; break;
        }
        Changed();
    }
    public void RecordVerifiedFile(DesktopHistoryEntry entry)
    {
        if (entry.Direction == DesktopTransferDirection.Receive) _receivedFolder = Path.GetDirectoryName(entry.Path);
        Changed();
    }
    private void Changed() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
    private static string ResultTitle(DesktopTransferResult result) => result.Success ? "Transfer complete" :
        result.Cancelled ? "Transfer cancelled" : result.ErrorCategory == TransferErrorCategory.UserRejected
            ? "Transfer declined" : "Transfer failed";
    public static string FormatBytes(long bytes) => bytes >= 1L << 30 ? $"{bytes / (double)(1L << 30):0.##} GB" :
        bytes >= 1L << 20 ? $"{bytes / (double)(1L << 20):0.##} MB" : bytes >= 1024 ? $"{bytes / 1024.0:0.##} KB" : $"{bytes} B";
}
public sealed record DesktopTransferSessionResult(string Title, string PeerName, string Summary);
