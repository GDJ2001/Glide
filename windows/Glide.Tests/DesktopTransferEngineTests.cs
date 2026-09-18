using System.Security.Cryptography;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class DesktopTransferEngineTests : IDisposable
{
    private readonly string _root;
    private readonly string _sourceDir;
    private readonly string _destinationDir;
    private readonly string _stagingDir;

    public DesktopTransferEngineTests()
    {
        _root = Path.Combine(
            Path.GetTempPath(),
            "glide-hard011-" + Guid.NewGuid().ToString("N"));
        _sourceDir = Path.Combine(_root, "source");
        _destinationDir = Path.Combine(_root, "destination");
        _stagingDir = Path.Combine(_root, "staging");
        Directory.CreateDirectory(_sourceDir);
        Directory.CreateDirectory(_destinationDir);
        Directory.CreateDirectory(_stagingDir);
    }

    [Fact]
    public async Task SharedEngine_QrPairedSendAndReceive_UsesSameSessionImplementation()
    {
        string firstPath = Path.Combine(_sourceDir, "alpha.bin");
        string secondPath = Path.Combine(_sourceDir, "beta.bin");
        byte[] firstPayload = Enumerable.Range(0, 32_768)
            .Select(i => (byte)(i % 251))
            .ToArray();
        byte[] secondPayload = Enumerable.Range(0, 8_193)
            .Select(i => (byte)(255 - (i % 251)))
            .ToArray();

        await File.WriteAllBytesAsync(firstPath, firstPayload);
        await File.WriteAllBytesAsync(secondPath, secondPayload);

        using var listener = new TlsSocketListener(0);
        var invitationAuthority = new LanInvitationAuthority();
        var invitation = invitationAuthority.Publish(CreateInvitation(listener));
        var receiverStore = new QuarantineStagingStore(
            stagingRoot: _stagingDir,
            destinationRoot: _destinationDir);

        var receiverInteraction = new RecordingInteraction();
        var senderInteraction = new RecordingInteraction();
        var receiverEngine = new DesktopTransferEngine();
        var senderEngine = new DesktopTransferEngine();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await receiverEngine.ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    receiverStore,
                    invitationAuthority,
                    LocalDeviceName: "Receiver-Desktop"),
                receiverInteraction);
        });

        var senderResult = await senderEngine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                new[]
                {
                    new DesktopTransferFile(firstPath),
                    new DesktopTransferFile(secondPath)
                },
                invitation,
                LocalDeviceName: "Sender-Desktop"),
            senderInteraction);

        var receiverResult = await receiverTask.WaitAsync(
            TimeSpan.FromSeconds(15));

        Assert.True(senderResult.Success, senderResult.Message);
        Assert.True(receiverResult.Success, receiverResult.Message);
        Assert.Equal(2, senderResult.VerifiedCount);
        Assert.Equal(2, receiverResult.VerifiedCount);

        Assert.True(senderInteraction.InvitationConsumed);
        Assert.True(receiverInteraction.InvitationConsumed);
        Assert.Equal("Receiver-Desktop", senderInteraction.LastPeerName);
        Assert.Equal("Sender-Desktop", receiverInteraction.LastPeerName);

        Assert.Equal(2, senderInteraction.History.Count);
        Assert.Equal(2, receiverInteraction.History.Count);
        Assert.Contains(
            senderInteraction.ItemStatuses,
            entry => entry.Status == "Verified");
        Assert.Contains(
            receiverInteraction.ItemStatuses,
            entry => entry.Status == "Verified");

        Assert.Equal(
            firstPayload,
            await File.ReadAllBytesAsync(
                Path.Combine(_destinationDir, "alpha.bin")));
        Assert.Equal(
            secondPayload,
            await File.ReadAllBytesAsync(
                Path.Combine(_destinationDir, "beta.bin")));
    }

    [Fact]
    public async Task SharedEngine_CancelledSender_ReturnsCancelledWithoutStartingProtocol()
    {
        string sourcePath = Path.Combine(_sourceDir, "cancel.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            RandomNumberGenerator.GetBytes(1024));

        using var listener = new TlsSocketListener(0);
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var engine = new DesktopTransferEngine();
        var result = await engine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                new[] { new DesktopTransferFile(sourcePath) },
                Invitation: null,
                LocalDeviceName: "Cancelled-Sender"),
            new RecordingInteraction(),
            cts.Token);

        Assert.True(result.Cancelled);
        Assert.False(result.Success);
    }

    [Fact]
    public async Task SharedEngine_ReceiverOfferRejection_StopsBeforeFileCommit()
    {
        string sourcePath = Path.Combine(_sourceDir, "reject.bin");
        await File.WriteAllBytesAsync(
            sourcePath,
            RandomNumberGenerator.GetBytes(4096));

        using var listener = new TlsSocketListener(0);
        var invitationAuthority = new LanInvitationAuthority();
        var invitation = invitationAuthority.Publish(CreateInvitation(listener));
        var receiverStore = new QuarantineStagingStore(
            stagingRoot: _stagingDir,
            destinationRoot: _destinationDir);

        var receiverInteraction = new RecordingInteraction
        {
            AcceptOffers = false
        };
        var senderInteraction = new RecordingInteraction();
        var receiverEngine = new DesktopTransferEngine();
        var senderEngine = new DesktopTransferEngine();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();
            return await receiverEngine.ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    receiverStore,
                    invitationAuthority,
                    "Rejecting-Receiver"),
                receiverInteraction);
        });

        var senderResult = await senderEngine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                listener.BoundPort,
                new[] { new DesktopTransferFile(sourcePath) },
                invitation,
                "Offer-Sender"),
            senderInteraction);

        var receiverResult = await receiverTask.WaitAsync(
            TimeSpan.FromSeconds(15));

        Assert.True(senderResult.Rejected);
        Assert.True(receiverResult.Rejected);
        Assert.Empty(Directory.GetFiles(
            _destinationDir,
            "*",
            SearchOption.AllDirectories));
        Assert.Empty(senderInteraction.History);
        Assert.Empty(receiverInteraction.History);
    }

    [Fact]
    public async Task SharedEngine_UnpairedSend_FailsClosedBeforeConnecting()
    {
        string sourcePath = Path.Combine(_sourceDir, "unpaired.bin");
        await File.WriteAllBytesAsync(sourcePath, [0x01, 0x02, 0x03]);

        var engine = new DesktopTransferEngine();
        var result = await engine.SendAsync(
            new DesktopSendRequest(
                "127.0.0.1",
                9,
                [new DesktopTransferFile(sourcePath)],
                Invitation: null,
                LocalDeviceName: "Unpaired-Sender"),
            new RecordingInteraction());

        Assert.False(result.Success);
        Assert.Contains(
            "Authenticated pairing invitation required",
            result.Message,
            StringComparison.Ordinal);
    }

    [Fact]
    public void InvitationAuthority_ConsumesExactlyOnce()
    {
        using var listener = new TlsSocketListener(0);
        var authority = new LanInvitationAuthority();
        var invitation = authority.Publish(CreateInvitation(listener));
        string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        var bootstrap = new LanBootstrapMessage(
            invitation.InvitationId,
            invitation.Token,
            invitation.InvitationHash,
            sessionId);

        var first = authority.Consume(bootstrap);
        var replay = authority.Consume(bootstrap);

        Assert.True(first.IsAccepted);
        Assert.False(replay.IsAccepted);
        Assert.Equal(
            LanBootstrapFailureCode.INVITATION_USED.ToString(),
            replay.Code);
    }

    private static LanInvitation CreateInvitation(
        TlsSocketListener listener)
    {
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(32);
        string token = Convert.ToBase64String(tokenBytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

        string spki = Convert.ToHexString(
            SHA256.HashData(
                listener.ServerCertificate.PublicKey
                    .ExportSubjectPublicKeyInfo()))
            .ToLowerInvariant();

        var invitation = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: Convert.ToHexString(
                    RandomNumberGenerator.GetBytes(16))
                .ToLowerInvariant(),
            name: "Receiver-Desktop",
            platform: "windows",
            endpoints:
            [
                new LanEndpoint("127.0.0.1", listener.BoundPort)
            ],
            spkiSha256: spki,
            token: token,
            expiresAt: (
                DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120)
                .ToString(),
            ttlSeconds: 120);

        // The invitation hash is the canonical hash of the encoded QR payload.
        return InvitationCodec.Decode(InvitationCodec.Encode(invitation));
    }

    private sealed class RecordingInteraction
        : DesktopTransferInteractionBase
    {
        public bool AcceptOffers { get; init; } = true;
        public bool InvitationConsumed { get; private set; }
        public string? LastPeerName { get; private set; }

        public List<DesktopHistoryEntry> History { get; } = [];
        public List<(DesktopTransferDirection Direction, string Item, string Status)>
            ItemStatuses { get; } = [];

        public override Task<bool> ConfirmSasAsync(
            DesktopTransferDirection direction,
            string peerName,
            int sixDigitCode,
            CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public override Task<bool> ConfirmOfferAsync(
            DesktopOffer offer,
            CancellationToken cancellationToken) =>
            Task.FromResult(AcceptOffers);

        public override void OnInvitationConsumed(
            DesktopTransferDirection direction) =>
            InvitationConsumed = true;

        public override void OnPeerIdentified(
            DesktopTransferDirection direction,
            string peerName) =>
            LastPeerName = peerName;

        public override void OnItemStatus(
            DesktopTransferDirection direction,
            string itemName,
            string status) =>
            ItemStatuses.Add((direction, itemName, status));

        public override void OnHistory(DesktopHistoryEntry entry) =>
            History.Add(entry);
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
