using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Glide.E2E.Tests.Infrastructure;

namespace Glide.E2E.Tests;

public sealed class Tier4_RealWorldScenarioTests
{
    [Fact]
    [Trait("Id", "E2E-T4-001")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T4_001_MixedDeveloperProjectBundle_Nested0BSmallMultiPageUnicodeAndLargeIncompressible_MatchesSha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T4-001");
        CorpusManifest manifest = CorpusVerifier.CreateDeveloperProjectBundle(sandbox.SourceDir, largeMiB: 50);

        Assert.True(manifest.Items.Count >= 40, "Developer bundle must span >32 items for multi-page offers.");
        Assert.Contains(manifest.Items, i => i.SizeBytes == 0);
        Assert.Contains(manifest.Items, i => i.RelativePath.Contains("Unicode_"));
        Assert.Contains(manifest.Items, i => i.SizeBytes >= 50L * 1024 * 1024);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(
            sandbox,
            sandbox.SourceDir,
            acceptOffer: true,
            acceptEnrollment: false,
            timeoutSeconds: 120);

        E2ETestHelpers.AssertResultSuccess(sender, "DeveloperBundleSender");
        E2ETestHelpers.AssertResultSuccess(receiver, "DeveloperBundleReceiver");

        JsonElement recvRes = receiver.FindEvent("result")!.Value;
        Assert.Equal(manifest.Items.Count, recvRes.GetProperty("verifiedCount").GetInt32());
        Assert.Equal(manifest.TotalBytes, recvRes.GetProperty("verifiedBytes").GetInt64());

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T4-002")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T4_002_WindowsAndUbuntu26Vm_CrossOsTrustedPairAndSync()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T4-002");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        // 1. Win -> VM mixed developer bundle
        CorpusManifest winBundle = CorpusVerifier.CreateDeveloperProjectBundle(sandbox.SourceDir, largeMiB: 10);
        string winIp = TargetEnvironment.Current.ResolveWindowsLocalIpForVm();

        try
        {
            string startVmRecv = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest"" ""$HOME/cfg""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --trust-store ""$HOME/cfg/trust-v1.json"" --identity-store ""$HOME/cfg/identity-v1.pk8"" --decision-offer accept --decision-enrollment accept --timeout-seconds 90 > ""$HOME/r1.out"" 2>""$HOME/r1.err"" < /dev/null &
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
            ProcessRunResult r1 = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-002", startVmRecv, timeoutSeconds: 30);
            Assert.Equal(0, r1.ExitCode);
            string uri1 = VmTestHelpers.ExtractOutput(r1).Trim();

            ProcessRunResult send1 = await GlideProcessRunner.RunBackendDriverAsync(
                sandbox,
                ["connect-sender", "--source-dir", sandbox.SourceDir, "--decision-enrollment", "accept", "--timeout-seconds", "90"],
                stdinLine: uri1);
            E2ETestHelpers.AssertResultSuccess(send1, "WinToVmSender");

            ProcessRunResult vmWait = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-002", @"
set -euo pipefail
RPID=""$(cat ""$HOME/r1.pid"")""
for _ in $(seq 1 60); do if ! kill -0 ""$RPID"" 2>/dev/null; then break; fi; sleep 0.25; done
grep -Fq '""success"":true' ""$HOME/r1.out""
test ""$(find ""$HOME/staging"" -type f | wc -l)"" -eq 0
");
            Assert.Equal(0, vmWait.ExitCode);

            // 2. VM -> Win reverse corpus transfer
            await using InteractiveDriverSession winRecv = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                [
                    "listen-receiver", "--port", "0", "--advertised-ip", winIp,
                    "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                    "--decision-offer", "accept", "--decision-enrollment", "accept",
                    "--timeout-seconds", "90"
                ]);
            string winUri = (await winRecv.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!;

            string vmSendCmd = @"
set -euo pipefail
mkdir -p ""$HOME/vm-src""
Glide.BackendTestDriver generate-corpus --output-dir ""$HOME/vm-src"" --small-count 10 --large-mib 5 --incompressible > ""$HOME/gen.out""
printf '%s\n' ""__WIN_URI__"" | Glide.BackendTestDriver connect-sender --source-dir ""$HOME/vm-src"" --decision-enrollment accept --timeout-seconds 90
cat ""$HOME/vm-src/corpus-manifest.json""
".Replace("__WIN_URI__", winUri);
            ProcessRunResult vmSendRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-002", vmSendCmd, timeoutSeconds: 120);
            Assert.Equal(0, vmSendRes.ExitCode);
            ProcessRunResult winRecvExit = await winRecv.WaitForExitAsync(TimeSpan.FromSeconds(30));
            E2ETestHelpers.AssertResultSuccess(winRecvExit, "WinReverseReceiver");

            // Verify every received file on Windows against VM-generated manifest
            string vmOutText = VmTestHelpers.ExtractOutput(vmSendRes);
            int manifestStart = vmOutText.IndexOf("{\"schema\"", StringComparison.Ordinal);
            Assert.True(manifestStart >= 0, "Expected VM corpus-manifest.json in output.");
            using var vmManifestDoc = JsonDocument.Parse(vmOutText[manifestStart..]);
            var entries = vmManifestDoc.RootElement.GetProperty("items").EnumerateArray()
                .Select(it => new CorpusFileEntry(
                    it.GetProperty("relativePath").GetString()!,
                    it.GetProperty("sizeBytes").GetInt64(),
                    it.GetProperty("sha256").GetString()!))
                .ToList();
            CorpusVerifier.AssertDestinationMatchesManifest(new CorpusManifest(entries, entries.Sum(e => e.SizeBytes)), sandbox.DestDir);
            sandbox.AssertStagingClean();
            Assert.NotEmpty(winBundle.Items);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-002");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T4-003")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T4_003_Ubuntu26VmToWindows_HeadlessDaemonBackupWorkflow()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T4-003");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string backupFile1 = Path.Combine(sandbox.SourceDir, "backup-10m.bin");
        string sha1 = CorpusVerifier.WriteIncompressibleFile(backupFile1, 10L * 1024 * 1024, 4003);
        string backupFile2 = Path.Combine(sandbox.SourceDir, "Unicode_备份_note.txt");
        string sha2 = CorpusVerifier.WriteUtf8TextFile(backupFile2, "Ubuntu 26 VM daemon backup workflow");

        using var winPeer = new IdentityV1Peer("WinBackupSender-T4-003");
        try
        {
            string startEnroll = @"
set -euo pipefail
mkdir -p ""$HOME/backup-dest"" ""$HOME/staging""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/backup-dest"" --trust-store ""$XDG_CONFIG_HOME/glide/trust-v1.json"" --identity-store ""$XDG_CONFIG_HOME/glide/identity-v1.pk8"" --decision-offer accept --decision-enrollment accept --timeout-seconds 40 > ""$HOME/e.out"" 2>""$HOME/e.err"" < /dev/null &
echo $! > ""$HOME/e.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/e.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/e.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);
            ProcessRunResult enrollStart = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-003", startEnroll, timeoutSeconds: 25);
            Assert.Equal(0, enrollStart.ExitCode);
            WireV1Invitation invEnroll = WireClientProbe.DecodeV1Uri(VmTestHelpers.ExtractOutput(enrollStart).Trim());
            IdentityTransferOutcome outEnroll = await winPeer.SendFilesProfile1Async(invEnroll, [("enroll.txt", Encoding.UTF8.GetBytes("enroll"))]);
            Assert.True(outEnroll.Succeeded, outEnroll.Message);
            string vmFp = outEnroll.ReceiverFingerprintHex!;

            string startDaemon = @"
set -euo pipefail
EPID=""$(cat ""$HOME/e.pid"")""
for _ in $(seq 1 30); do if ! kill -0 ""$EPID"" 2>/dev/null; then break; fi; sleep 0.2; done
nohup glide daemon --accept-paired-offers --trusted-peer ""__WIN_FP__"" --port 0 --max-offer-bytes 134217728 --destination ""$HOME/backup-dest"" > ""$HOME/daemon.log"" 2>&1 < /dev/null &
echo $! > ""$HOME/daemon.pid""
INV_FILE=""$XDG_RUNTIME_DIR/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do
  if [ -f ""$INV_FILE"" ]; then
    test ""$(stat -c '%a' ""$INV_FILE"")"" = ""600""
    cat ""$INV_FILE""
    exit 0
  fi
  sleep 0.25
done
cat ""$HOME/daemon.log"" >&2
exit 1
".Replace("__WIN_FP__", winPeer.FingerprintHex);
            ProcessRunResult daemonRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-003", startDaemon, timeoutSeconds: 30);
            Assert.Equal(0, daemonRes.ExitCode);
            WireV1Invitation daemonInv = WireClientProbe.DecodeV1Uri(VmTestHelpers.ExtractOutput(daemonRes).Trim());
            daemonInv = daemonInv with { Endpoints = [(TargetEnvironment.Current.VmHost, daemonInv.Endpoints[0].Port)] };

            IdentityTransferOutcome backupOut = await winPeer.SendFilesProfile1Async(
                daemonInv,
                [
                    ("backup-10m.bin", await File.ReadAllBytesAsync(backupFile1)),
                    ("Unicode_备份_note.txt", await File.ReadAllBytesAsync(backupFile2))
                ],
                expectedReceiverFingerprintHex: vmFp);
            Assert.True(backupOut.Succeeded, backupOut.Message);

            ProcessRunResult stopRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-003", @"
set -euo pipefail
DPID=""$(cat ""$HOME/daemon.pid"")""
kill -TERM ""$DPID""
wait ""$DPID""
test ! -e ""$XDG_RUNTIME_DIR/glide/daemon-invitation.txt""
test ""$(find ""$XDG_DATA_HOME/glide/staging"" -type f 2>/dev/null | wc -l)"" -eq 0
");
            Assert.Equal(0, stopRes.ExitCode);

            var (_, vmSha1) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t4-003/home/backup-dest/backup-10m.bin");
            var (_, vmSha2) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t4-003/home/backup-dest/Unicode_备份_note.txt");
            Assert.Equal(sha1, vmSha1);
            Assert.Equal(sha2, vmSha2);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-003");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T4-004")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T4_004_Ubuntu26Vm_LocalSystemdDaemonAndAvaloniaUiParity()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T4-004");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/t4-004""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/dest""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run"" ""$ROOT/b/src""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_A=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
: > ""$ROOT/b/src/zero.bin""
dd if=/dev/urandom of=""$ROOT/b/src/inc.bin"" bs=1024 count=64 status=none
printf 'unicode-内容' > ""$ROOT/b/src/测试_файл.txt""

FIFO=""$ROOT/a/run/in.fifo""; mkfifo ""$FIFO""
( printf 'y\ny\n' > ""$FIFO"" ) &
run_a glide receive --port 0 < ""$FIFO"" > ""$ROOT/a/e.log"" 2>&1 &
RPID=$!
URI=""""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$ROOT/a/e.log"" 2>/dev/null; then URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$ROOT/a/e.log"" | head -n 1)""; break; fi
  sleep 0.25
done
printf 'y\n' | run_b glide send --pair ""$URI"" ""$ROOT/b/src/zero.bin""
wait $RPID

run_a glide daemon --accept-paired-offers --trusted-peer ""$FP_B"" --port 0 --destination ""$ROOT/a/dest"" > ""$ROOT/a/d.log"" 2>&1 &
DPID=$!
trap 'kill $DPID 2>/dev/null || true' EXIT
INV_FILE=""$ROOT/a/run/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do if [ -f ""$INV_FILE"" ]; then break; fi; sleep 0.25; done
D_URI=""$(cat ""$INV_FILE"")""

run_b glide send --pair ""$D_URI"" --trusted-peer ""$FP_A"" ""$ROOT/b/src/zero.bin"" ""$ROOT/b/src/inc.bin"" ""$ROOT/b/src/测试_файл.txt""
for f in zero.bin inc.bin 测试_файл.txt; do
  test ""$(sha256sum ""$ROOT/b/src/$f"" | awk '{print $1}')"" = ""$(sha256sum ""$ROOT/a/dest/$f"" | awk '{print $1}')""
done
kill -TERM ""$DPID""; wait ""$DPID""

# Verify Glide.Linux.UI launches under DISPLAY/WAYLAND_DISPLAY or xvfb-run and single-instance forwards cleanly
RUN_PREFIX=""""
if [ -n ""${DISPLAY:-}"" ] || [ -n ""${WAYLAND_DISPLAY:-}"" ]; then
  RUN_PREFIX=""""
elif command -v xvfb-run >/dev/null 2>&1; then
  RUN_PREFIX=""xvfb-run -a""
else
  echo ""Neither DISPLAY/WAYLAND_DISPLAY nor xvfb-run is available for Glide.Linux.UI."" >&2
  exit 1
fi

run_a $RUN_PREFIX glide-desktop --tab Receive --width 1100 --height 720 > ""$ROOT/a/ui.log"" 2>&1 &
UIPID=$!
trap 'kill -TERM $UIPID 2>/dev/null || true' EXIT
for _ in $(seq 1 40); do
  if ! kill -0 ""$UIPID"" 2>/dev/null; then
    cat ""$ROOT/a/ui.log"" >&2
    exit 1
  fi
  if [ -S ""$ROOT/a/run/glide-desktop.sock"" ]; then
    break
  fi
  sleep 0.25
done
kill -0 ""$UIPID""
test -S ""$ROOT/a/run/glide-desktop.sock""
test -f ""$ROOT/a/run/glide-desktop.sock.lock""

set +e
run_a timeout 15 $RUN_PREFIX glide-desktop --tab Send > ""$ROOT/a/ui2.log"" 2>&1
UI2_RC=$?
set -e
if [ ""$UI2_RC"" -ne 0 ]; then
  echo ""Secondary glide-desktop launch failed with exit code $UI2_RC."" >&2
  test -f ""$ROOT/a/ui2.log"" && cat ""$ROOT/a/ui2.log"" >&2
  test -f ""$ROOT/a/ui.log"" && cat ""$ROOT/a/ui.log"" >&2
  exit ""$UI2_RC""
fi

kill -0 ""$UIPID""
test -S ""$ROOT/a/run/glide-desktop.sock""
kill -TERM ""$UIPID"" 2>/dev/null || true
wait ""$UIPID"" 2>/dev/null || true
echo ""VM_SYSTEMD_AND_UI_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-004", script, timeoutSeconds: 60);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_SYSTEMD_AND_UI_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-004");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T4-005")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Android")]
    public async Task E2E_T4_005_WindowsAndPixel10aEmulator_PhotoShareAndTrustedReconnect()
    {
        TargetEnvironment.Current.RequireCrossPlatformAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T4-005");
        string localPayload = Path.Combine(sandbox.SourceDir, "pixel10a-to-win.bin");
        string expectedSha = CorpusVerifier.WriteIncompressibleFile(localPayload, 64 * 1024, 4005);
        string deviceFile = "/sdcard/Download/pixel10a-to-win.bin";

        await using InteractiveDriverSession winRecv = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "10.0.2.2",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", sandbox.TrustStorePath, "--identity-store", sandbox.IdentityStorePath,
                "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "120"
            ]);
        string v1Uri = (await winRecv.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!;

        try
        {
            await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
            string contentUri = await AndroidUiHelpers.PushAndIndexFileAsync(sandbox, localPayload, deviceFile);
            await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "motion_normal");
            await AndroidUiHelpers.ShareSendAsync(sandbox, contentUri, "application/octet-stream", noRestart: true);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textContains: "Proceed to send (1)");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select recipient");

            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Scan QR code or paste invitation");
            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Paste invitation link without using camera");
            await AndroidUiHelpers.InputTextAsync(sandbox, v1Uri);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Verify & connect");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Connect", timeoutSeconds: 15);

            ProcessRunResult recvExit = await winRecv.WaitForExitAsync(TimeSpan.FromSeconds(90));
            E2ETestHelpers.AssertResultSuccess(recvExit, "WindowsFromAndroidReceiver");

            string delivered = Path.Combine(sandbox.DestDir, "pixel10a-to-win.bin");
            Assert.True(File.Exists(delivered));
            Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(delivered));
            sandbox.AssertStagingClean();

            // Sender completion auto-returns to SelectionScreen; return via Back to HomeScreen
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_completed_card", absent: true);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send", timeoutSeconds: 20);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Back");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Receive files", timeoutSeconds: 10);

            // Receiver enrollment on Android keeps TransferScreen mounted so "Trust device" can be confirmed explicitly
            string hostedDir = sandbox.CreateSubDir("hosted-send");
            string hostedFile = Path.Combine(hostedDir, "win-to-pixel10a.txt");
            CorpusVerifier.WriteUtf8TextFile(hostedFile, "trusted-enrollment-payload-4005");
            await using InteractiveDriverSession hostedSender = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                [
                    "listen-hosted-sender", "--port", "0", "--advertised-ip", "10.0.2.2",
                    "--source-dir", hostedDir,
                    "--trust-store", sandbox.TrustStorePath, "--identity-store", sandbox.IdentityStorePath,
                    "--decision-recipient", "accept", "--decision-enrollment", "accept",
                    "--timeout-seconds", "120"
                ]);
            string hostedUri = (await hostedSender.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!;

            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Receive files");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Scan PC QR code", timeoutSeconds: 15);
            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Paste invitation link without using camera");
            await AndroidUiHelpers.InputTextAsync(sandbox, hostedUri);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Verify & connect");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Connect", timeoutSeconds: 15);

            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Trust this device?", timeoutSeconds: 15);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Trust device");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Accept file", timeoutSeconds: 15);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_completed_card", timeoutSeconds: 20);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer complete");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Done");
            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Close scanner and return");
            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Stop receiving and return to Home");

            ProcessRunResult hostedExit = await hostedSender.WaitForExitAsync(TimeSpan.FromSeconds(30));
            E2ETestHelpers.AssertResultSuccess(hostedExit, "WindowsToAndroidHostedSender");

            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, contentDescContains: "Paired device", timeoutSeconds: 15);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Paired");
        }
        finally
        {
            await AndroidUiHelpers.CleanupAsync(sandbox, deviceFile);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T4-006")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "All")]
    public async Task E2E_T4_006_Pixel10aEmulatorToUbuntu26Vm_ShareSheetBatchTransfer()
    {
        TargetEnvironment.Current.RequireCrossPlatformAllReady();
        await using var sandbox = new E2ESandbox("E2E-T4-006");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string localPayload = Path.Combine(sandbox.SourceDir, "pixel10a-to-vm.bin");
        string expectedSha = CorpusVerifier.WriteIncompressibleFile(localPayload, 64 * 1024, 4006);
        string deviceFile = "/sdcard/Download/pixel10a-to-vm.bin";

        try
        {
            // Start VM receiver advertising 127.0.0.1/VmHost, and bridge QEMU 10.0.2.2:fwdPort -> VmHost:vmPort
            string startVm = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --decision-offer accept --decision-enrollment decline --timeout-seconds 120 > ""$HOME/r.out"" 2>""$HOME/r.err"" < /dev/null &
echo $! > ""$HOME/r.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r.out"" 2>/dev/null; then
    cat ""$HOME/r.out""
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);
            ProcessRunResult vmStart = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-006", startVm, timeoutSeconds: 30);
            Assert.Equal(0, vmStart.ExitCode);
            string rawOut = VmTestHelpers.ExtractOutput(vmStart);
            using var invDoc = JsonDocument.Parse(rawOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries).First(l => l.Contains("invitationUri")));
            int vmPort = invDoc.RootElement.GetProperty("port").GetInt32();
            string vmSpki = invDoc.RootElement.GetProperty("spkiSha256").GetString()!;

            await using var fwd = new VmTestHelpers.TcpPortForwarder(TargetEnvironment.Current.VmHost, vmPort);
            string emuUri = WireClientProbe.BuildV1Uri("10.0.2.2", fwd.BoundPort, vmSpki, name: "Ubuntu26-VM-Receiver", platform: "linux");

            await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
            string contentUri = await AndroidUiHelpers.PushAndIndexFileAsync(sandbox, localPayload, deviceFile);
            await AndroidUiHelpers.ShareSendAsync(sandbox, contentUri, "application/octet-stream");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textContains: "Proceed to send (1)");
            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Scan QR code or paste invitation");
            await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Paste invitation link without using camera");
            await AndroidUiHelpers.InputTextAsync(sandbox, emuUri);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Verify & connect");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Connect", timeoutSeconds: 15);

            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_completed_card", absent: true);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Select files to send", timeoutSeconds: 30);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "pixel10a-to-vm.bin");

            ProcessRunResult vmWait = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-006", @"
set -euo pipefail
RPID=""$(cat ""$HOME/r.pid"")""
for _ in $(seq 1 60); do if ! kill -0 ""$RPID"" 2>/dev/null; then break; fi; sleep 0.25; done
grep -Fq '""success"":true' ""$HOME/r.out""
test ""$(find ""$HOME/staging"" -type f | wc -l)"" -eq 0
");
            Assert.Equal(0, vmWait.ExitCode);
            var (vmSz, vmSha) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t4-006/home/dest/pixel10a-to-vm.bin");
            Assert.Equal(64 * 1024, vmSz);
            Assert.Equal(expectedSha, vmSha);
        }
        finally
        {
            await AndroidUiHelpers.CleanupAsync(sandbox, deviceFile);
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-006");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T4-007")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T4_007_Pixel10aEmulator_AppLifecycleMinimizationAndCustomSafSave()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T4-007");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "settings_storage_uninstall");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Settings");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "4 files");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Download/Glide");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "service_reconciliation_audit");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer history");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Pixel 8 Pro");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "financial_report.pdf");

        string lsOut = await AndroidUiHelpers.RunAsExecAsync(sandbox, "find files/received -type f");
        Assert.False(string.IsNullOrWhiteSpace(lsOut), "Expected reconciled file in app-private files/received/.");
        string firstFile = lsOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)[0].Trim();
        var (sz, sha) = await AndroidUiHelpers.HashDeviceFileAsync(sandbox, $"/data/user/0/dev.glide.app.debug/{firstFile}");
        Assert.True(sz > 0);
        Assert.Equal(64, sha.Length);

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "service_maintenance_audit");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer history");
    }

    [Fact]
    [Trait("Id", "E2E-T4-008")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T4_008_LargeIncompressibleOver4GiB_SustainedTransferAndUiNavigation()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T4-008");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        const int largeMiB = 4608;
        const long expectedBytes = (long)largeMiB * 1024L * 1024L;
        var winDrive = new DriveInfo(Path.GetPathRoot(sandbox.DestDir)!);
        Assert.True(winDrive.AvailableFreeSpace >= 10_000_000_000L,
            $"Insufficient Windows free space for 4.5 GiB staging+dest: {winDrive.AvailableFreeSpace} bytes.");

        ProcessRunResult vmSpace = await E2ETestHelpers.RunVmCommandAsync(
            sandbox, "e2e-t4-008",
            "df -B1 --output=avail /tmp | tail -n 1 | tr -d ' '");
        Assert.Equal(0, vmSpace.ExitCode);
        long vmAvail = long.Parse(VmTestHelpers.ExtractOutput(vmSpace).Trim());
        Assert.True(vmAvail >= 5_500_000_000L,
            $"Insufficient VM /tmp free space for 4.5 GiB source generation: {vmAvail} bytes.");

        string winIp = TargetEnvironment.Current.ResolveWindowsLocalIpForVm();
        string r3Path = Path.Combine(sandbox.LogsDir, "t4-008-r3.json");

        try
        {
            await using InteractiveDriverSession winRecv = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                [
                    "listen-receiver", "--port", "0", "--advertised-ip", winIp,
                    "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                    "--decision-offer", "accept", "--decision-enrollment", "decline",
                    "--r3-report", r3Path, "--timeout-seconds", "600"
                ]);
            string winUri = (await winRecv.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!;

            string vmSendCmd = @"
set -euo pipefail
mkdir -p ""$HOME/vm-4500""
Glide.BackendTestDriver generate-corpus --output-dir ""$HOME/vm-4500"" --small-count 0 --large-mib __MIB__ --no-extra --incompressible > ""$HOME/gen.out""
printf '%s\n' ""__WIN_URI__"" | Glide.BackendTestDriver connect-sender --source-dir ""$HOME/vm-4500"" --decision-enrollment decline --timeout-seconds 600
cat ""$HOME/vm-4500/corpus-manifest.json""
".Replace("__MIB__", largeMiB.ToString()).Replace("__WIN_URI__", winUri);

            ProcessRunResult vmSendRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t4-008", vmSendCmd, timeoutSeconds: 660);
            Assert.Equal(0, vmSendRes.ExitCode);

            ProcessRunResult recvExit = await winRecv.WaitForExitAsync(TimeSpan.FromSeconds(60));
            E2ETestHelpers.AssertResultSuccess(recvExit, "LargeVmToWinReceiver");
            JsonElement resEvt = recvExit.FindEvent("result")!.Value;
            Assert.Equal(expectedBytes, resEvt.GetProperty("verifiedBytes").GetInt64());

            string vmOutText = VmTestHelpers.ExtractOutput(vmSendRes);
            int manifestStart = vmOutText.IndexOf("{\"schema\"", StringComparison.Ordinal);
            Assert.True(manifestStart >= 0, "Expected VM corpus-manifest.json.");
            using var vmManifest = JsonDocument.Parse(vmOutText[manifestStart..]);
            string expectedSha = vmManifest.RootElement.GetProperty("items")[0].GetProperty("sha256").GetString()!;

            string destFile = Path.Combine(sandbox.DestDir, $"99-large-{largeMiB}mib.bin");
            Assert.True(File.Exists(destFile));
            Assert.Equal(expectedBytes, new FileInfo(destFile).Length);
            Assert.Equal(expectedSha, CorpusVerifier.ComputeFileSha256(destFile));
            sandbox.AssertStagingClean();

            Assert.True(File.Exists(r3Path), "Expected R3 diagnostic report for 4.5 GiB transfer.");
            string r3Text = File.ReadAllText(r3Path);
            Assert.DoesNotContain("glide://pair/", r3Text, StringComparison.OrdinalIgnoreCase);
            using var r3Doc = JsonDocument.Parse(r3Text);
            Assert.True(r3Doc.RootElement.ValueKind == JsonValueKind.Object);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-008");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T4-009")]
    [Trait("Tier", "4")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "All")]
    public async Task E2E_T4_009_FullCleanInstallPairTransferUpgradeRestartUninstallRetentionLifecycle()
    {
        TargetEnvironment.Current.RequireCrossPlatformAllReady();
        await using var sandbox = new E2ESandbox("E2E-T4-009");

        // 1. Linux VM .deb lifecycle + Win<->VM transfer pre- and post-upgrade
        string debPath = TargetEnvironment.Current.ResolveLinuxDebPackage();
        string runPath = TargetEnvironment.Current.ResolveLinuxRunInstaller();
        try
        {
            ProcessRunResult debLifecycle = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                [
                    "-Platform", "Linux",
                    "-PackagePath", debPath,
                    "-UpgradePackagePath", debPath,
                    "-SandboxRoot", "/tmp/glide-e2e/e2e-t4-009-deb",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser
                ],
                TimeSpan.FromSeconds(180));
            Assert.True(debLifecycle.ExitCode == 0, $"Linux .deb lifecycle failed: {debLifecycle.Stderr}\n{debLifecycle.Stdout}");

            ProcessRunResult runLifecycle = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                [
                    "-Platform", "Linux",
                    "-PackagePath", runPath,
                    "-UpgradePackagePath", runPath,
                    "-SandboxRoot", "/tmp/glide-e2e/e2e-t4-009-run",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser
                ],
                TimeSpan.FromSeconds(180));
            Assert.True(runLifecycle.ExitCode == 0, $"Linux .run lifecycle failed: {runLifecycle.Stderr}\n{runLifecycle.Stdout}");
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-009-deb");
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t4-009-run");
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "pkg-run");
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "pkg-deb-state");
        }

        // 2. Android APK install -> seed Room/DataStore -> uninstall -k -> reinstall -> SHA-256 & UI retention
        string apkPath = TargetEnvironment.Current.ResolveAndroidDebugApk();
        ProcessRunResult instAnd = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox, "Test-PackageLifecycle.ps1",
            ["-Platform", "Android", "-Action", "Install", "-PackagePath", apkPath, "-AndroidSerial", TargetEnvironment.Current.AndroidSerial],
            TimeSpan.FromSeconds(120));
        Assert.Equal(0, instAnd.ExitCode);

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "theme_settings");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Dark");
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "service_reconciliation_audit");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "financial_report.pdf");

        string lsOut = await AndroidUiHelpers.RunAsExecAsync(sandbox, "find files/received -type f");
        Assert.False(string.IsNullOrWhiteSpace(lsOut));
        string deviceFile = $"/data/user/0/dev.glide.app.debug/{lsOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)[0].Trim()}";
        var (preSz, preSha) = await AndroidUiHelpers.HashDeviceFileAsync(sandbox, deviceFile);

        ProcessRunResult uninstKeep = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox, "Test-PackageLifecycle.ps1",
            ["-Platform", "Android", "-Action", "UninstallKeepData", "-PackagePath", apkPath, "-AndroidSerial", TargetEnvironment.Current.AndroidSerial],
            TimeSpan.FromSeconds(60));
        Assert.Equal(0, uninstKeep.ExitCode);

        ProcessRunResult reinst = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox, "Test-PackageLifecycle.ps1",
            ["-Platform", "Android", "-Action", "ReinstallAndVerifyRetention", "-PackagePath", apkPath, "-AndroidSerial", TargetEnvironment.Current.AndroidSerial],
            TimeSpan.FromSeconds(90));
        Assert.Equal(0, reinst.ExitCode);

        var (postSz, postSha) = await AndroidUiHelpers.HashDeviceFileAsync(sandbox, deviceFile);
        Assert.Equal(preSz, postSz);
        Assert.Equal(preSha, postSha);

        // Launch "history" (NOT service_reconciliation_audit) so retained Room DB is verified without re-seeding
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "history");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Pixel 8 Pro");
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "theme_settings");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Dark");

        // 3. Windows NSIS/ZIP/MSI lifecycle when Windows UI opt-in is active
        if (TargetEnvironment.Current.IsWindowsUiReady)
        {
            string nsisExe = TargetEnvironment.Current.ResolveWindowsNsisInstaller();
            ProcessRunResult winLifecycle = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                [
                    "-Platform", "Windows",
                    "-LiveInstall",
                    "-PackagePath", nsisExe,
                    "-UpgradePackagePath", nsisExe,
                    "-SandboxRoot", sandbox.CreateSubDir("win-t4-009")
                ],
                TimeSpan.FromSeconds(180));
            Assert.True(winLifecycle.ExitCode == 0, $"Windows lifecycle failed: {winLifecycle.Stderr}\n{winLifecycle.Stdout}");
        }
    }
}
