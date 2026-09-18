using System.Diagnostics;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
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
using ProtocolViolationException = Glide.Core.Models.ProtocolViolationException;

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
        Console.WriteLine("      Uses a dynamic ephemeral port by default; --port pins an explicit listener port.");
        Console.WriteLine("      Outputs a pairing invitation link and UTF-8 QR code for instant pairing.\n");
        Console.WriteLine("  glide daemon --policy <policy.json> [--port <port>]");
        Console.WriteLine("      Run unattended receive with an explicit IP/destination/size policy.");
        Console.WriteLine("      Writes a fresh one-use pairing URI to the policy's protected enrollmentFile.\n");
        Console.WriteLine("  glide send --pair <pairing-invitation-uri> <file-paths...>");
        Console.WriteLine("      Connect to a Glide peer and send files over authenticated TLS 1.3.\n");
        Console.WriteLine("  glide web [--port <port>] <file-paths...>");
        Console.WriteLine("      Start zero-install web gateway to share files with any browser (iOS, Mac, Linux).\n");
        Console.WriteLine("Examples:");
        Console.WriteLine("  glide receive");
        Console.WriteLine("  glide daemon --policy ~/.config/glide/daemon-policy.json");
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
        int port = 0;
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

            string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
            byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
            string token = Convert.ToBase64String(tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            byte[] spkiHashBytes = SHA256.HashData(listener.ServerCertificate.PublicKey.ExportSubjectPublicKeyInfo());
            string spkiSha256 = Convert.ToHexString(spkiHashBytes).ToLowerInvariant();
            long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120;

            var endpoints = ips
                .Select(ip => new LanEndpoint(ip, boundPort))
                .Take(4)
                .ToList();
            if (endpoints.Count == 0)
            {
                endpoints.Add(new LanEndpoint("127.0.0.1", boundPort));
            }

            var stagingStore = new QuarantineStagingStore();
            var invitation = new LanInvitation(
                invitationId: Guid.NewGuid().ToString().ToLowerInvariant(),
                presence: presence,
                name: Environment.MachineName,
                platform: GetCurrentPlatform(),
                endpoints: endpoints,
                spkiSha256: spkiSha256,
                token: token,
                expiresAt: expiresAt.ToString(),
                ttlSeconds: 120);

            string pairingUri = InvitationCodec.Encode(invitation);
            var canonicalInvitation = InvitationCodec.Decode(pairingUri);
            var invitationAuthority = new LanInvitationAuthority(canonicalInvitation);

            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"\n[READY] Listening for incoming transfers on port {boundPort}");
            Console.WriteLine($"[INFO] Local IP addresses: {string.Join(", ", ips)}");
            Console.WriteLine($"[INFO] Staging sandbox: {stagingStore.StagingRoot}");
            Console.WriteLine($"[INFO] Destination: {stagingStore.DestinationRoot}");
            Console.ResetColor();

            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine("\n[PAIRING INVITATION] Scan or paste into peer device:");
            Console.WriteLine($"  {pairingUri}\n");
            Console.ResetColor();

            var qr = QrCodeGenerator.EncodeText(pairingUri);
            Console.Write(qr.ToTerminalString(border: 2));
            Console.WriteLine("\nWaiting for peer connection (Ctrl+C to cancel)...");

            await using var connection = await listener.AcceptConnectionAsync();
            var engine = new DesktopTransferEngine();
            var interaction = new ConsoleDesktopTransferInteraction(isDaemon: false);

            var result = await engine.ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    stagingStore,
                    invitationAuthority,
                    LocalDeviceName: Environment.MachineName),
                interaction);

            Console.WriteLine($"[RESULT] {result.Message}");
            return result.Success ? 0 : 1;
        }
    }

    private static async Task<int> RunDaemonAsync(string[] args)
    {
        int port = 0;
        string? policyPath = null;

        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] is "--port" or "-p")
            {
                if (i + 1 >= args.Length ||
                    !int.TryParse(args[++i], out port) ||
                    port is < 0 or > 65535)
                {
                    Console.Error.WriteLine(
                        "[DAEMON ERROR] --port requires a value from 0 to 65535.");
                    return 1;
                }
            }
            else if (args[i] == "--policy")
            {
                if (i + 1 >= args.Length)
                {
                    Console.Error.WriteLine(
                        "[DAEMON ERROR] --policy requires a JSON file path.");
                    return 1;
                }

                policyPath = args[++i];
            }
            else
            {
                Console.Error.WriteLine(
                    $"[DAEMON ERROR] Unknown daemon option '{args[i]}'.");
                return 1;
            }
        }

        if (string.IsNullOrWhiteSpace(policyPath))
        {
            Console.Error.WriteLine(
                "[DAEMON ERROR] Unattended receiving requires --policy <policy.json>. Refusing to start without explicit authorization policy.");
            return 1;
        }

        DaemonReceivePolicy policy;
        try
        {
            policy = await DaemonReceivePolicy.LoadAsync(policyPath);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(
                $"[DAEMON ERROR] Invalid receive policy: {ex.Message}");
            return 1;
        }

        using var shutdownCts =
            new CancellationTokenSource();

        ConsoleCancelEventHandler cancelHandler = (_, e) =>
        {
            e.Cancel = true;
            shutdownCts.Cancel();
        };
        Console.CancelKeyPress += cancelHandler;

        PosixSignalRegistration? sigTerm = null;
        if (OperatingSystem.IsLinux() ||
            OperatingSystem.IsMacOS())
        {
            sigTerm = PosixSignalRegistration.Create(
                PosixSignal.SIGTERM,
                context =>
                {
                    context.Cancel = true;
                    shutdownCts.Cancel();
                });
        }

        try
        {
            return await RunDaemonWithPolicyAsync(
                policy,
                port,
                shutdownCts.Token);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(
                $"[DAEMON ERROR] {ex.Message}");
            return 1;
        }
        finally
        {
            shutdownCts.Cancel();
            sigTerm?.Dispose();
            Console.CancelKeyPress -= cancelHandler;
        }
    }

    internal static async Task<int> RunDaemonWithPolicyAsync(
        DaemonReceivePolicy policy,
        int port,
        CancellationToken shutdownToken)
    {
        ArgumentNullException.ThrowIfNull(policy);
        policy = policy.Validate();

        if (port is < 0 or > 65535)
        {
            throw new ArgumentOutOfRangeException(
                nameof(port));
        }

        using var listener =
            new TlsSocketListener(port);

        int boundPort = listener.BoundPort;
        var enrollmentFile =
            new DaemonEnrollmentFile(
                policy.EnrollmentFile);
        var stagingStore =
            new QuarantineStagingStore(
                destinationRoot:
                    policy.DestinationDirectory);
        var engine =
            new DesktopTransferEngine();
        DesktopReceiveLimits receiveLimits =
            policy.ToReceiveLimits();

        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine(
            $"[{DateTime.UtcNow:O}] [DAEMON READY] Glide daemon listening on port {boundPort}");
        Console.WriteLine(
            $"[{DateTime.UtcNow:O}] [DAEMON POLICY] Destination: {policy.DestinationDirectory}");
        Console.WriteLine(
            $"[{DateTime.UtcNow:O}] [DAEMON POLICY] Allowed remote IPs: {string.Join(", ", policy.AllowedRemoteAddresses)}");
        Console.WriteLine(
            $"[{DateTime.UtcNow:O}] [DAEMON POLICY] Limits: {policy.MaxItems} item(s), {policy.MaxItemBytes} bytes/item, {policy.MaxBatchBytes} bytes/batch");
        Console.WriteLine(
            $"[{DateTime.UtcNow:O}] [DAEMON INFO] Enrollment URI is written to {policy.EnrollmentFile}; bearer contents are never logged.");
        Console.ResetColor();

        try
        {
            while (!shutdownToken.IsCancellationRequested)
            {
                (LanInvitation invitation, string invitationUri) =
                    CreateDaemonInvitation(
                        listener,
                        boundPort,
                        policy);

                var invitationAuthority =
                    new LanInvitationAuthority(
                        invitation);

                await enrollmentFile.WriteAsync(
                    invitationUri,
                    shutdownToken);

                Console.WriteLine(
                    $"[{DateTime.UtcNow:O}] [DAEMON ENROLLMENT] Fresh one-use enrollment ready at {policy.EnrollmentFile}; expires in {policy.InvitationTtlSeconds}s.");

                long expiryTicks =
                    Stopwatch.GetTimestamp() +
                    (long)(
                        policy.InvitationTtlSeconds *
                        (double)Stopwatch.Frequency);

                try
                {
                    while (!shutdownToken.IsCancellationRequested &&
                           !invitationAuthority.IsConsumed)
                    {
                        long remainingTicks =
                            expiryTicks -
                            Stopwatch.GetTimestamp();
                        if (remainingTicks <= 0)
                        {
                            break;
                        }

                        TimeSpan remaining =
                            TimeSpan.FromSeconds(
                                remainingTicks /
                                (double)Stopwatch.Frequency);

                        using var acceptCts =
                            CancellationTokenSource.CreateLinkedTokenSource(
                                shutdownToken);
                        acceptCts.CancelAfter(remaining);

                        TlsSocketConnection connection;
                        try
                        {
                            connection =
                                await listener.AcceptConnectionAsync(
                                    acceptCts.Token);
                        }
                        catch (OperationCanceledException)
                            when (!shutdownToken.IsCancellationRequested)
                        {
                            // Enrollment TTL elapsed while no connection was accepted.
                            break;
                        }
                        catch (OperationCanceledException)
                            when (shutdownToken.IsCancellationRequested)
                        {
                            throw;
                        }
                        catch (Exception ex)
                        {
                            Console.WriteLine(
                                $"[{DateTime.UtcNow:O}] [DAEMON REJECT] TLS/connection setup failed: {ex.Message}");
                            continue;
                        }

                        await using (connection)
                        {
                            if (connection.TcpClient.Client.RemoteEndPoint
                                    is not IPEndPoint remote ||
                                !policy.IsRemoteAllowed(remote.Address))
                            {
                                Console.WriteLine(
                                    $"[{DateTime.UtcNow:O}] [DAEMON REJECT] Remote endpoint is not authorized by policy.");
                                continue;
                            }

                            Console.WriteLine(
                                $"[{DateTime.UtcNow:O}] [DAEMON] Policy-allowed network peer connected from {remote.Address}.");

                            var interaction =
                                new ConsoleDesktopTransferInteraction(
                                    isDaemon: true,
                                    daemonPolicy: policy,
                                    onInvitationConsumed:
                                        enrollmentFile.Delete);

                            DesktopTransferResult result =
                                await engine.ReceiveAsync(
                                    connection,
                                    new DesktopReceiveRequest(
                                        stagingStore,
                                        invitationAuthority,
                                        LocalDeviceName:
                                            policy.DeviceName,
                                        Limits:
                                            receiveLimits),
                                    interaction,
                                    shutdownToken);

                            Console.WriteLine(
                                $"[{DateTime.UtcNow:O}] [RESULT] {result.Message}");
                        }
                    }
                }
                finally
                {
                    enrollmentFile.Delete();
                }
            }
        }
        catch (OperationCanceledException)
            when (shutdownToken.IsCancellationRequested)
        {
            // Normal Ctrl+C / SIGTERM/test-driven shutdown.
        }
        finally
        {
            enrollmentFile.Delete();
        }

        Console.WriteLine(
            $"[{DateTime.UtcNow:O}] [DAEMON STOPPED] Clean shutdown complete.");
        return 0;
    }

    private static (LanInvitation Invitation, string Uri)
        CreateDaemonInvitation(
            TlsSocketListener listener,
            int boundPort,
            DaemonReceivePolicy policy)
    {
        var localAddresses =
            Dns.GetHostAddresses(Dns.GetHostName())
                .Where(ip =>
                    ip.AddressFamily ==
                    System.Net.Sockets.AddressFamily.InterNetwork)
                .Select(ip => ip.ToString())
                .Distinct(StringComparer.Ordinal)
                .Take(4)
                .ToList();

        if (localAddresses.Count == 0)
        {
            localAddresses.Add("127.0.0.1");
        }

        string presence =
            Convert.ToHexString(
                    RandomNumberGenerator.GetBytes(16))
                .ToLowerInvariant();
        string token =
            Convert.ToBase64String(
                    RandomNumberGenerator.GetBytes(32))
                .TrimEnd('=')
                .Replace('+', '-')
                .Replace('/', '_');

        var draft = new LanInvitation(
            invitationId:
                Guid.NewGuid()
                    .ToString("D")
                    .ToLowerInvariant(),
            presence: presence,
            name: policy.DeviceName,
            platform: GetCurrentPlatform(),
            endpoints:
                localAddresses
                    .Select(address =>
                        new LanEndpoint(
                            address,
                            boundPort))
                    .ToList(),
            spkiSha256: listener.LocalSpkiSha256,
            token: token,
            expiresAt:
                DateTimeOffset.UtcNow
                    .AddSeconds(policy.InvitationTtlSeconds)
                    .ToUnixTimeSeconds()
                    .ToString(),
            ttlSeconds: policy.InvitationTtlSeconds);

        string uri =
            InvitationCodec.Encode(draft);
        LanInvitation canonical =
            InvitationCodec.Decode(uri);

        return (canonical, uri);
    }

    private static async Task<bool> HandleConnectionTransferAsync(
        TlsSocketConnection connection,
        QuarantineStagingStore stagingStore,
        LanInvitationAuthority? invitationAuthority,
        bool isDaemon)
    {
        var engine = new DesktopTransferEngine();
        var result = await engine.ReceiveAsync(
            connection,
            new DesktopReceiveRequest(
                stagingStore,
                invitationAuthority,
                LocalDeviceName: Environment.MachineName),
            new ConsoleDesktopTransferInteraction(isDaemon));

        return result.Success;
    }

    private static async Task<int> RunSendAsync(string[] args)
    {
        string? pairUri = null;
        string host = "127.0.0.1";
        int port = 0;
        var fileArgs = new List<string>();

        if (args.Length >= 3 && args[0] is "--pair" or "-p")
        {
            pairUri = args[1];
            fileArgs.AddRange(args[2..]);
        }
        else
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[ERROR] LAN sending requires --pair <pairing-invitation-uri> <file-paths...>.");
            Console.WriteLine("        Raw host/port sending is disabled because discovery endpoints are not authentication.");
            Console.ResetColor();
            return 1;
        }

        if (fileArgs.Count == 0)
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

        LanInvitation? invitation = null;
        if (pairUri != null)
        {
            try
            {
                invitation = InvitationCodec.Decode(pairUri);
                if (invitation.IsExpired())
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine("[ERROR] Pairing invitation has expired. Request a new QR/pairing link.");
                    Console.ResetColor();
                    return 1;
                }

                var endpoint = invitation.Endpoints.First();
                host = endpoint.Address;
                port = endpoint.Port;
                Console.WriteLine($"[INFO] Decoded pairing invitation for '{invitation.Name}' ({host}:{port})");
            }
            catch (Exception ex)
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine($"[ERROR] Invalid pairing link: {ex.Message}");
                Console.ResetColor();
                return 1;
            }
        }

        var engine = new DesktopTransferEngine();
        var interaction = new ConsoleDesktopTransferInteraction(isDaemon: false);
        var result = await engine.SendAsync(
            new DesktopSendRequest(
                host,
                port,
                files,
                invitation,
                Environment.MachineName),
            interaction);

        Console.WriteLine($"[RESULT] {result.Message}");
        return result.Success ? 0 : 1;
    }

    private sealed class ConsoleDesktopTransferInteraction(
        bool isDaemon,
        DaemonReceivePolicy? daemonPolicy = null,
        Action? onInvitationConsumed = null)
        : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmSasAsync(
            DesktopTransferDirection direction,
            string peerName,
            int sixDigitCode,
            CancellationToken cancellationToken)
        {
            Console.ForegroundColor = ConsoleColor.Magenta;
            if (isDaemon)
            {
                Console.WriteLine(
                    $"[{DateTime.UtcNow:O}] [DAEMON SAS] 6-digit verification code: {sixDigitCode:D6}");
            }
            else
            {
                Console.WriteLine("-----------------------------------------------------------------");
                Console.WriteLine(
                    $"  *** 6-DIGIT VERIFICATION CODE: [ {sixDigitCode:D6} ] ***");
                Console.WriteLine(
                    "  Verify that this code matches on the other device screen.");
                Console.WriteLine("-----------------------------------------------------------------");
            }
            Console.ResetColor();

            // Preserve current CLI behavior: display the code and continue.
            return Task.FromResult(true);
        }

        public override async Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken)
        {
            string prefix = isDaemon ? $"[{DateTime.UtcNow:O}] " : string.Empty;
            Console.WriteLine(
                $"{prefix}[OFFER] Inbound offer: {offer.Items.Count} items, {offer.TotalBytes} bytes from '{offer.PeerName}'");

            if (isDaemon)
            {
                bool authorized =
                    daemonPolicy?.AuthorizesOffer(offer) == true;
                Console.WriteLine(
                    authorized
                        ? $"{prefix}[OFFER] Authorized by explicit daemon size policy."
                        : $"{prefix}[OFFER] Rejected by daemon authorization policy.");
                return authorized;
            }

            Console.Write("Accept this transfer? [y/N]: ");
            string? response = await Console.In.ReadLineAsync(cancellationToken);
            return string.Equals(
                response?.Trim(),
                "y",
                StringComparison.OrdinalIgnoreCase) ||
                string.Equals(
                    response?.Trim(),
                    "yes",
                    StringComparison.OrdinalIgnoreCase);
        }

        public override void OnInvitationConsumed(DesktopTransferDirection direction)
        {
            onInvitationConsumed?.Invoke();
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("[BOOTSTRAP ACCEPTED] One-use pairing enrollment authenticated.");
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

        public override Task OnHistoryAsync(
            DesktopHistoryEntry entry,
            CancellationToken cancellationToken = default)
        {
            if (entry.Direction == DesktopTransferDirection.Receive)
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"[VERIFIED & COMMITTED] {entry.ItemName} -> {entry.Path}");
                Console.ResetColor();
            }

            return Task.CompletedTask;
        }
    }

    private static string GetCurrentPlatform() =>
        OperatingSystem.IsLinux() ? "linux" :
        OperatingSystem.IsMacOS() ? "macos" :
        OperatingSystem.IsWindows() ? "windows" : "unknown";
}
