using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class Hard013DesktopSessionTests : IDisposable
{
    private readonly string _root =
        Path.Combine(Path.GetTempPath(), "glide-hard013-" + Guid.NewGuid().ToString("N"));

    public Hard013DesktopSessionTests()
    {
        Directory.CreateDirectory(_root);
    }

    [Fact]
    public async Task Sender_MultiPageOffer_UsesContiguousPagesAndMonotonicSequence()
    {
        List<DesktopTransferFile> files = [];
        for (int i = 0; i < 33; i++)
        {
            string path = Path.Combine(_root, $"page-{i:D2}.bin");
            await File.WriteAllBytesAsync(path, Array.Empty<byte>());
            files.Add(new DesktopTransferFile(path));
        }

        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);
        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);
            LanBootstrapMessage bootstrap = await AcceptBootstrapAsync(channel);

            Envelope hello = await channel.ReadEnvelopeAsync();
            Assert.Equal(1, hello.Seq);
            await channel.WriteEnvelopeAsync(CreateHelloAck(bootstrap.SessionId, 1));

            Envelope begin = await channel.ReadEnvelopeAsync();
            Envelope page0 = await channel.ReadEnvelopeAsync();
            Envelope page1 = await channel.ReadEnvelopeAsync();
            Envelope end = await channel.ReadEnvelopeAsync();

            Assert.Equal(MessageTypes.OfferBegin, begin.Type);
            Assert.Equal(2, begin.Body["pageCount"]!.GetValue<int>());
            Assert.Equal(2, begin.Seq);
            Assert.Equal(MessageTypes.OfferPage, page0.Type);
            Assert.Equal(0, page0.Body["pageIndex"]!.GetValue<int>());
            Assert.Equal(3, page0.Seq);
            Assert.Equal(MessageTypes.OfferPage, page1.Type);
            Assert.Equal(1, page1.Body["pageIndex"]!.GetValue<int>());
            Assert.Equal(4, page1.Seq);
            Assert.Equal(MessageTypes.OfferEnd, end.Type);
            Assert.Equal(5, end.Seq);
            Assert.Equal(begin.Body["batchId"]!.ToString(), page0.Body["batchId"]!.ToString());
            Assert.Equal(begin.Body["batchId"]!.ToString(), page1.Body["batchId"]!.ToString());
            Assert.Equal(begin.Body["batchId"]!.ToString(), end.Body["batchId"]!.ToString());
        });

        var sender = new DesktopTransferEngine();
        DesktopTransferResult result = await sender.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                files,
                invitation,
                "HARD-013 Sender"),
            new PassiveInteraction());

        await serverTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(result.Success);
    }

    [Fact]
    public async Task Sender_MismatchedOfferDecisionBatch_FailsClosed()
    {
        string path = Path.Combine(_root, "correlation.bin");
        await File.WriteAllBytesAsync(path, [0x01, 0x02, 0x03]);

        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);
            LanBootstrapMessage bootstrap = await AcceptBootstrapAsync(channel);

            await channel.ReadEnvelopeAsync(); // HELLO
            await channel.WriteEnvelopeAsync(CreateHelloAck(bootstrap.SessionId, 1));

            Envelope begin = await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // page
            await channel.ReadEnvelopeAsync(); // end

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.OfferDecision,
                    new SessionId(bootstrap.SessionId),
                    NewMessageId(),
                    2,
                    new JsonObject
                    {
                        ["batchId"] = NewUuid(),
                        ["accepted"] = true
                    }));
        });

        var sender = new DesktopTransferEngine();
        DesktopTransferResult result = await sender.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                [new DesktopTransferFile(path)],
                invitation,
                "Correlation Sender"),
            new PassiveInteraction());

        await serverTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(result.Success);
        Assert.True(result.Message.Contains("does not match expected", StringComparison.OrdinalIgnoreCase), result.Message);
    }

    [Fact]
    public async Task Receiver_InvalidPageOrder_FailsBeforeConsent()
    {
        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);
        var interaction = new CountingInteraction();
        var store = new QuarantineStagingStore(
            stagingRoot: Path.Combine(_root, "staging"),
            destinationRoot: Path.Combine(_root, "downloads"));

        var receiverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine().ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    store,
                    new LanInvitationAuthority(invitation),
                    "Strict Receiver"),
                interaction);
        });

        var client = new TlsSocketClient();
        await using var senderConnection = await client.ConnectAsync(
            "127.0.0.1",
            listener.BoundPort,
            invitation.SpkiSha256);
        using var channel = new LanRecordChannel(senderConnection.SslStream);

        string sessionId = NewUuid();
        await channel.WriteBootstrapAsync(new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            sessionId));
        Assert.True((await channel.ReadBootstrapResultAsync()).IsAccepted);

        await channel.WriteEnvelopeAsync(CreateHello(sessionId, 1));
        Envelope helloAck = await channel.ReadEnvelopeAsync();
        Assert.Equal(1, helloAck.Seq);

        string batchId = NewUuid();
        string itemId = NewUuid();
        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferBegin,
                new SessionId(sessionId),
                NewMessageId(),
                2,
                new JsonObject
                {
                    ["batchId"] = batchId,
                    ["itemCount"] = 1,
                    ["totalBytes"] = "1",
                    ["pageCount"] = 2
                }));

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferPage,
                new SessionId(sessionId),
                NewMessageId(),
                3,
                CreateOfferPageBody(batchId, pageIndex: 1, itemId, "bad.bin", 1)));

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.False(receiverResult.Success);
        Assert.Equal(0, interaction.ConfirmOfferCalls);
        Assert.True(receiverResult.Message.Contains("page index", StringComparison.OrdinalIgnoreCase), receiverResult.Message);
    }

    [Fact]
    public async Task Sender_MismatchedItemReadyItemId_FailsBeforeStreaming()
    {
        string path = Path.Combine(_root, "wrong-item.bin");
        await File.WriteAllBytesAsync(path, [0x44, 0x55]);

        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);
            LanBootstrapMessage bootstrap = await AcceptBootstrapAsync(channel);

            await channel.ReadEnvelopeAsync(); // HELLO
            await channel.WriteEnvelopeAsync(CreateHelloAck(bootstrap.SessionId, 1));

            Envelope begin = await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // OFFER_PAGE
            await channel.ReadEnvelopeAsync(); // OFFER_END
            string batchId = begin.Body["batchId"]!.ToString();

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

            Envelope itemStart = await channel.ReadEnvelopeAsync();
            Assert.Equal(MessageTypes.ItemStart, itemStart.Type);

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
                        ["itemId"] = NewUuid()
                    }));
        });

        DesktopTransferResult result =
            await new DesktopTransferEngine().SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [new DesktopTransferFile(path)],
                    invitation,
                    "Wrong Item Sender"),
                new PassiveInteraction());

        await serverTask.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.False(result.Success);
        Assert.True(
            result.Message.Contains("itemId", StringComparison.Ordinal),
            result.Message);
        Assert.True(
            result.Message.Contains("does not match expected", StringComparison.OrdinalIgnoreCase),
            result.Message);
    }

    [Fact]
    public async Task Sender_FailedItemReceipt_TerminatesBatchWithoutNextItemOrBatchEnd()
    {
        string first = Path.Combine(_root, "first.bin");
        string second = Path.Combine(_root, "second.bin");
        await File.WriteAllBytesAsync(first, [0x11]);
        await File.WriteAllBytesAsync(second, [0x22]);

        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);
        LanRecord? recordAfterFailure = null;

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);
            LanBootstrapMessage bootstrap = await AcceptBootstrapAsync(channel);

            await channel.ReadEnvelopeAsync(); // HELLO
            await channel.WriteEnvelopeAsync(CreateHelloAck(bootstrap.SessionId, 1));

            Envelope begin = await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // OFFER_PAGE
            await channel.ReadEnvelopeAsync(); // OFFER_END
            string batchId = begin.Body["batchId"]!.ToString();

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

            Envelope itemStart = await channel.ReadEnvelopeAsync();
            string itemId = itemStart.Body["itemId"]!.ToString();
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
                        ["itemId"] = itemId
                    }));

            await DrainOneBulkStreamAsync(channel);
            await channel.ReadEnvelopeAsync(); // ITEM_END

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemResult,
                    new SessionId(bootstrap.SessionId),
                    NewMessageId(),
                    4,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = itemId,
                        ["status"] = "failed",
                        ["code"] = WireErrorCodes.WriteFailure
                    }));

            recordAfterFailure = await channel.ReadRecordAsync()
                .WaitAsync(TimeSpan.FromSeconds(2));
        });

        DesktopTransferResult result = await new DesktopTransferEngine().SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                [new DesktopTransferFile(first), new DesktopTransferFile(second)],
                invitation,
                "Failure Sender"),
            new PassiveInteraction());

        await serverTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(result.Success);
        Assert.True(result.Message.Contains(WireErrorCodes.WriteFailure, StringComparison.Ordinal), result.Message);
        Assert.Null(recordAfterFailure);
    }

    [Fact]
    public async Task Sender_MissingHelloAck_UsesConfiguredProtocolDeadline()
    {
        string path = Path.Combine(_root, "timeout.bin");
        await File.WriteAllBytesAsync(path, [0x01]);

        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);
            await AcceptBootstrapAsync(channel);
            await channel.ReadEnvelopeAsync(); // HELLO
            await Task.Delay(TimeSpan.FromSeconds(2));
        });

        var deadlines = DesktopProtocolDeadlines.Default with
        {
            Hello = TimeSpan.FromMilliseconds(200)
        };
        DesktopTransferResult result =
            await new DesktopTransferEngine(deadlines).SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [new DesktopTransferFile(path)],
                    invitation,
                    "Timeout Sender"),
                new PassiveInteraction());

        Assert.False(result.Success);
        Assert.True(result.Message.Contains("HELLO_ACK", StringComparison.Ordinal), result.Message);
        Assert.True(result.Message.Contains("deadline", StringComparison.OrdinalIgnoreCase), result.Message);

        await serverTask.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [Fact]
    public async Task Receiver_InboundSequenceGap_FailsClosedBeforeConsent()
    {
        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);
        var interaction = new CountingInteraction();
        var store = new QuarantineStagingStore(
            stagingRoot: Path.Combine(_root, "gap-staging"),
            destinationRoot: Path.Combine(_root, "gap-downloads"));

        var receiverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine().ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    store,
                    new LanInvitationAuthority(invitation),
                    "Gap Receiver"),
                interaction);
        });

        var client = new TlsSocketClient();
        await using var senderConnection = await client.ConnectAsync(
            "127.0.0.1",
            listener.BoundPort,
            invitation.SpkiSha256);
        using var channel = new LanRecordChannel(senderConnection.SslStream);

        string sessionId = NewUuid();
        await channel.WriteBootstrapAsync(new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            sessionId));
        Assert.True((await channel.ReadBootstrapResultAsync()).IsAccepted);

        await channel.WriteEnvelopeAsync(CreateHello(sessionId, 1));
        await channel.ReadEnvelopeAsync(); // HELLO_ACK seq=1

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferBegin,
                new SessionId(sessionId),
                NewMessageId(),
                3,
                new JsonObject
                {
                    ["batchId"] = NewUuid(),
                    ["itemCount"] = 1,
                    ["totalBytes"] = "0",
                    ["pageCount"] = 1
                }));

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.False(receiverResult.Success);
        Assert.Equal(0, interaction.ConfirmOfferCalls);
        Assert.True(receiverResult.Message.Contains("Expected inbound seq 2", StringComparison.Ordinal), receiverResult.Message);
    }

    [Fact]
    public async Task Receiver_DuplicateSequenceReplay_FailsClosedBeforeConsent()
    {
        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);
        var interaction = new CountingInteraction();
        var store = new QuarantineStagingStore(
            stagingRoot: Path.Combine(_root, "replay-staging"),
            destinationRoot: Path.Combine(_root, "replay-downloads"));

        var receiverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            return await new DesktopTransferEngine().ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    store,
                    new LanInvitationAuthority(invitation),
                    "Replay Receiver"),
                interaction);
        });

        var client = new TlsSocketClient();
        await using var senderConnection = await client.ConnectAsync(
            "127.0.0.1",
            listener.BoundPort,
            invitation.SpkiSha256);
        using var channel = new LanRecordChannel(senderConnection.SslStream);

        string sessionId = NewUuid();
        await channel.WriteBootstrapAsync(new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            sessionId));
        Assert.True((await channel.ReadBootstrapResultAsync()).IsAccepted);

        await channel.WriteEnvelopeAsync(CreateHello(sessionId, 1));
        await channel.ReadEnvelopeAsync(); // HELLO_ACK seq=1

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferBegin,
                new SessionId(sessionId),
                NewMessageId(),
                1, // replay the already-consumed sender seq
                new JsonObject
                {
                    ["batchId"] = NewUuid(),
                    ["itemCount"] = 1,
                    ["totalBytes"] = "0",
                    ["pageCount"] = 1
                }));

        DesktopTransferResult receiverResult =
            await receiverTask.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.False(receiverResult.Success);
        Assert.Equal(0, interaction.ConfirmOfferCalls);
        Assert.True(
            receiverResult.Message.Contains("Expected inbound seq 2", StringComparison.Ordinal),
            receiverResult.Message);
    }

    [Fact]
    public async Task Sender_LostBatchResult_UsesTerminalReceiptDeadline()
    {
        string path = Path.Combine(_root, "terminal-timeout.bin");
        await File.WriteAllBytesAsync(path, Array.Empty<byte>());

        using var listener = new TlsSocketListener(0);
        LanInvitation invitation = CreateInvitation(listener);

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);
            LanBootstrapMessage bootstrap = await AcceptBootstrapAsync(channel);

            await channel.ReadEnvelopeAsync(); // HELLO
            await channel.WriteEnvelopeAsync(CreateHelloAck(bootstrap.SessionId, 1));

            Envelope begin = await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // OFFER_PAGE
            await channel.ReadEnvelopeAsync(); // OFFER_END
            string batchId = begin.Body["batchId"]!.ToString();

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

            Envelope itemStart = await channel.ReadEnvelopeAsync();
            string itemId = itemStart.Body["itemId"]!.ToString();

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
                        ["itemId"] = itemId
                    }));

            LanRecord? streamEnd = await channel.ReadRecordAsync();
            Assert.IsType<LanRecord.StreamEnd>(streamEnd);

            Envelope itemEnd = await channel.ReadEnvelopeAsync();
            Assert.Equal(MessageTypes.ItemEnd, itemEnd.Type);

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemResult,
                    new SessionId(bootstrap.SessionId),
                    NewMessageId(),
                    4,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = itemId,
                        ["status"] = "verified",
                        ["sizeBytes"] = "0",
                        ["sha256"] = itemEnd.Body["sha256"]!.ToString()
                    }));

            Envelope batchEnd = await channel.ReadEnvelopeAsync();
            Assert.Equal(MessageTypes.BatchEnd, batchEnd.Type);

            // Intentionally omit BATCH_RESULT to prove the terminal receipt deadline.
            await Task.Delay(TimeSpan.FromSeconds(2));
        });

        var deadlines = DesktopProtocolDeadlines.Default with
        {
            BatchResult = TimeSpan.FromMilliseconds(200)
        };

        DesktopTransferResult result =
            await new DesktopTransferEngine(deadlines).SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [new DesktopTransferFile(path)],
                    invitation,
                    "Terminal Timeout Sender"),
                new PassiveInteraction());

        Assert.False(result.Success);
        Assert.True(result.Message.Contains("BATCH_RESULT", StringComparison.Ordinal), result.Message);
        Assert.True(result.Message.Contains("deadline", StringComparison.OrdinalIgnoreCase), result.Message);

        await serverTask.WaitAsync(TimeSpan.FromSeconds(5));
    }

    private static async Task<LanBootstrapMessage> AcceptBootstrapAsync(
        LanRecordChannel channel)
    {
        LanRecord? record = await channel.ReadRecordAsync();
        var bootstrapRecord = Assert.IsType<LanRecord.Bootstrap>(record);
        LanBootstrapMessage bootstrap =
            LanBootstrapCodec.DecodeBootstrap(bootstrapRecord.JsonBytes);
        await channel.WriteBootstrapResultAsync(
            LanBootstrapResult.Accepted(bootstrap.SessionId));
        return bootstrap;
    }

    private static async Task DrainOneBulkStreamAsync(LanRecordChannel channel)
    {
        while (true)
        {
            LanRecord? record = await channel.ReadRecordAsync();
            if (record == null)
            {
                throw new EndOfStreamException();
            }

            if (record is LanRecord.StreamEnd)
            {
                return;
            }

            Assert.IsType<LanRecord.Data>(record);
        }
    }

    private static Envelope CreateHello(string sessionId, long seq) =>
        new(
            1,
            MessageTypes.Hello,
            new SessionId(sessionId),
            NewMessageId(),
            seq,
            new JsonObject
            {
                ["supportedMajors"] = new JsonArray(1),
                ["requiredCapabilities"] =
                    new JsonArray("stream-v1", "sha256"),
                ["optionalCapabilities"] = new JsonArray(),
                ["role"] = "sender",
                ["ephemeralPeerId"] = NewUuid(),
                ["displayName"] = "HARD-013 Sender"
            });

    private static Envelope CreateHelloAck(string sessionId, long seq)
    {
        var limits = new JsonObject
        {
            ["maxItems"] = 1000,
            ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
            ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
            ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
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
                    new JsonArray("stream-v1", "sha256"),
                ["ephemeralPeerId"] = NewUuid(),
                ["displayName"] = "HARD-013 Receiver",
                ["limits"] = limits
            });
    }

    private static JsonObject CreateOfferPageBody(
        string batchId,
        int pageIndex,
        string itemId,
        string name,
        long size) =>
        new()
        {
            ["batchId"] = batchId,
            ["pageIndex"] = pageIndex,
            ["items"] = new JsonArray(
                new JsonObject
                {
                    ["itemId"] = itemId,
                    ["name"] = name,
                    ["mime"] = "application/octet-stream",
                    ["sizeBytes"] = size.ToString(),
                    ["relativePath"] = name
                })
        };

    private static LanInvitation CreateInvitation(TlsSocketListener listener)
    {
        string token = Convert.ToBase64String(
                RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        var draft = new LanInvitation(
            invitationId: NewUuid(),
            presence: Convert.ToHexString(
                    RandomNumberGenerator.GetBytes(16))
                .ToLowerInvariant(),
            name: "HARD-013 Receiver",
            platform: "windows",
            endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
            spkiSha256: listener.LocalSpkiSha256,
            token: token,
            expiresAt: DateTimeOffset.UtcNow
                .AddMinutes(2)
                .ToUnixTimeSeconds()
                .ToString(),
            ttlSeconds: 120);

        return InvitationCodec.Decode(InvitationCodec.Encode(draft));
    }

    private static MessageId NewMessageId() => new(NewUuid());

    private static string NewUuid() =>
        Guid.NewGuid().ToString("D").ToLowerInvariant();

    private sealed class PassiveInteraction : DesktopTransferInteractionBase
    {
    }

    private sealed class CountingInteraction : DesktopTransferInteractionBase
    {
        public int ConfirmOfferCalls { get; private set; }

        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken)
        {
            ConfirmOfferCalls++;
            return Task.FromResult(true);
        }
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch
        {
            // Best-effort test cleanup.
        }
    }
}
