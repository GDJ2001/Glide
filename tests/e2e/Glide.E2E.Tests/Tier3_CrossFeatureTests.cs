using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Glide.E2E.Tests.Infrastructure;

namespace Glide.E2E.Tests;

public sealed class Tier3_CrossFeatureTests
{
    [Fact]
    [Trait("Id", "E2E-T3-001")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T3_001_TrustedStore_PlusNestedFolderBatch_PlusFilenameCollision()
    {
        await using var sandbox = new E2ESandbox("E2E-T3-001");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peer = new IdentityV1Peer("NestedTrustedPeer");

        byte[] incomingBytes = Encoding.UTF8.GetBytes("new-team-notes-payload");
        string incomingSha = Convert.ToHexStringLower(SHA256.HashData(incomingBytes));
        string existingDest = Path.Combine(sandbox.DestDir, "team", "notes.txt");
        string existingSha = CorpusVerifier.WriteUtf8TextFile(existingDest, "original-team-notes");

        await using InteractiveDriverSession receiver = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "30"
            ]);
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri((await receiver.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
        IdentityTransferOutcome outcome = await peer.SendFilesProfile1Async(inv, [("team/notes.txt", incomingBytes)]);
        Assert.True(outcome.Succeeded, outcome.Message);
        E2ETestHelpers.AssertResultSuccess(await receiver.WaitForExitAsync(TimeSpan.FromSeconds(20)), "Receiver");

        Assert.Contains(peer.FingerprintHex, File.ReadAllText(recvTrust), StringComparison.Ordinal);
        Assert.Equal(existingSha, CorpusVerifier.ComputeFileSha256(existingDest));
        string collided = Path.Combine(sandbox.DestDir, "team", "notes (1).txt");
        Assert.True(File.Exists(collided), "Expected nested collision file 'team/notes (1).txt'.");
        Assert.Equal(incomingSha, CorpusVerifier.ComputeFileSha256(collided));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T3-002")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T3_002_ReceiveToggleOff_PairDevicesStillActive_ReceiveToggleBackOn()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T3-002");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        // 1. Toggle Receive off
        await ui.SwitchTabAsync("NavReceive");
        await ui.ClickByIdAsync("ReceiveControlButton");
        await ui.AssertVisibleByNameAsync("Start receiving");

        // 2. Pair devices Ready-to-pair still operates while Receive is off
        await ui.SwitchTabAsync("NavPairDevices");
        await ui.ClickByNameAsync("Show this PC's pairing QR and code");
        await ui.WaitForNameAsync("Six-digit pairing code");
        await ui.ClickByNameAsync("Copy link");
        WireV2Invitation v2 = WireClientProbe.DecodeV2Uri(await ui.ReadClipboardAsync());

        using var peer = new IdentityV1Peer("TogglePairPeer-T3-002");
        Task<Gld2PairOutcome> pairTask = Gld2Probe.RunQrV2PairAsync(v2, peer, approveOnSuccess: true);
        await ui.WaitForNameAsync("Approve", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Approve");
        Assert.True((await pairTask).Paired);

        // 3. Toggle Receive back on and complete a transfer
        await ui.SwitchTabAsync("NavReceive");
        await ui.ClickByIdAsync("ReceiveControlButton");
        await ui.AssertVisibleByNameAsync("Stop receiving");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        WireV1Invitation v1 = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

        byte[] payload = Encoding.UTF8.GetBytes("toggle-back-on-payload");
        Task<IdentityTransferOutcome> sendTask = peer.SendFilesProfile1Async(v1, [("toggle-ok.txt", payload)]);
        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Accept transfer");
        Assert.True((await sendTask).Succeeded);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(payload)), CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "toggle-ok.txt")));
    }

    [Fact]
    [Trait("Id", "E2E-T3-003")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T3_003_QrV2Pair_ImmediateTrustedSend_Restart_SecondTrustedSend()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T3-003");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        byte[] file1 = Encoding.UTF8.GetBytes("vm-trust-step1");
        byte[] file2 = Encoding.UTF8.GetBytes("vm-trust-step2");
        string sha1 = Convert.ToHexStringLower(SHA256.HashData(file1));
        string sha2 = Convert.ToHexStringLower(SHA256.HashData(file2));

        try
        {
            string start1 = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest"" ""$HOME/cfg""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --trust-store ""$HOME/cfg/trust-v1.json"" --identity-store ""$HOME/cfg/identity-v1.pk8"" --decision-offer accept --decision-enrollment accept --timeout-seconds 40 > ""$HOME/r1.out"" 2>""$HOME/r1.err"" < /dev/null &
echo $! > ""$HOME/r1.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r1.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/r1.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);

            ProcessRunResult r1 = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-003", start1, timeoutSeconds: 30);
            Assert.Equal(0, r1.ExitCode);
            string uri1 = VmTestHelpers.ExtractOutput(r1).Trim();

            using var winPeer = new IdentityV1Peer("WinPeer-T3-003");
            IdentityTransferOutcome out1 = await winPeer.SendFilesProfile1Async(WireClientProbe.DecodeV1Uri(uri1), [("step1.txt", file1)]);
            Assert.True(out1.Succeeded, out1.Message);
            string fp1 = out1.ReceiverFingerprintHex!;

            // Restart VM listener as glide daemon accepting only winPeer (verifies identity & trust persist across restart)
            string startDaemon = @"
set -euo pipefail
RPID=""$(cat ""$HOME/r1.pid"")""
for _ in $(seq 1 30); do if ! kill -0 ""$RPID"" 2>/dev/null; then break; fi; sleep 0.2; done
FP_VM=""$(glide identity show | awk '/Persistent identity:/ {print $3}')""
echo ""FP_VM=$FP_VM""
nohup glide daemon --accept-paired-offers --trusted-peer ""__WIN_FP__"" --port 0 --destination ""$HOME/dest"" > ""$HOME/daemon.log"" 2>&1 < /dev/null &
echo $! > ""$HOME/daemon.pid""
for _ in $(seq 1 40); do
  if [ -f ""$XDG_RUNTIME_DIR/glide/daemon-invitation.txt"" ]; then
    echo ""URI2=$(cat ""$XDG_RUNTIME_DIR/glide/daemon-invitation.txt"")""
    exit 0
  fi
  sleep 0.25
done
cat ""$HOME/daemon.log"" >&2
exit 1
".Replace("__WIN_FP__", winPeer.FingerprintHex);

            ProcessRunResult r2 = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-003", startDaemon, timeoutSeconds: 30);
            Assert.Equal(0, r2.ExitCode);
            string r2Out = VmTestHelpers.ExtractOutput(r2);
            string? uri2 = r2Out.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
                .FirstOrDefault(l => l.StartsWith("URI2="))?["URI2=".Length..].Trim();
            Assert.False(string.IsNullOrEmpty(uri2));
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri(uri2!);
            inv2 = inv2 with { Endpoints = [(TargetEnvironment.Current.VmHost, inv2.Endpoints[0].Port)] };

            IdentityTransferOutcome out2 = await winPeer.SendFilesProfile1Async(inv2, [("step2.txt", file2)], expectedReceiverFingerprintHex: fp1);
            Assert.True(out2.Succeeded, out2.Message);

            await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-003", "kill -TERM \"$(cat \"$HOME/daemon.pid\")\" && wait \"$(cat \"$HOME/daemon.pid\")\" || true");
            var (_, vmSha1) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t3-003/home/dest/step1.txt");
            var (_, vmSha2) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t3-003/home/dest/step2.txt");
            Assert.Equal(sha1, vmSha1);
            Assert.Equal(sha2, vmSha2);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t3-003");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T3-004")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T3_004_CancelMidTransfer_RetainsSourceCleanStaging_AndRetryToCompletion()
    {
        await using var sandbox = new E2ESandbox("E2E-T3-004");
        string srcFile = Path.Combine(sandbox.SourceDir, "cancel-retry-bundle.bin");
        string srcSha = CorpusVerifier.WriteIncompressibleFile(srcFile, 8L * 1024 * 1024, 3004);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (recv1, send1) = await E2ETestHelpers.RunLoopbackTransferAsync(
            sandbox, sandbox.SourceDir, acceptOffer: true, cancelAfterBytes: 256 * 1024);
        Assert.NotEqual(0, recv1.ExitCode);
        Assert.NotEqual(0, send1.ExitCode);
        Assert.Equal(srcSha, CorpusVerifier.ComputeFileSha256(srcFile));
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();

        var (recv2, send2) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir, acceptOffer: true);
        E2ETestHelpers.AssertResultSuccess(send2, "RetrySender");
        E2ETestHelpers.AssertResultSuccess(recv2, "RetryReceiver");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T3-005")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T3_005_DuplicatePeerNames_RevokeOne_TransferToRemainingPeer()
    {
        await using var sandbox = new E2ESandbox("E2E-T3-005");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peerA = new IdentityV1Peer("SharedDeskName");
        using var peerB = new IdentityV1Peer("SharedDeskName");

        byte[] payloadA = Encoding.UTF8.GetBytes("shared-name-peerA");
        byte[] payloadB = Encoding.UTF8.GetBytes("shared-name-peerB");
        string shaB = Convert.ToHexStringLower(SHA256.HashData(payloadB));

        // 1. Enroll both PeerA and PeerB (both named "SharedDeskName") into recvTrust
        foreach (var (peer, fname, bytes) in new[] { (peerA, "from-a.bin", payloadA), (peerB, "from-b.bin", payloadB) })
        {
            await using InteractiveDriverSession recv = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                [
                    "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                    "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                    "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                    "--timeout-seconds", "25"
                ]);
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri((await recv.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            Assert.True((await peer.SendFilesProfile1Async(inv, [(fname, bytes)])).Succeeded);
            E2ETestHelpers.AssertResultSuccess(await recv.WaitForExitAsync(TimeSpan.FromSeconds(15)), $"Enroll-{fname}");
        }

        // Verify 2 distinct entries in trust-v1.json despite identical DisplayName
        using (var trustDoc = JsonDocument.Parse(File.ReadAllText(recvTrust)))
        {
            Assert.Equal(2, trustDoc.RootElement.GetProperty("peers").GetArrayLength());
        }

        // 2. Revoke PeerA from trust-v1.json while preserving PeerB
        var rootObj = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(recvTrust))!.AsObject();
        var peersArr = rootObj["peers"]!.AsArray();
        for (int i = peersArr.Count - 1; i >= 0; i--)
        {
            if (peersArr[i]!["FingerprintHex"]!.GetValue<string>() == peerA.FingerprintHex)
            {
                peersArr.RemoveAt(i);
            }
        }
        File.WriteAllText(recvTrust, rootObj.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

        // 3. PeerA MUST now be rejected under --decision-enrollment decline + --expected-peer PeerA
        await using (InteractiveDriverSession recvA = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peerA.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "15"
            ]))
        {
            WireV1Invitation invA = WireClientProbe.DecodeV1Uri((await recvA.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome rejA = await peerA.SendFilesProfile1Async(invA, [("revoked-a.bin", payloadA)]);
            Assert.False(rejA.Succeeded);
            Assert.NotEqual(0, (await recvA.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }
        Assert.False(File.Exists(Path.Combine(sandbox.DestDir, "revoked-a.bin")));

        // 4. Remaining PeerB MUST still succeed cleanly under --decision-enrollment decline + --expected-peer PeerB
        await using (InteractiveDriverSession recvB = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peerB.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "25"
            ]))
        {
            WireV1Invitation invB = WireClientProbe.DecodeV1Uri((await recvB.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome okB = await peerB.SendFilesProfile1Async(invB, [("remaining-b.bin", payloadB)]);
            Assert.True(okB.Succeeded, okB.Message);
            E2ETestHelpers.AssertResultSuccess(await recvB.WaitForExitAsync(TimeSpan.FromSeconds(15)), "RemainingPeerB");
        }
        Assert.Equal(shaB, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "remaining-b.bin")));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T3-006")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T3_006_LinuxSettingsDestChange_PlusDaemon_PlusMaxOfferBytes()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T3-006");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/t3-006""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/custom-recv""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_A=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
printf 'enroll' > ""$ROOT/b/e.txt""

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
printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/e.txt""
wait $RPID

run_a glide daemon --accept-paired-offers --trusted-peer ""$FP_B"" --port 0 --max-offer-bytes 8192 --destination ""$ROOT/a/custom-recv"" > ""$ROOT/a/daemon.log"" 2>&1 &
DPID=$!
trap 'kill $DPID 2>/dev/null || true' EXIT

INV_FILE=""$ROOT/a/run/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do if [ -f ""$INV_FILE"" ]; then break; fi; sleep 0.25; done
URI_1=""$(cat ""$INV_FILE"")""

# 16 KiB exceeds --max-offer-bytes 8192 -> rejected
dd if=/dev/urandom of=""$ROOT/b/too-big.bin"" bs=1024 count=16 status=none
set +e
run_b glide send --pair ""$URI_1"" --trusted-peer ""$FP_A"" ""$ROOT/b/too-big.bin"" >/dev/null 2>&1
RC_BIG=$?
set -e
test ""$RC_BIG"" -ne 0
test ""$(find ""$ROOT/a/custom-recv"" -type f | wc -l)"" -eq 0
grep -Fq ""Offer rejected by peer-specific headless policy"" ""$ROOT/a/daemon.log""

# Wait for rotated daemon-invitation.txt and send 4 KiB -> succeeds
URI_2=""""
for _ in $(seq 1 30); do
  if [ -f ""$INV_FILE"" ]; then
    CAND=""$(cat ""$INV_FILE"")""
    if [ ""$CAND"" != ""$URI_1"" ]; then URI_2=""$CAND""; break; fi
  fi
  sleep 0.25
done
test -n ""$URI_2""

dd if=/dev/urandom of=""$ROOT/b/ok-4k.bin"" bs=1024 count=4 status=none
SRC_SHA=""$(sha256sum ""$ROOT/b/ok-4k.bin"" | awk '{print $1}')""
run_b glide send --pair ""$URI_2"" --trusted-peer ""$FP_A"" ""$ROOT/b/ok-4k.bin""
test -f ""$ROOT/a/custom-recv/ok-4k.bin""
test ""$(sha256sum ""$ROOT/a/custom-recv/ok-4k.bin"" | awk '{print $1}')"" = ""$SRC_SHA""
test ""$(find ""$ROOT/a/data/glide/staging"" -type f 2>/dev/null | wc -l)"" -eq 0

kill -TERM ""$DPID""
wait ""$DPID""
echo ""VM_MAX_OFFER_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-006", script, timeoutSeconds: 60);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_MAX_OFFER_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t3-006");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T3-007")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T3_007_AndroidShareMultiple_PairedSend_ReturnToSelection()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T3-007");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "share_test_multiple");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "vacation_1.jpg");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Proceed to send (2)");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select recipient");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "transfer_completed_sender");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_completed_card", absent: true);
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send");
    }

    [Fact]
    [Trait("Id", "E2E-T3-008")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T3_008_WindowsActivityNavigateAwayMidTransfer_BannerAndResultPreserved()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T3-008");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        await ui.SwitchTabAsync("NavReceive");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

        byte[] payload = new byte[8 * 1024 * 1024];
        new Random(3008).NextBytes(payload);
        string expectedSha = Convert.ToHexStringLower(SHA256.HashData(payload));

        using var peer = new IdentityV1Peer("NavAwayPeer-T3-008");
        Task<IdentityTransferOutcome> sendTask = peer.SendFilesProfile1Async(inv, [("nav-away-8m.bin", payload)]);
        await ui.WaitForNameAsync("Not Now", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Not Now");
        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Accept transfer");

        // Navigate away to Send and back to Activity
        await ui.SwitchTabAsync("NavSend");
        await ui.SwitchTabAsync("NavActivity");

        IdentityTransferOutcome outcome = await sendTask;
        Assert.True(outcome.Succeeded, outcome.Message);
        await ui.WaitForIdAsync("ActivityHistoryList", timeoutSeconds: 15);
        await ui.AssertVisibleByNameAsync("nav-away-8m.bin");
        Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "nav-away-8m.bin")));
    }

    [Fact]
    [Trait("Id", "E2E-T3-009")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T3_009_AtomicCommitAndCrossDirectoryVerifiedCopy_MatchesSha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T3-009");
        string stagingA = sandbox.CreateSubDir("staging-a");
        string destB = sandbox.CreateSubDir("dest-b");
        CorpusManifest manifest = CorpusVerifier.CreateSmallBatch(sandbox.SourceDir, count: 6, baseSize: 8192, seedOffset: 3009);

        await using InteractiveDriverSession receiver = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", stagingA, "--dest-dir", destB,
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
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, destB);
        E2ESandbox.AssertDirectoryCleanOfStagingArtifacts(stagingA);
    }

    [Fact]
    [Trait("Id", "E2E-T3-010")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T3_010_StaleMdnsDiscovery_EndpointRefresh_VerifiedTransfer()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T3-010");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        byte[] payload = Encoding.UTF8.GetBytes("stale-endpoint-refresh-payload");
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

        try
        {
            // Start VM receiver and enroll winPeer
            string startVm = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest"" ""$HOME/cfg""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --trust-store ""$HOME/cfg/trust-v1.json"" --identity-store ""$HOME/cfg/identity-v1.pk8"" --decision-offer accept --decision-enrollment accept --timeout-seconds 40 > ""$HOME/r1.out"" 2>""$HOME/r1.err"" < /dev/null &
echo $! > ""$HOME/r1.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r1.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/r1.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);
            ProcessRunResult r1 = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-010", startVm, timeoutSeconds: 25);
            Assert.Equal(0, r1.ExitCode);
            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri(VmTestHelpers.ExtractOutput(r1).Trim());

            using var winPeer = new IdentityV1Peer("StaleEndpointPeer");
            IdentityTransferOutcome out1 = await winPeer.SendFilesProfile1Async(inv1, [("seed.txt", payload)]);
            Assert.True(out1.Succeeded, out1.Message);
            string vmFp = out1.ReceiverFingerprintHex!;

            // Start fresh VM listener on new port, while testing closed stale port fails closed first
            int closedPort = E2ESandbox.AllocateEphemeralPort();
            await Assert.ThrowsAnyAsync<Exception>(() =>
                winPeer.SendTrustedProfile2Async("127.0.0.1", closedPort, vmFp, [("stale.txt", payload)]));

            ProcessRunResult r2 = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-010", @"
set -euo pipefail
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --trust-store ""$HOME/cfg/trust-v1.json"" --identity-store ""$HOME/cfg/identity-v1.pk8"" --expected-peer ""__WIN_FP__"" --decision-offer accept --decision-enrollment decline --timeout-seconds 40 > ""$HOME/r2.out"" 2>""$HOME/r2.err"" < /dev/null &
echo $! > ""$HOME/r2.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r2.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/r2.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost).Replace("__WIN_FP__", winPeer.FingerprintHex), timeoutSeconds: 25);
            Assert.Equal(0, r2.ExitCode);
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri(VmTestHelpers.ExtractOutput(r2).Trim());
            IdentityTransferOutcome out2 = await winPeer.SendFilesProfile1Async(inv2, [("refreshed.txt", payload)], expectedReceiverFingerprintHex: vmFp);
            Assert.True(out2.Succeeded, out2.Message);

            var (_, vmSha) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t3-010/home/dest/refreshed.txt");
            Assert.Equal(sha, vmSha);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t3-010");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T3-011")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T3_011_TrustMismatchRejectsTransfer_ThenReEnrollRecoverySucceeds()
    {
        await using var sandbox = new E2ESandbox("E2E-T3-011");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peer = new IdentityV1Peer("RevokeRecoveryPeer");
        byte[] payload = Encoding.UTF8.GetBytes("trust-revoke-reenroll");
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

        // 1. Enroll Peer
        await using (InteractiveDriverSession r1 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "25"
            ]))
        {
            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri((await r1.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            Assert.True((await peer.SendFilesProfile1Async(inv1, [("step1.bin", payload)])).Succeeded);
            E2ETestHelpers.AssertResultSuccess(await r1.WaitForExitAsync(TimeSpan.FromSeconds(15)), "Enroll");
        }

        // 2. Revoke peer from recvTrust -> expected-peer + decline rejects transfer
        var docObj = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(recvTrust))!.AsObject();
        docObj["peers"]!.AsArray().Clear();
        File.WriteAllText(recvTrust, docObj.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

        await using (InteractiveDriverSession r2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peer.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "15"
            ]))
        {
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri((await r2.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            Assert.False((await peer.SendFilesProfile1Async(inv2, [("rejected.bin", payload)])).Succeeded);
            Assert.NotEqual(0, (await r2.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }
        Assert.False(File.Exists(Path.Combine(sandbox.DestDir, "rejected.bin")));

        // 3. Re-enroll Peer -> transfer succeeds
        await using (InteractiveDriverSession r3 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "25"
            ]))
        {
            WireV1Invitation inv3 = WireClientProbe.DecodeV1Uri((await r3.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            Assert.True((await peer.SendFilesProfile1Async(inv3, [("recovered.bin", payload)])).Succeeded);
            E2ETestHelpers.AssertResultSuccess(await r3.WaitForExitAsync(TimeSpan.FromSeconds(15)), "ReEnroll");
        }
        Assert.Equal(sha, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "recovered.bin")));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T3-012")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T3_012_LinuxIdentityRotateYes_CascadesRevocationAndBlocksDaemon()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T3-012");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/t3-012""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/dest""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
printf 't3-012-e1' > ""$ROOT/b/e1.txt""
FIFO=""$ROOT/a/run/in.fifo""; mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
run_a glide receive --port 0 < ""$FIFO"" > ""$ROOT/a/e1.log"" 2>&1 &
RPID=$!
URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/e1.log"" 2>/dev/null; then URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/e1.log"" | head -n 1)""; break; fi
  sleep 0.25
done
printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/e1.txt""
wait $RPID

ROT=""$(run_a glide identity rotate --yes)""
echo ""$ROT"" | grep -Fq ""Revoked peer trust records: 1""
run_a glide trust list | grep -Fq ""No enrolled trusted peers.""

set +e
DAEM_OUT=""$(run_a glide daemon --accept-paired-offers --trusted-peer ""$FP_B"" --port 0 2>&1)""
DAEM_RC=$?
set -e
test ""$DAEM_RC"" -eq 1
echo ""$DAEM_OUT"" | grep -Fq ""not an enrolled persistent identity""

# Re-enroll peerB and verify daemon succeeds
FIFO2=""$ROOT/a/run/in2.fifo""; mkfifo ""$FIFO2""
( printf 'y\ny\n' > ""$FIFO2"" ) &
run_a glide receive --port 0 < ""$FIFO2"" > ""$ROOT/a/e2.log"" 2>&1 &
RPID2=$!
URI2=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/e2.log"" 2>/dev/null; then URI2=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/e2.log"" | head -n 1)""; break; fi
  sleep 0.25
done
printf 'y\n' | run_b glide send --pair ""$URI2"" ""$ROOT/b/e1.txt""
wait $RPID2

FP_A2=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
run_a glide daemon --accept-paired-offers --trusted-peer ""$FP_B"" --port 0 --destination ""$ROOT/a/dest"" > ""$ROOT/a/daemon.log"" 2>&1 &
DPID=$!
trap 'kill $DPID 2>/dev/null || true' EXIT
INV_FILE=""$ROOT/a/run/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do if [ -f ""$INV_FILE"" ]; then break; fi; sleep 0.25; done
D_URI=""$(cat ""$INV_FILE"")""
SRC_SHA=""$(sha256sum ""$ROOT/b/e1.txt"" | awk '{print $1}')""
run_b glide send --pair ""$D_URI"" --trusted-peer ""$FP_A2"" ""$ROOT/b/e1.txt""
test ""$(sha256sum ""$ROOT/a/dest/e1.txt"" | awk '{print $1}')"" = ""$SRC_SHA""
kill -TERM ""$DPID""; wait ""$DPID""
echo ""VM_ROTATE_BLOCKS_DAEMON_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-012", script, timeoutSeconds: 60);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_ROTATE_BLOCKS_DAEMON_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t3-012");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T3-013")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T3_013_WindowsSingleInstanceForwarding_WithBusyGuard()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T3-013");
        string f1 = Path.Combine(sandbox.SourceDir, "single-inst-1.txt");
        string f2 = Path.Combine(sandbox.SourceDir, "single-inst-2.txt");
        CorpusVerifier.WriteUtf8TextFile(f1, "payload-1");
        CorpusVerifier.WriteUtf8TextFile(f2, "payload-2");

        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);
        await ui.SwitchTabAsync("NavReceive");

        // Secondary launch forwards files via named pipe and switches primary to Send tab
        int exit1 = await ui.ForwardActivationAsync(f1, f2);
        Assert.Equal(0, exit1);
        await ui.WaitForIdAsync("SelectedFilesList");
        await ui.AssertVisibleByNameAsync("single-inst-1.txt");
        await ui.AssertVisibleByNameAsync("single-inst-2.txt");
        await ui.ClickByNameAsync("Clear selected files");

        // Now start a live 12 MiB receive transfer and send a second glide:// activation during active transfer -> busy guard protects active transfer
        await ui.SwitchTabAsync("NavReceive");
        await ui.ClickByIdAsync("ReceiveCopyLinkButton");
        WireV1Invitation liveInv = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

        byte[] largePayload = new byte[12 * 1024 * 1024];
        new Random(3013).NextBytes(largePayload);
        string expectedSha = Convert.ToHexStringLower(SHA256.HashData(largePayload));

        using var peer = new IdentityV1Peer("BusyGuardPeer-T3-013");
        Task<IdentityTransferOutcome> sendTask = peer.SendFilesProfile1Async(liveInv, [("busy-guard-12m.bin", largePayload)]);
        await ui.WaitForNameAsync("Not Now", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Not Now");
        await ui.WaitForIdAsync("OfferConsentOverlay", timeoutSeconds: 15);
        await ui.ClickByNameAsync("Accept transfer");

        // Forward a competing glide:// URI while transfer is active
        string competingUri = WireClientProbe.BuildV1Uri("127.0.0.1", E2ESandbox.AllocateEphemeralPort(), new string('a', 64));
        int exit2 = await ui.ForwardActivationAsync(competingUri);
        Assert.Equal(0, exit2);

        IdentityTransferOutcome outcome = await sendTask;
        Assert.True(outcome.Succeeded, $"Active transfer should complete uncorrupted despite competing activation: {outcome.ErrorCode} {outcome.Message}");
        Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "busy-guard-12m.bin")));
    }

    [Fact]
    [Trait("Id", "E2E-T3-014")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T3_014_LinuxXdgOverride_PlusSystemdDaemonEnv_PlusInvitationRotation()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T3-014");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/t3-014""
mkdir -p ""$ROOT/a/cfg/glide"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/env-dest""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run""
mkdir -p ""$ROOT/c/cfg"" ""$ROOT/c/data"" ""$ROOT/c/state"" ""$ROOT/c/cache"" ""$ROOT/c/run""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run"" ""$ROOT/c/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }
run_c() { HOME=""$ROOT/c"" XDG_CONFIG_HOME=""$ROOT/c/cfg"" XDG_DATA_HOME=""$ROOT/c/data"" XDG_STATE_HOME=""$ROOT/c/state"" XDG_CACHE_HOME=""$ROOT/c/cache"" XDG_RUNTIME_DIR=""$ROOT/c/run"" ""$@""; }

FP_A=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
run_c glide identity show >/dev/null
printf 'enroll' > ""$ROOT/b/e.txt""
FIFO=""$ROOT/a/run/in.fifo""; mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
run_a glide receive --port 0 < ""$FIFO"" > ""$ROOT/a/e.log"" 2>&1 &
RPID=$!
URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/e.log"" 2>/dev/null; then URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/e.log"" | head -n 1)""; break; fi
  sleep 0.25
done
printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/e.txt""
wait $RPID

cat > ""$ROOT/a/cfg/glide/daemon.env"" <<EOF
GLIDE_TRUSTED_PEER=$FP_B
GLIDE_MAX_OFFER_BYTES=65536
GLIDE_DESTINATION=$ROOT/a/env-dest
EOF
chmod 600 ""$ROOT/a/cfg/glide/daemon.env""

set -a
. ""$ROOT/a/cfg/glide/daemon.env""
set +a
run_a glide daemon --accept-paired-offers --trusted-peer ""$GLIDE_TRUSTED_PEER"" --max-offer-bytes ""$GLIDE_MAX_OFFER_BYTES"" --destination ""$GLIDE_DESTINATION"" --port 0 > ""$ROOT/a/daemon.log"" 2>&1 &
DPID=$!
trap 'kill $DPID 2>/dev/null || true' EXIT

INV_FILE=""$ROOT/a/run/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do if [ -f ""$INV_FILE"" ]; then break; fi; sleep 0.25; done
test ""$(stat -c '%a' ""$INV_FILE"")"" = ""600""
URI_1=""$(cat ""$INV_FILE"")""

# Unenrolled peerC fails -> daemon rotates daemon-invitation.txt to URI_2 != URI_1
printf 'unauth' > ""$ROOT/c/u.txt""
set +e
run_c glide send --pair ""$URI_1"" --trusted-peer ""$FP_A"" ""$ROOT/c/u.txt"" >/dev/null 2>&1
set -e

URI_2=""""
for _ in $(seq 1 30); do
  if [ -f ""$INV_FILE"" ]; then
    CAND=""$(cat ""$INV_FILE"")""
    if [ ""$CAND"" != ""$URI_1"" ]; then URI_2=""$CAND""; break; fi
  fi
  sleep 0.25
done
test -n ""$URI_2""

printf 'env-daemon-ok' > ""$ROOT/b/ok.txt""
SRC_SHA=""$(sha256sum ""$ROOT/b/ok.txt"" | awk '{print $1}')""
run_b glide send --pair ""$URI_2"" --trusted-peer ""$FP_A"" ""$ROOT/b/ok.txt""
test -f ""$GLIDE_DESTINATION/ok.txt""
test ""$(sha256sum ""$GLIDE_DESTINATION/ok.txt"" | awk '{print $1}')"" = ""$SRC_SHA""
kill -TERM ""$DPID""; wait ""$DPID""
echo ""VM_ENV_ROTATION_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-014", script, timeoutSeconds: 60);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_ENV_ROTATION_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t3-014");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T3-015")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T3_015_AndroidCustomSafDestination_ExposesEffectiveSaveDestination()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T3-015");

        await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "theme_settings");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Dark");
        await AndroidUiHelpers.SwipeScrollAsync(sandbox, "Down");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Received file save location");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Download/Glide");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "service_notification_redacted");
        var (svc1, _, notif1) = await AndroidUiHelpers.CheckServiceRunningAsync(sandbox);
        Assert.True(svc1, "Expected redacted TransferService to be active.");
        Assert.DoesNotContain("secret_tax_returns.pdf", notif1, StringComparison.OrdinalIgnoreCase);

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "service_notification_detailed");
        var (svc2, notifFound2, notif2) = await AndroidUiHelpers.CheckServiceRunningAsync(sandbox);
        Assert.True(svc2 && notifFound2, "Expected detailed TransferService notification to be active.");
        Assert.Contains("family_vacation_4k.mp4", notif2, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    [Trait("Id", "E2E-T3-016")]
    [Trait("Tier", "3")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T3_016_PackageUpgrade100To100_PreservesIdentityTrustSettingsHistoryAndFiles()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T3-016");
        string debPath = TargetEnvironment.Current.ResolveLinuxDebPackage();

        byte[] beforeBytes = Encoding.UTF8.GetBytes("pre-upgrade-deb-transfer");
        byte[] afterBytes = Encoding.UTF8.GetBytes("post-upgrade-deb-transfer");
        string shaBefore = Convert.ToHexStringLower(SHA256.HashData(beforeBytes));
        string shaAfter = Convert.ToHexStringLower(SHA256.HashData(afterBytes));

        try
        {
            // 1. Deploy & install .deb on VM
            await VmTestHelpers.DeployFileAsync(sandbox, "e2e-t3-016", debPath, "/tmp/glide-e2e/e2e-t3-016/glide.deb");
            ProcessRunResult inst1 = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox, "Invoke-VmRemote.ps1",
                ["-Action", "InstallDeb", "-VmHost", TargetEnvironment.Current.VmHost, "-VmUser", TargetEnvironment.Current.VmUser, "-RemotePath", "/tmp/glide-e2e/e2e-t3-016/glide.deb"]);
            Assert.Equal(0, inst1.ExitCode);

            // 2. Start /usr/bin/glide receive with enrollment + receive before-upgrade.bin from Win peer
            using var winPeer = new IdentityV1Peer("WinPeer-T3-016");
            string startPre = @"
set -euo pipefail
mkdir -p ""$HOME/Downloads"" ""$XDG_CONFIG_HOME/glide"" ""$XDG_DATA_HOME/glide""
FP_BEFORE=""$(/usr/bin/glide identity show | awk '/Persistent identity:/ {print $3}')""
echo ""FP_BEFORE=$FP_BEFORE""
FIFO=""$XDG_RUNTIME_DIR/in.fifo""; mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
nohup /usr/bin/glide receive --port 0 < ""$FIFO"" > ""$HOME/r1.log"" 2>&1 < /dev/null &
echo $! > ""$HOME/r1.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r1.log"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/r1.log"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
exit 1
";
            ProcessRunResult preRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-016", startPre, timeoutSeconds: 30, useSystemPath: true);
            Assert.Equal(0, preRes.ExitCode);
            string preOut = VmTestHelpers.ExtractOutput(preRes);
            string fpBefore = preOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
                .First(l => l.StartsWith("FP_BEFORE="))["FP_BEFORE=".Length..].Trim();
            string uri1 = preOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
                .First(l => l.StartsWith("glide://pair/v1#")).Trim();

            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri(uri1);
            inv1 = inv1 with { Endpoints = [(TargetEnvironment.Current.VmHost, inv1.Endpoints[0].Port)] };
            IdentityTransferOutcome out1 = await winPeer.SendFilesProfile1Async(inv1, [("before-upgrade.bin", beforeBytes)], expectedReceiverFingerprintHex: fpBefore);
            Assert.True(out1.Succeeded, out1.Message);

            // Record hashes & write daemon.env + desktop-state.json
            ProcessRunResult seedRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-016", @"
set -euo pipefail
RPID=""$(cat ""$HOME/r1.pid"")""
for _ in $(seq 1 30); do if ! kill -0 ""$RPID"" 2>/dev/null; then break; fi; sleep 0.2; done
cat > ""$XDG_CONFIG_HOME/glide/daemon.env"" <<'EOF'
GLIDE_MAX_OFFER_BYTES=10485760
EOF
chmod 600 ""$XDG_CONFIG_HOME/glide/daemon.env""
cat > ""$XDG_DATA_HOME/glide/desktop-state.json"" <<EOF
{""SchemaVersion"":1,""Settings"":{""ReceiveDirectory"":""$HOME/Downloads"",""ListenerPort"":42424},""History"":[]}
EOF
sha256sum ""$XDG_CONFIG_HOME/glide/identity-v1.pk8"" ""$XDG_CONFIG_HOME/glide/trust-v1.json"" ""$XDG_CONFIG_HOME/glide/daemon.env"" ""$XDG_DATA_HOME/glide/desktop-state.json"" ""$(find ""$HOME/Downloads"" -name 'before-upgrade.bin' | head -n 1)""
", useSystemPath: true);
            Assert.Equal(0, seedRes.ExitCode);
            string hashesBefore = VmTestHelpers.ExtractOutput(seedRes).Trim();

            // 3. Upgrade .deb 1.0.0 -> 1.0.0
            ProcessRunResult inst2 = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox, "Invoke-VmRemote.ps1",
                ["-Action", "InstallDeb", "-VmHost", TargetEnvironment.Current.VmHost, "-VmUser", TargetEnvironment.Current.VmUser, "-RemotePath", "/tmp/glide-e2e/e2e-t3-016/glide.deb"]);
            Assert.Equal(0, inst2.ExitCode);

            // 4. Verify all hashes & fingerprint unchanged + complete post-upgrade daemon transfer
            ProcessRunResult postCheck = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-016", @"
set -euo pipefail
FP_AFTER=""$(/usr/bin/glide identity show | awk '/Persistent identity:/ {print $3}')""
echo ""FP_AFTER=$FP_AFTER""
sha256sum ""$XDG_CONFIG_HOME/glide/identity-v1.pk8"" ""$XDG_CONFIG_HOME/glide/trust-v1.json"" ""$XDG_CONFIG_HOME/glide/daemon.env"" ""$XDG_DATA_HOME/glide/desktop-state.json"" ""$(find ""$HOME/Downloads"" -name 'before-upgrade.bin' | head -n 1)""
", useSystemPath: true);
            Assert.Equal(0, postCheck.ExitCode);
            string postOut = VmTestHelpers.ExtractOutput(postCheck);
            Assert.Contains($"FP_AFTER={fpBefore}", postOut);
            foreach (string line in hashesBefore.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
            {
                Assert.Contains(line.Trim(), postOut);
            }

            ProcessRunResult startDaemon = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-016", @"
set -euo pipefail
nohup /usr/bin/glide daemon --accept-paired-offers --trusted-peer ""__WIN_FP__"" --port 0 --destination ""$HOME/Downloads"" > ""$HOME/d.log"" 2>&1 < /dev/null &
echo $! > ""$HOME/d.pid""
for _ in $(seq 1 40); do
  if [ -f ""$XDG_RUNTIME_DIR/glide/daemon-invitation.txt"" ]; then
    cat ""$XDG_RUNTIME_DIR/glide/daemon-invitation.txt""
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__WIN_FP__", winPeer.FingerprintHex), useSystemPath: true);
            Assert.Equal(0, startDaemon.ExitCode);
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri(VmTestHelpers.ExtractOutput(startDaemon).Trim());
            inv2 = inv2 with { Endpoints = [(TargetEnvironment.Current.VmHost, inv2.Endpoints[0].Port)] };
            IdentityTransferOutcome out2 = await winPeer.SendFilesProfile1Async(inv2, [("after-upgrade.bin", afterBytes)], expectedReceiverFingerprintHex: fpBefore);
            Assert.True(out2.Succeeded, out2.Message);

            await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t3-016", "kill -TERM \"$(cat \"$HOME/d.pid\")\" && wait \"$(cat \"$HOME/d.pid\")\" || true", useSystemPath: true);
            var (_, vmShaBefore) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t3-016/home/Downloads/Glide/before-upgrade.bin");
            var (_, vmShaAfter) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t3-016/home/Downloads/after-upgrade.bin");
            Assert.Equal(shaBefore, vmShaBefore);
            Assert.Equal(shaAfter, vmShaAfter);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t3-016");
        }
    }
}
