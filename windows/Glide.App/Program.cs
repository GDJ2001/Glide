using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Transport.Discovery;
using Glide.Transport.Sockets;
using Glide.Transport.Web;

namespace Glide.App;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        PrintBanner();

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
                "receive" => await RunReceiveAsync(args[1..]),
                "daemon" => await RunDaemonAsync(args[1..]),
                "send" => await RunSendAsync(args[1..]),
                "identity" => await RunIdentityAsync(args[1..]),
                "trust" => await RunTrustAsync(args[1..]),
                "web" => await RunWebAsync(args[1..]),
                _ => HandleUnknownCommand(command)
            };
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"\n[ERROR] {ex.Message}");
            Console.ResetColor();
            return 1;
        }
    }

    private static void PrintBanner()
    {
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("=================================================================");
        string osName = OperatingSystem.IsLinux() ? "Linux" : OperatingSystem.IsMacOS() ? "macOS" : "Windows";
        Console.WriteLine($"        Glide.{osName} — High-Speed Local File Sharing Companion  ");
        Console.WriteLine("               Zero Cloud • 100% Offline Local LAN               ");
        Console.WriteLine("=================================================================");
        Console.ResetColor();
    }

    private static void PrintUsage()
    {
        Console.WriteLine("\nUsage:");
        Console.WriteLine("  glide receive [--port <port>]");
        Console.WriteLine("      Start listening for incoming files from Glide Android or Desktop.");
        Console.WriteLine("      Defaults to port 42424 (or dynamic ephemeral port if unavailable).");
        Console.WriteLine("      Outputs a pairing invitation link and UTF-8 QR code for instant pairing.\n");
        Console.WriteLine("  glide daemon --accept-paired-offers --trusted-peer <fingerprint> [--port <port>] [--max-offer-bytes <bytes>] [--destination <path>]");
        Console.WriteLine("      Run a long-lived listener with one-use invitation bootstrap plus peer-specific trust policy.");
        Console.WriteLine("      Headless offer acceptance requires an already-enrolled persistent identity and a live authenticated peer fingerprint.");
        Console.WriteLine("      Until identity-v1 live proof is wired by HARD-021, daemon offers fail closed.\n");
        Console.WriteLine("  glide send --pair <pairing-invitation-uri> <file-paths...>");
        Console.WriteLine("      Connect to a Glide peer with pinned TLS 1.3 and one-use invitation authentication.\n");
        Console.WriteLine("  glide identity show");
        Console.WriteLine("  glide identity rotate --yes");
        Console.WriteLine("      Inspect or explicitly rotate this device's persistent identity. Rotation revokes all remembered peers.\n");
        Console.WriteLine("  glide trust list");
        Console.WriteLine("  glide trust revoke <fingerprint>");
        Console.WriteLine("      Inspect or revoke enrolled peer identities. Enrollment itself requires verified identity proof.\n");
        Console.WriteLine("  glide web [--port <port>] <file-paths...>");
        Console.WriteLine("      Start zero-install web gateway to share files with any browser (iOS, Mac, Linux).\n");
        Console.WriteLine("Examples:");
        Console.WriteLine("  glide receive");
        Console.WriteLine("  glide daemon --accept-paired-offers --trusted-peer <64-lowercase-hex> --max-offer-bytes 1073741824");
        Console.WriteLine("  glide send --pair glide://pair/v1#... \"C:\\Photos\\vacation.jpg\"");
        Console.WriteLine("  glide web \"C:\\Photos\\vacation.jpg\"\n");
    }

    private static int HandleUnknownCommand(string command)
    {
        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine($"Unknown command: '{command}'");
        Console.ResetColor();
        PrintUsage();
        return 1;
    }

    private static Task<int> RunIdentityAsync(
        string[] args)
    {
        var manager =
            CreateDesktopTrustManager();
        DesktopTrustInitialization initialization =
            manager.Initialize();

        if (args.Length == 1 &&
            string.Equals(
                args[0],
                "show",
                StringComparison.OrdinalIgnoreCase))
        {
            Console.WriteLine(
                $"Persistent identity: {initialization.LocalFingerprintHex}");
            if (initialization.ResetAfterIdentityLossOrChange)
            {
                Console.WriteLine(
                    $"[SECURITY] Local identity changed or was restored without its key; {initialization.RevokedPeerCount} peer trust record(s) were revoked.");
            }
            return Task.FromResult(0);
        }

        if (args.Length == 2 &&
            string.Equals(
                args[0],
                "rotate",
                StringComparison.OrdinalIgnoreCase) &&
            args[1] == "--yes")
        {
            DesktopTrustInitialization rotated =
                manager.RotateLocalIdentity();
            Console.WriteLine(
                $"Persistent identity rotated: {rotated.LocalFingerprintHex}");
            Console.WriteLine(
                $"Revoked peer trust records: {rotated.RevokedPeerCount}");
            return Task.FromResult(0);
        }

        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine(
            "[ERROR] Usage: glide identity show | glide identity rotate --yes");
        Console.ResetColor();
        return Task.FromResult(1);
    }

    private static Task<int> RunTrustAsync(
        string[] args)
    {
        var manager =
            CreateDesktopTrustManager();
        manager.Initialize();

        if (args.Length == 1 &&
            string.Equals(
                args[0],
                "list",
                StringComparison.OrdinalIgnoreCase))
        {
            IReadOnlyList<DesktopTrustedPeer> peers =
                manager.GetTrustedPeers();
            if (peers.Count == 0)
            {
                Console.WriteLine(
                    "No enrolled trusted peers.");
                return Task.FromResult(0);
            }

            foreach (DesktopTrustedPeer peer in peers)
            {
                HeadlessReceivePolicy? policy =
                    manager.GetHeadlessPolicy(
                        peer.FingerprintHex);
                Console.WriteLine(
                    $"{peer.FingerprintHex}  {peer.DisplayName}  headless={(policy?.Enabled == true ? "enabled" : "disabled")}");
            }
            return Task.FromResult(0);
        }

        if (args.Length == 2 &&
            string.Equals(
                args[0],
                "revoke",
                StringComparison.OrdinalIgnoreCase))
        {
            bool revoked =
                manager.RevokePeer(
                    args[1]);
            if (!revoked)
            {
                Console.ForegroundColor =
                    ConsoleColor.Yellow;
                Console.WriteLine(
                    "[WARN] No enrolled peer matched that fingerprint.");
                Console.ResetColor();
                return Task.FromResult(1);
            }

            Console.WriteLine(
                "Peer trust and its headless policy were revoked.");
            return Task.FromResult(0);
        }

        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine(
            "[ERROR] Usage: glide trust list | glide trust revoke <fingerprint>");
        Console.ResetColor();
        return Task.FromResult(1);
    }

    private static DesktopPersistentTrustManager
        CreateDesktopTrustManager() =>
        new(
            new DesktopPersistentIdentityStore(),
            new DesktopTrustStore());

    private static async Task<int> RunWebAsync(string[] args)
    {
        int port = 0;
        var filePaths = new List<string>();

        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] is "--port" or "-p" && i + 1 < args.Length && int.TryParse(args[i + 1], out int p))
            {
                port = p;
                i++;
            }
            else
            {
                filePaths.Add(args[i]);
            }
        }

        var offeredItems = new List<OfferedWebItem>();
        foreach (var path in filePaths)
        {
            if (!File.Exists(path))
            {
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine($"[WARN] File not found: '{path}' (skipping)");
                Console.ResetColor();
                continue;
            }
            offeredItems.Add(new OfferedWebItem(path));
        }

        string hostName = Dns.GetHostName();
        var localIp = Dns.GetHostAddresses(hostName)
            .FirstOrDefault(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))?
            .ToString() ?? "127.0.0.1";

        await using var gateway = new GlideWebGateway(
            host: localIp,
            port: port,
            offeredItems: offeredItems,
            deviceName: Environment.MachineName);

        await gateway.StartAsync();

        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine($"\n[READY] Web Transfer Gateway active on port {gateway.Port}");
        Console.ResetColor();
        Console.WriteLine($"Offered files: {offeredItems.Count} item(s)");
        foreach (var item in offeredItems)
        {
            Console.WriteLine($"  - {item.FileName} ({item.FileSize:N0} bytes)");
        }

        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine($"\nWeb Share Link: {gateway.OnboardingUrl}\n");
        Console.ResetColor();

        var qr = QrCodeGenerator.EncodeText(gateway.OnboardingUrl);
        Console.Write(qr.ToTerminalString(border: 2));

        Console.WriteLine("\nOpen link or scan QR on any device's browser (iOS, Mac, Linux, ChromeOS) on the same Wi-Fi.");
        Console.WriteLine("Press [Ctrl+C] to stop.\n");

        var tcs = new TaskCompletionSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            tcs.TrySetResult();
        };

        await tcs.Task;
        Console.WriteLine("\nStopping Web Gateway...");
        await gateway.StopAsync();
        Console.WriteLine("[STOPPED] Web Gateway closed.");
        return 0;
    }

    private static async Task<int> RunReceiveAsync(string[] args)
    {
        int port = LanDiscoveryConstants.DefaultPort;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] is "--port" or "-p" &&
                i + 1 < args.Length &&
                int.TryParse(args[i + 1], out int parsedPort))
            {
                port = parsedPort;
                break;
            }
        }

        TlsSocketListener listener;
        try
        {
            listener = new TlsSocketListener(port);
        }
        catch
        {
            Console.WriteLine($"[INFO] Port {port} unavailable, falling back to ephemeral port...");
            listener = new TlsSocketListener(0);
        }

        using (listener)
        {
            int boundPort = listener.BoundPort;
            string hostName = Dns.GetHostName();
            var ips = Dns.GetHostAddresses(hostName)
                .Where(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                .Select(ip => ip.ToString())
                .ToList();

            var stagingStore =
                new QuarantineStagingStore();
            var invitationAuthority =
                new LanInvitationAuthority();

            Console.ForegroundColor =
                ConsoleColor.Green;
            Console.WriteLine(
                $"\n[READY] Listening for incoming transfers on port {boundPort}");
            Console.WriteLine(
                $"[INFO] Local IP addresses: {string.Join(", ", ips)}");
            Console.WriteLine(
                $"[INFO] Staging sandbox: {stagingStore.StagingRoot}");
            Console.WriteLine(
                $"[INFO] Destination: {stagingStore.DestinationRoot}");
            Console.ResetColor();

            while (true)
            {
                LanInvitation invitation =
                    CreateReceiverInvitation(
                        listener,
                        boundPort,
                        ips,
                        invitationAuthority);
                string pairingUri =
                    InvitationCodec.Encode(
                        invitation);

                Console.ForegroundColor =
                    ConsoleColor.Cyan;
                Console.WriteLine(
                    "\n[PAIRING INVITATION] Scan or paste into peer device:");
                Console.WriteLine(
                    $"  {pairingUri}\n");
                Console.ResetColor();

                var qr =
                    QrCodeGenerator.EncodeText(
                        pairingUri);
                Console.Write(
                    qr.ToTerminalString(
                        border: 2));
                Console.WriteLine(
                    "\nWaiting for peer connection (invitation rotates when it expires)...");

                using var invitationCts =
                    new CancellationTokenSource(
                        TimeSpan.FromSeconds(115));
                try
                {
                    await using var connection =
                        await listener.AcceptConnectionAsync(
                            invitationCts.Token);
                    var engine =
                        new DesktopTransferEngine();
                    var trustManager =
                        CreateDesktopTrustManager();
                    trustManager.Initialize();
                    var interaction =
                        new ConsoleDesktopTransferInteraction(
                            isDaemon: false,
                            trustManager:
                                trustManager);

                    var result =
                        await engine.ReceiveAsync(
                            connection,
                            new DesktopReceiveRequest(
                                stagingStore,
                                invitationAuthority,
                                LocalDeviceName:
                                    Environment.MachineName,
                                TrustManager:
                                    trustManager),
                            interaction);

                    return result.Success ? 0 : 1;
                }
                catch (OperationCanceledException)
                    when (invitationCts.IsCancellationRequested)
                {
                    invitationAuthority.RevokeActive();
                    Console.WriteLine(
                        "[INFO] Pairing invitation expired; rotating to a fresh one.");
                }
            }
        }
    }

    private static async Task<int> RunDaemonAsync(string[] args)
    {
        int port =
            LanDiscoveryConstants.DefaultPort;
        bool acceptPairedOffers = false;
        string? trustedPeerFingerprint = null;
        long maxOfferBytes =
            1L * 1024 * 1024 * 1024;
        string? destination = null;

        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] is "--port" or "-p" &&
                i + 1 < args.Length &&
                int.TryParse(
                    args[i + 1],
                    out int parsedPort))
            {
                port = parsedPort;
                i++;
            }
            else if (args[i] ==
                     "--accept-paired-offers")
            {
                acceptPairedOffers = true;
            }
            else if (args[i] ==
                     "--trusted-peer" &&
                     i + 1 < args.Length)
            {
                trustedPeerFingerprint =
                    args[++i];
            }
            else if (args[i] ==
                     "--max-offer-bytes" &&
                     i + 1 < args.Length &&
                     long.TryParse(
                         args[i + 1],
                         out long parsedMax) &&
                     parsedMax > 0)
            {
                maxOfferBytes = parsedMax;
                i++;
            }
            else if (args[i] ==
                     "--destination" &&
                     i + 1 < args.Length)
            {
                destination =
                    Path.GetFullPath(
                        args[++i]);
            }
        }

        if (!acceptPairedOffers ||
            string.IsNullOrWhiteSpace(
                trustedPeerFingerprint))
        {
            Console.ForegroundColor =
                ConsoleColor.Red;
            Console.WriteLine(
                "[ERROR] Headless receiving requires --accept-paired-offers plus --trusted-peer <enrolled-fingerprint>.");
            Console.ResetColor();
            return 1;
        }

        TlsSocketListener listener;
        try
        {
            listener = new TlsSocketListener(port);
        }
        catch
        {
            Console.WriteLine($"[{DateTime.UtcNow:O}] [INFO] Port {port} unavailable, falling back to ephemeral port...");
            listener = new TlsSocketListener(0);
        }

        using (listener)
        {
            int boundPort = listener.BoundPort;
            string hostName = Dns.GetHostName();
            var ips = Dns.GetHostAddresses(hostName)
                .Where(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                .Select(ip => ip.ToString())
                .ToList();

            var stagingStore =
                new QuarantineStagingStore(
                    destinationRoot:
                        destination);
            var trustManager =
                CreateDesktopTrustManager();
            DesktopTrustInitialization trustInitialization =
                trustManager.Initialize();

            DesktopTrustedPeer? trustedPeer =
                trustManager.GetTrustedPeer(
                    trustedPeerFingerprint);
            if (trustedPeer == null)
            {
                Console.ForegroundColor =
                    ConsoleColor.Red;
                Console.WriteLine(
                    "[ERROR] --trusted-peer is not an enrolled persistent identity. Enrollment requires verified identity proof; manual fingerprint trust is not supported.");
                Console.ResetColor();
                return 1;
            }

            HeadlessReceivePolicy headlessPolicy =
                trustManager.SetHeadlessPolicy(
                    trustedPeer.FingerprintHex,
                    stagingStore.DestinationRoot,
                    maxOfferBytes,
                    enabled: true);

            var engine =
                new DesktopTransferEngine();
            var interaction =
                new ConsoleDesktopTransferInteraction(
                    isDaemon: true,
                    trustManager:
                        trustManager,
                    authorizedPeerFingerprintHex:
                        headlessPolicy.PeerFingerprintHex,
                    destinationRoot:
                        headlessPolicy.DestinationRoot);
            var invitationAuthority = new LanInvitationAuthority();

            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON READY] Glide daemon listening on port {boundPort}");
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON INFO] Staging: {stagingStore.StagingRoot}");
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON INFO] Destination: {stagingStore.DestinationRoot}");
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON POLICY] Enrolled peer: {trustedPeer.FingerprintHex}");
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON POLICY] Maximum offer: {headlessPolicy.MaxOfferBytes} bytes");
            if (trustInitialization.ResetAfterIdentityLossOrChange)
            {
                Console.WriteLine(
                    $"[{DateTime.UtcNow:O}] [SECURITY] Local identity changed; {trustInitialization.RevokedPeerCount} trust record(s) were revoked.");
            }
            Console.ResetColor();

            using var cts = new CancellationTokenSource();
            Console.CancelKeyPress += (_, e) =>
            {
                e.Cancel = true;
                cts.Cancel();
            };

            PosixSignalRegistration? sigTerm = null;
            if (OperatingSystem.IsLinux() || OperatingSystem.IsMacOS())
            {
                sigTerm = PosixSignalRegistration.Create(
                    PosixSignal.SIGTERM,
                    _ => cts.Cancel());
            }

            try
            {
                while (!cts.IsCancellationRequested)
                {
                    try
                    {
                        LanInvitation invitation = CreateReceiverInvitation(
                            listener,
                            boundPort,
                            ips,
                            invitationAuthority);
                        string pairingUri = InvitationCodec.Encode(invitation);

                        Console.ForegroundColor = ConsoleColor.Cyan;
                        Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON AUTH] One-use pairing invitation (120 s):");
                        Console.WriteLine(pairingUri);
                        Console.ResetColor();

                        using var invitationCts =
                            CancellationTokenSource
                                .CreateLinkedTokenSource(
                                    cts.Token);
                        invitationCts.CancelAfter(
                            TimeSpan.FromSeconds(115));

                        await using var connection =
                            await listener.AcceptConnectionAsync(
                                invitationCts.Token);
                        Console.WriteLine(
                            $"[{DateTime.UtcNow:O}] [DAEMON] Connection established from {connection.TcpClient.Client.RemoteEndPoint}");

                        await engine.ReceiveAsync(
                            connection,
                            new DesktopReceiveRequest(
                                stagingStore,
                                invitationAuthority,
                                LocalDeviceName: Environment.MachineName,
                                TrustManager: trustManager,
                                ExpectedPeerFingerprintHex:
                                    trustedPeer.FingerprintHex),
                            interaction,
                            cts.Token);

                        // A connection attempt consumes or invalidates the current authorization.
                        invitationAuthority.RevokeActive();
                    }
                    catch (OperationCanceledException)
                        when (!cts.IsCancellationRequested)
                    {
                        invitationAuthority.RevokeActive();
                        Console.WriteLine(
                            $"[{DateTime.UtcNow:O}] [DAEMON AUTH] Pairing invitation expired; rotating.");
                        continue;
                    }
                    catch (OperationCanceledException)
                        when (cts.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        invitationAuthority.RevokeActive();
                        Console.ForegroundColor = ConsoleColor.Red;
                        Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON ERROR] Connection error: {ex.Message}");
                        Console.ResetColor();
                    }
                }
            }
            finally
            {
                invitationAuthority.RevokeAll();
                sigTerm?.Dispose();
            }

            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON STOPPED] Clean shutdown complete.");
            return 0;
        }
    }



    private static async Task<int> RunSendAsync(string[] args)
    {
        if (args.Length < 3 || args[0] is not ("--pair" or "-p"))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine(
                "[ERROR] Authenticated pairing is required. Use: glide send --pair <pairing-invitation-uri> <file-paths...>");
            Console.ResetColor();
            return 1;
        }

        string pairUri = args[1];
        var fileArgs = args[2..];
        if (fileArgs.Length == 0)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[ERROR] Please provide at least one file path to send.");
            Console.ResetColor();
            return 1;
        }

        var files = new List<DesktopTransferFile>();
        foreach (string filePath in fileArgs)
        {
            if (!File.Exists(filePath))
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine($"File not found: '{filePath}'");
                Console.ResetColor();
                return 1;
            }

            files.Add(new DesktopTransferFile(filePath));
        }

        LanInvitation invitation;
        try
        {
            invitation = InvitationCodec.Decode(pairUri);
            if (invitation.IsExpired())
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine(
                    "[ERROR] Pairing invitation has expired. Request a new QR/pairing link.");
                Console.ResetColor();
                return 1;
            }
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[ERROR] Invalid pairing link: {ex.Message}");
            Console.ResetColor();
            return 1;
        }

        var endpoint = invitation.Endpoints.First();
        Console.WriteLine(
            $"[INFO] Authenticated pairing invitation for '{invitation.Name}' ({endpoint.Address}:{endpoint.Port})");

        var engine = new DesktopTransferEngine();
        var trustManager =
            CreateDesktopTrustManager();
        trustManager.Initialize();
        var interaction =
            new ConsoleDesktopTransferInteraction(
                isDaemon: false,
                trustManager: trustManager);
        var result = await engine.SendAsync(
            new DesktopSendRequest(
                endpoint.Address,
                endpoint.Port,
                files,
                invitation,
                Environment.MachineName,
                TrustManager: trustManager),
            interaction);

        return result.Success ? 0 : 1;
    }

    private sealed class ConsoleDesktopTransferInteraction(
        bool isDaemon,
        DesktopPersistentTrustManager? trustManager = null,
        string? authorizedPeerFingerprintHex = null,
        string? destinationRoot = null)
        : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken)
        {
            cancellationToken
                .ThrowIfCancellationRequested();

            string prefix =
                isDaemon
                    ? $"[{DateTime.UtcNow:O}] "
                    : string.Empty;
            Console.WriteLine(
                $"{prefix}[OFFER] Inbound offer: {offer.Items.Count} items, {offer.TotalBytes} bytes from '{offer.PeerName}'");

            if (isDaemon)
            {
                bool fingerprintMatches =
                    !string.IsNullOrWhiteSpace(
                        authorizedPeerFingerprintHex) &&
                    string.Equals(
                        offer.AuthenticatedPeerFingerprintHex,
                        authorizedPeerFingerprintHex,
                        StringComparison.Ordinal);

                bool authorized =
                    fingerprintMatches &&
                    trustManager != null &&
                    destinationRoot != null &&
                    trustManager.AuthorizeHeadlessOffer(
                        offer.AuthenticatedPeerFingerprintHex,
                        offer.TotalBytes,
                        destinationRoot);

                if (offer.AuthenticatedPeerFingerprintHex == null)
                {
                    Console.WriteLine(
                        $"{prefix}[AUTH] Offer rejected: this session has no verified persistent peer identity.");
                }
                else
                {
                    Console.WriteLine(
                        authorized
                            ? $"{prefix}[AUTH] Offer accepted by enrolled peer-specific headless policy."
                            : $"{prefix}[AUTH] Offer rejected by peer-specific headless policy.");
                }

                return Task.FromResult(
                    authorized);
            }

            Console.Write(
                "Accept this authenticated transfer? [y/N]: ");
            string? answer =
                Console.ReadLine();
            return Task.FromResult(
                string.Equals(
                    answer?.Trim(),
                    "y",
                    StringComparison.OrdinalIgnoreCase) ||
                string.Equals(
                    answer?.Trim(),
                    "yes",
                    StringComparison.OrdinalIgnoreCase));
        }

        public override Task<bool> ConfirmPeerEnrollmentAsync(
            VerifiedDesktopPeerIdentity peer,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();

            if (isDaemon)
            {
                return Task.FromResult(false);
            }

            Console.WriteLine(
                $"[IDENTITY] Verified peer fingerprint: {peer.FingerprintHex}");
            Console.Write(
                "Trust this device for future authenticated sessions? [y/N]: ");
            string? answer =
                Console.ReadLine();
            bool approved =
                string.Equals(
                    answer?.Trim(),
                    "y",
                    StringComparison.OrdinalIgnoreCase) ||
                string.Equals(
                    answer?.Trim(),
                    "yes",
                    StringComparison.OrdinalIgnoreCase);

            return Task.FromResult(
                approved);
        }

        public override void OnInvitationConsumed(DesktopTransferDirection direction)
        {
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("[BOOTSTRAP ACCEPTED] Pairing invitation authenticated.");
            Console.ResetColor();
        }

        public override void OnPeerIdentified(
            DesktopTransferDirection direction,
            string peerName)
        {
            Console.WriteLine($"[PROTOCOL] Connected with '{peerName}'");
        }

        public override void OnStatus(
            DesktopTransferDirection direction,
            string message)
        {
            string prefix = isDaemon ? $"[{DateTime.UtcNow:O}] " : string.Empty;
            Console.WriteLine($"{prefix}[{direction.ToString().ToUpperInvariant()}] {message}");
        }

        public override void OnItemStatus(
            DesktopTransferDirection direction,
            string itemName,
            string status)
        {
            Console.WriteLine($"[{status.ToUpperInvariant()}] {itemName}");
        }

        public override void OnHistory(DesktopHistoryEntry entry)
        {
            if (entry.Direction == DesktopTransferDirection.Receive)
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"[VERIFIED & COMMITTED] {entry.ItemName} -> {entry.Path}");
                Console.ResetColor();
            }
        }
    }

    private static LanInvitation CreateReceiverInvitation(
        TlsSocketListener listener,
        int boundPort,
        IReadOnlyList<string> localIps,
        LanInvitationAuthority authority)
    {
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        byte[] spkiHashBytes = SHA256.HashData(
            listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
        string spkiSha256 =
            Convert.ToHexString(spkiHashBytes).ToLowerInvariant();

        var endpoints = localIps
            .Select(ip => new LanEndpoint(ip, boundPort))
            .Take(LanInvitation.MaxEndpoints)
            .ToList();
        if (endpoints.Count == 0)
        {
            endpoints.Add(new LanEndpoint("127.0.0.1", boundPort));
        }

        var invitation = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: LanDiscoveryConstants.GeneratePresenceId(),
            name: Environment.MachineName,
            platform: GetCurrentPlatform(),
            endpoints: endpoints,
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: (
                DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120)
                .ToString(),
            ttlSeconds: 120);

        return authority.Publish(invitation);
    }

    private static string GetCurrentPlatform() =>
        OperatingSystem.IsLinux() ? "linux" :
        OperatingSystem.IsMacOS() ? "macos" :
        OperatingSystem.IsWindows() ? "windows" : "unknown";
}
