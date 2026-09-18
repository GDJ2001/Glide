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
        Console.WriteLine("  glide daemon [--port <port>]");
        Console.WriteLine("      Starts the daemon transport host, but unauthenticated receiving is disabled.");
        Console.WriteLine("      Peer-specific trusted receiving policy is completed by HARD-016.\n");
        Console.WriteLine("  glide send --pair <pairing-invitation-uri> <file-paths...>");
        Console.WriteLine("      Connect to a Glide peer and send files over authenticated TLS 1.3.\n");
        Console.WriteLine("  glide web [--port <port>] <file-paths...>");
        Console.WriteLine("      Start zero-install web gateway to share files with any browser (iOS, Mac, Linux).\n");
        Console.WriteLine("Examples:");
        Console.WriteLine("  glide receive");
        Console.WriteLine("  glide daemon");
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

            return result.Success ? 0 : 1;
        }
    }

    private static async Task<int> RunDaemonAsync(string[] args)
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
            Console.WriteLine($"[{DateTime.UtcNow:O}] [INFO] Port {port} unavailable, falling back to ephemeral port...");
            listener = new TlsSocketListener(0);
        }

        using (listener)
        {
            int boundPort = listener.BoundPort;
            var stagingStore = new QuarantineStagingStore();
            var engine = new DesktopTransferEngine();
            var interaction = new ConsoleDesktopTransferInteraction(isDaemon: true);

            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON READY] Glide daemon listening on port {boundPort}");
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON INFO] Staging: {stagingStore.StagingRoot}");
            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON INFO] Destination: {stagingStore.DestinationRoot}");
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
                        var acceptTask = listener.AcceptConnectionAsync(cts.Token);
                        await using var connection = await acceptTask;
                        Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON] Connection established from {connection.TcpClient.Client.RemoteEndPoint}");

                        await engine.ReceiveAsync(
                            connection,
                            new DesktopReceiveRequest(
                                stagingStore,
                                InvitationAuthority: null,
                                LocalDeviceName: Environment.MachineName),
                            interaction,
                            cts.Token);
                    }
                    catch (OperationCanceledException) when (cts.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        Console.ForegroundColor = ConsoleColor.Red;
                        Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON ERROR] Connection error: {ex.Message}");
                        Console.ResetColor();
                    }
                }
            }
            finally
            {
                sigTerm?.Dispose();
            }

            Console.WriteLine($"[{DateTime.UtcNow:O}] [DAEMON STOPPED] Clean shutdown complete.");
            return 0;
        }
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

        return result.Success ? 0 : 1;
    }

    private sealed class ConsoleDesktopTransferInteraction(bool isDaemon)
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

        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken)
        {
            string prefix = isDaemon ? $"[{DateTime.UtcNow:O}] " : string.Empty;
            Console.WriteLine(
                $"{prefix}[OFFER] Inbound offer: {offer.Items.Count} items, {offer.TotalBytes} bytes from '{offer.PeerName}'");

            // HARD-016 owns explicit headless authorization policy.
            return Task.FromResult(true);
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
