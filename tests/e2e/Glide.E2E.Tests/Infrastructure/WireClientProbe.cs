using System.Buffers.Binary;
using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Glide.E2E.Tests.Infrastructure;

public sealed record WireV1Invitation(
    string RawUri,
    string InvitationId,
    string Presence,
    string Name,
    string Platform,
    IReadOnlyList<(string Address, int Port)> Endpoints,
    string SpkiSha256,
    string Token,
    string ExpiresAt,
    int TtlSeconds,
    string InvitationHash);

public sealed record WireV2Invitation(
    string RawUri,
    string Purpose,
    string InvitationId,
    string Presence,
    string Name,
    string Platform,
    IReadOnlyList<(string Address, int Port)> Endpoints,
    string SpkiSha256,
    string Token,
    string ExpiresAt,
    int TtlSeconds,
    string InvitationHash);

public enum WireRecordKind : byte
{
    Bootstrap = 0x01,
    BootstrapResult = 0x02,
    Control = 0x10,
    Data = 0x20,
    StreamEnd = 0x21
}

public sealed record WireRecord(WireRecordKind Kind, byte[] Payload);

public sealed record IdentityTransferOutcome(
    bool Succeeded,
    string? ReceiverFingerprintHex,
    string? ErrorCode,
    string? Message);

public sealed record Gld2PairOutcome(
    bool Paired,
    string? ErrorCode,
    string? Message,
    string? ResponderFingerprintHex);

public sealed class WireTlsSession : IAsyncDisposable
{
    public TcpClient Tcp { get; }
    public SslStream Ssl { get; }
    public string RemoteSpkiSha256 { get; }

    internal WireTlsSession(TcpClient tcp, SslStream ssl, string remoteSpkiSha256)
    {
        Tcp = tcp;
        Ssl = ssl;
        RemoteSpkiSha256 = remoteSpkiSha256;
    }

    public async ValueTask DisposeAsync()
    {
        try { await Ssl.DisposeAsync().ConfigureAwait(false); } catch { }
        try { Tcp.Dispose(); } catch { }
    }
}

public sealed class RogueTlsListener : IDisposable
{
    private readonly TcpListener _listener;
    private readonly X509Certificate2 _cert;
    public int Port { get; }
    public string SpkiSha256 { get; }

    public RogueTlsListener()
    {
        using var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var req = new CertificateRequest("CN=Glide-Wire-Probe", ecdsa, HashAlgorithmName.SHA256);
        using var transient = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1));
        _cert = X509CertificateLoader.LoadPkcs12(transient.Export(X509ContentType.Pfx), null);
        SpkiSha256 = Convert.ToHexStringLower(SHA256.HashData(_cert.PublicKey.ExportSubjectPublicKeyInfo()));
        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
    }

    public async Task<WireTlsSession> AcceptAsync(CancellationToken ct = default)
    {
        TcpClient client = await _listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
        client.NoDelay = true;
        var ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
        var opts = new SslServerAuthenticationOptions
        {
            ServerCertificate = _cert,
            ClientCertificateRequired = false,
            EnabledSslProtocols = SslProtocols.Tls13,
            ApplicationProtocols = [new SslApplicationProtocol("glide-lan/1")],
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck
        };
        await ssl.AuthenticateAsServerAsync(opts, ct).ConfigureAwait(false);
        return new WireTlsSession(client, ssl, SpkiSha256);
    }

    public void Dispose()
    {
        try { _listener.Stop(); } catch { }
        _cert.Dispose();
    }
}

public sealed class IdentityV1Peer : IDisposable
{
    private readonly ECDsa _key;

    public string DisplayName { get; }
    public string Platform { get; }
    public byte[] PublicKeyDer { get; }
    public string PublicKeyDerHex { get; }
    public string FingerprintHex { get; }

    public IdentityV1Peer(string displayName, string platform = "windows")
    {
        DisplayName = displayName;
        Platform = platform;
        _key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        PublicKeyDer = _key.ExportSubjectPublicKeyInfo();
        PublicKeyDerHex = Convert.ToHexStringLower(PublicKeyDer);
        FingerprintHex = Convert.ToHexStringLower(SHA256.HashData(PublicKeyDer));
    }

    public byte[] SignTranscript(ReadOnlySpan<byte> transcript) =>
        _key.SignData(transcript, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    public static bool VerifySignature(
        string publicKeyDerHex,
        ReadOnlySpan<byte> transcript,
        string signatureHex,
        out string fingerprintHex)
    {
        fingerprintHex = string.Empty;
        try
        {
            byte[] pubDer = Convert.FromHexString(publicKeyDerHex);
            byte[] sig = Convert.FromHexString(signatureHex);
            using ECDsa ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(pubDer, out int read);
            if (read != pubDer.Length) return false;
            if (!ecdsa.VerifyData(transcript, sig, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence))
            {
                return false;
            }
            fingerprintHex = Convert.ToHexStringLower(SHA256.HashData(pubDer));
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static byte[] BuildIdentityProofBytes(
        string activeSessionId,
        string challengeMessageId,
        string signerRole,
        string verifierRole,
        string signerFingerprintHex,
        string verifierFingerprintHex,
        string challengeNonceHex,
        string channelBindingHex,
        string capabilityHashHex)
    {
        using var ms = new MemoryStream();
        ms.Write("GLIDE-SIGN-V1"u8);
        ms.WriteByte(0);
        ms.WriteByte(1);
        WriteLenString(ms, "identity-v1");
        WriteLenString(ms, "tls-server-spki-sha256-v1");
        WriteLenString(ms, activeSessionId);
        WriteLenString(ms, challengeMessageId);
        WriteLenString(ms, signerRole);
        WriteLenString(ms, verifierRole);
        WriteLenString(ms, signerFingerprintHex);
        WriteLenString(ms, verifierFingerprintHex);
        WriteLenString(ms, challengeNonceHex);
        WriteLenString(ms, channelBindingHex);
        WriteLenString(ms, capabilityHashHex);
        return ms.ToArray();
    }

    public async Task<string> AuthenticateAsSenderAsync(
        WireTlsSession tls,
        string sessionId,
        long nextSeq,
        IEnumerable<string> negotiatedCapabilities,
        string? expectedReceiverFingerprintHex = null,
        CancellationToken ct = default)
    {
        string channelBinding = tls.RemoteSpkiSha256;
        string capHash = Convert.ToHexStringLower(SHA256.HashData(
            WireClientProbe.ComputeCanonicalCapabilitiesBytes(negotiatedCapabilities)));

        string challengeNonce = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        string challengeMsgId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        byte[] challengeBytes = WireClientProbe.BuildEnvelopeBytes(
            "IDENTITY_CHALLENGE",
            sessionId,
            nextSeq,
            new JsonObject
            {
                ["profile"] = "identity-v1",
                ["challengeNonceHex"] = challengeNonce,
                ["verifierFingerprintHex"] = FingerprintHex,
                ["verifierRole"] = "sender",
                ["signerRole"] = "receiver",
                ["channelBindingHex"] = channelBinding,
                ["capabilityHashHex"] = capHash
            },
            messageId: challengeMsgId);

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control, challengeBytes, ct).ConfigureAwait(false);

        JsonObject respEnv = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
        string respType = respEnv["type"]?.GetValue<string>() ?? "";
        if (respType != "IDENTITY_RESPONSE")
        {
            throw new InvalidDataException($"Expected IDENTITY_RESPONSE, got '{respType}': {respEnv.ToJsonString()}");
        }
        JsonObject respBody = respEnv["body"]!.AsObject();
        string signerFp = respBody["signerFingerprintHex"]!.GetValue<string>();
        string pubHex = respBody["publicKeyDerHex"]!.GetValue<string>();
        string sigHex = respBody["signatureHex"]!.GetValue<string>();

        byte[] expectedTranscript = BuildIdentityProofBytes(
            sessionId,
            challengeMsgId,
            "receiver",
            "sender",
            signerFp,
            FingerprintHex,
            challengeNonce,
            channelBinding,
            capHash);

        if (!VerifySignature(pubHex, expectedTranscript, sigHex, out string verifiedFp) ||
            !string.Equals(verifiedFp, signerFp, StringComparison.Ordinal))
        {
            throw new CryptographicException("Receiver identity-v1 signature failed verification.");
        }

        if (!string.IsNullOrWhiteSpace(expectedReceiverFingerprintHex) &&
            !string.Equals(verifiedFp, expectedReceiverFingerprintHex, StringComparison.OrdinalIgnoreCase))
        {
            throw new CryptographicException($"Receiver fingerprint '{verifiedFp}' did not match expected '{expectedReceiverFingerprintHex}'.");
        }

        JsonObject recChallenge = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
        string recType = recChallenge["type"]?.GetValue<string>() ?? "";
        if (recType != "IDENTITY_CHALLENGE")
        {
            throw new InvalidDataException($"Expected reciprocal IDENTITY_CHALLENGE, got '{recType}': {recChallenge.ToJsonString()}");
        }
        string recMsgId = recChallenge["messageId"]!.GetValue<string>();
        JsonObject recBody = recChallenge["body"]!.AsObject();
        string recNonce = recBody["challengeNonceHex"]!.GetValue<string>();
        string recVerifierFp = recBody["verifierFingerprintHex"]!.GetValue<string>();
        if (!string.Equals(recVerifierFp, verifiedFp, StringComparison.Ordinal))
        {
            throw new CryptographicException("Reciprocal challenge verifier fingerprint mismatch.");
        }

        byte[] myTranscript = BuildIdentityProofBytes(
            sessionId,
            recMsgId,
            "sender",
            "receiver",
            FingerprintHex,
            verifiedFp,
            recNonce,
            channelBinding,
            capHash);
        byte[] mySig = SignTranscript(myTranscript);

        byte[] myRespBytes = WireClientProbe.BuildEnvelopeBytes(
            "IDENTITY_RESPONSE",
            sessionId,
            nextSeq + 1,
            new JsonObject
            {
                ["profile"] = "identity-v1",
                ["challengeMessageId"] = recMsgId,
                ["challengeNonceHex"] = recNonce,
                ["signerFingerprintHex"] = FingerprintHex,
                ["publicKeyDerHex"] = PublicKeyDerHex,
                ["signatureHex"] = Convert.ToHexStringLower(mySig)
            });
        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control, myRespBytes, ct).ConfigureAwait(false);

        return verifiedFp;
    }

    public async Task<IdentityTransferOutcome> SendFilesProfile1Async(
        WireV1Invitation inv,
        IReadOnlyList<(string RelativePath, byte[] Content)> files,
        string? expectedReceiverFingerprintHex = null,
        CancellationToken ct = default)
    {
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(
            inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, ct).ConfigureAwait(false);

        string clientSessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, clientSessionId, "sender", ct).ConfigureAwait(false);
        if (bootRes["status"]?.GetValue<string>() != "accepted")
        {
            return new IdentityTransferOutcome(false, null, bootRes["code"]?.GetValue<string>(), bootRes.ToJsonString());
        }
        string sessionId = bootRes["sessionId"]!.GetValue<string>();
        return await RunAuthenticatedTransferCoreAsync(tls, sessionId, files, expectedReceiverFingerprintHex, ct).ConfigureAwait(false);
    }

    public async Task<IdentityTransferOutcome> SendTrustedProfile2Async(
        string host,
        int port,
        string expectedReceiverFingerprintHex,
        IReadOnlyList<(string RelativePath, byte[] Content)> files,
        CancellationToken ct = default)
    {
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(host, port, expectedSpkiSha256: null, ct).ConfigureAwait(false);
        string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        var open = new JsonObject
        {
            ["v"] = 1,
            ["type"] = "SESSION_OPEN",
            ["sessionId"] = sessionId,
            ["purpose"] = "TRUSTED_SEND",
            ["authMethod"] = "identity-v1",
            ["initiatorNonce"] = WireClientProbe.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
            ["initiatorDeviceName"] = DisplayName,
            ["initiatorPlatform"] = Platform,
            ["initiatorIdentityPublicKey"] = WireClientProbe.ToUnpaddedBase64Url(PublicKeyDer),
            ["expectedPeerIdentityFingerprint"] = expectedReceiverFingerprintHex
        };
        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, open, writePreamble: true, ct).ConfigureAwait(false);
        JsonObject ack = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (ack["accepted"]?.GetValue<bool>() != true)
        {
            return new IdentityTransferOutcome(
                false,
                null,
                ack["errorCode"]?.GetValue<string>(),
                ack["message"]?.GetValue<string>());
        }
        return await RunAuthenticatedTransferCoreAsync(tls, sessionId, files, expectedReceiverFingerprintHex, ct).ConfigureAwait(false);
    }

    private async Task<IdentityTransferOutcome> RunAuthenticatedTransferCoreAsync(
        WireTlsSession tls,
        string sessionId,
        IReadOnlyList<(string RelativePath, byte[] Content)> files,
        string? expectedReceiverFingerprintHex,
        CancellationToken ct)
    {
        long seq = 1;
        var helloBody = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
            ["optionalCapabilities"] = new JsonArray("folder-v1", "identity-v1"),
            ["role"] = "sender",
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["displayName"] = DisplayName
        };
        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("HELLO", sessionId, seq++, helloBody), ct).ConfigureAwait(false);

        JsonObject helloAck;
        try
        {
            helloAck = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            return new IdentityTransferOutcome(false, null, "HELLO_REJECTED", ex.Message);
        }

        if (helloAck["type"]?.GetValue<string>() != "HELLO_ACK")
        {
            return new IdentityTransferOutcome(false, null, helloAck["type"]?.GetValue<string>(), helloAck.ToJsonString());
        }

        string[] negotiatedCaps = helloAck["body"]!["capabilities"]!.AsArray()
            .Select(n => n!.GetValue<string>())
            .ToArray();
        if (!negotiatedCaps.Contains("identity-v1", StringComparer.Ordinal))
        {
            return new IdentityTransferOutcome(false, null, "NO_IDENTITY_V1", "Receiver omitted identity-v1.");
        }

        string receiverFp;
        try
        {
            receiverFp = await AuthenticateAsSenderAsync(tls, sessionId, seq, negotiatedCaps, expectedReceiverFingerprintHex, ct).ConfigureAwait(false);
            seq += 2;
        }
        catch (Exception ex)
        {
            return new IdentityTransferOutcome(false, null, "AUTH_FAILED", ex.Message);
        }

        string batchId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        long totalBytes = files.Sum(f => (long)f.Content.Length);
        int pageCount = Math.Max(1, (files.Count + 31) / 32);

        try
        {
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sessionId, seq++, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["itemCount"] = files.Count,
                    ["totalBytes"] = totalBytes.ToString(CultureInfo.InvariantCulture),
                    ["pageCount"] = pageCount
                }), ct).ConfigureAwait(false);

            var itemIds = new List<string>(files.Count);
            for (int p = 0; p < pageCount; p++)
            {
                var pageItems = new JsonArray();
                foreach (var (relPath, content) in files.Skip(p * 32).Take(32))
                {
                    string itemId = Guid.NewGuid().ToString("D").ToLowerInvariant();
                    itemIds.Add(itemId);
                    pageItems.Add(new JsonObject
                    {
                        ["itemId"] = itemId,
                        ["name"] = Path.GetFileName(relPath),
                        ["mime"] = "application/octet-stream",
                        ["sizeBytes"] = content.Length.ToString(CultureInfo.InvariantCulture),
                        ["relativePath"] = relPath.Replace('\\', '/')
                    });
                }

                await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                    WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sessionId, seq++, new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["pageIndex"] = p,
                        ["items"] = pageItems
                    }), ct).ConfigureAwait(false);
            }

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_END", sessionId, seq++, new JsonObject { ["batchId"] = batchId }), ct).ConfigureAwait(false);

            JsonObject decision = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
            if (decision["type"]?.GetValue<string>() != "OFFER_DECISION" ||
                decision["body"]?["accepted"]?.GetValue<bool>() != true)
            {
                return new IdentityTransferOutcome(
                    false,
                    receiverFp,
                    decision["body"]?["code"]?.GetValue<string>() ?? "OFFER_DECLINED",
                    decision.ToJsonString());
            }

            for (int i = 0; i < files.Count; i++)
            {
                byte[] payload = files[i].Content;
                string itemId = itemIds[i];
                string bulkRef = Guid.NewGuid().ToString("D").ToLowerInvariant();
                string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

                await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                    WireClientProbe.BuildEnvelopeBytes("ITEM_START", sessionId, seq++, new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = itemId,
                        ["bulkRef"] = bulkRef,
                        ["sizeBytes"] = payload.Length.ToString(CultureInfo.InvariantCulture)
                    }), ct).ConfigureAwait(false);

                JsonObject ready = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
                if (ready["type"]?.GetValue<string>() != "ITEM_READY")
                {
                    return new IdentityTransferOutcome(false, receiverFp, "ITEM_NOT_READY", ready.ToJsonString());
                }

                const int chunkLimit = 65520;
                for (int off = 0; off < payload.Length; off += chunkLimit)
                {
                    int count = Math.Min(chunkLimit, payload.Length - off);
                    await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Data,
                        WireClientProbe.BuildDataPayload(bulkRef, payload.AsSpan(off, count)), ct).ConfigureAwait(false);
                }
                await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.StreamEnd,
                    WireClientProbe.BuildStreamEndPayload(bulkRef, payload.Length), ct).ConfigureAwait(false);

                await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                    WireClientProbe.BuildEnvelopeBytes("ITEM_END", sessionId, seq++, new JsonObject
                    {
                        ["batchId"] = batchId,
                        ["itemId"] = itemId,
                        ["actualBytes"] = payload.Length.ToString(CultureInfo.InvariantCulture),
                        ["sha256"] = sha
                    }), ct).ConfigureAwait(false);

                JsonObject itemRes = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
                if (itemRes["type"]?.GetValue<string>() != "ITEM_RESULT" ||
                    itemRes["body"]?["status"]?.GetValue<string>() != "verified")
                {
                    return new IdentityTransferOutcome(false, receiverFp, "ITEM_FAILED", itemRes.ToJsonString());
                }
            }

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("BATCH_END", sessionId, seq++, new JsonObject { ["batchId"] = batchId }), ct).ConfigureAwait(false);
            JsonObject batchRes = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
            bool ok = batchRes["type"]?.GetValue<string>() == "BATCH_RESULT" &&
                      batchRes["body"]?["status"]?.GetValue<string>() == "completed";
            return new IdentityTransferOutcome(ok, receiverFp, ok ? null : "BATCH_FAILED", batchRes.ToJsonString());
        }
        catch (Exception ex)
        {
            return new IdentityTransferOutcome(false, receiverFp, "WIRE_ERROR", ex.Message);
        }
    }

    private static void WriteLenString(Stream s, string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        Span<byte> buf = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(buf, (uint)bytes.Length);
        s.Write(buf);
        s.Write(bytes);
    }

    public void Dispose() => _key.Dispose();
}

public static class Gld2Probe
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate nint CtxNewDelegate(int role, IntPtr myName, nuint myLen, IntPtr theirName, nuint theirLen);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void CtxFreeDelegate(nint ctx);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int GenerateMsgDelegate(nint ctx, IntPtr outMsg, out nuint outLen, nuint maxLen, IntPtr pwd, nuint pwdLen);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int ProcessMsgDelegate(nint ctx, IntPtr outKey, out nuint outKeyLen, nuint maxKeyLen, IntPtr theirMsg, nuint theirLen);

    private static readonly Lazy<(CtxNewDelegate New, CtxFreeDelegate Free, GenerateMsgDelegate Gen, ProcessMsgDelegate Proc)> SpakeApi =
        new(() =>
        {
            string libPath = TargetEnvironment.Current.ResolveGlideSpake2NativePath();
            nint handle = NativeLibrary.Load(libPath);
            return (
                Marshal.GetDelegateForFunctionPointer<CtxNewDelegate>(NativeLibrary.GetExport(handle, "glide_spake2_ctx_new")),
                Marshal.GetDelegateForFunctionPointer<CtxFreeDelegate>(NativeLibrary.GetExport(handle, "glide_spake2_ctx_free")),
                Marshal.GetDelegateForFunctionPointer<GenerateMsgDelegate>(NativeLibrary.GetExport(handle, "glide_spake2_generate_msg")),
                Marshal.GetDelegateForFunctionPointer<ProcessMsgDelegate>(NativeLibrary.GetExport(handle, "glide_spake2_process_msg"))
            );
        });

    public static async Task<Gld2PairOutcome> RunSpake2PinAttemptAsync(
        string host,
        int port,
        string pin,
        IdentityV1Peer peer,
        bool approveOnSuccess = true,
        string? expectedPresenceId = null,
        CancellationToken ct = default)
    {
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(host, port, expectedSpkiSha256: null, ct).ConfigureAwait(false);
        string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        byte[] initiatorNonce = RandomNumberGenerator.GetBytes(32);

        var open = new JsonObject
        {
            ["v"] = 1,
            ["type"] = "SESSION_OPEN",
            ["sessionId"] = sessionId,
            ["purpose"] = "PAIR_ONLY",
            ["authMethod"] = "spake2-pin-v1",
            ["initiatorNonce"] = WireClientProbe.ToUnpaddedBase64Url(initiatorNonce),
            ["initiatorDeviceName"] = peer.DisplayName,
            ["initiatorPlatform"] = peer.Platform,
            ["initiatorIdentityPublicKey"] = WireClientProbe.ToUnpaddedBase64Url(peer.PublicKeyDer)
        };
        if (!string.IsNullOrWhiteSpace(expectedPresenceId))
        {
            open["expectedPresenceId"] = expectedPresenceId;
        }

        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, open, writePreamble: true, ct).ConfigureAwait(false);
        JsonObject ack = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (ack["accepted"]?.GetValue<bool>() != true)
        {
            return new Gld2PairOutcome(false, ack["errorCode"]?.GetValue<string>(), ack["message"]?.GetValue<string>(), null);
        }

        byte[] responderNonce = WireClientProbe.FromUnpaddedBase64Url(ack["responderNonce"]!.GetValue<string>());
        byte[] responderSpki = WireClientProbe.FromUnpaddedBase64Url(ack["responderIdentityPublicKey"]!.GetValue<string>());
        string responderFp = Convert.ToHexStringLower(SHA256.HashData(responderSpki));
        string advertisedPresence = ack["advertisedPresenceId"]?.GetValue<string>() ?? (expectedPresenceId ?? string.Empty);

        byte[] aliceName = Encoding.ASCII.GetBytes($"glide-pair-v2:initiator:{sessionId}:").Concat(initiatorNonce).ToArray();
        byte[] bobName = Encoding.ASCII.GetBytes($"glide-pair-v2:responder:{sessionId}:").Concat(responderNonce).ToArray();

        string cleanPin = pin.Replace(" ", "", StringComparison.Ordinal).Trim();
        byte[] pinBytes = Encoding.ASCII.GetBytes(cleanPin);

        var api = SpakeApi.Value;
        nint ctx = nint.Zero;
        IntPtr pAlice = Marshal.AllocHGlobal(aliceName.Length);
        IntPtr pBob = Marshal.AllocHGlobal(bobName.Length);
        IntPtr pPin = Marshal.AllocHGlobal(pinBytes.Length);
        IntPtr pMyMsg = Marshal.AllocHGlobal(32);
        IntPtr pTheirMsg = Marshal.AllocHGlobal(32);
        IntPtr pShared = Marshal.AllocHGlobal(64);
        byte[] sharedSecret = new byte[64];
        try
        {
            Marshal.Copy(aliceName, 0, pAlice, aliceName.Length);
            Marshal.Copy(bobName, 0, pBob, bobName.Length);
            Marshal.Copy(pinBytes, 0, pPin, pinBytes.Length);

            ctx = api.New(0, pAlice, (nuint)aliceName.Length, pBob, (nuint)bobName.Length);
            if (ctx == nint.Zero) throw new CryptographicException("glide_spake2_ctx_new failed.");

            int rcGen = api.Gen(ctx, pMyMsg, out nuint myLen, 32, pPin, (nuint)pinBytes.Length);
            if (rcGen != 1 || myLen != 32) throw new CryptographicException("glide_spake2_generate_msg failed.");

            byte[] mySpakeMsg = new byte[32];
            Marshal.Copy(pMyMsg, mySpakeMsg, 0, 32);

            await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
            {
                ["v"] = 1,
                ["type"] = "PAIR_MSG",
                ["sessionId"] = sessionId,
                ["role"] = "initiator",
                ["spakePublicValue"] = WireClientProbe.ToUnpaddedBase64Url(mySpakeMsg)
            }, writePreamble: false, ct).ConfigureAwait(false);

            JsonObject peerMsg = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
            if (peerMsg["type"]?.GetValue<string>() == "PAIR_RESULT")
            {
                return new Gld2PairOutcome(false, peerMsg["errorCode"]?.GetValue<string>(), peerMsg["message"]?.GetValue<string>(), responderFp);
            }

            byte[] peerSpakeBytes = WireClientProbe.FromUnpaddedBase64Url(peerMsg["spakePublicValue"]!.GetValue<string>());
            Marshal.Copy(peerSpakeBytes, 0, pTheirMsg, 32);
            int rcProc = api.Proc(ctx, pShared, out nuint sharedLen, 64, pTheirMsg, 32);
            if (rcProc != 1 || sharedLen != 64) throw new CryptographicException("glide_spake2_process_msg failed.");
            Marshal.Copy(pShared, sharedSecret, 0, 64);
        }
        finally
        {
            if (ctx != nint.Zero) api.Free(ctx);
            Marshal.FreeHGlobal(pAlice);
            Marshal.FreeHGlobal(pBob);
            Marshal.FreeHGlobal(pPin);
            Marshal.FreeHGlobal(pMyMsg);
            Marshal.FreeHGlobal(pTheirMsg);
            Marshal.FreeHGlobal(pShared);
        }

        return await FinishInitiatorHandshakeAsync(
            tls, sessionId, "spake2-pin-v1",
            initiatorNonce, responderNonce,
            peer, responderSpki, responderFp, advertisedPresence,
            sharedSecret, approveOnSuccess, ct).ConfigureAwait(false);
    }

    public static async Task<Gld2PairOutcome> RunQrV2PairAsync(
        WireV2Invitation inv,
        IdentityV1Peer peer,
        bool approveOnSuccess = true,
        CancellationToken ct = default)
    {
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(
            inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, ct).ConfigureAwait(false);
        string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        byte[] initiatorNonce = RandomNumberGenerator.GetBytes(32);

        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
        {
            ["v"] = 1,
            ["type"] = "SESSION_OPEN",
            ["sessionId"] = sessionId,
            ["purpose"] = "PAIR_ONLY",
            ["authMethod"] = "qr-token-v2",
            ["initiatorNonce"] = WireClientProbe.ToUnpaddedBase64Url(initiatorNonce),
            ["initiatorDeviceName"] = peer.DisplayName,
            ["initiatorPlatform"] = peer.Platform,
            ["initiatorIdentityPublicKey"] = WireClientProbe.ToUnpaddedBase64Url(peer.PublicKeyDer),
            ["expectedPresenceId"] = inv.Presence,
            ["invitationId"] = inv.InvitationId
        }, writePreamble: true, ct).ConfigureAwait(false);

        JsonObject ack = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (ack["accepted"]?.GetValue<bool>() != true)
        {
            return new Gld2PairOutcome(false, ack["errorCode"]?.GetValue<string>(), ack["message"]?.GetValue<string>(), null);
        }

        byte[] responderNonce = WireClientProbe.FromUnpaddedBase64Url(ack["responderNonce"]!.GetValue<string>());
        byte[] responderSpki = WireClientProbe.FromUnpaddedBase64Url(ack["responderIdentityPublicKey"]!.GetValue<string>());
        string responderFp = Convert.ToHexStringLower(SHA256.HashData(responderSpki));
        string advertisedPresence = ack["advertisedPresenceId"]?.GetValue<string>() ?? inv.Presence;

        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
        {
            ["v"] = 1,
            ["type"] = "PAIR_MSG",
            ["sessionId"] = sessionId,
            ["role"] = "initiator",
            ["invitationHash"] = inv.InvitationHash
        }, writePreamble: false, ct).ConfigureAwait(false);

        JsonObject peerMsg = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (peerMsg["type"]?.GetValue<string>() == "PAIR_RESULT")
        {
            return new Gld2PairOutcome(false, peerMsg["errorCode"]?.GetValue<string>(), peerMsg["message"]?.GetValue<string>(), responderFp);
        }

        byte[] tokenBytes = WireClientProbe.FromUnpaddedBase64Url(inv.Token);
        byte[] salt = SHA256.HashData(Encoding.ASCII.GetBytes(inv.InvitationHash.ToLowerInvariant()));
        byte[] prk = HKDF.Extract(HashAlgorithmName.SHA256, tokenBytes, salt);
        byte[] qrSecret = new byte[64];
        HKDF.Expand(HashAlgorithmName.SHA256, prk, qrSecret, "glide-pair-v2-qr-secret"u8);

        return await FinishInitiatorHandshakeAsync(
            tls, sessionId, "qr-token-v2",
            initiatorNonce, responderNonce,
            peer, responderSpki, responderFp, advertisedPresence,
            qrSecret, approveOnSuccess, ct).ConfigureAwait(false);
    }

    public static async Task<Gld2PairOutcome> SendPairOnlyOfferInjectionAsync(
        string host,
        int port,
        IdentityV1Peer peer,
        CancellationToken ct = default)
    {
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(host, port, expectedSpkiSha256: null, ct).ConfigureAwait(false);
        string sessionId = Guid.NewGuid().ToString("D").ToLowerInvariant();
        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
        {
            ["v"] = 1,
            ["type"] = "SESSION_OPEN",
            ["sessionId"] = sessionId,
            ["purpose"] = "PAIR_ONLY",
            ["authMethod"] = "spake2-pin-v1",
            ["initiatorNonce"] = WireClientProbe.ToUnpaddedBase64Url(RandomNumberGenerator.GetBytes(32)),
            ["initiatorDeviceName"] = peer.DisplayName,
            ["initiatorPlatform"] = peer.Platform,
            ["initiatorIdentityPublicKey"] = WireClientProbe.ToUnpaddedBase64Url(peer.PublicKeyDer)
        }, writePreamble: true, ct).ConfigureAwait(false);

        JsonObject ack = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (ack["accepted"]?.GetValue<bool>() != true)
        {
            return new Gld2PairOutcome(false, ack["errorCode"]?.GetValue<string>() ?? "REJECTED", ack["message"]?.GetValue<string>(), null);
        }

        // Inject a transfer OFFER_BEGIN inside a PAIR_ONLY session -> must fail closed!
        try
        {
            await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
            {
                ["v"] = 1,
                ["type"] = "OFFER_BEGIN",
                ["sessionId"] = sessionId
            }, writePreamble: false, ct).ConfigureAwait(false);

            JsonObject resp = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
            return new Gld2PairOutcome(false, resp["errorCode"]?.GetValue<string>() ?? "PROTOCOL_ORDER", resp.ToJsonString(), null);
        }
        catch (Exception ex)
        {
            return new Gld2PairOutcome(false, "CLOSED_ON_INJECTION", ex.Message, null);
        }
    }

    private static async Task<Gld2PairOutcome> FinishInitiatorHandshakeAsync(
        WireTlsSession tls,
        string sessionId,
        string method,
        byte[] initiatorNonce,
        byte[] responderNonce,
        IdentityV1Peer peer,
        byte[] responderSpki,
        string responderFp,
        string advertisedPresence,
        byte[] sharedSecret,
        bool approveOnSuccess,
        CancellationToken ct)
    {
        byte[] transcript = WireClientProbe.ComputePairV2TranscriptBytes(
            method,
            sessionId,
            "initiator",
            "responder",
            Convert.ToHexStringLower(initiatorNonce),
            Convert.ToHexStringLower(responderNonce),
            peer.FingerprintHex,
            peer.PublicKeyDerHex,
            responderFp,
            Convert.ToHexStringLower(responderSpki),
            string.IsNullOrWhiteSpace(advertisedPresence) ? new string('0', 32) : advertisedPresence.ToLowerInvariant(),
            tls.RemoteSpkiSha256.ToLowerInvariant(),
            "PAIR_ONLY");

        byte[] transcriptHash = SHA256.HashData(transcript);
        byte[] prk = HKDF.Extract(HashAlgorithmName.SHA256, sharedSecret, transcriptHash);
        byte[] i2rKey = new byte[32];
        byte[] r2iKey = new byte[32];
        HKDF.Expand(HashAlgorithmName.SHA256, prk, i2rKey, "glide-pair-v2-confirm-i2r"u8);
        HKDF.Expand(HashAlgorithmName.SHA256, prk, r2iKey, "glide-pair-v2-confirm-r2i"u8);

        byte[] myMac = HMACSHA256.HashData(i2rKey, transcriptHash);
        byte[] mySig = peer.SignTranscript(transcript);

        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
        {
            ["v"] = 1,
            ["type"] = "PAIR_CONFIRM",
            ["sessionId"] = sessionId,
            ["role"] = "initiator",
            ["keyConfirmationMac"] = WireClientProbe.ToUnpaddedBase64Url(myMac),
            ["identitySignature"] = WireClientProbe.ToUnpaddedBase64Url(mySig)
        }, writePreamble: false, ct).ConfigureAwait(false);

        JsonObject confirmResp = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (confirmResp["type"]?.GetValue<string>() == "PAIR_RESULT")
        {
            return new Gld2PairOutcome(
                confirmResp["paired"]?.GetValue<bool>() == true,
                confirmResp["errorCode"]?.GetValue<string>(),
                confirmResp["message"]?.GetValue<string>(),
                responderFp);
        }

        byte[] peerMac = WireClientProbe.FromUnpaddedBase64Url(confirmResp["keyConfirmationMac"]!.GetValue<string>());
        byte[] expectedPeerMac = HMACSHA256.HashData(r2iKey, transcriptHash);
        if (!CryptographicOperations.FixedTimeEquals(peerMac, expectedPeerMac))
        {
            return new Gld2PairOutcome(false, "AUTH_FAILED", "Responder keyConfirmationMac mismatch.", responderFp);
        }

        await WireClientProbe.WriteGld2FrameAsync(tls.Ssl, new JsonObject
        {
            ["v"] = 1,
            ["type"] = "PAIR_APPROVAL",
            ["sessionId"] = sessionId,
            ["role"] = "initiator",
            ["approved"] = approveOnSuccess,
            ["reason"] = approveOnSuccess ? null : "DECLINED"
        }, writePreamble: false, ct).ConfigureAwait(false);

        JsonObject next = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (next["type"]?.GetValue<string>() == "PAIR_APPROVAL")
        {
            next = await WireClientProbe.ReadGld2FrameAsync(tls.Ssl, ct).ConfigureAwait(false);
        }
        return new Gld2PairOutcome(
            next["paired"]?.GetValue<bool>() == true,
            next["errorCode"]?.GetValue<string>(),
            next["message"]?.GetValue<string>(),
            responderFp);
    }
}

public static class WireClientProbe
{
    private static readonly Regex Base64UrlRegex = new("^[A-Za-z0-9_-]+$", RegexOptions.Compiled);
    private static readonly Regex Ipv4Regex = new(
        @"^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$",
        RegexOptions.Compiled);

    public static string ToUnpaddedBase64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[] FromUnpaddedBase64Url(string text)
    {
        if (!Base64UrlRegex.IsMatch(text))
        {
            throw new FormatException("Non-base64url characters or '=' padding present.");
        }
        string padded = text.Replace('-', '+').Replace('_', '/');
        int mod = padded.Length % 4;
        if (mod == 1) throw new FormatException("Invalid base64url length.");
        if (mod > 0) padded += new string('=', 4 - mod);
        byte[] decoded = Convert.FromBase64String(padded);
        if (!string.Equals(ToUnpaddedBase64Url(decoded), text, StringComparison.Ordinal))
        {
            throw new FormatException("Non-canonical base64url trailing bit residue.");
        }
        return decoded;
    }

    public static bool IsValidLanIpLiteral(string addr)
    {
        if (string.IsNullOrWhiteSpace(addr) || addr.Contains('/') || addr.Contains('%') || addr.Contains(' ') || addr.Contains("://"))
        {
            return false;
        }
        if (Ipv4Regex.IsMatch(addr))
        {
            return addr.Split('.').All(part => !(part.Length > 1 && part.StartsWith('0')));
        }
        return addr.Contains(':') && IPAddress.TryParse(addr, out var ip) && ip.AddressFamily == AddressFamily.InterNetworkV6;
    }

    public static string BuildV1Uri(
        string host,
        int port,
        string spkiSha256,
        string? invitationId = null,
        string? presence = null,
        string name = "WireProbe-Peer",
        string platform = "windows",
        long? expiresAtEpoch = null,
        int ttlSeconds = 120,
        byte[]? rawTokenBytes = null)
    {
        byte[] tokenBytes = rawTokenBytes ?? RandomNumberGenerator.GetBytes(32);
        long exp = expiresAtEpoch ?? (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + ttlSeconds);
        var obj = new JsonObject
        {
            ["endpoints"] = new JsonArray
            {
                new JsonObject { ["address"] = host, ["port"] = port }
            },
            ["expiresAt"] = exp.ToString(CultureInfo.InvariantCulture),
            ["invitationId"] = invitationId ?? Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["name"] = name,
            ["platform"] = platform,
            ["presence"] = presence ?? Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16)),
            ["profile"] = 1,
            ["protocol"] = 1,
            ["spkiSha256"] = spkiSha256,
            ["token"] = ToUnpaddedBase64Url(tokenBytes),
            ["transport"] = "lan-tls",
            ["ttlSeconds"] = ttlSeconds,
            ["v"] = 1
        };
        byte[] jsonBytes = Encoding.UTF8.GetBytes(obj.ToJsonString());
        return $"glide://pair/v1#{ToUnpaddedBase64Url(jsonBytes)}";
    }

    public static string BuildV2Uri(
        string host,
        int port,
        string spkiSha256,
        string? invitationId = null,
        string? presence = null,
        string name = "WireProbe-V2",
        string platform = "windows",
        string purpose = "pair-only",
        long? expiresAtEpoch = null,
        int ttlSeconds = 120,
        byte[]? rawTokenBytes = null)
    {
        byte[] tokenBytes = rawTokenBytes ?? RandomNumberGenerator.GetBytes(32);
        long exp = expiresAtEpoch ?? (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + ttlSeconds);
        var obj = new JsonObject
        {
            ["endpoints"] = new JsonArray
            {
                new JsonObject { ["address"] = host, ["port"] = port }
            },
            ["expiresAt"] = exp.ToString(CultureInfo.InvariantCulture),
            ["invitationId"] = invitationId ?? Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["name"] = name,
            ["platform"] = platform,
            ["presence"] = presence ?? Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16)),
            ["profile"] = 2,
            ["protocol"] = 1,
            ["purpose"] = purpose,
            ["spkiSha256"] = spkiSha256,
            ["token"] = ToUnpaddedBase64Url(tokenBytes),
            ["transport"] = "lan-tls",
            ["ttlSeconds"] = ttlSeconds,
            ["v"] = 2
        };
        byte[] jsonBytes = Encoding.UTF8.GetBytes(obj.ToJsonString());
        return $"glide://pair/v2#{ToUnpaddedBase64Url(jsonBytes)}";
    }

    public static WireV1Invitation DecodeV1Uri(string uri)
    {
        if (string.IsNullOrWhiteSpace(uri) || !uri.StartsWith("glide://pair/v1#", StringComparison.Ordinal))
        {
            throw new FormatException("Invalid v1 URI prefix.");
        }
        if (uri.Length > 2768) throw new FormatException("URI exceeds 2768 chars.");
        byte[] jsonBytes = FromUnpaddedBase64Url(uri["glide://pair/v1#".Length..]);
        if (jsonBytes.Length > 2048) throw new FormatException("JSON exceeds 2048 bytes.");
        string hash = Convert.ToHexStringLower(SHA256.HashData(jsonBytes));

        using var doc = JsonDocument.Parse(jsonBytes, new JsonDocumentOptions { MaxDepth = 4 });
        JsonElement root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object) throw new FormatException("Root must be object.");

        string[] expectedKeys =
        [
            "v", "transport", "profile", "protocol", "invitationId",
            "presence", "name", "platform", "endpoints", "spkiSha256",
            "token", "expiresAt", "ttlSeconds"
        ];
        var actualKeys = root.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal);
        if (actualKeys.Count != expectedKeys.Length || !expectedKeys.All(actualKeys.Contains))
        {
            throw new FormatException("v1 URI must contain exact 13 root keys.");
        }

        if (root.GetProperty("v").GetInt32() != 1) throw new FormatException("v must be 1.");
        if (root.GetProperty("transport").GetString() != "lan-tls") throw new FormatException("Invalid transport.");
        if (root.GetProperty("profile").GetInt32() != 1) throw new FormatException("profile must be 1.");
        if (root.GetProperty("protocol").GetInt32() != 1) throw new FormatException("protocol must be 1.");
        int ttl = root.GetProperty("ttlSeconds").GetInt32();
        if (ttl <= 0) throw new FormatException("ttlSeconds must be > 0.");

        var endpoints = new List<(string, int)>();
        foreach (JsonElement ep in root.GetProperty("endpoints").EnumerateArray())
        {
            string addr = ep.GetProperty("address").GetString()!;
            int port = ep.GetProperty("port").GetInt32();
            if (!IsValidLanIpLiteral(addr) || port is < 1 or > 65535)
            {
                throw new FormatException($"Invalid endpoint {addr}:{port}.");
            }
            endpoints.Add((addr, port));
        }
        if (endpoints.Count is < 1 or > 4) throw new FormatException("endpoints count must be 1..4.");

        string token = root.GetProperty("token").GetString()!;
        byte[] decodedToken = FromUnpaddedBase64Url(token);
        if (decodedToken.Length != 32) throw new FormatException("token must decode to 32 bytes.");

        return new WireV1Invitation(
            uri,
            root.GetProperty("invitationId").GetString()!,
            root.GetProperty("presence").GetString()!,
            root.GetProperty("name").GetString()!,
            root.GetProperty("platform").GetString()!,
            endpoints,
            root.GetProperty("spkiSha256").GetString()!,
            token,
            root.GetProperty("expiresAt").GetString()!,
            ttl,
            hash);
    }

    public static WireV2Invitation DecodeV2Uri(string uri)
    {
        if (string.IsNullOrWhiteSpace(uri) || !uri.StartsWith("glide://pair/v2#", StringComparison.Ordinal))
        {
            throw new FormatException("Invalid v2 URI prefix.");
        }
        if (uri.Length > 2768) throw new FormatException("URI exceeds 2768 chars.");
        byte[] jsonBytes = FromUnpaddedBase64Url(uri["glide://pair/v2#".Length..]);
        if (jsonBytes.Length > 2048) throw new FormatException("JSON exceeds 2048 bytes.");
        string hash = Convert.ToHexStringLower(SHA256.HashData(jsonBytes));

        using var doc = JsonDocument.Parse(jsonBytes, new JsonDocumentOptions { MaxDepth = 4 });
        JsonElement root = doc.RootElement;
        string[] expectedKeys =
        [
            "v", "purpose", "transport", "profile", "protocol", "invitationId",
            "presence", "name", "platform", "endpoints", "spkiSha256",
            "token", "expiresAt", "ttlSeconds"
        ];
        var actualKeys = root.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal);
        if (actualKeys.Count != expectedKeys.Length || !expectedKeys.All(actualKeys.Contains))
        {
            throw new FormatException("v2 URI must contain exact 14 root keys.");
        }

        if (root.GetProperty("v").GetInt32() != 2) throw new FormatException("v must be 2.");
        string purpose = root.GetProperty("purpose").GetString()!;
        if (purpose != "pair-only") throw new FormatException($"Invalid v2 purpose '{purpose}'.");
        string platform = root.GetProperty("platform").GetString()!;
        if (platform is not ("android" or "windows" or "macos" or "ios" or "linux" or "unknown"))
        {
            throw new FormatException($"Invalid v2 platform '{platform}'.");
        }
        int ttl = root.GetProperty("ttlSeconds").GetInt32();
        if (ttl is <= 0 or > 120) throw new FormatException($"v2 ttlSeconds {ttl} must be in 1..120.");

        var endpoints = new List<(string, int)>();
        foreach (JsonElement ep in root.GetProperty("endpoints").EnumerateArray())
        {
            string addr = ep.GetProperty("address").GetString()!;
            int port = ep.GetProperty("port").GetInt32();
            if (!IsValidLanIpLiteral(addr) || port is < 1 or > 65535)
            {
                throw new FormatException($"Invalid endpoint {addr}:{port}.");
            }
            endpoints.Add((addr, port));
        }
        if (endpoints.Count is < 1 or > 4) throw new FormatException("endpoints count must be 1..4.");

        string token = root.GetProperty("token").GetString()!;
        byte[] decodedToken = FromUnpaddedBase64Url(token);
        if (decodedToken.Length != 32) throw new FormatException("token must decode to 32 bytes.");

        return new WireV2Invitation(
            uri,
            purpose,
            root.GetProperty("invitationId").GetString()!,
            root.GetProperty("presence").GetString()!,
            root.GetProperty("name").GetString()!,
            platform,
            endpoints,
            root.GetProperty("spkiSha256").GetString()!,
            token,
            root.GetProperty("expiresAt").GetString()!,
            ttl,
            hash);
    }

    public static async Task<WireTlsSession> ConnectTlsAsync(
        string host,
        int port,
        string? expectedSpkiSha256,
        CancellationToken ct = default)
    {
        var tcp = new TcpClient { NoDelay = true };
        await tcp.ConnectAsync(host, port, ct).ConfigureAwait(false);
        string? observedSpki = null;
        var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false);
        try
        {
            var opts = new SslClientAuthenticationOptions
            {
                TargetHost = host,
                EnabledSslProtocols = SslProtocols.Tls13,
                ApplicationProtocols = [new SslApplicationProtocol("glide-lan/1")],
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                RemoteCertificateValidationCallback = (_, cert, _, _) =>
                {
                    if (cert == null) return false;
                    using var x509 = cert is X509Certificate2 c2 ? new X509Certificate2(c2) : new X509Certificate2(cert);
                    observedSpki = Convert.ToHexStringLower(SHA256.HashData(x509.PublicKey.ExportSubjectPublicKeyInfo()));
                    if (!string.IsNullOrEmpty(expectedSpkiSha256))
                    {
                        return string.Equals(observedSpki, expectedSpkiSha256, StringComparison.Ordinal);
                    }
                    return true;
                }
            };
            await ssl.AuthenticateAsClientAsync(opts, ct).ConfigureAwait(false);
            return new WireTlsSession(tcp, ssl, observedSpki ?? string.Empty);
        }
        catch
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            tcp.Dispose();
            throw;
        }
    }

    public static async Task WriteLanRecordAsync(Stream stream, WireRecordKind kind, ReadOnlyMemory<byte> payload, CancellationToken ct = default)
    {
        byte[] frame = new byte[5 + payload.Length];
        frame[0] = (byte)kind;
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(1, 4), (uint)payload.Length);
        if (!payload.IsEmpty)
        {
            payload.Span.CopyTo(frame.AsSpan(5));
        }
        await stream.WriteAsync(frame, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    public static async Task<WireRecord?> ReadLanRecordAsync(Stream stream, CancellationToken ct = default)
    {
        byte[] header = new byte[5];
        int readHeader = 0;
        while (readHeader < 5)
        {
            int n = await stream.ReadAsync(header.AsMemory(readHeader, 5 - readHeader), ct).ConfigureAwait(false);
            if (n == 0)
            {
                if (readHeader == 0) return null;
                throw new EndOfStreamException("Truncated LanRecord header.");
            }
            readHeader += n;
        }

        uint len = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(1, 4));
        if (len > 65536)
        {
            throw new InvalidDataException($"LanRecord payload length {len} exceeds 64 KiB ceiling.");
        }

        byte[] payload = new byte[len];
        int readBody = 0;
        while (readBody < payload.Length)
        {
            int n = await stream.ReadAsync(payload.AsMemory(readBody, payload.Length - readBody), ct).ConfigureAwait(false);
            if (n == 0) throw new EndOfStreamException("Truncated LanRecord payload.");
            readBody += n;
        }
        return new WireRecord((WireRecordKind)header[0], payload);
    }

    public static async Task WriteGld2FrameAsync(Stream stream, JsonObject payload, bool writePreamble, CancellationToken ct = default)
    {
        if (writePreamble)
        {
            await stream.WriteAsync("GLD2"u8.ToArray(), ct).ConfigureAwait(false);
        }
        byte[] json = Encoding.UTF8.GetBytes(payload.ToJsonString());
        byte[] lenPrefix = new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(lenPrefix, (uint)json.Length);
        await stream.WriteAsync(lenPrefix, ct).ConfigureAwait(false);
        await stream.WriteAsync(json, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    public static async Task<JsonObject> ReadGld2FrameAsync(Stream stream, CancellationToken ct = default)
    {
        byte[] lenBuf = new byte[4];
        await ReadExactAsync(stream, lenBuf, ct).ConfigureAwait(false);
        uint len = BinaryPrimitives.ReadUInt32BigEndian(lenBuf);
        if (len is 0 or > 16384) throw new InvalidDataException($"Invalid GLD2 frame length {len}.");
        byte[] jsonBuf = new byte[len];
        await ReadExactAsync(stream, jsonBuf, ct).ConfigureAwait(false);
        return JsonNode.Parse(jsonBuf)?.AsObject() ?? throw new InvalidDataException("Expected JSON object.");
    }

    public static byte[] BuildEnvelopeBytes(
        string type,
        string sessionId,
        long seq,
        JsonObject body,
        int v = 1,
        string? messageId = null)
    {
        var env = new JsonObject
        {
            ["v"] = v,
            ["type"] = type,
            ["sessionId"] = sessionId,
            ["messageId"] = messageId ?? Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["seq"] = seq,
            ["body"] = body
        };
        return Encoding.UTF8.GetBytes(env.ToJsonString());
    }

    public static JsonObject ParseControlJson(WireRecord record)
    {
        if (record.Kind != WireRecordKind.Control)
        {
            throw new InvalidDataException($"Expected Control record, got {record.Kind}.");
        }
        return JsonNode.Parse(record.Payload)?.AsObject()
            ?? throw new InvalidDataException("Control payload was not a JSON object.");
    }

    public static byte[] BuildDataPayload(string bulkRefUuid, ReadOnlySpan<byte> chunk)
    {
        var guid = Guid.ParseExact(bulkRefUuid, "D");
        byte[] buf = new byte[16 + chunk.Length];
        guid.TryWriteBytes(buf.AsSpan(0, 16), bigEndian: true, out _);
        chunk.CopyTo(buf.AsSpan(16));
        return buf;
    }

    public static byte[] BuildStreamEndPayload(string bulkRefUuid, long actualBytes)
    {
        var guid = Guid.ParseExact(bulkRefUuid, "D");
        byte[] buf = new byte[24];
        guid.TryWriteBytes(buf.AsSpan(0, 16), bigEndian: true, out _);
        BinaryPrimitives.WriteUInt64BigEndian(buf.AsSpan(16, 8), (ulong)actualBytes);
        return buf;
    }

    public static async Task<JsonObject> SendBootstrapAsync(
        Stream stream,
        WireV1Invitation inv,
        string clientSessionId,
        string role = "sender",
        CancellationToken ct = default)
    {
        var bootstrap = new JsonObject
        {
            ["v"] = 1,
            ["invitationId"] = inv.InvitationId,
            ["token"] = inv.Token,
            ["invitationHash"] = inv.InvitationHash,
            ["sessionId"] = clientSessionId,
            ["role"] = role
        };
        await WriteLanRecordAsync(stream, WireRecordKind.Bootstrap, Encoding.UTF8.GetBytes(bootstrap.ToJsonString()), ct).ConfigureAwait(false);
        WireRecord? resp = await ReadLanRecordAsync(stream, ct).ConfigureAwait(false);
        if (resp == null || resp.Kind != WireRecordKind.BootstrapResult)
        {
            throw new InvalidDataException($"Expected BootstrapResult, got {resp?.Kind.ToString() ?? "EOF"}.");
        }
        return JsonNode.Parse(resp.Payload)?.AsObject() ?? throw new InvalidDataException("Invalid BootstrapResult JSON.");
    }

    public static async Task<JsonObject> ReadControlEnvelopeAsync(Stream stream, CancellationToken ct = default)
    {
        WireRecord? rec = await ReadLanRecordAsync(stream, ct).ConfigureAwait(false);
        if (rec == null) throw new EndOfStreamException("Connection closed before expected control record.");
        return ParseControlJson(rec);
    }

    public static byte[] ComputeCanonicalCapabilitiesBytes(IEnumerable<string> capabilities)
    {
        string[] normalized = capabilities.Distinct(StringComparer.Ordinal).OrderBy(static s => s, StringComparer.Ordinal).ToArray();
        using var ms = new MemoryStream();
        ms.Write("GLIDE-CAPS-V1"u8);
        ms.WriteByte(0);
        WriteUInt32(ms, (uint)normalized.Length);
        foreach (string cap in normalized)
        {
            WriteLenString(ms, cap);
        }
        return ms.ToArray();
    }

    public static byte[] ComputePairV2TranscriptBytes(
        string method,
        string sessionId,
        string initiatorRole,
        string responderRole,
        string initiatorNonceHex,
        string responderNonceHex,
        string initiatorFpHex,
        string initiatorPubDerHex,
        string responderFpHex,
        string responderPubDerHex,
        string responderPresenceHex,
        string tlsServerSpkiSha256Hex,
        string purpose)
    {
        using var ms = new MemoryStream();
        ms.Write("GLIDE-PAIR-V2"u8);
        ms.WriteByte(0);
        WriteLenString(ms, method);
        WriteLenString(ms, sessionId);
        WriteLenString(ms, initiatorRole);
        WriteLenString(ms, responderRole);
        WriteLenString(ms, initiatorNonceHex);
        WriteLenString(ms, responderNonceHex);
        WriteLenString(ms, initiatorFpHex);
        WriteLenString(ms, initiatorPubDerHex);
        WriteLenString(ms, responderFpHex);
        WriteLenString(ms, responderPubDerHex);
        WriteLenString(ms, responderPresenceHex);
        WriteLenString(ms, tlsServerSpkiSha256Hex);
        WriteLenString(ms, purpose);
        return ms.ToArray();
    }

    private static void WriteLenString(Stream s, string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        WriteUInt32(s, (uint)bytes.Length);
        s.Write(bytes);
    }

    private static void WriteUInt32(Stream s, uint v)
    {
        Span<byte> buf = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(buf, v);
        s.Write(buf);
    }

    private static async Task ReadExactAsync(Stream s, byte[] buf, CancellationToken ct)
    {
        int read = 0;
        while (read < buf.Length)
        {
            int n = await s.ReadAsync(buf.AsMemory(read, buf.Length - read), ct).ConfigureAwait(false);
            if (n == 0) throw new EndOfStreamException("Unexpected EOF.");
            read += n;
        }
    }
}
