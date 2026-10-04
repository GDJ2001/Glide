using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using Avalonia;
using Avalonia.Threading;
using Avalonia.Media.Imaging;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Linux.UI.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Sockets;

namespace Glide.Linux.UI.ViewModels;

public enum LinuxPage
{
    Send,
    Receive,
    Pair,
    Activity,
    Settings
}

public enum SendMethod
{
    QuickShareQr,
    NearbyDevice,
    SavedDevice
}

public enum ActivityFilter
{
    All,
    Sent,
    Received
}

public enum AppThemePreference
{
    System,
    Light,
    Dark
}

public sealed class LinuxMainViewModel : ViewModelBase, IDesktopTransferInteraction, IDisposable
{
    private LinuxPage _activePage = LinuxPage.Send;
    private SendMethod _selectedSendMethod = SendMethod.QuickShareQr;
    private ActivityFilter _selectedActivityFilter = ActivityFilter.All;
    private string _activitySearchQuery = "";
    private AppThemePreference _currentThemePreference = AppThemePreference.System;

    private string _deviceName = Environment.MachineName;
    private string _downloadDirectory = LinuxEnvironment.ResolveDownloadsDirectory();
    private int _listeningPort = LanDiscoveryConstants.DefaultPort;
    private bool _isListening = false;
    private string _statusMessage = "Ready to share files nearby.";

    // Active transfer properties
    private bool _isTransferActive = false;
    private string _transferTitle = "";
    private double _transferProgressFraction = 0;
    private string _transferRateText = "";
    private string _transferredBytesText = "";
    private string _currentTransferFile = "";
    private CancellationTokenSource? _activeTransferCts;
    private readonly DesktopTransferRateCalculator _rateCalculator = new();

    // SAS Modal
    private bool _isSasModalVisible = false;
    private string _sasPeerName = "";
    private string _sasCode = "";
    private TaskCompletionSource<bool>? _sasTcs;

    // Incoming Offer Modal
    private bool _isOfferModalVisible = false;
    private string _offerPeerName = "";
    private string _offerSummaryText = "";
    private TaskCompletionSource<bool>? _offerTcs;

    // Sender-hosted QR still requires the local user to approve the recipient.
    private bool _isRecipientModalVisible;
    private string _recipientPeerName = "";
    private TaskCompletionSource<bool>? _recipientTcs;

    // Invitation pairing
    private string _invitationInput = "";
    private string _currentInvitationUri = "";
    private Bitmap? _pairingQrImage;
    private DesktopSendRequest? _hostedSendRequest;

    // Services
    private readonly DesktopTransferRuntime _runtime = new();
    private readonly MdnsDiscoveryService _mdnsDiscovery = new();
    private readonly DesktopListenerSupervisor _listenerSupervisor = new();
    private DesktopListenerOwnership? _listenerOwner;
    private readonly DesktopInvitationManager _invitationManager = new();
    private readonly CancellationTokenSource _lifetimeCts = new();
    private readonly DesktopStateStore _stateStore = new(Path.Combine(LinuxEnvironment.DataHome, "desktop-state.json"));
    private readonly DesktopPersistentTrustManager? _trustManager;

    public ObservableCollection<LinuxTransferItemViewModel> StagedItems { get; } = [];
    public ObservableCollection<LinuxDiscoveredPeerViewModel> DiscoveredPeers { get; } = [];
    public ObservableCollection<LinuxHistoryItemViewModel> HistoryItems { get; } = [];
    public ObservableCollection<DesktopTrustedPeer> TrustedPeers { get; } = [];

    private LinuxDiscoveredPeerViewModel? _selectedPeer;
    private DesktopTrustedPeer? _selectedTrustedPeer;

    public DesktopTrustedPeer? SelectedTrustedPeer
    {
        get => _selectedTrustedPeer;
        set => SetProperty(ref _selectedTrustedPeer, value);
    }

    public LinuxDiscoveredPeerViewModel? SelectedPeer
    {
        get => _selectedPeer;
        set => SetProperty(ref _selectedPeer, value);
    }

    public LinuxPage ActivePage
    {
        get => _activePage;
        set
        {
            if (SetProperty(ref _activePage, value))
            {
                OnPropertyChanged(nameof(CurrentTab));
                OnPropertyChanged(nameof(IsSendActive));
                OnPropertyChanged(nameof(IsReceiveActive));
                OnPropertyChanged(nameof(IsPairActive));
                OnPropertyChanged(nameof(IsActivityActive));
                OnPropertyChanged(nameof(IsSettingsActive));
            }
        }
    }

    public string CurrentTab
    {
        get => _activePage.ToString();
        set
        {
            if (Enum.TryParse<LinuxPage>(value, true, out var page))
            {
                ActivePage = page;
            }
        }
    }

    public bool IsSendActive => _activePage == LinuxPage.Send;
    public bool IsReceiveActive => _activePage == LinuxPage.Receive;
    public bool IsPairActive => _activePage == LinuxPage.Pair;
    public bool IsActivityActive => _activePage == LinuxPage.Activity;
    public bool IsSettingsActive => _activePage == LinuxPage.Settings;

    public SendMethod SelectedSendMethod
    {
        get => _selectedSendMethod;
        set
        {
            if (SetProperty(ref _selectedSendMethod, value))
            {
                OnPropertyChanged(nameof(IsQuickShareMethodSelected));
                OnPropertyChanged(nameof(IsNearbyMethodSelected));
                OnPropertyChanged(nameof(IsSavedMethodSelected));
            }
        }
    }

    public bool IsQuickShareMethodSelected => _selectedSendMethod == SendMethod.QuickShareQr;
    public bool IsNearbyMethodSelected => _selectedSendMethod == SendMethod.NearbyDevice;
    public bool IsSavedMethodSelected => _selectedSendMethod == SendMethod.SavedDevice;

    public bool HasStagedItems => StagedItems.Count > 0;
    public bool HasNoStagedItems => StagedItems.Count == 0;

    public bool AutoConfirmForTesting { get; set; }

    public string DeviceName
    {
        get => _deviceName;
        set => SetProperty(ref _deviceName, value);
    }

    public string DownloadDirectory
    {
        get => _downloadDirectory;
        set
        {
            if (SetProperty(ref _downloadDirectory, value) && !string.IsNullOrWhiteSpace(value))
                _stateStore.SaveSettings(new DesktopSettings(value, ListeningPort));
        }
    }

    public int ListeningPort
    {
        get => _listeningPort;
        set
        {
            if (SetProperty(ref _listeningPort, value) && value is > 0 and <= 65535)
                _stateStore.SaveSettings(new DesktopSettings(DownloadDirectory, value));
        }
    }

    public bool IsListening
    {
        get => _isListening;
        set
        {
            if (SetProperty(ref _isListening, value))
            {
                OnPropertyChanged(nameof(ReceivingButtonText));
                OnPropertyChanged(nameof(ReceivingStatusText));
                OnPropertyChanged(nameof(ReceivingStatusDetail));
                OnPropertyChanged(nameof(ReceivingStatusActive));
            }
        }
    }

    public string ReceivingButtonText => IsListening ? "Turn off receiving" : "Turn on receiving";
    public string ReceivingStatusText => IsListening ? "Ready to receive" : "Receiving is off";
    public string ReceivingStatusDetail => IsListening
        ? $"Listening on port {ListeningPort} as {DeviceName}"
        : "Turn on receiving to accept incoming transfers";
    public bool ReceivingStatusActive => IsListening;

    public AppThemePreference CurrentThemePreference
    {
        get => _currentThemePreference;
        set
        {
            if (SetProperty(ref _currentThemePreference, value))
            {
                ApplyTheme(value);
            }
        }
    }

    private static void ApplyTheme(AppThemePreference preference)
    {
        if (Application.Current == null) return;
        Application.Current.RequestedThemeVariant = preference switch
        {
            AppThemePreference.Light => Avalonia.Styling.ThemeVariant.Light,
            AppThemePreference.Dark => Avalonia.Styling.ThemeVariant.Dark,
            _ => Avalonia.Styling.ThemeVariant.Default
        };
    }

    public ActivityFilter SelectedActivityFilter
    {
        get => _selectedActivityFilter;
        set
        {
            if (SetProperty(ref _selectedActivityFilter, value))
            {
                OnPropertyChanged(nameof(FilteredHistoryItems));
            }
        }
    }

    public string ActivitySearchQuery
    {
        get => _activitySearchQuery;
        set
        {
            if (SetProperty(ref _activitySearchQuery, value))
            {
                OnPropertyChanged(nameof(FilteredHistoryItems));
            }
        }
    }

    public IEnumerable<LinuxHistoryItemViewModel> FilteredHistoryItems
    {
        get
        {
            var query = HistoryItems.AsEnumerable();
            if (SelectedActivityFilter == ActivityFilter.Sent)
                query = query.Where(h => string.Equals(h.Direction, "Send", StringComparison.OrdinalIgnoreCase) || string.Equals(h.Direction, "Sent", StringComparison.OrdinalIgnoreCase));
            else if (SelectedActivityFilter == ActivityFilter.Received)
                query = query.Where(h => string.Equals(h.Direction, "Receive", StringComparison.OrdinalIgnoreCase) || string.Equals(h.Direction, "Received", StringComparison.OrdinalIgnoreCase));

            if (!string.IsNullOrWhiteSpace(ActivitySearchQuery))
            {
                string search = ActivitySearchQuery.Trim();
                query = query.Where(h =>
                    (h.PeerName?.Contains(search, StringComparison.OrdinalIgnoreCase) ?? false) ||
                    (h.ItemName?.Contains(search, StringComparison.OrdinalIgnoreCase) ?? false));
            }

            return query.ToList();
        }
    }

    public string AppVersionText => typeof(LinuxMainViewModel).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";

    public bool IsAnyModalVisible => IsSasModalVisible || IsOfferModalVisible || IsRecipientModalVisible || IsQuickShareActive;

    public string StatusMessage
    {
        get => _statusMessage;
        set => SetProperty(ref _statusMessage, value);
    }

    public bool IsTransferActive
    {
        get => _isTransferActive;
        set
        {
            if (SetProperty(ref _isTransferActive, value))
            {
                SendFilesCommand.RaiseCanExecuteChanged();
                StartQuickShareCommand.RaiseCanExecuteChanged();
                ReceiveFromInvitationCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public string TransferTitle
    {
        get => _transferTitle;
        set => SetProperty(ref _transferTitle, value);
    }

    public double TransferProgressFraction
    {
        get => _transferProgressFraction;
        set
        {
            if (SetProperty(ref _transferProgressFraction, value))
            {
                OnPropertyChanged(nameof(TransferProgressPercent));
            }
        }
    }

    public int TransferProgressPercent => (int)(_transferProgressFraction * 100);

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

    public string CurrentTransferFile
    {
        get => _currentTransferFile;
        set => SetProperty(ref _currentTransferFile, value);
    }

    public bool IsSasModalVisible
    {
        get => _isSasModalVisible;
        set
        {
            if (SetProperty(ref _isSasModalVisible, value))
                OnPropertyChanged(nameof(IsAnyModalVisible));
        }
    }

    public string SasPeerName
    {
        get => _sasPeerName;
        set => SetProperty(ref _sasPeerName, value);
    }

    public string SasCode
    {
        get => _sasCode;
        set => SetProperty(ref _sasCode, value);
    }

    public bool IsOfferModalVisible
    {
        get => _isOfferModalVisible;
        set
        {
            if (SetProperty(ref _isOfferModalVisible, value))
                OnPropertyChanged(nameof(IsAnyModalVisible));
        }
    }

    public string OfferPeerName
    {
        get => _offerPeerName;
        set => SetProperty(ref _offerPeerName, value);
    }

    public string OfferSummaryText
    {
        get => _offerSummaryText;
        set => SetProperty(ref _offerSummaryText, value);
    }

    public bool IsRecipientModalVisible
    {
        get => _isRecipientModalVisible;
        set
        {
            if (SetProperty(ref _isRecipientModalVisible, value))
                OnPropertyChanged(nameof(IsAnyModalVisible));
        }
    }

    public string RecipientPeerName
    {
        get => _recipientPeerName;
        set => SetProperty(ref _recipientPeerName, value);
    }

    public string InvitationInput
    {
        get => _invitationInput;
        set
        {
            if (SetProperty(ref _invitationInput, value))
            {
                SendFilesCommand.RaiseCanExecuteChanged();
                ReceiveFromInvitationCommand.RaiseCanExecuteChanged();
            }
        }
    }

    public string CurrentInvitationUri
    {
        get => _currentInvitationUri;
        set
        {
            if (SetProperty(ref _currentInvitationUri, value))
            {
                PairingQrImage?.Dispose();
                PairingQrImage = string.IsNullOrWhiteSpace(value)
                    ? null : LinuxQrImageHelper.Create(value);
            }
        }
    }

    public Bitmap? PairingQrImage
    {
        get => _pairingQrImage;
        private set => SetProperty(ref _pairingQrImage, value);
    }

    public bool IsQuickShareActive => _hostedSendRequest != null;

    public int StagedCount => StagedItems.Count;
    public long TotalStagedBytes => StagedItems.Sum(item => item.Size);
    public string FormattedTotalStagedBytes => FormatBytes(TotalStagedBytes);

    // Commands
    public RelayCommand SwitchTabCommand { get; }
    public RelayCommand RemoveStagedItemCommand { get; }
    public RelayCommand ClearStagedItemsCommand { get; }
    public AsyncRelayCommand SendFilesCommand { get; }
    public AsyncRelayCommand ReceiveFromInvitationCommand { get; }
    public AsyncRelayCommand ToggleListenerCommand { get; }
    public RelayCommand CancelTransferCommand { get; }
    public RelayCommand AcceptSasCommand { get; }
    public RelayCommand DeclineSasCommand { get; }
    public RelayCommand AcceptOfferCommand { get; }
    public RelayCommand DeclineOfferCommand { get; }
    public RelayCommand AcceptRecipientCommand { get; }
    public RelayCommand DeclineRecipientCommand { get; }
    public RelayCommand RefreshInvitationCommand { get; }
    public AsyncRelayCommand StartQuickShareCommand { get; }
    public RelayCommand CancelQuickShareCommand { get; }
    public RelayCommand OpenDownloadsDirectoryCommand { get; }
    public RelayCommand ClearHistoryCommand { get; }
    public RelayCommand RevokeTrustedPeerCommand { get; }
    public RelayCommand DismissActiveModalCommand { get; }
    public RelayCommand SelectSendMethodCommand { get; }
    public RelayCommand SelectActivityFilterCommand { get; }
    public RelayCommand SetThemeCommand { get; }

    public LinuxMainViewModel()
    {
        HistoryItems.CollectionChanged += (_, _) => OnPropertyChanged(nameof(FilteredHistoryItems));

        DismissActiveModalCommand = new RelayCommand(() =>
        {
            if (IsSasModalVisible) ResolveSas(false);
            else if (IsOfferModalVisible) ResolveOffer(false);
            else if (IsRecipientModalVisible) ResolveRecipient(false);
            else if (IsQuickShareActive)
            {
                _hostedSendRequest = null;
                OnPropertyChanged(nameof(IsQuickShareActive));
                OnPropertyChanged(nameof(IsAnyModalVisible));
                if (IsListening) RefreshInvitation();
            }
        });

        SelectSendMethodCommand = new RelayCommand(p =>
        {
            if (p is SendMethod method) SelectedSendMethod = method;
            else if (p is string s && Enum.TryParse<SendMethod>(s, true, out var parsed))
                SelectedSendMethod = parsed;
        });

        SelectActivityFilterCommand = new RelayCommand(p =>
        {
            if (p is ActivityFilter filter) SelectedActivityFilter = filter;
            else if (p is string s && Enum.TryParse<ActivityFilter>(s, true, out var parsed))
                SelectedActivityFilter = parsed;
        });

        SetThemeCommand = new RelayCommand(p =>
        {
            if (p is AppThemePreference theme) CurrentThemePreference = theme;
            else if (p is string s && Enum.TryParse<AppThemePreference>(s, true, out var parsed))
                CurrentThemePreference = parsed;
        });

        if (OperatingSystem.IsLinux())
        {
            _trustManager = new DesktopPersistentTrustManager(
                new DesktopPersistentIdentityStore(), new DesktopTrustStore());
            _trustManager.Initialize();
            RefreshTrustedPeers();
        }
        DesktopPersistentState persisted = _stateStore.Load();
        _downloadDirectory = persisted.Settings.ReceiveDirectory;
        _listeningPort = persisted.Settings.ListenerPort;
        foreach (DesktopHistoryEntry entry in persisted.History)
            HistoryItems.Add(ToHistoryItem(entry));

        SwitchTabCommand = new RelayCommand(p =>
        {
            if (p is string tab) CurrentTab = tab;
        });

        RemoveStagedItemCommand = new RelayCommand(p =>
        {
            if (p is LinuxTransferItemViewModel item)
            {
                RemoveStagedItem(item);
            }
        });

        ClearStagedItemsCommand = new RelayCommand(() =>
        {
            StagedItems.Clear();
            NotifyStagingChanged();
        });

        SendFilesCommand = new AsyncRelayCommand(ExecuteSendAsync,
            () => StagedItems.Count > 0 && !IsTransferActive && !string.IsNullOrWhiteSpace(InvitationInput));
        ReceiveFromInvitationCommand = new AsyncRelayCommand(ExecuteReceiveFromInvitationAsync, () => !string.IsNullOrWhiteSpace(InvitationInput) && !IsTransferActive);
        ToggleListenerCommand = new AsyncRelayCommand(ExecuteToggleListenerAsync);
        CancelTransferCommand = new RelayCommand(ExecuteCancelTransfer);

        AcceptSasCommand = new RelayCommand(() => ResolveSas(true));
        DeclineSasCommand = new RelayCommand(() => ResolveSas(false));
        AcceptOfferCommand = new RelayCommand(() => ResolveOffer(true));
        DeclineOfferCommand = new RelayCommand(() => ResolveOffer(false));
        AcceptRecipientCommand = new RelayCommand(() => ResolveRecipient(true));
        DeclineRecipientCommand = new RelayCommand(() => ResolveRecipient(false));
        RefreshInvitationCommand = new RelayCommand(RefreshInvitation);
        StartQuickShareCommand = new AsyncRelayCommand(ExecuteStartQuickShareAsync,
            () => StagedItems.Count > 0 && !IsTransferActive);
        CancelQuickShareCommand = new RelayCommand(() =>
        {
            _hostedSendRequest = null;
            OnPropertyChanged(nameof(IsQuickShareActive));
            OnPropertyChanged(nameof(IsAnyModalVisible));
            if (IsListening) RefreshInvitation();
        });

        OpenDownloadsDirectoryCommand = new RelayCommand(() =>
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "xdg-open",
                    ArgumentList = { DownloadDirectory },
                    UseShellExecute = true
                });
            }
            catch { }
        });

        ClearHistoryCommand = new RelayCommand(() =>
        {
            _stateStore.ClearHistory();
            HistoryItems.Clear();
        });
        RevokeTrustedPeerCommand = new RelayCommand(() =>
        {
            if (SelectedTrustedPeer == null || _trustManager == null) return;
            string fingerprint = SelectedTrustedPeer.FingerprintHex;
            if (_trustManager.RevokePeer(fingerprint))
            {
                SelectedTrustedPeer = null;
                RefreshTrustedPeers();
                StatusMessage = "Saved device removed. A new authenticated pairing is required.";
            }
        });

        _runtime.EventOccurred += OnRuntimeEventOccurred;
        _listenerSupervisor.ListenerError += _ => OnUi(() =>
            StatusMessage = "An incoming connection ended unexpectedly. Refresh the link and try again.");
        StartMdnsDiscovery();
    }

    public void StagePaths(IEnumerable<string> paths) => _ = StagePathsAsync(paths);

    public async Task StagePathsAsync(IEnumerable<string> paths)
    {
        string[] selectedPaths = paths.Take(DesktopBatchPreparer.MaxItems + 1).ToArray();
        if (selectedPaths.Length > DesktopBatchPreparer.MaxItems)
        {
            StatusMessage = $"Choose at most {DesktopBatchPreparer.MaxItems} files or folders at once.";
            return;
        }

        List<LinuxTransferItemViewModel> items;
        int skipped;
        try
        {
            (items, skipped) = await Task.Run(() =>
            {
                var ready = new List<LinuxTransferItemViewModel>();
                int omitted = 0;
                foreach (string path in selectedPaths)
                {
                    _lifetimeCts.Token.ThrowIfCancellationRequested();
                    try
                    {
                        if (File.Exists(path))
                        {
                            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                                throw new IOException("Symbolic-link files cannot be shared.");
                            var file = new FileInfo(path);
                            if (file.Length > Glide.Core.Codec.ProtocolCatalog.MaxItemBytes)
                                throw new IOException("The file exceeds the item limit.");
                            ready.Add(new LinuxTransferItemViewModel(path, file.Name, file.Length, false));
                        }
                        else if (Directory.Exists(path))
                        {
                            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                                throw new IOException("Symbolic-link folders cannot be shared.");
                            var directory = new DirectoryInfo(path);
                            long size = 0;
                            int count = 0;
                            var options = new EnumerationOptions
                            {
                                RecurseSubdirectories = true,
                                IgnoreInaccessible = false,
                                AttributesToSkip = FileAttributes.ReparsePoint
                            };
                            foreach (FileInfo child in directory.EnumerateFiles("*", options))
                            {
                                _lifetimeCts.Token.ThrowIfCancellationRequested();
                                if (++count > DesktopBatchPreparer.MaxItems)
                                    throw new IOException("The folder exceeds the batch item limit.");
                                if (child.Length > Glide.Core.Codec.ProtocolCatalog.MaxItemBytes)
                                    throw new IOException("A file in the folder exceeds the item limit.");
                                size = checked(size + child.Length);
                                if (size > Glide.Core.Codec.ProtocolCatalog.MaxBatchBytes)
                                    throw new IOException("The folder exceeds the batch size limit.");
                            }
                            ready.Add(new LinuxTransferItemViewModel(path, directory.Name, size, true));
                        }
                        else omitted++;
                    }
                    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or OverflowException)
                    {
                        omitted++;
                    }
                }
                return (ready, omitted);
            }, _lifetimeCts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        await OnUiAsync(() =>
        {
            foreach (var item in items)
            {
                item.RemoveCommand = new RelayCommand(_ => RemoveStagedItem(item));
                StagedItems.Add(item);
            }
            NotifyStagingChanged();
            if (skipped > 0)
                StatusMessage = $"{skipped} selection(s) skipped because they were unavailable, unreadable, or too large.";
        });
    }

    public void RemoveStagedItem(LinuxTransferItemViewModel item)
    {
        StagedItems.Remove(item);
        NotifyStagingChanged();
    }

    private void NotifyStagingChanged()
    {
        OnPropertyChanged(nameof(StagedCount));
        OnPropertyChanged(nameof(TotalStagedBytes));
        OnPropertyChanged(nameof(FormattedTotalStagedBytes));
        OnPropertyChanged(nameof(HasStagedItems));
        OnPropertyChanged(nameof(HasNoStagedItems));
        SendFilesCommand.RaiseCanExecuteChanged();
        StartQuickShareCommand.RaiseCanExecuteChanged();
    }

    private void StartMdnsDiscovery()
    {
        try
        {
            _mdnsDiscovery.PeerDiscovered += peer =>
            {
                OnUi(() =>
                {
                    var existing = DiscoveredPeers.FirstOrDefault(p => p.Host == peer.Host && p.Port == peer.Port);
                    if (existing == null)
                        DiscoveredPeers.Add(new LinuxDiscoveredPeerViewModel(peer));
                });
            };
            _mdnsDiscovery.Start(enableBrowsing: true);
        }
        catch
        {
            // Ignore mDNS discovery failure on constrained environments
        }
    }

    private async Task ExecuteSendAsync()
    {
        if (StagedItems.Count == 0) return;

        var filePaths = StagedItems.Select(i => i.FullPath).ToList();
        BatchPreparationResult prep;
        try
        {
            prep = await Task.Run(() =>
                DesktopBatchPreparer.Prepare(filePaths, _lifetimeCts.Token), _lifetimeCts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        if (!prep.Success)
        {
            StatusMessage = $"Cannot send: {prep.Message}";
            return;
        }

        var destination = SelectedPeer;
        if (string.IsNullOrWhiteSpace(InvitationInput))
        {
            StatusMessage = "Open Receive on the other device and paste its current pairing link. Nearby discovery alone does not authenticate a device.";
            return;
        }

        LanInvitation invitation;
        try
        {
            invitation = InvitationCodec.Decode(InvitationInput.Trim());
            if (invitation.IsExpired())
            {
                StatusMessage = "This pairing link expired. Ask the other device for a fresh link.";
                return;
            }
            if (destination != null && !invitation.Endpoints.Any(endpoint =>
                    endpoint.Address == destination.Host && endpoint.Port == destination.Port))
            {
                StatusMessage = "The link does not match the nearby device you selected. Check the device and use its current link.";
                return;
            }
        }
        catch (Exception)
        {
            StatusMessage = "That pairing link is invalid. Copy a fresh Glide link from the receiving device.";
            return;
        }

        IsTransferActive = true;
        TransferTitle = $"Sending {StagedItems.Count} item(s)...";
        TransferProgressFraction = 0;
        _rateCalculator.Reset();
        _activeTransferCts = new CancellationTokenSource();

        try
        {
            var endpoint = invitation.Endpoints[0];

            var sendRequest = new DesktopSendRequest(
                Host: endpoint.Address,
                Port: endpoint.Port,
                Files: prep.Files,
                Invitation: invitation,
                LocalDeviceName: DeviceName,
                TrustManager: _trustManager,
                ExpectedPeerFingerprintHex: SelectedTrustedPeer?.FingerprintHex);

            var result = await _runtime.SendAsync(sendRequest, this, _activeTransferCts.Token);
            if (result.Success)
            {
                StatusMessage = "Transfer completed successfully.";
                RefreshTrustedPeers();
                LinuxNotificationService.ShowNotification("Transfer Complete", $"Sent {prep.Files.Count} item(s) successfully.");
                StagedItems.Clear();
                NotifyStagingChanged();
            }
            else
            {
                StatusMessage = $"Transfer failed: {result.Message}";
                HistoryItems.Insert(0, new LinuxHistoryItemViewModel(
                    Guid.NewGuid().ToString("N"),
                    DateTime.Now,
                    "Sent",
                    destination?.DisplayName ?? "Peer",
                    prep.Files.Count,
                    prep.TotalBytes,
                    "Failed",
                    result.Message));
            }
        }
        catch (OperationCanceledException)
        {
            StatusMessage = "Transfer cancelled.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Error: {ex.Message}";
        }
        finally
        {
            IsTransferActive = false;
            _activeTransferCts?.Dispose();
            _activeTransferCts = null;
        }
    }

    private async Task ExecuteReceiveFromInvitationAsync()
    {
        if (string.IsNullOrWhiteSpace(InvitationInput)) return;

        LanInvitation invitation;
        try
        {
            invitation = InvitationCodec.Decode(InvitationInput.Trim());
        }
        catch (Exception ex)
        {
            StatusMessage = $"Invalid invitation code: {ex.Message}";
            return;
        }

        IsTransferActive = true;
        TransferTitle = "Receiving from invitation...";
        TransferProgressFraction = 0;
        _rateCalculator.Reset();
        _activeTransferCts = new CancellationTokenSource();

        try
        {
            var stagingStore = new QuarantineStagingStore(
                destinationRoot: DownloadDirectory);

            var receiveRequest = new DesktopReceiveRequest(
                StagingStore: stagingStore,
                LocalDeviceName: DeviceName,
                Invitation: invitation,
                TrustManager: _trustManager);

            var result = await _runtime.ReceiveFromInvitationAsync(receiveRequest, this, _activeTransferCts.Token);
            if (result.Success)
            {
                StatusMessage = "Received files successfully.";
                RefreshTrustedPeers();
                LinuxNotificationService.ShowNotification("Transfer Complete", $"Received {result.VerifiedCount} file(s).");
            }
            else
            {
                StatusMessage = $"Receive failed: {result.Message}";
            }
        }
        catch (OperationCanceledException)
        {
            StatusMessage = "Transfer cancelled.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Error: {ex.Message}";
        }
        finally
        {
            IsTransferActive = false;
            _activeTransferCts?.Dispose();
            _activeTransferCts = null;
        }
    }

    private async Task ExecuteStartQuickShareAsync()
    {
        BatchPreparationResult prep;
        try
        {
            string[] selectedPaths = StagedItems.Select(item => item.FullPath).ToArray();
            prep = await Task.Run(() =>
                DesktopBatchPreparer.Prepare(selectedPaths, _lifetimeCts.Token), _lifetimeCts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        if (!prep.Success)
        {
            StatusMessage = $"Cannot share: {prep.Message}";
            return;
        }

        if (!IsListening) await ExecuteToggleListenerAsync();
        if (!IsListening || _listenerSupervisor.Listener == null) return;

        RefreshInvitation();
        var invitation = _invitationManager.ActiveInvitation;
        if (invitation == null) return;

        _hostedSendRequest = new DesktopSendRequest(
            Host: invitation.Endpoints[0].Address,
            Port: invitation.Endpoints[0].Port,
            Files: prep.Files,
            Invitation: invitation,
            LocalDeviceName: DeviceName,
            TrustManager: _trustManager,
            HostedInvitationAuthority: _invitationManager.Authority);
        OnPropertyChanged(nameof(IsQuickShareActive));
        OnPropertyChanged(nameof(IsAnyModalVisible));
        StatusMessage = "Scan this one-time QR in Glide on the receiving device. You will confirm the recipient.";
    }

    private async Task ExecuteToggleListenerAsync()
    {
        if (IsListening)
        {
            await _listenerSupervisor.StopAsync();
            _listenerOwner?.Dispose();
            _listenerOwner = null;
            _mdnsDiscovery.StopAdvertising();
            _invitationManager.RevokeActive();
            CurrentInvitationUri = "";
            IsListening = false;
            StatusMessage = "Listening stopped.";
        }
        else
        {
            try
            {
                _listenerOwner = DesktopListenerOwnership.AcquireLinux();
                await Task.Run(() =>
                {
                    var store = new QuarantineStagingStore(destinationRoot: DownloadDirectory);
                    store.PurgeAbandonedCommitFiles();
                    store.PurgeAbandonedQuarantineSessions();
                }, _lifetimeCts.Token);
                int boundPort = _listenerSupervisor.Start(ListeningPort);
                ListeningPort = boundPort;
                RefreshInvitation();
                _listenerSupervisor.StartListenerLoop(HandleIncomingConnectionAsync, _lifetimeCts.Token);
                _listenerSupervisor.StartInvitationRotation(
                    TimeSpan.FromSeconds(105),
                    _ => Dispatcher.UIThread.InvokeAsync(() =>
                    {
                        // Do not revoke a token during bootstrap or an active
                        // authenticated session. Completion creates a new link.
                        if (!IsTransferActive) RefreshInvitation();
                    }).GetTask(),
                    _lifetimeCts.Token);
                IsListening = true;
                StatusMessage = $"Ready to receive as {DeviceName}. Approve each incoming request.";
            }
            catch (Exception ex)
            {
                _listenerSupervisor.Stop();
                _listenerOwner?.Dispose();
                _listenerOwner = null;
                _mdnsDiscovery.StopAdvertising();
                _invitationManager.RevokeActive();
                CurrentInvitationUri = "";
                StatusMessage = $"Failed to start listener: {ex.Message}";
            }
        }
    }

    private void RefreshInvitation()
    {
        var listener = _listenerSupervisor.Listener;
        if (listener == null) return;

        try
        {
            var invitation = _invitationManager.CreateReceiverInvitation(listener, DeviceName, listener.BoundPort);
            CurrentInvitationUri = DesktopInvitationManager.EncodeInvitation(invitation);
            try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "glide_active_invitation.uri"), CurrentInvitationUri); } catch { }
            _mdnsDiscovery.StartAdvertising(DeviceName, listener.BoundPort, invitation.Presence);
            if (_hostedSendRequest != null)
                _hostedSendRequest = _hostedSendRequest with { Invitation = invitation };
            StatusMessage = "A new one-time receiving link is ready. It expires after two minutes.";
        }
        catch (Exception)
        {
            _invitationManager.RevokeActive();
            _mdnsDiscovery.StopAdvertising();
            CurrentInvitationUri = "";
            StatusMessage = "Could not create a reachable receiving link. Check your local network.";
        }
    }

    private async Task HandleIncomingConnectionAsync(TlsSocketConnection connection, CancellationToken cancellationToken)
    {
        await using (connection)
        {
            DesktopSendRequest? hosted = _hostedSendRequest;
            DesktopTransferResult result;
            if (hosted != null)
            {
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    _hostedSendRequest = null;
                    OnPropertyChanged(nameof(IsQuickShareActive));
                    OnPropertyChanged(nameof(IsAnyModalVisible));
                    IsTransferActive = true;
                    TransferTitle = "Sending selected files…";
                });
                result = await _runtime.SendHostedAsync(connection, hosted, this, cancellationToken);
            }
            else
            {
                var request = new DesktopReceiveRequest(
                    StagingStore: new QuarantineStagingStore(destinationRoot: DownloadDirectory),
                    InvitationAuthority: _invitationManager.Authority,
                    LocalDeviceName: DeviceName,
                    TrustManager: _trustManager);
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    IsTransferActive = true;
                    TransferTitle = "Receiving files…";
                });
                result = await _runtime.ReceiveAsync(connection, request, this, cancellationToken);
            }
            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                StatusMessage = result.Message;
                IsTransferActive = _runtime.Snapshot.IsActive;
                if (hosted != null)
                {
                    _hostedSendRequest = null;
                    OnPropertyChanged(nameof(IsQuickShareActive));
                    OnPropertyChanged(nameof(IsAnyModalVisible));
                    if (result.Success)
                    {
                        StagedItems.Clear();
                        NotifyStagingChanged();
                    }
                }
                RefreshTrustedPeers();
                // The invitation is single-use, including a declined offer. A fresh link
                // is needed before another device can connect.
                if (IsListening) RefreshInvitation();
            });
        }
    }

    private void ExecuteCancelTransfer()
    {
        _activeTransferCts?.Cancel();
        _runtime.CancelActiveTransfer();
    }

    private void OnRuntimeEventOccurred(object? sender, TransferEvent ev)
    {
        OnUi(() =>
        {
            switch (ev)
            {
                case TransferEvent.ProgressUpdated progress:
                    TransferProgressFraction = progress.Progress.Fraction;
                    TransferredBytesText = progress.TransferredBytesText;
                    TransferRateText = progress.RateText;
                    break;
                case TransferEvent.ItemStatusChanged item:
                    CurrentTransferFile = $"{item.ItemName} ({item.Status})";
                    break;
            }
        });
    }

    // IDesktopTransferInteraction Implementation
    public async Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken)
    {
        var prompt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        await OnUiAsync(() =>
        {
            RecipientPeerName = peerName;
            IsRecipientModalVisible = true;
            _recipientTcs = prompt;
            if (AutoConfirmForTesting)
            {
                _ = Task.Delay(1000).ContinueWith(_ => Dispatcher.UIThread.InvokeAsync(() => ResolveRecipient(true)));
            }
        });
        using var registration = cancellationToken.Register(() => prompt.TrySetResult(false));
        return await prompt.Task;
    }

    public async Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
    {
        var prompt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        await OnUiAsync(() =>
        {
            OfferPeerName = offer.PeerName;
            OfferSummaryText = $"{offer.Items.Count} file(s), total {FormatBytes(offer.TotalBytes)}";
            IsOfferModalVisible = true;
            _offerTcs = prompt;
            if (AutoConfirmForTesting)
            {
                _ = Task.Delay(1000).ContinueWith(_ => Dispatcher.UIThread.InvokeAsync(() => ResolveOffer(true)));
            }
        });
        using var registration = cancellationToken.Register(() => prompt.TrySetResult(false));
        return await prompt.Task;
    }

    public async Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken)
    {
        var prompt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        await OnUiAsync(() =>
        {
            SasPeerName = peer.DisplayName;
            // This is the verified persistent identity fingerprint, not a
            // session SAS. Never present a six-character hex prefix as a
            // six-digit mutual verification code.
            SasCode = string.Join(" ", Enumerable.Range(0, (peer.FingerprintHex.Length + 7) / 8)
                .Select(i => peer.FingerprintHex.Substring(i * 8,
                    Math.Min(8, peer.FingerprintHex.Length - i * 8)).ToUpperInvariant()));
            IsSasModalVisible = true;
            _sasTcs = prompt;
            if (AutoConfirmForTesting)
            {
                _ = Task.Delay(1000).ContinueWith(_ => Dispatcher.UIThread.InvokeAsync(() => ResolveSas(true)));
            }
        });
        using var registration = cancellationToken.Register(() => prompt.TrySetResult(false));
        return await prompt.Task;
    }

    public void OnInvitationConsumed(DesktopTransferDirection direction)
    {
        if (direction != DesktopTransferDirection.Receive) return;
        OnUi(() =>
        {
            CurrentInvitationUri = "";
            StatusMessage = "One-time pairing link used. A new link will be available after this transfer.";
        });
    }

    public void OnPeerIdentified(DesktopTransferDirection direction, string peerName)
    {
        OnUi(() => StatusMessage = $"Connected to peer: {peerName}");
    }

    public void OnStatus(DesktopTransferDirection direction, string message)
    {
        OnUi(() => StatusMessage = message);
    }

    public void OnProgress(DesktopTransferProgress progress)
    {
        OnUi(() =>
        {
            TransferProgressFraction = progress.Fraction;
            var (_, rateText, transferredText) = _rateCalculator.Update(progress.TransferredBytes, progress.TotalBytes);
            TransferredBytesText = transferredText;
            TransferRateText = rateText;
        });
    }

    public void OnItemStatus(DesktopTransferDirection direction, string itemName, string status)
    {
        OnUi(() => CurrentTransferFile = $"{itemName} ({status})");
    }

    public void OnHistory(DesktopHistoryEntry entry)
    {
        _stateStore.AppendVerifiedHistory(entry);
        OnUi(() => HistoryItems.Insert(0, ToHistoryItem(entry)));
    }

    private static LinuxHistoryItemViewModel ToHistoryItem(DesktopHistoryEntry entry) =>
        new(Guid.NewGuid().ToString("N"), entry.Timestamp.LocalDateTime,
            entry.Direction.ToString(), entry.PeerName, 1, entry.SizeBytes, "Completed", itemName: entry.ItemName);

    private void RefreshTrustedPeers()
    {
        TrustedPeers.Clear();
        if (_trustManager == null) return;
        foreach (var peer in _trustManager.GetTrustedPeers())
            TrustedPeers.Add(peer);
    }

    private void ResolveSas(bool accepted)
    {
        IsSasModalVisible = false;
        _sasTcs?.TrySetResult(accepted);
        _sasTcs = null;
    }

    private void ResolveOffer(bool accepted)
    {
        IsOfferModalVisible = false;
        _offerTcs?.TrySetResult(accepted);
        _offerTcs = null;
    }

    private void ResolveRecipient(bool accepted)
    {
        IsRecipientModalVisible = false;
        _recipientTcs?.TrySetResult(accepted);
        _recipientTcs = null;
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return $"{kb:F1} KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return $"{mb:F1} MB";
        double gb = mb / 1024.0;
        return $"{gb:F2} GB";
    }

    private static bool IsUiLoopActive =>
        Application.Current?.ApplicationLifetime is Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime;

    private static void OnUi(Action action)
    {
        if (!IsUiLoopActive || Dispatcher.UIThread.CheckAccess()) action();
        else Dispatcher.UIThread.Post(action);
    }

    private static async Task OnUiAsync(Action action)
    {
        if (!IsUiLoopActive || Dispatcher.UIThread.CheckAccess()) action();
        else await Dispatcher.UIThread.InvokeAsync(action);
    }

    public void Dispose()
    {
        _lifetimeCts.Cancel();
        _activeTransferCts?.Cancel();
        _listenerSupervisor.Dispose();
        _listenerOwner?.Dispose();
        _listenerOwner = null;
        _mdnsDiscovery.StopAdvertising();
        _invitationManager.RevokeActive();
        _mdnsDiscovery.Dispose();
        _lifetimeCts.Dispose();
    }
}
