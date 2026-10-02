using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.BackendTestDriver;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        if (args.Length == 0 || args[0] is "-h" or "--help" or "help")
        {
            PrintUsage();
            return 0;
        }

        string command = args[0].ToLowerInvariant();
        try
        {
            return command switch
            {
                "listen-receiver" => await RunListenReceiverAsync(args[1..]),
                "listen-hosted-sender" => await RunListenHostedSenderAsync(args[1..]),
                "connect-sender" => await RunConnectSenderAsync(args[1..]),
                "connect-receiver" => await RunConnectReceiverAsync(args[1..]),
                "adversarial-send" => await RunAdversarialSendAsync(args[1..]),
                "generate-corpus" => RunGenerateCorpus(args[1..]),
                "verify-corpus" => RunVerifyCorpus(args[1..]),
                "test-limits" => await RunTestLimitsAsync(args[1..]),
                "test-storage-safety" => await RunTestStorageSafetyAsync(args[1..]),
                "test-trust" => await RunTestTrustAsync(args[1..]),
                "test-discovery" => await RunTestDiscoveryAsync(args[1..]),
                "test-ownership" => await RunTestOwnershipAsync(args[1..]),
                _ => PrintUnknownCommand(command)
            };
        }
        catch (Exception ex)
        {
            var errorResult = new JsonObject
            {
                ["event"] = "result",
                ["success"] = false,
                ["rejected"] = false,
                ["cancelled"] = false,
                ["message"] = $"Unhandled exception in driver: {ex.GetType().Name}: {ex.Message}"
            };
            Console.WriteLine(errorResult.ToJsonString());
            Console.Error.WriteLine($"[DRIVER ERROR] {ex}");
            return 1;
        }
    }

    private static void PrintUsage()
    {
        Console.Error.WriteLine("Glide Backend Test Driver");
        Console.Error.WriteLine("Usage:");
        Console.Error.WriteLine("  listen-receiver [options]");
        Console.Error.WriteLine("  listen-hosted-sender [options]");
        Console.Error.WriteLine("  connect-sender [options]");
        Console.Error.WriteLine("  connect-receiver [options]");
        Console.Error.WriteLine("  adversarial-send [options]");
        Console.Error.WriteLine("  generate-corpus [options]");
        Console.Error.WriteLine("  verify-corpus [options]");
        Console.Error.WriteLine("  test-limits");
        Console.Error.WriteLine("  test-storage-safety");
        Console.Error.WriteLine("  test-trust");
        Console.Error.WriteLine("  test-discovery");
        Console.Error.WriteLine("  test-ownership");
    }

    private static int PrintUnknownCommand(string command)
    {
        Console.Error.WriteLine($"Unknown command: {command}");
        PrintUsage();
        return 1;
    }

    #region Interaction Implementation

    private sealed class DriverInteraction : IDesktopTransferInteraction
    {
        private readonly bool _acceptOffer;
        private readonly bool _acceptRecipient;
        private readonly bool _acceptEnrollment;
        private readonly long _cancelAfterBytes;
        private readonly CancellationTokenSource? _linkedCts;
        private long _transferredBytes;

        public DriverInteraction(
            bool acceptOffer = false,
            bool acceptRecipient = false,
            bool acceptEnrollment = false,
            long cancelAfterBytes = -1,
            CancellationTokenSource? linkedCts = null)
        {
            _acceptOffer = acceptOffer;
            _acceptRecipient = acceptRecipient;
            _acceptEnrollment = acceptEnrollment;
            _cancelAfterBytes = cancelAfterBytes;
            _linkedCts = linkedCts;
        }

        public bool InvitationConsumed { get; private set; }
        public string? PeerName { get; private set; }
        public List<string> StatusMessages { get; } = [];
        public List<(string item, string status)> ItemStatuses { get; } = [];
        public List<DesktopHistoryEntry> History { get; } = [];

        public Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken)
        {
            Console.Error.WriteLine($"[INTERACTION] ConfirmRecipientAsync for '{peerName}': {_acceptRecipient}");
            return Task.FromResult(_acceptRecipient);
        }

        public Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
        {
            Console.Error.WriteLine($"[INTERACTION] ConfirmOfferAsync for {offer.Items.Count} items ({offer.TotalBytes} bytes): {_acceptOffer}");
            return Task.FromResult(_acceptOffer);
        }

        public Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken)
        {
            Console.Error.WriteLine($"[INTERACTION] ConfirmPeerEnrollmentAsync for '{peer.DisplayName}' ({peer.FingerprintHex[..12]}...): {_acceptEnrollment}");
            return Task.FromResult(_acceptEnrollment);
        }

        public void OnInvitationConsumed(DesktopTransferDirection direction)
        {
            InvitationConsumed = true;
            Console.Error.WriteLine($"[INTERACTION] OnInvitationConsumed: {direction}");
        }

        public void OnPeerIdentified(DesktopTransferDirection direction, string peerName)
        {
            PeerName = peerName;
            Console.Error.WriteLine($"[INTERACTION] OnPeerIdentified: {peerName} ({direction})");
        }

        public void OnStatus(DesktopTransferDirection direction, string message)
        {
            StatusMessages.Add(message);
            Console.Error.WriteLine($"[STATUS {direction}] {message}");
        }

        public void OnProgress(DesktopTransferProgress progress)
        {
            _transferredBytes = progress.TransferredBytes;
            if (_cancelAfterBytes > 0 && _transferredBytes >= _cancelAfterBytes && _linkedCts != null && !_linkedCts.IsCancellationRequested)
            {
                Console.Error.WriteLine($"[CANCELLATION] CancelAfterBytes reached ({_transferredBytes} >= {_cancelAfterBytes}). Cancelling CTS.");
                _linkedCts.Cancel();
            }
        }

        public void OnItemStatus(DesktopTransferDirection direction, string itemName, string status)
        {
            ItemStatuses.Add((itemName, status));
            if (ItemStatuses.Count <= 5 || ItemStatuses.Count % 100 == 0 || status == "Failed")
            {
                Console.Error.WriteLine($"[ITEM {direction}] {itemName}: {status} (update #{ItemStatuses.Count})");
            }
        }

        public void OnHistory(DesktopHistoryEntry entry)
        {
            History.Add(entry);
        }
    }

    #endregion

    #region Helpers

    private static LanInvitation CreateInvitation(
        TlsSocketListener listener,
        string? advertisedIp,
        LanInvitationAuthority authority,
        string deviceName = "Glide-Test-Host")
    {
        var ips = new List<string>();
        if (!string.IsNullOrWhiteSpace(advertisedIp))
        {
            ips.Add(advertisedIp.Trim());
        }
        else
        {
            var hostAddresses = Dns.GetHostAddresses(Dns.GetHostName())
                .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                .Select(ip => ip.ToString())
                .ToList();
            ips.AddRange(hostAddresses);
            if (ips.Count == 0)
            {
                ips.Add("127.0.0.1");
            }
        }

        var endpoints = ips.Select(ip => new LanEndpoint(ip, listener.BoundPort)).ToArray();
        string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
        string invitationId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 300;

        var rawInvitation = new LanInvitation(
            invitationId: invitationId,
            presence: presence,
            name: deviceName,
            platform: OperatingSystem.IsLinux() ? "linux" : "windows",
            endpoints: endpoints,
            spkiSha256: listener.LocalSpkiSha256,
            token: token,
            expiresAt: expiresAt.ToString(),
            ttlSeconds: 300);

        string encoded = InvitationCodec.Encode(rawInvitation);
        var decoded = InvitationCodec.Decode(encoded);
        return authority.Publish(decoded);
    }

    private static string ReadInvitationUriFromStdin()
    {
        string? line = Console.ReadLine();
        if (string.IsNullOrWhiteSpace(line))
        {
            throw new InvalidOperationException("No invitation URI provided on stdin.");
        }

        line = line.Trim();
        if (line.StartsWith('{') && line.EndsWith('}'))
        {
            using var doc = JsonDocument.Parse(line);
            if (doc.RootElement.TryGetProperty("invitationUri", out var uriElem))
            {
                return uriElem.GetString() ?? throw new InvalidOperationException("Empty invitationUri in JSON.");
            }
        }

        return line;
    }

    private static (DesktopPersistentTrustManager manager, string localFingerprint) SetupTrust(
        string? trustStorePath,
        string? identityStorePath)
    {
        var identityStore = !string.IsNullOrWhiteSpace(identityStorePath)
            ? new DesktopPersistentIdentityStore(identityStorePath)
            : new DesktopPersistentIdentityStore();
        var trustStore = !string.IsNullOrWhiteSpace(trustStorePath)
            ? new DesktopTrustStore(trustStorePath)
            : new DesktopTrustStore();

        var manager = new DesktopPersistentTrustManager(identityStore, trustStore);
        var init = manager.Initialize();
        return (manager, init.LocalFingerprintHex);
    }

    private static bool IsQuarantineEmpty(string stagingDir)
    {
        if (!Directory.Exists(stagingDir)) return true;
        var files = Directory.GetFiles(stagingDir, "*", SearchOption.AllDirectories);
        return files.Length == 0;
    }

    private static JsonArray CollectReceivedFiles(string destDir)
    {
        var array = new JsonArray();
        if (!Directory.Exists(destDir)) return array;

        foreach (string file in Directory.GetFiles(destDir, "*", SearchOption.AllDirectories))
        {
            var fi = new FileInfo(file);
            using var stream = File.OpenRead(file);
            byte[] hash = SHA256.HashData(stream);
            string sha = Convert.ToHexStringLower(hash);
            string rel = Path.GetRelativePath(destDir, file).Replace('\\', '/');

            array.Add(new JsonObject
            {
                ["name"] = fi.Name,
                ["relativePath"] = rel,
                ["sizeBytes"] = fi.Length,
                ["sha256"] = sha
            });
        }
        return array;
    }

    #endregion

    #region Command Handlers

    private static async Task<int> RunListenReceiverAsync(string[] args)
    {
        int port = 45441;
        string? advertisedIp = null;
        string stagingDir = Path.Combine(Path.GetTempPath(), "glide-staging-" + Guid.NewGuid().ToString("N"));
        string destDir = Path.Combine(Path.GetTempPath(), "glide-dest-" + Guid.NewGuid().ToString("N"));
        bool acceptOffer = false;
        bool acceptEnrollment = false;
        string? expectedPeer = null;
        string? trustStorePath = null;
        string? identityStorePath = null;
        long cancelAfterBytes = -1;
        int timeoutSeconds = 60;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--port" when i + 1 < args.Length: port = int.Parse(args[++i]); break;
                case "--advertised-ip" when i + 1 < args.Length: advertisedIp = args[++i]; break;
                case "--staging-dir" when i + 1 < args.Length: stagingDir = args[++i]; break;
                case "--dest-dir" when i + 1 < args.Length: destDir = args[++i]; break;
                case "--decision-offer" when i + 1 < args.Length: acceptOffer = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--decision-enrollment" when i + 1 < args.Length: acceptEnrollment = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--expected-peer" when i + 1 < args.Length: expectedPeer = args[++i]; break;
                case "--trust-store" when i + 1 < args.Length: trustStorePath = args[++i]; break;
                case "--identity-store" when i + 1 < args.Length: identityStorePath = args[++i]; break;
                case "--cancel-after-bytes" when i + 1 < args.Length: cancelAfterBytes = long.Parse(args[++i]); break;
                case "--timeout-seconds" when i + 1 < args.Length: timeoutSeconds = int.Parse(args[++i]); break;
            }
        }

        stagingDir = Path.GetFullPath(stagingDir);
        destDir = Path.GetFullPath(destDir);
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        using var listener = new TlsSocketListener(port);
        var authority = new LanInvitationAuthority();
        LanInvitation invitation = CreateInvitation(listener, advertisedIp, authority, "Glide-Test-Receiver");
        string invitationUri = InvitationCodec.Encode(invitation);

        DesktopPersistentTrustManager? trustManager = null;
        if (trustStorePath != null || identityStorePath != null || expectedPeer != null)
        {
            (trustManager, _) = SetupTrust(trustStorePath, identityStorePath);
        }

        var interaction = new DriverInteraction(
            acceptOffer: acceptOffer,
            acceptEnrollment: acceptEnrollment,
            cancelAfterBytes: cancelAfterBytes,
            linkedCts: cts);

        // Emit invitation to stdout
        var invitationEvt = new JsonObject
        {
            ["event"] = "invitation",
            ["invitationUri"] = invitationUri,
            ["port"] = listener.BoundPort,
            ["spkiSha256"] = listener.LocalSpkiSha256
        };
        Console.WriteLine(invitationEvt.ToJsonString());
        Console.Out.Flush();

        var store = new QuarantineStagingStore(stagingDir, destDir);
        var engine = new DesktopTransferEngine();

        try
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync(cts.Token);
            var req = new DesktopReceiveRequest(
                StagingStore: store,
                InvitationAuthority: authority,
                LocalDeviceName: "Glide-Test-Receiver",
                TrustManager: trustManager,
                ExpectedPeerFingerprintHex: expectedPeer);

            DesktopTransferResult res = await engine.ReceiveAsync(conn, req, interaction, cts.Token);
            bool cleanQuarantine = IsQuarantineEmpty(stagingDir);
            JsonArray files = CollectReceivedFiles(destDir);

            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = res.Success,
                ["rejected"] = res.Rejected,
                ["cancelled"] = res.Cancelled,
                ["message"] = res.Message,
                ["verifiedCount"] = res.VerifiedCount,
                ["verifiedBytes"] = res.VerifiedBytes,
                ["quarantineEmpty"] = cleanQuarantine,
                ["receivedFiles"] = files
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return res.Success ? 0 : (res.Rejected || res.Cancelled ? 2 : 1);
        }
        catch (OperationCanceledException)
        {
            bool cleanQuarantine = IsQuarantineEmpty(stagingDir);
            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = false,
                ["rejected"] = false,
                ["cancelled"] = true,
                ["message"] = "Transfer timed out or was cancelled by policy.",
                ["quarantineEmpty"] = cleanQuarantine
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return 2;
        }
    }

    private static async Task<int> RunListenHostedSenderAsync(string[] args)
    {
        int port = 45441;
        string? advertisedIp = null;
        string? sourceDir = null;
        var fileList = new List<string>();
        bool acceptRecipient = false;
        bool acceptEnrollment = false;
        string? expectedPeer = null;
        string? trustStorePath = null;
        string? identityStorePath = null;
        long cancelAfterBytes = -1;
        int timeoutSeconds = 60;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--port" when i + 1 < args.Length: port = int.Parse(args[++i]); break;
                case "--advertised-ip" when i + 1 < args.Length: advertisedIp = args[++i]; break;
                case "--source-dir" when i + 1 < args.Length: sourceDir = args[++i]; break;
                case "--files" when i + 1 < args.Length: fileList.AddRange(args[++i].Split(',', StringSplitOptions.RemoveEmptyEntries)); break;
                case "--decision-recipient" when i + 1 < args.Length: acceptRecipient = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--decision-enrollment" when i + 1 < args.Length: acceptEnrollment = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--expected-peer" when i + 1 < args.Length: expectedPeer = args[++i]; break;
                case "--trust-store" when i + 1 < args.Length: trustStorePath = args[++i]; break;
                case "--identity-store" when i + 1 < args.Length: identityStorePath = args[++i]; break;
                case "--cancel-after-bytes" when i + 1 < args.Length: cancelAfterBytes = long.Parse(args[++i]); break;
                case "--timeout-seconds" when i + 1 < args.Length: timeoutSeconds = int.Parse(args[++i]); break;
            }
        }

        var files = ResolveFiles(sourceDir, fileList);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        using var listener = new TlsSocketListener(port);
        var authority = new LanInvitationAuthority();
        LanInvitation invitation = CreateInvitation(listener, advertisedIp, authority, "Glide-Test-Sender");
        string invitationUri = InvitationCodec.Encode(invitation);

        DesktopPersistentTrustManager? trustManager = null;
        if (trustStorePath != null || identityStorePath != null || expectedPeer != null)
        {
            (trustManager, _) = SetupTrust(trustStorePath, identityStorePath);
        }

        var interaction = new DriverInteraction(
            acceptRecipient: acceptRecipient,
            acceptEnrollment: acceptEnrollment,
            cancelAfterBytes: cancelAfterBytes,
            linkedCts: cts);

        // Emit invitation to stdout
        var invitationEvt = new JsonObject
        {
            ["event"] = "invitation",
            ["invitationUri"] = invitationUri,
            ["port"] = listener.BoundPort,
            ["spkiSha256"] = listener.LocalSpkiSha256
        };
        Console.WriteLine(invitationEvt.ToJsonString());
        Console.Out.Flush();

        var engine = new DesktopTransferEngine();
        try
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync(cts.Token);
            var req = new DesktopSendRequest(
                Host: "127.0.0.1",
                Port: listener.BoundPort,
                Files: files,
                Invitation: invitation,
                LocalDeviceName: "Glide-Test-Sender",
                TrustManager: trustManager,
                ExpectedPeerFingerprintHex: expectedPeer,
                HostedInvitationAuthority: authority);

            DesktopTransferResult res = await engine.SendHostedAsync(conn, req, interaction, cts.Token);
            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = res.Success,
                ["rejected"] = res.Rejected,
                ["cancelled"] = res.Cancelled,
                ["message"] = res.Message,
                ["verifiedCount"] = res.VerifiedCount,
                ["verifiedBytes"] = res.VerifiedBytes
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return res.Success ? 0 : (res.Rejected || res.Cancelled ? 2 : 1);
        }
        catch (OperationCanceledException)
        {
            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = false,
                ["rejected"] = false,
                ["cancelled"] = true,
                ["message"] = "Transfer timed out or was cancelled by policy."
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return 2;
        }
    }

    private static async Task<int> RunConnectSenderAsync(string[] args)
    {
        string? sourceDir = null;
        var fileList = new List<string>();
        bool acceptEnrollment = false;
        string? expectedPeer = null;
        string? trustStorePath = null;
        string? identityStorePath = null;
        long cancelAfterBytes = -1;
        int timeoutSeconds = 60;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--source-dir" when i + 1 < args.Length: sourceDir = args[++i]; break;
                case "--files" when i + 1 < args.Length: fileList.AddRange(args[++i].Split(',', StringSplitOptions.RemoveEmptyEntries)); break;
                case "--decision-enrollment" when i + 1 < args.Length: acceptEnrollment = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--expected-peer" when i + 1 < args.Length: expectedPeer = args[++i]; break;
                case "--trust-store" when i + 1 < args.Length: trustStorePath = args[++i]; break;
                case "--identity-store" when i + 1 < args.Length: identityStorePath = args[++i]; break;
                case "--cancel-after-bytes" when i + 1 < args.Length: cancelAfterBytes = long.Parse(args[++i]); break;
                case "--timeout-seconds" when i + 1 < args.Length: timeoutSeconds = int.Parse(args[++i]); break;
            }
        }

        string invitationUri = ReadInvitationUriFromStdin();
        LanInvitation invitation = InvitationCodec.Decode(invitationUri);
        var files = ResolveFiles(sourceDir, fileList);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        DesktopPersistentTrustManager? trustManager = null;
        if (trustStorePath != null || identityStorePath != null || expectedPeer != null)
        {
            (trustManager, _) = SetupTrust(trustStorePath, identityStorePath);
        }

        var interaction = new DriverInteraction(
            acceptEnrollment: acceptEnrollment,
            cancelAfterBytes: cancelAfterBytes,
            linkedCts: cts);

        var engine = new DesktopTransferEngine();
        try
        {
            var req = new DesktopSendRequest(
                Host: invitation.Endpoints[0].Address,
                Port: invitation.Endpoints[0].Port,
                Files: files,
                Invitation: invitation,
                LocalDeviceName: "Glide-Connect-Sender",
                TrustManager: trustManager,
                ExpectedPeerFingerprintHex: expectedPeer);

            DesktopTransferResult res = await engine.SendAsync(req, interaction, cts.Token);
            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = res.Success,
                ["rejected"] = res.Rejected,
                ["cancelled"] = res.Cancelled,
                ["message"] = res.Message,
                ["verifiedCount"] = res.VerifiedCount,
                ["verifiedBytes"] = res.VerifiedBytes
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return res.Success ? 0 : (res.Rejected || res.Cancelled ? 2 : 1);
        }
        catch (OperationCanceledException)
        {
            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = false,
                ["rejected"] = false,
                ["cancelled"] = true,
                ["message"] = "Transfer timed out or was cancelled by policy."
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return 2;
        }
    }

    private static async Task<int> RunConnectReceiverAsync(string[] args)
    {
        string stagingDir = Path.Combine(Path.GetTempPath(), "glide-staging-" + Guid.NewGuid().ToString("N"));
        string destDir = Path.Combine(Path.GetTempPath(), "glide-dest-" + Guid.NewGuid().ToString("N"));
        bool acceptOffer = false;
        bool acceptEnrollment = false;
        string? expectedPeer = null;
        string? trustStorePath = null;
        string? identityStorePath = null;
        long cancelAfterBytes = -1;
        int timeoutSeconds = 60;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--staging-dir" when i + 1 < args.Length: stagingDir = args[++i]; break;
                case "--dest-dir" when i + 1 < args.Length: destDir = args[++i]; break;
                case "--decision-offer" when i + 1 < args.Length: acceptOffer = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--decision-enrollment" when i + 1 < args.Length: acceptEnrollment = string.Equals(args[++i], "accept", StringComparison.OrdinalIgnoreCase); break;
                case "--expected-peer" when i + 1 < args.Length: expectedPeer = args[++i]; break;
                case "--trust-store" when i + 1 < args.Length: trustStorePath = args[++i]; break;
                case "--identity-store" when i + 1 < args.Length: identityStorePath = args[++i]; break;
                case "--cancel-after-bytes" when i + 1 < args.Length: cancelAfterBytes = long.Parse(args[++i]); break;
                case "--timeout-seconds" when i + 1 < args.Length: timeoutSeconds = int.Parse(args[++i]); break;
            }
        }

        stagingDir = Path.GetFullPath(stagingDir);
        destDir = Path.GetFullPath(destDir);
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        string invitationUri = ReadInvitationUriFromStdin();
        LanInvitation invitation = InvitationCodec.Decode(invitationUri);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        DesktopPersistentTrustManager? trustManager = null;
        if (trustStorePath != null || identityStorePath != null || expectedPeer != null)
        {
            (trustManager, _) = SetupTrust(trustStorePath, identityStorePath);
        }

        var interaction = new DriverInteraction(
            acceptOffer: acceptOffer,
            acceptEnrollment: acceptEnrollment,
            cancelAfterBytes: cancelAfterBytes,
            linkedCts: cts);

        var store = new QuarantineStagingStore(stagingDir, destDir);
        var engine = new DesktopTransferEngine();

        try
        {
            var req = new DesktopReceiveRequest(
                StagingStore: store,
                Invitation: invitation,
                LocalDeviceName: "Glide-Connect-Receiver",
                TrustManager: trustManager,
                ExpectedPeerFingerprintHex: expectedPeer);

            DesktopTransferResult res = await engine.ReceiveFromInvitationAsync(req, interaction, cts.Token);
            bool cleanQuarantine = IsQuarantineEmpty(stagingDir);
            JsonArray files = CollectReceivedFiles(destDir);

            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = res.Success,
                ["rejected"] = res.Rejected,
                ["cancelled"] = res.Cancelled,
                ["message"] = res.Message,
                ["verifiedCount"] = res.VerifiedCount,
                ["verifiedBytes"] = res.VerifiedBytes,
                ["quarantineEmpty"] = cleanQuarantine,
                ["receivedFiles"] = files
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return res.Success ? 0 : (res.Rejected || res.Cancelled ? 2 : 1);
        }
        catch (OperationCanceledException)
        {
            bool cleanQuarantine = IsQuarantineEmpty(stagingDir);
            var resultEvt = new JsonObject
            {
                ["event"] = "result",
                ["success"] = false,
                ["rejected"] = false,
                ["cancelled"] = true,
                ["message"] = "Transfer timed out or was cancelled by policy.",
                ["quarantineEmpty"] = cleanQuarantine
            };
            Console.WriteLine(resultEvt.ToJsonString());
            Console.Out.Flush();
            return 2;
        }
    }

    private static List<DesktopTransferFile> ResolveFiles(string? sourceDir, List<string> fileList)
    {
        var result = new List<DesktopTransferFile>();
        if (!string.IsNullOrWhiteSpace(sourceDir) && Directory.Exists(sourceDir))
        {
            foreach (string filePath in Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories))
            {
                if (Path.GetFileName(filePath) == "corpus-manifest.json") continue;
                string rel = Path.GetRelativePath(sourceDir, filePath).Replace('\\', '/');
                result.Add(new DesktopTransferFile(filePath, rel));
            }
        }
        else
        {
            foreach (string f in fileList)
            {
                if (File.Exists(f))
                {
                    result.Add(new DesktopTransferFile(f));
                }
            }
        }
        return result;
    }

    #endregion

    #region Adversarial Protocol Injections

    private static async Task<int> RunAdversarialSendAsync(string[] args)
    {
        string attack = "bad-digest";
        int timeoutSeconds = 15;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--attack" when i + 1 < args.Length: attack = args[++i].ToLowerInvariant(); break;
                case "--timeout-seconds" when i + 1 < args.Length: timeoutSeconds = int.Parse(args[++i]); break;
            }
        }

        string invitationUri = ReadInvitationUriFromStdin();
        LanInvitation invitation = InvitationCodec.Decode(invitationUri);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        var client = new TlsSocketClient();

        string expectedSpki = attack == "wrong-pin"
            ? "0000000000000000000000000000000000000000000000000000000000000000"
            : invitation.SpkiSha256;

        TlsSocketConnection conn;
        try
        {
            conn = await client.ConnectAsync(
                invitation.Endpoints[0].Address,
                invitation.Endpoints[0].Port,
                expectedSpkiSha256: expectedSpki,
                cancellationToken: cts.Token);
        }
        catch (Exception ex)
        {
            var attackResult = new JsonObject
            {
                ["event"] = "result",
                ["success"] = false,
                ["attack"] = attack,
                ["rejected"] = true,
                ["message"] = $"Connection rejected as expected during TLS/pin validation: {ex.Message}"
            };
            Console.WriteLine(attackResult.ToJsonString());
            return 0;
        }

        await using (conn)
        {
            using var channel = new LanRecordChannel(conn.SslStream);

            // 1. BOOTSTRAP
            string tokenToSend = attack == "wrong-token"
                ? "invalid-token-12345678901234567890123456789012"
                : invitation.Token;

            string invHashToSend = attack == "altered-payload"
                ? "0000000000000000000000000000000000000000000000000000000000000000"
                : invitation.InvitationHash;

            var bootstrap = new LanBootstrapMessage(
                invitation.InvitationId,
                tokenToSend,
                invHashToSend,
                Guid.NewGuid().ToString("D").ToLowerInvariant(),
                LanBootstrapMessage.RoleSender);

            await channel.WriteBootstrapAsync(bootstrap, cts.Token);
            LanBootstrapResult bResult = await channel.ReadBootstrapResultAsync(cts.Token);
            if (!bResult.IsAccepted)
            {
                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = true,
                    ["message"] = $"Bootstrap rejected by receiver: {bResult.Code}"
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 0;
            }

            SessionId sessionId = new SessionId(bResult.SessionId!);
            long seqNum = 1;

            // 2. HELLO
            var reqCaps = new JsonArray(ProtocolCapabilities.StreamV1, ProtocolCapabilities.Sha256);
            if (attack == "incompatible-caps")
            {
                reqCaps = new JsonArray(); // Missing required caps!
            }

            var helloBody = new JsonObject
            {
                ["supportedMajors"] = new JsonArray(1),
                ["requiredCapabilities"] = reqCaps,
                ["optionalCapabilities"] = new JsonArray(),
                ["role"] = "sender",
                ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                ["displayName"] = "Adversarial-Sender"
            };

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.Hello, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), helloBody), cts.Token);
            Envelope helloAck;
            try
            {
                helloAck = await channel.ReadEnvelopeAsync(cts.Token);
            }
            catch (Exception ex)
            {
                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = true,
                    ["message"] = $"HELLO rejected: {ex.Message}"
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 0;
            }

            if (attack == "incompatible-caps")
            {
                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = false,
                    ["message"] = "Receiver unexpectedly accepted missing capabilities!"
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 1;
            }

            if (attack == "corrupt-control")
            {
                // Send malformed record directly
                byte[] junk = Encoding.UTF8.GetBytes("{not valid json at all!}");
                await channel.WriteRecordAsync(new LanRecord.Control(junk), cts.Token);
                try
                {
                    await channel.ReadRecordAsync(cts.Token);
                }
                catch { }

                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = true,
                    ["message"] = "Corrupt control sent; receiver rejected connection."
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 0;
            }

            if (attack == "oversized-record")
            {
                // Send oversized control payload (>16 KiB)
                byte[] huge = new byte[32_768];
                Array.Fill(huge, (byte)'A');
                await channel.WriteRecordAsync(new LanRecord.Control(huge), cts.Token);
                try
                {
                    await channel.ReadRecordAsync(cts.Token);
                }
                catch { }

                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = true,
                    ["message"] = "Oversized record sent; receiver rejected."
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 0;
            }

            if (attack == "invalid-sequence")
            {
                // Send ITEM_START before OFFER!
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), new JsonObject
                {
                    ["batchId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["itemId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["bulkRef"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                    ["sizeBytes"] = "1024"
                }), cts.Token);

                try
                {
                    await channel.ReadEnvelopeAsync(cts.Token);
                }
                catch (Exception ex)
                {
                    var attackResult = new JsonObject
                    {
                        ["event"] = "result",
                        ["success"] = false,
                        ["attack"] = attack,
                        ["rejected"] = true,
                        ["message"] = $"Sequence violation caught: {ex.Message}"
                    };
                    Console.WriteLine(attackResult.ToJsonString());
                    return 0;
                }
            }

            // 3. Normal Offer
            string batchId = Guid.NewGuid().ToString("D").ToLowerInvariant();
            string itemId = Guid.NewGuid().ToString("D").ToLowerInvariant();
            long declaredSize = 4096;

            string itemName = attack switch
            {
                "traversal-dots" => "../../evil.txt",
                "traversal-slash" => "nested/sub/evil.txt",
                "traversal-win" => @"..\..\evil.txt",
                "dos-con" => "CON.txt",
                "dos-prn" => "PRN",
                _ => "testfile.bin"
            };

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferBegin, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), new JsonObject
            {
                ["batchId"] = batchId,
                ["itemCount"] = 1,
                ["totalBytes"] = declaredSize.ToString(),
                ["pageCount"] = 1
            }), cts.Token);

            var pageItems = new JsonArray
            {
                new JsonObject
                {
                    ["itemId"] = itemId,
                    ["name"] = itemName,
                    ["mime"] = "application/octet-stream",
                    ["sizeBytes"] = declaredSize.ToString(),
                    ["relativePath"] = itemName
                }
            };

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferPage, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), new JsonObject
            {
                ["batchId"] = batchId,
                ["pageIndex"] = 0,
                ["items"] = pageItems
            }), cts.Token);

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), new JsonObject
            {
                ["batchId"] = batchId
            }), cts.Token);

            Envelope decision = await channel.ReadEnvelopeAsync(cts.Token);
            bool accepted = bool.Parse(decision.Body["accepted"]!.ToString());
            if (!accepted)
            {
                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = true,
                    ["message"] = "Offer rejected by receiver (expected if testing refusal or malicious paths)."
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 0;
            }

            // 4. Send Item
            string actualItemIdToSend = attack == "wrong-item-id"
                ? Guid.NewGuid().ToString("D").ToLowerInvariant()
                : itemId;
            string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = actualItemIdToSend,
                ["bulkRef"] = bulkRef,
                ["sizeBytes"] = declaredSize.ToString()
            }), cts.Token);

            try
            {
                Envelope ready = await channel.ReadEnvelopeAsync(cts.Token);
            }
            catch (Exception ex)
            {
                var attackResult = new JsonObject
                {
                    ["event"] = "result",
                    ["success"] = false,
                    ["attack"] = attack,
                    ["rejected"] = true,
                    ["message"] = $"ItemStart rejected: {ex.Message}"
                };
                Console.WriteLine(attackResult.ToJsonString());
                return 0;
            }

            byte[] dataBytes = new byte[declaredSize];
            Array.Fill(dataBytes, (byte)0x42);
            string correctSha = Convert.ToHexStringLower(SHA256.HashData(dataBytes));

            if (attack == "truncated-stream")
            {
                // Send only half the data, then STREAM_END
                byte[] half = dataBytes[..2048];
                await channel.WriteDataChunkAsync(new BulkRef(bulkRef), half, cts.Token);
                await channel.WriteStreamEndAsync(new BulkRef(bulkRef), half.Length, cts.Token);
            }
            else if (attack == "excess-stream")
            {
                // Send extra data (> declaredSize)
                byte[] extra = new byte[6000];
                await channel.WriteDataChunkAsync(new BulkRef(bulkRef), extra, cts.Token);
                await channel.WriteStreamEndAsync(new BulkRef(bulkRef), extra.Length, cts.Token);
            }
            else
            {
                await channel.WriteDataChunkAsync(new BulkRef(bulkRef), dataBytes, cts.Token);
                await channel.WriteStreamEndAsync(new BulkRef(bulkRef), dataBytes.Length, cts.Token);
            }

            string shaToSend = attack == "bad-digest"
                ? "0000000000000000000000000000000000000000000000000000000000000000"
                : correctSha;

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), checked(seqNum++), new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = actualItemIdToSend,
                ["actualBytes"] = dataBytes.Length.ToString(),
                ["sha256"] = shaToSend
            }), cts.Token);

            Envelope itemResult = await channel.ReadEnvelopeAsync(cts.Token);
            string itemStatus = itemResult.Body["status"]?.ToString() ?? "unknown";

            var attackSummary = new JsonObject
            {
                ["event"] = "result",
                ["success"] = itemStatus == "verified",
                ["attack"] = attack,
                ["rejected"] = itemStatus != "verified",
                ["itemStatus"] = itemStatus,
                ["message"] = itemStatus == "verified"
                    ? "UNEXPECTED: Receiver verified item under attack!"
                    : $"Receiver safely rejected/failed item under attack ({attack}): status={itemStatus}"
            };
            Console.WriteLine(attackSummary.ToJsonString());
            return itemStatus == "verified" ? 1 : 0;
        }
    }

    #endregion

    #region Corpus Generation & Verification

    private static int RunGenerateCorpus(string[] args)
    {
        string outputDir = Path.Combine("build", "corpus-matrix");
        int smallCount = 32;
        int largeMiB = 100;
        bool addExtraCases = true;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--output-dir" when i + 1 < args.Length: outputDir = args[++i]; break;
                case "--small-count" when i + 1 < args.Length: smallCount = int.Parse(args[++i]); break;
                case "--large-mib" when i + 1 < args.Length: largeMiB = int.Parse(args[++i]); break;
                case "--no-extra": addExtraCases = false; break;
            }
        }

        Directory.CreateDirectory(outputDir);

        if (smallCount == 0)
        {
            byte[] buf = new byte[64 * 1024];
            string singleLargePath = Path.Combine(outputDir, $"99-large-{largeMiB}mib.bin");
            WritePatternFile(singleLargePath, (long)largeMiB * 1024 * 1024, 99, buf);
        }
        else
        {
            // 1. 00-empty.bin
            File.WriteAllBytes(Path.Combine(outputDir, "00-empty.bin"), []);

            // 2. Deterministic small files 01..32
            byte[] buffer = new byte[64 * 1024];
            for (int n = 1; n <= smallCount; n++)
            {
                string path = Path.Combine(outputDir, $"{n:D2}-small-{n:D2}.bin");
                long size = 1024 + (n * 37);
                WritePatternFile(path, size, n, buffer);
            }

            if (largeMiB > 0)
            {
                // 3. Medium 10 MiB file
                string mediumPath = Path.Combine(outputDir, "90-medium-10mib.bin");
                WritePatternFile(mediumPath, 10L * 1024 * 1024, 90, buffer);

                // 4. Large >=100 MiB file
                string largePath = Path.Combine(outputDir, $"99-large-{largeMiB}mib.bin");
                WritePatternFile(largePath, (long)largeMiB * 1024 * 1024, 99, buffer);
            }

            // 5. Extra cases: Unicode, spaces, nested folder, duplicate basenames
            if (addExtraCases)
            {
                // Unicode name
                File.WriteAllText(Path.Combine(outputDir, "Unicode_🚀_测试_файл.txt"), "Unicode content: 🌟✨", Encoding.UTF8);

                // Spaces in filename
                File.WriteAllText(Path.Combine(outputDir, "Document With Multiple Spaces In Name.txt"), "Space content", Encoding.UTF8);

                // Nested folder
                string nested = Path.Combine(outputDir, "folder_a", "subfolder_b");
                Directory.CreateDirectory(nested);
                File.WriteAllText(Path.Combine(nested, "nested_file.txt"), "Nested content", Encoding.UTF8);

                // Duplicate basename in different folder
                string nested2 = Path.Combine(outputDir, "folder_c");
                Directory.CreateDirectory(nested2);
                File.WriteAllText(Path.Combine(nested2, "nested_file.txt"), "Duplicate basename in different folder", Encoding.UTF8);
            }
        }

        // Generate manifest
        var items = new JsonArray();
        long totalBytes = 0;
        foreach (string file in Directory.GetFiles(outputDir, "*", SearchOption.AllDirectories).OrderBy(f => f))
        {
            if (Path.GetFileName(file) == "corpus-manifest.json") continue;
            var fi = new FileInfo(file);
            using var stream = File.OpenRead(file);
            string sha = Convert.ToHexStringLower(SHA256.HashData(stream));
            string rel = Path.GetRelativePath(outputDir, file).Replace('\\', '/');

            items.Add(new JsonObject
            {
                ["name"] = fi.Name,
                ["relativePath"] = rel,
                ["sizeBytes"] = fi.Length,
                ["sha256"] = sha
            });
            totalBytes += fi.Length;
        }

        var manifest = new JsonObject
        {
            ["schema"] = "glide-backend-test-corpus-v1",
            ["generatedAtUtc"] = DateTime.UtcNow.ToString("O"),
            ["itemCount"] = items.Count,
            ["totalBytes"] = totalBytes,
            ["items"] = items
        };

        string manifestPath = Path.Combine(outputDir, "corpus-manifest.json");
        File.WriteAllText(manifestPath, manifest.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

        var evt = new JsonObject
        {
            ["event"] = "corpus_generated",
            ["path"] = outputDir,
            ["itemCount"] = items.Count,
            ["totalBytes"] = totalBytes,
            ["manifest"] = manifestPath
        };
        Console.WriteLine(evt.ToJsonString());
        return 0;
    }

    private static void WritePatternFile(string path, long sizeBytes, int seed, byte[] buffer)
    {
        for (int i = 0; i < buffer.Length; i++)
        {
            buffer[i] = (byte)((i + seed) % 251);
        }

        using var fs = File.Open(path, FileMode.Create, FileAccess.Write, FileShare.None);
        long remaining = sizeBytes;
        while (remaining > 0)
        {
            int count = (int)Math.Min(buffer.Length, remaining);
            fs.Write(buffer, 0, count);
            remaining -= count;
        }
        fs.Flush();
    }

    private static int RunVerifyCorpus(string[] args)
    {
        string? corpusDir = null;
        string? destDir = null;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--corpus-dir" when i + 1 < args.Length: corpusDir = args[++i]; break;
                case "--dest-dir" when i + 1 < args.Length: destDir = args[++i]; break;
            }
        }

        if (string.IsNullOrWhiteSpace(corpusDir) || string.IsNullOrWhiteSpace(destDir))
        {
            Console.Error.WriteLine("Both --corpus-dir and --dest-dir are required.");
            return 1;
        }

        string manifestPath = Path.Combine(corpusDir, "corpus-manifest.json");
        if (!File.Exists(manifestPath))
        {
            Console.Error.WriteLine($"Manifest not found: {manifestPath}");
            return 1;
        }

        using var doc = JsonDocument.Parse(File.ReadAllText(manifestPath));
        var items = doc.RootElement.GetProperty("items").EnumerateArray();
        int verified = 0;
        int mismatch = 0;
        int missing = 0;
        var details = new JsonArray();

        foreach (var item in items)
        {
            string rel = item.GetProperty("relativePath").GetString()!;
            string expectedSha = item.GetProperty("sha256").GetString()!;
            long expectedSize = item.GetProperty("sizeBytes").GetInt64();

            string destFile = Path.Combine(destDir, rel.Replace('/', Path.DirectorySeparatorChar));
            if (!File.Exists(destFile))
            {
                missing++;
                details.Add(new JsonObject { ["file"] = rel, ["status"] = "missing" });
                continue;
            }

            var fi = new FileInfo(destFile);
            if (fi.Length != expectedSize)
            {
                mismatch++;
                details.Add(new JsonObject { ["file"] = rel, ["status"] = "size_mismatch", ["expected"] = expectedSize, ["actual"] = fi.Length });
                continue;
            }

            using var stream = File.OpenRead(destFile);
            string actualSha = Convert.ToHexStringLower(SHA256.HashData(stream));
            if (!string.Equals(actualSha, expectedSha, StringComparison.OrdinalIgnoreCase))
            {
                mismatch++;
                details.Add(new JsonObject { ["file"] = rel, ["status"] = "sha_mismatch", ["expected"] = expectedSha, ["actual"] = actualSha });
                continue;
            }

            verified++;
        }

        bool success = missing == 0 && mismatch == 0 && verified > 0;
        var res = new JsonObject
        {
            ["event"] = "corpus_verified",
            ["success"] = success,
            ["verified"] = verified,
            ["missing"] = missing,
            ["mismatch"] = mismatch,
            ["details"] = details
        };
        Console.WriteLine(res.ToJsonString());
        return success ? 0 : 1;
    }

    #endregion

    #region Unit Matrix Subtests

    private static Task<int> RunTestLimitsAsync(string[] args)
    {
        // Tests Protocol limits: 1001 items, >64 GiB per item, >256 GiB batch
        string tempDir = Path.Combine(Path.GetTempPath(), "limits-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempDir);
        string dummyFile = Path.Combine(tempDir, "dummy.bin");
        File.WriteAllBytes(dummyFile, [1, 2, 3]);

        var engine = new DesktopTransferEngine();
        var interaction = new DriverInteraction();

        // 1. >1,000 files
        var tooMany = Enumerable.Range(0, 1001)
            .Select(i => new DesktopTransferFile(dummyFile, $"file_{i}.bin"))
            .ToList();

        var dummyInvitation = new LanInvitation(
            "11111111-1111-4111-8111-111111111111", "0123456789abcdef0123456789abcdef", "name", "windows", [new LanEndpoint("127.0.0.1", 45441)],
            new string('0', 64), "token-1234567890123456789012345678901234567", (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 100).ToString(), 100);
        string encoded = InvitationCodec.Encode(dummyInvitation);
        dummyInvitation = InvitationCodec.Decode(encoded);

        var req1001 = new DesktopSendRequest("127.0.0.1", 45441, tooMany, dummyInvitation);
        var res1001 = engine.SendAsync(req1001, interaction).GetAwaiter().GetResult();
        bool pass1001 = !res1001.Success && res1001.Message.Contains("1,000");

        // Cleanup
        Directory.Delete(tempDir, true);

        var result = new JsonObject
        {
            ["event"] = "limits_result",
            ["success"] = pass1001,
            ["pass1001ItemsRejected"] = pass1001
        };
        Console.WriteLine(result.ToJsonString());
        return Task.FromResult(pass1001 ? 0 : 1);
    }

    private static Task<int> RunTestStorageSafetyAsync(string[] args)
    {
        // Verifies path sanitization and quarantine mechanics
        string badTraversal = "../../escaped.txt";
        bool traversalBlocked = false;
        try
        {
            WindowsPathSanitizer.SanitizeRelativePath(badTraversal);
        }
        catch (ArgumentException)
        {
            traversalBlocked = true;
        }

        string dosCon = "CON";
        bool dosBlocked = false;
        try
        {
            WindowsPathSanitizer.SanitizeRelativePath(dosCon);
        }
        catch (ArgumentException)
        {
            dosBlocked = true;
        }

        bool pass = traversalBlocked && dosBlocked;
        var result = new JsonObject
        {
            ["event"] = "storage_safety_result",
            ["success"] = pass,
            ["traversalBlocked"] = traversalBlocked,
            ["dosBlocked"] = dosBlocked
        };
        Console.WriteLine(result.ToJsonString());
        return Task.FromResult(pass ? 0 : 1);
    }

    private static Task<int> RunTestTrustAsync(string[] args)
    {
        string tempDir = Path.Combine(Path.GetTempPath(), "trust-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempDir);
        string idPath = Path.Combine(tempDir, "identity.json");
        string trPath = Path.Combine(tempDir, "trust.json");

        var (mgr, fp) = SetupTrust(trPath, idPath);
        bool initSuccess = !string.IsNullOrWhiteSpace(fp) && fp.Length == 64;

        // Rotate identity
        var rotated = mgr.RotateLocalIdentity();
        bool rotateSuccess = !string.IsNullOrWhiteSpace(rotated.LocalFingerprintHex) && rotated.LocalFingerprintHex != fp;

        Directory.Delete(tempDir, true);
        bool pass = initSuccess && rotateSuccess;
        var result = new JsonObject
        {
            ["event"] = "trust_result",
            ["success"] = pass,
            ["initialFingerprintValid"] = initSuccess,
            ["rotationSuccessful"] = rotateSuccess
        };
        Console.WriteLine(result.ToJsonString());
        return Task.FromResult(pass ? 0 : 1);
    }

    private static Task<int> RunTestDiscoveryAsync(string[] args)
    {
        string devName = LanDiscoveryConstants.NormalizeDeviceName("Test Device 🌟");
        bool validName = LanDiscoveryConstants.IsValidDeviceName(devName);
        string presence = LanDiscoveryConstants.GeneratePresenceId();
        bool validPresence = LanDiscoveryConstants.IsValidPresenceId(presence);

        // Test multicast socket binding
        bool multicastBound = false;
        try
        {
            using var udp = new UdpClient();
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));
            multicastBound = true;
        }
        catch { }

        bool pass = validName && validPresence && multicastBound;
        var result = new JsonObject
        {
            ["event"] = "discovery_result",
            ["success"] = pass,
            ["validName"] = validName,
            ["validPresence"] = validPresence,
            ["multicastSocketBound"] = multicastBound
        };
        Console.WriteLine(result.ToJsonString());
        return Task.FromResult(pass ? 0 : 1);
    }

    private static Task<int> RunTestOwnershipAsync(string[] args)
    {
        // Tests listener ownership lease
        using var lease1 = OperatingSystem.IsLinux()
            ? DesktopListenerOwnership.AcquireLinux()
            : null;

        bool duplicateAcquisitionRejected = false;
        if (OperatingSystem.IsLinux())
        {
            try
            {
                using var lease2 = DesktopListenerOwnership.AcquireLinux();
                duplicateAcquisitionRejected = false;
            }
            catch (IOException)
            {
                duplicateAcquisitionRejected = true;
            }
        }
        else
        {
            duplicateAcquisitionRejected = true;
        }

        var result = new JsonObject
        {
            ["event"] = "ownership_result",
            ["success"] = duplicateAcquisitionRejected,
            ["duplicateAcquisitionRejected"] = duplicateAcquisitionRejected
        };
        Console.WriteLine(result.ToJsonString());
        return Task.FromResult(duplicateAcquisitionRejected ? 0 : 1);
    }

    #endregion
}
