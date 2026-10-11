using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Glide.Core.Crypto;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Transport.Framing;
using Glide.Transport.Pairing;
using Glide.Transport.Sockets;
using Xunit;

namespace Glide.Tests;

public sealed class Profile2PairingAndSpake2Tests
{
    private sealed class InMemoryIdentityStore : IDesktopPersistentIdentityStore, IDisposable
    {
        private readonly ECDsa _ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);

        public bool HasIdentity() => true;

        public byte[] GetOrCreatePublicKeyDer() => _ecdsa.ExportSubjectPublicKeyInfo();

        public string GetFingerprintHex() =>
            Convert.ToHexStringLower(SHA256.HashData(GetOrCreatePublicKeyDer()));

        public byte[] SignTranscript(ReadOnlySpan<byte> transcript) =>
            _ecdsa.SignData(transcript, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

        public void DeleteIdentity() { }

        public void Dispose() => _ecdsa.Dispose();
    }

    private static string FindRepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "AGENTS.md")))
            {
                return dir.FullName;
            }
            dir = dir.Parent;
        }
        throw new DirectoryNotFoundException("Could not locate Glide repository root.");
    }

    [Fact]
    public void GoldenFixtures_ValidateV1Compatibility_V2PairOnly_AndTranscriptHash()
    {
        string fixturePath = Path.Combine(FindRepoRoot(), "doc", "protocol", "fixtures", "profile2-fixtures.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        var root = doc.RootElement;

        // 1. V1 Compatibility
        var v1 = root.GetProperty("v1Compatibility");
        string v1Uri = v1.GetProperty("validUri").GetString()!;
        var decodedV1 = InvitationCodec.Decode(v1Uri);
        Assert.Equal(v1.GetProperty("expectedInvitationHash").GetString(), decodedV1.InvitationHash);
        Assert.Equal(v1.GetProperty("expectedInvitationId").GetString(), decodedV1.InvitationId);
        Assert.Equal(v1.GetProperty("expectedName").GetString(), decodedV1.Name);
        Assert.Equal(v1.GetProperty("expectedPlatform").GetString(), decodedV1.Platform);
        Assert.Equal(v1.GetProperty("expectedPresence").GetString(), decodedV1.Presence);
        Assert.Equal(v1.GetProperty("expectedSpkiSha256").GetString(), decodedV1.SpkiSha256);
        Assert.Equal(v1.GetProperty("expectedToken").GetString(), decodedV1.Token);
        var roundTripV1 = InvitationCodec.Decode(InvitationCodec.Encode(decodedV1));
        Assert.Equal(decodedV1.InvitationId, roundTripV1.InvitationId);
        Assert.Equal(decodedV1.Token, roundTripV1.Token);

        // 2. V2 Pair-Only Invitation
        var v2 = root.GetProperty("v2PairOnlyInvitation");
        string v2Uri = v2.GetProperty("validUri").GetString()!;
        var decodedV2 = InvitationCodec.DecodePairV2(v2Uri);
        Assert.Equal(v2.GetProperty("expectedInvitationHash").GetString(), decodedV2.InvitationHash);
        Assert.Equal(v2.GetProperty("expectedInvitationId").GetString(), decodedV2.InvitationId);
        Assert.Equal(v2.GetProperty("expectedPurpose").GetString(), decodedV2.Purpose);
        Assert.Equal(v2.GetProperty("expectedName").GetString(), decodedV2.Name);
        Assert.Equal(v2.GetProperty("expectedPlatform").GetString(), decodedV2.Platform);
        Assert.Equal(v2.GetProperty("expectedPresence").GetString(), decodedV2.Presence);
        Assert.Equal(v2.GetProperty("expectedSpkiSha256").GetString(), decodedV2.SpkiSha256);
        Assert.Equal(v2.GetProperty("expectedToken").GetString(), decodedV2.Token);
        var roundTripV2 = InvitationCodec.DecodePairV2(InvitationCodec.EncodePairV2(decodedV2));
        Assert.Equal(decodedV2.InvitationId, roundTripV2.InvitationId);
        Assert.Equal(decodedV2.Purpose, roundTripV2.Purpose);
        Assert.Equal(decodedV2.Token, roundTripV2.Token);

        // Ensure v1 decoder explicitly surfaces "Update Glide to pair this device" on v2 URI
        var v1RejectEx = Assert.Throws<InvitationException>(() => InvitationCodec.Decode(v2Uri));
        Assert.Equal("Update Glide to pair this device", v1RejectEx.Message);

        // Ensure DecodeAny routes both v1 and v2 accurately
        var anyV1 = InvitationCodec.DecodeAny(v1Uri);
        Assert.IsType<ParsedGlideInvitation.TransferV1>(anyV1);

        var anyV2 = InvitationCodec.DecodeAny(v2Uri);
        Assert.IsType<ParsedGlideInvitation.PairOnlyV2>(anyV2);

        // 3. Invalid V2 cases
        foreach (var invalid in v2.GetProperty("invalidCases").EnumerateArray())
        {
            string badUri = invalid.GetProperty("uri").GetString()!;
            Assert.Throws<InvitationException>(() => InvitationCodec.DecodePairV2(badUri));
        }

        // 4. GLIDE-PAIR-V2 binary transcript fixture
        var tx = root.GetProperty("pairV2Transcript");
        byte[] transcript = GlideSpake2Session.BuildPairV2TranscriptBytes(
            method: tx.GetProperty("method").GetString()!,
            sessionId: tx.GetProperty("sessionId").GetString()!,
            initiatorNonceHex: tx.GetProperty("initiatorNonceHex").GetString()!,
            responderNonceHex: tx.GetProperty("responderNonceHex").GetString()!,
            initiatorFingerprintHex: tx.GetProperty("initiatorFingerprintHex").GetString()!,
            initiatorPublicKeyDerHex: tx.GetProperty("initiatorPublicKeyDerHex").GetString()!,
            responderFingerprintHex: tx.GetProperty("responderFingerprintHex").GetString()!,
            responderPublicKeyDerHex: tx.GetProperty("responderPublicKeyDerHex").GetString()!,
            responderPresenceHex: tx.GetProperty("responderPresenceHex").GetString()!,
            tlsServerSpkiSha256Hex: tx.GetProperty("tlsServerSpkiSha256Hex").GetString()!,
            purpose: tx.GetProperty("purpose").GetString()!);

        Assert.Equal(tx.GetProperty("expectedTranscriptLength").GetInt32(), transcript.Length);
        string actualHash = Convert.ToHexString(SHA256.HashData(transcript)).ToLowerInvariant();
        Assert.Equal(tx.GetProperty("expectedTranscriptSha256Hex").GetString(), actualHash);
    }

    [Fact]
    public async Task Profile2WireFrames_SharedFixtures_DecodeAndRejectAmbiguity()
    {
        string fixturePath = Path.Combine(FindRepoRoot(), "doc", "protocol", "fixtures", "profile2-fixtures.json");
        using var document = JsonDocument.Parse(File.ReadAllText(fixturePath));
        foreach (var fixture in document.RootElement.GetProperty("profile2WireFrames").EnumerateArray())
        {
            byte[] frame = Convert.FromHexString(fixture.GetProperty("frameHex").GetString()!);
            int length = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(frame.AsSpan(0, 4));
            Assert.Equal(frame.Length - 4, length);
            using var stream = new MemoryStream(frame);
            Profile2Message message = await Profile2WireCodec.ReadMessageAsync(stream);
            Assert.Equal(fixture.GetProperty("json").GetProperty("type").GetString(), message.Type);
            Assert.Equal(fixture.GetProperty("json").GetProperty("sessionId").GetString(), message.SessionId);
            using var output = new MemoryStream();
            await Profile2WireCodec.WriteMessageAsync(output, message);
            Assert.Equal(message, Profile2WireCodec.Decode(output.ToArray().AsSpan(4)));
        }

        byte[] unknown = Encoding.UTF8.GetBytes("{\"v\":1,\"type\":\"PAIR_RESULT\",\"sessionId\":\"770e8400-e29b-41d4-a716-446655440002\",\"paired\":true,\"extra\":1}");
        Assert.Throws<Glide.Core.Models.ProtocolViolationException>(() => Profile2WireCodec.Decode(unknown));
        byte[] duplicate = Encoding.UTF8.GetBytes("{\"v\":1,\"type\":\"PAIR_RESULT\",\"sessionId\":\"770e8400-e29b-41d4-a716-446655440002\",\"paired\":true,\"paired\":false}");
        Assert.Throws<Glide.Core.Models.ProtocolViolationException>(() => Profile2WireCodec.Decode(duplicate));
    }

    [Fact]
    public void GlideSpake2_PinnedBoringSslCommit_DeterministicAndRandomExchange_SucceedsAndRejectsWrongPin()
    {
        Assert.Equal(GlideSpake2Session.PinnedBoringSslCommit, GlideSpake2Session.GetBoringSslCommit());

        byte[] myName = Encoding.UTF8.GetBytes("glide-spake2-initiator-v1");
        byte[] theirName = Encoding.UTF8.GetBytes("glide-spake2-responder-v1");
        byte[] password = Encoding.UTF8.GetBytes("042857");
        byte[] wrongPassword = Encoding.UTF8.GetBytes("042858");

        byte[] seedA = Convert.FromHexString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40");
        byte[] seedB = Convert.FromHexString("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");

        using var detAlice1 = new GlideSpake2Session(Spake2Role.Alice, myName, theirName);
        using var detAlice2 = new GlideSpake2Session(Spake2Role.Alice, myName, theirName);
        using var detBob = new GlideSpake2Session(Spake2Role.Bob, theirName, myName);

        byte[] pubA1 = detAlice1.GenerateMessageDeterministicForTest(password, seedA);
        byte[] pubA2 = detAlice2.GenerateMessageDeterministicForTest(password, seedA);
        Assert.Equal(pubA1, pubA2);

        byte[] pubB1 = detBob.GenerateMessageDeterministicForTest(password, seedB);
        byte[] keyA = detAlice1.ProcessPeerMessage(pubB1);
        byte[] keyB = detBob.ProcessPeerMessage(pubA1);
        Assert.Equal(64, keyA.Length);
        Assert.Equal(keyA, keyB);

        // Live session with matching PIN
        using var alice = new GlideSpake2Session(Spake2Role.Alice, myName, theirName);
        using var bob = new GlideSpake2Session(Spake2Role.Bob, theirName, myName);
        byte[] msgA = alice.GenerateMessage(password);
        byte[] msgB = bob.GenerateMessage(password);
        byte[] sharedA = alice.ProcessPeerMessage(msgB);
        byte[] sharedB = bob.ProcessPeerMessage(msgA);
        Assert.Equal(sharedA, sharedB);

        // Live session with mismatched PIN produces different shared secrets
        using var aliceWrong = new GlideSpake2Session(Spake2Role.Alice, myName, theirName);
        using var bobRight = new GlideSpake2Session(Spake2Role.Bob, theirName, myName);
        byte[] wMsgA = aliceWrong.GenerateMessage(wrongPassword);
        byte[] rMsgB = bobRight.GenerateMessage(password);
        byte[] wSharedA = aliceWrong.ProcessPeerMessage(rMsgB);
        byte[] rSharedB = bobRight.ProcessPeerMessage(wMsgA);
        Assert.NotEqual(wSharedA, rSharedB);
    }

    [Fact]
    public async Task Profile2PairingEngine_Spake2PinV1_CompletesFullMutualTrustedEnrollment()
    {
        var (initTrustDir, respTrustDir) = CreateTempTrustDirectories();
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
        try
        {
            var initTrust = new DesktopPersistentTrustManager(initId, new DesktopTrustStore(Path.Combine(initTrustDir, "trust.json")));
            var respTrust = new DesktopPersistentTrustManager(respId, new DesktopTrustStore(Path.Combine(respTrustDir, "trust.json")));
            initTrust.Initialize();
            respTrust.Initialize();

            using var listener = new TlsSocketListener(port: 0);
            using var authority = new PairReadyWindowAuthority(
                presenceId: "fedcba9876543210fedcba9876543210",
                deviceName: "Responder Linux PC",
                platform: "linux",
                endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                serverSpkiSha256Hex: listener.LocalSpkiSha256,
                fixedPinForTest: "042857");

            Assert.Equal("042 857", authority.FormattedPinDisplay);

            var serverTask = AcceptAndHandleResponderSessionAsync(
                listener,
                authority,
                isBusy: false,
                localDeviceName: "Responder Linux PC",
                localPlatform: "linux",
                trustManager: respTrust,
                approvePeerAsync: (_, _) => Task.FromResult(true));

            var clientTask = Profile2PairingEngine.RunInitiatorPinPairAsync(
                host: "127.0.0.1",
                port: listener.BoundPort,
                expectedPresenceId: "fedcba9876543210fedcba9876543210",
                sixDigitPin: "042857",
                localDeviceName: "Initiator Windows PC",
                localPlatform: "windows",
                trustManager: initTrust,
                approvePeerAsync: (_, _) => Task.FromResult(true));

            await Task.WhenAll(clientTask, serverTask);
            var initOutcome = await clientTask;
            var respOutcome = await serverTask;

            Assert.True(initOutcome.Succeeded, initOutcome.UserMessage);
            Assert.True(respOutcome.Succeeded, respOutcome.UserMessage);
            Assert.NotNull(initOutcome.Peer);
            Assert.NotNull(respOutcome.Peer);
            Assert.Equal(respTrust.LocalFingerprintHex(), initOutcome.Peer!.FingerprintHex);
            Assert.Equal(initTrust.LocalFingerprintHex(), respOutcome.Peer!.FingerprintHex);

            // Both trust stores now have the peer enrolled
            Assert.Single(initTrust.GetTrustedPeers());
            Assert.Single(respTrust.GetTrustedPeers());
            Assert.Equal("Responder Linux PC", initTrust.GetTrustedPeers()[0].DisplayName);
            Assert.Equal("Initiator Windows PC", respTrust.GetTrustedPeers()[0].DisplayName);
        }
        finally
        {
            CleanupTempDirectories(initTrustDir, respTrustDir);
        }
    }

    [Fact]
    public async Task Profile2PairingEngine_WrongPin_FailsAndInvalidatesAfterThreeAttempts()
    {
        var (initTrustDir, respTrustDir) = CreateTempTrustDirectories();
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
        try
        {
            var initTrust = new DesktopPersistentTrustManager(initId, new DesktopTrustStore(Path.Combine(initTrustDir, "trust.json")));
            var respTrust = new DesktopPersistentTrustManager(respId, new DesktopTrustStore(Path.Combine(respTrustDir, "trust.json")));
            initTrust.Initialize();
            respTrust.Initialize();

            using var listener = new TlsSocketListener(port: 0);
            using var authority = new PairReadyWindowAuthority(
                presenceId: "fedcba9876543210fedcba9876543210",
                deviceName: "Responder Linux PC",
                platform: "linux",
                endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                serverSpkiSha256Hex: listener.LocalSpkiSha256,
                fixedPinForTest: "123456");

            for (int attempt = 1; attempt <= 3; attempt++)
            {
                var serverTask = AcceptAndHandleResponderSessionAsync(
                    listener,
                    authority,
                    isBusy: false,
                    localDeviceName: "Responder Linux PC",
                    localPlatform: "linux",
                    trustManager: respTrust,
                    approvePeerAsync: (_, _) => Task.FromResult(true));

                var clientTask = Profile2PairingEngine.RunInitiatorPinPairAsync(
                    host: "127.0.0.1",
                    port: listener.BoundPort,
                    expectedPresenceId: "fedcba9876543210fedcba9876543210",
                    sixDigitPin: "999999",
                    localDeviceName: "Initiator Windows PC",
                    localPlatform: "windows",
                    trustManager: initTrust,
                    approvePeerAsync: (_, _) => Task.FromResult(true));

                await Task.WhenAll(clientTask, serverTask);
                var initOutcome = await clientTask;
                var respOutcome = await serverTask;

                Assert.False(initOutcome.Succeeded);
                Assert.False(respOutcome.Succeeded);
                if (attempt == 3)
                {
                    Assert.Equal("Code expired — get a new code", initOutcome.UserMessage);
                    Assert.False(authority.IsActive());
                }
            }

            Assert.Empty(initTrust.GetTrustedPeers());
            Assert.Empty(respTrust.GetTrustedPeers());
        }
        finally
        {
            CleanupTempDirectories(initTrustDir, respTrustDir);
        }
    }

    [Fact]
    public async Task Profile2PairingEngine_QrTokenV2_CompletesAndRejectsDeclinedApproval()
    {
        var (initTrustDir, respTrustDir) = CreateTempTrustDirectories();
        using var initId = new InMemoryIdentityStore();
        using var respId = new InMemoryIdentityStore();
        try
        {
            var initTrust = new DesktopPersistentTrustManager(initId, new DesktopTrustStore(Path.Combine(initTrustDir, "trust.json")));
            var respTrust = new DesktopPersistentTrustManager(respId, new DesktopTrustStore(Path.Combine(respTrustDir, "trust.json")));
            initTrust.Initialize();
            respTrust.Initialize();

            using var listener = new TlsSocketListener(port: 0);
            using var authority = new PairReadyWindowAuthority(
                presenceId: "fedcba9876543210fedcba9876543210",
                deviceName: "Responder Linux PC",
                platform: "linux",
                endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                serverSpkiSha256Hex: listener.LocalSpkiSha256);

            var v2Invitation = InvitationCodec.DecodePairV2(authority.QrInvitationUri);

            // First test: responder declines approval
            var declinedServerTask = AcceptAndHandleResponderSessionAsync(
                listener,
                authority,
                isBusy: false,
                localDeviceName: "Responder Linux PC",
                localPlatform: "linux",
                trustManager: respTrust,
                approvePeerAsync: (_, _) => Task.FromResult(false));

            var declinedClientTask = Profile2PairingEngine.RunInitiatorQrPairAsync(
                invitation: v2Invitation,
                localDeviceName: "Android Phone",
                localPlatform: "android",
                trustManager: initTrust,
                approvePeerAsync: (_, _) => Task.FromResult(true));

            await Task.WhenAll(declinedClientTask, declinedServerTask);
            var declinedInit = await declinedClientTask;
            var declinedResp = await declinedServerTask;

            Assert.False(declinedInit.Succeeded);
            Assert.False(declinedResp.Succeeded);
            Assert.Empty(initTrust.GetTrustedPeers());
            Assert.Empty(respTrust.GetTrustedPeers());

            // Start fresh window and approve on both sides
            using var authority2 = new PairReadyWindowAuthority(
                presenceId: "fedcba9876543210fedcba9876543210",
                deviceName: "Responder Linux PC",
                platform: "linux",
                endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
                serverSpkiSha256Hex: listener.LocalSpkiSha256);
            var v2Invitation2 = InvitationCodec.DecodePairV2(authority2.QrInvitationUri);

            var approvedServerTask = AcceptAndHandleResponderSessionAsync(
                listener,
                authority2,
                isBusy: false,
                localDeviceName: "Responder Linux PC",
                localPlatform: "linux",
                trustManager: respTrust,
                approvePeerAsync: (_, _) => Task.FromResult(true));

            var approvedClientTask = Profile2PairingEngine.RunInitiatorQrPairAsync(
                invitation: v2Invitation2,
                localDeviceName: "Android Phone",
                localPlatform: "android",
                trustManager: initTrust,
                approvePeerAsync: (_, _) => Task.FromResult(true));

            await Task.WhenAll(approvedClientTask, approvedServerTask);
            var approvedInit = await approvedClientTask;
            var approvedResp = await approvedServerTask;

            Assert.True(approvedInit.Succeeded, approvedInit.UserMessage);
            Assert.True(approvedResp.Succeeded, approvedResp.UserMessage);
            Assert.Single(initTrust.GetTrustedPeers());
            Assert.Single(respTrust.GetTrustedPeers());
        }
        finally
        {
            CleanupTempDirectories(initTrustDir, respTrustDir);
        }
    }

    private static async Task<Profile2PairOutcome> AcceptAndHandleResponderSessionAsync(
        TlsSocketListener listener,
        PairReadyWindowAuthority? activeWindow,
        bool isBusy,
        string localDeviceName,
        string localPlatform,
        DesktopPersistentTrustManager trustManager,
        Func<VerifiedDesktopPeerIdentity, CancellationToken, Task<bool>> approvePeerAsync)
    {
        await using var conn = await listener.AcceptConnectionAsync();
        var kind = await LanSessionPreambleInspector.InspectAndPrepareAsync(conn);
        Assert.Equal(LanSessionDispatchKind.Profile2Gld2, kind);
        var openMsg = await Profile2WireCodec.ReadMessageAsync(conn.TransportStream);
        var open = Assert.IsType<Profile2Message.SessionOpen>(openMsg);

        return await Profile2PairingEngine.HandleResponderPairOnlySessionAsync(
            conn,
            open,
            activeWindow,
            isBusy,
            localDeviceName,
            localPlatform,
            trustManager,
            approvePeerAsync);
    }

    private static (string InitDir, string RespDir) CreateTempTrustDirectories()
    {
        string initDir = Path.Combine(Path.GetTempPath(), "glide-test-init-" + Guid.NewGuid().ToString("N"));
        string respDir = Path.Combine(Path.GetTempPath(), "glide-test-resp-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(initDir);
        Directory.CreateDirectory(respDir);
        return (initDir, respDir);
    }

    private static void CleanupTempDirectories(params string[] dirs)
    {
        foreach (string dir in dirs)
        {
            try
            {
                if (Directory.Exists(dir))
                {
                    Directory.Delete(dir, recursive: true);
                }
            }
            catch
            {
                // Ignore best-effort cleanup
            }
        }
    }
}
