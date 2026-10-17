using System.Globalization;
using System.Text;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Pairing;
using Glide.Transport.Sockets;

namespace Glide.Desktop.Pairing;

/// <summary>Visible states of the single active pairing panel.</summary>
public enum DesktopPairingPhase
{
    /// <summary>Header only: nothing active yet.</summary>
    Idle,
    Scanning,
    PeersFound,
    Empty,
    TargetReady,
    InitiatorPinEntry,
    ManualEntry,
    UseLink,
    TargetApproval,
    Verifying,
    Success,
    Failed
}

/// <summary>Immutable view of the coordinator for presentation layers. Never contains secrets other than the on-screen code.</summary>
public sealed record DesktopPairingSnapshot(
    DesktopPairingPhase Phase,
    string PeerName,
    string Code,
    int SecondsRemaining,
    string QrUri,
    string Message,
    string Diagnostic,
    string RecoveryAction)
{
    public static DesktopPairingSnapshot Initial { get; } =
        new(DesktopPairingPhase.Idle, "", "", 0, "", "", "", "");

    public string ExpiryText => SecondsRemaining <= 0
        ? ""
        : string.Create(CultureInfo.InvariantCulture, $"Expires in {SecondsRemaining / 60}:{SecondsRemaining % 60:D2}");
}

/// <summary>
/// Owns the pair-only user flow for desktop shells: discovery, the two-minute
/// "Ready to pair" window, initiator PIN/link entry, responder approval, and
/// failure copy. Pairing never carries files; trust enrolment only happens
/// through <see cref="Profile2PairingEngine"/> after both users approve.
/// </summary>
public sealed class DesktopPairingCoordinator : IDisposable
{
    public const int ScanTimeoutSeconds = 4;

    private readonly object _gate = new();
    private readonly DesktopPersistentTrustManager? _trustManager;
    private readonly DesktopInvitationManager _invitations;
    private readonly MdnsDiscoveryService _mdns;
    private readonly Func<string> _deviceName;
    private readonly Func<CancellationToken, Task<TlsSocketListener>> _ensureListener;
    private readonly Action _releaseListener;
    private readonly Func<bool> _isTransferBusy;
    private readonly Func<System.Net.IPAddress, bool> _addressPolicy;
    private readonly TimeSpan _scanTimeoutDuration;

    private DesktopPairingSnapshot _snapshot = DesktopPairingSnapshot.Initial;
    private PairReadyWindowAuthority? _window;
    private Timer? _tick;
    private Timer? _scanTimeout;
    private CancellationTokenSource? _operationCts;
    private TaskCompletionSource<bool>? _approvalTcs;
    private DiscoveredPeer? _selectedPeer;
    private int _peerCount;
    private long _lastObservedRevision;
    private int _sessionActive;
    private int _disposed;

    public DesktopPairingCoordinator(
        DesktopPersistentTrustManager? trustManager,
        DesktopInvitationManager invitations,
        MdnsDiscoveryService mdns,
        Func<string> deviceName,
        Func<CancellationToken, Task<TlsSocketListener>> ensureListener,
        Action releaseListener,
        Func<bool> isTransferBusy,
        Func<System.Net.IPAddress, bool>? addressPolicy = null,
        TimeSpan? scanTimeoutDuration = null,
        DesktopTimeoutPolicy? timeoutPolicy = null)
    {
        TimeoutPolicy = timeoutPolicy ?? DesktopTimeoutPolicy.Default;
        _addressPolicy = addressPolicy ?? IsAllowedLocalLanAddress;
        _scanTimeoutDuration = scanTimeoutDuration ?? TimeSpan.FromSeconds(ScanTimeoutSeconds);
        _trustManager = trustManager;
        _invitations = invitations ?? throw new ArgumentNullException(nameof(invitations));
        _mdns = mdns ?? throw new ArgumentNullException(nameof(mdns));
        _deviceName = deviceName ?? throw new ArgumentNullException(nameof(deviceName));
        _ensureListener = ensureListener ?? throw new ArgumentNullException(nameof(ensureListener));
        _releaseListener = releaseListener ?? throw new ArgumentNullException(nameof(releaseListener));
        _isTransferBusy = isTransferBusy ?? throw new ArgumentNullException(nameof(isTransferBusy));
        _mdns.SnapshotChanged += ApplyDiscoverySnapshot;
    }

    public DesktopTimeoutPolicy TimeoutPolicy { get; set; }

    public bool IsBusy => Volatile.Read(ref _sessionActive) != 0 || _isTransferBusy();

    public event Action<DesktopPairingSnapshot>? Changed;

    /// <summary>Raised after a peer has been saved so lists can refresh in place.</summary>
    public event Action<DesktopTrustedPeer>? PeerPaired;

    public DesktopPairingSnapshot Snapshot
    {
        get { lock (_gate) return _snapshot; }
    }

    public bool IsReadyWindowActive
    {
        get { lock (_gate) return _window?.IsActive() == true; }
    }

    /// <summary>Presence id to advertise while ready; null when no window is active.</summary>
    public string? ActiveWindowPresenceId
    {
        get { lock (_gate) return _window?.IsActive() == true ? _window.PresenceId : null; }
    }

    public string? SelectedPeerName => _selectedPeer?.DeviceName;
    public DiscoveredPeer? SelectedPeer => _selectedPeer;
    public bool IsIdentityAvailable => _trustManager != null;

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    public void BeginFindDevices()
    {
        CancelReadyWindow(publish: false);
        CancelOperation();
        _selectedPeer = null;

        _mdns.PruneExpiredPeers();
        DiscoverySnapshot current = _mdns.Snapshot;
        int cachedPairReady = current.Peers.Count(static p => p.IsPairReady);
        string diagnostic = FormatDiscoveryDiagnostic(current.Diagnostics);

        lock (_gate)
        {
            _peerCount = cachedPairReady;
            if (current.Revision > _lastObservedRevision)
            {
                _lastObservedRevision = current.Revision;
            }
        }

        if (cachedPairReady > 0)
        {
            Publish(Snapshot with
            {
                Phase = DesktopPairingPhase.PeersFound,
                Message = "",
                Diagnostic = diagnostic,
                RecoveryAction = ""
            });
        }
        else
        {
            Publish(Snapshot with
            {
                Phase = DesktopPairingPhase.Scanning,
                Message = "Looking for Glide devices…",
                Diagnostic = diagnostic,
                RecoveryAction = ""
            });
        }

        try { _mdns.QueryPeers(); }
        catch (Exception ex) { SetDiscoveryDiagnostic(ex.Message); }

        _scanTimeout?.Dispose();
        _scanTimeout = new Timer(_ =>
        {
            _mdns.PruneExpiredPeers();
            DiscoverySnapshot latest = _mdns.Snapshot;
            int latestReady = latest.Peers.Count(static p => p.IsPairReady);
            int effectiveReady;
            lock (_gate)
            {
                if (_snapshot.Phase != DesktopPairingPhase.Scanning) return;
                effectiveReady = Math.Max(_peerCount, latestReady);
                _peerCount = effectiveReady;
            }
            Publish(Snapshot with
            {
                Phase = effectiveReady > 0 ? DesktopPairingPhase.PeersFound : DesktopPairingPhase.Empty,
                Message = effectiveReady > 0 ? "" : FormatEmptyDiscoveryMessage(latest.Diagnostics),
                Diagnostic = FormatDiscoveryDiagnostic(latest.Diagnostics)
            });
        }, null, _scanTimeoutDuration, Timeout.InfiniteTimeSpan);
    }

    /// <summary>Consumes an immutable discovery snapshot from <see cref="MdnsDiscoveryService"/>.</summary>
    public void ApplyDiscoverySnapshot(DiscoverySnapshot snapshot)
    {
        if (snapshot == null) return;

        DesktopPairingPhase phase;
        lock (_gate)
        {
            if (snapshot.Revision < _lastObservedRevision)
            {
                return;
            }
            _lastObservedRevision = snapshot.Revision;
            phase = _snapshot.Phase;
        }

        if (_selectedPeer != null &&
            phase is DesktopPairingPhase.Scanning or DesktopPairingPhase.PeersFound or DesktopPairingPhase.Empty)
        {
            DiscoveredPeer? matching = snapshot.Peers.FirstOrDefault(p =>
                p.IsPairReady &&
                string.Equals(p.Host, _selectedPeer.Host, StringComparison.OrdinalIgnoreCase) &&
                p.Port == _selectedPeer.Port);
            _selectedPeer = matching;
        }

        int pairReadyPeers = snapshot.Peers.Count(static p => p.IsPairReady);
        NotifyPeerCountCore(pairReadyPeers, snapshot.Diagnostics);
    }

    /// <summary>Called by the shell whenever the count of pair-ready peers changes.</summary>
    public void NotifyPeerCount(int pairReadyPeers) =>
        NotifyPeerCountCore(pairReadyPeers, _mdns.Diagnostics);

    private void NotifyPeerCountCore(int pairReadyPeers, LanDiscoveryDiagnostics diagnostics)
    {
        DesktopPairingPhase phase;
        lock (_gate)
        {
            _peerCount = pairReadyPeers;
            phase = _snapshot.Phase;
        }

        string diagText = FormatDiscoveryDiagnostic(diagnostics);
        if (phase is DesktopPairingPhase.Scanning or DesktopPairingPhase.Empty && pairReadyPeers > 0)
        {
            _scanTimeout?.Dispose();
            _scanTimeout = null;
            Publish(Snapshot with
            {
                Phase = DesktopPairingPhase.PeersFound,
                Message = "",
                Diagnostic = diagText
            });
        }
        else if (phase == DesktopPairingPhase.PeersFound && pairReadyPeers == 0)
        {
            Publish(Snapshot with
            {
                Phase = DesktopPairingPhase.Empty,
                Message = FormatEmptyDiscoveryMessage(diagnostics),
                Diagnostic = diagText
            });
        }
        else if (phase is DesktopPairingPhase.Scanning or DesktopPairingPhase.Empty or DesktopPairingPhase.PeersFound)
        {
            string message = phase == DesktopPairingPhase.Empty
                ? FormatEmptyDiscoveryMessage(diagnostics)
                : Snapshot.Message;
            Publish(Snapshot with
            {
                Message = message,
                Diagnostic = diagText
            });
        }
    }

    private static string FormatEmptyDiscoveryMessage(LanDiscoveryDiagnostics diagnostics) =>
        diagnostics.Status switch
        {
            LanDiscoveryDiagnosticStatus.NoEligibleInterfaces =>
                "No local network interface is active. Connect to Wi-Fi or Ethernet, or use Pair by address.",
            LanDiscoveryDiagnosticStatus.MulticastBlocked =>
                "Automatic discovery is blocked on this network. Use Pair by address or Enter pairing link.",
            LanDiscoveryDiagnosticStatus.Degraded =>
                "Open Glide on the other computer and choose Show my QR and code, or use Pair by address.",
            _ => "Open Glide on the other computer and choose Show my QR and code."
        };

    private static string FormatDiscoveryDiagnostic(LanDiscoveryDiagnostics diagnostics) =>
        diagnostics.Status switch
        {
            LanDiscoveryDiagnosticStatus.Healthy => "",
            _ => LanDiscoverySafeText($"{diagnostics.Status}: {diagnostics.ErrorMessage ?? "Discovery issue detected."}")
        };

    private void SetDiscoveryDiagnostic(string detail) =>
        Publish(Snapshot with { Diagnostic = LanDiscoverySafeText(detail) });

    private static string LanDiscoverySafeText(string value) =>
        value.Length > 200 ? value[..200] : value;

    // ------------------------------------------------------------------
    // Target: Ready to pair
    // ------------------------------------------------------------------

    public async Task StartReadyAsync(CancellationToken cancellationToken = default)
    {
        CancelOperation();
        CancelReadyWindow(publish: false);
        _scanTimeout?.Dispose();
        _selectedPeer = null;

        if (_trustManager == null)
        {
            Fail("IDENTITY_UNAVAILABLE", "This device's identity is unavailable — restart Glide and try again", "Persistent identity store could not be opened.");
            return;
        }

        PairReadyWindowAuthority window;
        try
        {
            TlsSocketListener listener = await _ensureListener(cancellationToken).ConfigureAwait(false);
            window = _invitations.CreatePairReadyWindow(listener, _deviceName(), listener.BoundPort);
            _mdns.StartAdvertising(_deviceName(), listener.BoundPort, window.PresenceId, pairReady: true, trustedReconnectSupported: true);
        }
        catch (InvalidOperationException ex)
        {
            _releaseListener();
            Fail("NETWORK_UNAVAILABLE", "Couldn’t reach this device — check the local network", ex.Message);
            return;
        }
        catch (Exception ex)
        {
            _releaseListener();
            Fail("LISTENER_FAILED", "Couldn’t reach this device — check the local network", ex.Message);
            return;
        }

        lock (_gate) _window = window;
        string endpointsText = string.Join(", ", window.QrInvitation.Endpoints.Select(static e => $"{e.Address}:{e.Port}"));
        Publish(new DesktopPairingSnapshot(
            DesktopPairingPhase.TargetReady,
            "",
            window.FormattedPinDisplay,
            window.RemainingSeconds(),
            window.QrInvitationUri,
            "Enter this code on the other computer.",
            endpointsText,
            ""));

        _tick?.Dispose();
        _tick = new Timer(OnTick, window, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
    }

    private void OnTick(object? state)
    {
        var window = (PairReadyWindowAuthority)state!;
        DesktopPairingPhase phase;
        lock (_gate)
        {
            if (!ReferenceEquals(_window, window)) return;
            phase = _snapshot.Phase;
        }

        if (!window.IsActive())
        {
            // A used window is finished by the handler. An expired or exhausted one fails visibly.
            if (phase == DesktopPairingPhase.TargetReady)
            {
                CancelReadyWindow(publish: false);
                Fail(Profile2Constants.ErrorExpired, "Code expired — get a new code", "Ready window expired or failed attempt limit reached.", "Get a new code");
            }
            return;
        }

        if (phase == DesktopPairingPhase.TargetReady)
        {
            Publish(Snapshot with { SecondsRemaining = window.RemainingSeconds() });
        }
    }

    /// <summary>Cancels the Ready window (explicit Cancel, close, or leaving the page) and returns to the idle header.</summary>
    public void Cancel()
    {
        CancelOperation();
        _scanTimeout?.Dispose();
        _scanTimeout = null;
        CancelReadyWindow(publish: false);
        DeclineApproval();
        _selectedPeer = null;
        Publish(DesktopPairingSnapshot.Initial);
    }

    /// <summary>Leaving the Pair devices page ends readiness but keeps results.</summary>
    public void OnPageLeft()
    {
        if (Snapshot.Phase != DesktopPairingPhase.Idle) Cancel();
    }

    private void CancelReadyWindow(bool publish)
    {
        PairReadyWindowAuthority? window;
        lock (_gate)
        {
            window = _window;
            _window = null;
        }
        _tick?.Dispose();
        _tick = null;
        if (window != null)
        {
            window.Cancel();
            _invitations.RevokeActivePairWindow();
            _mdns.SetPairReadiness(false);
            _releaseListener();
        }
        if (publish) Publish(DesktopPairingSnapshot.Initial);
    }

    // ------------------------------------------------------------------
    // Initiator flows
    // ------------------------------------------------------------------

    public void SelectPeer(DiscoveredPeer peer)
    {
        ArgumentNullException.ThrowIfNull(peer);
        _selectedPeer = peer;
        Publish(Snapshot with
        {
            Phase = DesktopPairingPhase.InitiatorPinEntry,
            PeerName = Sanitize(peer.DeviceName),
            Message = "",
            Diagnostic = "",
            RecoveryAction = ""
        });
    }

    public void ShowManualEntry()
    {
        CancelReadyWindow(publish: false);
        _selectedPeer = null;
        Publish(Snapshot with { Phase = DesktopPairingPhase.ManualEntry, Message = "", Diagnostic = "", RecoveryAction = "", PeerName = "" });
    }

    public void ShowLinkEntry()
    {
        CancelReadyWindow(publish: false);
        _selectedPeer = null;
        Publish(Snapshot with { Phase = DesktopPairingPhase.UseLink, Message = "", Diagnostic = "", RecoveryAction = "", PeerName = "" });
    }

    public Task PairWithSelectedPeerAsync(string pin) =>
        _selectedPeer is null
            ? Task.CompletedTask
            : RunInitiatorAsync(
                _selectedPeer.DeviceName,
                (trust, approve, verifying, ct) => Profile2PairingEngine.RunInitiatorPinPairAsync(
                    _selectedPeer.Host, _selectedPeer.Port, _selectedPeer.PresenceId,
                    pin, _deviceName(), PlatformName(), trust, approve, verifying, ct,
                    TimeoutPolicy.TcpConnectTimeout,
                    TimeoutPolicy.TlsHandshakeTimeout,
                    TimeoutPolicy.MachineAuthenticationTimeout,
                    TimeoutPolicy.HumanApprovalTimeout));

    public Task PairWithAddressAsync(string hostPort, string pin)
    {
        if (!TryParseHostPort(hostPort, out string host, out int port, _addressPolicy))
        {
            Fail("BAD_ADDRESS", "Couldn’t reach this device — check the local network", "Enter a reachable local-network IPv4 address and port, for example 192.168.0.20:42424.", "Try again", DesktopPairingPhase.ManualEntry);
            return Task.CompletedTask;
        }

        return RunInitiatorAsync(
            host,
            (trust, approve, verifying, ct) => Profile2PairingEngine.RunInitiatorPinPairAsync(
                host, port, null, pin, _deviceName(), PlatformName(), trust, approve, verifying, ct,
                TimeoutPolicy.TcpConnectTimeout,
                TimeoutPolicy.TlsHandshakeTimeout,
                TimeoutPolicy.MachineAuthenticationTimeout,
                TimeoutPolicy.HumanApprovalTimeout));
    }

    public Task PairWithLinkAsync(string uri)
    {
        LanPairV2Invitation invitation;
        try
        {
            ParsedGlideInvitation parsed = InvitationCodec.DecodeAny((uri ?? "").Trim());
            if (parsed is not ParsedGlideInvitation.PairOnlyV2 pairOnly)
            {
                Fail("WRONG_LINK", "This link sends files — open Receive to use it", "Link purpose is not pair-only.", "Try again", DesktopPairingPhase.UseLink);
                return Task.CompletedTask;
            }
            invitation = pairOnly.Invitation;
        }
        catch (Exception)
        {
            Fail("BAD_LINK", "Couldn’t read this link — copy it again", "Pairing link could not be parsed.", "Try again", DesktopPairingPhase.UseLink);
            return Task.CompletedTask;
        }

        return RunInitiatorAsync(
            invitation.Name,
            (trust, approve, verifying, ct) => Profile2PairingEngine.RunInitiatorQrPairAsync(
                invitation, _deviceName(), PlatformName(), trust, approve, verifying, ct,
                TimeoutPolicy.TcpConnectTimeout,
                TimeoutPolicy.TlsHandshakeTimeout,
                TimeoutPolicy.MachineAuthenticationTimeout,
                TimeoutPolicy.HumanApprovalTimeout));
    }

    private async Task RunInitiatorAsync(
        string peerName,
        Func<DesktopPersistentTrustManager, Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>>, Action, CancellationToken, Task<Profile2PairOutcome>> run)
    {
        if (_trustManager == null)
        {
            Fail("IDENTITY_UNAVAILABLE", "This device's identity is unavailable — restart Glide and try again", "Persistent identity store could not be opened.");
            return;
        }
        if (_isTransferBusy())
        {
            Fail(Profile2Constants.ErrorBusy, "This device is busy — try again", "A pairing or transfer session is already active.");
            return;
        }
        if (Interlocked.CompareExchange(ref _sessionActive, 1, 0) != 0)
        {
            return;
        }

        CancelReadyWindow(publish: false);
        CancelOperation();
        var cts = new CancellationTokenSource();
        lock (_gate) _operationCts = cts;
        string name = Sanitize(peerName);
        Publish(Snapshot with { Phase = DesktopPairingPhase.Verifying, PeerName = name, Message = "Verifying…", Diagnostic = "", RecoveryAction = "" });

        try
        {
            Profile2PairOutcome outcome = await run(
                _trustManager,
                // Entering the code or link is this device's explicit approval.
                (_, _) => Task.FromResult(true),
                () => Publish(Snapshot with { Phase = DesktopPairingPhase.Verifying, Message = "Verifying…" }),
                cts.Token).ConfigureAwait(false);
            Complete(outcome, name);
        }
        catch (OperationCanceledException)
        {
            // Explicit Cancel already published the idle state.
        }
        catch (Exception)
        {
            Fail("UNREACHABLE", "Couldn’t reach this device — check the local network", "Unexpected error while pairing.", "Try again");
        }
        finally
        {
            Interlocked.Exchange(ref _sessionActive, 0);
        }
    }

    // ------------------------------------------------------------------
    // Responder
    // ------------------------------------------------------------------

    /// <summary>Handles one PAIR_ONLY session after SESSION_OPEN has been read from the connection.</summary>
    public async Task HandleInboundAsync(
        TlsSocketConnection connection,
        Profile2Message.SessionOpen open,
        CancellationToken cancellationToken)
    {
        if (_trustManager == null)
        {
            await connection.DisposeAsync().ConfigureAwait(false);
            return;
        }

        PairReadyWindowAuthority? window;
        DesktopPairingSnapshot preInboundSnapshot;
        lock (_gate)
        {
            window = _window;
            preInboundSnapshot = _snapshot;
        }

        bool acquired = Interlocked.CompareExchange(ref _sessionActive, 1, 0) == 0;
        bool busy = !acquired || _isTransferBusy();
        bool reachedVerifying = false;
        try
        {
            string peerName = Sanitize(open.InitiatorDeviceName);
            Profile2PairOutcome outcome = await Profile2PairingEngine.HandleResponderPairOnlySessionAsync(
                connection,
                open,
                window,
                busy,
                _deviceName(),
                PlatformName(),
                _trustManager,
                ApproveInboundAsync,
                () =>
                {
                    reachedVerifying = true;
                    Publish(Snapshot with { Phase = DesktopPairingPhase.Verifying, PeerName = peerName, Message = "Verifying…" });
                },
                cancellationToken,
                TimeoutPolicy.MachineAuthenticationTimeout,
                TimeoutPolicy.HumanApprovalTimeout).ConfigureAwait(false);

            if (busy)
            {
                return; // Never disturb the session that is already using the panel.
            }

            if (outcome.Succeeded)
            {
                CancelReadyWindow(publish: false);
            }
            else if (outcome.ErrorCode == Profile2Constants.ErrorExpired && window?.IsActive() != true)
            {
                CancelReadyWindow(publish: false);
            }
            else if (!reachedVerifying &&
                     outcome.ErrorCode == Profile2Constants.ErrorProtocolOrder &&
                     window?.IsActive() == true &&
                     preInboundSnapshot.Phase == DesktopPairingPhase.TargetReady)
            {
                // An unauthenticated peer that stalled before PairMsg must not destroy the active TargetReady window.
                Publish(preInboundSnapshot with { SecondsRemaining = window.RemainingSeconds() });
                return;
            }
            Complete(outcome, peerName);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception)
        {
            if (!busy) Fail("UNREACHABLE", "Couldn’t reach this device — check the local network", "Unexpected error while handling an incoming pairing session.", "Try again");
        }
        finally
        {
            if (acquired)
            {
                DeclineApproval();
                Interlocked.Exchange(ref _sessionActive, 0);
            }
            await connection.DisposeAsync().ConfigureAwait(false);
        }
    }

    private async Task<bool> ApproveInboundAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken)
    {
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate) _approvalTcs = tcs;
        Publish(Snapshot with
        {
            Phase = DesktopPairingPhase.TargetApproval,
            PeerName = Sanitize(peer.DisplayName),
            Message = "",
            Diagnostic = ""
        });
        try
        {
            TimeSpan approvalLimit = TimeoutPolicy.HumanApprovalTimeout > TimeSpan.Zero
                ? TimeoutPolicy.HumanApprovalTimeout
                : TimeSpan.FromSeconds(Profile2Constants.DefaultPairWindowSeconds);
            bool approved = await tcs.Task.WaitAsync(approvalLimit, cancellationToken).ConfigureAwait(false);
            if (approved) Publish(Snapshot with { Phase = DesktopPairingPhase.Verifying, Message = "Verifying…" });
            return approved;
        }
        catch (TimeoutException)
        {
            return false;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
        finally
        {
            lock (_gate)
            {
                if (ReferenceEquals(_approvalTcs, tcs)) _approvalTcs = null;
            }
        }
    }

    public void Approve()
    {
        TaskCompletionSource<bool>? tcs;
        lock (_gate) tcs = _approvalTcs;
        tcs?.TrySetResult(true);
    }

    public void DeclineApproval()
    {
        TaskCompletionSource<bool>? tcs;
        lock (_gate) tcs = _approvalTcs;
        tcs?.TrySetResult(false);
    }

    // ------------------------------------------------------------------
    // Shared outcome handling
    // ------------------------------------------------------------------

    private void Complete(Profile2PairOutcome outcome, string peerName)
    {
        if (outcome.Succeeded && outcome.Peer != null)
        {
            string name = Sanitize(outcome.Peer.DisplayName);
            Publish(new DesktopPairingSnapshot(
                DesktopPairingPhase.Success, name, "", 0, "", $"Paired with {name}", "", ""));
            PeerPaired?.Invoke(outcome.Peer);
            return;
        }

        if (outcome.ErrorCode == "CANCELLED")
        {
            Publish(DesktopPairingSnapshot.Initial);
            return;
        }

        string action = outcome.ErrorCode switch
        {
            Profile2Constants.ErrorExpired => "Get a new code",
            Profile2Constants.ErrorIdentityChanged or Profile2Constants.ErrorUntrustedPeer => "Pair again",
            _ => "Try again"
        };
        DesktopPairingPhase retry = _selectedPeer != null
            ? DesktopPairingPhase.InitiatorPinEntry
            : DesktopPairingPhase.Failed;
        Fail(outcome.ErrorCode ?? "FAILED", outcome.UserMessage, $"Result: {outcome.ErrorCode ?? "FAILED"}", action,
            outcome.ErrorCode is Profile2Constants.ErrorAuthFailed ? retry : DesktopPairingPhase.Failed);
        if (outcome.ErrorCode == Profile2Constants.ErrorAuthFailed && _selectedPeer != null)
        {
            Publish(Snapshot with { PeerName = Sanitize(_selectedPeer.DeviceName) });
        }
    }

    private void Fail(
        string code,
        string userMessage,
        string diagnostic,
        string action = "Try again",
        DesktopPairingPhase phase = DesktopPairingPhase.Failed)
    {
        Publish(Snapshot with
        {
            Phase = phase,
            Message = userMessage,
            Diagnostic = $"{code}: {diagnostic}",
            RecoveryAction = action,
            Code = "",
            QrUri = "",
            SecondsRemaining = 0
        });
    }

    private void CancelOperation()
    {
        CancellationTokenSource? cts;
        lock (_gate)
        {
            cts = _operationCts;
            _operationCts = null;
        }
        try { cts?.Cancel(); } catch (ObjectDisposedException) { }
        cts?.Dispose();
    }

    private void Publish(DesktopPairingSnapshot next)
    {
        lock (_gate)
        {
            if (_snapshot == next) return;
            _snapshot = next;
        }
        Changed?.Invoke(next);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public static string PlatformName() =>
        OperatingSystem.IsWindows() ? "windows"
        : OperatingSystem.IsLinux() ? "linux"
        : OperatingSystem.IsMacOS() ? "macos"
        : "unknown";

    /// <summary>Peer-provided names are untrusted display strings: strip control characters and bound length.</summary>
    public static string Sanitize(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return "Glide device";
        var sb = new StringBuilder(name.Length);
        foreach (char c in name)
        {
            if (!char.IsControl(c) && c != '\u202E' && c != '\u202D') sb.Append(c);
            if (sb.Length >= 64) break;
        }
        string result = sb.ToString().Trim();
        return result.Length == 0 ? "Glide device" : result;
    }

    public static bool TryParseHostPort(
        string? text,
        out string host,
        out int port,
        Func<System.Net.IPAddress, bool>? addressPolicy = null)
    {
        host = "";
        port = LanDiscoveryConstants.DefaultPort;
        if (string.IsNullOrWhiteSpace(text)) return false;
        string value = text.Trim();
        int colon = value.LastIndexOf(':');
        string hostPart = colon >= 0 ? value[..colon] : value;
        if (colon >= 0 && (!int.TryParse(value[(colon + 1)..], NumberStyles.None, CultureInfo.InvariantCulture, out port) || port is < 1 or > 65535))
        {
            return false;
        }
        if (!LanEndpoint.IsValidAddress(hostPart) ||
            !System.Net.IPAddress.TryParse(hostPart, out var ip) ||
            ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
        {
            return false;
        }
        var policy = addressPolicy ?? IsAllowedLocalLanAddress;
        if (!policy(ip))
        {
            return false;
        }
        host = ip.ToString();
        return true;
    }

    public static bool IsAllowedLocalLanAddress(System.Net.IPAddress ip)
    {
        if (ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) return false;
        if (System.Net.IPAddress.IsLoopback(ip) || ip.Equals(System.Net.IPAddress.Any) || ip.Equals(System.Net.IPAddress.Broadcast))
        {
            return false;
        }
        byte[] b = ip.GetAddressBytes();
        // Reject IPv4 link-local 169.254.0.0/16
        if (b[0] == 169 && b[1] == 254) return false;
        // Allow RFC 1918 private ranges (10/8, 172.16/12, 192.168/16) and CGNAT/LAN overlay 100.64/10
        if (b[0] == 10) return true;
        if (b[0] == 172 && b[1] is >= 16 and <= 31) return true;
        if (b[0] == 192 && b[1] == 168) return true;
        if (b[0] == 100 && b[1] is >= 64 and <= 127) return true;
        return false;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        _mdns.SnapshotChanged -= ApplyDiscoverySnapshot;
        CancelOperation();
        _scanTimeout?.Dispose();
        DeclineApproval();
        CancelReadyWindow(publish: false);
    }
}
