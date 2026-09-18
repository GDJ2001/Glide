using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Glide.Transport.Web;
using Glide.Windows.UI.Common;
using Microsoft.Win32;
using ProtocolViolationException = Glide.Core.Models.ProtocolViolationException;

namespace Glide.Windows.UI.ViewModels;

public class MainViewModel : ViewModelBase, IDesktopTransferInteraction
{
    private string _currentTab = "Send";
    private string _peerHost = "127.0.0.1";
    private int _peerPort;
    private bool _isListening;
    private int _listeningPort;
    private string _statusMessage = "Ready";
    private string _localIpText = "";

    private bool _isSasModalVisible;
    private string _sasCode = "";
    private string _sasPeerName = "";
    private TaskCompletionSource<bool>? _sasConfirmationTcs;

    private bool _isTransferActive;
    private string _transferTitle = "";
    private double _totalProgress;
    private string _transferRateText = "";
    private string _transferredBytesText = "";
    private string _currentFileName = "";
    private CancellationTokenSource? _activeTransferCts;
    private readonly DesktopTransferEngine _desktopTransferEngine = new();
    private readonly Stopwatch _transferStopwatch = new();
    private readonly IDesktopStateStore _stateStore;
    private readonly IUiDispatcher _uiDispatcher;

    private TlsSocketListener? _listener;

    private readonly MdnsDiscoveryService _mdnsService = new();
    public ObservableCollection<DiscoveredPeerViewModel> DiscoveredPeers { get; } = [];

    private bool _isPairModalVisible;
    private string _pairInputUri = "";
    private string _activeInvitationUri = "";
    private LanInvitation? _activeInvitation;
    private LanInvitationAuthority? _activeInvitationAuthority;
    private LanInvitation? _pairedInvitation;

    private bool _isIncomingOfferModalVisible;
    private string _incomingOfferPeerName = "";
    private string _incomingOfferSummary = "";
    private TaskCompletionSource<bool>? _incomingOfferConfirmationTcs;

    private string _deviceName = Environment.MachineName;
    private string _receiveDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        "Downloads");
    private int _historyLimit = 200;

    private GlideWebGateway? _webGateway;
    private bool _isWebShareModalVisible;
    private string _webShareUrl = "";

    private ImageSource? _webShareQrImage;
    private ImageSource? _pairingQrImage;

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
    public ObservableCollection<IncomingOfferItemViewModel> IncomingOfferItems { get; } = [];

    internal Task InitializationTask { get; private set; } = Task.CompletedTask;

    public RelayCommand SwitchTabCommand { get; }
    public RelayCommand AddFilesCommand { get; }
    public RelayCommand RemoveFileCommand { get; }
    public RelayCommand ClearFilesCommand { get; }
    public RelayCommand SendFilesCommand { get; }
    public RelayCommand ToggleListenerCommand { get; }
    public RelayCommand ConfirmSasCommand { get; }
    public RelayCommand RejectSasCommand { get; }
    public RelayCommand CancelTransferCommand { get; }
    public RelayCommand OpenDownloadsCommand { get; }
    public RelayCommand ShowPairModalCommand { get; }
    public RelayCommand CancelPairModalCommand { get; }
    public RelayCommand SubmitPairModalCommand { get; }
    public RelayCommand SelectPeerCommand { get; }
    public RelayCommand RefreshPeersCommand { get; }
    public RelayCommand CopyPairingLinkCommand { get; }
    public RelayCommand StartWebShareCommand { get; }
    public RelayCommand StopWebShareCommand { get; }
    public RelayCommand CopyWebShareLinkCommand { get; }
    public RelayCommand AcceptIncomingOfferCommand { get; }
    public RelayCommand RejectIncomingOfferCommand { get; }
    public RelayCommand SaveSettingsCommand { get; }
    public RelayCommand ClearHistoryCommand { get; }

    public MainViewModel()
        : this(
            new JsonDesktopStateStore(),
            new WpfUiDispatcher(),
            startDiscovery: true)
    {
    }

    internal MainViewModel(
        IDesktopStateStore stateStore,
        IUiDispatcher uiDispatcher,
        bool startDiscovery)
    {
        _stateStore = stateStore
            ?? throw new ArgumentNullException(nameof(stateStore));
        _uiDispatcher = uiDispatcher
            ?? throw new ArgumentNullException(nameof(uiDispatcher));
        SwitchTabCommand = new RelayCommand(param => CurrentTab = param?.ToString() ?? "Send");
        AddFilesCommand = new RelayCommand(ExecuteAddFiles);
        RemoveFileCommand = new RelayCommand(param =>
        {
            if (param is TransferItemViewModel item) SelectedFiles.Remove(item);
        });
        ClearFilesCommand = new RelayCommand(() => SelectedFiles.Clear());
        SendFilesCommand = new RelayCommand(async () => await ExecuteSendFilesAsync(), () => !IsTransferActive && SelectedFiles.Count > 0);
        ToggleListenerCommand = new RelayCommand(async () => await ToggleListenerAsync());
        ConfirmSasCommand = new RelayCommand(() => _sasConfirmationTcs?.TrySetResult(true));
        RejectSasCommand = new RelayCommand(() => _sasConfirmationTcs?.TrySetResult(false));
        CancelTransferCommand = new RelayCommand(() => _activeTransferCts?.Cancel());
        OpenDownloadsCommand = new RelayCommand(ExecuteOpenDownloads);
        ShowPairModalCommand = new RelayCommand(() => IsPairModalVisible = true);
        CancelPairModalCommand = new RelayCommand(() => { IsPairModalVisible = false; PairInputUri = ""; });
        SubmitPairModalCommand = new RelayCommand(ExecuteSubmitPairModal);
        SelectPeerCommand = new RelayCommand(param =>
        {
            if (param is DiscoveredPeerViewModel peer)
            {
                PeerHost = peer.Host;
                PeerPort = peer.Port;
                _pairedInvitation = null;
                StatusMessage = $"Discovered {peer.DeviceName} ({peer.Host}:{peer.Port}). Scan/paste its pairing invitation before sending.";
            }
        });
        RefreshPeersCommand = new RelayCommand(() =>
        {
            _mdnsService.QueryPeers();
            StatusMessage = "Scanning for nearby Glide devices...";
        });
        CopyPairingLinkCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrEmpty(ActiveInvitationUri))
            {
                Clipboard.SetText(ActiveInvitationUri);
                StatusMessage = "Pairing link copied to clipboard!";
            }
        });

        StartWebShareCommand = new RelayCommand(async () => await ExecuteStartWebShareAsync(), () => !IsTransferActive && SelectedFiles.Count > 0);
        StopWebShareCommand = new RelayCommand(async () => await ExecuteStopWebShareAsync());
        CopyWebShareLinkCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrEmpty(WebShareUrl))
            {
                Clipboard.SetText(WebShareUrl);
                StatusMessage = "Web Share link copied to clipboard!";
            }
        });

        AcceptIncomingOfferCommand =
            new RelayCommand(
                () => _incomingOfferConfirmationTcs?.TrySetResult(true),
                () => IsIncomingOfferModalVisible);
        RejectIncomingOfferCommand =
            new RelayCommand(
                () => _incomingOfferConfirmationTcs?.TrySetResult(false),
                () => IsIncomingOfferModalVisible);
        SaveSettingsCommand =
            new RelayCommand(async () => await ExecuteSaveSettingsAsync());
        ClearHistoryCommand =
            new RelayCommand(async () => await ExecuteClearHistoryAsync());

        SelectedFiles.CollectionChanged += (_, _) =>
            CommandManager.InvalidateRequerySuggested();

        _mdnsService.PeerDiscovered += peer =>
        {
            RunOnUi(() =>
            {
                if (!DiscoveredPeers.Any(
                        p => p.Host == peer.Host &&
                             p.Port == peer.Port))
                {
                    DiscoveredPeers.Add(
                        new DiscoveredPeerViewModel(peer));
                }
            });
        };

        _mdnsService.PeerLost += peer =>
        {
            RunOnUi(() =>
            {
                var existing = DiscoveredPeers.FirstOrDefault(
                    p => p.Host == peer.Host &&
                         p.Port == peer.Port);
                if (existing != null)
                {
                    DiscoveredPeers.Remove(existing);
                }
            });
        };

        InitializationTask = InitializePersistentStateAsync();

        if (startDiscovery)
        {
            _mdnsService.Start(enableBrowsing: true);
            RefreshNetworkInfo();
        }
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

    public bool IsSasModalVisible
    {
        get => _isSasModalVisible;
        set => SetProperty(ref _isSasModalVisible, value);
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

    public string SasCode
    {
        get => _sasCode;
        set => SetProperty(ref _sasCode, value);
    }

    public string SasPeerName
    {
        get => _sasPeerName;
        set => SetProperty(ref _sasPeerName, value);
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

    public bool IsIncomingOfferModalVisible
    {
        get => _isIncomingOfferModalVisible;
        set
        {
            if (SetProperty(ref _isIncomingOfferModalVisible, value))
            {
                CommandManager.InvalidateRequerySuggested();
            }
        }
    }

    public string IncomingOfferPeerName
    {
        get => _incomingOfferPeerName;
        set => SetProperty(ref _incomingOfferPeerName, value);
    }

    public string IncomingOfferSummary
    {
        get => _incomingOfferSummary;
        set => SetProperty(ref _incomingOfferSummary, value);
    }

    public string DeviceName
    {
        get => _deviceName;
        set => SetProperty(ref _deviceName, value);
    }

    public string ReceiveDirectory
    {
        get => _receiveDirectory;
        set => SetProperty(ref _receiveDirectory, value);
    }

    public int HistoryLimit
    {
        get => _historyLimit;
        set => SetProperty(ref _historyLimit, value);
    }

    private async Task InitializePersistentStateAsync()
    {
        try
        {
            DesktopPersistentState state =
                await _stateStore.LoadAsync();

            await _uiDispatcher.InvokeAsync(() =>
            {
                DeviceName = state.Settings.DeviceName;
                ReceiveDirectory = state.Settings.ReceiveDirectory;
                HistoryLimit = state.Settings.HistoryLimit;

                HistoryItems.Clear();
                foreach (DesktopHistoryEntry entry in state.History)
                {
                    HistoryItems.Add(CreateHistoryItem(entry));
                }
            });
        }
        catch (Exception ex)
        {
            await _uiDispatcher.InvokeAsync(() =>
            {
                StatusMessage =
                    $"Desktop state could not be loaded: {ex.Message}";
            });
        }
    }

    internal async Task ExecuteSaveSettingsAsync()
    {
        await InitializationTask;

        try
        {
            var settings = new DesktopSettings(
                DeviceName,
                ReceiveDirectory,
                HistoryLimit).Validate();

            await _stateStore.SaveSettingsAsync(settings);

            await _uiDispatcher.InvokeAsync(() =>
            {
                DeviceName = settings.DeviceName;
                ReceiveDirectory = settings.ReceiveDirectory;
                HistoryLimit = settings.HistoryLimit;

                while (HistoryItems.Count > settings.HistoryLimit)
                {
                    HistoryItems.RemoveAt(HistoryItems.Count - 1);
                }

                StatusMessage = IsListening
                    ? "Settings saved. Restart listening to advertise the new device name."
                    : "Settings saved.";
            });
        }
        catch (Exception ex)
        {
            await _uiDispatcher.InvokeAsync(() =>
            {
                StatusMessage =
                    $"Settings were not saved: {ex.Message}";
            });
        }
    }

    internal async Task ExecuteClearHistoryAsync()
    {
        await InitializationTask;

        try
        {
            await _stateStore.ClearHistoryAsync();

            await _uiDispatcher.InvokeAsync(() =>
            {
                HistoryItems.Clear();
                StatusMessage = "Transfer history cleared.";
            });
        }
        catch (Exception ex)
        {
            await _uiDispatcher.InvokeAsync(() =>
            {
                StatusMessage =
                    $"Transfer history could not be cleared: {ex.Message}";
            });
        }
    }

    private static HistoryItemViewModel CreateHistoryItem(
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

    public void AddDroppedFiles(string[] filePaths)
    {
        foreach (var path in filePaths)
        {
            if (File.Exists(path) && !SelectedFiles.Any(f => f.FilePath.Equals(path, StringComparison.OrdinalIgnoreCase)))
            {
                SelectedFiles.Add(new TransferItemViewModel(path));
            }
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

    private void ExecuteSubmitPairModal()
    {
        if (string.IsNullOrWhiteSpace(PairInputUri)) return;

        try
        {
            var invitation = InvitationCodec.Decode(PairInputUri.Trim());
            if (invitation.IsExpired())
            {
                MessageBox.Show("This pairing invitation has expired. Please ask the receiver to generate a new pairing link.", "Pairing Expired", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var targetEndpoint = invitation.Endpoints.FirstOrDefault();
            if (targetEndpoint != null)
            {
                PeerHost = targetEndpoint.Address;
                PeerPort = targetEndpoint.Port;
                _pairedInvitation = invitation;
                StatusMessage = $"Paired with {invitation.Name} ({targetEndpoint.Address}:{targetEndpoint.Port}) via invitation";
                IsPairModalVisible = false;
                PairInputUri = "";
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Invalid pairing invitation link:\n\n{ex.Message}", "Pairing Failed", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private async Task ToggleListenerAsync()
    {
        await InitializationTask;

        if (IsListening)
        {
            _mdnsService.StopAdvertising();
            ActiveInvitationUri = "";
            _activeInvitationAuthority?.Revoke();
            _activeInvitationAuthority = null;
            _activeInvitation = null;
            _listener?.Dispose();
            _listener = null;
            IsListening = false;
            StatusMessage = "Listener stopped";
            return;
        }

        try
        {
            _listener = new TlsSocketListener(0);
        }
        catch
        {
            _listener = new TlsSocketListener(0);
        }

        ListeningPort = _listener.BoundPort;
        IsListening = true;

        try
        {
            string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
            string token = Convert.ToBase64String(tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            byte[] spkiHashBytes = SHA256.HashData(_listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
            string spkiSha256 = Convert.ToHexString(spkiHashBytes).ToLowerInvariant();
            long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120;

            var localIps = Dns.GetHostAddresses(Dns.GetHostName())
                .Where(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                .Select(ip => ip.ToString())
                .ToList();

            var endpoints = localIps.Select(ip => new LanEndpoint(ip, ListeningPort)).Take(4).ToList();
            if (endpoints.Count == 0)
            {
                endpoints.Add(new LanEndpoint("127.0.0.1", ListeningPort));
            }

            var invitation = new LanInvitation(
                invitationId: Guid.NewGuid().ToString().ToLowerInvariant(),
                presence: presence,
                name: DeviceName,
                platform: "windows",
                endpoints: endpoints,
                spkiSha256: spkiSha256,
                token: token,
                expiresAt: expiresAt.ToString(),
                ttlSeconds: 120);

            ActiveInvitationUri = InvitationCodec.Encode(invitation);
            _activeInvitation = InvitationCodec.Decode(ActiveInvitationUri);
            _activeInvitationAuthority = new LanInvitationAuthority(_activeInvitation);

            _mdnsService.StartAdvertising(
                deviceName: DeviceName,
                port: ListeningPort,
                presenceId: presence);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to generate pairing invitation: {ex.Message}");
        }

        StatusMessage = $"Listening on port {ListeningPort}";
        _ = Task.Run(async () => await RunListenerLoopAsync());
    }

    private async Task RunListenerLoopAsync()
    {
        while (IsListening && _listener != null)
        {
            try
            {
                var conn = await _listener.AcceptConnectionAsync();
                _ = Task.Run(async () => await HandleInboundTransferAsync(conn));
            }
            catch
            {
                break;
            }
        }
    }

    private async Task HandleInboundTransferAsync(TlsSocketConnection conn)
    {
        await InitializationTask;

        await using (conn)
        {
            _activeTransferCts?.Dispose();
            _activeTransferCts = new CancellationTokenSource();
            var cancellationToken = _activeTransferCts.Token;

            try
            {
                RunOnUi(() =>
                {
                    IsTransferActive = true;
                    TransferTitle = "Incoming Connection...";
                    TotalProgress = 0;
                    _transferStopwatch.Restart();
                });

                var result = await _desktopTransferEngine.ReceiveAsync(
                    conn,
                    new DesktopReceiveRequest(
                        new QuarantineStagingStore(
                            destinationRoot: ReceiveDirectory),
                        _activeInvitationAuthority,
                        LocalDeviceName: DeviceName),
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
                    IsSasModalVisible = false;
                });
            }
        }
    }

private async Task ExecuteSendFilesAsync()
    {
        await InitializationTask;

        if (SelectedFiles.Count == 0) return;

        if (_pairedInvitation == null)
        {
            MessageBox.Show(
                "A fresh QR/pairing invitation is required before sending over LAN. Discovery only identifies a nearby endpoint and is not authentication.",
                "Pairing Required",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
            StatusMessage = "Pairing invitation required before sending.";
            IsPairModalVisible = true;
            return;
        }

        if (_pairedInvitation.IsExpired())
        {
            _pairedInvitation = null;
            MessageBox.Show(
                "The pairing invitation has expired. Ask the receiver to generate a new one.",
                "Pairing Expired",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            StatusMessage = "Pairing invitation expired.";
            IsPairModalVisible = true;
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
                .Select(item => new DesktopTransferFile(item.FilePath))
                .ToList();

            var result = await _desktopTransferEngine.SendAsync(
                new DesktopSendRequest(
                    PeerHost,
                    PeerPort,
                    files,
                    _pairedInvitation,
                    DeviceName),
                this,
                cancellationToken);

            StatusMessage = result.Message;
        }
        finally
        {
            _transferStopwatch.Stop();
            IsTransferActive = false;
            IsSasModalVisible = false;
        }
    }

    public async Task<bool> ConfirmSasAsync(
        DesktopTransferDirection direction,
        string peerName,
        int sixDigitCode,
        CancellationToken cancellationToken)
    {
        var confirmation = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);

        RunOnUi(() =>
        {
            _sasConfirmationTcs = confirmation;
            SasCode = sixDigitCode.ToString("D6");
            SasPeerName = peerName;
            IsSasModalVisible = true;
        });

        try
        {
            return await confirmation.Task.WaitAsync(cancellationToken);
        }
        finally
        {
            RunOnUi(() =>
            {
                if (ReferenceEquals(_sasConfirmationTcs, confirmation))
                {
                    _sasConfirmationTcs = null;
                }

                IsSasModalVisible = false;
            });
        }
    }

    public async Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(offer);

        var confirmation =
            new TaskCompletionSource<bool>(
                TaskCreationOptions.RunContinuationsAsynchronously);

        await _uiDispatcher.InvokeAsync(() =>
        {
            _incomingOfferConfirmationTcs = confirmation;
            IncomingOfferPeerName = offer.PeerName;
            IncomingOfferSummary =
                $"{offer.Items.Count} file(s) • " +
                TransferItemViewModel.FormatSize(offer.TotalBytes);

            IncomingOfferItems.Clear();
            foreach (DesktopOfferItem item in offer.Items)
            {
                IncomingOfferItems.Add(
                    new IncomingOfferItemViewModel(
                        item.Name,
                        item.SizeBytes,
                        item.RelativePath));
            }

            TransferTitle =
                $"Incoming offer from {offer.PeerName}";
            IsIncomingOfferModalVisible = true;
        });

        try
        {
            return await confirmation.Task.WaitAsync(
                cancellationToken);
        }
        finally
        {
            await _uiDispatcher.InvokeAsync(() =>
            {
                if (ReferenceEquals(
                        _incomingOfferConfirmationTcs,
                        confirmation))
                {
                    _incomingOfferConfirmationTcs = null;
                }

                IsIncomingOfferModalVisible = false;
                IncomingOfferItems.Clear();
                IncomingOfferSummary = "";
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
            }
            else
            {
                _activeInvitationAuthority = null;
                _activeInvitation = null;
                ActiveInvitationUri = "";
            }
        });
    }

    public void OnPeerIdentified(
        DesktopTransferDirection direction,
        string peerName)
    {
        RunOnUi(() => SasPeerName = peerName);
    }

    public void OnStatus(
        DesktopTransferDirection direction,
        string message)
    {
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

    public async Task OnHistoryAsync(
        DesktopHistoryEntry entry,
        CancellationToken cancellationToken = default)
    {
        bool persisted = true;

        try
        {
            await _stateStore.AppendHistoryAsync(
                entry,
                cancellationToken);
        }
        catch
        {
            persisted = false;
        }

        await _uiDispatcher.InvokeAsync(() =>
        {
            HistoryItems.Insert(
                0,
                CreateHistoryItem(entry));

            while (HistoryItems.Count > HistoryLimit)
            {
                HistoryItems.RemoveAt(
                    HistoryItems.Count - 1);
            }

            if (!persisted)
            {
                StatusMessage =
                    "Transfer verified, but history could not be saved.";
            }
        });
    }

    private void RunOnUi(Action action) =>
        _uiDispatcher.Invoke(action);

private void ExecuteOpenDownloads()
    {
        string downloads = ReceiveDirectory;
        if (Directory.Exists(downloads))
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = downloads,
                UseShellExecute = true
            });
        }
    }

    private async Task ExecuteStartWebShareAsync()
    {
        await InitializationTask;

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
                deviceName: DeviceName);

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
