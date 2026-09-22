using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Xunit;
using ProtocolViolationException = Glide.Core.Models.ProtocolViolationException;

namespace Glide.Tests;

public sealed class LanProfile1TransferIntegrationTests : IDisposable
{
    private readonly string _testSandboxDir;
    private readonly string _testDestDir;

    public LanProfile1TransferIntegrationTests()
    {
        string baseDir = Path.Combine(Path.GetTempPath(), "GlideTests", Guid.NewGuid().ToString("N"));
        _testSandboxDir = Path.Combine(baseDir, "staging");
        _testDestDir = Path.Combine(baseDir, "destination");
        Directory.CreateDirectory(_testSandboxDir);
        Directory.CreateDirectory(_testDestDir);
    }

    public void Dispose()
    {
        try
        {
            string? parent = Path.GetDirectoryName(_testSandboxDir);
            if (parent != null && Directory.Exists(parent))
            {
                Directory.Delete(parent, recursive: true);
            }
        }
        catch
        {
            // Ignore cleanup exceptions
        }
    }

    private static LanInvitation CreateTestInvitation(int port, string spkiSha256)
    {
        string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        string invitationId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        string presence = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        long expiresAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 300;

        var invitation = new LanInvitation(
            invitationId: invitationId,
            presence: presence,
            name: "Test-Server",
            platform: "windows",
            endpoints: [new LanEndpoint("127.0.0.1", port)],
            spkiSha256: spkiSha256,
            token: token,
            expiresAt: expiresAt.ToString(),
            ttlSeconds: 300);

        // Encode and Decode to populate invitationHash
        string encoded = InvitationCodec.Encode(invitation);
        return InvitationCodec.Decode(encoded);
    }

    private static Envelope CreateHello(SessionId sessionId, string displayName = "Client-Sender")
    {
        var body = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = new JsonArray(),
            ["role"] = "sender",
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["displayName"] = displayName
        };
        return new Envelope(1, MessageTypes.Hello, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 1, body);
    }

    private static Envelope CreateHelloAck(SessionId sessionId, string displayName = "Server-Receiver")
    {
        var limits = new JsonObject
        {
            ["maxItems"] = 1000,
            ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
            ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
            ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
        };
        var body = new JsonObject
        {
            ["selectedMajor"] = 1,
            ["capabilities"] = new JsonArray(ProtocolCapabilities.StreamV1, ProtocolCapabilities.Sha256),
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["displayName"] = displayName,
            ["limits"] = limits
        };
        return new Envelope(1, MessageTypes.HelloAck, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, body);
    }

    [Fact]
    public async Task Transfer_WithQrInvitationAndSpkiPinning_FullBatch_Succeeds()
    {
        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;
        var invitation = CreateTestInvitation(port, listener.LocalSpkiSha256);

        byte[] testData1 = Encoding.UTF8.GetBytes("Hello, cross-platform LAN Profile 1 transfer! " + new string('A', 80000));
        byte[] testData2 = Encoding.UTF8.GetBytes("Second file in batch: short text file.");
        string sha1 = Convert.ToHexStringLower(SHA256.HashData(testData1));
        string sha2 = Convert.ToHexStringLower(SHA256.HashData(testData2));

        var filesToTransfer = new List<(string name, byte[] data, string sha256)>
        {
            ("large_text.txt", testData1, sha1),
            ("notes.txt", testData2, sha2)
        };

        var serverTask = Task.Run(async () =>
        {
            await using var conn = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(conn.SslStream);

            // 1. BOOTSTRAP
            var bootstrap = await channel.ReadBootstrapAsync();
            Assert.Equal(invitation.InvitationId, bootstrap.InvitationId);
            Assert.Equal(invitation.Token, bootstrap.Token);
            Assert.Equal(invitation.InvitationHash, bootstrap.InvitationHash);

            await channel.WriteBootstrapResultAsync(LanBootstrapResult.Accepted(bootstrap.SessionId));

            // 2. HELLO
            var hello = await channel.ReadEnvelopeAsync();
            Assert.Equal(MessageTypes.Hello, hello.Type);

            await channel.WriteEnvelopeAsync(CreateHelloAck(hello.SessionId, "Server-Device"));

            // 3. OFFER
            var offerBegin = await channel.ReadEnvelopeAsync();
            var offerPage = await channel.ReadEnvelopeAsync();
            var offerEnd = await channel.ReadEnvelopeAsync();

            var decisionBody = new JsonObject
            {
                ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                ["accepted"] = true
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferDecision, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, decisionBody));

            // 4. File items
            foreach (var (expectedName, expectedData, expectedSha) in filesToTransfer)
            {
                var itemStart = await channel.ReadEnvelopeAsync();
                string itemId = itemStart.Body["itemId"]!.ToString();
                string bulkRef = itemStart.Body["bulkRef"]!.ToString();

                var readyBody = new JsonObject
                {
                    ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                    ["itemId"] = itemId
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemReady, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, readyBody));

                string qPath = Path.Combine(_testSandboxDir, $"{itemId}.quarantine");
                using var fs = new FileStream(qPath, FileMode.Create, FileAccess.Write, FileShare.None, 65536);
                using var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                long receivedBytes = 0;

                while (true)
                {
                    var rec = await channel.ReadRecordAsync();
                    Assert.NotNull(rec);

                    if (rec is LanRecord.Data dataRec)
                    {
                        Assert.Equal(bulkRef, dataRec.BulkRef.Value);
                        fs.Write(dataRec.Chunk);
                        sha256.AppendData(dataRec.Chunk);
                        receivedBytes += dataRec.Chunk.Length;
                    }
                    else if (rec is LanRecord.StreamEnd streamEndRec)
                    {
                        Assert.Equal(bulkRef, streamEndRec.BulkRef.Value);
                        Assert.Equal(expectedData.Length, streamEndRec.ActualBytes);
                        break;
                    }
                }

                fs.Close();
                string computedSha = Convert.ToHexStringLower(sha256.GetHashAndReset());

                var itemEnd = await channel.ReadEnvelopeAsync();
                Assert.Equal(expectedSha, itemEnd.Body["sha256"]!.ToString());
                Assert.Equal(expectedSha, computedSha);

                // Commit to destination
                string destFile = Path.Combine(_testDestDir, expectedName);
                File.Move(qPath, destFile, overwrite: true);

                var itemResult = new JsonObject
                {
                    ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                    ["itemId"] = itemId,
                    ["status"] = "verified",
                    ["sizeBytes"] = expectedData.Length.ToString(),
                    ["sha256"] = computedSha
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, itemResult));
            }

            var batchEnd = await channel.ReadEnvelopeAsync();
            var batchResult = new JsonObject
            {
                ["batchId"] = offerBegin.Body["batchId"]!.ToString(),
                ["status"] = "completed",
                ["verifiedCount"] = filesToTransfer.Count,
                ["totalVerifiedBytes"] = filesToTransfer.Sum(f => (long)f.data.Length).ToString()
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchResult, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, batchResult));
        });

        var clientTask = Task.Run(async () =>
        {
            var client = new TlsSocketClient();
            await using var conn = await client.ConnectAsync("127.0.0.1", port, expectedSpkiSha256: invitation.SpkiSha256);
            using var channel = new LanRecordChannel(conn.SslStream);

            var sessionId = new SessionId(Guid.NewGuid().ToString("D").ToLowerInvariant());

            // 1. BOOTSTRAP
            var bootstrapMsg = new LanBootstrapMessage(
                invitationId: invitation.InvitationId,
                token: invitation.Token,
                invitationHash: invitation.InvitationHash,
                sessionId: sessionId.Value,
                role: LanBootstrapMessage.RoleSender);
            await channel.WriteBootstrapAsync(bootstrapMsg);

            var bootstrapResult = await channel.ReadBootstrapResultAsync();
            Assert.True(bootstrapResult.IsAccepted);

            // 2. HELLO
            await channel.WriteEnvelopeAsync(CreateHello(sessionId, "Client-Sender"));
            var ack = await channel.ReadEnvelopeAsync();
            Assert.Equal(MessageTypes.HelloAck, ack.Type);

            // 3. OFFER
            var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
            var offerBegin = new JsonObject
            {
                ["batchId"] = batchId.Value,
                ["itemCount"] = filesToTransfer.Count,
                ["totalBytes"] = filesToTransfer.Sum(f => (long)f.data.Length).ToString(),
                ["pageCount"] = 1
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferBegin, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, offerBegin));

            var itemsArr = new JsonArray();
            var itemMap = new List<(string itemId, string name, byte[] data, string sha256)>();
            foreach (var (name, data, sha) in filesToTransfer)
            {
                string iId = Guid.NewGuid().ToString("D").ToLowerInvariant();
                itemMap.Add((iId, name, data, sha));
                itemsArr.Add(new JsonObject
                {
                    ["itemId"] = iId,
                    ["name"] = name,
                    ["mime"] = "application/octet-stream",
                    ["sizeBytes"] = data.Length.ToString(),
                    ["relativePath"] = name
                });
            }

            var offerPage = new JsonObject
            {
                ["batchId"] = batchId.Value,
                ["pageIndex"] = 0,
                ["items"] = itemsArr
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferPage, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, offerPage));
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, new JsonObject { ["batchId"] = batchId.Value }));

            var decision = await channel.ReadEnvelopeAsync();
            Assert.True(decision.Body["accepted"]?.GetValue<bool>());

            // 4. File items
            foreach (var (itemId, name, data, sha) in itemMap)
            {
                string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();
                var startBody = new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemId"] = itemId,
                    ["bulkRef"] = bulkRef,
                    ["sizeBytes"] = data.Length.ToString()
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, startBody));

                var ready = await channel.ReadEnvelopeAsync();
                Assert.Equal(MessageTypes.ItemReady, ready.Type);

                // Stream chunks via LanRecord.Data (64 KiB chunks)
                int offset = 0;
                while (offset < data.Length)
                {
                    int chunkSize = Math.Min(65520, data.Length - offset);
                    await channel.WriteDataChunkAsync(bulkRef, data.AsMemory(offset, chunkSize));
                    offset += chunkSize;
                }

                // Send STREAM_END
                await channel.WriteStreamEndAsync(bulkRef, data.Length);

                // Send ITEM_END
                var endBody = new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemId"] = itemId,
                    ["actualBytes"] = data.Length.ToString(),
                    ["sha256"] = sha
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, endBody));

                var res = await channel.ReadEnvelopeAsync();
                Assert.Equal("verified", res.Body["status"]?.ToString());
            }

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 7, new JsonObject { ["batchId"] = batchId.Value }));
            var batchRes = await channel.ReadEnvelopeAsync();
            Assert.Equal("completed", batchRes.Body["status"]?.ToString());
        });

        await Task.WhenAll(serverTask, clientTask);

        // Verify committed files on disk
        foreach (var (name, data, _) in filesToTransfer)
        {
            string committedPath = Path.Combine(_testDestDir, name);
            Assert.True(File.Exists(committedPath), $"Expected committed file {name} to exist");
            byte[] readBytes = await File.ReadAllBytesAsync(committedPath);
            Assert.Equal(data, readBytes);
        }
    }

    [Fact]
    public async Task Transfer_WithWrongSpkiPin_AbortsAtTlsHandshake()
    {
        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;

        var serverTask = Task.Run(async () =>
        {
            try
            {
                await using var conn = await listener.AcceptConnectionAsync();
            }
            catch
            {
                // Expected TLS handshake failure on server side
            }
        });

        var client = new TlsSocketClient();
        string wrongPin = new string('0', 64); // Mismatched SPKI hash

        await Assert.ThrowsAsync<AuthenticationException>(async () =>
        {
            await client.ConnectAsync("127.0.0.1", port, expectedSpkiSha256: wrongPin);
        });

        await serverTask;
    }

    [Fact]
    public async Task Transfer_ZeroByteFile_HandlesStreamEndAndVerifies()
    {
        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;
        var invitation = CreateTestInvitation(port, listener.LocalSpkiSha256);

        string emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
        string itemId = Guid.NewGuid().ToString("D").ToLowerInvariant();

        var serverTask = Task.Run(async () =>
        {
            await using var conn = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(conn.SslStream);

            var bootstrap = await channel.ReadBootstrapAsync();
            await channel.WriteBootstrapResultAsync(LanBootstrapResult.Accepted(bootstrap.SessionId));

            var hello = await channel.ReadEnvelopeAsync();
            await channel.WriteEnvelopeAsync(CreateHelloAck(hello.SessionId));

            var offerBegin = await channel.ReadEnvelopeAsync();
            var offerPage = await channel.ReadEnvelopeAsync();
            var offerEnd = await channel.ReadEnvelopeAsync();

            var decBody = new JsonObject { ["batchId"] = batchId.Value, ["accepted"] = true };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferDecision, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, decBody));

            var itemStart = await channel.ReadEnvelopeAsync();
            string bulkRef = itemStart.Body["bulkRef"]!.ToString();

            var readyBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemReady, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, readyBody));

            // For zero byte file, only STREAM_END is transmitted (actualBytes = 0)
            var rec = await channel.ReadRecordAsync();
            Assert.IsType<LanRecord.StreamEnd>(rec);
            var streamEnd = (LanRecord.StreamEnd)rec!;
            Assert.Equal(0, streamEnd.ActualBytes);
            Assert.Equal(bulkRef, streamEnd.BulkRef.Value);

            var itemEnd = await channel.ReadEnvelopeAsync();
            Assert.Equal(emptySha, itemEnd.Body["sha256"]!.ToString());

            string destFile = Path.Combine(_testDestDir, "empty.dat");
            File.WriteAllBytes(destFile, Array.Empty<byte>());

            var resBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId, ["status"] = "verified", ["sizeBytes"] = "0", ["sha256"] = emptySha };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, resBody));

            await channel.ReadEnvelopeAsync(); // BATCH_END
            var bResBody = new JsonObject { ["batchId"] = batchId.Value, ["status"] = "completed", ["verifiedCount"] = 1, ["totalVerifiedBytes"] = "0" };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchResult, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, bResBody));
        });

        var clientTask = Task.Run(async () =>
        {
            var client = new TlsSocketClient();
            await using var conn = await client.ConnectAsync("127.0.0.1", port, expectedSpkiSha256: invitation.SpkiSha256);
            using var channel = new LanRecordChannel(conn.SslStream);

            var sessionId = new SessionId(Guid.NewGuid().ToString("D").ToLowerInvariant());
            await channel.WriteBootstrapAsync(new LanBootstrapMessage(invitation.InvitationId, invitation.Token, invitation.InvitationHash, sessionId.Value));
            var bRes = await channel.ReadBootstrapResultAsync();
            Assert.True(bRes.IsAccepted);

            await channel.WriteEnvelopeAsync(CreateHello(sessionId));
            await channel.ReadEnvelopeAsync();

            var oBegin = new JsonObject { ["batchId"] = batchId.Value, ["itemCount"] = 1, ["totalBytes"] = "0", ["pageCount"] = 1 };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferBegin, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, oBegin));

            var oPage = new JsonObject
            {
                ["batchId"] = batchId.Value,
                ["pageIndex"] = 0,
                ["items"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["itemId"] = itemId,
                        ["name"] = "empty.dat",
                        ["mime"] = "application/octet-stream",
                        ["sizeBytes"] = "0",
                        ["relativePath"] = "empty.dat"
                    }
                }
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferPage, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, oPage));
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, new JsonObject { ["batchId"] = batchId.Value }));
            await channel.ReadEnvelopeAsync();

            string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();
            var startBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId, ["bulkRef"] = bulkRef, ["sizeBytes"] = "0" };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, startBody));
            await channel.ReadEnvelopeAsync();

            // Zero bytes data, directly write STREAM_END with 0 bytes
            await channel.WriteStreamEndAsync(bulkRef, 0);

            var endBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId, ["actualBytes"] = "0", ["sha256"] = emptySha };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, endBody));
            var itemRes = await channel.ReadEnvelopeAsync();
            Assert.Equal("verified", itemRes.Body["status"]?.ToString());

            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.BatchEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 7, new JsonObject { ["batchId"] = batchId.Value }));
            await channel.ReadEnvelopeAsync();
        });

        await Task.WhenAll(serverTask, clientTask);

        string emptyFile = Path.Combine(_testDestDir, "empty.dat");
        Assert.True(File.Exists(emptyFile));
        Assert.Empty(File.ReadAllBytes(emptyFile));
    }

    [Fact]
    public async Task Transfer_CorruptedDataChunk_FailsVerificationAndPurgesQuarantine()
    {
        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;
        var invitation = CreateTestInvitation(port, listener.LocalSpkiSha256);

        byte[] originalBytes = Encoding.UTF8.GetBytes("Original authentic file data.");
        string originalSha = Convert.ToHexStringLower(SHA256.HashData(originalBytes));
        byte[] corruptedBytes = Encoding.UTF8.GetBytes("Corrupted tempered payload data.");

        var batchId = new BatchId(Guid.NewGuid().ToString("D").ToLowerInvariant());
        string itemId = Guid.NewGuid().ToString("D").ToLowerInvariant();

        var serverTask = Task.Run(async () =>
        {
            await using var conn = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(conn.SslStream);

            var bootstrap = await channel.ReadBootstrapAsync();
            await channel.WriteBootstrapResultAsync(LanBootstrapResult.Accepted(bootstrap.SessionId));

            var hello = await channel.ReadEnvelopeAsync();
            await channel.WriteEnvelopeAsync(CreateHelloAck(hello.SessionId));

            var offerBegin = await channel.ReadEnvelopeAsync();
            var offerPage = await channel.ReadEnvelopeAsync();
            var offerEnd = await channel.ReadEnvelopeAsync();

            var decBody = new JsonObject { ["batchId"] = batchId.Value, ["accepted"] = true };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferDecision, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, decBody));

            var itemStart = await channel.ReadEnvelopeAsync();
            string bulkRef = itemStart.Body["bulkRef"]!.ToString();

            var readyBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemReady, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, readyBody));

            string qPath = Path.Combine(_testSandboxDir, $"{itemId}.quarantine");
            using (var fs = new FileStream(qPath, FileMode.Create, FileAccess.Write))
            using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                var dataRec = (LanRecord.Data)(await channel.ReadRecordAsync())!;
                fs.Write(dataRec.Chunk);
                sha.AppendData(dataRec.Chunk);

                var streamEnd = (LanRecord.StreamEnd)(await channel.ReadRecordAsync())!;

                string computedSha = Convert.ToHexStringLower(sha.GetHashAndReset());

                var itemEnd = await channel.ReadEnvelopeAsync();
                string declaredSha = itemEnd.Body["sha256"]!.ToString();

                // Detect mismatch!
                Assert.NotEqual(declaredSha, computedSha);

                // Purge quarantine!
                fs.Close();
                if (File.Exists(qPath)) File.Delete(qPath);

                var failResult = new JsonObject
                {
                    ["batchId"] = batchId.Value,
                    ["itemId"] = itemId,
                    ["status"] = "failed",
                    ["code"] = "INTEGRITY_MISMATCH"
                };
                await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, hello.SessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, failResult));
            }

            Assert.False(File.Exists(qPath), "Quarantined file must be purged upon verification failure");
        });

        var clientTask = Task.Run(async () =>
        {
            var client = new TlsSocketClient();
            await using var conn = await client.ConnectAsync("127.0.0.1", port, expectedSpkiSha256: invitation.SpkiSha256);
            using var channel = new LanRecordChannel(conn.SslStream);

            var sessionId = new SessionId(Guid.NewGuid().ToString("D").ToLowerInvariant());
            await channel.WriteBootstrapAsync(new LanBootstrapMessage(invitation.InvitationId, invitation.Token, invitation.InvitationHash, sessionId.Value));
            await channel.ReadBootstrapResultAsync();

            await channel.WriteEnvelopeAsync(CreateHello(sessionId));
            await channel.ReadEnvelopeAsync();

            var oBegin = new JsonObject { ["batchId"] = batchId.Value, ["itemCount"] = 1, ["totalBytes"] = originalBytes.Length.ToString(), ["pageCount"] = 1 };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferBegin, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 2, oBegin));

            var oPage = new JsonObject
            {
                ["batchId"] = batchId.Value,
                ["pageIndex"] = 0,
                ["items"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["itemId"] = itemId,
                        ["name"] = "corrupt.dat",
                        ["mime"] = "application/octet-stream",
                        ["sizeBytes"] = originalBytes.Length.ToString(),
                        ["relativePath"] = "corrupt.dat"
                    }
                }
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferPage, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 3, oPage));
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 4, new JsonObject { ["batchId"] = batchId.Value }));
            await channel.ReadEnvelopeAsync();

            string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();
            var startBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId, ["bulkRef"] = bulkRef, ["sizeBytes"] = originalBytes.Length.ToString() };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemStart, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 5, startBody));
            await channel.ReadEnvelopeAsync();

            // Transmit CORRUPTED bytes instead of original!
            await channel.WriteDataChunkAsync(bulkRef, corruptedBytes);
            await channel.WriteStreamEndAsync(bulkRef, corruptedBytes.Length);

            // Send expected ORIGINAL SHA-256 in ITEM_END
            var endBody = new JsonObject { ["batchId"] = batchId.Value, ["itemId"] = itemId, ["actualBytes"] = originalBytes.Length.ToString(), ["sha256"] = originalSha };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemEnd, sessionId, new MessageId(Guid.NewGuid().ToString("D").ToLowerInvariant()), 6, endBody));

            var itemRes = await channel.ReadEnvelopeAsync();
            Assert.Equal("failed", itemRes.Body["status"]?.ToString());
            Assert.Equal("INTEGRITY_MISMATCH", itemRes.Body["code"]?.ToString());
        });

        await Task.WhenAll(serverTask, clientTask);
    }

    [Fact]
    public async Task Transfer_InvalidBootstrapToken_RejectsTransfer()
    {
        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;
        var invitation = CreateTestInvitation(port, listener.LocalSpkiSha256);

        var serverTask = Task.Run(async () =>
        {
            await using var conn = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(conn.SslStream);

            var bootstrap = await channel.ReadBootstrapAsync();
            bool matches = string.Equals(invitation.Token, bootstrap.Token, StringComparison.Ordinal);
            if (!matches)
            {
                await channel.WriteBootstrapResultAsync(LanBootstrapResult.Rejected(LanBootstrapFailureCode.AUTHENTICATION_FAILED));
            }
        });

        var clientTask = Task.Run(async () =>
        {
            var client = new TlsSocketClient();
            await using var conn = await client.ConnectAsync("127.0.0.1", port, expectedSpkiSha256: invitation.SpkiSha256);
            using var channel = new LanRecordChannel(conn.SslStream);

            string wrongToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var sessionId = new SessionId(Guid.NewGuid().ToString("D").ToLowerInvariant());

            // Send wrong token
            await channel.WriteBootstrapAsync(new LanBootstrapMessage(invitation.InvitationId, wrongToken, invitation.InvitationHash, sessionId.Value));

            var result = await channel.ReadBootstrapResultAsync();
            Assert.False(result.IsAccepted);
            Assert.Equal("AUTHENTICATION_FAILED", result.Code);
        });

        await Task.WhenAll(serverTask, clientTask);
    }
}
