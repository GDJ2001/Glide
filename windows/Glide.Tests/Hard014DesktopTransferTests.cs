using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class Hard014DesktopTransferTests : IDisposable
{
    private readonly string _root =
        Path.Combine(
            Path.GetTempPath(),
            "glide-hard014-transfer-" + Guid.NewGuid().ToString("N"));

    public Hard014DesktopTransferTests()
    {
        Directory.CreateDirectory(_root);
    }

    [Fact]
    public async Task SharedEngine_TenMiBFile_StreamsAndCommitsExactDigest()
    {
        const int totalBytes = 10 * 1024 * 1024;
        string sourceDirectory =
            Path.Combine(_root, "ten-mib-source");
        string destinationDirectory =
            Path.Combine(_root, "ten-mib-destination");
        string stagingDirectory =
            Path.Combine(_root, "ten-mib-staging");

        Directory.CreateDirectory(sourceDirectory);

        string sourcePath =
            Path.Combine(sourceDirectory, "ten-mib.bin");
        Sha256Digest sourceDigest =
            await WritePatternFileAsync(
                sourcePath,
                totalBytes);

        using var listener =
            new TlsSocketListener(0);
        LanInvitation invitation =
            CreateInvitation(listener);

        var store =
            new QuarantineStagingStore(
                stagingDirectory,
                destinationDirectory,
                () => 64L * 1024 * 1024);

        var receiverInteraction =
            new AcceptingInteraction();
        var senderInteraction =
            new AcceptingInteraction();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine().ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    store,
                    new LanInvitationAuthority(invitation),
                    "HARD-014 Receiver"),
                receiverInteraction);
        });

        DesktopTransferResult senderResult =
            await new DesktopTransferEngine().SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [new DesktopTransferFile(sourcePath)],
                    invitation,
                    "HARD-014 Sender"),
                senderInteraction);

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(
                TimeSpan.FromSeconds(30));

        Assert.True(senderResult.Success, senderResult.Message);
        Assert.True(receiverResult.Success, receiverResult.Message);
        Assert.Equal(totalBytes, senderResult.VerifiedBytes);
        Assert.Equal(totalBytes, receiverResult.VerifiedBytes);

        string committedPath =
            Path.Combine(
                destinationDirectory,
                "ten-mib.bin");
        Assert.True(File.Exists(committedPath));
        Assert.Equal(
            totalBytes,
            new FileInfo(committedPath).Length);

        Sha256Digest committedDigest =
            await HashFileAsync(committedPath);

        Assert.Equal(
            sourceDigest,
            committedDigest);
        Assert.Equal(0, store.ReservedBytes);
        Assert.Empty(
            Directory.Exists(stagingDirectory)
                ? Directory.GetFiles(
                    stagingDirectory,
                    "*",
                    SearchOption.AllDirectories)
                : Array.Empty<string>());
    }

    [Fact]
    public async Task Receiver_InsufficientBatchReservation_RejectsBeforeAnyItemTransfer()
    {
        string sourceDirectory =
            Path.Combine(_root, "disk-full-source");
        string destinationDirectory =
            Path.Combine(_root, "disk-full-destination");
        string stagingDirectory =
            Path.Combine(_root, "disk-full-staging");

        Directory.CreateDirectory(sourceDirectory);

        string sourcePath =
            Path.Combine(sourceDirectory, "too-large.bin");
        await using (var source = new FileStream(
            sourcePath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None))
        {
            source.SetLength(2 * 1024 * 1024);
        }

        using var listener =
            new TlsSocketListener(0);
        LanInvitation invitation =
            CreateInvitation(listener);

        var store =
            new QuarantineStagingStore(
                stagingDirectory,
                destinationDirectory,
                () => 1024 * 1024);

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine().ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    store,
                    new LanInvitationAuthority(invitation),
                    "Disk Full Receiver"),
                new AcceptingInteraction());
        });

        DesktopTransferResult senderResult =
            await new DesktopTransferEngine().SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [new DesktopTransferFile(sourcePath)],
                    invitation,
                    "Disk Full Sender"),
                new AcceptingInteraction());

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(
                TimeSpan.FromSeconds(10));

        Assert.True(senderResult.Rejected, senderResult.Message);
        Assert.True(receiverResult.Rejected, receiverResult.Message);
        Assert.Contains(
            WireErrorCodes.InsufficientStorage,
            receiverResult.Message);
        Assert.Equal(0, store.ReservedBytes);
        Assert.Empty(
            Directory.Exists(destinationDirectory)
                ? Directory.GetFiles(
                    destinationDirectory,
                    "*",
                    SearchOption.AllDirectories)
                : Array.Empty<string>());
    }

    [Fact]
    public async Task Sender_SourceSizeChangedAfterOffer_FailsBeforePayloadStreaming()
    {
        string sourcePath =
            Path.Combine(_root, "mutating-source.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            new byte[4096]);

        using var listener =
            new TlsSocketListener(0);
        LanInvitation invitation =
            CreateInvitation(listener);

        string? offeredItemId = null;

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            using var channel =
                new LanRecordChannel(
                    connection.SslStream);

            LanBootstrapMessage bootstrap =
                await AcceptBootstrapAsync(channel);

            Envelope hello =
                await channel.ReadEnvelopeAsync();
            Assert.Equal(1, hello.Seq);

            await channel.WriteEnvelopeAsync(
                CreateHelloAck(
                    bootstrap.SessionId,
                    1));

            Envelope offerBegin =
                await channel.ReadEnvelopeAsync();
            Envelope offerPage =
                await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // OFFER_END

            string batchId =
                offerBegin.Body["batchId"]!.ToString();
            JsonArray items =
                Assert.IsType<JsonArray>(
                    offerPage.Body["items"]);
            JsonObject firstItem =
                Assert.IsType<JsonObject>(
                    items[0]);
            offeredItemId =
                firstItem["itemId"]!.ToString();

            // Mutate the source after its size has already been offered but before ITEM_READY.
            await using (var mutate = new FileStream(
                sourcePath,
                FileMode.Open,
                FileAccess.Write,
                FileShare.ReadWrite))
            {
                mutate.SetLength(1);
            }

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.OfferDecision,
                    new SessionId(bootstrap.SessionId),
                    NewMessageId(),
                    2,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["accepted"] = true
                    }));

            Envelope itemStart =
                await channel.ReadEnvelopeAsync();
            Assert.Equal(
                offeredItemId,
                itemStart.Body["itemId"]!.ToString());

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemReady,
                    new SessionId(bootstrap.SessionId),
                    NewMessageId(),
                    3,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = offeredItemId
                    }));
        });

        DesktopTransferResult senderResult =
            await new DesktopTransferEngine().SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [new DesktopTransferFile(sourcePath)],
                    invitation,
                    "Mutating Sender"),
                new AcceptingInteraction());

        await receiverTask.WaitAsync(
            TimeSpan.FromSeconds(10));

        Assert.False(senderResult.Success);
        Assert.True(
            senderResult.Message.Contains(
                "changed size",
                StringComparison.OrdinalIgnoreCase),
            senderResult.Message);
    }

    private static async Task<Sha256Digest> WritePatternFileAsync(
        string path,
        int sizeBytes)
    {
        byte[] block =
            new byte[64 * 1024];
        RandomNumberGenerator.Fill(block);

        using var digest =
            new StreamingSha256();

        await using var output =
            new FileStream(
                path,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                64 * 1024,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan);

        int remaining = sizeBytes;
        while (remaining > 0)
        {
            int take =
                Math.Min(
                    remaining,
                    block.Length);

            await output.WriteAsync(
                block.AsMemory(0, take));
            digest.Append(
                block.AsSpan(0, take));
            remaining -= take;
        }

        await output.FlushAsync();
        return digest.Complete();
    }

    private static async Task<Sha256Digest> HashFileAsync(
        string path)
    {
        using var digest =
            new StreamingSha256();

        byte[] buffer =
            new byte[64 * 1024];

        await using var input =
            new FileStream(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                buffer.Length,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan);

        int read;
        while ((read = await input.ReadAsync(buffer)) > 0)
        {
            digest.Append(
                buffer.AsSpan(0, read));
        }

        return digest.Complete();
    }

    private static async Task<LanBootstrapMessage> AcceptBootstrapAsync(
        LanRecordChannel channel)
    {
        LanRecord? record =
            await channel.ReadRecordAsync();
        var bootstrapRecord =
            Assert.IsType<LanRecord.Bootstrap>(
                record);

        LanBootstrapMessage bootstrap =
            LanBootstrapCodec.DecodeBootstrap(
                bootstrapRecord.JsonBytes);

        await channel.WriteBootstrapResultAsync(
            LanBootstrapResult.Accepted(
                bootstrap.SessionId));

        return bootstrap;
    }

    private static Envelope CreateHelloAck(
        string sessionId,
        long seq)
    {
        var limits =
            new JsonObject
            {
                ["maxItems"] = 1000,
                ["maxItemBytes"] =
                    ProtocolCatalog.MaxItemBytes.ToString(),
                ["maxBatchBytes"] =
                    ProtocolCatalog.MaxBatchBytes.ToString(),
                ["maxControlBytes"] =
                    ProtocolCatalog.MaxControlBytes
            };

        return new Envelope(
            1,
            MessageTypes.HelloAck,
            new SessionId(sessionId),
            NewMessageId(),
            seq,
            new JsonObject
            {
                ["selectedMajor"] = 1,
                ["capabilities"] =
                    new JsonArray(
                        "stream-v1",
                        "sha256"),
                ["ephemeralPeerId"] =
                    NewUuid(),
                ["displayName"] =
                    "HARD-014 Test Receiver",
                ["limits"] = limits
            });
    }

    private static LanInvitation CreateInvitation(
        TlsSocketListener listener)
    {
        string token =
            Convert.ToBase64String(
                    RandomNumberGenerator.GetBytes(32))
                .TrimEnd('=')
                .Replace('+', '-')
                .Replace('/', '_');

        var draft =
            new LanInvitation(
                invitationId: NewUuid(),
                presence: Convert.ToHexString(
                        RandomNumberGenerator.GetBytes(16))
                    .ToLowerInvariant(),
                name: "HARD-014 Receiver",
                platform: "windows",
                endpoints:
                [
                    new LanEndpoint(
                        "127.0.0.1",
                        listener.BoundPort)
                ],
                spkiSha256:
                    listener.LocalSpkiSha256,
                token: token,
                expiresAt:
                    DateTimeOffset.UtcNow
                        .AddMinutes(2)
                        .ToUnixTimeSeconds()
                        .ToString(),
                ttlSeconds: 120);

        return InvitationCodec.Decode(
            InvitationCodec.Encode(draft));
    }

    private static MessageId NewMessageId() =>
        new(NewUuid());

    private static string NewUuid() =>
        Guid.NewGuid()
            .ToString("D")
            .ToLowerInvariant();

    private sealed class AcceptingInteraction :
        DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken) =>
            Task.FromResult(true);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(
                    _root,
                    recursive: true);
            }
        }
        catch
        {
            // Best-effort test cleanup.
        }
    }
}
