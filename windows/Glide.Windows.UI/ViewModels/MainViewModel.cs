using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Glide.Windows.UI.Common;
using Microsoft.Win32;
using ProtocolViolationException = Glide.Core.Models.ProtocolViolationException;

namespace Glide.Windows.UI.ViewModels;

public class MainViewModel : ViewModelBase
{
    private string _currentTab = "Send";
    private string _peerHost = "127.0.0.1";
    private int _peerPort = LanDiscoveryConstants.DefaultPort;
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

    private TlsSocketListener? _listener;

    private readonly MdnsDiscoveryService _mdnsService = new();
    public ObservableCollection<DiscoveredPeerViewModel> DiscoveredPeers { get; } = [];

    private bool _isPairModalVisible;
    private string _pairInputUri = "";
    private string _activeInvitationUri = "";
    private LanInvitation? _activeInvitation;
    private LanInvitation? _pairedInvitation;

    public ObservableCollection<TransferItemViewModel> SelectedFiles { get; } = [];
    public ObservableCollection<HistoryItemViewModel> HistoryItems { get; } = [];

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

    public MainViewModel()
    {
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
                StatusMessage = $"Selected peer: {peer.DeviceName} ({peer.Host}:{peer.Port})";
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

        _mdnsService.Start(enableBrowsing: true);
        RefreshNetworkInfo();
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
        set => SetProperty(ref _activeInvitationUri, value);
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
        if (IsListening)
        {
            _mdnsService.StopAdvertising();
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
            _listener = new TlsSocketListener(LanDiscoveryConstants.DefaultPort);
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
                name: Environment.MachineName,
                platform: "windows",
                endpoints: endpoints,
                spkiSha256: spkiSha256,
                token: token,
                expiresAt: expiresAt.ToString(),
                ttlSeconds: 120);

            _activeInvitation = invitation;
            ActiveInvitationUri = InvitationCodec.Encode(invitation);

            _mdnsService.StartAdvertising(
                deviceName: Environment.MachineName,
                port: ListeningPort,
                certFingerprintPrefix: spkiSha256[..8],
                capabilities: LanDiscoveryConstants.DefaultCapabilities);
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
        await using (conn)
        {
            _activeTransferCts = new CancellationTokenSource();
            var ct = _activeTransferCts.Token;

            try
            {
                IsTransferActive = true;
                TransferTitle = "Incoming Connection...";

                var channel = new LanRecordChannel(conn.SslStream);
                var stagingStore = new QuarantineStagingStore();

                // Read first record: may be BOOTSTRAP or CONTROL (HELLO)
                var firstRecord = await channel.ReadRecordAsync(ct);
                if (firstRecord == null) return;

                Envelope helloEnv;
                if (firstRecord is LanRecord.Bootstrap bootstrapRecord)
                {
                    var bootstrap = LanBootstrapCodec.DecodeBootstrap(bootstrapRecord.JsonBytes);
                    bool isValid = _activeInvitation != null &&
                        string.Equals(_activeInvitation.InvitationId, bootstrap.InvitationId, StringComparison.OrdinalIgnoreCase) &&
                        string.Equals(_activeInvitation.Token, bootstrap.Token, StringComparison.Ordinal) &&
                        string.Equals(_activeInvitation.InvitationHash, bootstrap.InvitationHash, StringComparison.OrdinalIgnoreCase) &&
                        !_activeInvitation.IsExpired();

                    if (!isValid)
                    {
                        var failCode = (_activeInvitation?.IsExpired() ?? false)
                            ? LanBootstrapFailureCode.INVITATION_EXPIRED
                            : LanBootstrapFailureCode.AUTHENTICATION_FAILED;
                        await channel.WriteBootstrapResultAsync(LanBootstrapResult.Rejected(failCode), ct);
                        StatusMessage = "Rejected inbound transfer: Invalid or expired pairing token.";
                        return;
                    }

                    // Acknowledge bootstrap acceptance
                    await channel.WriteBootstrapResultAsync(LanBootstrapResult.Accepted(bootstrap.SessionId), ct);
                    _activeInvitation = null; // One-use token consumed
                    ActiveInvitationUri = "";

                    // Read subsequent HELLO envelope
                    helloEnv = await channel.ReadEnvelopeAsync(ct);
                }
                else if (firstRecord is LanRecord.Control controlRecord)
                {
                    // Fallback to SAS verification for direct / un-paired LAN connections
                    byte[] sessionNonce = RandomNumberGenerator.GetBytes(32);
                    int sas = SasCalculator.CalculateSas(conn.RemotePublicKeyBytes, conn.LocalPublicKeyBytes, sessionNonce);

                    SasCode = $"{sas:D6}";
                    SasPeerName = "Remote Peer";
                    IsSasModalVisible = true;

                    _sasConfirmationTcs = new TaskCompletionSource<bool>();
                    bool sasConfirmed = await _sasConfirmationTcs.Task;
                    IsSasModalVisible = false;

                    if (!sasConfirmed)
                    {
                        StatusMessage = "Transfer rejected by user.";
                        return;
                    }

                    helloEnv = ProtocolCodec.Decode(controlRecord.Payload);
                }
                else
                {
                    throw new ProtocolViolationException(WireErrorCodes.ProtocolOrder, $"Unexpected first record kind: {firstRecord.Kind}");
                }

                SasPeerName = helloEnv.Body["displayName"]?.ToString() ?? "Remote Peer";

                // 2. HELLO_ACK
                var limits = new JsonObject
                {
                    ["maxItems"] = 1000,
                    ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
                    ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
                    ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
                };
                var ackBody = new JsonObject
                {
                    ["selectedMajor"] = 1,
                    ["capabilities"] = new JsonArray("lan-v1", "folder-v1"),
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["displayName"] = Environment.MachineName,
                    ["limits"] = limits
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.HelloAck, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, ackBody), ct);

                // 3. OFFER_BEGIN
                var offerBegin = await channel.ReadEnvelopeAsync(ct);
                int itemCount = offerBegin.Body["itemCount"]?.GetValue<int>() ?? 0;
                long totalBytes = long.Parse(offerBegin.Body["totalBytes"]?.ToString() ?? "0");

                // 4. OFFER_PAGE
                var offerPage = await channel.ReadEnvelopeAsync(ct);
                var items = new List<(string id, string name, long size, string relPath)>();
                if (offerPage.Body["items"] is JsonArray arr)
                {
                    foreach (var n in arr)
                    {
                        if (n is JsonObject obj)
                        {
                            items.Add((obj["itemId"]!.ToString(), obj["name"]!.ToString(), long.Parse(obj["sizeBytes"]!.ToString()), obj.TryGetPropertyValue("relativePath", out var r) && r != null ? r.ToString() : obj["name"]!.ToString()));
                        }
                    }
                }

                // 5. OFFER_END
                await channel.ReadEnvelopeAsync(ct);

                // 6. OFFER_DECISION
                var decBody = new JsonObject
                {
                    ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                    ["accepted"] = true
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferDecision, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, decBody), ct);

                TransferTitle = $"Receiving {itemCount} file(s) from {SasPeerName}";
                long cumulativeTransferred = 0;
                var sw = Stopwatch.StartNew();

                foreach (var item in items)
                {
                    CurrentFileName = item.name;
                    var itemStart = await channel.ReadEnvelopeAsync(ct); // ITEM_START
                    string bulkRef = itemStart.Body["bulkRef"]?.ToString() ?? Guid.NewGuid().ToString("D").ToLowerInvariant();

                    var readyBody = new JsonObject { ["batchId"] = offerBegin.Body["batchId"]!.ToString(), ["itemId"] = item.id };
                    await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemReady, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, readyBody), ct);

                    // Bulk ingestion via LanRecord.Data and LanRecord.StreamEnd
                    string qPath = stagingStore.GetQuarantinePath(helloEnv.SessionId, new ItemId(item.id));
                    var qDir = Path.GetDirectoryName(qPath);
                    if (qDir != null && !Directory.Exists(qDir)) Directory.CreateDirectory(qDir);

                    byte[] hash;
                    using (var fs = new FileStream(qPath, FileMode.Create, FileAccess.Write, FileShare.None, 65536, true))
                    using (var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                    {
                        long receivedBytes = 0;
                        while (true)
                        {
                            var rec = await channel.ReadRecordAsync(ct);
                            if (rec == null) throw new EndOfStreamException("Connection terminated during payload transfer.");

                            if (rec is LanRecord.Data dataRec)
                            {
                                if (!string.Equals(dataRec.BulkRef.Value, bulkRef, StringComparison.OrdinalIgnoreCase))
                                {
                                    throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Unmatched bulkRef in DATA record");
                                }
                                await fs.WriteAsync(dataRec.Chunk.AsMemory(), ct);
                                sha256.AppendData(dataRec.Chunk);
                                receivedBytes += dataRec.Chunk.Length;
                                cumulativeTransferred += dataRec.Chunk.Length;

                                TotalProgress = totalBytes > 0 ? (cumulativeTransferred * 100.0) / totalBytes : 100;
                                double mbps = (cumulativeTransferred / (1024.0 * 1024.0)) / Math.Max(0.01, sw.Elapsed.TotalSeconds);
                                TransferRateText = $"{mbps:F1} MB/s";
                                TransferredBytesText = $"{TransferItemViewModel.FormatSize(cumulativeTransferred)} / {TransferItemViewModel.FormatSize(totalBytes)}";
                            }
                            else if (rec is LanRecord.StreamEnd streamEndRec)
                            {
                                if (!string.Equals(streamEndRec.BulkRef.Value, bulkRef, StringComparison.OrdinalIgnoreCase))
                                {
                                    throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Unmatched bulkRef in STREAM_END record");
                                }
                                if (streamEndRec.ActualBytes != receivedBytes || receivedBytes != item.size)
                                {
                                    throw new ProtocolViolationException(WireErrorCodes.IntegrityMismatch, $"STREAM_END actualBytes {streamEndRec.ActualBytes} does not match expected {item.size}");
                                }
                                break;
                            }
                            else
                            {
                                throw new ProtocolViolationException(WireErrorCodes.ProtocolOrder, $"Unexpected record kind during data stream: {rec.Kind}");
                            }
                        }
                        hash = sha256.GetHashAndReset();
                    }

                    var itemEnd = await channel.ReadEnvelopeAsync(ct);
                    string expectedSha = itemEnd.Body["sha256"]!.ToString();
                    string actualSha = Convert.ToHexStringLower(hash);

                    if (!string.Equals(actualSha, expectedSha, StringComparison.OrdinalIgnoreCase))
                    {
                        stagingStore.PurgeQuarantine(helloEnv.SessionId, new ItemId(item.id));
                        throw new ProtocolViolationException(WireErrorCodes.IntegrityMismatch, $"Digest mismatch for {item.name}");
                    }

                    string destDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                    string finalPath = QuarantineStagingStore.ResolveCollision(Path.Combine(destDir, item.relPath.Replace('/', Path.DirectorySeparatorChar)));
                    var td = Path.GetDirectoryName(finalPath);
                    if (td != null && !Directory.Exists(td)) Directory.CreateDirectory(td);
                    File.Move(qPath, finalPath, false);

                    var verResult = new JsonObject
                    {
                        ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                        ["itemId"] = item.id,
                        ["status"] = "verified",
                        ["sizeBytes"] = item.size.ToString(),
                        ["sha256"] = actualSha
                    };
                    await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, verResult), ct);

                    Application.Current?.Dispatcher.Invoke(() =>
                    {
                        HistoryItems.Insert(0, new HistoryItemViewModel
                        {
                            Direction = "Received",
                            ItemName = item.name,
                            FormattedSize = TransferItemViewModel.FormatSize(item.size),
                            TargetPath = finalPath,
                            PeerName = SasPeerName,
                            Timestamp = DateTime.Now
                        });
                    });
                }

                await channel.ReadEnvelopeAsync(ct); // BATCH_END
                var bRes = new JsonObject
                {
                    ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                    ["status"] = "completed",
                    ["verifiedCount"] = items.Count,
                    ["totalVerifiedBytes"] = totalBytes.ToString()
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchResult, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, bRes), ct);

                StatusMessage = $"Successfully received {items.Count} file(s)!";
            }
            catch (Exception ex)
            {
                StatusMessage = $"Receive failed: {ex.Message}";
            }
            finally
            {
                IsTransferActive = false;
                IsSasModalVisible = false;
            }
        }
    }

    private async Task ExecuteSendFilesAsync()
    {
        if (SelectedFiles.Count == 0) return;

        _activeTransferCts = new CancellationTokenSource();
        var ct = _activeTransferCts.Token;

        try
        {
            IsTransferActive = true;
            TransferTitle = $"Connecting to {PeerHost}:{PeerPort}...";
            TotalProgress = 0;

            var client = new TlsSocketClient();
            TlsSocketConnection conn;
            if (_pairedInvitation != null)
            {
                if (_pairedInvitation.IsExpired())
                {
                    StatusMessage = "Pairing invitation has expired. Please pair again.";
                    return;
                }
                conn = await client.ConnectAsync(PeerHost, PeerPort, expectedSpkiSha256: _pairedInvitation.SpkiSha256, ct);
            }
            else
            {
                conn = await client.ConnectAsync(PeerHost, PeerPort, ct);
            }

            await using (conn)
            {
                var channel = new LanRecordChannel(conn.SslStream);
                var sessionId = new SessionId(Guid.NewGuid().ToString("D").ToLowerInvariant());

                if (_pairedInvitation != null)
                {
                    // Transmit BOOTSTRAP record
                    var bootstrapMsg = new LanBootstrapMessage(
                        invitationId: _pairedInvitation.InvitationId,
                        token: _pairedInvitation.Token,
                        invitationHash: _pairedInvitation.InvitationHash,
                        sessionId: sessionId.Value,
                        role: LanBootstrapMessage.RoleSender);

                    await channel.WriteBootstrapAsync(bootstrapMsg, ct);

                    var bootstrapResult = await channel.ReadBootstrapResultAsync(ct);
                    if (!bootstrapResult.IsAccepted)
                    {
                        StatusMessage = $"Pairing bootstrap rejected by peer: {bootstrapResult.Code}";
                        return;
                    }
                    _pairedInvitation = null; // One-use token consumed
                }
                else
                {
                    byte[] sessionNonce = RandomNumberGenerator.GetBytes(32);
                    int sas = SasCalculator.CalculateSas(conn.LocalPublicKeyBytes, conn.RemotePublicKeyBytes, sessionNonce);

                    SasCode = $"{sas:D6}";
                    SasPeerName = PeerHost;
                    IsSasModalVisible = true;

                    _sasConfirmationTcs = new TaskCompletionSource<bool>();
                    bool sasConfirmed = await _sasConfirmationTcs.Task;
                    IsSasModalVisible = false;

                    if (!sasConfirmed)
                    {
                        StatusMessage = "Transfer cancelled by user.";
                        return;
                    }
                }

                // 1. HELLO
                var helloBody = new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray(),
                    ["optionalCapabilities"] = new JsonArray("folder-v1"),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["displayName"] = Environment.MachineName
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.Hello, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 1, helloBody), ct);

                // 2. HELLO_ACK
                var ack = await channel.ReadEnvelopeAsync(ct);
                SasPeerName = ack.Body["displayName"]?.ToString() ?? PeerHost;

                // 3. OFFER_BEGIN
                var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
                long totalBytes = SelectedFiles.Sum(f => f.SizeBytes);
                var offerBegin = new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemCount"] = SelectedFiles.Count,
                    ["totalBytes"] = totalBytes.ToString(),
                    ["pageCount"] = 1
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferBegin, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, offerBegin), ct);

                // 4. OFFER_PAGE
                var itemsArr = new JsonArray();
                var itemMap = new Dictionary<string, (TransferItemViewModel item, Sha256Digest digest)>();

                foreach (var f in SelectedFiles)
                {
                    var itemId = new ItemId(Guid.NewGuid().ToString("D").ToLowerInvariant());
                    byte[] bytes = await File.ReadAllBytesAsync(f.FilePath, ct);
                    var digest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(bytes)));
                    itemMap[itemId.Value] = (f, digest);

                    itemsArr.Add(new JsonObject
                    {
                        ["itemId"] = itemId.Value,
                        ["name"] = f.Name,
                        ["mime"] = "application/octet-stream",
                        ["sizeBytes"] = f.SizeBytes.ToString(),
                        ["relativePath"] = f.Name
                    });
                }

                var offerPage = new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["pageIndex"] = 0,
                    ["items"] = itemsArr
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferPage, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, offerPage), ct);

                // 5. OFFER_END
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, new JsonObject { ["batchId"] = batchId.Value }), ct);

                // 6. OFFER_DECISION
                TransferTitle = "Waiting for peer approval...";
                var dec = await channel.ReadEnvelopeAsync(ct);
                if (!(dec.Body["accepted"]?.GetValue<bool>() ?? false))
                {
                    StatusMessage = "Peer rejected the transfer offer.";
                    return;
                }

                TransferTitle = $"Sending {SelectedFiles.Count} file(s) to {SasPeerName}";
                long cumulativeTransferred = 0;
                var sw = Stopwatch.StartNew();

                foreach (var (itemId, (item, digest)) in itemMap)
                {
                    CurrentFileName = item.Name;
                    item.Status = "Sending...";
                    string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();

                    var startBody = new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = itemId,
                        ["bulkRef"] = bulkRef,
                        ["sizeBytes"] = item.SizeBytes.ToString()
                    };
                    await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, startBody), ct);

                    await channel.ReadEnvelopeAsync(ct); // ITEM_READY

                    // Stream file via LanRecord.Data
                    using (var fs = new FileStream(item.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, true))
                    {
                        byte[] buf = new byte[65520];
                        int read;
                        while ((read = await fs.ReadAsync(buf.AsMemory(0, buf.Length), ct)) > 0)
                        {
                            await channel.WriteDataChunkAsync(bulkRef, buf.AsMemory(0, read), ct);
                            cumulativeTransferred += read;

                            TotalProgress = totalBytes > 0 ? (cumulativeTransferred * 100.0) / totalBytes : 100;
                            double mbps = (cumulativeTransferred / (1024.0 * 1024.0)) / Math.Max(0.01, sw.Elapsed.TotalSeconds);
                            TransferRateText = $"{mbps:F1} MB/s";
                            TransferredBytesText = $"{TransferItemViewModel.FormatSize(cumulativeTransferred)} / {TransferItemViewModel.FormatSize(totalBytes)}";
                        }

                        byte[] extraBuf = new byte[1];
                        int extra = await fs.ReadAsync(extraBuf.AsMemory(0, 1), ct);
                        if (extra > 0)
                        {
                            throw new InvalidOperationException($"File {item.Name} had unexpected bytes beyond declared size.");
                        }
                    }

                    // Send STREAM_END
                    await channel.WriteStreamEndAsync(bulkRef, item.SizeBytes, ct);

                    var endBody = new JsonObject
                    {
                        ["batchId"] = batchId.Value,
                        ["itemId"] = itemId,
                        ["actualBytes"] = item.SizeBytes.ToString(),
                        ["sha256"] = digest.Value
                    };
                    await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, endBody), ct);

                    var res = await channel.ReadEnvelopeAsync(ct); // ITEM_RESULT
                    item.Status = res.Body["status"]?.ToString() == "verified" ? "Verified" : "Failed";

                    Application.Current?.Dispatcher.Invoke(() =>
                    {
                        HistoryItems.Insert(0, new HistoryItemViewModel
                        {
                            Direction = "Sent",
                            ItemName = item.Name,
                            FormattedSize = item.FormattedSize,
                            TargetPath = item.FilePath,
                            PeerName = SasPeerName,
                            Timestamp = DateTime.Now
                        });
                    });
                }

                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 7, new JsonObject { ["batchId"] = batchId.Value }), ct);
                await channel.ReadEnvelopeAsync(ct); // BATCH_RESULT

                StatusMessage = $"Sent {SelectedFiles.Count} file(s) successfully!";
            }
        }
        catch (Exception ex)
        {
            StatusMessage = $"Send failed: {ex.Message}";
        }
        finally
        {
            IsTransferActive = false;
            IsSasModalVisible = false;
        }
    }

    private void ExecuteOpenDownloads()
    {
        string downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        if (Directory.Exists(downloads))
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = downloads,
                UseShellExecute = true
            });
        }
    }
}
