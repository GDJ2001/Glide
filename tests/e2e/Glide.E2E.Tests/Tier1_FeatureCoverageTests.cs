using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Glide.E2E.Tests.Infrastructure;

namespace Glide.E2E.Tests;

public sealed class Tier1_FeatureCoverageTests
{
    // =========================================================================
    // UF1: Send Workflow & File Staging (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-001")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_001_SingleSmallFile_LoopbackTransfer_MatchesSha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-001");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "hello.bin"), 4096, seed: 101);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-002")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_002_HostedQuickShareQr_SenderHostsListener_MatchesSha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-002");
        CorpusVerifier.CreateSmallBatch(sandbox.SourceDir, count: 3, baseSize: 2048, seedOffset: 200);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (sender, receiver) = await E2ETestHelpers.RunHostedSenderTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "HostedSender");
        E2ETestHelpers.AssertResultSuccess(receiver, "ConnectReceiver");

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-003")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_003_NestedDirectoryTree_PreservesRelativePaths_MatchesSha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-003");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "a", "b", "c", "deep.bin"), 8192, 301);
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "a", "sibling.bin"), 2048, 302);
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "x", "b", "c", "deep.bin"), 4096, 303);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-004")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_004_UnicodeAndSpacesFilenames_TransferAccurately()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-004");
        CorpusVerifier.WriteUtf8TextFile(Path.Combine(sandbox.SourceDir, "Unicode_🚀_测试_файл.txt"), "Unicode content 🌟✨");
        CorpusVerifier.WriteUtf8TextFile(Path.Combine(sandbox.SourceDir, "Project Spec With Multiple Spaces.txt"), "Spaces content");
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-005")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_005_WindowsWpfSendTab_QuickQrAndTrustedMethods_Smoke()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-005");
        string file = Path.Combine(sandbox.SourceDir, "wpf-send.txt");
        CorpusVerifier.WriteUtf8TextFile(file, "wpf-send-payload");

        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);
        int fwdCode = await ui.ForwardActivationAsync(file);
        Assert.Equal(0, fwdCode);

        await ui.AssertVisibleByIdAsync("SelectedFilesList");
        await ui.AssertVisibleByNameAsync("wpf-send.txt");
        await ui.AssertVisibleByNameAsync("Quick share with QR code");
        await ui.AssertVisibleByNameAsync("Paired device");

        await ui.ClickByNameAsync("Quick share with QR code");
        await ui.WaitForIdAsync("HostedQrImage");
        await ui.AssertVisibleByIdAsync("CancelQuickShareButton");
        await ui.ClickByIdAsync("CancelQuickShareButton");
        await ui.AssertAbsentByIdAsync("HostedQrImage");

        await ui.ClickByNameAsync("Clear selected files");
        await ui.AssertVisibleByIdAsync("EmptyAddFilesButton");
    }

    // =========================================================================
    // UF2: Receive Workflow & Listener Lifecycle (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-006")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_006_EphemeralPortListener_EmitsValidV1Invitation()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-006");
        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--timeout-seconds", "5"
            ]);

        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        int port = invEvt.GetProperty("port").GetInt32();
        string uri = invEvt.GetProperty("invitationUri").GetString()!;
        string spki = invEvt.GetProperty("spkiSha256").GetString()!;

        Assert.InRange(port, 1, 65535);
        WireV1Invitation decoded = WireClientProbe.DecodeV1Uri(uri);
        Assert.Equal(spki, decoded.SpkiSha256);
        Assert.Contains(decoded.Endpoints, ep => ep.Address == "127.0.0.1" && ep.Port == port);
        listener.KillNow();
        await listener.WaitForExitAsync(TimeSpan.FromSeconds(5));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-007")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_007_OpaqueWireClient_CompletesFullProfile1TransferToListener()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-007");
        byte[] payload = new byte[128 * 1024];
        new Random(707).NextBytes(payload);
        string expectedSha = Convert.ToHexStringLower(SHA256.HashData(payload));

        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ]);

        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
        Assert.Equal(inv.SpkiSha256, tls.RemoteSpkiSha256);

        JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
        Assert.Equal("accepted", bootRes["status"]!.GetValue<string>());
        string sessionId = bootRes["sessionId"]!.GetValue<string>();
        long seq = 1;

        var helloBody = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
            ["optionalCapabilities"] = new JsonArray(),
            ["role"] = "sender",
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
            ["displayName"] = "WireProbe"
        };
        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("HELLO", sessionId, seq++, helloBody), cts.Token);
        JsonObject helloAck = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);
        Assert.Equal("HELLO_ACK", helloAck["type"]!.GetValue<string>());

        string batchId = Guid.NewGuid().ToString("D");
        string itemId = Guid.NewGuid().ToString("D");
        string bulkRef = Guid.NewGuid().ToString("D");

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sessionId, seq++, new JsonObject
            {
                ["batchId"] = batchId,
                ["itemCount"] = 1,
                ["totalBytes"] = payload.Length.ToString(),
                ["pageCount"] = 1
            }), cts.Token);

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sessionId, seq++, new JsonObject
            {
                ["batchId"] = batchId,
                ["pageIndex"] = 0,
                ["items"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["itemId"] = itemId,
                        ["name"] = "wire-payload.bin",
                        ["mime"] = "application/octet-stream",
                        ["sizeBytes"] = payload.Length.ToString(),
                        ["relativePath"] = "wire-payload.bin"
                    }
                }
            }), cts.Token);

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("OFFER_END", sessionId, seq++, new JsonObject { ["batchId"] = batchId }), cts.Token);

        JsonObject decision = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);
        Assert.Equal("OFFER_DECISION", decision["type"]!.GetValue<string>());
        Assert.True(decision["body"]!["accepted"]!.GetValue<bool>());

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("ITEM_START", sessionId, seq++, new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = itemId,
                ["bulkRef"] = bulkRef,
                ["sizeBytes"] = payload.Length.ToString()
            }), cts.Token);
        JsonObject ready = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);
        Assert.Equal("ITEM_READY", ready["type"]!.GetValue<string>());

        const int chunkLimit = 65520;
        for (int off = 0; off < payload.Length; off += chunkLimit)
        {
            int count = Math.Min(chunkLimit, payload.Length - off);
            byte[] dataFrame = WireClientProbe.BuildDataPayload(bulkRef, payload.AsSpan(off, count));
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Data, dataFrame, cts.Token);
        }
        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.StreamEnd,
            WireClientProbe.BuildStreamEndPayload(bulkRef, payload.Length), cts.Token);

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("ITEM_END", sessionId, seq++, new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = itemId,
                ["actualBytes"] = payload.Length.ToString(),
                ["sha256"] = expectedSha
            }), cts.Token);

        JsonObject itemRes = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);
        Assert.Equal("ITEM_RESULT", itemRes["type"]!.GetValue<string>());
        Assert.Equal("verified", itemRes["body"]!["status"]!.GetValue<string>());

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("BATCH_END", sessionId, seq++, new JsonObject { ["batchId"] = batchId }), cts.Token);
        JsonObject batchRes = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);
        Assert.Equal("BATCH_RESULT", batchRes["type"]!.GetValue<string>());
        Assert.Equal("completed", batchRes["body"]!["status"]!.GetValue<string>());

        ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(15));
        E2ETestHelpers.AssertResultSuccess(recvExit, "Receiver");

        string destFile = Path.Combine(sandbox.DestDir, "wire-payload.bin");
        Assert.True(File.Exists(destFile));
        Assert.Equal(payload.Length, new FileInfo(destFile).Length);
        Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(destFile));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-008")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_008_QuarantineStagingCleanup_LeavesZeroPartOrQuarantineFiles()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-008");
        CorpusVerifier.CreateSmallBatch(sandbox.SourceDir, count: 5, baseSize: 4096, seedOffset: 80);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        JsonElement resEvt = receiver.FindEvent("result")!.Value;
        Assert.True(resEvt.GetProperty("quarantineEmpty").GetBoolean());
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-009")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_009_LinuxVmXdgIsolatedReceive_StartsListenerCleanly()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-009");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
mkdir -p ""$HOME/Downloads"" ""$HOME/src""
dd if=/dev/urandom of=""$HOME/src/vm-recv-test.bin"" bs=1024 count=8 status=none
SRC_SHA=""$(sha256sum ""$HOME/src/vm-recv-test.bin"" | awk '{print $1}')""

FIFO=""$XDG_RUNTIME_DIR/recv.in""
mkfifo ""$FIFO""
(
  # Feed offer accept 'y' and enrollment decline 'n'
  printf 'y\nn\n' > ""$FIFO""
) &
FEEDER_PID=$!
glide receive --port 0 < ""$FIFO"" > ""$XDG_RUNTIME_DIR/recv.log"" 2>&1 &
RECV_PID=$!
trap 'kill $RECV_PID $FEEDER_PID 2>/dev/null || true' EXIT

URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$XDG_RUNTIME_DIR/recv.log"" 2>/dev/null && grep -q '\[READY\]' ""$XDG_RUNTIME_DIR/recv.log"" 2>/dev/null; then
    URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$XDG_RUNTIME_DIR/recv.log"" | head -n 1)""
    break
  fi
  sleep 0.25
done
test -n ""$URI""
test -e ""$XDG_RUNTIME_DIR/glide/listener.lock""

printf 'n\n' | glide send --pair ""$URI"" ""$HOME/src/vm-recv-test.bin""
wait $RECV_PID

DST_FILE=""$HOME/Downloads/Glide/vm-recv-test.bin""
if [ ! -f ""$DST_FILE"" ]; then
  DST_FILE=""$(find ""$HOME/Downloads"" -name 'vm-recv-test.bin' | head -n 1)""
fi
test -f ""$DST_FILE""
DST_SHA=""$(sha256sum ""$DST_FILE"" | awk '{print $1}')""
test ""$SRC_SHA"" = ""$DST_SHA""
STAGING_COUNT=""$(find ""$XDG_DATA_HOME/glide/staging"" -type f 2>/dev/null | wc -l)""
test ""$STAGING_COUNT"" -eq 0
echo ""VM_RECV_OK sha=$DST_SHA""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-009", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_RECV_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-009");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-010")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_010_WindowsWpfReceiveControls_ToggleZoomCopyRefresh()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-010");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        await ui.SwitchTabAsync("NavReceive");
        await ui.AssertVisibleByNameAsync("Stop receiving");

        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        string uri1Raw = await ui.ReadClipboardAsync();
        WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri(uri1Raw);

        await ui.ClickByIdAsync("ReceiveShowQrButton");
        await ui.WaitForIdAsync("ZoomQrImage");
        await ui.ClickByNameAsync("Close");
        await ui.AssertAbsentByIdAsync("ZoomQrImage");

        await ui.ClickByIdAsync("ReceiveRefreshQrButton");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        string uri2Raw = await ui.ReadClipboardAsync();
        WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri(uri2Raw);
        Assert.NotEqual(inv1.InvitationId, inv2.InvitationId);

        await ui.ClickByIdAsync("ReceiveControlButton");
        await ui.AssertVisibleByNameAsync("Start receiving");
        await ui.ClickByIdAsync("ReceiveControlButton");
        await ui.AssertVisibleByNameAsync("Stop receiving");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        WireV1Invitation liveInv = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

        byte[] payload = new byte[8192];
        new Random(1010).NextBytes(payload);
        string expectedSha = Convert.ToHexStringLower(SHA256.HashData(payload));

        using var peer = new IdentityV1Peer("WinUi-T1-010");
        var sendTask = peer.SendFilesProfile1Async(liveInv, [("recv-control.bin", payload)]);
        await ui.WaitForNameAsync("Not Now", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Not Now");
        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Accept transfer");

        IdentityTransferOutcome outcome = await sendTask;
        Assert.True(outcome.Succeeded, $"Transfer failed: {outcome.ErrorCode} {outcome.Message}");
        string delivered = Path.Combine(sandbox.DestDir, "recv-control.bin");
        Assert.True(File.Exists(delivered));
        Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(delivered));
    }

    // =========================================================================
    // UF3: Device Pairing (Pair devices / Profile 2 QR & PIN) (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-011")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "Loopback")]
    public void E2E_T1_011_V2PairOnlyUri_GoldenFixtureAndRoundTrip_MatchContract()
    {
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        Assert.True(File.Exists(fixturePath), $"Missing golden fixture: {fixturePath}");

        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        JsonElement v2 = doc.RootElement.GetProperty("v2PairOnlyInvitation");
        string validUri = v2.GetProperty("validUri").GetString()!;
        WireV2Invitation decoded = WireClientProbe.DecodeV2Uri(validUri);

        Assert.Equal(v2.GetProperty("expectedInvitationHash").GetString(), decoded.InvitationHash);
        Assert.Equal(v2.GetProperty("expectedInvitationId").GetString(), decoded.InvitationId);
        Assert.Equal(v2.GetProperty("expectedPurpose").GetString(), decoded.Purpose);
        Assert.Equal(v2.GetProperty("expectedName").GetString(), decoded.Name);
        Assert.Equal(v2.GetProperty("expectedPlatform").GetString(), decoded.Platform);
        Assert.Equal(v2.GetProperty("expectedPresence").GetString(), decoded.Presence);
        Assert.Equal(v2.GetProperty("expectedSpkiSha256").GetString(), decoded.SpkiSha256);
        Assert.Equal(v2.GetProperty("expectedToken").GetString(), decoded.Token);
        Assert.Equal(v2.GetProperty("expectedTtlSeconds").GetInt32(), decoded.TtlSeconds);
    }

    [Fact]
    [Trait("Id", "E2E-T1-012")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "Loopback")]
    public void E2E_T1_012_Profile2TranscriptAndGld2Framing_MatchesGoldenVector()
    {
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        JsonElement t = doc.RootElement.GetProperty("pairV2Transcript");

        byte[] transcript = WireClientProbe.ComputePairV2TranscriptBytes(
            t.GetProperty("method").GetString()!,
            t.GetProperty("sessionId").GetString()!,
            t.GetProperty("initiatorRole").GetString()!,
            t.GetProperty("responderRole").GetString()!,
            t.GetProperty("initiatorNonceHex").GetString()!,
            t.GetProperty("responderNonceHex").GetString()!,
            t.GetProperty("initiatorFingerprintHex").GetString()!,
            t.GetProperty("initiatorPublicKeyDerHex").GetString()!,
            t.GetProperty("responderFingerprintHex").GetString()!,
            t.GetProperty("responderPublicKeyDerHex").GetString()!,
            t.GetProperty("responderPresenceHex").GetString()!,
            t.GetProperty("tlsServerSpkiSha256Hex").GetString()!,
            t.GetProperty("purpose").GetString()!);

        Assert.Equal(t.GetProperty("expectedTranscriptLength").GetInt32(), transcript.Length);
        Assert.Equal(t.GetProperty("expectedTranscriptSha256Hex").GetString(), Convert.ToHexStringLower(SHA256.HashData(transcript)));
    }

    [Fact]
    [Trait("Id", "E2E-T1-013")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_013_IsolatedTrustAndIdentity_InitializeAndRotate_Succeeds()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-013");
        if (!OperatingSystem.IsWindows())
        {
            // On Linux, DesktopPersistentIdentityStore uses sandboxed PKCS#8 path (never mutates host CNG)
            ProcessRunResult res = await GlideProcessRunner.RunBackendDriverAsync(sandbox, ["test-trust"]);
            Assert.Equal(0, res.ExitCode);
            JsonElement? ev = res.FindEvent("trust_result");
            Assert.True(ev.HasValue);
            Assert.True(ev.Value.GetProperty("success").GetBoolean());
            Assert.True(ev.Value.GetProperty("initialFingerprintValid").GetBoolean());
            Assert.True(ev.Value.GetProperty("rotationSuccessful").GetBoolean());
            return;
        }

        // On Windows: NEVER invoke test-trust / RotateLocalIdentity() (would delete CNG Glide.Identity.v1).
        // Use ephemeral in-memory IdentityV1Peer enrollment + rotated-key rejection + original-key reconnect.
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peerA = new IdentityV1Peer("PeerA-Orig");
        using var peerARotated = new IdentityV1Peer("PeerA-Rotated");
        byte[] file1 = Encoding.UTF8.GetBytes("enroll-payload-1");

        // 1. Enroll PeerA via listen-receiver --decision-enrollment accept
        await using (InteractiveDriverSession recv1 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "30"
            ]))
        {
            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri((await recv1.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome out1 = await peerA.SendFilesProfile1Async(inv1, [("t1-013-a.txt", file1)]);
            Assert.True(out1.Succeeded, out1.Message);
            E2ETestHelpers.AssertResultSuccess(await recv1.WaitForExitAsync(TimeSpan.FromSeconds(15)), "EnrollReceiver");
        }

        Assert.True(File.Exists(recvTrust));
        Assert.Contains(peerA.FingerprintHex, File.ReadAllText(recvTrust), StringComparison.Ordinal);

        // 2. Connect with rotated keypair against --expected-peer PeerA -> MUST reject on wire
        await using (InteractiveDriverSession recv2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peerA.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "20"
            ]))
        {
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri((await recv2.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome outRot = await peerARotated.SendFilesProfile1Async(inv2, [("t1-013-rot.txt", file1)]);
            Assert.False(outRot.Succeeded);
            Assert.NotEqual(0, (await recv2.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }

        // 3. Reconnect with original PeerA under --expected-peer PeerA --decision-enrollment decline -> MUST succeed
        await using (InteractiveDriverSession recv3 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peerA.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ]))
        {
            WireV1Invitation inv3 = WireClientProbe.DecodeV1Uri((await recv3.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome outOrig = await peerA.SendFilesProfile1Async(inv3, [("t1-013-b.txt", file1)]);
            Assert.True(outOrig.Succeeded, outOrig.Message);
            E2ETestHelpers.AssertResultSuccess(await recv3.WaitForExitAsync(TimeSpan.FromSeconds(15)), "TrustedReconnectReceiver");
        }
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-014")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_014_WindowsWpfPairDevicesPage_PinAndV2Qr_Smoke()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-014");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        await ui.SwitchTabAsync("NavPairDevices");
        await ui.AssertVisibleByNameAsync("Find pair-ready Glide devices");
        await ui.AssertVisibleByNameAsync("Show this PC's pairing QR and code");
        await ui.AssertVisibleByNameAsync("Enter pairing link");
        await ui.AssertVisibleByNameAsync("Pair by address");

        await ui.ClickByNameAsync("Show this PC's pairing QR and code");
        await ui.WaitForNameAsync("Six-digit pairing code");
        await ui.AssertVisibleByNameAsync("Six-digit pairing code");

        await ui.ClickByNameAsync("Copy link");
        string v2Raw = await ui.ReadClipboardAsync();
        WireV2Invitation v2 = WireClientProbe.DecodeV2Uri(v2Raw);
        Assert.Equal("pair-only", v2.Purpose);

        int port = v2.Endpoints[0].Port;
        using var probePeer = new IdentityV1Peer("Gld2-Test-Peer");

        // 1. PAIR_ONLY transfer OFFER_BEGIN injection must be rejected cleanly
        Gld2PairOutcome inj = await Gld2Probe.SendPairOnlyOfferInjectionAsync("127.0.0.1", port, probePeer);
        Assert.False(inj.Paired);
        sandbox.AssertStagingClean();

        // 2. 3 consecutive wrong PIN attempts -> lockout (1st/2nd AUTH_FAILED, 3rd EXPIRED, 4th rejected)
        const string wrongPin = "000000";
        Gld2PairOutcome a1 = await Gld2Probe.RunSpake2PinAttemptAsync("127.0.0.1", port, wrongPin, probePeer, approveOnSuccess: false);
        Assert.False(a1.Paired);
        Assert.Equal("AUTH_FAILED", a1.ErrorCode);

        Gld2PairOutcome a2 = await Gld2Probe.RunSpake2PinAttemptAsync("127.0.0.1", port, wrongPin, probePeer, approveOnSuccess: false);
        Assert.False(a2.Paired);
        Assert.Equal("AUTH_FAILED", a2.ErrorCode);

        Gld2PairOutcome a3 = await Gld2Probe.RunSpake2PinAttemptAsync("127.0.0.1", port, wrongPin, probePeer, approveOnSuccess: false);
        Assert.False(a3.Paired);
        Assert.Equal("EXPIRED", a3.ErrorCode);

        await ui.WaitForNameAsync("Get a new code");
        Gld2PairOutcome a4 = await Gld2Probe.RunSpake2PinAttemptAsync("127.0.0.1", port, wrongPin, probePeer, approveOnSuccess: false);
        Assert.False(a4.Paired);
        Assert.Equal("EXPIRED", a4.ErrorCode);

        // 3. Get a new code -> pair with fresh v2 QR link + UIA Approve -> peer appears in Paired devices
        await ui.ClickByNameAsync("Get a new code");
        await ui.WaitForNameAsync("Six-digit pairing code");
        await ui.AssertVisibleByNameAsync("Six-digit pairing code");
        await ui.ClickByNameAsync("Copy link");
        WireV2Invitation freshV2 = WireClientProbe.DecodeV2Uri(await ui.ReadClipboardAsync());
        Assert.NotEqual(v2.InvitationId, freshV2.InvitationId);

        Task<Gld2PairOutcome> pairTask = Gld2Probe.RunQrV2PairAsync(freshV2, probePeer, approveOnSuccess: true);
        await ui.WaitForNameAsync("Approve", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Approve");
        Gld2PairOutcome paired = await pairTask;
        Assert.True(paired.Paired, $"Expected successful QR v2 pairing: {paired.ErrorCode} {paired.Message}");
        await ui.WaitForNameAsync(probePeer.DisplayName, timeoutSeconds: 10);
    }

    [Fact]
    [Trait("Id", "E2E-T1-015")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_015_LinuxVmPairDevices_EnrollsTrustStore()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-015");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/pair-test""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/Downloads""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run"" ""$ROOT/b/Downloads""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""

run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_A=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
test -n ""$FP_A"" && test -n ""$FP_B"" && test ""$FP_A"" != ""$FP_B""

printf 'pair-enroll-payload' > ""$ROOT/b/payload.txt""
SRC_SHA=""$(sha256sum ""$ROOT/b/payload.txt"" | awk '{print $1}')""

FIFO=""$ROOT/a/run/in.fifo""
mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
run_a glide receive --port 0 < ""$FIFO"" > ""$ROOT/a/recv.log"" 2>&1 &
RPID=$!
trap 'kill $RPID 2>/dev/null || true' EXIT

URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/recv.log"" 2>/dev/null; then
    URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/recv.log"" | head -n 1)""
    break
  fi
  sleep 0.25
done
test -n ""$URI""

printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/payload.txt""
wait $RPID

run_a glide trust list | grep -Fq ""$FP_B""
run_b glide trust list | grep -Fq ""$FP_A""
test ""$(stat -c '%a' ""$ROOT/a/cfg/glide/identity-v1.pk8"")"" = ""600""
test ""$(stat -c '%a' ""$ROOT/a/cfg/glide/trust-v1.json"")"" = ""600""
DST_FILE=""$(find ""$ROOT/a/Downloads"" -name 'payload.txt' | head -n 1)""
test -f ""$DST_FILE""
test ""$(sha256sum ""$DST_FILE"" | awk '{print $1}')"" = ""$SRC_SHA""
echo ""VM_PAIR_ENROLL_OK a=$FP_A b=$FP_B""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-015", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_PAIR_ENROLL_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-015");
        }
    }

    // =========================================================================
    // UF4: Quick Share QR & Link (glide://pair/v1#) (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-016")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public void E2E_T1_016_V1InvitationUri_MatchesGoldenFixtureAndRoundTrip()
    {
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        JsonElement v1 = doc.RootElement.GetProperty("v1Compatibility");
        string uri = v1.GetProperty("validUri").GetString()!;

        WireV1Invitation decoded = WireClientProbe.DecodeV1Uri(uri);
        Assert.Equal(v1.GetProperty("expectedInvitationHash").GetString(), decoded.InvitationHash);
        Assert.Equal(v1.GetProperty("expectedInvitationId").GetString(), decoded.InvitationId);
        Assert.Equal(v1.GetProperty("expectedName").GetString(), decoded.Name);
        Assert.Equal(v1.GetProperty("expectedPlatform").GetString(), decoded.Platform);
        Assert.Equal(v1.GetProperty("expectedPresence").GetString(), decoded.Presence);
        Assert.Equal(v1.GetProperty("expectedSpkiSha256").GetString(), decoded.SpkiSha256);
        Assert.Equal(v1.GetProperty("expectedToken").GetString(), decoded.Token);
        Assert.Equal(v1.GetProperty("expectedExpiresAt").GetString(), decoded.ExpiresAt);
        Assert.Equal(v1.GetProperty("expectedTtlSeconds").GetInt32(), decoded.TtlSeconds);
    }

    [Fact]
    [Trait("Id", "E2E-T1-017")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_017_V1UriViaStdinPipe_ConnectsWithoutLeakingSecretInLogs()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-017");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "link-file.bin"), 2048, seed: 417);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        Assert.DoesNotMatch(@"glide://pair/v1#[A-Za-z0-9_-]{20,}", sender.Stdout);
        Assert.DoesNotMatch(@"glide://pair/v1#[A-Za-z0-9_-]{20,}", sender.Stderr);
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-018")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_018_V1InvitationSpkiPin_AuthenticatesTls13Listener()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-018");
        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--timeout-seconds", "15"
            ]);
        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
        Assert.Equal(System.Security.Authentication.SslProtocols.Tls13, tls.Ssl.SslProtocol);
        Assert.Equal("glide-lan/1", Encoding.ASCII.GetString(tls.Ssl.NegotiatedApplicationProtocol.Protocol.Span));
        Assert.Equal(inv.SpkiSha256, tls.RemoteSpkiSha256);

        listener.KillNow();
        await listener.WaitForExitAsync(TimeSpan.FromSeconds(5));
    }

    [Fact]
    [Trait("Id", "E2E-T1-019")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_019_WindowsUriProtocolActivation_OpensPairModal()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-019");
        CorpusVerifier.WriteUtf8TextFile(Path.Combine(sandbox.SourceDir, "uri-activated.txt"), "uri-activation-payload");
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        await using InteractiveDriverSession hostedSender = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-hosted-sender", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--source-dir", sandbox.SourceDir,
                "--decision-recipient", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "45"
            ]);
        string uri = (await hostedSender.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!;

        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);
        int fwdExit = await ui.ForwardActivationAsync(uri);
        Assert.Equal(0, fwdExit);

        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 20);
        await ui.AssertVisibleByNameAsync("Incoming transfer");
        await ui.ClickByNameAsync("Accept transfer");

        ProcessRunResult senderExit = await hostedSender.WaitForExitAsync(TimeSpan.FromSeconds(30));
        E2ETestHelpers.AssertResultSuccess(senderExit, "HostedSender");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
    }

    [Fact]
    [Trait("Id", "E2E-T1-020")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_020_AndroidPasteInvitationDialog_AcceptsV1Uri()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-020");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "discovery_peers");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select recipient");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Pixel 9 Pro");

        await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Scan QR code or paste invitation");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, contentDescContains: "Paste invitation link without using camera");
        await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Paste invitation link without using camera");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Paste invitation");

        string v1Uri = WireClientProbe.BuildV1Uri("10.0.2.2", 42424, new string('a', 64), name: "E2E-Paste-Receiver");
        await AndroidUiHelpers.InputTextAsync(sandbox, v1Uri);
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Verify & connect");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Connect to");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "E2E-Paste-Receiver");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "10.0.2.2:42424");
    }

    // =========================================================================
    // UF5: Trusted Reconnect & Saved Devices (TRUSTED_SEND) (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-021")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public void E2E_T1_021_Gld2SessionDispatchSchema_ValidatesTrustedSendAndPairOnlyPurposes()
    {
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        JsonElement dispatch = doc.RootElement.GetProperty("sessionDispatch");
        Assert.Equal("GLD2", dispatch.GetProperty("gld2PreambleAscii").GetString());
        Assert.Equal("474c4432", dispatch.GetProperty("gld2PreambleHex").GetString());

        string[] allowed = dispatch.GetProperty("allowedPurposes").EnumerateArray().Select(e => e.GetString()!).ToArray();
        Assert.Contains("PAIR_ONLY", allowed);
        Assert.Contains("TRUSTED_SEND", allowed);

        byte[] capBytes = WireClientProbe.ComputeCanonicalCapabilitiesBytes(["identity-v1", "sha256", "stream-v1"]);
        Assert.StartsWith("GLIDE-CAPS-V1", Encoding.ASCII.GetString(capBytes, 0, 13));
    }

    [Fact]
    [Trait("Id", "E2E-T1-022")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_022_MdnsDiscoveryPrimitives_ValidateNamePresenceAndMulticastBind()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-022");
        ProcessRunResult res = await GlideProcessRunner.RunBackendDriverAsync(sandbox, ["test-discovery"]);
        Assert.Equal(0, res.ExitCode);
        JsonElement ev = res.FindEvent("discovery_result")!.Value;
        Assert.True(ev.GetProperty("success").GetBoolean());
        Assert.True(ev.GetProperty("validName").GetBoolean());
        Assert.True(ev.GetProperty("validPresence").GetBoolean());
        Assert.True(ev.GetProperty("multicastSocketBound").GetBoolean());
    }

    [Fact]
    [Trait("Id", "E2E-T1-023")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_023_IsolatedTrustStores_MutualEnrollmentAndTransfer_Succeeds()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-023");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peerA = new IdentityV1Peer("TrustedPeerA");

        byte[] doc1 = new byte[4096];
        new Random(523).NextBytes(doc1);
        byte[] doc2 = new byte[4096];
        new Random(524).NextBytes(doc2);
        string sha1 = Convert.ToHexStringLower(SHA256.HashData(doc1));
        string sha2 = Convert.ToHexStringLower(SHA256.HashData(doc2));

        // 1. First transfer with enrollment accept
        await using (InteractiveDriverSession recv1 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "30"
            ]))
        {
            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri((await recv1.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome out1 = await peerA.SendFilesProfile1Async(inv1, [("trusted-doc-1.bin", doc1)]);
            Assert.True(out1.Succeeded, out1.Message);
            E2ETestHelpers.AssertResultSuccess(await recv1.WaitForExitAsync(TimeSpan.FromSeconds(15)), "Receiver1");
        }

        Assert.True(File.Exists(recvTrust));
        Assert.Contains(peerA.FingerprintHex, File.ReadAllText(recvTrust), StringComparison.Ordinal);

        // 2. Second reconnect under --decision-enrollment decline + --expected-peer (only persisted trust succeeds)
        await using (InteractiveDriverSession recv2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peerA.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ]))
        {
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri((await recv2.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome out2 = await peerA.SendFilesProfile1Async(inv2, [("trusted-doc-2.bin", doc2)]);
            Assert.True(out2.Succeeded, out2.Message);
            E2ETestHelpers.AssertResultSuccess(await recv2.WaitForExitAsync(TimeSpan.FromSeconds(15)), "Receiver2");
        }

        Assert.Equal(sha1, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "trusted-doc-1.bin")));
        Assert.Equal(sha2, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "trusted-doc-2.bin")));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-024")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_024_WindowsWpfPairedDevicePicker_SkipsQrScan()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-024");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        // 1. Pair an in-memory peer via Pair devices v2 link flow so trust-v1.json is populated legitimately
        await ui.SwitchTabAsync("NavPairDevices");
        await ui.ClickByNameAsync("Show this PC's pairing QR and code");
        await ui.WaitForNameAsync("Six-digit pairing code");
        await ui.ClickByNameAsync("Copy link");
        WireV2Invitation v2 = WireClientProbe.DecodeV2Uri(await ui.ReadClipboardAsync());

        using var peer = new IdentityV1Peer("SavedDesk-T1-024");
        Task<Gld2PairOutcome> pairTask = Gld2Probe.RunQrV2PairAsync(v2, peer, approveOnSuccess: true);
        await ui.WaitForNameAsync("Approve", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Approve");
        Gld2PairOutcome paired = await pairTask;
        Assert.True(paired.Paired, paired.Message);
        string winFp = paired.ResponderFingerprintHex!;

        // 2. Select file & pick paired device without scanning QR
        string sendFile = Path.Combine(sandbox.SourceDir, "picker-doc.txt");
        CorpusVerifier.WriteUtf8TextFile(sendFile, "paired-picker-payload");
        await ui.ForwardActivationAsync(sendFile);
        await ui.AssertVisibleByNameAsync("picker-doc.txt");

        await ui.ClickByNameAsync("Paired device");
        await ui.WaitForIdAsync("TrustedDevicesScroll");
        string connectName = $"Connect to {peer.DisplayName}, identity {peer.FingerprintHex[..8]}…{peer.FingerprintHex[^8..]}";
        await ui.ClickByNameAsync(connectName);
        await ui.AssertVisibleByIdAsync("SendChosenDeviceButton");

        // 3. Also prove saved peer can send directly via GLD2 TRUSTED_SEND without QR scan
        byte[] payload = Encoding.UTF8.GetBytes("trusted-send-no-qr");
        Task<IdentityTransferOutcome> sendTask = peer.SendTrustedProfile2Async("127.0.0.1", v2.Endpoints[0].Port, winFp, [("trusted-no-qr.txt", payload)]);
        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Accept transfer");
        IdentityTransferOutcome outcome = await sendTask;
        Assert.True(outcome.Succeeded, $"{outcome.ErrorCode} {outcome.Message}");
        string delivered = Path.Combine(sandbox.DestDir, "trusted-no-qr.txt");
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(payload)), CorpusVerifier.ComputeFileSha256(delivered));
    }

    [Fact]
    [Trait("Id", "E2E-T1-025")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T1_025_WinToUbuntuVm_TrustedSendWithoutRepeatQr_Succeeds()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-025");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string file1 = Path.Combine(sandbox.SourceDir, "enroll-cross.bin");
        string sha1 = CorpusVerifier.WriteIncompressibleFile(file1, 8192, 1251);
        string file2 = Path.Combine(sandbox.SourceDir, "trusted-cross.bin");
        string sha2 = CorpusVerifier.WriteIncompressibleFile(file2, 8192, 1252);

        // 1. Mutual enrollment via listen-receiver on VM storing trust/identity into $XDG_CONFIG_HOME/glide/
        try
        {
            string startVmRecv = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest"" ""$XDG_CONFIG_HOME/glide""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --trust-store ""$XDG_CONFIG_HOME/glide/trust-v1.json"" --identity-store ""$XDG_CONFIG_HOME/glide/identity-v1.pk8"" --decision-offer accept --decision-enrollment accept --timeout-seconds 40 > ""$HOME/recv1.out"" 2>""$HOME/recv1.err"" < /dev/null &
RPID=$!
echo $RPID > ""$HOME/recv1.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/recv1.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/recv1.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
cat ""$HOME/recv1.err"" >&2
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);

            ProcessRunResult startRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-025", startVmRecv, timeoutSeconds: 30);
            Assert.Equal(0, startRes.ExitCode);
            string invUri = VmTestHelpers.ExtractOutput(startRes).Trim();
            Assert.StartsWith("glide://pair/v1#", invUri);

            using var winPeer = new IdentityV1Peer("WinCrossPeer-025");
            IdentityTransferOutcome out1 = await winPeer.SendFilesProfile1Async(
                WireClientProbe.DecodeV1Uri(invUri),
                [("enroll-cross.bin", await File.ReadAllBytesAsync(file1))]);
            Assert.True(out1.Succeeded, out1.Message);
            string vmFp = out1.ReceiverFingerprintHex!;

            ProcessRunResult waitRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-025", @"
set -euo pipefail
RPID=""$(cat ""$HOME/recv1.pid"")""
for _ in $(seq 1 40); do
  if ! kill -0 ""$RPID"" 2>/dev/null; then break; fi
  sleep 0.2
done
grep -Fq '""success"":true' ""$HOME/recv1.out""
");
            Assert.Equal(0, waitRes.ExitCode);
            var (sz1, vmSha1) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t1-025/home/dest/enroll-cross.bin");
            Assert.Equal(8192, sz1);
            Assert.Equal(sha1, vmSha1);

            // 2. Start live VM glide daemon reading the enrolled XDG trust/identity store and send via GLD2 TRUSTED_SEND without QR
            string startVmDaemon = @"
set -euo pipefail
nohup glide daemon --accept-paired-offers --trusted-peer ""__WIN_FP__"" --port 0 --destination ""$HOME/dest"" > ""$HOME/daemon.log"" 2>&1 < /dev/null &
DPID=$!
echo $DPID > ""$HOME/daemon.pid""
for _ in $(seq 1 40); do
  if ! kill -0 ""$DPID"" 2>/dev/null; then
    cat ""$HOME/daemon.log"" >&2
    exit 1
  fi
  if grep -qE 'listening on port [0-9]+' ""$HOME/daemon.log"" 2>/dev/null; then
    grep -oE 'listening on port [0-9]+' ""$HOME/daemon.log"" | head -n 1 | awk '{print $NF}'
    exit 0
  fi
  sleep 0.25
done
cat ""$HOME/daemon.log"" >&2
exit 1
".Replace("__WIN_FP__", winPeer.FingerprintHex);

            ProcessRunResult daemonRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-025", startVmDaemon, timeoutSeconds: 30);
            Assert.Equal(0, daemonRes.ExitCode);
            int daemonPort = int.Parse(VmTestHelpers.ExtractOutput(daemonRes).Trim());

            IdentityTransferOutcome out2 = await winPeer.SendTrustedProfile2Async(
                TargetEnvironment.Current.VmHost,
                daemonPort,
                vmFp,
                [("trusted-cross.bin", await File.ReadAllBytesAsync(file2))]);
            Assert.True(out2.Succeeded, $"QR-less TRUSTED_SEND failed (ErrorCode={out2.ErrorCode}): {out2.Message}");

            ProcessRunResult stopDaemonRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-025", @"
set -euo pipefail
DPID=""$(cat ""$HOME/daemon.pid"")""
set +e
kill -TERM ""$DPID"" 2>/dev/null
wait ""$DPID"" 2>/dev/null
set -e
test ""$(find ""$XDG_DATA_HOME/glide/staging"" ""$HOME/staging"" -type f 2>/dev/null | wc -l)"" -eq 0
");
            Assert.Equal(0, stopDaemonRes.ExitCode);

            var (sz2, vmSha2) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t1-025/home/dest/trusted-cross.bin");
            Assert.Equal(8192, sz2);
            Assert.Equal(sha2, vmSha2);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-025");
        }
    }

    // =========================================================================
    // UF6: Explicit Consent & Trust/Identity Management (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-026")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_026_ExplicitOfferConsent_AcceptCommitsVerifiedFiles()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-026");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "consent-item.bin"), 3072, 626);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir, acceptOffer: true);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        Assert.Contains("ConfirmOfferAsync", receiver.Stderr);
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-027")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_027_ExplicitRecipientConfirmation_HostedSenderAcceptCommitsFiles()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-027");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "hosted-consent.bin"), 2048, 627);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (sender, receiver) = await E2ETestHelpers.RunHostedSenderTransferAsync(sandbox, sandbox.SourceDir, acceptRecipient: true, acceptOffer: true);
        E2ETestHelpers.AssertResultSuccess(sender, "HostedSender");
        E2ETestHelpers.AssertResultSuccess(receiver, "ConnectReceiver");

        Assert.Contains("ConfirmRecipientAsync", sender.Stderr);
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-028")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_028_ReceiverNeverAutoAccepts_DestinationEmptyUntilOfferDecision()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-028");
        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--decision-offer", "decline", "--timeout-seconds", "15"
            ]);
        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
        await E2ETestHelpers.PerformWireHelloAsync(tls, inv, Guid.NewGuid().ToString("D"), ct: cts.Token);

        string batchId = Guid.NewGuid().ToString("D");
        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", inv.InvitationId, 2, new JsonObject
            {
                ["batchId"] = batchId, ["itemCount"] = 1, ["totalBytes"] = "128", ["pageCount"] = 1
            }), cts.Token);
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        listener.KillNow();
        await listener.WaitForExitAsync(TimeSpan.FromSeconds(5));
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
    }

    [Fact]
    [Trait("Id", "E2E-T1-029")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_029_LinuxVmXdgIsolatedIdentityAndTrustCli_ShowListRevoke()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-029");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/trust-cli""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/Downloads""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run"" ""$ROOT/b/Downloads""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_A=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
printf 'trust-revoke-payload' > ""$ROOT/b/item.txt""

FIFO=""$ROOT/a/run/in.fifo""
mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
run_a glide receive --port 0 < ""$FIFO"" > ""$ROOT/a/recv.log"" 2>&1 &
RPID=$!
trap 'kill $RPID 2>/dev/null || true' EXIT

URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/recv.log"" 2>/dev/null; then
    URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/recv.log"" | head -n 1)""
    break
  fi
  sleep 0.25
done
test -n ""$URI""
printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/item.txt""
wait $RPID

run_a glide trust list | grep -Fq ""$FP_B""
REV_OUT=""$(run_a glide trust revoke ""$FP_B"")""
echo ""$REV_OUT"" | grep -Fq ""Peer trust and its headless policy were revoked.""
run_a glide trust list | grep -Fq ""No enrolled trusted peers.""

set +e
DUP_OUT=""$(run_a glide trust revoke ""$FP_B"" 2>&1)""
DUP_RC=$?
set -e
test ""$DUP_RC"" -eq 1
echo ""$DUP_OUT"" | grep -Fq ""No enrolled peer matched that fingerprint.""
echo ""VM_TRUST_REVOKE_OK a=$FP_A revoked=$FP_B""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-029", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_TRUST_REVOKE_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-029");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-030")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_030_WindowsWpfConsentAndEnrollmentModals_ShowFullFingerprint()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-030");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        await ui.SwitchTabAsync("NavReceive");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

        using var peer = new IdentityV1Peer("ConsentPeer-T1-030");
        byte[] payload = Encoding.UTF8.GetBytes("consent-enrollment-payload");
        string expectedSha = Convert.ToHexStringLower(SHA256.HashData(payload));

        Task<IdentityTransferOutcome> sendTask = peer.SendFilesProfile1Async(inv, [("consent-verified.txt", payload)]);

        await ui.WaitForNameAsync("Trust this device?", timeoutSeconds: 15);
        string formattedFingerprint = string.Join(" ", Enumerable.Range(0, (peer.FingerprintHex.Length + 7) / 8)
            .Select(i => peer.FingerprintHex.Substring(i * 8, Math.Min(8, peer.FingerprintHex.Length - i * 8))));
        await ui.AssertVisibleByNameAsync(formattedFingerprint);
        await ui.ClickByNameAsync("Trust Device");

        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.AssertVisibleByNameAsync("Incoming transfer");
        await ui.ClickByNameAsync("Accept transfer");

        IdentityTransferOutcome outcome = await sendTask;
        Assert.True(outcome.Succeeded, $"{outcome.ErrorCode} {outcome.Message}");
        string delivered = Path.Combine(sandbox.DestDir, "consent-verified.txt");
        Assert.True(File.Exists(delivered));
        Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(delivered));
    }

    // =========================================================================
    // UF7: Activity, Progress States & Verified History (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-031")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_031_ResultMetrics_ReportExactVerifiedCountAndBytes()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-031");
        CorpusManifest manifest = CorpusVerifier.CreateSmallBatch(sandbox.SourceDir, count: 4, baseSize: 1500, seedOffset: 731);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        JsonElement recvRes = receiver.FindEvent("result")!.Value;
        Assert.Equal(manifest.Items.Count, recvRes.GetProperty("verifiedCount").GetInt32());
        Assert.Equal(manifest.TotalBytes, recvRes.GetProperty("verifiedBytes").GetInt64());
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
    }

    [Fact]
    [Trait("Id", "E2E-T1-032")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_032_R3DiagnosticsEnvOverride_LogsWithoutInvitationSecrets()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-032");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "diag.bin"), 4096, 732);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);
        string senderDiagPath = Path.Combine(sandbox.LogsDir, "sender-diagnostics.jsonl");

        await using InteractiveDriverSession recvSession = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ]);
        JsonElement invEvt = await recvSession.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        string invUri = invEvt.GetProperty("invitationUri").GetString()!;

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            [
                "connect-sender", "--source-dir", sandbox.SourceDir,
                "--decision-enrollment", "decline", "--timeout-seconds", "30"
            ],
            stdinLine: invUri,
            envOverrides: new Dictionary<string, string> { ["GLIDE_DIAGNOSTIC_LOG_PATH"] = senderDiagPath });
        ProcessRunResult receiver = await recvSession.WaitForExitAsync(TimeSpan.FromSeconds(20));

        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        foreach (string logPath in new[] { sandbox.DiagnosticLogPath, senderDiagPath })
        {
            Assert.True(File.Exists(logPath), $"Expected diagnostic JSONL log at {logPath}");
            string[] lines = File.ReadAllLines(logPath)
                .Where(l => !string.IsNullOrWhiteSpace(l))
                .ToArray();
            Assert.NotEmpty(lines);

            foreach (string line in lines)
            {
                using var doc = JsonDocument.Parse(line);
                Assert.True(doc.RootElement.ValueKind == JsonValueKind.Object);
                Assert.DoesNotMatch(@"glide://pair/v\d+#[A-Za-z0-9_-]{20,}", line);
                Assert.DoesNotContain("\"token\":", line, StringComparison.OrdinalIgnoreCase);
            }
        }

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
    }

    [Fact]
    [Trait("Id", "E2E-T1-033")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_033_MultiPageOfferBatch_36Items_TransfersAndVerifiesAll()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-033");
        CorpusManifest manifest = CorpusVerifier.CreateSmallBatch(sandbox.SourceDir, count: 36, baseSize: 512, seedOffset: 733);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        JsonElement recvRes = receiver.FindEvent("result")!.Value;
        Assert.Equal(36, recvRes.GetProperty("verifiedCount").GetInt32());
        Assert.Equal(manifest.TotalBytes, recvRes.GetProperty("verifiedBytes").GetInt64());
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-034")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_034_WindowsWpfActivityStatesAndSearchableHistory_Smoke()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-034");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        await ui.SwitchTabAsync("NavReceive");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

        using var peer = new IdentityV1Peer("HistoryPeer-T1-034");
        byte[] payload = Encoding.UTF8.GetBytes("activity-history-payload");
        Task<IdentityTransferOutcome> sendTask = peer.SendFilesProfile1Async(inv, [("activity-entry-xyz.txt", payload)]);

        await ui.WaitForNameAsync("Not Now", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Not Now");
        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Accept transfer");
        IdentityTransferOutcome outcome = await sendTask;
        Assert.True(outcome.Succeeded, outcome.Message);

        await ui.SwitchTabAsync("NavActivity");
        await ui.WaitForIdAsync("ActivityHistoryList");
        await ui.AssertVisibleByNameAsync("activity-entry-xyz.txt");

        await ui.ClickByNameAsync("Show sent history");
        await ui.AssertAbsentByNameAsync("activity-entry-xyz.txt");
        await ui.ClickByNameAsync("Show received history");
        await ui.AssertVisibleByNameAsync("activity-entry-xyz.txt");

        await ui.SetTextByIdAsync("ActivityHistorySearchBox", "activity-entry-xyz");
        await ui.AssertVisibleByNameAsync("activity-entry-xyz.txt");
        await ui.SetTextByIdAsync("ActivityHistorySearchBox", "no-match-999");
        await ui.AssertAbsentByNameAsync("activity-entry-xyz.txt");
        await ui.SetTextByIdAsync("ActivityHistorySearchBox", "");
        await ui.ClickByNameAsync("Show all history");
        await ui.AssertVisibleByNameAsync("activity-entry-xyz.txt");

        await ui.ClickByNameAsync("Clear verified transfer history");
        await ui.AssertAbsentByNameAsync("activity-entry-xyz.txt");
    }

    [Fact]
    [Trait("Id", "E2E-T1-035")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_035_AndroidDebugLaunchHarness_RendersTransferAndHistoryStates()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-035");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "completed_receiver", debugTheme: "dark");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_completed_card");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer complete");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "sample_document.txt");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Nearby Sender");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Save file");

        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Done");
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "history_detail_available");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer history");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Galaxy Tab S10+");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "project_milestone_deck_final.key");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "brand_guidelines_2026_vector.pdf");
    }

    // =========================================================================
    // UF8: Settings, Theme & Save Destination (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-036")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_036_CustomDestinationDirectory_ReceivesExclusivelyIntoTargetFolder()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-036");
        string customDest = sandbox.CreateSubDir("custom-downloads");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "custom.bin"), 2048, 836);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        await using InteractiveDriverSession receiver = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", customDest,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ]);
        JsonElement invEvt = await receiver.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        string uri = invEvt.GetProperty("invitationUri").GetString()!;

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--decision-enrollment", "decline"],
            stdinLine: uri);
        ProcessRunResult recvExit = await receiver.WaitForExitAsync(TimeSpan.FromSeconds(30));

        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(recvExit, "Receiver");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, customDest);
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-037")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_037_DisjointStagingAndDestination_CommitsCleanlyWithSha256Match()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-037");
        string altStaging = sandbox.CreateSubDir("alt-staging");
        string altDest = sandbox.CreateSubDir("alt-dest");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "disjoint.bin"), 16384, 837);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        await using InteractiveDriverSession receiver = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", altStaging, "--dest-dir", altDest,
                "--decision-offer", "accept", "--decision-enrollment", "decline"
            ]);
        JsonElement invEvt = await receiver.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        string uri = invEvt.GetProperty("invitationUri").GetString()!;

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--decision-enrollment", "decline"],
            stdinLine: uri);
        ProcessRunResult recvExit = await receiver.WaitForExitAsync(TimeSpan.FromSeconds(30));

        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(recvExit, "Receiver");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, altDest);
        E2ESandbox.AssertDirectoryCleanOfStagingArtifacts(altStaging);
    }

    [Fact]
    [Trait("Id", "E2E-T1-038")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_038_WindowsSettingsThemeAndDestination_Smoke()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-038");
        string customRecv = sandbox.CreateSubDir("win-settings-recv");

        await using (WindowsUiSession ui1 = await WindowsUiSession.StartAsync(sandbox, cleanAppData: true, seedState: true, receiveDirectory: customRecv))
        {
            await ui1.SwitchTabAsync("NavSettings");
            string observedDir = await ui1.GetValueByNameAsync("Receive folder");
            Assert.Equal(customRecv, observedDir);

            await ui1.SelectComboByNameAsync("Appearance theme", "Dark");
            await ui1.AssertVisibleByNameAsync("Advanced connection details");
        }

        // Restart cleanly without re-seeding or wiping AppData, verify persistence
        await using WindowsUiSession ui2 = await WindowsUiSession.StartAsync(sandbox, cleanAppData: false, seedState: false);
        await ui2.SwitchTabAsync("NavSettings");
        string persistedDir = await ui2.GetValueByNameAsync("Receive folder");
        Assert.Equal(customRecv, persistedDir);
        string persistedTheme = await ui2.GetComboSelectionByNameAsync("Appearance theme");
        Assert.Equal("Dark", persistedTheme);
    }

    [Fact]
    [Trait("Id", "E2E-T1-039")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_039_LinuxVmXdgPathsAndSettingsPersistence_VerifyLowercaseGlide()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-039");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
mkdir -p ""$HOME/custom-dl"" ""$HOME/src""
printf 'XDG_DOWNLOAD_DIR=""%s""\n' ""$HOME/custom-dl"" > ""$XDG_CONFIG_HOME/user-dirs.dirs""
glide identity show >/dev/null

test -f ""$XDG_CONFIG_HOME/glide/identity-v1.pk8""
test ""$(stat -c '%a' ""$XDG_CONFIG_HOME/glide/identity-v1.pk8"")"" = ""600""
test ""$(stat -c '%a' ""$XDG_CONFIG_HOME/glide"")"" = ""700""
test ! -e ""$XDG_CONFIG_HOME/Glide""
test ! -e ""$XDG_DATA_HOME/Glide""

printf 'xdg-dl-payload' > ""$HOME/src/xdg.txt""
SRC_SHA=""$(sha256sum ""$HOME/src/xdg.txt"" | awk '{print $1}')""
FIFO=""$XDG_RUNTIME_DIR/in.fifo""
mkfifo ""$FIFO""
( printf 'y\nn\n' > ""$FIFO"" ) &
glide receive --port 0 < ""$FIFO"" > ""$XDG_RUNTIME_DIR/recv.log"" 2>&1 &
RPID=$!
trap 'kill $RPID 2>/dev/null || true' EXIT

URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$XDG_RUNTIME_DIR/recv.log"" 2>/dev/null; then
    URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$XDG_RUNTIME_DIR/recv.log"" | head -n 1)""
    break
  fi
  sleep 0.25
done
test -n ""$URI""
test -e ""$XDG_RUNTIME_DIR/glide/listener.lock""
printf 'n\n' | glide send --pair ""$URI"" ""$HOME/src/xdg.txt""
wait $RPID

test -f ""$HOME/custom-dl/Glide/xdg.txt""
test ""$(sha256sum ""$HOME/custom-dl/Glide/xdg.txt"" | awk '{print $1}')"" = ""$SRC_SHA""
echo ""VM_XDG_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-039", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_XDG_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-039");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-040")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_040_AndroidSettingsScreen_ShowsSaveDestinationAndTheme()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-040");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "theme_settings", debugTheme: "light");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Settings");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Appearance");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Theme mode");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Dark");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Dark");

        await AndroidUiHelpers.SwipeScrollAsync(sandbox, "Down");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Received file save location");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Download/Glide");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Choose folder");
        await AndroidUiHelpers.SwipeScrollAsync(sandbox, "Down");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Version 1.0.0");
    }

    // =========================================================================
    // UF9: Headless CLI, Systemd Daemon & Isolation/Diagnostics (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-041")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_041_GlideAppCliHelp_DocumentsAllSubcommandsAndExitsZero()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-041");
        foreach (string[] args in new string[][] { [], ["--help"], ["-h"], ["help"] })
        {
            ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(sandbox, args);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("Usage:", res.Stdout);
            Assert.Contains("receive", res.Stdout);
            Assert.Contains("daemon", res.Stdout);
            Assert.Contains("send", res.Stdout);
            Assert.Contains("identity", res.Stdout);
            Assert.Contains("trust", res.Stdout);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-042")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_042_GlideAppWebCommand_RetiredGatewayFailsClosed()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-042");
        ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(sandbox, ["web"]);
        Assert.Equal(1, res.ExitCode);
        Assert.Contains("retired", res.Stdout, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    [Trait("Id", "E2E-T1-043")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_043_ListenerOwnershipCheck_VerifiesSingleOwnerLease()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-043");
        if (!OperatingSystem.IsWindows())
        {
            ProcessRunResult res = await GlideProcessRunner.RunBackendDriverAsync(sandbox, ["test-ownership"]);
            Assert.Equal(0, res.ExitCode);
            JsonElement ev = res.FindEvent("ownership_result")!.Value;
            Assert.True(ev.GetProperty("success").GetBoolean());
            Assert.True(ev.GetProperty("duplicateAcquisitionRejected").GetBoolean());
            return;
        }

        // On Windows: verify real duplicate port binding failure without touching host CNG/AppData
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "owner.bin"), 2048, 1043);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        await using InteractiveDriverSession listener1 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ]);
        JsonElement invEvt = await listener1.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        int boundPort = invEvt.GetProperty("port").GetInt32();
        string uri1 = invEvt.GetProperty("invitationUri").GetString()!;

        ProcessRunResult listener2 = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            [
                "listen-receiver", "--port", boundPort.ToString(), "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.CreateSubDir("staging2"), "--dest-dir", sandbox.CreateSubDir("dest2"),
                "--timeout-seconds", "5"
            ],
            timeout: TimeSpan.FromSeconds(15));
        Assert.NotEqual(0, listener2.ExitCode);

        // Listener #1 must remain healthy and complete a transfer
        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--decision-enrollment", "decline"],
            stdinLine: uri1);
        ProcessRunResult recv1 = await listener1.WaitForExitAsync(TimeSpan.FromSeconds(20));
        E2ETestHelpers.AssertResultSuccess(sender, "OwnerSender");
        E2ETestHelpers.AssertResultSuccess(recv1, "OwnerReceiver");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-044")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_044_LinuxVmDaemonRuntimeFile_Writes0600UriAndStopsOnSigterm()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-044");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/daemon-t1-044""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/dest""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_A=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
printf 'daemon-enroll' > ""$ROOT/b/enroll.txt""

# Enroll B into A
FIFO=""$ROOT/a/run/in.fifo""
mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
run_a glide receive --port 0 < ""$FIFO"" > ""$ROOT/a/enroll.log"" 2>&1 &
RPID=$!
URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/enroll.log"" 2>/dev/null; then
    URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/enroll.log"" | head -n 1)""
    break
  fi
  sleep 0.25
done
printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/enroll.txt""
wait $RPID

# Start daemon on A
run_a glide daemon --accept-paired-offers --trusted-peer ""$FP_B"" --port 0 --destination ""$ROOT/a/dest"" > ""$ROOT/a/daemon.log"" 2>&1 &
DPID=$!
trap 'kill $DPID 2>/dev/null || true' EXIT

INV_FILE=""$ROOT/a/run/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do
  if [ -f ""$INV_FILE"" ] && grep -q '\[DAEMON READY\]' ""$ROOT/a/daemon.log"" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
test -f ""$INV_FILE""
test ""$(stat -c '%a' ""$INV_FILE"")"" = ""600""
test ""$(stat -c '%a' ""$ROOT/a/run/glide"")"" = ""700""
if grep -q 'glide://pair/' ""$ROOT/a/daemon.log""; then
  echo ""Daemon log leaked invitation URI!"" >&2
  exit 1
fi

D_URI=""$(cat ""$INV_FILE"")""
printf 'daemon-transfer-payload' > ""$ROOT/b/dfile.txt""
SRC_SHA=""$(sha256sum ""$ROOT/b/dfile.txt"" | awk '{print $1}')""
run_b glide send --pair ""$D_URI"" --trusted-peer ""$FP_A"" ""$ROOT/b/dfile.txt""

test -f ""$ROOT/a/dest/dfile.txt""
test ""$(sha256sum ""$ROOT/a/dest/dfile.txt"" | awk '{print $1}')"" = ""$SRC_SHA""

kill -TERM ""$DPID""
wait ""$DPID""
D_RC=$?
test ""$D_RC"" -eq 0
grep -Fq ""[DAEMON STOPPED] Clean shutdown complete."" ""$ROOT/a/daemon.log""
test ! -e ""$INV_FILE""
echo ""VM_DAEMON_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t1-044", script, timeoutSeconds: 60);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_DAEMON_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-044");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-045")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_045_LinuxVmSystemdUnit_SmokeCheck()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-045");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Invoke-VmRemote.ps1",
            [
                "-Action", "RunSystemdSmoke",
                "-VmHost", TargetEnvironment.Current.VmHost,
                "-VmUser", TargetEnvironment.Current.VmUser
            ]);
        Assert.Equal(0, res.ExitCode);
        Assert.Contains("SYSTEMD_SMOKE_OK", res.Stdout);
    }

    // =========================================================================
    // UF10: Android Share Ingress, Background & Adaptive UI (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-046")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_046_AndroidActionSend_RoutesToSelection()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-046");
        string localFile = Path.Combine(sandbox.SourceDir, "android-share-single.txt");
        CorpusVerifier.WriteUtf8TextFile(localFile, "android-action-send-payload");
        string deviceFile = "/sdcard/Download/glide-e2e-t1-046.txt";

        try
        {
            await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
            string contentUri = await AndroidUiHelpers.PushAndIndexFileAsync(sandbox, localFile, deviceFile);
            await AndroidUiHelpers.ShareSendAsync(sandbox, contentUri, "text/plain");

            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "glide-e2e-t1-046.txt");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "1 / 1000");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Proceed to send (1)");
        }
        finally
        {
            await AndroidUiHelpers.CleanupAsync(sandbox, deviceFile);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-047")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_047_AndroidActionSendMultiple_StagesBatch()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-047");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "share_test_multiple");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "vacation_1.jpg");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "vacation_2.jpg");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "2 / 1000");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Proceed to send (2)");

        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Proceed to send (2)");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select recipient");
    }

    [Fact]
    [Trait("Id", "E2E-T1-048")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_048_AndroidAdaptiveLayout_BookPosture_RendersViaUiAutomator()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-048");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "history_populated", debugPosture: "book");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer history");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, contentDescContains: "Transfer detail pane");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Select a transfer to view details");
    }

    [Fact]
    [Trait("Id", "E2E-T1-049")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_049_AndroidForegroundTransferService_ContinuesInBackground()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-049");

        await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "service_transfer_active");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_progress_card");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Sending to");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Pixel 9 Pro");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "system_backup.tar.gz");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Cancel transfer");

        var (svcActive, notifFound, _) = await AndroidUiHelpers.CheckServiceRunningAsync(sandbox);
        Assert.True(svcActive, "Expected TransferService foreground service to be active.");
        Assert.True(notifFound, "Expected TransferService notification to be present.");
    }

    [Fact]
    [Trait("Id", "E2E-T1-050")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_050_AndroidPairedSendCompletion_ReturnsToSelection()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-050");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "home_active_transfer");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Send files");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Receive files");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer in progress");
        await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Resume active transfer");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_progress_card");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "transfer_completed_sender");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_completed_card", absent: true);
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send");
    }

    // =========================================================================
    // UF11: Cancellation, Disconnect, Recovery & Staging Cleanup (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-051")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_051_MidTransferCancel_RetainsSourceLeavesDestAndStagingClean()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-051");
        string srcFile = Path.Combine(sandbox.SourceDir, "cancel-target.bin");
        string srcSha = CorpusVerifier.WriteIncompressibleFile(srcFile, 16L * 1024 * 1024, seed: 1151);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(
            sandbox,
            sandbox.SourceDir,
            acceptOffer: true,
            cancelAfterBytes: 256 * 1024,
            timeoutSeconds: 30);

        Assert.NotEqual(0, receiver.ExitCode);
        Assert.NotEqual(0, sender.ExitCode);
        Assert.True(File.Exists(srcFile));
        Assert.Equal(srcSha, CorpusVerifier.ComputeFileSha256(srcFile));
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-052")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_052_WireCancelControlFrame_TearsDownCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-052");
        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--decision-offer", "accept", "--timeout-seconds", "15"
            ]);
        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
        JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
        string sessionId = bootRes["sessionId"]!.GetValue<string>();

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("HELLO", sessionId, 1, new JsonObject
            {
                ["supportedMajors"] = new JsonArray(1),
                ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                ["optionalCapabilities"] = new JsonArray(),
                ["role"] = "sender",
                ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                ["displayName"] = "WireCancel"
            }), cts.Token);
        await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
            WireClientProbe.BuildEnvelopeBytes("CANCEL", sessionId, 2, new JsonObject
            {
                ["code"] = "USER_CANCELLED"
            }), cts.Token);

        ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
        Assert.NotEqual(0, recvExit.ExitCode);
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-053")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_053_ReceiverAbruptKillMidStream_SenderFailsCleanlyWithoutPartialDest()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-053");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "kill-mid.bin"), 4L * 1024 * 1024, seed: 1153);

        using var rogue = new RogueTlsListener();
        string uri = WireClientProbe.BuildV1Uri("127.0.0.1", rogue.Port, rogue.SpkiSha256);
        using var serverCts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
        CancellationToken ct = serverCts.Token;

        Task serverTask = Task.Run(async () =>
        {
            await using WireTlsSession tls = await rogue.AcceptAsync(ct);
            WireRecord? boot = await WireClientProbe.ReadLanRecordAsync(tls.Ssl, ct);
            using var bootDoc = JsonDocument.Parse(boot!.Payload);
            string sid = bootDoc.RootElement.GetProperty("sessionId").GetString()!;
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.BootstrapResult,
                Encoding.UTF8.GetBytes(new JsonObject { ["v"] = 1, ["status"] = "accepted", ["sessionId"] = sid }.ToJsonString()), ct);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // HELLO
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO_ACK", sid, 1, new JsonObject
                {
                    ["selectedMajor"] = 1,
                    ["capabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "KillMidStreamPeer",
                    ["limits"] = new JsonObject { ["maxItems"] = 1000, ["maxItemBytes"] = "68719476736", ["maxBatchBytes"] = "274877906944", ["maxControlBytes"] = 16384 }
                }), ct);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_BEGIN
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_PAGE
            JsonObject offerEnd = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct);
            string batchId = offerEnd["body"]!["batchId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_DECISION", sid, 2, new JsonObject { ["batchId"] = batchId, ["accepted"] = true }), ct);

            JsonObject itemStart = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct);
            string itemId = itemStart["body"]!["itemId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("ITEM_READY", sid, 3, new JsonObject { ["batchId"] = batchId, ["itemId"] = itemId }), ct);

            WireRecord? firstData = await WireClientProbe.ReadLanRecordAsync(tls.Ssl, ct);
            Assert.NotNull(firstData);
            Assert.Equal(WireRecordKind.Data, firstData!.Kind);
            tls.Tcp.Client.Close(0);
        }, ct);

        ProcessRunResult senderResult = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "20"],
            stdinLine: uri);

        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, senderResult);
        Assert.NotEqual(0, senderResult.ExitCode);
        Assert.False(senderResult.TimedOut);
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
    }

    [Fact]
    [Trait("Id", "E2E-T1-054")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_054_RetryAfterCancellation_CompletesWithExactSha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-054");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "retry-item.bin"), 4L * 1024 * 1024, seed: 1154);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (recv1, send1) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir, cancelAfterBytes: 128 * 1024);
        Assert.NotEqual(0, recv1.ExitCode);
        Assert.NotEqual(0, send1.ExitCode);
        sandbox.AssertStagingClean();

        var (recv2, send2) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(send2, "RetrySender");
        E2ETestHelpers.AssertResultSuccess(recv2, "RetryReceiver");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T1-055")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_055_RogueReceiverSendsBusyError_SenderExitsCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-055");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "busy.bin"), 1024, seed: 1155);

        using var listener = new RogueTlsListener();
        string uri = WireClientProbe.BuildV1Uri("127.0.0.1", listener.Port, listener.SpkiSha256);
        using var serverCts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
        CancellationToken ct = serverCts.Token;

        Task serverTask = Task.Run(async () =>
        {
            await using WireTlsSession tls = await listener.AcceptAsync(ct);
            WireRecord? boot = await WireClientProbe.ReadLanRecordAsync(tls.Ssl, ct);
            using var bootDoc = JsonDocument.Parse(boot!.Payload);
            var rejected = new JsonObject
            {
                ["v"] = 1,
                ["status"] = "rejected",
                ["code"] = "BUSY"
            };
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.BootstrapResult,
                Encoding.UTF8.GetBytes(rejected.ToJsonString()), ct);
        }, ct);

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "30"],
            stdinLine: uri);

        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, sender);
        Assert.NotEqual(0, sender.ExitCode);
    }

    // =========================================================================
    // UF12: Packaging, Install, Upgrade, Uninstall & Data Retention (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T1-056")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T1_056_PackageLifecycleDriver_DryRunValidatesInstallerScripts()
    {
        await using var sandbox = new E2ESandbox("E2E-T1-056");
        foreach (string platform in new[] { "Windows", "Linux", "Android" })
        {
            ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                ["-Platform", platform, "-DryRun"]);
            Assert.Equal(0, res.ExitCode);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-057")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T1_057_WindowsInstaller_InstallUpgradeUninstallRetention()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T1-057");
        string nsisExe = TargetEnvironment.Current.ResolveWindowsNsisInstaller();
        string zipPkg = TargetEnvironment.Current.ResolveWindowsPortableZip();
        string msiPkg = TargetEnvironment.Current.ResolveWindowsMsiInstaller();

        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Test-PackageLifecycle.ps1",
            [
                "-Platform", "Windows",
                "-LiveInstall",
                "-PackagePath", nsisExe,
                "-ZipPackagePath", zipPkg,
                "-MsiPackagePath", msiPkg,
                "-SandboxRoot", sandbox.CreateSubDir("winpkg")
            ],
            TimeSpan.FromSeconds(180));
        Assert.True(res.ExitCode == 0, $"Windows package lifecycle failed: {res.Stderr}\n{res.Stdout}");
    }

    [Fact]
    [Trait("Id", "E2E-T1-058")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_058_LinuxRunInstaller_SandboxedPrefixLifecycle()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-058");
        string runPath = TargetEnvironment.Current.ResolveLinuxRunInstaller();

        try
        {
            ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                [
                    "-Platform", "Linux",
                    "-PackagePath", runPath,
                    "-SandboxRoot", "/tmp/glide-e2e/e2e-t1-058",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser
                ],
                TimeSpan.FromSeconds(180));
            Assert.True(res.ExitCode == 0, $"Linux .run lifecycle failed: {res.Stderr}\n{res.Stdout}");
            Assert.Contains("LINUX_RUN_LIFECYCLE_OK", res.Stdout);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-058");
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "pkg-run");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-059")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T1_059_LinuxDebPackage_InstallAndRetainUserData()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T1-059");
        string debPath = TargetEnvironment.Current.ResolveLinuxDebPackage();

        try
        {
            ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                [
                    "-Platform", "Linux",
                    "-PackagePath", debPath,
                    "-SandboxRoot", "/tmp/glide-e2e/e2e-t1-059",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser
                ],
                TimeSpan.FromSeconds(180));
            Assert.True(res.ExitCode == 0, $"Linux .deb lifecycle failed: {res.Stderr}\n{res.Stdout}");
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t1-059");
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "pkg-deb-state");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T1-060")]
    [Trait("Tier", "1")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T1_060_AndroidApk_InstallUpgradeAndRetainData()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T1-060");
        string apkPath = TargetEnvironment.Current.ResolveAndroidDebugApk();

        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Test-PackageLifecycle.ps1",
            [
                "-Platform", "Android",
                "-Action", "Install",
                "-PackagePath", apkPath,
                "-AndroidSerial", TargetEnvironment.Current.AndroidSerial
            ],
            TimeSpan.FromSeconds(120));
        Assert.True(res.ExitCode == 0, $"Android APK Install lifecycle failed: {res.Stderr}\n{res.Stdout}");
    }
}
