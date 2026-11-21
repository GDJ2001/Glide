using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
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
using Glide.Desktop.Diagnostics;
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
                "storage-baseline" => await RunStorageBaselineAsync(args[1..]),
                "benchmark" => await RunBenchmarkAsync(args[1..]),
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
        Console.Error.WriteLine("  storage-baseline [--target-dir DIR] [--size-mb N] [--buffer-kb K] [--repetitions N] [--output-json PATH] [--r3-report PATH]");
        Console.Error.WriteLine("  benchmark [--size-mb N] [--mode legacy|current] [--path all|1|2|3|4] [--run both|cold|warm] [--stage NAME] [--bench-root DIR] [--corpus-path PATH] [--dataset single|mixed|small-1000] [--incompressible] [--repetitions N] [--timeout-seconds N] [--output-json PATH] [--output-log PATH] [--r3-report PATH]");
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
        string? r3ReportPath = null;

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
                case "--r3-report" when i + 1 < args.Length: r3ReportPath = args[++i]; break;
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
            if (!string.IsNullOrWhiteSpace(r3ReportPath) && engine.LastR3DiagnosticReport != null)
            {
                await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(r3ReportPath, engine.LastR3DiagnosticReport);
            }

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
        string? r3ReportPath = null;

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
                case "--r3-report" when i + 1 < args.Length: r3ReportPath = args[++i]; break;
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
            if (!string.IsNullOrWhiteSpace(r3ReportPath) && engine.LastR3DiagnosticReport != null)
            {
                await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(r3ReportPath, engine.LastR3DiagnosticReport);
            }
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
        string? r3ReportPath = null;

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
                case "--r3-report" when i + 1 < args.Length: r3ReportPath = args[++i]; break;
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
            if (!string.IsNullOrWhiteSpace(r3ReportPath) && engine.LastR3DiagnosticReport != null)
            {
                await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(r3ReportPath, engine.LastR3DiagnosticReport);
            }
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
        string? r3ReportPath = null;

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
                case "--r3-report" when i + 1 < args.Length: r3ReportPath = args[++i]; break;
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
            if (!string.IsNullOrWhiteSpace(r3ReportPath) && engine.LastR3DiagnosticReport != null)
            {
                await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(r3ReportPath, engine.LastR3DiagnosticReport);
            }

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
        bool incompressible = true;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--output-dir" when i + 1 < args.Length: outputDir = args[++i]; break;
                case "--small-count" when i + 1 < args.Length: smallCount = int.Parse(args[++i]); break;
                case "--large-mib" when i + 1 < args.Length: largeMiB = int.Parse(args[++i]); break;
                case "--no-extra": addExtraCases = false; break;
                case "--incompressible": incompressible = true; break;
            }
        }
        _ = incompressible;

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
        WriteIncompressibleFile(path, sizeBytes, (ulong)(uint)seed ^ 0xA0761D6478BD642FUL, buffer);
    }

    private static string WriteIncompressibleFile(string path, long sizeBytes, ulong seed, byte[] buffer)
    {
        using var fs = File.Open(path, FileMode.Create, FileAccess.Write, FileShare.None);
        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        ulong state = seed == 0 ? 0x9E3779B97F4A7C15UL : seed;
        long remaining = sizeBytes;
        while (remaining > 0)
        {
            int count = (int)Math.Min(buffer.Length, remaining);
            FillSplitMix64(buffer.AsSpan(0, count), ref state);
            fs.Write(buffer, 0, count);
            hasher.AppendData(buffer, 0, count);
            remaining -= count;
        }
        fs.Flush();
        return Convert.ToHexStringLower(hasher.GetHashAndReset());
    }

    private static void FillSplitMix64(Span<byte> dest, ref ulong state)
    {
        Span<ulong> words = MemoryMarshal.Cast<byte, ulong>(dest);
        for (int i = 0; i < words.Length; i++)
        {
            state += 0x9E3779B97F4A7C15UL;
            ulong z = state;
            z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9UL;
            z = (z ^ (z >> 27)) * 0x94D049BB133111EBUL;
            words[i] = z ^ (z >> 31);
        }

        int tailStart = words.Length * sizeof(ulong);
        if (tailStart < dest.Length)
        {
            state += 0x9E3779B97F4A7C15UL;
            ulong z = state;
            z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9UL;
            z = (z ^ (z >> 27)) * 0x94D049BB133111EBUL;
            z ^= z >> 31;
            for (int b = 0; b < dest.Length - tailStart; b++)
            {
                dest[tailStart + b] = (byte)(z >> (b * 8));
            }
        }
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

    #region Reproducible 4-Path Benchmark Harness

    private sealed class SimulatedWpfSynchronizationContext : SynchronizationContext, IDisposable
    {
        private readonly BlockingCollection<(SendOrPostCallback Callback, object? State, ManualResetEventSlim? Done, Action<Exception>? OnError)> _queue = new();
        private readonly Thread _uiThread;
        private readonly int _simulatedCallbackWorkSpinIterations;
        private long _dispatchedCallbackCount;

        public SimulatedWpfSynchronizationContext(int simulatedCallbackWorkSpinIterations = 1200)
        {
            _simulatedCallbackWorkSpinIterations = simulatedCallbackWorkSpinIterations;
            _uiThread = new Thread(RunMessagePump)
            {
                IsBackground = true,
                Name = "Simulated-WPF-Dispatcher"
            };
            _uiThread.Start();
        }

        public long DispatchedCallbackCount => Interlocked.Read(ref _dispatchedCallbackCount);

        private void RunMessagePump()
        {
            SetSynchronizationContext(this);
            foreach (var item in _queue.GetConsumingEnumerable())
            {
                try
                {
                    if (_simulatedCallbackWorkSpinIterations > 0)
                    {
                        Thread.SpinWait(_simulatedCallbackWorkSpinIterations);
                    }
                    Interlocked.Increment(ref _dispatchedCallbackCount);
                    item.Callback(item.State);
                }
                catch (Exception ex)
                {
                    item.OnError?.Invoke(ex);
                }
                finally
                {
                    item.Done?.Set();
                }
            }
        }

        public override void Post(SendOrPostCallback d, object? state)
        {
            if (!_queue.IsAddingCompleted)
            {
                try
                {
                    _queue.Add((d, state, null, null));
                }
                catch (InvalidOperationException) { }
            }
        }

        public override void Send(SendOrPostCallback d, object? state)
        {
            if (Thread.CurrentThread == _uiThread)
            {
                if (_simulatedCallbackWorkSpinIterations > 0)
                {
                    Thread.SpinWait(_simulatedCallbackWorkSpinIterations);
                }
                Interlocked.Increment(ref _dispatchedCallbackCount);
                d(state);
                return;
            }

            using var done = new ManualResetEventSlim(false);
            Exception? error = null;
            _queue.Add((d, state, done, ex => error = ex));
            done.Wait();
            if (error != null)
            {
                throw new AggregateException(error);
            }
        }

        public Task<T> InvokeOnUiThreadAsync<T>(Func<Task<T>> func)
        {
            var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
            Post(async _ =>
            {
                try
                {
                    T res = await func();
                    tcs.TrySetResult(res);
                }
                catch (Exception ex)
                {
                    tcs.TrySetException(ex);
                }
            }, null);
            return tcs.Task;
        }

        public void Dispose()
        {
            _queue.CompleteAdding();
            _uiThread.Join(TimeSpan.FromSeconds(2));
            _queue.Dispose();
        }
    }

    private sealed class BenchmarkProgressTracker : IDesktopTransferInteraction
    {
        private readonly SimulatedWpfSynchronizationContext? _uiContext;
        private readonly bool _useBlockingUiInvoke;
        private readonly DesktopTransferRateCalculator _rateCalculator = new();
        private readonly long _totalBytes;
        private readonly List<(double ElapsedSec, long Bytes)> _oneSecondPoints = new(256);
        private long _startTimestamp;
        private long _lastSampleTimestamp;
        private long _firstQuarterStartTimestamp;
        private long _firstQuarterEndTimestamp;
        private long _finalQuarterStartTimestamp;
        private long _finalQuarterEndTimestamp;
        private long _slice45GbTimestamp;
        private long _slice45GbBytes;
        private long _slice50GbTimestamp;
        private long _slice50GbBytes;
        private long _firstQuarterStartBytes;
        private long _firstQuarterEndBytes;
        private long _finalQuarterStartBytes;
        private long _finalQuarterEndBytes;
        private long _lastProgressCallbacks;
        private const long Slice45GbThreshold = 4_500_000_000L;
        private const long Slice50GbThreshold = 5_000_000_000L;

        public BenchmarkProgressTracker(
            long totalBytes,
            SimulatedWpfSynchronizationContext? uiContext = null,
            bool useBlockingUiInvoke = false)
        {
            _totalBytes = totalBytes;
            _uiContext = uiContext;
            _useBlockingUiInvoke = useBlockingUiInvoke;
            _rateCalculator.Reset(0);
        }

        public long ProgressCallbacks => Interlocked.Read(ref _lastProgressCallbacks);

        public IReadOnlyList<(double ElapsedSec, long Bytes)> ProgressPoints => _oneSecondPoints;

        public IReadOnlyList<double> ComputeOneSecondRatesMBps()
        {
            var rates = new List<double>();
            for (int i = 1; i < _oneSecondPoints.Count; i++)
            {
                double dt = _oneSecondPoints[i].ElapsedSec - _oneSecondPoints[i - 1].ElapsedSec;
                long db = Math.Max(0L, _oneSecondPoints[i].Bytes - _oneSecondPoints[i - 1].Bytes);
                if (dt >= 0.25)
                {
                    rates.Add(Math.Round((db / dt) / 1_000_000.0, 2));
                }
            }
            return rates;
        }

        public JsonObject? BuildSlice4_5To5_0GbJson()
        {
            if (_totalBytes < Slice50GbThreshold || _slice45GbTimestamp == 0 || _slice50GbTimestamp <= _slice45GbTimestamp)
            {
                return null;
            }
            double durMs = Stopwatch.GetElapsedTime(_slice45GbTimestamp, _slice50GbTimestamp).TotalMilliseconds;
            long deltaBytes = Math.Max(0L, _slice50GbBytes - _slice45GbBytes);
            double rateMBps = durMs > 0.1 ? (deltaBytes / (durMs / 1000.0)) / 1_000_000.0 : 0.0;
            double? q1 = FirstQuarterMBps;
            double? ratio = q1 > 0.1 ? Math.Round(rateMBps / q1.Value, 3) : null;
            return new JsonObject
            {
                ["startBytes"] = _slice45GbBytes,
                ["endBytes"] = _slice50GbBytes,
                ["durationMs"] = Math.Round(durMs, 2),
                ["rateMBps"] = Math.Round(rateMBps, 2),
                ["ratioToFirstQuarter"] = ratio
            };
        }

        public double? FirstQuarterMBps
        {
            get
            {
                if (_firstQuarterStartTimestamp == 0 || _firstQuarterEndTimestamp <= _firstQuarterStartTimestamp ||
                    _firstQuarterEndBytes <= _firstQuarterStartBytes)
                    return null;
                double sec = Stopwatch.GetElapsedTime(_firstQuarterStartTimestamp, _firstQuarterEndTimestamp).TotalSeconds;
                double bytes = _firstQuarterEndBytes - _firstQuarterStartBytes;
                return sec > 0.0001 ? Math.Round((bytes / sec) / 1_000_000.0, 2) : null;
            }
        }

        public double? FinalQuarterMBps
        {
            get
            {
                if (_finalQuarterStartTimestamp == 0 || _finalQuarterEndTimestamp <= _finalQuarterStartTimestamp ||
                    _finalQuarterEndBytes <= _finalQuarterStartBytes)
                    return null;
                double sec = Stopwatch.GetElapsedTime(_finalQuarterStartTimestamp, _finalQuarterEndTimestamp).TotalSeconds;
                double bytes = _finalQuarterEndBytes - _finalQuarterStartBytes;
                return sec > 0.0001 ? Math.Round((bytes / sec) / 1_000_000.0, 2) : null;
            }
        }

        public Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public void OnInvitationConsumed(DesktopTransferDirection direction) { }
        public void OnPeerIdentified(DesktopTransferDirection direction, string peerName) { }
        public void OnStatus(DesktopTransferDirection direction, string message) { }
        public void OnItemStatus(DesktopTransferDirection direction, string itemName, string status) { }
        public void OnHistory(DesktopHistoryEntry entry) { }

        public void OnProgress(DesktopTransferProgress progress)
        {
            Interlocked.Increment(ref _lastProgressCallbacks);
            long now = Stopwatch.GetTimestamp();
            if (_startTimestamp == 0)
            {
                _startTimestamp = now;
                _lastSampleTimestamp = now;
                _firstQuarterStartTimestamp = now;
                _firstQuarterStartBytes = progress.TransferredBytes;
                _oneSecondPoints.Add((0.0, progress.TransferredBytes));
            }

            if (Stopwatch.GetElapsedTime(_lastSampleTimestamp, now).TotalSeconds >= 1.0 && _oneSecondPoints.Count < 10_800)
            {
                _lastSampleTimestamp = now;
                _oneSecondPoints.Add((Stopwatch.GetElapsedTime(_startTimestamp, now).TotalSeconds, progress.TransferredBytes));
            }

            long q1Bytes = _totalBytes / 4;
            long q3Bytes = (_totalBytes * 3) / 4;

            if (_firstQuarterEndTimestamp == 0 && progress.TransferredBytes >= q1Bytes)
            {
                _firstQuarterEndTimestamp = now;
                _firstQuarterEndBytes = progress.TransferredBytes;
            }
            if (_finalQuarterStartTimestamp == 0 && progress.TransferredBytes >= q3Bytes)
            {
                _finalQuarterStartTimestamp = now;
                _finalQuarterStartBytes = progress.TransferredBytes;
            }
            if (_totalBytes >= Slice50GbThreshold)
            {
                if (_slice45GbTimestamp == 0 && progress.TransferredBytes >= Slice45GbThreshold)
                {
                    _slice45GbTimestamp = now;
                    _slice45GbBytes = progress.TransferredBytes;
                }
                if (_slice50GbTimestamp == 0 && progress.TransferredBytes >= Slice50GbThreshold)
                {
                    _slice50GbTimestamp = now;
                    _slice50GbBytes = progress.TransferredBytes;
                }
            }
            if (progress.TransferredBytes >= _totalBytes)
            {
                _finalQuarterEndTimestamp = now;
                _finalQuarterEndBytes = progress.TransferredBytes;
                if (_oneSecondPoints.Count < 10_800 &&
                    (_oneSecondPoints.Count == 0 || _oneSecondPoints[^1].Bytes < progress.TransferredBytes))
                {
                    _oneSecondPoints.Add((Stopwatch.GetElapsedTime(_startTimestamp, now).TotalSeconds, progress.TransferredBytes));
                }
            }

            if (_uiContext != null)
            {
                if (_useBlockingUiInvoke)
                {
                    _uiContext.Send(_ => _rateCalculator.Update(progress.TransferredBytes, progress.TotalBytes), null);
                }
                else
                {
                    _uiContext.Post(_ => _rateCalculator.Update(progress.TransferredBytes, progress.TotalBytes), null);
                }
            }
        }
    }

    private static async Task<int> RunStorageBaselineAsync(string[] args)
    {
        string targetDir = Path.GetTempPath();
        long sizeDecimalMB = 256;
        int bufferKiB = 1024;
        int repetitions = 1;
        string? outputJson = null;
        string? r3ReportPath = null;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--target-dir" when i + 1 < args.Length: targetDir = args[++i]; break;
                case "--size-mb" when i + 1 < args.Length: sizeDecimalMB = long.Parse(args[++i]); break;
                case "--buffer-kb" when i + 1 < args.Length: bufferKiB = int.Parse(args[++i]); break;
                case "--repetitions" when i + 1 < args.Length: repetitions = Math.Clamp(int.Parse(args[++i]), 1, 50); break;
                case "--output-json" when i + 1 < args.Length: outputJson = args[++i]; break;
                case "--r3-report" when i + 1 < args.Length: r3ReportPath = args[++i]; break;
            }
        }

        if (sizeDecimalMB <= 0 || sizeDecimalMB > 20_000)
        {
            throw new ArgumentException("Invalid --size-mb for storage-baseline (1..20000).");
        }

        targetDir = Path.GetFullPath(targetDir);
        Directory.CreateDirectory(targetDir);
        long totalBytes = sizeDecimalMB * 1_000_000L;
        int bufferSize = Math.Clamp(bufferKiB * 1024, 4096, 16 * 1024 * 1024);
        byte[] writeBuf = new byte[bufferSize];
        byte[] readBuf = new byte[bufferSize];

        var repResults = new JsonArray();
        R3DiagnosticReport? lastR3Report = null;

        for (int rep = 1; rep <= repetitions; rep++)
        {
            string testFile = Path.Combine(targetDir, $"glide-storage-baseline-{Guid.NewGuid():N}.bin");
            try
            {
                using var diagSink = new BoundedTransferDiagnosticSink();
                using IDesktopTransferAttemptDiagnostics attemptDiag = diagSink.BeginAttempt(DesktopTransferDirection.Receive, totalBytes);
                attemptDiag.SetAttemptMetadata(new DesktopAttemptMetadata(
                    PairingMode: "benchmark-harness",
                    StagingLocation: targetDir,
                    DestinationLocation: targetDir,
                    CommitStrategy: "StorageBaselineDirect",
                    DatasetId: $"storage-{sizeDecimalMB}mb-incompressible",
                    ContentProfile: "incompressible-prng",
                    ItemCount: 1,
                    TransportName: "local-storage"));

                string expectedSha;
                long writeStart = Stopwatch.GetTimestamp();
                long writeEnd;
                long fsyncStart;
                long fsyncEnd;
                using (var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                await using (var writeStream = new FileStream(
                    testFile,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.None,
                    bufferSize: bufferSize,
                    FileOptions.SequentialScan))
                {
                    ulong prngState = 0x9E3779B97F4A7C15UL ^ (ulong)rep;
                    long remaining = totalBytes;
                    while (remaining > 0)
                    {
                        int count = (int)Math.Min(writeBuf.Length, remaining);
                        FillSplitMix64(writeBuf.AsSpan(0, count), ref prngState);
                        await writeStream.WriteAsync(writeBuf.AsMemory(0, count));
                        hasher.AppendData(writeBuf, 0, count);
                        remaining -= count;
                    }
                    writeEnd = Stopwatch.GetTimestamp();
                    fsyncStart = Stopwatch.GetTimestamp();
                    writeStream.Flush(flushToDisk: true);
                    fsyncEnd = Stopwatch.GetTimestamp();
                    expectedSha = Convert.ToHexStringLower(hasher.GetHashAndReset());
                }

                double writeMs = Stopwatch.GetElapsedTime(writeStart, writeEnd).TotalMilliseconds;
                double fsyncMs = Stopwatch.GetElapsedTime(fsyncStart, fsyncEnd).TotalMilliseconds;
                double writePlusFsyncMs = Stopwatch.GetElapsedTime(writeStart, fsyncEnd).TotalMilliseconds;

                // Sequential read + SHA-256
                attemptDiag.OnPhaseChanged(DesktopTransferPhase.Verifying);
                long readStart = Stopwatch.GetTimestamp();
                long totalRead = 0;
                await using (var readStream = new FileStream(
                    testFile,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    bufferSize: bufferSize,
                    FileOptions.SequentialScan))
                {
                    int r;
                    while ((r = await readStream.ReadAsync(readBuf)) > 0)
                    {
                        totalRead += r;
                    }
                }
                long readEnd = Stopwatch.GetTimestamp();

                long shaStart = Stopwatch.GetTimestamp();
                string actualSha;
                using (var shaHasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                await using (var shaStream = new FileStream(
                    testFile,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    bufferSize: bufferSize,
                    FileOptions.SequentialScan))
                {
                    int r;
                    while ((r = await shaStream.ReadAsync(readBuf)) > 0)
                    {
                        shaHasher.AppendData(readBuf, 0, r);
                    }
                    actualSha = Convert.ToHexStringLower(shaHasher.GetHashAndReset());
                }
                long shaEnd = Stopwatch.GetTimestamp();

                double readMs = Stopwatch.GetElapsedTime(readStart, readEnd).TotalMilliseconds;
                double shaMs = Stopwatch.GetElapsedTime(shaStart, shaEnd).TotalMilliseconds;
                bool verified = totalRead == totalBytes && string.Equals(actualSha, expectedSha, StringComparison.Ordinal);

                double seqWriteMBps = writeMs > 0.1 ? (totalBytes / (writeMs / 1000.0)) / 1_000_000.0 : 0.0;
                double writeFsyncMBps = writePlusFsyncMs > 0.1 ? (totalBytes / (writePlusFsyncMs / 1000.0)) / 1_000_000.0 : 0.0;
                double seqReadMBps = readMs > 0.1 ? (totalBytes / (readMs / 1000.0)) / 1_000_000.0 : 0.0;
                double streamShaMBps = shaMs > 0.1 ? (totalBytes / (shaMs / 1000.0)) / 1_000_000.0 : 0.0;

                attemptDiag.AddStagingWrite(totalBytes, writeEnd - writeStart);
                attemptDiag.AddCommit(fsyncEnd - fsyncStart);
                attemptDiag.AddVerificationRead(totalBytes, shaEnd - shaStart);
                attemptDiag.OnItemVerified(totalBytes);
                var transferRes = verified
                    ? DesktopTransferResult.Completed("Storage baseline verified.", 1, totalBytes)
                    : DesktopTransferResult.Failed("Storage baseline verification mismatch.");
                attemptDiag.Complete(transferRes);

                lastR3Report = attemptDiag.BuildR3Report(transferRes, new DesktopR3ReportOptions(
                    BenchmarkPath: "storage-baseline",
                    BenchmarkMode: "current",
                    RunType: rep == 1 ? "cold" : "warm",
                    RepetitionIndex: rep,
                    RepetitionTotal: repetitions,
                    UiState: "headless-driver",
                    DatasetSha256Override: expectedSha,
                    Sha256VerifiedOverride: verified,
                    QuarantineCleanAfterTerminalOverride: true));
                lastR3Report = lastR3Report with
                {
                    Throughput = lastR3Report.Throughput with
                    {
                        AveragePayloadRateMBps = Math.Round(writeFsyncMBps, 2),
                        AcceptanceToVerifiedRateMBps = Math.Round(streamShaMBps, 2)
                    },
                    PhaseDurationsMs = lastR3Report.PhaseDurationsMs with
                    {
                        SavingMs = Math.Round(fsyncMs, 2),
                        VerificationMs = Math.Round(shaMs, 2)
                    }
                };

                repResults.Add(new JsonObject
                {
                    ["repetitionIndex"] = rep,
                    ["sizeBytes"] = totalBytes,
                    ["bufferKiB"] = bufferKiB,
                    ["writeDurationMs"] = Math.Round(writeMs, 2),
                    ["fsyncFlushMs"] = Math.Round(fsyncMs, 2),
                    ["writePlusFsyncMs"] = Math.Round(writePlusFsyncMs, 2),
                    ["readDurationMs"] = Math.Round(readMs, 2),
                    ["sha256DurationMs"] = Math.Round(shaMs, 2),
                    ["sequentialWriteMBps"] = Math.Round(seqWriteMBps, 2),
                    ["writePlusFsyncMBps"] = Math.Round(writeFsyncMBps, 2),
                    ["sequentialReadMBps"] = Math.Round(seqReadMBps, 2),
                    ["streamingSha256MBps"] = Math.Round(streamShaMBps, 2),
                    ["sha256"] = expectedSha,
                    ["verified"] = verified
                });
            }
            finally
            {
                try { if (File.Exists(testFile)) File.Delete(testFile); } catch { }
            }
        }

        if (!string.IsNullOrWhiteSpace(r3ReportPath) && lastR3Report != null)
        {
            await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(r3ReportPath, lastR3Report);
        }

        var report = new JsonObject
        {
            ["event"] = "storage_baseline",
            ["timestampUtc"] = DateTimeOffset.UtcNow.ToString("O"),
            ["targetDir"] = BoundedTransferDiagnosticSink.SanitizePathForDiagnostics(targetDir),
            ["sizeDecimalMB"] = sizeDecimalMB,
            ["totalBytes"] = totalBytes,
            ["repetitions"] = repetitions,
            ["results"] = repResults
        };

        string formatted = report.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
        if (!string.IsNullOrWhiteSpace(outputJson))
        {
            string fullOut = Path.GetFullPath(outputJson);
            Directory.CreateDirectory(Path.GetDirectoryName(fullOut)!);
            await File.WriteAllTextAsync(fullOut, formatted);
        }

        Console.WriteLine(report.ToJsonString());
        return 0;
    }

    private static async Task<int> RunBenchmarkAsync(string[] args)
    {
        long sizeDecimalMB = 256;
        string mode = "current";
        string selectedPath = "all";
        string selectedRun = "both";
        string stageName = "unspecified";
        string? customBenchRoot = null;
        string? corpusPath = null;
        string dataset = "single";
        bool incompressible = true;
        int repetitions = 1;
        int? timeoutSecondsOverride = null;
        string? outputJson = null;
        string? outputLog = null;
        string? r3ReportPath = null;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--size-mb" when i + 1 < args.Length: sizeDecimalMB = long.Parse(args[++i]); break;
                case "--mode" when i + 1 < args.Length: mode = args[++i].ToLowerInvariant(); break;
                case "--path" when i + 1 < args.Length: selectedPath = args[++i].ToLowerInvariant(); break;
                case "--run" when i + 1 < args.Length: selectedRun = args[++i].ToLowerInvariant(); break;
                case "--stage" when i + 1 < args.Length: stageName = args[++i]; break;
                case "--bench-root" when i + 1 < args.Length: customBenchRoot = args[++i]; break;
                case "--corpus-path" when i + 1 < args.Length: corpusPath = args[++i]; break;
                case "--dataset" when i + 1 < args.Length: dataset = args[++i].ToLowerInvariant(); break;
                case "--incompressible": incompressible = true; break;
                case "--repetitions" when i + 1 < args.Length: repetitions = Math.Clamp(int.Parse(args[++i]), 1, 50); break;
                case "--timeout-seconds" when i + 1 < args.Length: timeoutSecondsOverride = Math.Max(10, int.Parse(args[++i])); break;
                case "--output-json" when i + 1 < args.Length: outputJson = args[++i]; break;
                case "--output-log" when i + 1 < args.Length: outputLog = args[++i]; break;
                case "--r3-report" when i + 1 < args.Length: r3ReportPath = args[++i]; break;
            }
        }
        _ = incompressible;

        if (sizeDecimalMB <= 0 || sizeDecimalMB > 20_000 ||
            selectedPath is not ("all" or "1" or "2" or "3" or "4") ||
            selectedRun is not ("both" or "cold" or "warm") ||
            dataset is not ("single" or "mixed" or "small-1000"))
        {
            throw new ArgumentException("Invalid benchmark size, path, dataset, or run selection.");
        }

        string benchBase = string.IsNullOrWhiteSpace(customBenchRoot) ? Path.GetTempPath() : Path.GetFullPath(customBenchRoot);
        string benchRoot = Path.Combine(benchBase, "glide-bench-" + Guid.NewGuid().ToString("N"));
        string sourceDir = Path.Combine(benchRoot, "src");
        string stagingDir = Path.Combine(benchRoot, "staging");
        string destDir = Path.Combine(benchRoot, "dest");
        Directory.CreateDirectory(sourceDir);
        Directory.CreateDirectory(stagingDir);
        Directory.CreateDirectory(destDir);

        var sourceFiles = new List<DesktopTransferFile>();
        var expectedHashes = new Dictionary<string, (long Size, string Sha256)>(StringComparer.Ordinal);
        long totalBytes = 0;
        string datasetId;
        string contentProfile;
        string primaryDatasetSha256 = "unavailable";
        string manifestSha256 = "unavailable";

        byte[] patternBuf = new byte[1024 * 1024];

        if (!string.IsNullOrWhiteSpace(corpusPath))
        {
            string fullCorpus = Path.GetFullPath(corpusPath);
            if (File.Exists(fullCorpus))
            {
                var fi = new FileInfo(fullCorpus);
                string rel = fi.Name;
                using var fs = File.OpenRead(fullCorpus);
                string sha = Convert.ToHexStringLower(SHA256.HashData(fs));
                sourceFiles.Add(new DesktopTransferFile(fullCorpus, rel));
                expectedHashes[rel] = (fi.Length, sha);
                totalBytes = fi.Length;
                datasetId = $"corpus-{fi.Name}";
                contentProfile = "incompressible-prng";
                primaryDatasetSha256 = sha;
            }
            else if (Directory.Exists(fullCorpus))
            {
                var manifestSb = new StringBuilder();
                foreach (string f in Directory.GetFiles(fullCorpus, "*", SearchOption.AllDirectories).OrderBy(x => x, StringComparer.Ordinal))
                {
                    if (Path.GetFileName(f) == "corpus-manifest.json") continue;
                    var fi = new FileInfo(f);
                    string rel = Path.GetRelativePath(fullCorpus, f).Replace('\\', '/');
                    using var fs = File.OpenRead(f);
                    string sha = Convert.ToHexStringLower(SHA256.HashData(fs));
                    sourceFiles.Add(new DesktopTransferFile(f, rel));
                    expectedHashes[rel] = (fi.Length, sha);
                    totalBytes += fi.Length;
                    manifestSb.Append(rel).Append(':').Append(fi.Length).Append(':').Append(sha).Append('\n');
                }
                manifestSha256 = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(manifestSb.ToString())));
                datasetId = $"corpus-dir-{sourceFiles.Count}-items";
                contentProfile = dataset == "single" ? "mixed-corpus" : dataset;
            }
            else
            {
                throw new FileNotFoundException($"--corpus-path not found: {fullCorpus}");
            }
        }
        else if (dataset == "mixed")
        {
            File.WriteAllBytes(Path.Combine(sourceDir, "00-empty.bin"), []);
            for (int n = 1; n <= 32; n++)
            {
                string p = Path.Combine(sourceDir, $"{n:D2}-small-{n:D2}.bin");
                WriteIncompressibleFile(p, 1024 + (n * 37L), (ulong)n, patternBuf);
            }
            WriteIncompressibleFile(Path.Combine(sourceDir, "90-medium-10mb.bin"), 10_000_000L, 90, patternBuf);
            WriteIncompressibleFile(Path.Combine(sourceDir, "99-large-100mb.bin"), 100_000_000L, 99, patternBuf);
            File.WriteAllText(Path.Combine(sourceDir, "Unicode_🚀_测试_файл.txt"), "Unicode content: 🌟✨", Encoding.UTF8);
            File.WriteAllText(Path.Combine(sourceDir, "Document With Multiple Spaces In Name.txt"), "Space content", Encoding.UTF8);
            string nested = Path.Combine(sourceDir, "folder_a", "subfolder_b");
            Directory.CreateDirectory(nested);
            File.WriteAllText(Path.Combine(nested, "nested_file.txt"), "Nested content", Encoding.UTF8);
            string nested2 = Path.Combine(sourceDir, "folder_c");
            Directory.CreateDirectory(nested2);
            File.WriteAllText(Path.Combine(nested2, "nested_file.txt"), "Duplicate basename", Encoding.UTF8);

            var manifestSb = new StringBuilder();
            foreach (string f in Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories).OrderBy(x => x, StringComparer.Ordinal))
            {
                var fi = new FileInfo(f);
                string rel = Path.GetRelativePath(sourceDir, f).Replace('\\', '/');
                using var fs = File.OpenRead(f);
                string sha = Convert.ToHexStringLower(SHA256.HashData(fs));
                sourceFiles.Add(new DesktopTransferFile(f, rel));
                expectedHashes[rel] = (fi.Length, sha);
                totalBytes += fi.Length;
                manifestSb.Append(rel).Append(':').Append(fi.Length).Append(':').Append(sha).Append('\n');
            }
            manifestSha256 = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(manifestSb.ToString())));
            datasetId = "mixed-folder";
            contentProfile = "mixed-corpus";
        }
        else if (dataset == "small-1000")
        {
            var manifestSb = new StringBuilder();
            for (int n = 1; n <= 1000; n++)
            {
                string rel = $"item-{n:D4}.bin";
                string p = Path.Combine(sourceDir, rel);
                long sz = 4096 + ((n % 60) * 1024L);
                string sha = WriteIncompressibleFile(p, sz, (ulong)(1000 + n), patternBuf);
                sourceFiles.Add(new DesktopTransferFile(p, rel));
                expectedHashes[rel] = (sz, sha);
                totalBytes += sz;
                manifestSb.Append(rel).Append(':').Append(sz).Append(':').Append(sha).Append('\n');
            }
            manifestSha256 = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(manifestSb.ToString())));
            datasetId = "small-1000";
            contentProfile = "small-files";
        }
        else
        {
            totalBytes = sizeDecimalMB * 1_000_000L;
            string sourceFile = Path.Combine(sourceDir, $"benchmark-{sizeDecimalMB}mb.bin");
            primaryDatasetSha256 = WriteIncompressibleFile(sourceFile, totalBytes, 42UL, patternBuf);
            string rel = Path.GetFileName(sourceFile);
            sourceFiles.Add(new DesktopTransferFile(sourceFile, rel));
            expectedHashes[rel] = (totalBytes, primaryDatasetSha256);
            datasetId = $"single-{sizeDecimalMB}mb-incompressible";
            contentProfile = "incompressible-prng";
        }

        int effectiveTimeoutSeconds = timeoutSecondsOverride ?? Math.Max(180, (int)(totalBytes / 2_000_000L) + (sourceFiles.Count / 2) + 180);
        string diagLogFile = outputLog ?? Path.Combine(benchRoot, "diagnostics.jsonl");
        await using var diagSink = new BoundedTransferDiagnosticSink(diagLogFile);

        bool isLegacy = string.Equals(mode, "legacy", StringComparison.OrdinalIgnoreCase);
        DesktopTransferIoOptions baseOptions = isLegacy
            ? DesktopTransferIoOptions.LegacyBaseline
            : DesktopTransferIoOptions.Default;

        var resultsArray = new JsonArray();
        R3DiagnosticReport? lastR3Report = null;
        string? r3Dir = !string.IsNullOrWhiteSpace(r3ReportPath) ? Path.GetDirectoryName(Path.GetFullPath(r3ReportPath)) : null;
        string? r3Stem = !string.IsNullOrWhiteSpace(r3ReportPath) ? Path.GetFileNameWithoutExtension(r3ReportPath) : null;

        async Task RecordAttemptReportAsync(string pathName, string runType, int rep, R3DiagnosticReport? report)
        {
            if (report == null) return;
            lastR3Report = report;
            if (!string.IsNullOrWhiteSpace(r3ReportPath) && !string.IsNullOrWhiteSpace(r3Dir) && !string.IsNullOrWhiteSpace(r3Stem))
            {
                string perRepPath = Path.Combine(r3Dir, $"{r3Stem}-{pathName}-{runType}-rep{rep}.json");
                await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(perRepPath, report);
            }
        }

        try
        {
            var runTypes = selectedRun switch
            {
                "both" => new[] { "cold", "warm" },
                _ => new[] { selectedRun }
            };

            for (int rep = 1; rep <= repetitions; rep++)
            {
                foreach (string runType in runTypes)
                {
                    if (selectedPath is "all" or "1")
                    {
                        var (p1Json, p1R3) = await RunPath1RawTlsBaselineAsync(
                            totalBytes,
                            runType,
                            rep,
                            repetitions,
                            effectiveTimeoutSeconds,
                            datasetId,
                            contentProfile,
                            primaryDatasetSha256,
                            manifestSha256);
                        resultsArray.Add(p1Json);
                        await RecordAttemptReportAsync("1_RawTlsBaseline", runType, rep, p1R3);
                    }

                    if (selectedPath is "all" or "2")
                    {
                        var (p2Json, p2R3) = await RunGlideBenchmarkPathAsync(
                            pathName: "2_GlideTlsAndFramingNoStorage",
                            runType: runType,
                            repetitionIndex: rep,
                            repetitionTotal: repetitions,
                            timeoutSeconds: effectiveTimeoutSeconds,
                            sourceFiles: sourceFiles,
                            expectedHashes: expectedHashes,
                            totalBytes: totalBytes,
                            datasetId: datasetId,
                            contentProfile: contentProfile,
                            datasetSha256: primaryDatasetSha256,
                            manifestSha256: manifestSha256,
                            stagingDir: stagingDir,
                            destDir: destDir,
                            diagSink: diagSink,
                            ioOptions: baseOptions with { DiscardStorageForBenchmark = true },
                            mode: mode,
                            useUiRuntime: false,
                            useLegacyBlockingUi: false);
                        resultsArray.Add(p2Json);
                        await RecordAttemptReportAsync("2_GlideTlsAndFramingNoStorage", runType, rep, p2R3);
                    }

                    if (selectedPath is "all" or "3")
                    {
                        var (p3Json, p3R3) = await RunGlideBenchmarkPathAsync(
                            pathName: "3_GlideCompleteBackend",
                            runType: runType,
                            repetitionIndex: rep,
                            repetitionTotal: repetitions,
                            timeoutSeconds: effectiveTimeoutSeconds,
                            sourceFiles: sourceFiles,
                            expectedHashes: expectedHashes,
                            totalBytes: totalBytes,
                            datasetId: datasetId,
                            contentProfile: contentProfile,
                            datasetSha256: primaryDatasetSha256,
                            manifestSha256: manifestSha256,
                            stagingDir: stagingDir,
                            destDir: destDir,
                            diagSink: diagSink,
                            ioOptions: baseOptions with { DiscardStorageForBenchmark = false },
                            mode: mode,
                            useUiRuntime: false,
                            useLegacyBlockingUi: false);
                        resultsArray.Add(p3Json);
                        await RecordAttemptReportAsync("3_GlideCompleteBackend", runType, rep, p3R3);
                    }

                    if (selectedPath is "all" or "4")
                    {
                        var (p4Json, p4R3) = await RunGlideBenchmarkPathAsync(
                            pathName: "4_GlideUiRuntimeCallbacks",
                            runType: runType,
                            repetitionIndex: rep,
                            repetitionTotal: repetitions,
                            timeoutSeconds: effectiveTimeoutSeconds,
                            sourceFiles: sourceFiles,
                            expectedHashes: expectedHashes,
                            totalBytes: totalBytes,
                            datasetId: datasetId,
                            contentProfile: contentProfile,
                            datasetSha256: primaryDatasetSha256,
                            manifestSha256: manifestSha256,
                            stagingDir: stagingDir,
                            destDir: destDir,
                            diagSink: diagSink,
                            ioOptions: baseOptions with { DiscardStorageForBenchmark = false },
                            mode: mode,
                            useUiRuntime: true,
                            useLegacyBlockingUi: isLegacy);
                        resultsArray.Add(p4Json);
                        await RecordAttemptReportAsync("4_GlideUiRuntimeCallbacks", runType, rep, p4R3);
                    }
                }
            }

            if (!string.IsNullOrWhiteSpace(r3ReportPath) && lastR3Report != null)
            {
                await BoundedTransferDiagnosticSink.WriteR3ReportFileAsync(r3ReportPath, lastR3Report);
            }

            var summaryReport = new JsonObject
            {
                ["event"] = "benchmark_complete",
                ["timestampUtc"] = DateTimeOffset.UtcNow.ToString("O"),
                ["buildCommit"] = BoundedTransferDiagnosticSink.ResolveBuildCommit(),
                ["stage"] = stageName,
                ["mode"] = mode,
                ["dataset"] = dataset,
                ["datasetId"] = datasetId,
                ["incompressible"] = true,
                ["selectedPath"] = selectedPath,
                ["selectedRun"] = selectedRun,
                ["repetitions"] = repetitions,
                ["timeoutSeconds"] = effectiveTimeoutSeconds,
                ["sizeDecimalMB"] = sizeDecimalMB,
                ["totalBytes"] = totalBytes,
                ["itemCount"] = sourceFiles.Count,
                ["expectedSha256"] = primaryDatasetSha256,
                ["manifestSha256"] = manifestSha256,
                ["results"] = resultsArray
            };

            string jsonText = summaryReport.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
            if (!string.IsNullOrWhiteSpace(outputJson))
            {
                string fullOut = Path.GetFullPath(outputJson);
                Directory.CreateDirectory(Path.GetDirectoryName(fullOut)!);
                await File.WriteAllTextAsync(fullOut, jsonText);
            }

            Console.WriteLine(summaryReport.ToJsonString());
            return 0;
        }
        finally
        {
            try
            {
                if (Directory.Exists(benchRoot))
                {
                    Directory.Delete(benchRoot, recursive: true);
                }
            }
            catch { }
        }
    }

    private static async Task<(JsonObject Summary, R3DiagnosticReport R3Report)> RunPath1RawTlsBaselineAsync(
        long totalBytes,
        string runType,
        int repetitionIndex,
        int repetitionTotal,
        int timeoutSeconds,
        string datasetId,
        string contentProfile,
        string datasetSha256,
        string manifestSha256)
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();

        long allocBefore = GC.GetTotalAllocatedBytes(precise: false);
        TimeSpan gcPauseBefore = GC.GetTotalPauseDuration();
        long startTick = Stopwatch.GetTimestamp();

        using var diagSink = new BoundedTransferDiagnosticSink();
        using IDesktopTransferAttemptDiagnostics attemptDiag = diagSink.BeginAttempt(DesktopTransferDirection.Send, totalBytes);
        attemptDiag.SetAttemptMetadata(new DesktopAttemptMetadata(
            PairingMode: "benchmark-harness",
            LocalAddress: "127.0.0.1",
            PeerAddress: "127.0.0.1",
            ItemCount: 1,
            DatasetId: datasetId,
            ContentProfile: contentProfile,
            DatasetSha256: datasetSha256,
            ManifestSha256: manifestSha256,
            TransportName: "loopback"));

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));

        // 1a. Raw unencrypted TCP loopback baseline
        using var tcpListener = new TcpListener(IPAddress.Loopback, 0);
        tcpListener.Start();
        int tcpPort = ((IPEndPoint)tcpListener.LocalEndpoint).Port;
        Task<long> rawTcpReceiver = Task.Run(async () =>
        {
            using TcpClient srv = await tcpListener.AcceptTcpClientAsync(cts.Token).ConfigureAwait(false);
            using NetworkStream ns = srv.GetStream();
            byte[] buf = new byte[256 * 1024];
            long recvd = 0;
            while (recvd < totalBytes)
            {
                int r = await ns.ReadAsync(buf, cts.Token).ConfigureAwait(false);
                if (r <= 0) break;
                recvd += r;
            }
            return recvd;
        }, cts.Token);

        double rawTcpRateMBps = 0.0;
        using (var tcpClient = new TcpClient())
        {
            await tcpClient.ConnectAsync(IPAddress.Loopback, tcpPort, cts.Token).ConfigureAwait(false);
            using NetworkStream ns = tcpClient.GetStream();
            byte[] sendBuf = new byte[256 * 1024];
            ulong sState = 0x123456789ABCDEF0UL;
            FillSplitMix64(sendBuf, ref sState);
            long tcpStart = Stopwatch.GetTimestamp();
            long rem = totalBytes;
            while (rem > 0)
            {
                int toWrite = (int)Math.Min(sendBuf.Length, rem);
                await ns.WriteAsync(sendBuf.AsMemory(0, toWrite), cts.Token).ConfigureAwait(false);
                rem -= toWrite;
            }
            await ns.FlushAsync(cts.Token).ConfigureAwait(false);
            long tcpRecvd = await rawTcpReceiver.ConfigureAwait(false);
            double tcpMs = Stopwatch.GetElapsedTime(tcpStart, Stopwatch.GetTimestamp()).TotalMilliseconds;
            rawTcpRateMBps = tcpMs > 0.1 ? (tcpRecvd / (tcpMs / 1000.0)) / 1_000_000.0 : 0.0;
        }
        tcpListener.Stop();

        // 1b. TLS 1.3 socket baseline with 1s / 5s / steady-quarter / 4.5-5 GB tracking
        using var listener = new TlsSocketListener(0);
        int port = listener.BoundPort;
        string spki = listener.LocalSpkiSha256;

        Task<long> receiverTask = Task.Run(async () =>
        {
            await using TlsSocketConnection serverConn = await listener.AcceptConnectionAsync(cts.Token).ConfigureAwait(false);
            byte[] recvBuf = new byte[256 * 1024];
            long received = 0;
            while (received < totalBytes)
            {
                int r = await serverConn.TransportStream.ReadAsync(recvBuf, cts.Token).ConfigureAwait(false);
                if (r <= 0) break;
                received += r;
            }
            return received;
        }, cts.Token);

        var client = new TlsSocketClient();
        long connectStartTick = Stopwatch.GetTimestamp();
        await using TlsSocketConnection clientConn = await client.ConnectAsync("127.0.0.1", port, spki, cts.Token).ConfigureAwait(false);
        long payloadStartTick = Stopwatch.GetTimestamp();
        attemptDiag.RecordConnectionDurationTicks(payloadStartTick - connectStartTick);
        attemptDiag.OnPhaseChanged(DesktopTransferPhase.Transferring);

        var points = new List<(double ElapsedSec, long Bytes)> { (0.0, 0L) };
        long lastSampleTick = payloadStartTick;
        long slice45Tick = 0;
        long slice45Bytes = 0;
        long slice50Tick = 0;
        long slice50Bytes = 0;

        byte[] tlsBuf = new byte[256 * 1024];
        ulong tState = 0xCAFEBABE12345678UL;
        FillSplitMix64(tlsBuf, ref tState);
        long remaining = totalBytes;
        long sent = 0;
        while (remaining > 0)
        {
            int toWrite = (int)Math.Min(tlsBuf.Length, remaining);
            long wStart = Stopwatch.GetTimestamp();
            await clientConn.TransportStream.WriteAsync(tlsBuf.AsMemory(0, toWrite), cts.Token).ConfigureAwait(false);
            long wEnd = Stopwatch.GetTimestamp();
            attemptDiag.AddTlsWrite(toWrite, wEnd - wStart);
            sent += toWrite;
            remaining -= toWrite;

            if (totalBytes >= 5_000_000_000L)
            {
                if (slice45Tick == 0 && sent >= 4_500_000_000L)
                {
                    slice45Tick = wEnd;
                    slice45Bytes = sent;
                }
                if (slice50Tick == 0 && sent >= 5_000_000_000L)
                {
                    slice50Tick = wEnd;
                    slice50Bytes = sent;
                }
            }

            if (Stopwatch.GetElapsedTime(lastSampleTick, wEnd).TotalSeconds >= 1.0 && points.Count < 10_800)
            {
                lastSampleTick = wEnd;
                points.Add((Stopwatch.GetElapsedTime(payloadStartTick, wEnd).TotalSeconds, sent));
            }
        }
        await clientConn.TransportStream.FlushAsync(cts.Token).ConfigureAwait(false);

        long totalReceived = await receiverTask.ConfigureAwait(false);
        long endTick = Stopwatch.GetTimestamp();
        points.Add((Stopwatch.GetElapsedTime(payloadStartTick, endTick).TotalSeconds, totalReceived));

        var transferResult = totalReceived == totalBytes
            ? DesktopTransferResult.Completed("Raw TLS baseline complete.", 1, totalReceived)
            : DesktopTransferResult.Failed("Raw TLS baseline byte count mismatch.");
        attemptDiag.OnItemVerified(totalReceived);
        attemptDiag.Complete(transferResult);

        double prepMs = Stopwatch.GetElapsedTime(startTick, payloadStartTick).TotalMilliseconds;
        double payloadMs = Stopwatch.GetElapsedTime(payloadStartTick, endTick).TotalMilliseconds;
        double rateMBps = payloadMs > 0.1 ? (totalReceived / (payloadMs / 1000.0)) / 1_000_000.0 : 0.0;
        double allocMB = (GC.GetTotalAllocatedBytes(precise: false) - allocBefore) / 1_000_000.0;
        double gcPauseMs = (GC.GetTotalPauseDuration() - gcPauseBefore).TotalMilliseconds;

        var oneSecRates = new List<double>();
        for (int i = 1; i < points.Count; i++)
        {
            double dt = points[i].ElapsedSec - points[i - 1].ElapsedSec;
            long db = Math.Max(0L, points[i].Bytes - points[i - 1].Bytes);
            if (dt >= 0.25) oneSecRates.Add(Math.Round((db / dt) / 1_000_000.0, 2));
        }
        IReadOnlyList<R3FiveSecondRateWindow> fiveSecWindows = BoundedTransferDiagnosticSink.ComputeFiveSecondRateWindows(points);
        var (q1, q4, qRatio) = BoundedTransferDiagnosticSink.ComputeSteadyQuarterRates(points, totalBytes);

        JsonObject? sliceObj = null;
        if (totalBytes >= 5_000_000_000L && slice45Tick > 0 && slice50Tick > slice45Tick)
        {
            double sliceMs = Stopwatch.GetElapsedTime(slice45Tick, slice50Tick).TotalMilliseconds;
            double sliceRate = sliceMs > 0.1 ? ((slice50Bytes - slice45Bytes) / (sliceMs / 1000.0)) / 1_000_000.0 : 0.0;
            sliceObj = new JsonObject
            {
                ["startBytes"] = slice45Bytes,
                ["endBytes"] = slice50Bytes,
                ["durationMs"] = Math.Round(sliceMs, 2),
                ["rateMBps"] = Math.Round(sliceRate, 2),
                ["ratioToFirstQuarter"] = q1.IsAvailable && q1.Value > 0.1 ? Math.Round(sliceRate / q1.Value, 3) : null
            };
        }

        R3DiagnosticReport r3Report = attemptDiag.BuildR3Report(transferResult, new DesktopR3ReportOptions(
            BenchmarkPath: "1_RawTlsBaseline",
            BenchmarkMode: "current",
            RunType: runType,
            RepetitionIndex: repetitionIndex,
            RepetitionTotal: repetitionTotal,
            UiState: "headless-driver",
            DatasetSha256Override: datasetSha256,
            ManifestSha256Override: manifestSha256,
            Sha256VerifiedOverride: totalReceived == totalBytes,
            QuarantineCleanAfterTerminalOverride: true));

        r3Report = r3Report with
        {
            Throughput = r3Report.Throughput with
            {
                AveragePayloadRateMBps = Math.Round(rateMBps, 2),
                AcceptanceToVerifiedRateMBps = Math.Round(rateMBps, 2),
                OneSecondRatesMBps = oneSecRates,
                FiveSecondRateWindowsMBps = fiveSecWindows,
                FirstSteadyQuarterRateMBps = q1,
                FinalSteadyQuarterRateMBps = q4,
                FinalToFirstQuarterRatio = qRatio
            }
        };

        var oneSecJson = new JsonArray();
        foreach (double r in oneSecRates) oneSecJson.Add(r);
        var fiveSecJson = new JsonArray();
        foreach (var w in fiveSecWindows)
        {
            fiveSecJson.Add(new JsonObject
            {
                ["windowIndex"] = w.WindowIndex,
                ["startElapsedSec"] = w.StartElapsedSec,
                ["endElapsedSec"] = w.EndElapsedSec,
                ["bytes"] = w.Bytes,
                ["rateMBps"] = w.RateMBps
            });
        }

        var summary = new JsonObject
        {
            ["path"] = "1_RawTlsBaseline",
            ["runType"] = runType,
            ["repetitionIndex"] = repetitionIndex,
            ["repetitionTotal"] = repetitionTotal,
            ["sizeBytes"] = totalReceived,
            ["preparationMs"] = Math.Round(prepMs, 2),
            ["payloadTransferMs"] = Math.Round(payloadMs, 2),
            ["acceptanceToVerifiedMs"] = Math.Round(payloadMs, 2),
            ["rawTcpRateMBps"] = Math.Round(rawTcpRateMBps, 2),
            ["payloadRateMBps"] = Math.Round(rateMBps, 2),
            ["acceptanceToVerifiedRateMBps"] = Math.Round(rateMBps, 2),
            ["oneSecondRatesMBps"] = oneSecJson,
            ["fiveSecondRateWindowsMBps"] = fiveSecJson,
            ["firstQuarterPayloadRateMBps"] = q1.IsAvailable ? q1.Value : null,
            ["finalQuarterPayloadRateMBps"] = q4.IsAvailable ? q4.Value : null,
            ["finalToFirstQuarterRatio"] = qRatio.IsAvailable ? qRatio.Value : null,
            ["slice4_5to5_0GB"] = sliceObj,
            ["totalAllocatedMB"] = Math.Round(allocMB, 2),
            ["gcPauseMs"] = Math.Round(gcPauseMs, 2),
            ["sha256Verified"] = totalReceived == totalBytes
        };

        return (summary, r3Report);
    }

    private static async Task<(JsonObject Summary, R3DiagnosticReport? R3Report)> RunGlideBenchmarkPathAsync(
        string pathName,
        string runType,
        int repetitionIndex,
        int repetitionTotal,
        int timeoutSeconds,
        IReadOnlyList<DesktopTransferFile> sourceFiles,
        IReadOnlyDictionary<string, (long Size, string Sha256)> expectedHashes,
        long totalBytes,
        string datasetId,
        string contentProfile,
        string datasetSha256,
        string manifestSha256,
        string stagingDir,
        string destDir,
        BoundedTransferDiagnosticSink diagSink,
        DesktopTransferIoOptions ioOptions,
        string mode,
        bool useUiRuntime,
        bool useLegacyBlockingUi)
    {
        foreach (string f in Directory.GetFiles(destDir, "*", SearchOption.AllDirectories))
        {
            File.Delete(f);
        }
        foreach (string f in Directory.GetFiles(stagingDir, "*", SearchOption.AllDirectories))
        {
            File.Delete(f);
        }

        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();

        long heapBefore = GC.GetTotalMemory(forceFullCollection: false);
        long allocBefore = GC.GetTotalAllocatedBytes(precise: false);
        TimeSpan gcPauseBefore = GC.GetTotalPauseDuration();

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        using var listener = new TlsSocketListener(0);
        var authority = new LanInvitationAuthority();
        LanInvitation invitation = CreateInvitation(listener, "127.0.0.1", authority, "Bench-Receiver");

        var store = new QuarantineStagingStore(stagingDir, destDir);
        var receiverEngine = new DesktopTransferEngine(diagSink, ioOptions);
        var senderEngine = new DesktopTransferEngine(diagSink, ioOptions);

        using var uiContext = useUiRuntime ? new SimulatedWpfSynchronizationContext() : null;
        var senderInteraction = new BenchmarkProgressTracker(totalBytes, uiContext, useLegacyBlockingUi);
        var receiverInteraction = new BenchmarkProgressTracker(totalBytes, uiContext: null, useBlockingUiInvoke: false);

        Task<DesktopTransferResult> receiverTask = Task.Run(async () =>
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync(cts.Token).ConfigureAwait(false);
            var recvReq = new DesktopReceiveRequest(
                StagingStore: store,
                InvitationAuthority: authority,
                LocalDeviceName: "Bench-Receiver");
            return await receiverEngine.ReceiveAsync(conn, recvReq, receiverInteraction, cts.Token).ConfigureAwait(false);
        }, cts.Token);

        var sendReq = new DesktopSendRequest(
            Host: "127.0.0.1",
            Port: listener.BoundPort,
            Files: sourceFiles,
            Invitation: invitation,
            LocalDeviceName: "Bench-Sender");

        DesktopTransferResult sendResult;
        if (useUiRuntime && uiContext != null)
        {
            var runtime = new DesktopTransferRuntime(senderEngine);
            var presentation = new DesktopTransferPresentation();
            runtime.SnapshotChanged += (_, s) =>
            {
                if (useLegacyBlockingUi)
                    uiContext.Send(_ => presentation.ApplySnapshot(s), null);
                else
                    uiContext.Post(_ => presentation.ApplySnapshot(s), null);
            };
            runtime.EventOccurred += (_, ev) =>
            {
                if (useLegacyBlockingUi)
                    uiContext.Send(_ => presentation.ApplyEvent(ev), null);
                else
                    uiContext.Post(_ => presentation.ApplyEvent(ev), null);
            };

            sendResult = await uiContext.InvokeOnUiThreadAsync(
                () => runtime.SendAsync(sendReq, senderInteraction, cts.Token));
        }
        else
        {
            sendResult = await senderEngine.SendAsync(sendReq, senderInteraction, cts.Token);
        }

        DesktopTransferResult recvResult = await receiverTask;

        long allocAfter = GC.GetTotalAllocatedBytes(precise: false);
        long heapAfter = GC.GetTotalMemory(forceFullCollection: false);
        TimeSpan gcPauseAfter = GC.GetTotalPauseDuration();

        bool shaVerified = false;
        if (ioOptions.DiscardStorageForBenchmark)
        {
            shaVerified = sendResult.Success && recvResult.Success && recvResult.VerifiedBytes == totalBytes;
        }
        else
        {
            int verifiedFileCount = 0;
            bool allMatch = sendResult.Success && recvResult.Success && recvResult.VerifiedCount == expectedHashes.Count;
            if (allMatch)
            {
                foreach ((string rel, (long expSize, string expSha)) in expectedHashes)
                {
                    string committedFile = Path.Combine(destDir, rel.Replace('/', Path.DirectorySeparatorChar));
                    if (!File.Exists(committedFile))
                    {
                        allMatch = false;
                        break;
                    }
                    var committedInfo = new FileInfo(committedFile);
                    if (committedInfo.Length != expSize)
                    {
                        allMatch = false;
                        break;
                    }
                    using var fs = File.OpenRead(committedFile);
                    string actualSha = Convert.ToHexStringLower(SHA256.HashData(fs));
                    if (!string.Equals(actualSha, expSha, StringComparison.OrdinalIgnoreCase))
                    {
                        allMatch = false;
                        break;
                    }
                    verifiedFileCount++;
                }
            }
            shaVerified = allMatch && verifiedFileCount == expectedHashes.Count;
        }

        bool quarantineClean = IsQuarantineEmpty(stagingDir);

        DesktopTransferDiagnosticSample? sendDiag = senderEngine.LastDiagnosticSample;
        DesktopTransferDiagnosticSample? recvDiag = receiverEngine.LastDiagnosticSample;

        double payloadMs = Math.Max(sendDiag?.PayloadTransferMs ?? 0, recvDiag?.PayloadTransferMs ?? 0);
        double acceptToVerifiedMs = Math.Max(sendDiag?.AcceptanceToVerifiedMs ?? 0, recvDiag?.AcceptanceToVerifiedMs ?? 0);
        double payloadRateMBps = payloadMs > 0.1 ? (totalBytes / (payloadMs / 1000.0)) / 1_000_000.0 : 0.0;
        double acceptToVerifiedRateMBps = acceptToVerifiedMs > 0.1 ? (totalBytes / (acceptToVerifiedMs / 1000.0)) / 1_000_000.0 : 0.0;

        IReadOnlyList<double> oneSecRates = senderInteraction.ComputeOneSecondRatesMBps();
        IReadOnlyList<R3FiveSecondRateWindow> fiveSecWindows = BoundedTransferDiagnosticSink.ComputeFiveSecondRateWindows(senderInteraction.ProgressPoints);
        double? firstQ = senderInteraction.FirstQuarterMBps;
        double? finalQ = senderInteraction.FinalQuarterMBps;
        double? qRatio = firstQ > 0.1 && finalQ.HasValue
            ? Math.Round(finalQ.Value / firstQ.Value, 3)
            : null;
        JsonObject? sliceObj = senderInteraction.BuildSlice4_5To5_0GbJson();

        R3DiagnosticReport? baseR3 = receiverEngine.LastR3DiagnosticReport ?? senderEngine.LastR3DiagnosticReport;
        if (baseR3 != null)
        {
            baseR3 = baseR3 with
            {
                Dataset = baseR3.Dataset with
                {
                    DatasetId = datasetId,
                    ContentProfile = contentProfile,
                    ItemCount = sourceFiles.Count,
                    DeclaredTotalBytes = totalBytes,
                    DatasetSha256 = MetricValue<string>.Available(datasetSha256),
                    ManifestSha256 = MetricValue<string>.Available(manifestSha256)
                },
                TestConfiguration = baseR3.TestConfiguration with
                {
                    BenchmarkPath = MetricValue<string>.Available(pathName),
                    BenchmarkMode = mode,
                    RunType = runType,
                    RepetitionIndex = repetitionIndex,
                    RepetitionTotal = repetitionTotal,
                    UiState = useUiRuntime ? "simulated-wpf-dispatcher" : "headless-driver"
                },
                Outcome = baseR3.Outcome with
                {
                    Sha256Verified = shaVerified,
                    QuarantineCleanAfterTerminal = MetricValue<bool>.Available(quarantineClean)
                },
                Throughput = baseR3.Throughput with
                {
                    OneSecondRatesMBps = oneSecRates.Count > 0 ? oneSecRates : baseR3.Throughput.OneSecondRatesMBps,
                    FiveSecondRateWindowsMBps = fiveSecWindows.Count > 0 ? fiveSecWindows : baseR3.Throughput.FiveSecondRateWindowsMBps,
                    FirstSteadyQuarterRateMBps = firstQ.HasValue ? MetricValue<double>.Available(firstQ.Value) : baseR3.Throughput.FirstSteadyQuarterRateMBps,
                    FinalSteadyQuarterRateMBps = finalQ.HasValue ? MetricValue<double>.Available(finalQ.Value) : baseR3.Throughput.FinalSteadyQuarterRateMBps,
                    FinalToFirstQuarterRatio = qRatio.HasValue ? MetricValue<double>.Available(qRatio.Value) : baseR3.Throughput.FinalToFirstQuarterRatio
                }
            };
        }

        var oneSecJson = new JsonArray();
        foreach (double r in oneSecRates) oneSecJson.Add(r);
        var fiveSecJson = new JsonArray();
        foreach (var w in fiveSecWindows)
        {
            fiveSecJson.Add(new JsonObject
            {
                ["windowIndex"] = w.WindowIndex,
                ["startElapsedSec"] = w.StartElapsedSec,
                ["endElapsedSec"] = w.EndElapsedSec,
                ["bytes"] = w.Bytes,
                ["rateMBps"] = w.RateMBps
            });
        }

        var summary = new JsonObject
        {
            ["path"] = pathName,
            ["runType"] = runType,
            ["repetitionIndex"] = repetitionIndex,
            ["repetitionTotal"] = repetitionTotal,
            ["success"] = sendResult.Success && recvResult.Success && shaVerified,
            ["sizeBytes"] = totalBytes,
            ["itemCount"] = sourceFiles.Count,
            ["preparationMs"] = sendDiag?.PreparationMs ?? 0,
            ["payloadTransferMs"] = Math.Round(payloadMs, 2),
            ["verificationMs"] = recvDiag?.VerificationMs ?? 0,
            ["commitMs"] = recvDiag?.CommitMs ?? 0,
            ["acceptanceToVerifiedMs"] = Math.Round(acceptToVerifiedMs, 2),
            ["payloadRateMBps"] = Math.Round(payloadRateMBps, 2),
            ["acceptanceToVerifiedRateMBps"] = Math.Round(acceptToVerifiedRateMBps, 2),
            ["oneSecondRatesMBps"] = oneSecJson,
            ["fiveSecondRateWindowsMBps"] = fiveSecJson,
            ["firstQuarterPayloadRateMBps"] = firstQ,
            ["finalQuarterPayloadRateMBps"] = finalQ,
            ["finalToFirstQuarterRatio"] = qRatio,
            ["slice4_5to5_0GB"] = sliceObj,
            ["senderSourceReadMs"] = sendDiag?.SourceReadMs ?? 0,
            ["senderHashMs"] = sendDiag?.HashComputeMs ?? 0,
            ["senderTlsWriteMs"] = sendDiag?.TlsWriteMs ?? 0,
            ["receiverTlsReadMs"] = recvDiag?.TlsReadMs ?? 0,
            ["receiverStagingWriteMs"] = recvDiag?.StagingWriteMs ?? 0,
            ["receiverHashMs"] = recvDiag?.HashComputeMs ?? 0,
            ["receiverVerificationReadMs"] = recvDiag?.VerificationReadMs ?? 0,
            ["receiverCommitMs"] = recvDiag?.CommitMs ?? 0,
            ["progressCallbacks"] = senderInteraction.ProgressCallbacks,
            ["uiDispatchedCallbacks"] = uiContext?.DispatchedCallbackCount ?? 0,
            ["totalAllocatedMB"] = Math.Round((allocAfter - allocBefore) / 1_000_000.0, 2),
            ["managedHeapDeltaMiB"] = Math.Round((heapAfter - heapBefore) / (1024.0 * 1024.0), 2),
            ["gcPauseMs"] = Math.Round((gcPauseAfter - gcPauseBefore).TotalMilliseconds, 2),
            ["gen0"] = (sendDiag?.Gen0Collections ?? 0) + (recvDiag?.Gen0Collections ?? 0),
            ["gen1"] = (sendDiag?.Gen1Collections ?? 0) + (recvDiag?.Gen1Collections ?? 0),
            ["gen2"] = (sendDiag?.Gen2Collections ?? 0) + (recvDiag?.Gen2Collections ?? 0),
            ["quarantineClean"] = quarantineClean,
            ["sha256Verified"] = shaVerified
        };

        return (summary, baseR3);
    }

    #endregion
}
