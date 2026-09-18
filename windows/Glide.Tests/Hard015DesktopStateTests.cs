using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class Hard015DesktopStateTests : IDisposable
{
    private readonly string _root =
        Path.Combine(
            Path.GetTempPath(),
            "glide-hard015-" + Guid.NewGuid().ToString("N"));

    public Hard015DesktopStateTests()
    {
        Directory.CreateDirectory(_root);
    }

    [Fact]
    public void DesktopSettings_RelativeReceiveDirectory_IsRejected()
    {
        Assert.Throws<ArgumentException>(
            () => new DesktopSettings(
                "Desktop",
                "relative/downloads",
                20).Validate());
    }

    [Fact]
    public async Task JsonStateStore_SettingsAndHistorySurviveRestart()
    {
        string statePath =
            Path.Combine(_root, "state", "desktop-state.json");
        string receiveDirectory =
            Path.Combine(_root, "received");

        var settings =
            new DesktopSettings(
                "Persistent Desktop",
                receiveDirectory,
                25);

        var entry =
            new DesktopHistoryEntry(
                DesktopTransferDirection.Receive,
                "verified.bin",
                42,
                Path.Combine(receiveDirectory, "verified.bin"),
                "Pixel",
                DateTimeOffset.UtcNow);

        using (var store = new JsonDesktopStateStore(statePath))
        {
            await store.SaveSettingsAsync(settings);
            await store.AppendHistoryAsync(entry);
        }

        using var reopened =
            new JsonDesktopStateStore(statePath);

        DesktopPersistentState state =
            await reopened.LoadAsync();

        Assert.Equal(
            "Persistent Desktop",
            state.Settings.DeviceName);
        Assert.Equal(
            Path.GetFullPath(receiveDirectory),
            state.Settings.ReceiveDirectory);
        Assert.Equal(25, state.Settings.HistoryLimit);
        Assert.Single(state.History);
        Assert.Equal(entry, state.History[0]);
    }

    [Fact]
    public async Task JsonStateStore_HistoryRetentionAndClearAreDurable()
    {
        string statePath =
            Path.Combine(_root, "retention", "desktop-state.json");
        string receiveDirectory =
            Path.Combine(_root, "retention-received");

        using (var store = new JsonDesktopStateStore(statePath))
        {
            await store.SaveSettingsAsync(
                new DesktopSettings(
                    "Retention Desktop",
                    receiveDirectory,
                    2));

            for (int i = 0; i < 3; i++)
            {
                await store.AppendHistoryAsync(
                    new DesktopHistoryEntry(
                        DesktopTransferDirection.Send,
                        $"item-{i}.bin",
                        i,
                        Path.Combine(_root, $"item-{i}.bin"),
                        "Peer",
                        DateTimeOffset.UtcNow.AddSeconds(i)));
            }

            DesktopPersistentState state =
                await store.LoadAsync();

            Assert.Equal(2, state.History.Count);
            Assert.Equal("item-2.bin", state.History[0].ItemName);
            Assert.Equal("item-1.bin", state.History[1].ItemName);

            await store.ClearHistoryAsync();
        }

        using var reopened =
            new JsonDesktopStateStore(statePath);
        Assert.Empty(
            (await reopened.LoadAsync()).History);
    }

    [Fact]
    public async Task FailedSecondItem_PreservesOnlyDurableVerifiedFirstItemHistory()
    {
        string first =
            Path.Combine(_root, "partial-first.bin");
        string second =
            Path.Combine(_root, "partial-second.bin");

        await File.WriteAllBytesAsync(
            first,
            [0x01, 0x02, 0x03]);
        await File.WriteAllBytesAsync(
            second,
            [0x04, 0x05, 0x06]);

        string statePath =
            Path.Combine(_root, "partial-state.json");

        using var stateStore =
            new JsonDesktopStateStore(statePath);

        using var listener =
            new TlsSocketListener(0);
        LanInvitation invitation =
            CreateInvitation(listener);

        var serverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            using var channel =
                new LanRecordChannel(connection.SslStream);

            LanBootstrapMessage bootstrap =
                await AcceptBootstrapAsync(channel);

            await channel.ReadEnvelopeAsync(); // HELLO
            await channel.WriteEnvelopeAsync(
                CreateHelloAck(
                    bootstrap.SessionId,
                    1));

            Envelope offerBegin =
                await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // OFFER_PAGE
            await channel.ReadEnvelopeAsync(); // OFFER_END

            string batchId =
                offerBegin.Body["batchId"]!.ToString();

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

            Envelope firstStart =
                await channel.ReadEnvelopeAsync();
            string firstItemId =
                firstStart.Body["itemId"]!.ToString();

            await channel.WriteEnvelopeAsync(
                CreateItemReady(
                    bootstrap.SessionId,
                    3,
                    batchId,
                    firstItemId));

            await DrainBulkAsync(channel);
            Envelope firstEnd =
                await channel.ReadEnvelopeAsync();

            await channel.WriteEnvelopeAsync(
                CreateVerifiedItemResult(
                    bootstrap.SessionId,
                    4,
                    batchId,
                    firstItemId,
                    firstEnd));

            Envelope secondStart =
                await channel.ReadEnvelopeAsync();
            string secondItemId =
                secondStart.Body["itemId"]!.ToString();

            await channel.WriteEnvelopeAsync(
                CreateItemReady(
                    bootstrap.SessionId,
                    5,
                    batchId,
                    secondItemId));

            await DrainBulkAsync(channel);
            await channel.ReadEnvelopeAsync(); // ITEM_END

            await channel.WriteEnvelopeAsync(
                new Envelope(
                    1,
                    MessageTypes.ItemResult,
                    new SessionId(bootstrap.SessionId),
                    NewMessageId(),
                    6,
                    new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = secondItemId,
                        ["status"] = "failed",
                        ["code"] = WireErrorCodes.WriteFailure
                    }));
        });

        var interaction =
            new PersistingInteraction(stateStore);

        DesktopTransferResult result =
            await new DesktopTransferEngine().SendAsync(
                new DesktopSendRequest(
                    "127.0.0.1",
                    listener.BoundPort,
                    [
                        new DesktopTransferFile(first),
                        new DesktopTransferFile(second)
                    ],
                    invitation,
                    "History Sender"),
                interaction);

        await serverTask.WaitAsync(
            TimeSpan.FromSeconds(10));

        Assert.False(result.Success);
        Assert.Single(interaction.History);
        Assert.Equal(
            "partial-first.bin",
            interaction.History[0].ItemName);

        using var reopened =
            new JsonDesktopStateStore(statePath);
        DesktopPersistentState persisted =
            await reopened.LoadAsync();

        Assert.Single(persisted.History);
        Assert.Equal(
            "partial-first.bin",
            persisted.History[0].ItemName);
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
        long seq) =>
        new(
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
                ["ephemeralPeerId"] = NewUuid(),
                ["displayName"] = "HARD-015 Receiver",
                ["limits"] =
                    new JsonObject
                    {
                        ["maxItems"] = 1000,
                        ["maxItemBytes"] =
                            ProtocolCatalog.MaxItemBytes.ToString(),
                        ["maxBatchBytes"] =
                            ProtocolCatalog.MaxBatchBytes.ToString(),
                        ["maxControlBytes"] =
                            ProtocolCatalog.MaxControlBytes
                    }
            });

    private static Envelope CreateItemReady(
        string sessionId,
        long seq,
        string batchId,
        string itemId) =>
        new(
            1,
            MessageTypes.ItemReady,
            new SessionId(sessionId),
            NewMessageId(),
            seq,
            new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = itemId
            });

    private static Envelope CreateVerifiedItemResult(
        string sessionId,
        long seq,
        string batchId,
        string itemId,
        Envelope itemEnd) =>
        new(
            1,
            MessageTypes.ItemResult,
            new SessionId(sessionId),
            NewMessageId(),
            seq,
            new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = itemId,
                ["status"] = "verified",
                ["sizeBytes"] =
                    itemEnd.Body["actualBytes"]!.ToString(),
                ["sha256"] =
                    itemEnd.Body["sha256"]!.ToString()
            });

    private static async Task DrainBulkAsync(
        LanRecordChannel channel)
    {
        while (true)
        {
            LanRecord? record =
                await channel.ReadRecordAsync();

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
                name: "HARD-015 Receiver",
                platform: "windows",
                endpoints:
                [
                    new LanEndpoint(
                        "127.0.0.1",
                        listener.BoundPort)
                ],
                spkiSha256: listener.LocalSpkiSha256,
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

    private sealed class PersistingInteraction(
        IDesktopStateStore stateStore)
        : DesktopTransferInteractionBase
    {
        public List<DesktopHistoryEntry> History { get; } = [];

        public override async Task OnHistoryAsync(
            DesktopHistoryEntry entry,
            CancellationToken cancellationToken = default)
        {
            await stateStore.AppendHistoryAsync(
                entry,
                cancellationToken);
            History.Add(entry);
        }
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
            // Best-effort cleanup.
        }
    }
}
