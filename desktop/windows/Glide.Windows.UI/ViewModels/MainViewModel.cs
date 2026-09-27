using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Transport.Discovery;
using Glide.Transport.Sockets;
using Glide.Transport.Web;
using Glide.Windows.UI.Common;
using Microsoft.Win32;

namespace Glide.Windows.UI.ViewModels;

public class MainViewModel : ViewModelBase, IDesktopTransferInteraction
{
    private string _currentTab = "Send";
    private string _peerHost = "127.0.0.1";
    private int _peerPort = LanDiscoveryConstants.DefaultPort;
    private bool _isListening;
    private int _listeningPort;
    private string _statusMessage = "Ready";
    private string _localIpText = "";

    private bool _isOfferModalVisible;
    private string _incomingOfferPeer = "";
    private string _incomingOfferSummary = "";
    private TaskCompletionSource<bool>? _offerConfirmationTcs;

    private bool _isTrustEnrollmentModalVisible;
    private string _trustEnrollmentPeerName = "";
    private string _trustEnrollmentFingerprint = "";
    private TaskCompletionSource<bool>? _trustEnrollmentTcs;
    private DesktopPersistentTrustManager? _trustManager;
    private string _localIdentityFingerprint = "Unavailable";
    private int _trustedDeviceCount;

    private bool _isTransferActive;
    private string _transferTitle = "";
    private double _totalProgress;
    private string _transferRateText = "";
    private string _transferredBytesText = "";
    private string _currentFileName = "";
    private CancellationTokenSource? _activeTransferCts;
    private readonly DesktopTransferRuntime _transferRuntime = new();
    private readonly DesktopStateStore _stateStore = new();
    private readonly Stopwatch _transferStopwatch = new();
    private string _receiveDirectory = "";
    private int _listenerPortPreference = LanDiscoveryConstants.DefaultPort;

    private TlsSocketListener? _listener;
    private CancellationTokenSource? _invitationRotationCts;

    private readonly MdnsDiscoveryService _mdnsService = new();
    public ObservableCollection<DiscoveredPeerViewModel> DiscoveredPeers { get; } = [];

    private bool _isPairModalVisible;
    private string _pairInputUri = "";
    private string _activeInvitationUri = "";
    private LanInvitation? _activeInvitation;
    private LanInvitation? _pairedInvitation;
    private DiscoveredPeerViewModel? _selectedPeer;
    private bool _sendAfterPairing;
    private string _pairingStatusText = "Choose a nearby device to start.";
    private readonly LanInvitationAuthority _invitationAuthority = new();

    private GlideWebGateway? _webGateway;
    private bool _isWebShareModalVisible;
    private string _webShareUrl = "";

    private ImageSource? _webShareQrImage;
    private ImageSource? _pairingQrImage;
    private bool _isQrZoomModalVisible;

    public bool IsQrZoomModalVisible
    {
        get => _isQrZoomModalVisible;
        set => SetProperty(ref _isQrZoomModalVisible, value);
    }

    public bool IsWebShareModalVisible
    {
        get => _isWebShareModalVisible;
        set => SetProperty(ref _isWebShareModalVisible, value);
    }

    public string WebShareUrl
    {
        get => _webShareUrl;
        set
        {
            if (SetProperty(ref _webShareUrl, value))
            {
                WebShareQrImage = QrImageHelper.GenerateQrImageSource(value);
            }
        }
    }

    public ImageSource? WebShareQrImage
    {
        get => _webShareQrImage;
        set => SetProperty(ref _webShareQrImage, value);
    }

    public ImageSource? PairingQrImage
    {
        get => _pairingQrImage;
        set => SetProperty(ref _pairingQrImage, value);
    }

    public ObservableCollection<TransferItemViewModel> SelectedFiles { get; } = [];
    public ObservableCollection<HistoryItemViewModel> HistoryItems { get; } = [];

    public RelayCommand SwitchTabCommand { get; }
    public RelayCommand AddFilesCommand { get; }
    public RelayCommand RemoveFileCommand { get; }
    public RelayCommand ClearFilesCommand { get; }
    public RelayCommand SendFilesCommand { get; }
    public RelayCommand ToggleListenerCommand { get; }
    public RelayCommand AcceptOfferCommand { get; }
    public RelayCommand RejectOfferCommand { get; }
    public RelayCommand AcceptTrustEnrollmentCommand { get; }
    public RelayCommand RejectTrustEnrollmentCommand { get; }
    public RelayCommand BrowseReceiveFolderCommand { get; }
    public RelayCommand CancelTransferCommand { get; }
    public RelayCommand OpenDownloadsCommand { get; }
    public RelayCommand ShowPairModalCommand { get; }
    public RelayCommand CancelPairModalCommand { get; }
    public RelayCommand SubmitPairModalCommand { get; }
    public RelayCommand PastePairingLinkCommand { get; }
    public RelayCommand ClearPairingCommand { get; }
    public RelayCommand SelectPeerCommand { get; }
    public RelayCommand RefreshPeersCommand { get; }
    public RelayCommand RefreshQrCommand { get; }
    public RelayCommand ToggleQrZoomCommand { get; }
    public RelayCommand CopyPairingLinkCommand { get; }
    public RelayCommand StartWebShareCommand { get; }
    public RelayCommand StopWebShareCommand { get; }
    public RelayCommand CopyWebShareLinkCommand { get; }

    public MainViewModel()
    {
        try
        {
            DesktopPersistentState persisted = _stateStore.Load();
            _receiveDirectory = persisted.Settings.ReceiveDirectory;
            _listenerPortPreference = persisted.Settings.ListenerPort;
            foreach (DesktopHistoryEntry entry in persisted.History)
            {
                HistoryItems.Add(ToHistoryViewModel(entry));
            }
        }
        catch
        {
            _receiveDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads",
                "Glide");
            _listenerPortPreference = LanDiscoveryConstants.DefaultPort;
        }

        if (string.IsNullOrWhiteSpace(_receiveDirectory))
        {
            _receiveDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads",
                "Glide");
        }

        try
        {
            Directory.CreateDirectory(_receiveDirectory);
        }
        catch
        {
            // Ignore directory creation error until transfer
        }

        InitializePersistentTrust();

        SwitchTabCommand = new RelayCommand(param => CurrentTab = param?.ToString() ?? "Send");
        AddFilesCommand = new RelayCommand(ExecuteAddFiles);
        RemoveFileCommand = new RelayCommand(param =>
        {
            if (param is TransferItemViewModel item) SelectedFiles.Remove(item);
        });
        ClearFilesCommand = new RelayCommand(() => SelectedFiles.Clear());
        SendFilesCommand = new RelayCommand(
            () => _ = ExecuteSendFilesSafelyAsync(),
            () => !IsTransferActive && SelectedFiles.Count > 0);
        ToggleListenerCommand = new RelayCommand(async () => await ToggleListenerAsync());
        AcceptOfferCommand = new RelayCommand(() => _offerConfirmationTcs?.TrySetResult(true));
        RejectOfferCommand = new RelayCommand(() => _offerConfirmationTcs?.TrySetResult(false));
        AcceptTrustEnrollmentCommand = new RelayCommand(() => _trustEnrollmentTcs?.TrySetResult(true));
        RejectTrustEnrollmentCommand = new RelayCommand(() => _trustEnrollmentTcs?.TrySetResult(false));
        BrowseReceiveFolderCommand = new RelayCommand(ExecuteBrowseReceiveFolder);
        CancelTransferCommand = new RelayCommand(() =>
        {
            _transferRuntime.CancelActiveTransfer();
            _activeTransferCts?.Cancel();
        });
        OpenDownloadsCommand = new RelayCommand(ExecuteOpenDownloads);
        ShowPairModalCommand = new RelayCommand(ShowPairingSheet);
        CancelPairModalCommand = new RelayCommand(() =>
        {
            _sendAfterPairing = false;
            IsPairModalVisible = false;
            PairInputUri = "";
            StatusMessage = SelectedFiles.Count > 0
                ? "Send paused. Your selected files are still ready."
                : "Pairing cancelled.";
        });
        SubmitPairModalCommand = new RelayCommand(ExecuteSubmitPairModal);
        PastePairingLinkCommand = new RelayCommand(() =>
        {
            if (TryReadPairingLinkFromClipboard(out string invitationUri))
            {
                PairInputUri = invitationUri;
                ExecuteSubmitPairModal();
            }
            else
            {
                StatusMessage = "Copy a Glide pairing code first, then choose Paste code.";
            }
        });
        ClearPairingCommand = new RelayCommand(() =>
        {
            _pairedInvitation = null;
            _selectedPeer = null;
            _sendAfterPairing = false;
            PairingStatusText = "Choose a nearby device to start.";
            OnPropertyChanged(nameof(PairingSheetTitle));
            OnPropertyChanged(nameof(PairingSheetInstruction));
            OnPropertyChanged(nameof(SendActionText));
            StatusMessage = "Pairing cleared. Choose a nearby device when you are ready.";
            CommandManager.InvalidateRequerySuggested();
        });
        SelectPeerCommand = new RelayCommand(param =>
        {
            if (param is DiscoveredPeerViewModel peer)
            {
                _selectedPeer = peer;
                OnPropertyChanged(nameof(PairingSheetTitle));
                OnPropertyChanged(nameof(PairingSheetInstruction));
                PeerHost = peer.Host;
                PeerPort = peer.Port;
                _pairedInvitation = null;
                PairingStatusText = $"{peer.DeviceName} selected. Confirm its secure pairing code.";
                OnPropertyChanged(nameof(SendActionText));

                if (TryReadPairingLinkFromClipboard(out string invitationUri) &&
                    TryApplyPairingInvitation(invitationUri, showErrors: false))
                {
                    StatusMessage = $"Paired with {peer.DeviceName} using the copied secure code.";
                }
                else
                {
                    StatusMessage = $"{peer.DeviceName} selected. Scan its QR code or paste its Glide code to continue.";
                    ShowPairingSheet();
                }
                CommandManager.InvalidateRequerySuggested();
            }
        });
        RefreshPeersCommand = new RelayCommand(() =>
        {
            try
            {
                _mdnsService.QueryPeers();
                StatusMessage = "Scanning for nearby Glide devices...";
            }
            catch (Exception ex)
            {
                StatusMessage = $"Discovery scan warning: {ex.Message}";
            }
        });
        RefreshQrCommand = new RelayCommand(() =>
        {
            if (IsListening && _listener != null)
            {
                _invitationAuthority.RevokeActive();
                RefreshReceiverInvitation();
                StatusMessage = "Pairing QR code refreshed.";
            }
        });
        ToggleQrZoomCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrEmpty(ActiveInvitationUri))
            {
                IsQrZoomModalVisible = !IsQrZoomModalVisible;
            }
        });
        CopyPairingLinkCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrEmpty(ActiveInvitationUri))
            {
                Clipboard.SetText(ActiveInvitationUri);
                StatusMessage = "Pairing link copied to clipboard!";
            }
        });

        StartWebShareCommand = new RelayCommand(
            () => StatusMessage = "Browser sharing is not included in this Windows release.",
            () => false);
        StopWebShareCommand = new RelayCommand(async () => await ExecuteStopWebShareAsync());
        CopyWebShareLinkCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrEmpty(WebShareUrl))
            {
                Clipboard.SetText(WebShareUrl);
                StatusMessage = "Web Share link copied to clipboard!";
            }
        });

        SelectedFiles.CollectionChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(SelectedFilesSummary));
            CommandManager.InvalidateRequerySuggested();
        };

        _mdnsService.PeerDiscovered += peer =>
        {
            Application.Current?.Dispatcher?.Invoke(() =>
            {
                if (!DiscoveredPeers.Any(p => p.Host == peer.Host && p.Port == peer.Port))
                {
                    DiscoveredPeers.Add(new DiscoveredPeerViewModel(peer));
                }
            });
        };

        _mdnsService.PeerLost += peer =>
        {
            Application.Current?.Dispatcher?.Invoke(() =>
            {
                var existing = DiscoveredPeers.FirstOrDefault(p => p.Host == peer.Host && p.Port == peer.Port);
                if (existing != null)
                {
                    DiscoveredPeers.Remove(existing);
                }
            });
        };

        try
        {
            _mdnsService.Start(enableBrowsing: true);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"mDNS startup non-fatal warning: {ex.Message}");
        }

        RefreshNetworkInfo();
        _ = ToggleListenerAsync();
    }

    public string CurrentTab
    {
        get => _currentTab;
        set => SetProperty(ref _currentTab, value);
    }

    public string PeerHost
    {
        get => _peerHost;
        set => SetProperty(ref _peerHost, value);
    }

    public int PeerPort
    {
        get => _peerPort;
        set => SetProperty(ref _peerPort, value);
    }

    public bool IsListening
    {
        get => _isListening;
        set => SetProperty(ref _isListening, value);
    }

    public int ListeningPort
    {
        get => _listeningPort;
        set => SetProperty(ref _listeningPort, value);
    }

    public string StatusMessage
    {
        get => _statusMessage;
        set => SetProperty(ref _statusMessage, value);
    }

    public string LocalIpText
    {
        get => _localIpText;
        set => SetProperty(ref _localIpText, value);
    }

    public bool IsOfferModalVisible
    {
        get => _isOfferModalVisible;
        set => SetProperty(ref _isOfferModalVisible, value);
    }

    public string IncomingOfferPeer
    {
        get => _incomingOfferPeer;
        set => SetProperty(ref _incomingOfferPeer, value);
    }

    public string IncomingOfferSummary
    {
        get => _incomingOfferSummary;
        set => SetProperty(ref _incomingOfferSummary, value);
    }

    public bool IsTrustEnrollmentModalVisible
    {
        get => _isTrustEnrollmentModalVisible;
        set => SetProperty(ref _isTrustEnrollmentModalVisible, value);
    }

    public string TrustEnrollmentPeerName
    {
        get => _trustEnrollmentPeerName;
        set => SetProperty(ref _trustEnrollmentPeerName, value);
    }

    public string TrustEnrollmentFingerprint
    {
        get => _trustEnrollmentFingerprint;
        set => SetProperty(ref _trustEnrollmentFingerprint, value);
    }

    public string LocalIdentityFingerprint
    {
        get => _localIdentityFingerprint;
        set => SetProperty(ref _localIdentityFingerprint, value);
    }

    public int TrustedDeviceCount
    {
        get => _trustedDeviceCount;
        set => SetProperty(ref _trustedDeviceCount, value);
    }

    public string ReceiveDirectory
    {
        get => _receiveDirectory;
        set
        {
            if (SetProperty(ref _receiveDirectory, value))
            {
                PersistDesktopSettings();
            }
        }
    }

    public int ListenerPortPreference
    {
        get => _listenerPortPreference;
        set
        {
            if (value is < 1 or > 65535)
            {
                return;
            }

            if (SetProperty(ref _listenerPortPreference, value))
            {
                PersistDesktopSettings();
            }
        }
    }

    public bool IsPairModalVisible
    {
        get => _isPairModalVisible;
        set => SetProperty(ref _isPairModalVisible, value);
    }

    public string PairInputUri
    {
        get => _pairInputUri;
        set => SetProperty(ref _pairInputUri, value);
    }

    public string PairingStatusText
    {
        get => _pairingStatusText;
        private set => SetProperty(ref _pairingStatusText, value);
    }

    public string PairingSheetTitle => _selectedPeer is null
        ? "Connect to the receiving phone"
        : $"Pair with {_selectedPeer.DeviceName}";

    public string PairingSheetInstruction => _selectedPeer is null
        ? "On the phone, open Glide > Receive files > Show QR code > Copy invite. Bring that Glide invite to this PC, paste it below, and confirm. Both devices must be on the same local network."
        : $"On {_selectedPeer.DeviceName}, open Receive files > Show QR code > Copy invite. Paste that invite below. Glide checks that it belongs to the nearby device you selected.";

    public string SendActionText => _pairedInvitation is null
        ? "Choose phone and send"
        : $"Send to {_pairedInvitation.Name}";

    public string SelectedFilesSummary => SelectedFiles.Count switch
    {
        0 => "Add files above to begin.",
        1 => "1 file ready to send.",
        _ => $"{SelectedFiles.Count} files ready to send."
    };

    public string ActiveInvitationUri
    {
        get => _activeInvitationUri;
        set
        {
            if (SetProperty(ref _activeInvitationUri, value))
            {
                PairingQrImage = QrImageHelper.GenerateQrImageSource(value);
            }
        }
    }

    public bool IsTransferActive
    {
        get => _isTransferActive;
        set
        {
            SetProperty(ref _isTransferActive, value);
            CommandManager.InvalidateRequerySuggested();
        }
    }

    public string TransferTitle
    {
        get => _transferTitle;
        set => SetProperty(ref _transferTitle, value);
    }

    public double TotalProgress
    {
        get => _totalProgress;
        set => SetProperty(ref _totalProgress, value);
    }

    public string TransferRateText
    {
        get => _transferRateText;
        set => SetProperty(ref _transferRateText, value);
    }

    public string TransferredBytesText
    {
        get => _transferredBytesText;
        set => SetProperty(ref _transferredBytesText, value);
    }

    public string CurrentFileName
    {
        get => _currentFileName;
        set => SetProperty(ref _currentFileName, value);
    }

    public void AddDroppedFiles(string[] filePaths)
    {
        DesktopTransferSelectionResult selection =
            DesktopTransferSelection.Expand(filePaths);

        foreach (DesktopTransferFile file in selection.Files)
        {
            if (!SelectedFiles.Any(f => f.FilePath.Equals(file.FilePath, StringComparison.OrdinalIgnoreCase)))
            {
                SelectedFiles.Add(new TransferItemViewModel(file.FilePath, file.TransferRelativePath));
            }
        }

        if (selection.Skipped.Count > 0)
        {
            StatusMessage = $"Added {selection.Files.Count} item(s). Skipped {selection.Skipped.Count}: {selection.Skipped[0].Reason}";
        }
        else if (selection.Files.Count > 0)
        {
            StatusMessage = _pairedInvitation is null
                ? $"{SelectedFiles.Count} item(s) ready. Choose a phone and send."
                : $"{SelectedFiles.Count} item(s) ready to send to {_pairedInvitation.Name}.";
        }
        CommandManager.InvalidateRequerySuggested();
    }

    private void ExecuteAddFiles()
    {
        var dlg = new OpenFileDialog
        {
            Multiselect = true,
            Title = "Select files to send with Glide"
        };
        if (dlg.ShowDialog() == true)
        {
            AddDroppedFiles(dlg.FileNames);
        }
    }

    private void RefreshNetworkInfo()
    {
        try
        {
            var ips = Dns.GetHostAddresses(Dns.GetHostName())
                .Where(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                .Select(ip => ip.ToString())
                .ToList();
            LocalIpText = ips.Count > 0 ? string.Join(", ", ips) : "127.0.0.1";
        }
        catch
        {
            LocalIpText = "127.0.0.1";
        }
    }

    private void ShowPairingSheet()
    {
        if (string.IsNullOrWhiteSpace(PairInputUri) &&
            TryReadPairingLinkFromClipboard(out string invitationUri))
        {
            PairInputUri = invitationUri;
        }

        IsPairModalVisible = true;
    }

    private static bool TryReadPairingLinkFromClipboard(out string invitationUri)
    {
        invitationUri = string.Empty;
        try
        {
            if (!Clipboard.ContainsText())
            {
                return false;
            }

            string candidate = Clipboard.GetText().Trim();
            if (!candidate.StartsWith("glide://pair/", StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }

            // Decode now so opening the sheet never turns an arbitrary clipboard value into a
            // pairing attempt. Detailed errors remain available when the user submits manually.
            _ = InvitationCodec.Decode(candidate);
            invitationUri = candidate;
            return true;
        }
        catch
        {
            return false;
        }
    }

    private void ExecuteSubmitPairModal()
    {
        if (string.IsNullOrWhiteSpace(PairInputUri))
        {
            StatusMessage = "Scan a Glide QR code or paste a pairing code to continue.";
            return;
        }

        if (TryApplyPairingInvitation(PairInputUri.Trim(), showErrors: true))
        {
            IsPairModalVisible = false;
            PairInputUri = "";
            CommandManager.InvalidateRequerySuggested();

            bool continueSending = _sendAfterPairing && SelectedFiles.Count > 0;
            _sendAfterPairing = false;
            if (continueSending)
            {
                _ = ExecuteSendFilesSafelyAsync();
            }
        }
    }

    private bool TryApplyPairingInvitation(string invitationUri, bool showErrors)
    {
        try
        {
            LanInvitation invitation = InvitationCodec.Decode(invitationUri);
            if (invitation.IsExpired())
            {
                if (showErrors)
                {
                    MessageBox.Show(
                        "This pairing code has expired. Ask the receiving device to show a fresh QR code.",
                        "Pairing code expired",
                        MessageBoxButton.OK,
                        MessageBoxImage.Warning);
                }
                return false;
            }

            if (_selectedPeer is not null &&
                !string.Equals(invitation.Presence, _selectedPeer.PresenceId, StringComparison.Ordinal))
            {
                if (showErrors)
                {
                    MessageBox.Show(
                        $"This pairing code does not belong to {_selectedPeer.DeviceName}. Select the device that displayed this code, or use its current QR code.",
                        "Different device selected",
                        MessageBoxButton.OK,
                        MessageBoxImage.Warning);
                }
                return false;
            }

            LanEndpoint? targetEndpoint = invitation.Endpoints.FirstOrDefault();
            if (targetEndpoint is null)
            {
                throw new InvalidDataException("The pairing code did not include a reachable device address.");
            }

            PeerHost = targetEndpoint.Address;
            PeerPort = targetEndpoint.Port;
            _pairedInvitation = invitation;
            PairingStatusText = $"Paired with {invitation.Name}. Ready to send.";
            OnPropertyChanged(nameof(SendActionText));
            StatusMessage = $"Paired with {invitation.Name}. Ready to send.";
            return true;
        }
        catch (Exception ex)
        {
            if (showErrors)
            {
                MessageBox.Show(
                    $"That pairing code could not be used:\n\n{ex.Message}",
                    "Pairing failed",
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning);
            }
            return false;
        }
    }

    private async Task ToggleListenerAsync()
    {
        if (IsListening)
        {
            _invitationRotationCts?.Cancel();
            _invitationRotationCts?.Dispose();
            _invitationRotationCts = null;
            _mdnsService.StopAdvertising();
            _invitationAuthority.RevokeActive();
            ActiveInvitationUri = "";
            _activeInvitation = null;
            _listener?.Dispose();
            _listener = null;
            IsListening = false;
            StatusMessage = "Listener stopped";
            return;
        }

        try
        {
            _listener = new TlsSocketListener(ListenerPortPreference);
        }
        catch
        {
            _listener = new TlsSocketListener(0);
        }

        ListeningPort = _listener.BoundPort;
        IsListening = true;

        RefreshReceiverInvitation();
        _invitationRotationCts?.Dispose();
        _invitationRotationCts =
            new CancellationTokenSource();

        StatusMessage = $"Listening on port {ListeningPort}";
        _ = Task.Run(
            async () =>
                await RunInvitationRotationLoopAsync(
                    _invitationRotationCts.Token));
        _ = Task.Run(async () => await RunListenerLoopAsync());
    }

    private async Task RunInvitationRotationLoopAsync(
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(
                    TimeSpan.FromSeconds(100),
                    cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }

            RunOnUi(() =>
            {
                if (IsListening &&
                    _listener != null &&
                    !IsTransferActive)
                {
                    _invitationAuthority.RevokeActive();
                    RefreshReceiverInvitation();
                    StatusMessage =
                        "Pairing invitation rotated.";
                }
            });
        }
    }

    private async Task RunListenerLoopAsync()
    {
        while (IsListening && _listener != null)
        {
            try
            {
                var conn = await _listener.AcceptConnectionAsync();
                await HandleInboundTransferAsync(conn);
            }
            catch (Exception ex)
            {
                if (!IsListening || _listener == null)
                {
                    break;
                }
                RunOnUi(() => StatusMessage = $"Inbound connection failed: {ex.Message}. Listening on port {ListeningPort}...");
            }
        }
    }

    private async Task HandleInboundTransferAsync(TlsSocketConnection conn)
    {
        await using (conn)
        {
            _activeTransferCts?.Dispose();
            _activeTransferCts = new CancellationTokenSource();
            var cancellationToken = _activeTransferCts.Token;

            try
            {
                RunOnUi(() =>
                {
                    IsQrZoomModalVisible = false;
                    CurrentTab = "Receive";
                    IsTransferActive = true;
                    TransferTitle = "Incoming Connection...";
                    TotalProgress = 0;
                    _transferStopwatch.Restart();
                });

                var result = await _transferRuntime.ReceiveAsync(
                    conn,
                    new DesktopReceiveRequest(
                        new QuarantineStagingStore(
                            destinationRoot: ReceiveDirectory),
                        _invitationAuthority,
                        LocalDeviceName: Environment.MachineName,
                        TrustManager: _trustManager),
                    this,
                    cancellationToken);

                RunOnUi(() => StatusMessage = result.Message);
            }
            finally
            {
                RunOnUi(() =>
                {
                    _transferStopwatch.Stop();
                    IsTransferActive = false;
                    IsOfferModalVisible = false;
                    if (IsListening && _listener != null)
                    {
                        RefreshReceiverInvitation();
                    }
                });
            }
        }
    }

    private async Task ExecuteSendFilesAsync()
    {
        if (SelectedFiles.Count == 0) return;
        if (_pairedInvitation == null &&
            !string.IsNullOrWhiteSpace(PeerHost) &&
            PeerHost.Trim().StartsWith("glide://", StringComparison.OrdinalIgnoreCase))
        {
            PairInputUri = PeerHost.Trim();
            ExecuteSubmitPairModal();
        }

        if (_pairedInvitation == null)
        {
            _sendAfterPairing = true;
            StatusMessage = _selectedPeer is null
                ? "On the phone, open Receive files, show its QR code, and copy the invite. Paste it here to continue."
                : $"On {_selectedPeer.DeviceName}, show its receive QR code and copy the invite. Paste it here to continue.";
            ShowPairingSheet();
            return;
        }

        _activeTransferCts?.Dispose();
        _activeTransferCts = new CancellationTokenSource();
        var cancellationToken = _activeTransferCts.Token;

        try
        {
            IsTransferActive = true;
            TransferTitle = $"Connecting to {PeerHost}:{PeerPort}...";
            TotalProgress = 0;
            _transferStopwatch.Restart();

            var files = SelectedFiles
                .Select(item => new DesktopTransferFile(item.FilePath, item.Name))
                .ToList();

            var result = await _transferRuntime.SendAsync(
                new DesktopSendRequest(
                    PeerHost,
                    PeerPort,
                    files,
                    _pairedInvitation,
                    Environment.MachineName,
                    TrustManager: _trustManager),
                this,
                cancellationToken);

            StatusMessage = result.Message;
        }
        finally
        {
            _transferStopwatch.Stop();
            IsTransferActive = false;
            IsOfferModalVisible = false;
            // LAN invitations are short-lived bearer credentials and are consumed by a
            // connection attempt. Never present a stale invitation as ready for another send.
            _pairedInvitation = null;
            PairingStatusText = "Transfer session finished. Use a fresh phone invite to send again.";
            OnPropertyChanged(nameof(SendActionText));
        }
    }

    private async Task ExecuteSendFilesSafelyAsync()
    {
        try
        {
            await ExecuteSendFilesAsync();
        }
        catch (OperationCanceledException)
        {
            StatusMessage = "Transfer cancelled. Your selected files remain in the queue.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Send failed: {ex.Message}";
            MessageBox.Show(
                $"Glide could not send the selected files.\n\n{ex.Message}",
                "Send failed",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
    }

    public async Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken)
    {
        var confirmation =
            new TaskCompletionSource<bool>(
                TaskCreationOptions.RunContinuationsAsynchronously);

        RunOnUi(() =>
        {
            _offerConfirmationTcs = confirmation;
            IncomingOfferPeer = offer.PeerName;
            IncomingOfferSummary =
                $"{offer.Items.Count} item(s) • " +
                $"{TransferItemViewModel.FormatSize(offer.TotalBytes)}";
            TransferTitle =
                $"Incoming transfer from {offer.PeerName}";
            IsOfferModalVisible = true;
        });

        try
        {
            return await confirmation.Task.WaitAsync(
                cancellationToken);
        }
        finally
        {
            RunOnUi(() =>
            {
                if (ReferenceEquals(
                        _offerConfirmationTcs,
                        confirmation))
                {
                    _offerConfirmationTcs = null;
                }

                IsOfferModalVisible = false;
            });
        }
    }

    public async Task<bool> ConfirmPeerEnrollmentAsync(
        VerifiedDesktopPeerIdentity peer,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();

        if (_trustManager == null)
        {
            return false;
        }

        var confirmation =
            new TaskCompletionSource<bool>(
                TaskCreationOptions.RunContinuationsAsynchronously);

        RunOnUi(() =>
        {
            _trustEnrollmentTcs
                ?.TrySetResult(false);
            _trustEnrollmentTcs =
                confirmation;
            TrustEnrollmentPeerName =
                peer.DisplayName;
            TrustEnrollmentFingerprint =
                FormatFingerprint(
                    peer.FingerprintHex);
            TransferTitle =
                $"Verify identity for {peer.DisplayName}";
            IsTrustEnrollmentModalVisible =
                true;
        });

        try
        {
            return await confirmation.Task.WaitAsync(
                cancellationToken);
        }
        finally
        {
            RunOnUi(() =>
            {
                if (ReferenceEquals(
                        _trustEnrollmentTcs,
                        confirmation))
                {
                    _trustEnrollmentTcs =
                        null;
                }

                IsTrustEnrollmentModalVisible =
                    false;
            });
        }
    }

    public void OnInvitationConsumed(DesktopTransferDirection direction)
    {
        RunOnUi(() =>
        {
            if (direction == DesktopTransferDirection.Send)
            {
                _pairedInvitation = null;
                CommandManager.InvalidateRequerySuggested();
            }
            else
            {
                _activeInvitation = null;
                _mdnsService.StopAdvertising();
                ActiveInvitationUri = "";
            }
        });
    }

    public void OnPeerIdentified(
        DesktopTransferDirection direction,
        string peerName)
    {
        RunOnUi(() =>
        {
            TransferTitle =
                direction == DesktopTransferDirection.Receive
                    ? $"Receiving from {peerName}"
                    : $"Sending to {peerName}";
        });
    }

    public void OnStatus(
        DesktopTransferDirection direction,
        string message)
    {
        if (message.Contains(
                "Trusted identity authenticated",
                StringComparison.OrdinalIgnoreCase))
        {
            RefreshTrustSummary();
        }

        RunOnUi(() =>
        {
            StatusMessage = message;
            if (message.Contains(
                "Waiting for peer approval",
                StringComparison.OrdinalIgnoreCase))
            {
                TransferTitle = message;
            }
        });
    }

    public void OnProgress(DesktopTransferProgress progress)
    {
        RunOnUi(() =>
        {
            CurrentFileName = progress.ItemName;
            TotalProgress = progress.Fraction * 100.0;

            double elapsedSeconds = Math.Max(
                0.01,
                _transferStopwatch.Elapsed.TotalSeconds);
            double megabytesPerSecond =
                (progress.TransferredBytes / (1024.0 * 1024.0)) /
                elapsedSeconds;

            TransferRateText = $"{megabytesPerSecond:F1} MB/s";
            TransferredBytesText =
                $"{TransferItemViewModel.FormatSize(progress.TransferredBytes)} / " +
                $"{TransferItemViewModel.FormatSize(progress.TotalBytes)}";
        });
    }

    public void OnItemStatus(
        DesktopTransferDirection direction,
        string itemName,
        string status)
    {
        RunOnUi(() =>
        {
            CurrentFileName = itemName;
            if (direction == DesktopTransferDirection.Send)
            {
                var selected = SelectedFiles.FirstOrDefault(
                    item => string.Equals(
                        item.Name,
                        itemName,
                        StringComparison.Ordinal));
                if (selected != null)
                {
                    selected.Status = status;
                }
            }
        });
    }

    public void OnHistory(DesktopHistoryEntry entry)
    {
        try
        {
            _stateStore.AppendVerifiedHistory(entry);
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                $"Failed to persist transfer history: {ex.Message}");
        }

        RunOnUi(() =>
        {
            HistoryItems.Insert(
                0,
                ToHistoryViewModel(entry));
        });
    }

    private void InitializePersistentTrust()
    {
        try
        {
            _trustManager =
                new DesktopPersistentTrustManager(
                    new DesktopPersistentIdentityStore(),
                    new DesktopTrustStore());

            DesktopTrustInitialization initialization =
                _trustManager.Initialize();

            LocalIdentityFingerprint =
                FormatFingerprint(
                    initialization.LocalFingerprintHex);
            TrustedDeviceCount =
                _trustManager
                    .GetTrustedPeers()
                    .Count;

            if (initialization
                .ResetAfterIdentityLossOrChange)
            {
                StatusMessage =
                    $"Persistent identity changed or was restored without its protected key. " +
                    $"{initialization.RevokedPeerCount} trusted device(s) were revoked.";
            }
        }
        catch (Exception ex)
        {
            _trustManager = null;
            LocalIdentityFingerprint =
                "Unavailable";
            TrustedDeviceCount = 0;
            StatusMessage =
                "Persistent identity is unavailable. " +
                "One-use invitation pairing remains available, but trusted-device mode is disabled. " +
                ex.Message;
        }
    }

    private void RefreshTrustSummary()
    {
        DesktopPersistentTrustManager? manager =
            _trustManager;
        if (manager == null)
        {
            return;
        }

        try
        {
            string fingerprint =
                FormatFingerprint(
                    manager.LocalFingerprintHex());
            int count =
                manager.GetTrustedPeers().Count;

            RunOnUi(() =>
            {
                LocalIdentityFingerprint =
                    fingerprint;
                TrustedDeviceCount =
                    count;
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                $"Failed to refresh persistent trust summary: {ex.Message}");
        }
    }

    private static string FormatFingerprint(
        string fingerprintHex)
    {
        if (string.IsNullOrWhiteSpace(
                fingerprintHex))
        {
            return "Unavailable";
        }

        return string.Join(
            " ",
            Enumerable.Range(
                    0,
                    (fingerprintHex.Length + 7) / 8)
                .Select(index =>
                {
                    int start =
                        index * 8;
                    int length =
                        Math.Min(
                            8,
                            fingerprintHex.Length -
                                start);
                    return fingerprintHex.Substring(
                        start,
                        length);
                }));
    }

    private static HistoryItemViewModel ToHistoryViewModel(
        DesktopHistoryEntry entry) =>
        new()
        {
            Direction =
                entry.Direction == DesktopTransferDirection.Send
                    ? "Sent"
                    : "Received",
            ItemName = entry.ItemName,
            FormattedSize =
                TransferItemViewModel.FormatSize(entry.SizeBytes),
            TargetPath = entry.Path,
            PeerName = entry.PeerName,
            Timestamp = entry.Timestamp.LocalDateTime
        };

    private static void RunOnUi(Action action)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher == null || dispatcher.CheckAccess())
        {
            action();
            return;
        }

        dispatcher.Invoke(action);
    }

    private void ExecuteBrowseReceiveFolder()
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Choose Glide receive folder",
            InitialDirectory =
                Directory.Exists(ReceiveDirectory)
                    ? ReceiveDirectory
                    : Environment.GetFolderPath(
                        Environment.SpecialFolder.UserProfile)
        };

        if (dialog.ShowDialog() == true)
        {
            ReceiveDirectory = dialog.FolderName;
        }
    }

    private void PersistDesktopSettings()
    {
        if (string.IsNullOrWhiteSpace(ReceiveDirectory) ||
            ListenerPortPreference is < 1 or > 65535)
        {
            return;
        }

        try
        {
            _stateStore.SaveSettings(
                new DesktopSettings(
                    ReceiveDirectory,
                    ListenerPortPreference));
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                $"Failed to persist desktop settings: {ex.Message}");
        }
    }

    private void RefreshReceiverInvitation()
    {
        if (_listener == null || !IsListening)
        {
            return;
        }

        string presence =
            LanDiscoveryConstants.GeneratePresenceId();
        byte[] tokenBytes =
            RandomNumberGenerator.GetBytes(32);
        string token =
            Convert.ToBase64String(tokenBytes)
                .TrimEnd('=')
                .Replace('+', '-')
                .Replace('/', '_');
        byte[] spkiHashBytes =
            SHA256.HashData(
                _listener.ServerCertificate
                    .PublicKey
                    .ExportSubjectPublicKeyInfo());
        string spkiSha256 =
            Convert.ToHexString(
                    spkiHashBytes)
                .ToLowerInvariant();
        long expiresAt =
            DateTimeOffset.UtcNow
                .ToUnixTimeSeconds() + 120;

        var preferredLanIps = new List<string>();
        try
        {
            var candidateInterfaces = System.Net.NetworkInformation.NetworkInterface
                .GetAllNetworkInterfaces()
                .Where(ni =>
                    ni.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up &&
                    ni.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback &&
                    ni.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Tunnel &&
                    !ni.Name.Contains("vEthernet", StringComparison.OrdinalIgnoreCase) &&
                    !ni.Name.Contains("WSL", StringComparison.OrdinalIgnoreCase) &&
                    !ni.Description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) &&
                    !ni.Description.Contains("Virtual", StringComparison.OrdinalIgnoreCase) &&
                    !ni.Description.Contains("VMware", StringComparison.OrdinalIgnoreCase) &&
                    !ni.Description.Contains("TAP-", StringComparison.OrdinalIgnoreCase))
                .Select(ni =>
                {
                    var props = ni.GetIPProperties();
                    bool isUsbPeerLink =
                        ni.Description.Contains("Android", StringComparison.OrdinalIgnoreCase) ||
                        ni.Description.Contains("RNDIS", StringComparison.OrdinalIgnoreCase) ||
                        ni.Description.Contains("UsbNcm", StringComparison.OrdinalIgnoreCase);
                    bool hasGateway = props.GatewayAddresses.Any(g =>
                        g.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                        !IPAddress.Any.Equals(g.Address));
                    var ipv4s = props.UnicastAddresses
                        .Where(ua =>
                            ua.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                            !IPAddress.IsLoopback(ua.Address))
                        .Select(ua => ua.Address.ToString())
                        .Where(ip => !ip.StartsWith("169.254.", StringComparison.Ordinal))
                        .ToList();
                    return new { Interface = ni, IsUsbPeerLink = isUsbPeerLink, HasGateway = hasGateway, Ips = ipv4s };
                })
                .Where(x => x.Ips.Count > 0)
                .OrderByDescending(x => x.IsUsbPeerLink)
                .ThenByDescending(x => x.HasGateway)
                .ThenBy(x => x.Interface.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 ? 0 : 1)
                .ToList();

            foreach (var item in candidateInterfaces)
            {
                foreach (var ip in item.Ips)
                {
                    if (!preferredLanIps.Contains(ip, StringComparer.Ordinal))
                    {
                        preferredLanIps.Add(ip);
                    }
                }
            }
        }
        catch
        {
            // Fallback to Dns.GetHostAddresses below
        }

        if (preferredLanIps.Count == 0)
        {
            preferredLanIps = Dns.GetHostAddresses(Dns.GetHostName())
                .Where(ip =>
                    ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                    !IPAddress.IsLoopback(ip))
                .Select(ip => ip.ToString())
                .Where(ip => !ip.StartsWith("169.254.", StringComparison.Ordinal))
                .OrderBy(ip =>
                    ip.StartsWith("192.168.", StringComparison.Ordinal) ? 0 :
                    ip.StartsWith("10.", StringComparison.Ordinal) ? 1 : 2)
                .Distinct(StringComparer.Ordinal)
                .ToList();
        }

        var endpoints =
            preferredLanIps
                .Take(2)
                .Select(ip =>
                    new LanEndpoint(
                        ip,
                        ListeningPort))
                .ToList();
        if (endpoints.Count == 0)
        {
            endpoints.Add(
                new LanEndpoint(
                    "127.0.0.1",
                    ListeningPort));
        }

        var invitation =
            new LanInvitation(
                invitationId:
                    Guid.NewGuid()
                        .ToString("D")
                        .ToLowerInvariant(),
                presence: presence,
                name: Environment.MachineName,
                platform: "windows",
                endpoints: endpoints,
                spkiSha256: spkiSha256,
                token: token,
                expiresAt: expiresAt.ToString(),
                ttlSeconds: 120);

        LanInvitation canonicalInvitation =
            _invitationAuthority.Publish(
                invitation);
        _activeInvitation =
            canonicalInvitation;
        ActiveInvitationUri =
            InvitationCodec.Encode(
                canonicalInvitation);

        _mdnsService.StartAdvertising(
            deviceName:
                Environment.MachineName,
            port: ListeningPort,
            presenceId:
                canonicalInvitation.Presence);
    }

    private void ExecuteOpenDownloads()
    {
        string targetFolder = !string.IsNullOrWhiteSpace(ReceiveDirectory)
            ? ReceiveDirectory
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Glide");

        try
        {
            Directory.CreateDirectory(targetFolder);
            Process.Start(new ProcessStartInfo
            {
                FileName = targetFolder,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            StatusMessage = $"Could not open folder: {ex.Message}";
        }
    }

    private async Task ExecuteStartWebShareAsync()
    {
        if (SelectedFiles.Count == 0)
        {
            StatusMessage = "Please add at least one file to share via Web.";
            return;
        }

        try
        {
            await ExecuteStopWebShareAsync();

            var offeredItems = SelectedFiles
                .Where(f => File.Exists(f.FilePath))
                .Select(f => new OfferedWebItem(f.FilePath))
                .ToList();

            if (offeredItems.Count == 0)
            {
                StatusMessage = "None of the selected files exist on disk.";
                return;
            }

            string hostName = Dns.GetHostName();
            var localIp = Dns.GetHostAddresses(hostName)
                .FirstOrDefault(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))?
                .ToString() ?? "127.0.0.1";

            _webGateway = new GlideWebGateway(
                host: localIp,
                port: 0,
                offeredItems: offeredItems,
                deviceName: Environment.MachineName);

            await _webGateway.StartAsync();

            WebShareUrl = _webGateway.OnboardingUrl;
            IsWebShareModalVisible = true;
            StatusMessage = $"Web Share active on port {_webGateway.Port} ({offeredItems.Count} item(s)).";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Failed to start Web Share: {ex.Message}";
            IsWebShareModalVisible = false;
        }
    }

    private async Task ExecuteStopWebShareAsync()
    {
        if (_webGateway != null)
        {
            try
            {
                await _webGateway.StopAsync();
                await _webGateway.DisposeAsync();
            }
            catch
            {
                // Ignored
            }
            finally
            {
                _webGateway = null;
            }
        }

        IsWebShareModalVisible = false;
        WebShareUrl = "";
        StatusMessage = "Web Share stopped.";
    }
}
