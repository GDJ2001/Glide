using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Desktop.Pairing;
using Glide.Desktop.Services;
using Glide.Transport.Framing;
using Glide.Transport.Discovery;
using Glide.Transport.Sockets;
using Glide.Windows.UI.Common;
using Microsoft.Win32;

namespace Glide.Windows.UI.ViewModels;

public enum SendMethodChoice { None, QuickQr, Nearby, Trusted }
public enum PairDirectionChoice { UseInvitation, ShowThisPcQr }

public class MainViewModel : ViewModelBase, IDesktopTransferInteraction
{
    private string _currentTab = "Send";
    private readonly WindowsUiPreferencesStore _uiPreferences = new();
    private string _appearanceMode = "System";
    private string _peerHost = "127.0.0.1";
    private int _peerPort = LanDiscoveryConstants.DefaultPort;
    private bool _isListening;
    private int _listeningPort;
    private string _statusMessage = "Ready to share nearby.";
    private string _localIpText = "";

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
    private bool _pendingTrustedSend;
    public DesktopTransferPresentation Transfer { get; } = new();
    private readonly DesktopStateStore _stateStore = new();
    private readonly Stopwatch _transferStopwatch = new();
    private string _receiveDirectory = "";
    private int _listenerPortPreference = LanDiscoveryConstants.DefaultPort;

    private TlsSocketListener? _listener;
    private CancellationTokenSource? _invitationRotationCts;

    private readonly MdnsDiscoveryService _mdnsService = new();
    private readonly DesktopInvitationManager _pairInvitationManager = new();
    private readonly DesktopPairingCoordinator _pairing;
    private string _pairPin = "";
    private string _pairAddress = "";
    private string _pairOnlyLink = "";
    private ImageSource? _pairOnlyQrImage;
    private bool _isPairQrVisible;

    public DesktopPairingSnapshot Pairing => _pairing.Snapshot;
    public bool IsPairDiscovery => Pairing.Phase is DesktopPairingPhase.Scanning or DesktopPairingPhase.PeersFound or DesktopPairingPhase.Empty;
    public bool IsPairEmpty => Pairing.Phase == DesktopPairingPhase.Empty;
    public bool IsPairReady => Pairing.Phase == DesktopPairingPhase.TargetReady;
    public bool IsPairPinEntry => Pairing.Phase == DesktopPairingPhase.InitiatorPinEntry;
    public bool IsPairManualEntry => Pairing.Phase == DesktopPairingPhase.ManualEntry;
    public bool IsPairLinkEntry => Pairing.Phase == DesktopPairingPhase.UseLink;
    public bool IsPairApproval => Pairing.Phase == DesktopPairingPhase.TargetApproval;
    public bool IsPairVerifying => Pairing.Phase == DesktopPairingPhase.Verifying;
    public bool IsPairSuccess => Pairing.Phase == DesktopPairingPhase.Success;
    public bool IsPairFailed => Pairing.Phase == DesktopPairingPhase.Failed;
    public bool IsPairResult => Pairing.Phase is DesktopPairingPhase.Success or DesktopPairingPhase.Failed or DesktopPairingPhase.Verifying;
    public bool HasPairDiagnostic => !string.IsNullOrWhiteSpace(Pairing.Diagnostic);
    public bool IsPairQrVisible { get => _isPairQrVisible; set => SetProperty(ref _isPairQrVisible, value); }
    public string PairPin { get => _pairPin; set => SetProperty(ref _pairPin, value); }
    public string PairAddress { get => _pairAddress; set => SetProperty(ref _pairAddress, value); }
    public string PairOnlyLink { get => _pairOnlyLink; set => SetProperty(ref _pairOnlyLink, value); }
    public ImageSource? PairOnlyQrImage { get => _pairOnlyQrImage; private set => SetProperty(ref _pairOnlyQrImage, value); }
    public ObservableCollection<DiscoveredPeerViewModel> DiscoveredPeers { get; } = [];

    private bool _isPairModalVisible;
    private string _pairInputUri = "";
    private string _activeInvitationUri = "";
    private LanInvitation? _activeInvitation;
    private LanInvitation? _pairedInvitation;
    private readonly DispatcherTimer _pairingExpiryTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private DesktopSendRequest? _hostedSendRequest;
    private DiscoveredPeerViewModel? _selectedPeer;
    private TrustedDeviceViewModel? _selectedTrustedDevice;
    private SendMethodChoice _sendMethod;
    private PairDirectionChoice _pairDirection = PairDirectionChoice.UseInvitation;
    private bool _isTrustedDevicePickerVisible;
    private bool _isSavedDeviceConnectVisible;
    private string _pairingStatusText = "Paste a current invitation, or choose a nearby device below.";
    private readonly LanInvitationAuthority _invitationAuthority = new();
    private ImageSource? _pairingQrImage;
    private bool _isQrZoomModalVisible;

    public bool IsQrZoomModalVisible
    {
        get => _isQrZoomModalVisible;
        set => SetProperty(ref _isQrZoomModalVisible, value);
    }

    public ImageSource? PairingQrImage
    {
        get => _pairingQrImage;
        set => SetProperty(ref _pairingQrImage, value);
    }

    public ObservableCollection<TransferItemViewModel> SelectedFiles { get; } = [];
    public ActivityHistoryViewModel Activity { get; } = new();
    public DesktopConsentViewModel Consent { get; } = new(RunOnUi);
    public ObservableCollection<TrustedDeviceViewModel> TrustedDevices { get; } = [];
    public IReadOnlyList<string> AppearanceOptions { get; } = ["System", "Light", "Dark"];

    public RelayCommand SwitchTabCommand { get; }
    public RelayCommand AddFilesCommand { get; }
    public RelayCommand AddFolderCommand { get; }
    public RelayCommand RemoveFileCommand { get; }
    public RelayCommand ClearFilesCommand { get; }
    public RelayCommand SendFilesCommand { get; }
    public RelayCommand ToggleListenerCommand { get; }
    public RelayCommand BrowseReceiveFolderCommand { get; }
    public RelayCommand CancelTransferCommand { get; }
    public RelayCommand RetryTransferCommand { get; }
    public RelayCommand OpenTransferFolderCommand { get; }
    public RelayCommand OpenDownloadsCommand { get; }
    public RelayCommand OpenHistoryItemCommand { get; }
    public RelayCommand RevokeTrustedDeviceCommand { get; }
    public RelayCommand ShowPairModalCommand { get; }
    public RelayCommand CancelPairModalCommand { get; }
    public RelayCommand SubmitPairModalCommand { get; }
    public RelayCommand OpenReceiveFromInvitationCommand { get; }
    public RelayCommand PastePairingLinkCommand { get; }
    public RelayCommand ClearPairingCommand { get; }
    public RelayCommand SelectPeerCommand { get; }
    public RelayCommand SelectSendMethodCommand { get; }
    public RelayCommand ShowTrustedDevicePickerCommand { get; }
    public RelayCommand CloseTrustedDevicePickerCommand { get; }
    public RelayCommand SelectTrustedDeviceCommand { get; }
    public RelayCommand ConnectSavedDeviceCommand { get; }
    public RelayCommand CloseSavedDeviceConnectCommand { get; }
    public RelayCommand ShowSavedDeviceQrCommand { get; }
    public RelayCommand ShowQuickShareQrCommand { get; }
    public RelayCommand SelectPairDirectionCommand { get; }
    public RelayCommand RefreshPeersCommand { get; }
    public RelayCommand RefreshQrCommand { get; }
    public RelayCommand ToggleQrZoomCommand { get; }
    public RelayCommand CopyPairingLinkCommand { get; }
    public RelayCommand FindPairDevicesCommand { get; }
    public RelayCommand ReadyToPairCommand { get; }
    public RelayCommand CancelPairCommand { get; }
    public RelayCommand SelectPairPeerCommand { get; }
    public RelayCommand SubmitPairPinCommand { get; }
    public RelayCommand ShowPairAddressCommand { get; }
    public RelayCommand SubmitPairAddressCommand { get; }
    public RelayCommand ShowPairLinkCommand { get; }
    public RelayCommand SubmitPairLinkCommand { get; }
    public RelayCommand CopyPairOnlyLinkCommand { get; }
    public RelayCommand ShowPairQrCommand { get; }
    public RelayCommand PairRecoveryCommand { get; }
    public RelayCommand ApprovePairCommand { get; }
    public RelayCommand DeclinePairCommand { get; }

    public MainViewModel()
    {
        _transferRuntime.SnapshotChanged += (_, snapshot) => RunOnUi(() =>
        {
            bool trustedSend = _pendingTrustedSend && snapshot.Direction == DesktopTransferDirection.Send;
            if (Transfer.ApplySnapshot(snapshot, navigateOnPreparation: trustedSend)) CurrentTab = "Activity";
            OnPropertyChanged(nameof(IsTransferBannerVisible));
        });
        _transferRuntime.EventOccurred += (_, ev) => RunOnUi(() => Transfer.ApplyEvent(ev));
        _appearanceMode = _uiPreferences.Load().AppearanceMode;
        ApplyAppearance();
        try
        {
            DesktopPersistentState persisted = _stateStore.Load();
            _receiveDirectory = persisted.Settings.ReceiveDirectory;
            _listenerPortPreference = persisted.Settings.ListenerPort;
            foreach (DesktopHistoryEntry entry in persisted.History)
            {
                Activity.Entries.Add(ToHistoryViewModel(entry));
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
        RefreshTrustedDevices();
        _pairing = new DesktopPairingCoordinator(
            _trustManager, _pairInvitationManager, _mdnsService,
            () => Environment.MachineName, EnsurePairListenerAsync, ReleasePairListener,
            () => IsTransferActive || _transferRuntime.IsBusy);
        _pairing.Changed += snapshot => RunOnUi(() =>
        {
            if (snapshot.Phase is DesktopPairingPhase.Idle or DesktopPairingPhase.Success or DesktopPairingPhase.Failed)
                PairPin = "";
            if (snapshot.Phase != DesktopPairingPhase.TargetReady)
                IsPairQrVisible = false;
            PairOnlyQrImage = string.IsNullOrWhiteSpace(snapshot.QrUri)
                ? null : QrImageHelper.GenerateQrImageSource(snapshot.QrUri);
            OnPropertyChanged(nameof(Pairing));
            OnPropertyChanged(nameof(IsPairDiscovery));
            OnPropertyChanged(nameof(IsPairEmpty));
            OnPropertyChanged(nameof(IsPairReady));
            OnPropertyChanged(nameof(IsPairPinEntry));
            OnPropertyChanged(nameof(IsPairManualEntry));
            OnPropertyChanged(nameof(IsPairLinkEntry));
            OnPropertyChanged(nameof(IsPairApproval));
            OnPropertyChanged(nameof(IsPairVerifying));
            OnPropertyChanged(nameof(IsPairSuccess));
            OnPropertyChanged(nameof(IsPairFailed));
            OnPropertyChanged(nameof(IsPairResult));
            OnPropertyChanged(nameof(HasPairDiagnostic));
        });
        _pairing.PeerPaired += _ => RefreshTrustedDevices();
        _pairInvitationManager.SnapshotProvider.NetworkAddressChanged += (_, _) =>
            RunOnUi(() => { if (_pairing.IsReadyWindowActive) _pairing.Cancel(); });

        SwitchTabCommand = new RelayCommand(param => CurrentTab = param?.ToString() ?? "Send");
        FindPairDevicesCommand = new RelayCommand(() => _pairing.BeginFindDevices());
        ReadyToPairCommand = new RelayCommand(() => _ = _pairing.StartReadyAsync());
        CancelPairCommand = new RelayCommand(() => _pairing.Cancel());
        SelectPairPeerCommand = new RelayCommand(param =>
        {
            if (param is DiscoveredPeerViewModel peer && peer.IsPairReady)
                _pairing.SelectPeer(peer.Peer);
        });
        SubmitPairPinCommand = new RelayCommand(() => _ = _pairing.PairWithSelectedPeerAsync(PairPin));
        ShowPairAddressCommand = new RelayCommand(() => _pairing.ShowManualEntry());
        SubmitPairAddressCommand = new RelayCommand(() => _ = _pairing.PairWithAddressAsync(PairAddress, PairPin));
        ShowPairLinkCommand = new RelayCommand(() => _pairing.ShowLinkEntry());
        SubmitPairLinkCommand = new RelayCommand(() => _ = _pairing.PairWithLinkAsync(PairOnlyLink));
        CopyPairOnlyLinkCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrWhiteSpace(Pairing.QrUri)) Clipboard.SetText(Pairing.QrUri);
        });
        ShowPairQrCommand = new RelayCommand(() => IsPairQrVisible = !IsPairQrVisible);
        PairRecoveryCommand = new RelayCommand(() =>
        {
            if (string.Equals(Pairing.RecoveryAction, "Get a new code", StringComparison.Ordinal))
                _ = _pairing.StartReadyAsync();
            else
                _pairing.BeginFindDevices();
        });
        ApprovePairCommand = new RelayCommand(() => _pairing.Approve());
        DeclinePairCommand = new RelayCommand(() => _pairing.DeclineApproval());
        AddFilesCommand = new RelayCommand(ExecuteAddFiles);
        AddFolderCommand = new RelayCommand(ExecuteAddFolder);
        RemoveFileCommand = new RelayCommand(param =>
        {
            if (param is not TransferItemViewModel item || !SelectedFiles.Remove(item)) return;
            if (SelectedFiles.Count == 0)
            {
                ResetSendSelection();
                if (!IsTransferActive) StatusMessage = "Add files or a folder to get started.";
            }
            else if (!IsTransferActive)
            {
                StatusMessage = SelectedFiles.Count == 1
                    ? "1 file ready. Choose how to send it."
                    : $"{SelectedFiles.Count} files ready. Choose how to send them.";
            }
        });
        ClearFilesCommand = new RelayCommand(() =>
        {
            SelectedFiles.Clear();
            ResetSendSelection();
            if (!IsTransferActive) StatusMessage = "Add files or a folder to get started.";
        }, () => SelectedFiles.Count > 0);
        SendFilesCommand = new RelayCommand(
            () => _ = ExecuteSendFilesSafelyAsync(),
            () => IsSendActionVisible);
        ToggleListenerCommand = new RelayCommand(async () => await ToggleListenerAsync());
        BrowseReceiveFolderCommand = new RelayCommand(ExecuteBrowseReceiveFolder);
        CancelTransferCommand = new RelayCommand(() =>
        {
            _transferRuntime.CancelActiveTransfer();
            _activeTransferCts?.Cancel();
        });
        RetryTransferCommand = new RelayCommand(() =>
        {
            if (SelectedFiles.Count == 0) return;
            if (_sendMethod == SendMethodChoice.Trusted && _selectedTrustedDevice is not null)
                _ = ExecuteTrustedSendAsync();
            else
                _ = StartQuickShareSafelyAsync();
        });
        OpenTransferFolderCommand = new RelayCommand(() =>
        {
            if (!string.IsNullOrWhiteSpace(Transfer.ReceivedFolder))
                Process.Start(new ProcessStartInfo("explorer.exe", Transfer.ReceivedFolder) { UseShellExecute = true });
        });
        OpenDownloadsCommand = new RelayCommand(ExecuteOpenDownloads);
        OpenHistoryItemCommand = new RelayCommand(ExecuteOpenHistoryItem);
        RevokeTrustedDeviceCommand = new RelayCommand(ExecuteRevokeTrustedDevice);
        ShowPairModalCommand = new RelayCommand(() =>
        {
            IsTrustedDevicePickerVisible = false;
            SelectPairDirection(PairDirectionChoice.UseInvitation);
            CurrentTab = "Pair devices";
        });
        CancelPairModalCommand = new RelayCommand(() =>
        {
            bool wasShowingOutgoingQr = _hostedSendRequest != null;
            _hostedSendRequest = null;
            IsPairModalVisible = false;
            PairInputUri = "";
            if (wasShowingOutgoingQr && IsListening && _listener != null)
            {
                _invitationAuthority.RevokeActive();
                RefreshReceiverInvitation();
            }
            OnPropertyChanged(nameof(IsHostedSendQrVisible));
            OnPropertyChanged(nameof(PairingSheetTitle));
            OnPropertyChanged(nameof(PairingSheetInstruction));
            StatusMessage = SelectedFiles.Count > 0
                ? "Send paused. Your selected files are still ready."
                : "Pairing cancelled.";
        });
        SubmitPairModalCommand = new RelayCommand(ExecuteSubmitPairModal);
        OpenReceiveFromInvitationCommand = new RelayCommand(() =>
        {
            SelectPairDirection(PairDirectionChoice.UseInvitation);
            IsPairModalVisible = true;
        });
        PastePairingLinkCommand = new RelayCommand(() =>
        {
            if (TryReadPairingLinkFromClipboard(out string invitationUri))
            {
                PairInputUri = invitationUri;
                PairingStatusText = "Link pasted. Choose Use invitation to continue.";
            }
            else
            {
                PairingStatusText = "Copy a Glide pairing link first, then choose Paste link.";
            }
        });
        ClearPairingCommand = new RelayCommand(() =>
        {
            _pairedInvitation = null;
            _selectedPeer = null;
            _selectedTrustedDevice = null;
            IsSavedDeviceConnectVisible = false;
            PairingStatusText = "Paste a current invitation, or choose a nearby device below.";
            OnPropertyChanged(nameof(PairingSheetTitle));
            OnPropertyChanged(nameof(PairingSheetInstruction));
            NotifySendState();
            StatusMessage = "Pairing cleared. Choose a nearby device when you are ready.";
        });
        SelectSendMethodCommand = new RelayCommand(param =>
        {
            if (!Enum.TryParse(param?.ToString(), out SendMethodChoice method) ||
                method == SendMethodChoice.None)
            {
                return;
            }
            SelectSendMethod(method);
            if (method == SendMethodChoice.QuickQr)
            {
                _ = StartQuickShareSafelyAsync();
            }
            else if (method == SendMethodChoice.Nearby)
            {
                SelectPairDirection(PairDirectionChoice.UseInvitation);
                CurrentTab = "Pair devices";
                try { _mdnsService.QueryPeers(); }
                catch (Exception ex) { StatusMessage = $"Discovery is unavailable: {ex.Message}"; }
            }
            else
            {
                IsTrustedDevicePickerVisible = true;
            }
        });
        ShowQuickShareQrCommand = new RelayCommand(
            () => _ = StartQuickShareSafelyAsync(),
            () => SelectedFiles.Count > 0 && !IsTransferActive);
        SelectPairDirectionCommand = new RelayCommand(param =>
        {
            if (Enum.TryParse(param?.ToString(), out PairDirectionChoice direction))
                SelectPairDirection(direction);
        });
        ShowTrustedDevicePickerCommand = new RelayCommand(() =>
        {
            SelectSendMethod(SendMethodChoice.Trusted);
            IsTrustedDevicePickerVisible = true;
        });
        CloseTrustedDevicePickerCommand = new RelayCommand(() => IsTrustedDevicePickerVisible = false);
        SelectTrustedDeviceCommand = new RelayCommand(param =>
        {
            if (param is not TrustedDeviceViewModel device) return;
            SelectSendMethod(SendMethodChoice.Trusted);
            // Choosing a saved identity must never reuse another device's one-use invitation.
            _pairedInvitation = null;
            _selectedPeer = null;
            _selectedTrustedDevice = device;
            IsTrustedDevicePickerVisible = false;
            PairInputUri = string.Empty;
            PairingStatusText = $"{device.DisplayName} selected. Glide will verify its saved identity before sending.";
            StatusMessage = PairingStatusText;
            NotifySendState();
            OnPropertyChanged(nameof(SelectedTrustedDeviceName));
            OnPropertyChanged(nameof(PairingSheetTitle));
            OnPropertyChanged(nameof(PairingSheetInstruction));
            IsSavedDeviceConnectVisible = false;
        });
        ConnectSavedDeviceCommand = new RelayCommand(() =>
        {
            if (_selectedTrustedDevice is null || SelectedFiles.Count == 0 || IsTransferActive)
                return;
            IsSavedDeviceConnectVisible = false;
            PairInputUri = string.Empty;
            _ = ExecuteSendFilesSafelyAsync();
        });
        CloseSavedDeviceConnectCommand = new RelayCommand(() => IsSavedDeviceConnectVisible = false);
        ShowSavedDeviceQrCommand = new RelayCommand(() => _ = StartSavedDeviceQrSafelyAsync());
        SelectPeerCommand = new RelayCommand(param =>
        {
            if (param is DiscoveredPeerViewModel peer)
            {
                SelectSendMethod(SendMethodChoice.Nearby);
                _selectedPeer = peer;
                OnPropertyChanged(nameof(PairingSheetTitle));
                OnPropertyChanged(nameof(PairingSheetInstruction));
                PeerHost = peer.Host;
                PeerPort = peer.Port;
                _pairedInvitation = null;
                PairingStatusText = $"{peer.DeviceName} selected. Paste its current Glide invitation to continue.";
                StatusMessage = PairingStatusText;
                NotifySendState();
                SelectPairDirection(PairDirectionChoice.UseInvitation);
                CurrentTab = "Pair devices";
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
                if (_hostedSendRequest != null && _activeInvitation != null)
                {
                    _hostedSendRequest = _hostedSendRequest with
                    {
                        Invitation = _activeInvitation,
                        HostedInvitationAuthority = _invitationAuthority
                    };
                }
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

        SelectedFiles.CollectionChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(SelectedFilesSummary));
            NotifySendState();
        };
        DiscoveredPeers.CollectionChanged += (_, _) =>
            OnPropertyChanged(nameof(HasDiscoveredPeers));
        _pairingExpiryTimer.Tick += (_, _) =>
        {
            if (_pairedInvitation is null)
            {
                _pairingExpiryTimer.Stop();
            }
            else if (_pairedInvitation.IsExpired())
            {
                _pairedInvitation = null;
                _pairingExpiryTimer.Stop();
                PairingStatusText = "The invitation expired. Get a fresh code from the receiving device.";
                StatusMessage = PairingStatusText;
                NotifySendState();
            }
        };

        _mdnsService.PeerDiscovered += peer =>
        {
            Application.Current?.Dispatcher?.Invoke(() =>
            {
                if (!DiscoveredPeers.Any(p => p.Host == peer.Host && p.Port == peer.Port))
                {
                    DiscoveredPeers.Add(new DiscoveredPeerViewModel(peer));
                }
                else
                {
                    var existing = DiscoveredPeers.First(p => p.Host == peer.Host && p.Port == peer.Port);
                    DiscoveredPeers.Remove(existing);
                    DiscoveredPeers.Add(new DiscoveredPeerViewModel(peer));
                }
                _pairing.NotifyPeerCount(DiscoveredPeers.Count(p => p.IsPairReady));
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
                _pairing.NotifyPeerCount(DiscoveredPeers.Count(p => p.IsPairReady));
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
        set
        {
            if (SetProperty(ref _currentTab, value))
            {
                OnPropertyChanged(nameof(IsSendSelected));
                OnPropertyChanged(nameof(IsReceiveSelected));
                OnPropertyChanged(nameof(IsActivitySelected));
                OnPropertyChanged(nameof(IsSettingsSelected));
                OnPropertyChanged(nameof(IsPairDevicesSelected));
                OnPropertyChanged(nameof(IsTransferBannerVisible));
                if (value != "Pair devices") _pairing.OnPageLeft();
            }
        }
    }

    public bool IsSendSelected => CurrentTab == "Send";
    public bool IsReceiveSelected => CurrentTab == "Receive";
    public bool IsActivitySelected => CurrentTab == "Activity";
    public bool IsTransferBannerVisible => Transfer.IsActive && !IsActivitySelected;
    public bool IsSettingsSelected => CurrentTab == "Settings";
    public bool IsPairDevicesSelected => CurrentTab == "Pair devices";
    public bool IsUseInvitationSelected => _pairDirection == PairDirectionChoice.UseInvitation;
    public bool IsShowThisPcQrSelected => _pairDirection == PairDirectionChoice.ShowThisPcQr;

    public SendMethodChoice SendMethod => _sendMethod;
    public bool IsQuickQrSelected => SendMethod == SendMethodChoice.QuickQr;
    public bool IsNearbySelected => SendMethod == SendMethodChoice.Nearby;
    public bool IsTrustedSelected => SendMethod == SendMethodChoice.Trusted;
    public bool HasSelectedFiles => SelectedFiles.Count > 0;
    public bool HasDiscoveredPeers => DiscoveredPeers.Count > 0;
    public bool HasTrustedDevices => TrustedDevices.Count > 0;
    public bool IsSendActionVisible =>
        !IsTransferActive && HasSelectedFiles &&
        ((SendMethod == SendMethodChoice.Trusted && _selectedTrustedDevice is not null) ||
         (SendMethod == SendMethodChoice.Nearby && _pairedInvitation is not null && !_pairedInvitation.IsExpired()));
    public string SelectedSendMethodText => SendMethod switch
    {
        SendMethodChoice.Nearby when _pairedInvitation is not null =>
            $"{_pairedInvitation.Name} is ready. Its identity will be checked when you send.",
        SendMethodChoice.Nearby when _selectedPeer is not null =>
            $"Selected {_selectedPeer.DeviceName}. Complete pairing to enable Send.",
        SendMethodChoice.Nearby => "Find a nearby device, then use its current invitation.",
        SendMethodChoice.Trusted when _selectedTrustedDevice is not null =>
            $"Selected {_selectedTrustedDevice.DisplayName}. Glide will verify its identity before sending.",
        _ => string.Empty
    };
    public bool HasSelectedSendMethodText => !string.IsNullOrWhiteSpace(SelectedSendMethodText);
    public bool IsTrustedDevicePickerVisible
    {
        get => _isTrustedDevicePickerVisible;
        set => SetProperty(ref _isTrustedDevicePickerVisible, value);
    }

    public bool IsSavedDeviceConnectVisible
    {
        get => _isSavedDeviceConnectVisible;
        set => SetProperty(ref _isSavedDeviceConnectVisible, value);
    }

    public string SelectedTrustedDeviceName => _selectedTrustedDevice?.DisplayName ?? "saved device";

    public string AppearanceMode
    {
        get => _appearanceMode;
        set
        {
            if (SetProperty(ref _appearanceMode, value))
            {
                _uiPreferences.Save(new WindowsUiPreferences(value));
                ApplyAppearance();
            }
        }
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
        set
        {
            if (SetProperty(ref _isListening, value))
            {
                OnPropertyChanged(nameof(ReceiveStatusTitle));
                OnPropertyChanged(nameof(ReceiveActionText));
            }
        }
    }

    public string ReceiveStatusTitle => IsListening ? "Ready to receive" : "Receiving is off";
    public string ReceiveActionText => IsListening ? "Stop receiving" : "Start receiving";

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

    public string LocalIdentityFingerprint
    {
        get => _localIdentityFingerprint;
        set => SetProperty(ref _localIdentityFingerprint, value);
    }

    public int TrustedDeviceCount
    {
        get => _trustedDeviceCount;
        set
        {
            if (SetProperty(ref _trustedDeviceCount, value))
            {
                OnPropertyChanged(nameof(TrustedDevicesSummary));
                OnPropertyChanged(nameof(HasTrustedDevices));
            }
        }
    }

    public string TrustedDevicesSummary => TrustedDeviceCount switch
    {
        0 => "No trusted devices yet. You can approve one after connecting.",
        1 => "1 device trusted for future connections.",
        _ => $"{TrustedDeviceCount} devices trusted for future connections."
    };

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
        ? IsHostedSendQrVisible
            ? _selectedTrustedDevice is null
                ? "Scan with Glide on your phone"
                : $"Connect {SelectedTrustedDeviceName}"
            : "Connect to the receiving device"
        : $"Pair with {_selectedPeer.DeviceName}";

    public string PairingSheetInstruction => _selectedPeer is null
        ? IsHostedSendQrVisible
            ? _selectedTrustedDevice is null
                ? "On the phone, choose Receive files, then Scan PC QR code. Glide will ask the phone to approve the selected files before anything is sent."
                : $"On {SelectedTrustedDeviceName}, scan this code in Glide. Its saved identity must match before files are sent."
            : "On the receiving device, show its Glide QR code and paste the invite below. Both devices must be on the same local network."
        : $"On {_selectedPeer.DeviceName}, open Receive files > Show QR code > Copy invite. Paste that invite below. Glide checks that it belongs to the nearby device you selected.";

    public bool IsHostedSendQrVisible => _hostedSendRequest != null && PairingQrImage != null;

    public string SendActionText => _selectedTrustedDevice is not null
        ? $"Send to {_selectedTrustedDevice.DisplayName}"
        : _pairedInvitation is not null
            ? $"Send to {_pairedInvitation.Name}"
            : "Send files";

    public string SelectedFilesSummary => SelectedFiles.Count switch
    {
        0 => "Add files above to begin.",
        1 => $"1 file · {TransferItemViewModel.FormatSize(SelectedFiles[0].SizeBytes)}",
        _ => $"{SelectedFiles.Count} files · {TransferItemViewModel.FormatSize(SelectedFiles.Sum(item => item.SizeBytes))}"
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
            NotifySendState();
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
            StatusMessage = SelectedFiles.Count == 1
                ? "1 file ready. Choose how to send it."
                : $"{SelectedFiles.Count} files ready. Choose how to send them.";
        }
        CommandManager.InvalidateRequerySuggested();
    }

    private void SelectSendMethod(SendMethodChoice method)
    {
        if (_sendMethod == method) return;

        _sendMethod = method;
        _pairedInvitation = null;
        _selectedPeer = null;
        _selectedTrustedDevice = null;
        IsSavedDeviceConnectVisible = false;
        PairInputUri = string.Empty;
        PairingStatusText = "Paste a current invitation, or choose a nearby device below.";
        NotifySendState();
    }

    private void SelectPairDirection(PairDirectionChoice direction)
    {
        if (_pairDirection == direction) return;
        _pairDirection = direction;
        OnPropertyChanged(nameof(IsUseInvitationSelected));
        OnPropertyChanged(nameof(IsShowThisPcQrSelected));
    }

    private void ResetSendSelection()
    {
        _sendMethod = SendMethodChoice.None;
        _pairedInvitation = null;
        _selectedPeer = null;
        _selectedTrustedDevice = null;
        IsSavedDeviceConnectVisible = false;
        PairInputUri = string.Empty;
        PairingStatusText = "Paste a current invitation, or choose a nearby device below.";
        NotifySendState();
    }

    private void NotifySendState()
    {
        OnPropertyChanged(nameof(HasSelectedFiles));
        OnPropertyChanged(nameof(IsQuickQrSelected));
        OnPropertyChanged(nameof(IsNearbySelected));
        OnPropertyChanged(nameof(IsTrustedSelected));
        OnPropertyChanged(nameof(IsSendActionVisible));
        OnPropertyChanged(nameof(SelectedSendMethodText));
        OnPropertyChanged(nameof(HasSelectedSendMethodText));
        OnPropertyChanged(nameof(SendActionText));
        CommandManager.InvalidateRequerySuggested();
    }

    private async Task StartQuickShareSafelyAsync()
    {
        if (SelectedFiles.Count == 0 || IsTransferActive) return;
        if (_sendMethod != SendMethodChoice.QuickQr)
            SelectSendMethod(SendMethodChoice.QuickQr);
        if (IsPairModalVisible && _hostedSendRequest != null) return;

        try
        {
            await StartHostedSendPairingAsync();
        }
        catch (Exception ex)
        {
            StatusMessage = $"Could not show the QR code: {ex.Message}";
        }
    }

    private async Task StartSavedDeviceQrSafelyAsync()
    {
        if (_selectedTrustedDevice is null || SelectedFiles.Count == 0 || IsTransferActive)
            return;
        try
        {
            await StartHostedSendPairingAsync();
            if (IsPairModalVisible)
                IsSavedDeviceConnectVisible = false;
        }
        catch (Exception ex)
        {
            PairingStatusText = $"Could not show the QR code: {ex.Message}";
        }
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

    private void ExecuteAddFolder()
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Select a folder to send with Glide"
        };

        if (dialog.ShowDialog() == true)
        {
            AddDroppedFiles([dialog.FolderName]);
        }
    }

    private void ExecuteSubmitPairModal()
    {
        SelectPairDirection(PairDirectionChoice.UseInvitation);
        if (string.IsNullOrWhiteSpace(PairInputUri))
        {
            StatusMessage = "Scan a Glide QR code or paste a pairing code to continue.";
            return;
        }

        string trimmedUri = PairInputUri.Trim();
        if (TryApplyPairingInvitation(trimmedUri, showErrors: true))
        {
            IsPairModalVisible = false;
            PairInputUri = "";
            if (SelectedFiles.Count == 0 || string.Equals(CurrentTab, "Receive", StringComparison.OrdinalIgnoreCase))
            {
                if (_pairedInvitation != null)
                {
                    _ = ExecuteReceiveFromInvitationSafelyAsync(_pairedInvitation);
                }
            }
            else
            {
                CurrentTab = "Send";
                NotifySendState();
            }
        }
    }

    private async Task ExecuteReceiveFromInvitationSafelyAsync(LanInvitation invitation)
    {
        if (IsTransferActive) return;

        _activeTransferCts?.Dispose();
        _activeTransferCts = new CancellationTokenSource();
        var cancellationToken = _activeTransferCts.Token;

        try
        {
            RunOnUi(() =>
            {
                CurrentTab = "Receive";
                IsTransferActive = true;
                TransferTitle = $"Connecting to {invitation.Name}...";
                TotalProgress = 0;
                _transferStopwatch.Restart();
            });

            var request = new DesktopReceiveRequest(
                StagingStore: new QuarantineStagingStore(destinationRoot: ReceiveDirectory),
                LocalDeviceName: Environment.MachineName,
                TrustManager: _trustManager,
                Invitation: invitation);

            DesktopTransferResult result = await _transferRuntime.ReceiveFromInvitationAsync(
                request,
                this,
                cancellationToken);

            RunOnUi(() => StatusMessage = result.Message);
        }
        catch (OperationCanceledException)
        {
            RunOnUi(() => StatusMessage = "Receive cancelled.");
        }
        catch (Exception ex)
        {
            RunOnUi(() => StatusMessage = $"Receive failed: {ex.Message}");
        }
        finally
        {
            RunOnUi(() =>
            {
                _transferStopwatch.Stop();
                IsTransferActive = false;
                Consent.IsOfferModalVisible = false;
                _pairedInvitation = null;
            });
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
            if (SendMethod is SendMethodChoice.None or SendMethodChoice.QuickQr)
                SelectSendMethod(SendMethodChoice.Nearby);
            _pairedInvitation = invitation;
            _pairingExpiryTimer.Start();
            PairingStatusText = $"Invitation ready for {invitation.Name}. Glide will verify the device before sending.";
            StatusMessage = PairingStatusText;
            NotifySendState();
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
            if (!_pairing.IsReadyWindowActive) _mdnsService.StopAdvertising();
            _invitationAuthority.RevokeActive();
            ActiveInvitationUri = "";
            _activeInvitation = null;
            if (!_pairing.IsReadyWindowActive)
            {
                _listener?.Dispose();
                _listener = null;
            }
            IsListening = false;
            StatusMessage = "Receiving is off. Turn it on in Receive when you are ready.";
            return;
        }

        // Scan only a bounded portion of the configured destination, off the UI
        // thread. Old verified commit copies can remain after an interrupted run.
        try
        {
            await Task.Run(() =>
            {
                var store = new QuarantineStagingStore(destinationRoot: ReceiveDirectory);
                store.PurgeAbandonedCommitFiles();
                store.PurgeAbandonedQuarantineSessions();
            });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            StatusMessage = "The receiving folder is unavailable. Choose a safe folder in Settings and try again.";
            return;
        }

        bool wasPairListener = _listener != null;
        if (_listener == null)
        {
            try { _listener = new TlsSocketListener(ListenerPortPreference); }
            catch { _listener = new TlsSocketListener(0); }
        }

        ListeningPort = _listener.BoundPort;
        IsListening = true;

        RefreshReceiverInvitation();
        _invitationRotationCts?.Dispose();
        _invitationRotationCts =
            new CancellationTokenSource();

        StatusMessage = "Ready to share on this network.";
        _ = Task.Run(
            async () =>
                await RunInvitationRotationLoopAsync(
                    _invitationRotationCts.Token));
        if (!wasPairListener) _ = Task.Run(RunListenerLoopAsync);
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
                    !IsTransferActive &&
                    _hostedSendRequest == null &&
                    !_pairing.IsReadyWindowActive)
                {
                    _invitationAuthority.RevokeActive();
                    RefreshReceiverInvitation();
                    StatusMessage = "Ready to share on this network.";
                }
            });
        }
    }

    private async Task RunListenerLoopAsync()
    {
        while (_listener != null)
        {
            try
            {
                var conn = await _listener.AcceptConnectionAsync();
                LanSessionDispatchKind kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn);
                if (kind == LanSessionDispatchKind.Profile2Gld2)
                {
                    await DesktopProfile2Router.RouteAsync(
                        conn, _pairing, IsListening ? HandleTrustedIncomingAsync : null,
                        CancellationToken.None);
                }
                else if (kind == LanSessionDispatchKind.Profile1LanRecord &&
                         (IsListening || _hostedSendRequest != null))
                {
                    await HandleInboundTransferAsync(conn);
                }
                else
                {
                    await conn.DisposeAsync();
                }
            }
            catch (Exception ex)
            {
                if (_listener == null)
                {
                    break;
                }
                RunOnUi(() => StatusMessage = $"Inbound connection failed: {ex.Message}. Listening on port {ListeningPort}...");
            }
        }
    }

    private Task<TlsSocketListener> EnsurePairListenerAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (_listener is { } existing) return Task.FromResult(existing);
        try { _listener = new TlsSocketListener(ListenerPortPreference); }
        catch { _listener = new TlsSocketListener(0); }
        ListeningPort = _listener.BoundPort;
        _ = Task.Run(RunListenerLoopAsync);
        return Task.FromResult(_listener);
    }

    private void ReleasePairListener()
    {
        if (IsListening)
        {
            RunOnUi(RefreshReceiverInvitation);
            return;
        }
        _mdnsService.StopAdvertising();
        _listener?.Dispose();
        _listener = null;
    }

    private async Task HandleTrustedIncomingAsync(
        TlsSocketConnection connection,
        Profile2Message.SessionOpen open,
        CancellationToken cancellationToken)
    {
        await using (connection)
        {
            RunOnUi(() =>
            {
                CurrentTab = "Receive";
                IsTransferActive = true;
                TransferTitle = "Receiving from paired device…";
            });
            try
            {
                var request = new DesktopReceiveRequest(
                    new QuarantineStagingStore(destinationRoot: ReceiveDirectory),
                    LocalDeviceName: Environment.MachineName,
                    TrustManager: _trustManager);
                DesktopTransferResult result = await _transferRuntime.ReceiveTrustedAsync(
                    connection, open, request, this, cancellationToken);
                RunOnUi(() => StatusMessage = result.Message);
            }
            finally
            {
                RunOnUi(() =>
                {
                    IsTransferActive = _transferRuntime.Snapshot.IsActive;
                    RefreshTrustedDevices();
                });
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
                DesktopSendRequest? hostedRequest = _hostedSendRequest;
                RunOnUi(() =>
                {
                    IsQrZoomModalVisible = false;
                    CurrentTab = hostedRequest == null ? "Receive" : "Send";
                    IsTransferActive = true;
                    TransferTitle = hostedRequest == null
                        ? "Incoming connection..."
                        : "Phone connected. Preparing transfer...";
                    TotalProgress = 0;
                    _transferStopwatch.Restart();
                });

                DesktopTransferResult result;
                if (hostedRequest != null)
                {
                    result = await _transferRuntime.SendHostedAsync(
                        conn,
                        hostedRequest with { HostedInvitationAuthority = _invitationAuthority },
                        this,
                        cancellationToken);
                }
                else
                {
                    result = await _transferRuntime.ReceiveAsync(
                        conn,
                        new DesktopReceiveRequest(
                            new QuarantineStagingStore(
                                destinationRoot: ReceiveDirectory),
                            _invitationAuthority,
                            LocalDeviceName: Environment.MachineName,
                            TrustManager: _trustManager),
                        this,
                        cancellationToken);
                }

                RunOnUi(() => StatusMessage = result.Message);
            }
            finally
            {
                RunOnUi(() =>
                {
                    _transferStopwatch.Stop();
                    IsTransferActive = false;
                    Consent.IsOfferModalVisible = false;
                    _hostedSendRequest = null;
                    IsPairModalVisible = false;
                    OnPropertyChanged(nameof(IsHostedSendQrVisible));
                    OnPropertyChanged(nameof(PairingSheetTitle));
                    OnPropertyChanged(nameof(PairingSheetInstruction));
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
        if (SendMethod == SendMethodChoice.Trusted)
        {
            await ExecuteTrustedSendAsync();
            return;
        }
        if (SendMethod is not (SendMethodChoice.Nearby or SendMethodChoice.Trusted) ||
            _pairedInvitation == null ||
            (SendMethod == SendMethodChoice.Trusted && _selectedTrustedDevice is null))
        {
            StatusMessage = "Choose a sending method and complete pairing before sending.";
            return;
        }
        if (_pairedInvitation.IsExpired())
        {
            _pairedInvitation = null;
            NotifySendState();
            StatusMessage = "That invitation expired. Ask the receiving device for a fresh one.";
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
                    TrustManager: _trustManager,
                    ExpectedPeerFingerprintHex: _selectedTrustedDevice?.FingerprintHex),
                this,
                cancellationToken);

            StatusMessage = result.Message;
        }
        finally
        {
            _transferStopwatch.Stop();
            IsTransferActive = false;
            Consent.IsOfferModalVisible = false;
            // LAN invitations are short-lived bearer credentials and are consumed by a
            // connection attempt. Never present a stale invitation as ready for another send.
            _pairedInvitation = null;
            PairingStatusText = "Transfer finished. Use a fresh invitation to send again.";
            NotifySendState();
        }
    }

    private async Task ExecuteTrustedSendAsync()
    {
        TrustedDeviceViewModel? peer = _selectedTrustedDevice;
        if (peer == null || _trustManager == null)
        {
            StatusMessage = "Choose a paired device to send to.";
            return;
        }

        _activeTransferCts?.Dispose();
        _activeTransferCts = new CancellationTokenSource();
        try
        {
            IsTransferActive = true;
            TransferTitle = $"Connecting to {peer.DisplayName}…";
            TotalProgress = 0;
            _transferStopwatch.Restart();

            var candidates = DiscoveredPeers
                .Where(p => p.SupportsTrustedReconnect &&
                    string.Equals(p.DeviceName, peer.DisplayName, StringComparison.Ordinal))
                .Select(p => new LanEndpoint(p.Host, p.Port))
                .ToList();
            var files = SelectedFiles
                .Select(item => new DesktopTransferFile(item.FilePath, item.Name))
                .ToList();
            var request = new DesktopSendRequest(
                Host: peer.LastKnownHost ?? "",
                Port: peer.LastKnownPort ?? 0,
                Files: files,
                LocalDeviceName: Environment.MachineName,
                TrustManager: _trustManager,
                ExpectedPeerFingerprintHex: peer.FingerprintHex,
                CandidateEndpoints: candidates.Count > 0 ? candidates : null);
            _pendingTrustedSend = true;
            DesktopTransferResult result = await _transferRuntime.SendTrustedAsync(
                request, this, _activeTransferCts.Token);
            StatusMessage = result.Message;
            if (result.Success) RefreshTrustedDevices();
        }
        finally
        {
            _pendingTrustedSend = false;
            _transferStopwatch.Stop();
            IsTransferActive = false;
            Consent.IsOfferModalVisible = false;
            _activeTransferCts?.Dispose();
            _activeTransferCts = null;
            NotifySendState();
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

    private async Task StartHostedSendPairingAsync()
    {
        if (!IsListening || _listener == null)
        {
            await ToggleListenerAsync();
        }

        if (!IsListening || _listener == null)
        {
            StatusMessage = "Glide could not start local receiving. Check the network and try again.";
            return;
        }

        var files = SelectedFiles
            .Select(item => new DesktopTransferFile(item.FilePath, item.Name))
            .ToList();
        if (files.Count == 0)
        {
            StatusMessage = "Select at least one file before showing a QR code.";
            return;
        }

        _invitationAuthority.RevokeActive();
        RefreshReceiverInvitation();
        if (_activeInvitation == null)
        {
            StatusMessage = "Glide could not create a pairing QR code. Try again.";
            return;
        }

        _hostedSendRequest = new DesktopSendRequest(
            Host: string.Empty,
            Port: 0,
            Files: files,
            Invitation: _activeInvitation,
            LocalDeviceName: Environment.MachineName,
            TrustManager: _trustManager,
            ExpectedPeerFingerprintHex: _selectedTrustedDevice?.FingerprintHex,
            HostedInvitationAuthority: _invitationAuthority);
        PairInputUri = string.Empty;
        IsPairModalVisible = true;
        OnPropertyChanged(nameof(IsHostedSendQrVisible));
        OnPropertyChanged(nameof(PairingSheetTitle));
        OnPropertyChanged(nameof(PairingSheetInstruction));
        StatusMessage = _selectedTrustedDevice is null
            ? "Scan the QR code with Glide on the receiving phone."
            : $"Waiting for {SelectedTrustedDeviceName} to scan the QR code.";
    }

    public async Task<bool> ConfirmOfferAsync(
        DesktopOffer offer,
        CancellationToken cancellationToken)
    {
        RunOnUi(() => TransferTitle = $"Incoming transfer from {offer.PeerName}");
        return await Consent.ConfirmOfferAsync(offer, cancellationToken);
    }

    public async Task<bool> ConfirmRecipientAsync(
        string peerName,
        CancellationToken cancellationToken)
    {
        RunOnUi(() =>
        {
            // The pairing sheet must not cover the next identity-enrolment
            // prompt. Leaving it open made a successful QR connection look
            // like an automatic cancellation: identity verification waited
            // for a confirmation that the user could not see.
            IsPairModalVisible = false;
            StatusMessage = $"{peerName} connected. Confirm the device, then choose whether to remember it.";
        });
        return await Consent.ConfirmRecipientAsync(peerName, cancellationToken);
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

        RunOnUi(() => TransferTitle = $"Verify identity for {peer.DisplayName}");
        return await Consent.ConfirmTrustEnrollmentAsync(peer.DisplayName,
            FormatFingerprint(peer.FingerprintHex), cancellationToken);
    }

    public void OnInvitationConsumed(DesktopTransferDirection direction)
    {
        RunOnUi(() =>
        {
            if (direction == DesktopTransferDirection.Send)
            {
                _pairedInvitation = null;
                NotifySendState();
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
            Transfer.RecordVerifiedFile(entry);
            Activity.Entries.Insert(
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
                RefreshTrustedDevices();
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                $"Failed to refresh persistent trust summary: {ex.Message}");
        }
    }

    private void RefreshTrustedDevices()
    {
        DesktopPersistentTrustManager? manager = _trustManager;
        if (manager == null)
        {
            return;
        }

        try
        {
            IReadOnlyList<DesktopTrustedPeer> peers = manager.GetTrustedPeers();
            RunOnUi(() =>
            {
                TrustedDevices.Clear();
                foreach (DesktopTrustedPeer peer in peers.OrderBy(peer => peer.DisplayName))
                {
                    TrustedDevices.Add(new TrustedDeviceViewModel(peer));
                }

                TrustedDeviceCount = TrustedDevices.Count;
                if (_selectedTrustedDevice is not null &&
                    !TrustedDevices.Any(device => device.FingerprintHex == _selectedTrustedDevice.FingerprintHex))
                {
                    _selectedTrustedDevice = null;
                    _pairedInvitation = null;
                    NotifySendState();
                }
            });
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to read trusted devices: {ex.Message}");
        }
    }

    private void ExecuteRevokeTrustedDevice(object? parameter)
    {
        if (parameter is not TrustedDeviceViewModel device || _trustManager == null)
        {
            return;
        }

        try
        {
            if (_trustManager.RevokePeer(device.FingerprintHex))
            {
                StatusMessage = $"Removed {device.DisplayName} from trusted devices.";
                RefreshTrustedDevices();
            }
        }
        catch (Exception ex)
        {
            StatusMessage = $"Could not remove trusted device: {ex.Message}";
        }
    }

    private void ApplyAppearance()
    {
        if (SystemParameters.HighContrast)
        {
            SetBrush("AppBackgroundBrush", SystemColors.WindowColor);
            SetBrush("SidebarBrush", SystemColors.WindowColor);
            SetBrush("SurfaceBrush", SystemColors.WindowColor);
            SetBrush("SurfaceRaisedBrush", SystemColors.ControlColor);
            SetBrush("SurfaceSelectedBrush", SystemColors.HighlightColor);
            SetBrush("PrimaryBrush", SystemColors.HighlightColor);
            SetBrush("PrimaryHoverBrush", SystemColors.HighlightColor);
            SetBrush("TextPrimaryBrush", SystemColors.WindowTextColor);
            SetBrush("TextSecondaryBrush", SystemColors.WindowTextColor);
            SetBrush("BorderBrush", SystemColors.WindowTextColor);
            SetBrush("FocusBrush", SystemColors.HighlightColor);
            return;
        }

        bool dark = AppearanceMode == "Dark" ||
            (AppearanceMode == "System" && IsSystemDark());
        if (dark)
        {
            SetBrush("AppBackgroundBrush", ColorFrom("0F172A"));
            SetBrush("SidebarBrush", ColorFrom("111C31"));
            SetBrush("SurfaceBrush", ColorFrom("16233A"));
            SetBrush("SurfaceRaisedBrush", ColorFrom("1D2D47"));
            SetBrush("SurfaceSelectedBrush", ColorFrom("173B7D"));
            SetBrush("PrimaryBrush", ColorFrom("6D98FF"));
            SetBrush("PrimaryHoverBrush", ColorFrom("8AADFF"));
            SetBrush("TextPrimaryBrush", ColorFrom("F3F7FF"));
            SetBrush("TextSecondaryBrush", ColorFrom("B7C3D8"));
            SetBrush("BorderBrush", ColorFrom("2A3B57"));
            SetBrush("SuccessBrush", ColorFrom("63D7A8"));
            SetBrush("SuccessSurfaceBrush", ColorFrom("123B32"));
            SetBrush("WarningBrush", ColorFrom("FFD184"));
            SetBrush("WarningSurfaceBrush", ColorFrom("493315"));
            SetBrush("ErrorBrush", ColorFrom("FF9EAA"));
            SetBrush("ErrorSurfaceBrush", ColorFrom("4D202A"));
            SetBrush("FocusBrush", ColorFrom("9EBAFF"));
        }
        else
        {
            SetBrush("AppBackgroundBrush", ColorFrom("F6F8FC"));
            SetBrush("SidebarBrush", Colors.White);
            SetBrush("SurfaceBrush", Colors.White);
            SetBrush("SurfaceRaisedBrush", ColorFrom("F1F5FB"));
            SetBrush("SurfaceSelectedBrush", ColorFrom("E8F0FF"));
            SetBrush("PrimaryBrush", ColorFrom("2457E6"));
            SetBrush("PrimaryHoverBrush", ColorFrom("1D46BF"));
            SetBrush("TextPrimaryBrush", ColorFrom("14213D"));
            SetBrush("TextSecondaryBrush", ColorFrom("55627A"));
            SetBrush("BorderBrush", ColorFrom("DCE4F2"));
            SetBrush("SuccessBrush", ColorFrom("087E5B"));
            SetBrush("SuccessSurfaceBrush", ColorFrom("E7F7F0"));
            SetBrush("WarningBrush", ColorFrom("A15C00"));
            SetBrush("WarningSurfaceBrush", ColorFrom("FFF4DE"));
            SetBrush("ErrorBrush", ColorFrom("C42B3A"));
            SetBrush("ErrorSurfaceBrush", ColorFrom("FDECEE"));
            SetBrush("FocusBrush", ColorFrom("2457E6"));
        }
    }

    private static bool IsSystemDark()
    {
        try
        {
            object? value = Microsoft.Win32.Registry.GetValue(
                @"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
                "AppsUseLightTheme",
                1);
            return value is int setting && setting == 0;
        }
        catch
        {
            return false;
        }
    }

    private static Color ColorFrom(string hex) =>
        (Color)ColorConverter.ConvertFromString($"#{hex}");

    private static void SetBrush(string key, Color color)
    {
        if (Application.Current is not null)
        {
            Application.Current.Resources[key] = new SolidColorBrush(color);
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

    private async void ExecuteBrowseReceiveFolder()
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
            try
            {
                await new QuarantineStagingStore(destinationRoot: dialog.FolderName)
                    .VerifyDestinationWritableAsync();
                ReceiveDirectory = dialog.FolderName;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
            {
                StatusMessage = "That folder cannot receive files. Choose a writable folder.";
            }
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
                _pairing.ActiveWindowPresenceId ?? canonicalInvitation.Presence,
            pairReady: _pairing.IsReadyWindowActive,
            trustedReconnectSupported: true);
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

    private void ExecuteOpenHistoryItem(object? parameter)
    {
        if (parameter is not HistoryItemViewModel entry)
        {
            return;
        }

        string target = entry.Direction == "Received" && !string.IsNullOrWhiteSpace(entry.TargetPath)
            ? Path.GetDirectoryName(entry.TargetPath) ?? ReceiveDirectory
            : ReceiveDirectory;

        try
        {
            if (!Directory.Exists(target))
            {
                StatusMessage = "That folder is no longer available.";
                return;
            }

            Process.Start(new ProcessStartInfo { FileName = target, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            StatusMessage = $"Could not open folder: {ex.Message}";
        }
    }

}
