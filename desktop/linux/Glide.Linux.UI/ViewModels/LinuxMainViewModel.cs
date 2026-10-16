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
using Glide.Desktop.Network;
using Glide.Desktop.Pairing;
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

public sealed class LinuxSavedDeviceRow(DesktopTrustedPeer peer)
{
    public DesktopTrustedPeer Peer { get; } = peer;
    public string DisplayName => Peer.DisplayName;
    public string LastSeenText => "Last verified " +
        DateTimeOffset.FromUnixTimeMilliseconds(Peer.LastSeenAtUnixMs).ToLocalTime().ToString("g");
    public string ConnectAutomationName => $"Connect to {DisplayName}, identity {Peer.FingerprintHex[..Math.Min(8, Peer.FingerprintHex.Length)]}";
}

public enum SendMethod
{
    QuickShareQr,
    // Retained for settings compatibility; no longer exposed on Send.
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
    private int _disposeState;
    private LinuxPage _activePage = LinuxPage.Send;
    private SendMethod _selectedSendMethod = SendMethod.QuickShareQr;
    private AppThemePreference _currentThemePreference = AppThemePreference.System;

    private string _deviceName = Environment.MachineName;
    private string _downloadDirectory = LinuxEnvironment.ResolveDownloadsDirectory();
    private int _listeningPort = LanDiscoveryConstants.DefaultPort;
    private bool _isListening = false;
    private string _statusMessage = "Turn on receiving to accept files.";

    // Active transfer properties
    private bool _isTransferActive = false;
    private string _transferTitle = "";
    private double _transferProgressFraction = 0;
    private string _transferRateText = "";
    private string _transferredBytesText = "";
    private string _currentTransferFile = "";
    private CancellationTokenSource? _activeTransferCts;
    private readonly DesktopTransferRateCalculator _rateCalculator = new();
    private DesktopTransferProgress? _coalescedProgress;
    private TransferEvent.ProgressUpdated? _coalescedProgressEvent;

    // SAS Modal
    private bool _isSasModalVisible = false;
    private string _sasPeerName = "";
    private string _sasCode = "";
    private readonly LinuxConsentDecision _sasDecision = new(OnUiAsync);

    // Incoming Offer Modal
    private bool _isOfferModalVisible = false;
    private string _offerPeerName = "";
    private string _offerSummaryText = "";
    private readonly LinuxConsentDecision _offerDecision = new(OnUiAsync);

    // Sender-hosted QR still requires the local user to approve the recipient.
    private bool _isRecipientModalVisible;
    private string _recipientPeerName = "";
    private readonly LinuxConsentDecision _recipientDecision = new(OnUiAsync);

    // Invitation pairing
    private string _invitationInput = "";
    private string _currentInvitationUri = "";
    private Bitmap? _pairingQrImage;
    private bool _isReceiveQrExpanded;
    private DesktopSendRequest? _hostedSendRequest;

    // Services
    private readonly DesktopTransferRuntime _runtime = new();
    public DesktopTransferPresentation Transfer { get; } = new();
    private readonly MdnsDiscoveryService _mdnsDiscovery = new();
    private readonly DesktopListenerSupervisor _listenerSupervisor = new();
    private DesktopListenerOwnership? _listenerOwner;
    private readonly DesktopInvitationManager _invitationManager;
    private readonly DesktopPairingCoordinator _pairing;
    private readonly CancellationTokenSource _lifetimeCts = new();
    private readonly DesktopStateStore _stateStore;
    private readonly DesktopPersistentTrustManager? _trustManager;

    private DesktopInvitationReadiness _networkReadiness;
    private string _connectionDetailsText = "";

    public ObservableCollection<LinuxTransferItemViewModel> StagedItems { get; } = [];
    public ObservableCollection<LinuxDiscoveredPeerViewModel> DiscoveredPeers { get; } = [];
    public LinuxActivityHistoryViewModel History { get; } = new();
    public ObservableCollection<LinuxHistoryItemViewModel> HistoryItems => History.Items;
    public ObservableCollection<DesktopTrustedPeer> TrustedPeers { get; } = [];
    public ObservableCollection<LinuxSavedDeviceRow> SavedDeviceRows { get; } = [];

    private LinuxDiscoveredPeerViewModel? _selectedPeer;
    private DesktopTrustedPeer? _selectedTrustedPeer;
    private string _pairPin = "";
    private string _pairAddress = "";
    private string _pairLink = "";
    private Bitmap? _pairQrImage;
    private bool _isPairQrVisible;
    private string _renderedPairQrUri = "";

    public DesktopPairingSnapshot Pairing => _pairing.Snapshot;
    public bool IsPairIdle => Pairing.Phase == DesktopPairingPhase.Idle;
    public bool IsPairEmpty => Pairing.Phase == DesktopPairingPhase.Empty;
    public bool IsPairReady => Pairing.Phase == DesktopPairingPhase.TargetReady;
    public bool IsPairApproval => Pairing.Phase == DesktopPairingPhase.TargetApproval;
    public bool IsPairPinEntry => Pairing.Phase == DesktopPairingPhase.InitiatorPinEntry;
    public bool IsPairManualEntry => Pairing.Phase == DesktopPairingPhase.ManualEntry;
    public bool IsPairLinkEntry => Pairing.Phase == DesktopPairingPhase.UseLink;
    public bool IsPairDiscovery => Pairing.Phase is DesktopPairingPhase.Scanning or DesktopPairingPhase.PeersFound or DesktopPairingPhase.Empty;
    public bool IsPairVerifying => Pairing.Phase == DesktopPairingPhase.Verifying;
    public bool IsPairSuccess => Pairing.Phase == DesktopPairingPhase.Success;
    public bool IsPairFailed => Pairing.Phase == DesktopPairingPhase.Failed;
    public bool IsPairResult => Pairing.Phase is DesktopPairingPhase.Success or DesktopPairingPhase.Failed or DesktopPairingPhase.Verifying;
    public bool IsPairQrVisible { get => _isPairQrVisible; set => SetProperty(ref _isPairQrVisible, value); }
    public bool IsPairQrUnavailable => IsPairReady && PairQrImage is null;
    public string PairPin { get => _pairPin; set => SetProperty(ref _pairPin, value); }
    public string PairAddress { get => _pairAddress; set => SetProperty(ref _pairAddress, value); }
    public string PairLink { get => _pairLink; set => SetProperty(ref _pairLink, value); }
    public Bitmap? PairQrImage { get => _pairQrImage; private set => SetProperty(ref _pairQrImage, value); }

    public DesktopTrustedPeer? SelectedTrustedPeer
    {
        get => _selectedTrustedPeer;
        set { if (SetProperty(ref _selectedTrustedPeer, value)) SendFilesCommand?.RaiseCanExecuteChanged(); }
    }

    public LinuxDiscoveredPeerViewModel? SelectedPeer
    {
        get => _selectedPeer;
        set
        {
            if (SetProperty(ref _selectedPeer, value) && value?.IsPairReady == true && IsPairDiscovery)
                _pairing.SelectPeer(value.Peer);
        }
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
                OnPropertyChanged(nameof(IsTransferBannerVisible));
                OnPropertyChanged(nameof(IsSettingsActive));
                if (value != LinuxPage.Pair) _pairing.OnPageLeft();
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
    public bool IsTransferBannerVisible => Transfer.IsActive && !IsActivityActive;
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
                SendFilesCommand?.RaiseCanExecuteChanged();
            }
        }
    }

    public bool IsQuickShareMethodSelected => _selectedSendMethod == SendMethod.QuickShareQr;
    public bool IsNearbyMethodSelected => _selectedSendMethod == SendMethod.NearbyDevice;
    public bool IsSavedMethodSelected => _selectedSendMethod == SendMethod.SavedDevice;

    public bool HasStagedItems => StagedItems.Count > 0;
    public bool HasNoStagedItems => StagedItems.Count == 0;

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
        get => History.SelectedFilter;
        set => History.SelectedFilter = value;
    }

    public string ActivitySearchQuery
    {
        get => History.SearchQuery;
        set => History.SearchQuery = value;
    }

    public IEnumerable<LinuxHistoryItemViewModel> FilteredHistoryItems => History.FilteredItems;

    public string AppVersionText => typeof(LinuxMainViewModel).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";

    public bool IsAnyModalVisible => IsSasModalVisible || IsOfferModalVisible || IsRecipientModalVisible || IsQuickShareActive || IsReceiveQrExpanded;

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
                ConnectSavedDeviceCommand?.RaiseCanExecuteChanged();
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
            {
                if (value) IsReceiveQrExpanded = false;
                OnPropertyChanged(nameof(IsAnyModalVisible));
            }
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
                OnPropertyChanged(nameof(HasActiveInvitation));
                if (!HasActiveInvitation) IsReceiveQrExpanded = false;
            }
        }
    }

    public bool HasActiveInvitation => !string.IsNullOrWhiteSpace(CurrentInvitationUri);

    public bool IsReceiveQrExpanded
    {
        get => _isReceiveQrExpanded;
        set
        {
            if (SetProperty(ref _isReceiveQrExpanded, value))
                OnPropertyChanged(nameof(IsAnyModalVisible));
        }
    }

    public Bitmap? PairingQrImage
    {
        get => _pairingQrImage;
        private set => SetProperty(ref _pairingQrImage, value);
    }

    public bool IsQuickShareActive => _hostedSendRequest != null;

    public DesktopInvitationReadiness NetworkReadiness
    {
        get => _networkReadiness;
        private set
        {
            if (SetProperty(ref _networkReadiness, value))
            {
                OnPropertyChanged(nameof(IsNetworkReady));
                OnPropertyChanged(nameof(IsVirtualMachineNatOnly));
                OnPropertyChanged(nameof(NetworkGuidanceMessage));
                StartQuickShareCommand?.RaiseCanExecuteChanged();
            }
        }
    }

    public bool IsNetworkReady => _networkReadiness?.IsReady ?? false;
    public bool IsVirtualMachineNatOnly => _networkReadiness?.Status == DesktopInvitationReadinessStatus.VirtualMachineNatOnly;

    public string NetworkGuidanceMessage =>
        _networkReadiness?.Status switch
        {
            DesktopInvitationReadinessStatus.VirtualMachineNatOnly =>
                DesktopInvitationManager.VirtualMachineNatGuidance,
            DesktopInvitationReadinessStatus.NoUsableIpv4 =>
                "No local network connection is available. Connect this computer to Wi-Fi or Ethernet to share files.",
            _ => ""
        };

    public string ConnectionDetailsText
    {
        get => _connectionDetailsText;
        private set => SetProperty(ref _connectionDetailsText, value);
    }

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
    public AsyncRelayCommand RetryTransferCommand { get; }
    public RelayCommand OpenTransferFolderCommand { get; }
    public AsyncRelayCommand ConnectSavedDeviceCommand { get; }
    public RelayCommand AcceptSasCommand { get; }
    public RelayCommand DeclineSasCommand { get; }
    public RelayCommand AcceptOfferCommand { get; }
    public RelayCommand DeclineOfferCommand { get; }
    public RelayCommand AcceptRecipientCommand { get; }
    public RelayCommand DeclineRecipientCommand { get; }
    public RelayCommand RefreshInvitationCommand { get; }
    public RelayCommand OpenReceiveQrCommand { get; }
    public RelayCommand CloseReceiveQrCommand { get; }
    public AsyncRelayCommand StartQuickShareCommand { get; }
    public RelayCommand CancelQuickShareCommand { get; }
    public RelayCommand OpenDownloadsDirectoryCommand { get; }
    public RelayCommand ClearHistoryCommand { get; }
    public RelayCommand RevokeTrustedPeerCommand { get; }
    public RelayCommand DismissActiveModalCommand { get; }
    public RelayCommand SelectSendMethodCommand { get; }
    public RelayCommand SelectActivityFilterCommand { get; }
    public RelayCommand SetThemeCommand { get; }
    public RelayCommand FindPairDevicesCommand { get; }
    public AsyncRelayCommand ReadyToPairCommand { get; }
    public RelayCommand CancelPairCommand { get; }
    public RelayCommand ChoosePairPeerCommand { get; }
    public AsyncRelayCommand SubmitPairPinCommand { get; }
    public RelayCommand ShowPairAddressCommand { get; }
    public AsyncRelayCommand SubmitPairAddressCommand { get; }
    public RelayCommand ShowPairLinkCommand { get; }
    public AsyncRelayCommand SubmitPairLinkCommand { get; }
    public RelayCommand PairRecoveryCommand { get; }
    public RelayCommand ApprovePairCommand { get; }
    public RelayCommand DeclinePairCommand { get; }

    public LinuxMainViewModel(
        DesktopStateStore? store = null,
        IDesktopNetworkSnapshotProvider? networkSnapshotProvider = null)
    {
        _stateStore = store ?? new DesktopStateStore(Path.Combine(LinuxEnvironment.DataHome, "desktop-state.json"));
        _invitationManager = new DesktopInvitationManager(networkSnapshotProvider);
        _networkReadiness = _invitationManager.AssessReadiness();
        try
        {
            _invitationManager.SnapshotProvider.NetworkAddressChanged += OnNetworkAddressChanged;
        }
        catch
        {
            // Ignore if platform does not support network change events
        }

        DismissActiveModalCommand = new RelayCommand(() =>
        {
            if (IsSasModalVisible) ResolveSas(false);
            else if (IsOfferModalVisible) ResolveOffer(false);
            else if (IsRecipientModalVisible) ResolveRecipient(false);
            else if (IsReceiveQrExpanded) IsReceiveQrExpanded = false;
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
        _pairing = new DesktopPairingCoordinator(
            _trustManager, _invitationManager, _mdnsDiscovery, () => DeviceName,
            EnsurePairListenerAsync, ReleasePairListener,
            () => IsTransferActive || _runtime.IsBusy);
        _pairing.Changed += snapshot => OnUi(() =>
        {
            if (snapshot.Phase is DesktopPairingPhase.Idle or DesktopPairingPhase.Success or DesktopPairingPhase.Failed)
                PairPin = "";
            if (!string.Equals(_renderedPairQrUri, snapshot.QrUri, StringComparison.Ordinal))
            {
                _renderedPairQrUri = snapshot.QrUri;
                PairQrImage?.Dispose();
                PairQrImage = string.IsNullOrWhiteSpace(snapshot.QrUri) ? null : LinuxQrImageHelper.Create(snapshot.QrUri);
            }
            IsPairQrVisible = snapshot.Phase == DesktopPairingPhase.TargetReady && PairQrImage is not null;
            OnPropertyChanged(nameof(Pairing));
            OnPropertyChanged(nameof(IsPairIdle));
            OnPropertyChanged(nameof(IsPairEmpty));
            OnPropertyChanged(nameof(IsPairReady));
            OnPropertyChanged(nameof(IsPairQrUnavailable));
            OnPropertyChanged(nameof(IsPairApproval));
            OnPropertyChanged(nameof(IsPairPinEntry));
            OnPropertyChanged(nameof(IsPairManualEntry));
            OnPropertyChanged(nameof(IsPairLinkEntry));
            OnPropertyChanged(nameof(IsPairDiscovery));
            OnPropertyChanged(nameof(IsPairVerifying));
            OnPropertyChanged(nameof(IsPairSuccess));
            OnPropertyChanged(nameof(IsPairFailed));
            OnPropertyChanged(nameof(IsPairResult));
        });
        _pairing.PeerPaired += _ => OnUi(RefreshTrustedPeers);
        DesktopPersistentState persisted = _stateStore.Load();
        _downloadDirectory = persisted.Settings.ReceiveDirectory;
        _listeningPort = persisted.Settings.ListenerPort;
        foreach (DesktopHistoryEntry entry in persisted.History)
            HistoryItems.Add(ToHistoryItem(entry));

        SwitchTabCommand = new RelayCommand(p =>
        {
            if (p is string tab) CurrentTab = tab;
        });
        FindPairDevicesCommand = new RelayCommand(() => _pairing.BeginFindDevices());
        ReadyToPairCommand = new AsyncRelayCommand(() => _pairing.StartReadyAsync(_lifetimeCts.Token));
        CancelPairCommand = new RelayCommand(() => _pairing.Cancel());
        ChoosePairPeerCommand = new RelayCommand(p =>
        {
            if (p is LinuxDiscoveredPeerViewModel peer && peer.IsPairReady)
                _pairing.SelectPeer(peer.Peer);
        });
        SubmitPairPinCommand = new AsyncRelayCommand(() => _pairing.PairWithSelectedPeerAsync(PairPin));
        ShowPairAddressCommand = new RelayCommand(() => _pairing.ShowManualEntry());
        SubmitPairAddressCommand = new AsyncRelayCommand(() => _pairing.PairWithAddressAsync(PairAddress, PairPin));
        ShowPairLinkCommand = new RelayCommand(() => _pairing.ShowLinkEntry());
        SubmitPairLinkCommand = new AsyncRelayCommand(() => _pairing.PairWithLinkAsync(PairLink));
        PairRecoveryCommand = new RelayCommand(() =>
        {
            if (string.Equals(Pairing.RecoveryAction, "Get a new code", StringComparison.Ordinal))
                _ = _pairing.StartReadyAsync(_lifetimeCts.Token);
            else
                _pairing.BeginFindDevices();
        });
        ApprovePairCommand = new RelayCommand(() => _pairing.Approve());
        DeclinePairCommand = new RelayCommand(() => _pairing.DeclineApproval());

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
            () => StagedItems.Count > 0 && !IsTransferActive &&
                (SelectedSendMethod == SendMethod.SavedDevice ? SelectedTrustedPeer != null : !string.IsNullOrWhiteSpace(InvitationInput)));
        ReceiveFromInvitationCommand = new AsyncRelayCommand(ExecuteReceiveFromInvitationAsync, () => !string.IsNullOrWhiteSpace(InvitationInput) && !IsTransferActive);
        ToggleListenerCommand = new AsyncRelayCommand(ExecuteToggleListenerAsync);
        CancelTransferCommand = new RelayCommand(ExecuteCancelTransfer);
        RetryTransferCommand = new AsyncRelayCommand(async () =>
        {
            if (StagedItems.Count == 0) return;
            if (SelectedSendMethod == SendMethod.SavedDevice && SelectedTrustedPeer != null)
                await ExecuteSendAsync();
            else
                await ExecuteStartQuickShareAsync();
        });
        ConnectSavedDeviceCommand = new AsyncRelayCommand(async value =>
        {
            if (value is not LinuxSavedDeviceRow row || StagedItems.Count == 0 || IsTransferActive) return;
            SelectedSendMethod = SendMethod.SavedDevice;
            SelectedTrustedPeer = row.Peer;
            await ExecuteSendAsync();
        }, value => value is LinuxSavedDeviceRow && StagedItems.Count > 0 && !IsTransferActive);
        OpenTransferFolderCommand = new RelayCommand(() =>
        {
            if (string.IsNullOrWhiteSpace(Transfer.ReceivedFolder)) return;
            try
            {
                Process.Start(new ProcessStartInfo("xdg-open")
                {
                    ArgumentList = { Transfer.ReceivedFolder }, UseShellExecute = true
                });
            }
            catch (Exception error) { StatusMessage = $"Could not open folder: {error.Message}"; }
        });

        AcceptSasCommand = new RelayCommand(() => ResolveSas(true));
        DeclineSasCommand = new RelayCommand(() => ResolveSas(false));
        AcceptOfferCommand = new RelayCommand(() => ResolveOffer(true));
        DeclineOfferCommand = new RelayCommand(() => ResolveOffer(false));
        AcceptRecipientCommand = new RelayCommand(() => ResolveRecipient(true));
        DeclineRecipientCommand = new RelayCommand(() => ResolveRecipient(false));
        RefreshInvitationCommand = new RelayCommand(RefreshInvitation);
        OpenReceiveQrCommand = new RelayCommand(() =>
        {
            if (IsListening && HasActiveInvitation)
                IsReceiveQrExpanded = true;
        });
        CloseReceiveQrCommand = new RelayCommand(() => IsReceiveQrExpanded = false);
        StartQuickShareCommand = new AsyncRelayCommand(ExecuteStartQuickShareAsync,
            () => StagedItems.Count > 0 && !IsTransferActive && IsNetworkReady);
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
        RevokeTrustedPeerCommand = new RelayCommand(parameter =>
        {
            DesktopTrustedPeer? peer = parameter as DesktopTrustedPeer ?? SelectedTrustedPeer;
            if (peer == null || _trustManager == null) return;
            string fingerprint = peer.FingerprintHex;
            if (_trustManager.RevokePeer(fingerprint))
            {
                if (SelectedTrustedPeer?.FingerprintHex == fingerprint)
                    SelectedTrustedPeer = null;
                RefreshTrustedPeers();
                StatusMessage = "Saved device removed. A new authenticated pairing is required.";
            }
        });

        _runtime.SnapshotChanged += (_, snapshot) => OnUi(() =>
        {
            bool trustedSend = snapshot.Direction == DesktopTransferDirection.Send &&
                SelectedSendMethod == SendMethod.SavedDevice;
            if (Transfer.ApplySnapshot(snapshot, navigateOnPreparation: trustedSend))
                ActivePage = LinuxPage.Activity;
            OnPropertyChanged(nameof(IsTransferBannerVisible));
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
        ConnectSavedDeviceCommand?.RaiseCanExecuteChanged();
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
                    if (existing != null) DiscoveredPeers.Remove(existing);
                    DiscoveredPeers.Add(new LinuxDiscoveredPeerViewModel(peer));
                    _pairing.NotifyPeerCount(DiscoveredPeers.Count(p => p.IsPairReady));
                });
            };
            _mdnsDiscovery.PeerLost += peer => OnUi(() =>
            {
                var existing = DiscoveredPeers.FirstOrDefault(p => p.Host == peer.Host && p.Port == peer.Port);
                if (existing != null) DiscoveredPeers.Remove(existing);
                _pairing.NotifyPeerCount(DiscoveredPeers.Count(p => p.IsPairReady));
            });
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
        if (SelectedSendMethod == SendMethod.SavedDevice)
        {
            if (SelectedTrustedPeer == null || _trustManager == null)
            {
                StatusMessage = "Choose a paired device to send to.";
                return;
            }

            IsTransferActive = true;
            TransferTitle = $"Connecting to {SelectedTrustedPeer.DisplayName}…";
            TransferProgressFraction = 0;
            _rateCalculator.Reset();
            _activeTransferCts = new CancellationTokenSource();
            try
            {
                var peer = SelectedTrustedPeer;
                var matches = DiscoveredPeers.Where(p =>
                    p.SupportsTrustedReconnect &&
                    string.Equals(p.DisplayName, peer.DisplayName, StringComparison.Ordinal) &&
                    !string.IsNullOrWhiteSpace(p.Host))
                    .Select(p => new LanEndpoint(p.Host, p.Port)).ToList();
                var request = new DesktopSendRequest(
                    Host: peer.LastKnownHost ?? "",
                    Port: peer.LastKnownPort ?? 0,
                    Files: prep.Files,
                    LocalDeviceName: DeviceName,
                    TrustManager: _trustManager,
                    ExpectedPeerFingerprintHex: peer.FingerprintHex,
                    CandidateEndpoints: matches.Count > 0 ? matches : null);
                DesktopTransferResult result = await _runtime.SendTrustedAsync(request, this, _activeTransferCts.Token);
                StatusMessage = result.Message;
                if (result.Success)
                {
                    StagedItems.Clear();
                    NotifyStagingChanged();
                    RefreshTrustedPeers();
                }
            }
            finally
            {
                IsTransferActive = false;
                _activeTransferCts.Dispose();
                _activeTransferCts = null;
            }
            return;
        }
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
                StagedItems.Clear();
                NotifyStagingChanged();
            }
            else
            {
                StatusMessage = $"Transfer failed: {result.Message}";
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
        RefreshNetworkReadiness();
        if (!IsNetworkReady)
        {
            StatusMessage = NetworkGuidanceMessage;
            return;
        }

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
            if (!_pairing.IsReadyWindowActive)
            {
                await _listenerSupervisor.StopAsync();
                _listenerOwner?.Dispose();
                _listenerOwner = null;
                _mdnsDiscovery.StopAdvertising();
            }
            _invitationManager.RevokeActive();
            CurrentInvitationUri = "";
            ConnectionDetailsText = "";
            IsListening = false;
            StatusMessage = "Listening stopped.";
        }
        else
        {
            RefreshNetworkReadiness();
            if (!IsNetworkReady)
            {
                StatusMessage = NetworkGuidanceMessage;
                return;
            }

            try
            {
                await Task.Run(() =>
                {
                    var store = new QuarantineStagingStore(destinationRoot: DownloadDirectory);
                    store.PurgeAbandonedCommitFiles();
                    store.PurgeAbandonedQuarantineSessions();
                }, _lifetimeCts.Token);
                int boundPort;
                if (_listenerSupervisor.Listener != null)
                {
                    boundPort = _listenerSupervisor.BoundPort;
                }
                else
                {
                    if (OperatingSystem.IsLinux())
                        _listenerOwner = DesktopListenerOwnership.AcquireLinux();
                    boundPort = _listenerSupervisor.Start(ListeningPort);
                    _listenerSupervisor.StartListenerLoop(
                        HandleIncomingConnectionAsync, _lifetimeCts.Token,
                        HandleProfile2ConnectionAsync);
                }
                ListeningPort = boundPort;
                RefreshInvitation();
                _listenerSupervisor.StartInvitationRotation(
                    TimeSpan.FromSeconds(105),
                    _ => Dispatcher.UIThread.InvokeAsync(() =>
                    {
                        // Do not revoke a token during bootstrap or an active
                        // authenticated session. Completion creates a new link.
                        if (!IsTransferActive && !_pairing.IsReadyWindowActive)
                            RefreshInvitation(announceReady: false);
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
                ConnectionDetailsText = "";
                StatusMessage = $"Failed to start listener: {ex.Message}";
            }
        }
    }

    public void RefreshNetworkReadiness()
    {
        NetworkReadiness = _invitationManager.AssessReadiness();
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        OnUi(() =>
        {
            if (_pairing.IsReadyWindowActive) _pairing.Cancel();
            RefreshNetworkReadiness();
            if (IsListening)
            {
                _invitationManager.RevokeActive();
                if (IsNetworkReady)
                {
                    RefreshInvitation();
                }
                else
                {
                    _mdnsDiscovery.StopAdvertising();
                    CurrentInvitationUri = "";
                    ConnectionDetailsText = "";
                    StatusMessage = NetworkGuidanceMessage;
                }
            }
            if (_hostedSendRequest != null && !IsNetworkReady)
            {
                _hostedSendRequest = null;
                OnPropertyChanged(nameof(IsQuickShareActive));
                OnPropertyChanged(nameof(IsAnyModalVisible));
                StatusMessage = NetworkGuidanceMessage;
            }
        });
    }

    public void RefreshInvitation() => RefreshInvitation(announceReady: true);

    private void RefreshInvitation(bool announceReady)
    {
        var listener = _listenerSupervisor.Listener;
        if (listener == null) return;

        RefreshNetworkReadiness();
        if (!IsNetworkReady)
        {
            _invitationManager.RevokeActive();
            _mdnsDiscovery.StopAdvertising();
            CurrentInvitationUri = "";
            ConnectionDetailsText = "";
            StatusMessage = NetworkGuidanceMessage;
            return;
        }

        try
        {
            var invitation = _invitationManager.CreateReceiverInvitation(listener, DeviceName, listener.BoundPort);
            CurrentInvitationUri = DesktopInvitationManager.EncodeInvitation(invitation);
            ConnectionDetailsText = $"Available on {string.Join(", ", invitation.Endpoints.Select(e => $"{e.Address}:{e.Port}"))}";
            _mdnsDiscovery.StartAdvertising(DeviceName, listener.BoundPort, _pairing.ActiveWindowPresenceId ?? invitation.Presence, pairReady: _pairing.IsReadyWindowActive, trustedReconnectSupported: true);
            if (_hostedSendRequest != null)
                _hostedSendRequest = _hostedSendRequest with { Invitation = invitation };
            if (announceReady)
                StatusMessage = "A new one-time receiving link is ready. It expires after two minutes.";
        }
        catch (Exception ex)
        {
            _invitationManager.RevokeActive();
            _mdnsDiscovery.StopAdvertising();
            CurrentInvitationUri = "";
            ConnectionDetailsText = "";
            StatusMessage = !string.IsNullOrWhiteSpace(ex.Message) ? ex.Message : "Could not create a reachable receiving link. Check your local network.";
        }
    }

    private async Task HandleIncomingConnectionAsync(TlsSocketConnection connection, CancellationToken cancellationToken)
    {
        if (!IsListening && _hostedSendRequest == null)
        {
            await connection.DisposeAsync();
            return;
        }
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
                // Keep the session outcome visible after preparing the next invitation.
                // RefreshInvitation updates StatusMessage with link guidance, which used
                // to hide the actual transfer failure before it could be inspected.
                StatusMessage = result.Message;
            });
        }
    }

    private Task<TlsSocketListener> EnsurePairListenerAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (_listenerSupervisor.Listener is { } existing) return Task.FromResult(existing);
        if (OperatingSystem.IsLinux()) _listenerOwner = DesktopListenerOwnership.AcquireLinux();
        try
        {
            int boundPort = _listenerSupervisor.Start(ListeningPort);
            ListeningPort = boundPort;
            _listenerSupervisor.StartListenerLoop(
                HandleIncomingConnectionAsync, _lifetimeCts.Token,
                HandleProfile2ConnectionAsync);
            return Task.FromResult(_listenerSupervisor.Listener!);
        }
        catch
        {
            _listenerOwner?.Dispose();
            _listenerOwner = null;
            throw;
        }
    }

    private void ReleasePairListener()
    {
        if (IsListening)
        {
            OnUi(() => RefreshInvitation(announceReady: false));
            return;
        }
        _listenerSupervisor.Stop();
        _listenerOwner?.Dispose();
        _listenerOwner = null;
        _mdnsDiscovery.StopAdvertising();
    }

    private Task HandleProfile2ConnectionAsync(TlsSocketConnection connection, CancellationToken cancellationToken) =>
        DesktopProfile2Router.RouteAsync(connection, _pairing,
            IsListening ? HandleTrustedIncomingAsync : null, cancellationToken);

    private async Task HandleTrustedIncomingAsync(
        TlsSocketConnection connection,
        Profile2Message.SessionOpen open,
        CancellationToken cancellationToken)
    {
        await using (connection)
        {
            var request = new DesktopReceiveRequest(
                new QuarantineStagingStore(destinationRoot: DownloadDirectory),
                LocalDeviceName: DeviceName,
                TrustManager: _trustManager);
            await OnUiAsync(() =>
            {
                IsTransferActive = true;
                TransferTitle = "Receiving from paired device…";
            });
            DesktopTransferResult result = await _runtime.ReceiveTrustedAsync(
                connection, open, request, this, cancellationToken);
            await OnUiAsync(() =>
            {
                IsTransferActive = _runtime.Snapshot.IsActive;
                StatusMessage = result.Message;
                RefreshTrustedPeers();
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
        if (ev is TransferEvent.ProgressUpdated progressEvent)
        {
            if (Interlocked.Exchange(ref _coalescedProgressEvent, progressEvent) == null)
            {
                OnUi(() =>
                {
                    var latest = Interlocked.Exchange(ref _coalescedProgressEvent, null);
                    if (latest != null && Transfer.IsActive)
                    {
                        Transfer.ApplyEvent(latest);
                        TransferProgressFraction = latest.Progress.Fraction;
                        TransferredBytesText = latest.TransferredBytesText;
                        TransferRateText = latest.RateText;
                    }
                });
            }
            return;
        }

        if (ev is TransferEvent.Completed or TransferEvent.Failed)
        {
            Interlocked.Exchange(ref _coalescedProgressEvent, null);
            Interlocked.Exchange(ref _coalescedProgress, null);
        }

        OnUi(() =>
        {
            Transfer.ApplyEvent(ev);
            if (ev is TransferEvent.ItemStatusChanged item)
            {
                CurrentTransferFile = $"{item.ItemName} ({item.Status})";
            }
        });
    }

    // IDesktopTransferInteraction Implementation
    public Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken) =>
        _recipientDecision.RequestAsync(cancellationToken,
            () => { RecipientPeerName = peerName; IsRecipientModalVisible = true; },
            () => IsRecipientModalVisible = false);

    public Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
        _offerDecision.RequestAsync(cancellationToken,
            () =>
            {
                OfferPeerName = offer.PeerName;
                OfferSummaryText = $"{offer.Items.Count} file(s), total {FormatBytes(offer.TotalBytes)}";
                IsOfferModalVisible = true;
            },
            () => IsOfferModalVisible = false);

    public Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken) =>
        _sasDecision.RequestAsync(cancellationToken,
            () =>
            {
                SasPeerName = peer.DisplayName;
                // This is the verified persistent identity fingerprint, not a
                // session SAS. Never present a six-character hex prefix as a
                // six-digit mutual verification code.
                SasCode = string.Join(" ", Enumerable.Range(0, (peer.FingerprintHex.Length + 7) / 8)
                    .Select(i => peer.FingerprintHex.Substring(i * 8,
                        Math.Min(8, peer.FingerprintHex.Length - i * 8)).ToUpperInvariant()));
                IsSasModalVisible = true;
            },
            () => IsSasModalVisible = false);

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
        if (Interlocked.Exchange(ref _coalescedProgress, progress) != null)
        {
            return;
        }

        OnUi(() =>
        {
            var latest = Interlocked.Exchange(ref _coalescedProgress, null);
            if (latest == null) return;
            TransferProgressFraction = latest.Fraction;
            var (_, rateText, transferredText) = _rateCalculator.Update(latest.TransferredBytes, latest.TotalBytes);
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
        OnUi(() =>
        {
            Transfer.RecordVerifiedFile(entry);
            HistoryItems.Insert(0, ToHistoryItem(entry));
        });
    }

    private static LinuxHistoryItemViewModel ToHistoryItem(DesktopHistoryEntry entry) =>
        new(Guid.NewGuid().ToString("N"), entry.Timestamp.LocalDateTime,
            entry.Direction.ToString(), entry.PeerName, 1, entry.SizeBytes, "Completed", itemName: entry.ItemName);

    private void RefreshTrustedPeers()
    {
        TrustedPeers.Clear();
        SavedDeviceRows.Clear();
        if (_trustManager == null) return;
        foreach (var peer in _trustManager.GetTrustedPeers())
        {
            TrustedPeers.Add(peer);
            SavedDeviceRows.Add(new LinuxSavedDeviceRow(peer));
        }
    }

    private void ResolveSas(bool accepted)
    {
        _sasDecision.Resolve(accepted);
    }

    private void ResolveOffer(bool accepted)
    {
        _offerDecision.Resolve(accepted);
    }

    private void ResolveRecipient(bool accepted)
    {
        _recipientDecision.Resolve(accepted);
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
        if (Interlocked.Exchange(ref _disposeState, 1) != 0) return;
        OnUi(() =>
        {
            ResolveRecipient(false);
            ResolveOffer(false);
            ResolveSas(false);
        });
        _lifetimeCts.Cancel();
        _activeTransferCts?.Cancel();
        _listenerSupervisor.Dispose();
        _pairing.Dispose();
        PairQrImage?.Dispose();
        _listenerOwner?.Dispose();
        _listenerOwner = null;
        _mdnsDiscovery.StopAdvertising();
        _invitationManager.RevokeActive();
        try
        {
            _invitationManager.SnapshotProvider.NetworkAddressChanged -= OnNetworkAddressChanged;
        }
        catch
        {
            // Ignore
        }
        _mdnsDiscovery.Dispose();
        _lifetimeCts.Dispose();
    }
}
