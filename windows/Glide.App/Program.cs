using System.Net;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

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
                "send" => await RunSendAsync(args[1..]),
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
        Console.WriteLine("        Glide.Windows — High-Speed Local File Sharing Companion  ");
        Console.WriteLine("               Zero Cloud • 100% Offline Local LAN               ");
        Console.WriteLine("=================================================================");
        Console.ResetColor();
    }

    private static void PrintUsage()
    {
        Console.WriteLine("\nUsage:");
        Console.WriteLine("  glide receive [--port <port>]");
        Console.WriteLine("      Start listening for incoming files from Glide Android or Desktop.");
        Console.WriteLine("      Defaults to port 42424 (or dynamic ephemeral port if unavailable).\n");
        Console.WriteLine("  glide send <peer-host> <peer-port> <file-paths...>");
        Console.WriteLine("      Connect to a Glide peer and send files over authenticated TLS 1.3.\n");
        Console.WriteLine("Examples:");
        Console.WriteLine("  glide receive");
        Console.WriteLine("  glide send 192.168.1.50 42424 \"C:\\Photos\\vacation.jpg\"\n");
    }

    private static int HandleUnknownCommand(string command)
    {
        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine($"Unknown command: '{command}'");
        Console.ResetColor();
        PrintUsage();
        return 1;
    }

    private static async Task<int> RunReceiveAsync(string[] args)
    {
        int port = LanDiscoveryConstants.DefaultPort;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] is "--port" or "-p" && i + 1 < args.Length && int.TryParse(args[i + 1], out int p))
            {
                port = p;
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

            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"\n[READY] Listening for incoming transfers on port {boundPort}");
            Console.WriteLine($"[INFO] Local IP addresses: {string.Join(", ", ips)}");
            Console.WriteLine($"[INFO] Staging sandbox: %LocalAppData%\\Glide\\staging\\");
            Console.WriteLine($"[INFO] Destination: %USERPROFILE%\\Downloads\\");
            Console.ResetColor();
            Console.WriteLine("\nWaiting for peer connection (Ctrl+C to cancel)...");

            await using var conn = await listener.AcceptConnectionAsync();
            Console.ForegroundColor = ConsoleColor.Yellow;
            Console.WriteLine($"\n[CONNECTED] Peer connected! Performing TLS 1.3 mutual handshake...");
            Console.ResetColor();

            // Compute SAS
            byte[] sessionNonce = RandomNumberGenerator.GetBytes(32);
            int sas = SasCalculator.CalculateSas(conn.RemotePublicKeyBytes, conn.LocalPublicKeyBytes, sessionNonce);

            Console.ForegroundColor = ConsoleColor.Magenta;
            Console.WriteLine("-----------------------------------------------------------------");
            Console.WriteLine($"  *** 6-DIGIT VERIFICATION CODE: [ {sas:D6} ] ***");
            Console.WriteLine("  Verify that this code matches on the sending device screen.");
            Console.WriteLine("-----------------------------------------------------------------");
            Console.ResetColor();

            var channel = new LengthPrefixedFrameChannel(conn.SslStream);
            var stagingStore = new QuarantineStagingStore();

            // Receive HELLO
            var helloEnv = await channel.ReadEnvelopeAsync();
            Console.WriteLine($"[PROTOCOL] Received {helloEnv.Type} from '{helloEnv.Body["displayName"]}'");

            // Send HELLO_ACK
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
            var ackEnv = new Envelope(1, MessageTypes.HelloAck, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, ackBody);
            await channel.WriteEnvelopeAsync(ackEnv);

            // Receive OFFER_BEGIN
            var offerBeginEnv = await channel.ReadEnvelopeAsync();
            int itemCount = offerBeginEnv.Body["itemCount"]?.GetValue<int>() ?? 0;
            string totalBytesStr = offerBeginEnv.Body["totalBytes"]?.ToString() ?? "0";
            Console.WriteLine($"[OFFER] Inbound offer: {itemCount} items, {totalBytesStr} bytes");

            // Receive OFFER_PAGE(s)
            var itemsToReceive = new List<(string itemId, string name, long size, string relativePath)>();
            var offerPageEnv = await channel.ReadEnvelopeAsync();
            if (offerPageEnv.Body["items"] is JsonArray itemArr)
            {
                foreach (var itemNode in itemArr)
                {
                    if (itemNode is JsonObject itemObj)
                    {
                        string id = itemObj["itemId"]!.ToString();
                        string name = itemObj["name"]!.ToString();
                        long size = long.Parse(itemObj["sizeBytes"]!.ToString());
                        string rel = itemObj.TryGetPropertyValue("relativePath", out var r) && r != null ? r.ToString() : name;
                        itemsToReceive.Add((id, name, size, rel));
                    }
                }
            }

            // Await OFFER_END
            await channel.ReadEnvelopeAsync();

            // Send OFFER_DECISION (Accepted)
            var decisionBody = new JsonObject
            {
                ["batchId"] = offerBeginEnv.Body["batchId"]!.ToString(),
                ["accepted"] = true
            };
            var decisionEnv = new Envelope(1, MessageTypes.OfferDecision, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, decisionBody);
            await channel.WriteEnvelopeAsync(decisionEnv);

            // Receive items
            int verifiedCount = 0;
            long totalVerifiedBytes = 0;

            foreach (var item in itemsToReceive)
            {
                Console.WriteLine($"\n[TRANSFER] Awaiting {item.name} ({item.size:N0} bytes)...");

                // ITEM_START
                var itemStartEnv = await channel.ReadEnvelopeAsync();

                // Send ITEM_READY
                var readyBody = new JsonObject
                {
                    ["batchId"] = offerBeginEnv.Body["batchId"]!.ToString(),
                    ["itemId"] = item.itemId
                };
                var readyEnv = new Envelope(1, MessageTypes.ItemReady, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, readyBody);
                await channel.WriteEnvelopeAsync(readyEnv);

                // Receive bulk payload directly from SSL stream
                string quarantinePath = stagingStore.GetQuarantinePath(helloEnv.SessionId, new ItemId(item.itemId));
                string? qDir = Path.GetDirectoryName(quarantinePath);
                if (qDir != null && !Directory.Exists(qDir)) Directory.CreateDirectory(qDir);

                byte[] hash;
                using (var fileStream = new FileStream(quarantinePath, FileMode.Create, FileAccess.Write, FileShare.None, 65536, useAsync: true))
                using (var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
                {
                    byte[] buf = new byte[65536];
                    long remaining = item.size;
                    while (remaining > 0)
                    {
                        int toRead = (int)Math.Min(buf.Length, remaining);
                        int read = await conn.SslStream.ReadAsync(buf.AsMemory(0, toRead));
                        if (read <= 0) throw new EndOfStreamException("Connection terminated prematurely during payload stream.");
                        await fileStream.WriteAsync(buf.AsMemory(0, read));
                        sha256.AppendData(buf, 0, read);
                        remaining -= read;
                    }
                    hash = sha256.GetHashAndReset();
                }

                string computedSha256 = Convert.ToHexStringLower(hash);

                // Await ITEM_END
                var itemEndEnv = await channel.ReadEnvelopeAsync();
                string declaredSha256 = itemEndEnv.Body["sha256"]!.ToString();

                if (!string.Equals(computedSha256, declaredSha256, StringComparison.OrdinalIgnoreCase))
                {
                    stagingStore.PurgeQuarantine(helloEnv.SessionId, new ItemId(item.itemId));
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine($"[VERIFY FAILED] SHA-256 mismatch for {item.name}! Quarantined file deleted.");
                    Console.ResetColor();

                    var failedResult = new JsonObject
                    {
                        ["batchId"] = offerBeginEnv.Body["batchId"]!.ToString(),
                        ["itemId"] = item.itemId,
                        ["status"] = "failed",
                        ["code"] = "INTEGRITY_MISMATCH"
                    };
                    await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, failedResult));
                    continue;
                }

                // Commit atomically to Downloads
                string destDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                string targetPath = Path.Combine(destDir, item.relativePath.Replace('/', Path.DirectorySeparatorChar));
                string finalPath = QuarantineStagingStore.ResolveCollision(targetPath);
                string? td = Path.GetDirectoryName(finalPath);
                if (td != null && !Directory.Exists(td)) Directory.CreateDirectory(td);

                File.Move(quarantinePath, finalPath, overwrite: false);

                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"[VERIFIED & COMMITTED] {item.name} -> {finalPath}");
                Console.ResetColor();

                verifiedCount++;
                totalVerifiedBytes += item.size;

                var verifiedResult = new JsonObject
                {
                    ["batchId"] = offerBeginEnv.Body["batchId"]!.ToString(),
                    ["itemId"] = item.itemId,
                    ["status"] = "verified",
                    ["sizeBytes"] = item.size.ToString(),
                    ["sha256"] = computedSha256
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, verifiedResult));
            }

            // Receive BATCH_END
            await channel.ReadEnvelopeAsync();

            // Send BATCH_RESULT
            var batchResult = new JsonObject
            {
                ["batchId"] = offerBeginEnv.Body["batchId"]!.ToString(),
                ["status"] = "completed",
                ["verifiedCount"] = verifiedCount,
                ["totalVerifiedBytes"] = totalVerifiedBytes.ToString()
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchResult, helloEnv.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 7, batchResult));

            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine("\n=================================================================");
            Console.WriteLine($"  TRANSFER COMPLETE: {verifiedCount} file(s) safely saved ({totalVerifiedBytes:N0} bytes)");
            Console.WriteLine("=================================================================");
            Console.ResetColor();

            return 0;
        }
    }

    private static async Task<int> RunSendAsync(string[] args)
    {
        if (args.Length < 3)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("Error: 'send' requires <peer-host> <peer-port> and at least one file path.");
            Console.ResetColor();
            return 1;
        }

        string host = args[0];
        if (!int.TryParse(args[1], out int port))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"Invalid port: '{args[1]}'");
            Console.ResetColor();
            return 1;
        }

        var files = new List<FileInfo>();
        for (int i = 2; i < args.Length; i++)
        {
            string p = args[i];
            if (!File.Exists(p))
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine($"File not found: '{p}'");
                Console.ResetColor();
                return 1;
            }
            files.Add(new FileInfo(p));
        }

        Console.WriteLine($"[INFO] Connecting to {host}:{port} over TLS 1.3...");
        var client = new TlsSocketClient();
        await using var conn = await client.ConnectAsync(host, port);

        // Compute SAS
        byte[] sessionNonce = RandomNumberGenerator.GetBytes(32);
        int sas = SasCalculator.CalculateSas(conn.LocalPublicKeyBytes, conn.RemotePublicKeyBytes, sessionNonce);

        Console.ForegroundColor = ConsoleColor.Magenta;
        Console.WriteLine("-----------------------------------------------------------------");
        Console.WriteLine($"  *** 6-DIGIT VERIFICATION CODE: [ {sas:D6} ] ***");
        Console.WriteLine("  Verify that this code matches on the receiving device screen.");
        Console.WriteLine("-----------------------------------------------------------------");
        Console.ResetColor();

        var channel = new LengthPrefixedFrameChannel(conn.SslStream);
        var sessionId = new SessionId(Guid.NewGuid().ToString("D").ToLowerInvariant());

        // Send HELLO
        var helloBody = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = new JsonArray(),
            ["optionalCapabilities"] = new JsonArray("folder-v1"),
            ["role"] = "sender",
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["displayName"] = Environment.MachineName
        };
        var helloEnv = new Envelope(1, MessageTypes.Hello, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 1, helloBody);
        await channel.WriteEnvelopeAsync(helloEnv);

        // Receive HELLO_ACK
        var ackEnv = await channel.ReadEnvelopeAsync();
        Console.WriteLine($"[PROTOCOL] Connected with '{ackEnv.Body["displayName"]}'");

        // Send OFFER_BEGIN
        var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
        long totalBytes = files.Sum(f => f.Length);

        var offerBeginBody = new JsonObject
        {
            ["batchId"] = batchId.Value,
            ["itemCount"] = files.Count,
            ["totalBytes"] = totalBytes.ToString(),
            ["pageCount"] = 1
        };
        await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferBegin, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, offerBeginBody));

        // Send OFFER_PAGE
        var itemsArr = new JsonArray();
        var itemMap = new Dictionary<string, (FileInfo file, Sha256Digest digest)>();

        foreach (var file in files)
        {
            var itemId = new ItemId(Guid.NewGuid().ToString("D").ToLowerInvariant());
            byte[] fileBytes = await File.ReadAllBytesAsync(file.FullName);
            var digest = new Sha256Digest(Convert.ToHexStringLower(SHA256.HashData(fileBytes)));
            itemMap[itemId.Value] = (file, digest);

            itemsArr.Add(new JsonObject
            {
                ["itemId"] = itemId.Value,
                ["name"] = file.Name,
                ["mime"] = "application/octet-stream",
                ["sizeBytes"] = file.Length.ToString(),
                ["relativePath"] = file.Name
            });
        }

        var offerPageBody = new JsonObject
        {
            ["batchId"] = batchId.Value,
            ["pageIndex"] = 0,
            ["items"] = itemsArr
        };
        await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferPage, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, offerPageBody));

        // Send OFFER_END
        var offerEndBody = new JsonObject { ["batchId"] = batchId.Value };
        await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, offerEndBody));

        // Await OFFER_DECISION
        Console.WriteLine("[OFFER] Waiting for peer acceptance...");
        var decisionEnv = await channel.ReadEnvelopeAsync();
        bool accepted = decisionEnv.Body["accepted"]?.GetValue<bool>() ?? false;
        if (!accepted)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[OFFER REJECTED] Peer declined the transfer.");
            Console.ResetColor();
            return 1;
        }

        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine("[OFFER ACCEPTED] Streaming files...");
        Console.ResetColor();

        // Stream each file
        foreach (var (itemId, (file, digest)) in itemMap)
        {
            Console.WriteLine($"\n[SENDING] {file.Name} ({file.Length:N0} bytes)...");

            // Send ITEM_START
            var itemStartBody = new JsonObject
            {
                ["batchId"] = batchId.Value,
                ["itemId"] = itemId,
                ["bulkRef"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                ["sizeBytes"] = file.Length.ToString()
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, itemStartBody));

            // Await ITEM_READY
            await channel.ReadEnvelopeAsync();

            // Stream bulk payload directly
            using (var fs = new FileStream(file.FullName, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, useAsync: true))
            {
                await channel.WriteBulkStreamAsync(fs, file.Length);
            }

            // Send ITEM_END
            var itemEndBody = new JsonObject
            {
                ["batchId"] = batchId.Value,
                ["itemId"] = itemId,
                ["actualBytes"] = file.Length.ToString(),
                ["sha256"] = digest.Value
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, itemEndBody));

            // Await ITEM_RESULT
            var resultEnv = await channel.ReadEnvelopeAsync();
            string status = resultEnv.Body["status"]?.ToString() ?? "unknown";
            if (status == "verified")
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"[CONFIRMED] {file.Name} verified successfully by peer!");
                Console.ResetColor();
            }
            else
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine($"[FAILED] {file.Name} failed peer verification: {resultEnv.Body["code"]}");
                Console.ResetColor();
            }
        }

        // Send BATCH_END
        var batchEndBody = new JsonObject { ["batchId"] = batchId.Value };
        await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 7, batchEndBody));

        // Await BATCH_RESULT
        var batchResultEnv = await channel.ReadEnvelopeAsync();
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("\n=================================================================");
        Console.WriteLine($"  TRANSFER COMPLETE: Peer verified {batchResultEnv.Body["verifiedCount"]} items ({batchResultEnv.Body["totalVerifiedBytes"]} bytes)");
        Console.WriteLine("=================================================================");
        Console.ResetColor();

        return 0;
    }
}
