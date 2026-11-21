using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Glide.E2E.Tests.Infrastructure;

namespace Glide.E2E.Tests;

public sealed class Tier2_BoundaryCornerTests
{
    // =========================================================================
    // UF1: Send Workflow & File Staging Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-001")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_001_ZeroByteFile_TransfersWithExactEmptySha256()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-001");
        string zeroFile = Path.Combine(sandbox.SourceDir, "empty-zero.bin");
        string sha = CorpusVerifier.WriteIncompressibleFile(zeroFile, 0, seed: 1);
        Assert.Equal("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-002")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_002_EmptyBatchAndMissingSourceFile_RejectedCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-002");
        using var dummyListener = new RogueTlsListener();
        string validUri = WireClientProbe.BuildV1Uri("127.0.0.1", dummyListener.Port, dummyListener.SpkiSha256);
        string missingFile = Path.Combine(sandbox.SourceDir, "does-not-exist.bin");

        ProcessRunResult cliMissing = await GlideProcessRunner.RunGlideAppAsync(
            sandbox,
            ["send", "--pair", validUri, missingFile]);
        Assert.Equal(1, cliMissing.ExitCode);
        Assert.Contains("File not found", cliMissing.Stdout);

        ProcessRunResult drvEmpty = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "10"],
            stdinLine: validUri);
        Assert.NotEqual(0, drvEmpty.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-003")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_003_Max1000ItemsSucceeds_And1001ItemsRejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-003");
        CorpusManifest manifest1000 = CorpusVerifier.CreateExactItemCountBatch(sandbox.SourceDir, count: 1000, bytesPerItem: 64);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(
            sandbox, sandbox.SourceDir, timeoutSeconds: 240);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender1000");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver1000");

        JsonElement recvRes = receiver.FindEvent("result")!.Value;
        Assert.Equal(1000, recvRes.GetProperty("verifiedCount").GetInt32());
        CorpusVerifier.AssertDestinationMatchesManifest(manifest1000, sandbox.DestDir);
        sandbox.AssertStagingClean();

        ProcessRunResult limitsRes = await GlideProcessRunner.RunBackendDriverAsync(sandbox, ["test-limits"]);
        Assert.Equal(0, limitsRes.ExitCode);
        JsonElement limEvt = limitsRes.FindEvent("limits_result")!.Value;
        Assert.True(limEvt.GetProperty("pass1001ItemsRejected").GetBoolean());
    }

    [Fact]
    [Trait("Id", "E2E-T2-004")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_004_ProtocolSizeCeilings_Over256GiBAndOver64GiBAnd33ItemsPerPage_RejectedOnWire()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-004");

        // Subcase A: OFFER_BEGIN totalBytes > 256 GiB (274877906945)
        await using (InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, 1, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "LimitProbe"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, 2, new JsonObject
                {
                    ["batchId"] = Guid.NewGuid().ToString("D"),
                    ["itemCount"] = 1,
                    ["totalBytes"] = "274877906945",
                    ["pageCount"] = 1
                }), cts.Token);

            ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        // Subcase B: OFFER_PAGE item sizeBytes > 64 GiB (68719476737)
        await using (InteractiveDriverSession listener2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener2.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, 1, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "LimitProbe64G"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            string batchId = Guid.NewGuid().ToString("D");
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["itemCount"] = 1,
                    ["totalBytes"] = "68719476737",
                    ["pageCount"] = 1
                }), cts.Token);

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sid, 3, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["pageIndex"] = 0,
                    ["items"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["itemId"] = Guid.NewGuid().ToString("D"),
                            ["name"] = "huge.bin",
                            ["mime"] = "application/octet-stream",
                            ["sizeBytes"] = "68719476737",
                            ["relativePath"] = "huge.bin"
                        }
                    }
                }), cts.Token);

            ProcessRunResult recvExit = await listener2.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        // Subcase C: 33 items inside a single OFFER_PAGE (> MaxItemsPerOfferPage = 32)
        await using (InteractiveDriverSession listener3 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener3.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, 1, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "LimitProbe33"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            string batchId = Guid.NewGuid().ToString("D");
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["itemCount"] = 33,
                    ["totalBytes"] = "33",
                    ["pageCount"] = 1
                }), cts.Token);

            var items33 = new JsonArray();
            for (int k = 1; k <= 33; k++)
            {
                items33.Add(new JsonObject
                {
                    ["itemId"] = Guid.NewGuid().ToString("D"),
                    ["name"] = $"p{k:D2}.bin",
                    ["mime"] = "application/octet-stream",
                    ["sizeBytes"] = "1",
                    ["relativePath"] = $"p{k:D2}.bin"
                });
            }

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sid, 3, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["pageIndex"] = 0,
                    ["items"] = items33
                }), cts.Token);

            ProcessRunResult recvExit = await listener3.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-005")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF1")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T2_005_Over4GiB_SustainedIncompressibleTransfer_NoSlowdownAndSha256Match()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-005");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        const int largeMiB = 4608; // 4.5 GiB = 4,831,838,208 bytes (> 4 GiB)
        const long expectedBytes = (long)largeMiB * 1024L * 1024L;
        var winDrive = new DriveInfo(Path.GetPathRoot(sandbox.SourceDir)!);
        Assert.True(winDrive.AvailableFreeSpace >= 5_500_000_000L,
            $"Insufficient Windows disk space for 4.5 GiB test: {winDrive.AvailableFreeSpace} bytes.");

        ProcessRunResult vmSpace = await E2ETestHelpers.RunVmCommandAsync(
            sandbox, "e2e-t2-005",
            "df -B1 --output=avail /tmp | tail -n 1 | tr -d ' '");
        Assert.Equal(0, vmSpace.ExitCode);
        long vmAvail = long.Parse(VmTestHelpers.ExtractOutput(vmSpace).Trim());
        Assert.True(vmAvail >= 10_000_000_000L,
            $"Insufficient VM /tmp free space for 4.5 GiB staging + commit: {vmAvail} bytes.");

        string largeDir = sandbox.CreateSubDir("large-4500");
        try
        {
            ProcessRunResult genRes = await GlideProcessRunner.RunBackendDriverAsync(
                sandbox,
                ["generate-corpus", "--output-dir", largeDir, "--small-count", "0", "--large-mib", largeMiB.ToString(), "--no-extra"],
                timeout: TimeSpan.FromSeconds(300));
            Assert.Equal(0, genRes.ExitCode);
            string manifestPath = Path.Combine(largeDir, "corpus-manifest.json");
            using var manifestDoc = JsonDocument.Parse(File.ReadAllText(manifestPath));
            JsonElement item = manifestDoc.RootElement.GetProperty("items")[0];
            string expectedSha = item.GetProperty("sha256").GetString()!;
            Assert.Equal(expectedBytes, item.GetProperty("sizeBytes").GetInt64());

            string startVmRecv = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --decision-offer accept --decision-enrollment decline --timeout-seconds 600 > ""$HOME/recv.out"" 2>""$HOME/recv.err"" < /dev/null &
echo $! > ""$HOME/recv.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/recv.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/recv.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
cat ""$HOME/recv.err"" >&2
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);

            ProcessRunResult startRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-005", startVmRecv, timeoutSeconds: 30);
            Assert.Equal(0, startRes.ExitCode);
            string uri = VmTestHelpers.ExtractOutput(startRes).Trim();

            ProcessRunResult sendRes = await GlideProcessRunner.RunBackendDriverAsync(
                sandbox,
                ["connect-sender", "--source-dir", largeDir, "--decision-enrollment", "decline", "--timeout-seconds", "600"],
                stdinLine: uri,
                timeout: TimeSpan.FromSeconds(620));
            E2ETestHelpers.AssertResultSuccess(sendRes, "LargeWinToVmSender");

            ProcessRunResult waitRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-005", @"
set -euo pipefail
RPID=""$(cat ""$HOME/recv.pid"")""
for _ in $(seq 1 120); do
  if ! kill -0 ""$RPID"" 2>/dev/null; then break; fi
  sleep 0.5
done
grep -Fq '""success"":true' ""$HOME/recv.out""
test ""$(find ""$HOME/staging"" -type f | wc -l)"" -eq 0
", timeoutSeconds: 90);
            Assert.Equal(0, waitRes.ExitCode);

            var (vmSize, vmSha) = await VmTestHelpers.HashFileAsync(
                sandbox, $"/tmp/glide-e2e/e2e-t2-005/home/dest/99-large-{largeMiB}mib.bin");
            Assert.Equal(expectedBytes, vmSize);
            Assert.Equal(expectedSha, vmSha);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-005");
            try { if (Directory.Exists(largeDir)) Directory.Delete(largeDir, true); } catch { }
        }
    }

    // =========================================================================
    // UF2: Receive Workflow & Storage Safety Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-006")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_006_PathTraversalAttacks_DotsSlashWinUncDrive_AllRejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-006");

        foreach (string maliciousRel in new[] { "../../evil.txt", @"..\..\evil.txt", @"\\server\share\pwn.txt", @"C:\Windows\System32\pwn.txt", "/etc/passwd" })
        {
            await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]);
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, 1, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "TraversalProbe"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            string batchId = Guid.NewGuid().ToString("D");
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId, ["itemCount"] = 1, ["totalBytes"] = "16", ["pageCount"] = 1
                }), cts.Token);
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sid, 3, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["pageIndex"] = 0,
                    ["items"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["itemId"] = Guid.NewGuid().ToString("D"),
                            ["name"] = "pwn.txt",
                            ["mime"] = "text/plain",
                            ["sizeBytes"] = "16",
                            ["relativePath"] = maliciousRel
                        }
                    }
                }), cts.Token);

            ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-007")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_007_DosReservedNamesAndTrailingDotSpace_Rejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-007");

        ProcessRunResult safetyRes = await GlideProcessRunner.RunBackendDriverAsync(sandbox, ["test-storage-safety"]);
        Assert.Equal(0, safetyRes.ExitCode);
        Assert.True(safetyRes.FindEvent("storage_safety_result")!.Value.GetProperty("dosBlocked").GetBoolean());

        foreach (string badName in new[] { "CON", "CON.txt", "PRN", "NUL.txt", "LPT1", "trailing.", "trailing " })
        {
            await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]);
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, 1, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "DosProbe"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            string batchId = Guid.NewGuid().ToString("D");
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId, ["itemCount"] = 1, ["totalBytes"] = "16", ["pageCount"] = 1
                }), cts.Token);
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sid, 3, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["pageIndex"] = 0,
                    ["items"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["itemId"] = Guid.NewGuid().ToString("D"),
                            ["name"] = badName,
                            ["mime"] = "text/plain",
                            ["sizeBytes"] = "16",
                            ["relativePath"] = badName
                        }
                    }
                }), cts.Token);

            ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-008")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_008_FilenameCollision_SuffixesWithoutOverwritingExistingFiles()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-008");
        string existing0Sha = CorpusVerifier.WriteUtf8TextFile(Path.Combine(sandbox.DestDir, "report.txt"), "existing-v0");
        string existing1Sha = CorpusVerifier.WriteUtf8TextFile(Path.Combine(sandbox.DestDir, "report (1).txt"), "existing-v1");
        string incomingSha = CorpusVerifier.WriteUtf8TextFile(Path.Combine(sandbox.SourceDir, "report.txt"), "incoming-v2-payload");

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        Assert.Equal(existing0Sha, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "report.txt")));
        Assert.Equal(existing1Sha, CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.DestDir, "report (1).txt")));
        string collidedPath = Path.Combine(sandbox.DestDir, "report (2).txt");
        Assert.True(File.Exists(collidedPath), "Expected collision file 'report (2).txt' to be created.");
        Assert.Equal(incomingSha, CorpusVerifier.ComputeFileSha256(collidedPath));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-009")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_009_ReparsePointJunctionInDestination_FailsClosedWithoutEscapingSandbox()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-009");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "escape.bin"), 1024, 2009);
        string outsideTarget = sandbox.CreateSubDir("outside-target");
        string junctionDest = Path.Combine(sandbox.RootDir, "junction-dest");

        if (OperatingSystem.IsWindows())
        {
            using var mklink = Process.Start(new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{junctionDest}\" \"{outsideTarget}\"")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            })!;
            await mklink.WaitForExitAsync();
            Assert.Equal(0, mklink.ExitCode);
        }
        else
        {
            Directory.CreateSymbolicLink(junctionDest, outsideTarget);
        }

        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", junctionDest,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "15"
            ]);
        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        string uri = invEvt.GetProperty("invitationUri").GetString()!;

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "15"],
            stdinLine: uri);
        ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(15));

        Assert.NotEqual(0, recvExit.ExitCode);
        CorpusVerifier.AssertDestinationEmpty(outsideTarget);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-010")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF2")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_010_Utf8BomAndZeroByteResumeMetadata_RejectedOnWire()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-010");

        // Subcase A: UTF-8 BOM in control payload
        await using (InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);

            byte[] bomPayload = [0xEF, 0xBB, 0xBF, (byte)'{', (byte)'}'];
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control, bomPayload, cts.Token);

            ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        // Subcase B: Zero-byte item carrying resume metadata (rootHash + chunkSize)
        await using (InteractiveDriverSession listener2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener2.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, 1, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "ResumeMetaProbe"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            string batchId = Guid.NewGuid().ToString("D");
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId, ["itemCount"] = 1, ["totalBytes"] = "0", ["pageCount"] = 1
                }), cts.Token);
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sid, 3, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["pageIndex"] = 0,
                    ["items"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["itemId"] = Guid.NewGuid().ToString("D"),
                            ["name"] = "zero.bin",
                            ["mime"] = "application/octet-stream",
                            ["sizeBytes"] = "0",
                            ["rootHash"] = new string('a', 64),
                            ["chunkSize"] = 65536
                        }
                    }
                }), cts.Token);

            ProcessRunResult recvExit = await listener2.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        sandbox.AssertStagingClean();
    }

    // =========================================================================
    // UF3: Device Pairing Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-011")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "Loopback")]
    public void E2E_T2_011_V2UriTtlAndFixtureInvalidCases_AllRejected()
    {
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        JsonElement invalidCases = doc.RootElement.GetProperty("v2PairOnlyInvitation").GetProperty("invalidCases");

        foreach (JsonElement ic in invalidCases.EnumerateArray())
        {
            string badUri = ic.GetProperty("uri").GetString()!;
            Assert.ThrowsAny<Exception>(() => WireClientProbe.DecodeV2Uri(badUri));
        }

        string spki = new string('a', 64);
        string ttl0 = WireClientProbe.BuildV2Uri("127.0.0.1", 42424, spki, ttlSeconds: 0);
        string ttl121 = WireClientProbe.BuildV2Uri("127.0.0.1", 42424, spki, ttlSeconds: 121);
        string webPlat = WireClientProbe.BuildV2Uri("127.0.0.1", 42424, spki, platform: "web");

        Assert.ThrowsAny<Exception>(() => WireClientProbe.DecodeV2Uri(ttl0));
        Assert.ThrowsAny<Exception>(() => WireClientProbe.DecodeV2Uri(ttl121));
        Assert.ThrowsAny<Exception>(() => WireClientProbe.DecodeV2Uri(webPlat));
    }

    [Fact]
    [Trait("Id", "E2E-T2-012")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_012_V2UriPassedToV1SendCommand_ReturnsUpdateGlideMessage()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-012");
        string dummyFile = Path.Combine(sandbox.SourceDir, "sample.txt");
        CorpusVerifier.WriteUtf8TextFile(dummyFile, "v2-test");
        string v2Uri = WireClientProbe.BuildV2Uri("127.0.0.1", 42424, new string('b', 64));

        ProcessRunResult cliRes = await GlideProcessRunner.RunGlideAppAsync(
            sandbox,
            ["send", "--pair", v2Uri, dummyFile]);
        Assert.Equal(1, cliRes.ExitCode);
        Assert.Contains("Update Glide to pair this device", cliRes.Stdout);
    }

    [Fact]
    [Trait("Id", "E2E-T2-013")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "Loopback")]
    public void E2E_T2_013_Profile2FixtureRejectedPurposes_ValidateProtocolOrderRules()
    {
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        string[] rejectedPurposes = doc.RootElement
            .GetProperty("sessionDispatch")
            .GetProperty("rejectedPurposes")
            .EnumerateArray()
            .Select(e => e.GetString()!)
            .ToArray();

        Assert.Contains("FILE_TRANSFER", rejectedPurposes);
        Assert.Contains("pair_only", rejectedPurposes);
        Assert.Contains("", rejectedPurposes);
        Assert.Contains("UNKNOWN", rejectedPurposes);
    }

    [Fact]
    [Trait("Id", "E2E-T2-014")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_014_LinuxVmProfile2PinLockout_ExpiresOn3rdAttempt()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-014");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        // Invoke real Profile 2 PAIR_ONLY responder on VM (Glide.App pair --ready or Glide.BackendTestDriver listen-pair-only);
        // fails honestly until M1/M2 exposes Profile 2 CLI/driver endpoint (never stubs with glide identity show).
        string script = @"
set -euo pipefail
mkdir -p ""$HOME/pair2""
glide pair --ready --port 0 > ""$HOME/pair2/ready.log"" 2>&1 &
PPID_PID=$!
trap 'kill $PPID_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 20); do
  if grep -qE 'glide://pair/v2#[A-Za-z0-9_-]+' ""$HOME/pair2/ready.log"" 2>/dev/null; then
    grep -oE 'glide://pair/v2#[A-Za-z0-9_-]+' ""$HOME/pair2/ready.log"" | head -n 1
    exit 0
  fi
  if ! kill -0 $PPID_PID 2>/dev/null; then
    cat ""$HOME/pair2/ready.log"" >&2
    exit 1
  fi
  sleep 0.25
done
cat ""$HOME/pair2/ready.log"" >&2
exit 1
";
        try
        {
            ProcessRunResult startRes = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-014", script, timeoutSeconds: 20);
            Assert.True(startRes.ExitCode == 0,
                $"Linux VM Profile 2 PAIR_ONLY responder unavailable: {VmTestHelpers.ExtractOutput(startRes)}");
            string v2Raw = VmTestHelpers.ExtractOutput(startRes).Trim();
            WireV2Invitation v2 = WireClientProbe.DecodeV2Uri(v2Raw);

            using var peer = new IdentityV1Peer("LinuxPinLockoutProbe");
            Gld2PairOutcome r1 = await Gld2Probe.RunSpake2PinAttemptAsync(TargetEnvironment.Current.VmHost, v2.Endpoints[0].Port, "000000", peer);
            Assert.False(r1.Paired);
            Assert.Equal("AUTH_FAILED", r1.ErrorCode);
            Gld2PairOutcome r2 = await Gld2Probe.RunSpake2PinAttemptAsync(TargetEnvironment.Current.VmHost, v2.Endpoints[0].Port, "000000", peer);
            Assert.False(r2.Paired);
            Assert.Equal("AUTH_FAILED", r2.ErrorCode);
            Gld2PairOutcome r3 = await Gld2Probe.RunSpake2PinAttemptAsync(TargetEnvironment.Current.VmHost, v2.Endpoints[0].Port, "000000", peer);
            Assert.False(r3.Paired);
            Assert.Equal("EXPIRED", r3.ErrorCode);
            Gld2PairOutcome r4 = await Gld2Probe.RunSpake2PinAttemptAsync(TargetEnvironment.Current.VmHost, v2.Endpoints[0].Port, "000000", peer);
            Assert.False(r4.Paired);
            Assert.Equal("EXPIRED", r4.ErrorCode);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-014");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-015")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF3")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T2_015_WindowsLeavingPairDevicesPage_CancelsReadyWindow()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T2-015");
        await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox);

        await ui.SwitchTabAsync("NavPairDevices");
        await ui.ClickByNameAsync("Show this PC's pairing QR and code");
        await ui.WaitForNameAsync("Six-digit pairing code");
        await ui.ClickByNameAsync("Copy link");
        WireV2Invitation v2 = WireClientProbe.DecodeV2Uri(await ui.ReadClipboardAsync());

        // Leaving Pair devices cancels the active Ready window immediately
        await ui.SwitchTabAsync("NavSend");

        using var peer = new IdentityV1Peer("LeavePageProbe");
        bool rejected = false;
        try
        {
            Gld2PairOutcome res = await Gld2Probe.RunQrV2PairAsync(v2, peer);
            rejected = !res.Paired;
        }
        catch
        {
            rejected = true;
        }
        Assert.True(rejected, "Expected Ready window to be cancelled upon leaving Pair devices page.");

        await ui.SwitchTabAsync("NavPairDevices");
        await ui.AssertAbsentByNameAsync("Six-digit pairing code");
    }

    // =========================================================================
    // UF4: Quick Share QR & Link Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-016")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_016_MalformedV1Uris_PaddingAndOversizeAndUnknownPrefix_RejectedByCli()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-016");
        string dummyFile = Path.Combine(sandbox.SourceDir, "uri-test.txt");
        CorpusVerifier.WriteUtf8TextFile(dummyFile, "uri");

        string validUri = WireClientProbe.BuildV1Uri("127.0.0.1", 42424, new string('c', 64));
        string paddedUri = validUri + "=";
        string unknownVersionUri = "glide://pair/v9#abcd";
        string oversizeUri = "glide://pair/v1#" + new string('A', 2800);

        foreach (string bad in new[] { paddedUri, unknownVersionUri, oversizeUri })
        {
            ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(sandbox, ["send", "--pair", bad, dummyFile]);
            Assert.Equal(1, res.ExitCode);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-017")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_017_ExpiredV1Invitation_RejectedByCliSend()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-017");
        string dummyFile = Path.Combine(sandbox.SourceDir, "expired.txt");
        CorpusVerifier.WriteUtf8TextFile(dummyFile, "expired");
        string expiredUri = WireClientProbe.BuildV1Uri(
            "127.0.0.1", 42424, new string('d', 64),
            expiresAtEpoch: DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 600,
            ttlSeconds: 120);

        ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(sandbox, ["send", "--pair", expiredUri, dummyFile]);
        Assert.Equal(1, res.ExitCode);
        Assert.Contains("expired", res.Stdout, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    [Trait("Id", "E2E-T2-018")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_018_OneUseInvitationCannotBeReused_AndWrongBootstrapRoleRejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-018");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "once.bin"), 1024, 2018);

        await using (InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), role: "receiver", cts.Token);
            Assert.Equal("rejected", bootRes["status"]!.GetValue<string>());
            Assert.Equal("UNSUPPORTED_PROFILE", bootRes["code"]!.GetValue<string>());
            await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
        }

        await using InteractiveDriverSession listener2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "20"]);
        JsonElement invEvt2 = await listener2.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
        string uri2 = invEvt2.GetProperty("invitationUri").GetString()!;

        ProcessRunResult send1 = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "15"], stdinLine: uri2);
        ProcessRunResult recv2 = await listener2.WaitForExitAsync(TimeSpan.FromSeconds(15));
        E2ETestHelpers.AssertResultSuccess(send1, "FirstSend");
        E2ETestHelpers.AssertResultSuccess(recv2, "FirstRecv");

        ProcessRunResult replaySend = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "5"], stdinLine: uri2);
        Assert.NotEqual(0, replaySend.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-019")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_019_WrongBootstrapTokenAndAlteredInvitationHash_Rejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-019");

        await using (InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "30"]))
        {
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(20));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            WireV1Invitation badTokenInv = inv with { Token = new string('B', 43) };
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, badTokenInv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            Assert.Equal("rejected", bootRes["status"]!.GetValue<string>());
            Assert.Equal("AUTHENTICATION_FAILED", bootRes["code"]!.GetValue<string>());
            Assert.NotEqual(0, (await listener.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }

        await using (InteractiveDriverSession listener2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "30"]))
        {
            JsonElement invEvt = await listener2.WaitForInvitationAsync(TimeSpan.FromSeconds(20));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            WireV1Invitation badHashInv = inv with { InvitationHash = new string('0', 64) };
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, badHashInv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            Assert.Equal("rejected", bootRes["status"]!.GetValue<string>());
            Assert.Equal("AUTHENTICATION_FAILED", bootRes["code"]!.GetValue<string>());
            Assert.NotEqual(0, (await listener2.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }

        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-020")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF4")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_020_LanEndpointInvalidLiterals_LeadingZeroZoneIdHostname_Rejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-020");
        string dummyFile = Path.Combine(sandbox.SourceDir, "ep.txt");
        CorpusVerifier.WriteUtf8TextFile(dummyFile, "ep");

        Assert.False(WireClientProbe.IsValidLanIpLiteral("192.168.01.1"));
        Assert.False(WireClientProbe.IsValidLanIpLiteral("fe80::1%eth0"));
        Assert.False(WireClientProbe.IsValidLanIpLiteral("localhost"));

        foreach (string badHost in new[] { "192.168.01.1", "fe80::1%eth0", "localhost" })
        {
            string badUri = WireClientProbe.BuildV1Uri(badHost, 42424, new string('e', 64));
            ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(sandbox, ["send", "--pair", badUri, dummyFile]);
            Assert.Equal(1, res.ExitCode);
        }
    }

    // =========================================================================
    // UF5: Trusted Reconnect & Discovery Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-021")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_021_TlsSpkiPinMismatch_FailsClosedOnClientAndServer()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-021");
        var (recv, atk) = await E2ETestHelpers.RunAdversarialAttackAsync(sandbox, "wrong-pin");
        Assert.Equal(0, atk.ExitCode);
        Assert.True(atk.FindEvent("result")!.Value.GetProperty("rejected").GetBoolean());
        Assert.NotEqual(0, recv.ExitCode);

        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "pin.bin"), 512, 2021);
        using var rogue = new RogueTlsListener();
        string wrongPinUri = WireClientProbe.BuildV1Uri("127.0.0.1", rogue.Port, new string('f', 64));
        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "10"], stdinLine: wrongPinUri);
        Assert.NotEqual(0, sender.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-022")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_022_CapabilityDowngrade_MissingStreamOrIdentityV1_Rejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-022");
        var (recv, atk) = await E2ETestHelpers.RunAdversarialAttackAsync(sandbox, "incompatible-caps");
        Assert.Equal(0, atk.ExitCode);
        Assert.True(atk.FindEvent("result")!.Value.GetProperty("rejected").GetBoolean());
        Assert.NotEqual(0, recv.ExitCode);

        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "downgrade.bin"), 512, 2022);
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
                    ["capabilities"] = new JsonArray("stream-v1", "sha256"), // OMITS identity-v1
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "DowngradeServer",
                    ["limits"] = new JsonObject
                    {
                        ["maxItems"] = 1000,
                        ["maxItemBytes"] = "68719476736",
                        ["maxBatchBytes"] = "274877906944",
                        ["maxControlBytes"] = 16384
                    }
                }), ct);
        }, ct);

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            [
                "connect-sender", "--source-dir", sandbox.SourceDir,
                "--trust-store", sandbox.TrustStorePath,
                "--identity-store", sandbox.IdentityStorePath,
                "--expected-peer", new string('a', 64),
                "--timeout-seconds", "10"
            ],
            stdinLine: uri);
        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, sender);
        Assert.NotEqual(0, sender.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-023")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_023_IdentitySubstitutionAndExpectedPeerMismatch_FailsClosed()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-023");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peerA = new IdentityV1Peer("ExpectedPeerA");
        using var impostorB = new IdentityV1Peer("ImpostorPeerB");
        byte[] payload = Encoding.UTF8.GetBytes("id-substitution-payload");

        // 1. Enroll PeerA legitimately
        await using (InteractiveDriverSession recv1 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "25"
            ]))
        {
            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri((await recv1.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome out1 = await peerA.SendFilesProfile1Async(inv1, [("legit.bin", payload)]);
            Assert.True(out1.Succeeded, out1.Message);
            E2ETestHelpers.AssertResultSuccess(await recv1.WaitForExitAsync(TimeSpan.FromSeconds(15)), "EnrollRecv");
        }

        // 2. ImpostorB connects while receiver expects PeerA -> must fail closed and leave DestDir without impostor file
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
            IdentityTransferOutcome outSub = await impostorB.SendFilesProfile1Async(inv2, [("impostor.bin", payload)]);
            Assert.False(outSub.Succeeded);
            Assert.NotEqual(0, (await recv2.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }

        Assert.False(File.Exists(Path.Combine(sandbox.DestDir, "impostor.bin")));
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-024")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_024_DuplicateDisplayNamesAndForbiddenMdnsKeys_SchemaValidation()
    {
        // 1. Golden fixture TXT forbidden key check
        string fixturePath = TargetEnvironment.Current.ResolveProfile2FixtureFile();
        using var doc = JsonDocument.Parse(File.ReadAllText(fixturePath));
        JsonElement txt = doc.RootElement.GetProperty("dnsSdTxtRecords");
        string[] forbidden = txt.GetProperty("forbiddenKeys").EnumerateArray().Select(e => e.GetString()!).ToArray();

        Assert.Equal(["spki", "pin", "token", "trust", "fp"], forbidden);
        JsonElement ready = txt.GetProperty("readyToPairDesktop");
        foreach (string key in forbidden)
        {
            Assert.False(ready.TryGetProperty(key, out _), $"Forbidden TXT key '{key}' must not appear.");
        }

        // 2. Real multi-peer trust persistence with identical DisplayName ("Office Desktop") across restarts
        await using var sandbox = new E2ESandbox("E2E-T2-024");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peer1 = new IdentityV1Peer("Office Desktop");
        using var peer2 = new IdentityV1Peer("Office Desktop");
        byte[] payload = Encoding.UTF8.GetBytes("dup-display-name-payload");

        foreach (var (peer, fname) in new[] { (peer1, "office1.bin"), (peer2, "office2.bin") })
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
            IdentityTransferOutcome outcome = await peer.SendFilesProfile1Async(inv, [(fname, payload)]);
            Assert.True(outcome.Succeeded, outcome.Message);
            E2ETestHelpers.AssertResultSuccess(await recv.WaitForExitAsync(TimeSpan.FromSeconds(15)), $"Recv-{fname}");
        }

        string trustJson = File.ReadAllText(recvTrust);
        Assert.DoesNotContain("glide://pair/", trustJson, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("\"token\"", trustJson, StringComparison.OrdinalIgnoreCase);
        using var trustDoc = JsonDocument.Parse(trustJson);
        JsonElement[] peers = trustDoc.RootElement.GetProperty("peers").EnumerateArray().ToArray();
        Assert.Equal(2, peers.Length);
        Assert.Contains(peers, p => p.GetProperty("FingerprintHex").GetString() == peer1.FingerprintHex && p.GetProperty("DisplayName").GetString() == "Office Desktop");
        Assert.Contains(peers, p => p.GetProperty("FingerprintHex").GetString() == peer2.FingerprintHex && p.GetProperty("DisplayName").GetString() == "Office Desktop");

        // Reconnect with wrong expected fingerprint must fail closed after restart
        await using InteractiveDriverSession recvCheck = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peer1.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "15"
            ]);
        WireV1Invitation invCheck = WireClientProbe.DecodeV1Uri((await recvCheck.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
        IdentityTransferOutcome mismatch = await peer2.SendFilesProfile1Async(invCheck, [("mismatch.bin", payload)]);
        Assert.False(mismatch.Succeeded);
        Assert.NotEqual(0, (await recvCheck.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-025")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF5")]
    [Trait("Target", "CrossPlatform")]
    [Trait("CrossTarget", "Vm")]
    public async Task E2E_T2_025_NetworkInterfaceChange_RevokesStaleInvitationsAndRecovers()
    {
        TargetEnvironment.Current.RequireCrossPlatformVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-025");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string payloadPath = Path.Combine(sandbox.SourceDir, "recovered-net.bin");
        string expectedSha = CorpusVerifier.WriteIncompressibleFile(payloadPath, 8192, 2025);

        try
        {
            // 1. Start VM listener #1, get URI_1, then kill listener #1 (simulating interface reset / endpoint change)
            string start1 = @"
set -euo pipefail
mkdir -p ""$HOME/staging"" ""$HOME/dest""
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --decision-offer accept --decision-enrollment decline --timeout-seconds 20 > ""$HOME/r1.out"" 2>""$HOME/r1.err"" < /dev/null &
RPID=$!
echo $RPID > ""$HOME/r1.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r1.out"" 2>/dev/null; then
    URI=""$(grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/r1.out"" | head -n 1)""
    kill $RPID 2>/dev/null || true
    wait $RPID 2>/dev/null || true
    echo ""$URI""
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);

            ProcessRunResult r1Res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-025", start1, timeoutSeconds: 25);
            Assert.Equal(0, r1Res.ExitCode);
            string staleUri = VmTestHelpers.ExtractOutput(r1Res).Trim();

            // Stale URI_1 must fail closed without hanging
            ProcessRunResult staleSend = await GlideProcessRunner.RunBackendDriverAsync(
                sandbox,
                ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "10"],
                stdinLine: staleUri,
                timeout: TimeSpan.FromSeconds(20));
            Assert.NotEqual(0, staleSend.ExitCode);
            Assert.False(staleSend.TimedOut);

            // 2. Restart VM listener on fresh port -> URI_2 succeeds
            string start2 = @"
set -euo pipefail
nohup Glide.BackendTestDriver listen-receiver --port 0 --advertised-ip ""__VM_HOST__"" --staging-dir ""$HOME/staging"" --dest-dir ""$HOME/dest"" --decision-offer accept --decision-enrollment decline --timeout-seconds 40 > ""$HOME/r2.out"" 2>""$HOME/r2.err"" < /dev/null &
echo $! > ""$HOME/r2.pid""
for _ in $(seq 1 40); do
  if grep -q 'glide://pair/v1#' ""$HOME/r2.out"" 2>/dev/null; then
    grep -oE 'glide://pair/v1#[A-Za-z0-9_-]+' ""$HOME/r2.out"" | head -n 1
    exit 0
  fi
  sleep 0.25
done
exit 1
".Replace("__VM_HOST__", TargetEnvironment.Current.VmHost);

            ProcessRunResult r2Res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-025", start2, timeoutSeconds: 25);
            Assert.Equal(0, r2Res.ExitCode);
            string freshUri = VmTestHelpers.ExtractOutput(r2Res).Trim();
            Assert.NotEqual(WireClientProbe.DecodeV1Uri(staleUri).Endpoints[0].Port, WireClientProbe.DecodeV1Uri(freshUri).Endpoints[0].Port);

            ProcessRunResult okSend = await GlideProcessRunner.RunBackendDriverAsync(
                sandbox,
                ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "30"],
                stdinLine: freshUri);
            E2ETestHelpers.AssertResultSuccess(okSend, "RecoveredSender");

            var (vmSize, vmSha) = await VmTestHelpers.HashFileAsync(sandbox, "/tmp/glide-e2e/e2e-t2-025/home/dest/recovered-net.bin");
            Assert.Equal(8192, vmSize);
            Assert.Equal(expectedSha, vmSha);
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-025");
        }
    }

    // =========================================================================
    // UF6: Explicit Consent & Trust Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-026")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_026_ReceiverDeclinesOffer_CommitsZeroBytesAndCleansStaging()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-026");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "declined.bin"), 4096, 2026);

        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir, acceptOffer: false);
        Assert.NotEqual(0, receiver.ExitCode);
        Assert.NotEqual(0, sender.ExitCode);
        Assert.True(receiver.FindEvent("result")!.Value.GetProperty("rejected").GetBoolean());
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-027")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_027_HostedSenderDeclinesRecipient_AbortsCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-027");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "hosted-decline.bin"), 4096, 2027);

        var (sender, receiver) = await E2ETestHelpers.RunHostedSenderTransferAsync(
            sandbox, sandbox.SourceDir, acceptRecipient: false, acceptOffer: true);
        Assert.NotEqual(0, sender.ExitCode);
        Assert.NotEqual(0, receiver.ExitCode);
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-028")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_028_RogueReceiverRejectsOfferWithUserRejected_SenderExitsCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-028");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "rej.bin"), 1024, 2028);
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
                    ["displayName"] = "RejectingPeer",
                    ["limits"] = new JsonObject { ["maxItems"] = 1000, ["maxItemBytes"] = "68719476736", ["maxBatchBytes"] = "274877906944", ["maxControlBytes"] = 16384 }
                }), ct);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_BEGIN
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_PAGE
            JsonObject offerEnd = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_END
            string batchId = offerEnd["body"]!["batchId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_DECISION", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["accepted"] = false,
                    ["code"] = "USER_REJECTED"
                }), ct);
        }, ct);

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "30"], stdinLine: uri);
        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, sender);
        Assert.NotEqual(0, sender.ExitCode);
        Assert.True(sender.FindEvent("result")!.Value.GetProperty("rejected").GetBoolean());
    }

    [Fact]
    [Trait("Id", "E2E-T2-029")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_029_ExpectedPeerMismatchAfterTrustRotation_RejectsConnection()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-029");
        string recvTrust = Path.Combine(sandbox.ConfigDir, "recv-trust.json");
        using var peerOld = new IdentityV1Peer("Peer-Old");
        using var peerRotated = new IdentityV1Peer("Peer-Rotated");
        byte[] payload = Encoding.UTF8.GetBytes("rotation-mismatch-payload");

        // Step 1: Enroll Peer-Old
        await using (InteractiveDriverSession recv1 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "25"
            ]))
        {
            WireV1Invitation inv1 = WireClientProbe.DecodeV1Uri((await recv1.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            Assert.True((await peerOld.SendFilesProfile1Async(inv1, [("t2-029-a.bin", payload)])).Succeeded);
            E2ETestHelpers.AssertResultSuccess(await recv1.WaitForExitAsync(TimeSpan.FromSeconds(15)), "EnrollOld");
        }

        // Step 2: Rotated key against --expected-peer Peer-Old under --decision-enrollment decline -> MUST reject
        await using (InteractiveDriverSession recv2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--expected-peer", peerOld.FingerprintHex,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "20"
            ]))
        {
            WireV1Invitation inv2 = WireClientProbe.DecodeV1Uri((await recv2.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            IdentityTransferOutcome rotOut = await peerRotated.SendFilesProfile1Async(inv2, [("t2-029-bad.bin", payload)]);
            Assert.False(rotOut.Succeeded);
            Assert.NotEqual(0, (await recv2.WaitForExitAsync(TimeSpan.FromSeconds(15))).ExitCode);
        }
        Assert.False(File.Exists(Path.Combine(sandbox.DestDir, "t2-029-bad.bin")));

        // Step 3: Re-enroll Peer-Rotated with explicit accept -> succeeds
        await using (InteractiveDriverSession recv3 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir,
                "--trust-store", recvTrust, "--decision-offer", "accept", "--decision-enrollment", "accept",
                "--timeout-seconds", "25"
            ]))
        {
            WireV1Invitation inv3 = WireClientProbe.DecodeV1Uri((await recv3.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!);
            Assert.True((await peerRotated.SendFilesProfile1Async(inv3, [("t2-029-new.bin", payload)])).Succeeded);
            E2ETestHelpers.AssertResultSuccess(await recv3.WaitForExitAsync(TimeSpan.FromSeconds(15)), "ReEnrollNew");
        }
        Assert.Contains(peerRotated.FingerprintHex, File.ReadAllText(recvTrust), StringComparison.Ordinal);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-030")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF6")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_030_LinuxVmDaemon_RejectsPairOnlyAndUnenrolledPeer()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-030");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ZERO_FP=""0000000000000000000000000000000000000000000000000000000000000000""

set +e
OUT_A=""$(glide daemon --accept-paired-offers --trusted-peer ""$ZERO_FP"" 2>&1)""
RC_A=$?
OUT_B=""$(glide daemon --trusted-peer ""$ZERO_FP"" 2>&1)""
RC_B=$?
set -e
test ""$RC_A"" -eq 1
echo ""$OUT_A"" | grep -Fq -- ""--trusted-peer is not an enrolled persistent identity""
test ""$RC_B"" -eq 1
echo ""$OUT_B"" | grep -Fq -- ""--accept-paired-offers""

# Enroll peerB into peerA, start daemon for peerB, verify unenrolled peerC is rejected
ROOT=""$HOME/daemon-t2-030""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/dest""
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
printf 'unauth' > ""$ROOT/c/unauth.txt""

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

run_a glide daemon --accept-paired-offers --trusted-peer ""$FP_B"" --port 0 --destination ""$ROOT/a/dest"" > ""$ROOT/a/daemon.log"" 2>&1 &
DPID=$!
trap 'kill $DPID 2>/dev/null || true' EXIT

INV_FILE=""$ROOT/a/run/glide/daemon-invitation.txt""
for _ in $(seq 1 40); do
  if [ -f ""$INV_FILE"" ]; then break; fi
  sleep 0.25
done
D_URI=""$(cat ""$INV_FILE"")""

set +e
run_c glide send --pair ""$D_URI"" --trusted-peer ""$FP_A"" ""$ROOT/c/unauth.txt"" >/dev/null 2>&1
RC_C=$?
set -e
test ""$RC_C"" -ne 0
test ""$(find ""$ROOT/a/dest"" -type f | wc -l)"" -eq 0

kill -TERM ""$DPID""
wait ""$DPID""
echo ""VM_DAEMON_REJECT_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-030", script, timeoutSeconds: 60);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_DAEMON_REJECT_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-030");
        }
    }

    // =========================================================================
    // UF7: Integrity, Stream Faults & Final Ack Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-031")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_031_BadSha256Digest_TriggersIntegrityMismatchAndZeroCommittedBytes()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-031");
        var (recv, atk) = await E2ETestHelpers.RunAdversarialAttackAsync(sandbox, "bad-digest");
        Assert.Equal(0, atk.ExitCode);
        Assert.True(atk.FindEvent("result")!.Value.GetProperty("rejected").GetBoolean());
        Assert.NotEqual(0, recv.ExitCode);
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-032")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_032_TruncatedAndExcessDataStreams_BothRejectedCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-032");
        byte[] payload = new byte[4096];
        Array.Fill(payload, (byte)0x42);
        string sha = Convert.ToHexStringLower(SHA256.HashData(payload));

        foreach (bool isTruncated in new[] { true, false })
        {
            await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
                sandbox,
                ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--decision-offer", "accept", "--timeout-seconds", "15"]);
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            JsonObject boot = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);
            string sid = boot["sessionId"]!.GetValue<string>();
            long seq = 1;
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("HELLO", sid, seq++, new JsonObject
                {
                    ["supportedMajors"] = new JsonArray(1),
                    ["requiredCapabilities"] = new JsonArray("stream-v1", "sha256"),
                    ["optionalCapabilities"] = new JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = Guid.NewGuid().ToString("D"),
                    ["displayName"] = "StreamFaultProbe"
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token);

            string batchId = Guid.NewGuid().ToString("D");
            string itemId = Guid.NewGuid().ToString("D");
            string bulkRef = Guid.NewGuid().ToString("D");
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_BEGIN", sid, seq++, new JsonObject
                {
                    ["batchId"] = batchId, ["itemCount"] = 1, ["totalBytes"] = payload.Length.ToString(), ["pageCount"] = 1
                }), cts.Token);
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_PAGE", sid, seq++, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["pageIndex"] = 0,
                    ["items"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["itemId"] = itemId, ["name"] = "fault.bin", ["mime"] = "application/octet-stream",
                            ["sizeBytes"] = payload.Length.ToString(), ["relativePath"] = "fault.bin"
                        }
                    }
                }), cts.Token);
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_END", sid, seq++, new JsonObject { ["batchId"] = batchId }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token); // OFFER_DECISION

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("ITEM_START", sid, seq++, new JsonObject
                {
                    ["batchId"] = batchId, ["itemId"] = itemId, ["bulkRef"] = bulkRef, ["sizeBytes"] = payload.Length.ToString()
                }), cts.Token);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, cts.Token); // ITEM_READY

            try
            {
                if (isTruncated)
                {
                    await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Data,
                        WireClientProbe.BuildDataPayload(bulkRef, payload.AsSpan(0, 2048)), cts.Token);
                    await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.StreamEnd,
                        WireClientProbe.BuildStreamEndPayload(bulkRef, 2048), cts.Token);
                }
                else
                {
                    byte[] excess = new byte[6000];
                    await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Data,
                        WireClientProbe.BuildDataPayload(bulkRef, excess), cts.Token);
                    await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.StreamEnd,
                        WireClientProbe.BuildStreamEndPayload(bulkRef, excess.Length), cts.Token);
                }
                await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                    WireClientProbe.BuildEnvelopeBytes("ITEM_END", sid, seq++, new JsonObject
                    {
                        ["batchId"] = batchId, ["itemId"] = itemId, ["actualBytes"] = payload.Length.ToString(), ["sha256"] = sha
                    }), cts.Token);
            }
            catch
            {
                // Receiver may close socket immediately upon stream length violation
            }

            ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-033")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_033_WrongItemIdAndInvalidControlSequence_Rejected()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-033");
        foreach (string attack in new[] { "wrong-item-id", "invalid-sequence" })
        {
            var (recv, atk) = await E2ETestHelpers.RunAdversarialAttackAsync(sandbox, attack);
            Assert.Equal(0, atk.ExitCode);
            Assert.True(atk.FindEvent("result")!.Value.GetProperty("rejected").GetBoolean());
            Assert.NotEqual(0, recv.ExitCode);
        }
        CorpusVerifier.AssertDestinationEmpty(sandbox.DestDir);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-034")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_034_LostFinalAcknowledgement_DropsAfterItemEnd_SenderReportsFailureWithoutHanging()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-034");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "lost-ack.bin"), 4096, 2034);
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
                    ["displayName"] = "DropAckPeer",
                    ["limits"] = new JsonObject { ["maxItems"] = 1000, ["maxItemBytes"] = "68719476736", ["maxBatchBytes"] = "274877906944", ["maxControlBytes"] = 16384 }
                }), ct);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_BEGIN
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_PAGE
            JsonObject offerEnd = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_END
            string batchId = offerEnd["body"]!["batchId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_DECISION", sid, 2, new JsonObject { ["batchId"] = batchId, ["accepted"] = true }), ct);

            JsonObject itemStart = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // ITEM_START
            string itemId = itemStart["body"]!["itemId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("ITEM_READY", sid, 3, new JsonObject { ["batchId"] = batchId, ["itemId"] = itemId }), ct);

            while (true)
            {
                WireRecord? rec = await WireClientProbe.ReadLanRecordAsync(tls.Ssl, ct);
                if (rec == null) break;
                if (rec.Kind == WireRecordKind.Control)
                {
                    JsonObject ctrl = WireClientProbe.ParseControlJson(rec);
                    if (ctrl["type"]?.GetValue<string>() == "ITEM_END")
                    {
                        tls.Tcp.Client.Close(0);
                        break;
                    }
                }
            }
        }, ct);

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "30"], stdinLine: uri);
        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, sender);
        Assert.NotEqual(0, sender.ExitCode);
        Assert.False(sender.TimedOut);
    }

    [Fact]
    [Trait("Id", "E2E-T2-035")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF7")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_035_CorruptDiagnosticJsonlPathAndLogRedaction_IsolatedToSandbox()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-035");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "log-redaction.bin"), 2048, 2035);
        CorpusManifest manifest = CorpusVerifier.BuildManifest(sandbox.SourceDir);

        // Subcase A: Pre-existing corrupt JSONL lines in GLIDE_DIAGNOSTIC_LOG_PATH must not break transfer
        File.WriteAllText(sandbox.DiagnosticLogPath, "{\"ts\":\nCORRUPT-NOT-JSON\n");
        var (recv1, send1) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(send1, "SenderCorruptLog");
        E2ETestHelpers.AssertResultSuccess(recv1, "ReceiverCorruptLog");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, sandbox.DestDir);

        // Inspect RAW stdout/stderr (pre-runner-redaction!) of sender & stderr of receiver for zero secret tokens
        Assert.DoesNotMatch(@"glide://pair/v\d+#[A-Za-z0-9_-]{20,}", send1.RawStdout + send1.RawStderr + recv1.RawStderr);
        Assert.DoesNotMatch(@"glide://pair/v\d+#[A-Za-z0-9_-]{20,}", File.ReadAllText(sandbox.DiagnosticLogPath));

        // Subcase B: Unwritable GLIDE_DIAGNOSTIC_LOG_PATH (pointing to an existing directory) must not crash transfer
        string dirAsLog = sandbox.CreateSubDir("diag-is-a-directory");
        string destB = sandbox.CreateSubDir("dest-b");
        var unwritableEnv = new Dictionary<string, string> { ["GLIDE_DIAGNOSTIC_LOG_PATH"] = dirAsLog };
        await using InteractiveDriverSession recv2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            [
                "listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1",
                "--staging-dir", sandbox.StagingDir, "--dest-dir", destB,
                "--decision-offer", "accept", "--decision-enrollment", "decline",
                "--timeout-seconds", "30"
            ],
            envOverrides: unwritableEnv);
        string uri2 = (await recv2.WaitForInvitationAsync(TimeSpan.FromSeconds(15))).GetProperty("invitationUri").GetString()!;
        ProcessRunResult send2 = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["connect-sender", "--source-dir", sandbox.SourceDir, "--decision-enrollment", "decline", "--timeout-seconds", "30"],
            stdinLine: uri2,
            envOverrides: unwritableEnv);
        ProcessRunResult recv2Exit = await recv2.WaitForExitAsync(TimeSpan.FromSeconds(20));

        E2ETestHelpers.AssertResultSuccess(send2, "SenderUnwritableLog");
        E2ETestHelpers.AssertResultSuccess(recv2Exit, "ReceiverUnwritableLog");
        CorpusVerifier.AssertDestinationMatchesManifest(manifest, destB);
        sandbox.AssertStagingClean();
    }

    // =========================================================================
    // UF8: Storage, Permission & Save Destination Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-036")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_036_InsufficientStorageRejection_SurfacesCleanlyToSender()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-036");
        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "enospc.bin"), 2048, 2036);
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
                    ["displayName"] = "FullDiskPeer",
                    ["limits"] = new JsonObject { ["maxItems"] = 1000, ["maxItemBytes"] = "68719476736", ["maxBatchBytes"] = "274877906944", ["maxControlBytes"] = 16384 }
                }), ct);
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_BEGIN
            await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct); // OFFER_PAGE
            JsonObject offerEnd = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct);
            string batchId = offerEnd["body"]!["batchId"]!.GetValue<string>();
            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("OFFER_DECISION", sid, 2, new JsonObject
                {
                    ["batchId"] = batchId,
                    ["accepted"] = false,
                    ["code"] = "INSUFFICIENT_STORAGE"
                }), ct);
        }, ct);

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "30"], stdinLine: uri);
        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, sender);
        Assert.NotEqual(0, sender.ExitCode);
        JsonElement resEvt = sender.FindEvent("result")!.Value;
        Assert.False(resEvt.GetProperty("success").GetBoolean());
        Assert.Contains("cannot save these files", resEvt.GetProperty("message").GetString()!, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    [Trait("Id", "E2E-T2-037")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_037_UnavailableOrFileBlockedDestination_FailsClosed()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-037");
        string blockingFile = Path.Combine(sandbox.RootDir, "not-a-directory.txt");
        CorpusVerifier.WriteUtf8TextFile(blockingFile, "block");

        ProcessRunResult res = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["listen-receiver", "--port", "0", "--staging-dir", sandbox.StagingDir, "--dest-dir", blockingFile, "--timeout-seconds", "5"]);
        Assert.NotEqual(0, res.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-038")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T2_038_WindowsUnwritableSaveFolder_RejectedInSettings()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T2-038");
        string lockedDir = sandbox.CreateSubDir("acl-locked-recv");
        string user = Environment.UserName;

        using (var denyProc = Process.Start(new ProcessStartInfo("icacls.exe", $"\"{lockedDir}\" /deny \"{user}:(OI)(CI)(W)\"")
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        })!)
        {
            await denyProc.WaitForExitAsync();
            Assert.Equal(0, denyProc.ExitCode);
        }

        try
        {
            await using WindowsUiSession ui = await WindowsUiSession.StartAsync(sandbox, cleanAppData: true, seedState: true, receiveDirectory: lockedDir);
            await ui.SwitchTabAsync("NavSettings");
            Assert.Equal(lockedDir, await ui.GetValueByNameAsync("Receive folder"));

            await ui.SwitchTabAsync("NavReceive");
            await ui.ClickByIdAsync("ReceiveCopyLinkButton");
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(await ui.ReadClipboardAsync());

            using var peer = new IdentityV1Peer("LockedDestPeer-T2-038");
            byte[] payload = Encoding.UTF8.GetBytes("unwritable-dest-probe");
            Task<IdentityTransferOutcome> sendTask = peer.SendFilesProfile1Async(inv, [("blocked.txt", payload)]);

            await ui.WaitForNameAsync("Not Now", timeoutSeconds: 15);
            await ui.ClickByNameAsync("Not Now");

            IdentityTransferOutcome outcome = await sendTask;
            Assert.False(outcome.Succeeded);
            Assert.Equal("INSUFFICIENT_STORAGE", outcome.ErrorCode);
            await ui.AssertVisibleByNameAsync("The receiving folder is unavailable, read-only, or short of space. Choose a writable folder in Settings and try again.");
            CorpusVerifier.AssertDestinationEmpty(lockedDir);
        }
        finally
        {
            using var restoreProc = Process.Start(new ProcessStartInfo("icacls.exe", $"\"{lockedDir}\" /remove:d \"{user}\"")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            });
            if (restoreProc != null) await restoreProc.WaitForExitAsync();
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-039")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_039_LinuxCorruptDesktopStateJson_ArchivedAndResetToDefaults()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-039");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
mkdir -p ""$XDG_DATA_HOME/glide"" ""$XDG_STATE_HOME/glide""
printf '{bad-json' > ""$XDG_DATA_HOME/glide/desktop-state.json""
printf '{bad-json' > ""$XDG_STATE_HOME/glide/desktop-state.json""

RUN_PREFIX=""""
if [ -n ""${DISPLAY:-}"" ] || [ -n ""${WAYLAND_DISPLAY:-}"" ]; then
  RUN_PREFIX=""""
elif command -v xvfb-run >/dev/null 2>&1; then
  RUN_PREFIX=""xvfb-run -a""
else
  echo ""Neither DISPLAY/WAYLAND_DISPLAY nor xvfb-run is available on VM for Glide.Linux.UI."" >&2
  exit 1
fi

set +e
timeout 5 $RUN_PREFIX glide-desktop > ""$XDG_STATE_HOME/ui.log"" 2>&1
set -e

ARCHIVED=""$(find ""$XDG_DATA_HOME/glide"" ""$XDG_STATE_HOME/glide"" -name 'desktop-state.json.corrupt-*' 2>/dev/null | wc -l)""
test ""$ARCHIVED"" -ge 1
if [ -f ""$XDG_DATA_HOME/glide/desktop-state.json"" ]; then
  ! grep -Fq '{bad-json' ""$XDG_DATA_HOME/glide/desktop-state.json""
fi
echo ""VM_CORRUPT_STATE_ARCHIVED count=$ARCHIVED""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-039", script, timeoutSeconds: 30);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_CORRUPT_STATE_ARCHIVED", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-039");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-040")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF8")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_040_AndroidErrorAndMissingFileStates_RenderActionableGuidance()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-040");

        // Pre-API-29 SAF fallback warning (SettingsScreen.kt SDK_INT < 29) is covered by JVM unit tests on API >= 29 emulators;
        // verify default Download/Glide save destination alongside actionable error and missing-file guidance states.
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "theme_settings");
        await AndroidUiHelpers.SwipeScrollAsync(sandbox, "Down");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Download/Glide");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "discovery_blocked");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Bluetooth");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "discovery_timeout");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "nearby");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "offer_insufficient_storage");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "storage");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "history_detail_missing");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Pixel 9 Pro");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "deleted_after_export_demo.mp4");
    }

    // =========================================================================
    // UF9: Headless CLI & Daemon Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-041")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_041_GlideAppCliInvalidArgsAndUnknownVerbs_ExitOneCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-041");
        string[][] badInvocations =
        [
            ["unknown-verb"],
            ["send"],
            ["send", "--pair"],
            ["send", "--pair", "not-a-uri"],
            ["daemon"],
            ["daemon", "--accept-paired-offers"]
        ];

        foreach (string[] args in badInvocations)
        {
            ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(sandbox, args);
            Assert.Equal(1, res.ExitCode);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-042")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_042_GlideAppDaemonMissingRequiredFlags_FailsBeforeTouchingStores()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-042");
        ProcessRunResult res = await GlideProcessRunner.RunGlideAppAsync(
            sandbox,
            ["daemon", "--trusted-peer", new string('a', 64), "--max-offer-bytes", "1024"]);
        Assert.Equal(1, res.ExitCode);
        Assert.Contains("--accept-paired-offers", res.Stdout);
    }

    [Fact]
    [Trait("Id", "E2E-T2-043")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_043_LinuxPermissiveRuntimeDir0777_RejectedFailClosed()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-043");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
# (A) Permissive XDG_RUNTIME_DIR (0777) must NOT be used; fallback $HOME/.local/share/glide/runtime/glide (0700) is used
chmod 777 ""$XDG_RUNTIME_DIR""
glide receive --port 0 > ""$HOME/recv.log"" 2>&1 &
RPID=$!
for _ in $(seq 1 30); do
  if grep -q '\[READY\]' ""$HOME/recv.log"" 2>/dev/null; then break; fi
  sleep 0.25
done
test ! -e ""$XDG_RUNTIME_DIR/glide/listener.lock""
test -d ""$HOME/.local/share/glide/runtime/glide""
test ""$(stat -c '%a' ""$HOME/.local/share/glide/runtime/glide"")"" = ""700""
kill -TERM ""$RPID"" 2>/dev/null || true
wait ""$RPID"" 2>/dev/null || true

# (B) Permissive identity-v1.pk8 (0644) -> glide identity show & glide receive MUST exit 1
chmod 700 ""$XDG_RUNTIME_DIR""
glide identity show >/dev/null
chmod 0644 ""$XDG_CONFIG_HOME/glide/identity-v1.pk8""

set +e
OUT_ID=""$(glide identity show 2>&1)""
RC_ID=$?
OUT_RECV=""$(glide receive --port 0 2>&1)""
RC_RECV=$?
set -e
test ""$RC_ID"" -eq 1
echo ""$OUT_ID"" | grep -Fq ""Persistent identity key permissions must be owner-only (0600)""
test ""$RC_RECV"" -eq 1
echo ""$OUT_RECV"" | grep -Fq ""Persistent identity key permissions must be owner-only (0600)""

# (C) Permissive trust-v1.json (0644) -> glide trust list MUST exit 1
chmod 0600 ""$XDG_CONFIG_HOME/glide/identity-v1.pk8""
cat > ""$XDG_CONFIG_HOME/glide/trust-v1.json"" <<'EOF'
{
  ""version"": 1,
  ""localIdentityFingerprintHex"": null,
  ""peers"": [],
  ""headlessPolicies"": []
}
EOF
chmod 0644 ""$XDG_CONFIG_HOME/glide/trust-v1.json""
set +e
OUT_TR=""$(glide trust list 2>&1)""
RC_TR=$?
set -e
test ""$RC_TR"" -eq 1
echo ""$OUT_TR"" | grep -Fq ""Trust database permissions must be owner-only""
echo ""VM_PERMS_FAIL_CLOSED_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-043", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_PERMS_FAIL_CLOSED_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-043");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-044")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_044_LinuxSecondListenerProcess_BlockedByListenerLock()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-044");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
FIFO=""$XDG_RUNTIME_DIR/lock.fifo""
mkfifo ""$FIFO""
exec 3<> ""$FIFO""
glide receive --port 0 <&3 > ""$XDG_RUNTIME_DIR/first.log"" 2>&1 &
FIRST_PID=$!
trap 'kill -TERM $FIRST_PID 2>/dev/null || true; exec 3>&-' EXIT

for _ in $(seq 1 40); do
  if grep -q '\[READY\]' ""$XDG_RUNTIME_DIR/first.log"" 2>/dev/null; then break; fi
  sleep 0.25
done
kill -0 ""$FIRST_PID""

set +e
glide receive --port 0 > ""$XDG_RUNTIME_DIR/second.log"" 2>&1
SECOND_RC=$?
set -e
test ""$SECOND_RC"" -eq 1
grep -Fq ""Another Glide process already owns the receiving listener"" ""$XDG_RUNTIME_DIR/second.log""
echo ""VM_LISTENER_LOCK_OK""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-044", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_LISTENER_LOCK_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-044");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-045")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF9")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_045_LinuxIdentityRotateYes_RevokesAllPeers()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-045");
        await VmTestHelpers.EnsureVmSuiteDeployedAsync(sandbox);

        string script = @"
set -euo pipefail
ROOT=""$HOME/rotate-t2-045""
mkdir -p ""$ROOT/a/cfg"" ""$ROOT/a/data"" ""$ROOT/a/state"" ""$ROOT/a/cache"" ""$ROOT/a/run"" ""$ROOT/a/Downloads""
mkdir -p ""$ROOT/b/cfg"" ""$ROOT/b/data"" ""$ROOT/b/state"" ""$ROOT/b/cache"" ""$ROOT/b/run""
chmod 700 ""$ROOT/a/run"" ""$ROOT/b/run""
run_a() { HOME=""$ROOT/a"" XDG_CONFIG_HOME=""$ROOT/a/cfg"" XDG_DATA_HOME=""$ROOT/a/data"" XDG_STATE_HOME=""$ROOT/a/state"" XDG_CACHE_HOME=""$ROOT/a/cache"" XDG_RUNTIME_DIR=""$ROOT/a/run"" ""$@""; }
run_b() { HOME=""$ROOT/b"" XDG_CONFIG_HOME=""$ROOT/b/cfg"" XDG_DATA_HOME=""$ROOT/b/data"" XDG_STATE_HOME=""$ROOT/b/state"" XDG_CACHE_HOME=""$ROOT/b/cache"" XDG_RUNTIME_DIR=""$ROOT/b/run"" ""$@""; }

FP_OLD=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
FP_B=""$(run_b glide identity show | awk '/Persistent identity:/ {print $3}')""
printf 'enroll-before-rotate' > ""$ROOT/b/e.txt""

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
run_a glide trust list | grep -Fq ""$FP_B""

ROT_OUT=""$(run_a glide identity rotate --yes)""
echo ""$ROT_OUT"" | grep -Fq ""Persistent identity rotated:""
echo ""$ROT_OUT"" | grep -Fq ""Revoked peer trust records: 1""
FP_NEW=""$(run_a glide identity show | awk '/Persistent identity:/ {print $3}')""
test ""$FP_OLD"" != ""$FP_NEW""
run_a glide trust list | grep -Fq ""No enrolled trusted peers.""
echo ""VM_ROTATE_CASCADE_OK old=$FP_OLD new=$FP_NEW""
";
        try
        {
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-045", script, timeoutSeconds: 45);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_ROTATE_CASCADE_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-045");
        }
    }

    // =========================================================================
    // UF10: Android Share Ingress & Intent Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-046")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_046_AndroidNonContentUriShare_ShowsInvalidShareDialog()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-046");

        foreach (string badUri in new[] { "file:///sdcard/Download/secret.bin", "https://example.com/file.bin" })
        {
            await AndroidUiHelpers.ShareSendAsync(sandbox, badUri, "application/octet-stream");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Invalid share");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Only content URIs are accepted.");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "OK");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Invalid share", absent: true);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-047")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_047_AndroidTextOnlyShare_ShowsUnsupportedShareDialog()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-047");

        await AndroidUiHelpers.ShareTextAsync(sandbox, "https://glide.local/unsupported-text-share");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Unsupported share");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Text and link sharing is not supported in Glide V1");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "OK");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Unsupported share", absent: true);
    }

    [Fact]
    [Trait("Id", "E2E-T2-048")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_048_AndroidShareOver1000Items_ShowsBatchLimitExceeded()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-048");
        const string deviceFolder = "/sdcard/Download/glide-e2e-1001";

        string adbExe = "adb";
        string serial = TargetEnvironment.Current.AndroidSerial;
        // Create 1,001 tiny files inside /sdcard/Download/glide-e2e-1001 via adb shell loop
        using (var mkProc = Process.Start(new ProcessStartInfo(adbExe)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            ArgumentList = { "-s", serial, "shell", $"rm -rf '{deviceFolder}' && mkdir -p '{deviceFolder}' && i=1; while [ $i -le 1001 ]; do : > '{deviceFolder}'/f$i.txt; i=$((i+1)); done" }
        })!)
        {
            await mkProc.WaitForExitAsync();
            Assert.Equal(0, mkProc.ExitCode);
        }

        try
        {
            await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
            await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "motion_normal");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Send files");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Choose folder");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Choose folder");

            // Navigate Android DocumentsUI picker to Download/glide-e2e-1001
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Download", timeoutSeconds: 10, ignoreCase: true);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textContains: "glide-e2e-1001", timeoutSeconds: 10);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textContains: "USE THIS FOLDER", timeoutSeconds: 10);
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Allow", timeoutSeconds: 10, ignoreCase: true);

            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Selection Error", timeoutSeconds: 20);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Batch limit exceeded: Maximum 1000 files allowed");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Choose files again");
        }
        finally
        {
            await AndroidUiHelpers.CleanupAsync(sandbox, deviceFolder);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-049")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_049_AndroidShareWhileTransferActive_ShowsActiveSessionBusy()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-049");
        string localFile = Path.Combine(sandbox.SourceDir, "busy-share.txt");
        CorpusVerifier.WriteUtf8TextFile(localFile, "busy-guard-share");
        string deviceFile = "/sdcard/Download/glide-e2e-busy.txt";

        try
        {
            await AndroidUiHelpers.GrantPermissionsAsync(sandbox);
            string uri = await AndroidUiHelpers.PushAndIndexFileAsync(sandbox, localFile, deviceFile);

            await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "share_test_busy");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, resourceId: "transfer_progress_card");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "large_dataset.zip");

            // Send ACTION_SEND with -NoRestart so MainActivity.onNewIntent fires while active
            await AndroidUiHelpers.ShareSendAsync(sandbox, uri, "text/plain", noRestart: true);
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Transfer in progress");
            await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "Glide cannot start a new share while a transfer is active.");
            await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "OK");
        }
        finally
        {
            await AndroidUiHelpers.CleanupAsync(sandbox, deviceFile);
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-050")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF10")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_050_AndroidCorruptQrInvitation_ShowsScanErrorDialog()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-050");

        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "discovery_peers");
        await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Scan QR code or paste invitation");
        await AndroidUiHelpers.TapNodeAsync(sandbox, contentDescContains: "Paste invitation link without using camera");
        await AndroidUiHelpers.InputTextAsync(sandbox, "not-a-glide-uri");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Verify & connect");

        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Invalid QR code");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "This code is not a valid Glide transfer invitation.");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Paste invitation");

        string expiredUri = WireClientProbe.BuildV1Uri(
            "10.0.2.2", 42424, new string('e', 64),
            expiresAtEpoch: DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 600,
            ttlSeconds: 120);
        await AndroidUiHelpers.InputTextAsync(sandbox, expiredUri);
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Verify & connect");

        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Invitation expired");
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textContains: "This code has expired or was used");
    }

    // =========================================================================
    // UF11: Recovery, Timeouts & Wire Framing Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-051")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_051_SourceMutatesMidSend_SenderAbortsAndZeroPartialCommitted()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-051");
        string srcFile = Path.Combine(sandbox.SourceDir, "mutating.bin");
        CorpusVerifier.WriteIncompressibleFile(srcFile, 256 * 1024, 2051);

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
                    ["displayName"] = "MutateProbe",
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

            File.WriteAllBytes(srcFile, new byte[128]);

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control,
                WireClientProbe.BuildEnvelopeBytes("ITEM_READY", sid, 3, new JsonObject { ["batchId"] = batchId, ["itemId"] = itemId }), ct);

            while (await WireClientProbe.ReadLanRecordAsync(tls.Ssl, ct) != null) { }
        }, ct);

        ProcessRunResult sender = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox, ["connect-sender", "--source-dir", sandbox.SourceDir, "--timeout-seconds", "30"], stdinLine: uri);
        await E2ETestHelpers.WaitForRogueServerCompletionAsync(serverTask, serverCts, sender);
        Assert.NotEqual(0, sender.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-052")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_052_StaleStagingFilesPresent_ValidTransferStillCommitsCleanly()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-052");
        string sessionUuid = Guid.NewGuid().ToString("D").ToLowerInvariant();
        string staleDir = Path.Combine(sandbox.StagingDir, sessionUuid);
        Directory.CreateDirectory(staleDir);

        string staleQuarantineFile = Path.Combine(staleDir, $"{Guid.NewGuid().ToString("D").ToLowerInvariant()}.quarantine");
        File.WriteAllText(staleQuarantineFile, "stale-quarantine-data");
        File.SetLastWriteTimeUtc(staleQuarantineFile, DateTime.UtcNow.AddDays(-2));
        Directory.SetLastWriteTimeUtc(staleDir, DateTime.UtcNow.AddDays(-2));

        string staleCommitFile = Path.Combine(sandbox.DestDir, ".glide-commit-0123456789abcdef0123456789abcdef.part");
        File.WriteAllText(staleCommitFile, "stale-commit-part");
        File.SetLastWriteTimeUtc(staleCommitFile, DateTime.UtcNow.AddHours(-1));

        CorpusVerifier.WriteIncompressibleFile(Path.Combine(sandbox.SourceDir, "fresh.bin"), 4096, 2052);
        var (receiver, sender) = await E2ETestHelpers.RunLoopbackTransferAsync(sandbox, sandbox.SourceDir);
        E2ETestHelpers.AssertResultSuccess(sender, "Sender");
        E2ETestHelpers.AssertResultSuccess(receiver, "Receiver");

        string destFile = Path.Combine(sandbox.DestDir, "fresh.bin");
        Assert.True(File.Exists(destFile));
        Assert.Equal(CorpusVerifier.ComputeFileSha256(Path.Combine(sandbox.SourceDir, "fresh.bin")), CorpusVerifier.ComputeFileSha256(destFile));
        Assert.False(File.Exists(staleCommitFile), "Abandoned commit temp file (>15m) should have been purged.");
        Assert.False(Directory.Exists(staleDir), "Abandoned quarantine session (>1d) should have been purged.");
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-053")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_053_SilentWirePeer_ReceiverTimesOutWithinBoundedBudget()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-053");
        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--timeout-seconds", "5"]);
        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(10));
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
        ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(15));
        Assert.False(recvExit.TimedOut);
        Assert.NotEqual(0, recvExit.ExitCode);
        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-054")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_054_OversizedAndCorruptControlFrames_RejectedBeforeAllocation()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-054");

        await using (InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);

            byte[] oversized = new byte[32_768];
            Array.Fill(oversized, (byte)'A');
            try
            {
                await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control, oversized, cts.Token);
            }
            catch { }

            ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        await using (InteractiveDriverSession listener2 = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--timeout-seconds", "15"]))
        {
            JsonElement invEvt = await listener2.WaitForInvitationAsync(TimeSpan.FromSeconds(15));
            WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
            await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, Guid.NewGuid().ToString("D"), "sender", cts.Token);

            await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control, "{not valid json!"u8.ToArray(), cts.Token);

            ProcessRunResult recvExit = await listener2.WaitForExitAsync(TimeSpan.FromSeconds(10));
            Assert.NotEqual(0, recvExit.ExitCode);
        }

        sandbox.AssertStagingClean();
    }

    [Fact]
    [Trait("Id", "E2E-T2-055")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF11")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_055_UnknownLanRecordKind_RejectedAndClosesConnection()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-055");
        await using InteractiveDriverSession listener = GlideProcessRunner.StartBackendDriverListener(
            sandbox,
            ["listen-receiver", "--port", "0", "--advertised-ip", "127.0.0.1", "--staging-dir", sandbox.StagingDir, "--dest-dir", sandbox.DestDir, "--timeout-seconds", "10"]);
        JsonElement invEvt = await listener.WaitForInvitationAsync(TimeSpan.FromSeconds(10));
        WireV1Invitation inv = WireClientProbe.DecodeV1Uri(invEvt.GetProperty("invitationUri").GetString()!);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        await using WireTlsSession tls = await WireClientProbe.ConnectTlsAsync(inv.Endpoints[0].Address, inv.Endpoints[0].Port, inv.SpkiSha256, cts.Token);
        byte[] bogusRecord = [0x7F, 0x00, 0x00, 0x00, 0x04, (byte)'t', (byte)'e', (byte)'s', (byte)'t'];
        await tls.Ssl.WriteAsync(bogusRecord, cts.Token);
        await tls.Ssl.FlushAsync(cts.Token);

        ProcessRunResult recvExit = await listener.WaitForExitAsync(TimeSpan.FromSeconds(10));
        Assert.NotEqual(0, recvExit.ExitCode);
        sandbox.AssertStagingClean();
    }

    // =========================================================================
    // UF12: Packaging & Upgrade Boundaries (5 Cases)
    // =========================================================================

    [Fact]
    [Trait("Id", "E2E-T2-056")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "Loopback")]
    public async Task E2E_T2_056_PackageLifecycleDriver_FailsClosedOnMissingPackagePath()
    {
        await using var sandbox = new E2ESandbox("E2E-T2-056");
        string nonexistentPkg = Path.Combine(sandbox.RootDir, "missing-glide-installer.exe");
        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Test-PackageLifecycle.ps1",
            ["-Platform", "Windows", "-PackagePath", nonexistentPkg]);
        Assert.NotEqual(0, res.ExitCode);
    }

    [Fact]
    [Trait("Id", "E2E-T2-057")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "WindowsUi")]
    public async Task E2E_T2_057_WindowsSilentUninstall_PreservesAppDataUnlessRemoveFlagSet()
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        await using var sandbox = new E2ESandbox("E2E-T2-057");
        string nsisExe = TargetEnvironment.Current.ResolveWindowsNsisInstaller();

        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Test-PackageLifecycle.ps1",
            [
                "-Platform", "Windows",
                "-LiveInstall",
                "-PackagePath", nsisExe,
                "-UpgradePackagePath", nsisExe,
                "-SandboxRoot", sandbox.CreateSubDir("win-uninst")
            ],
            TimeSpan.FromSeconds(180));
        Assert.True(res.ExitCode == 0, $"Windows silent uninstall retention test failed: {res.Stderr}\n{res.Stdout}");
    }

    [Fact]
    [Trait("Id", "E2E-T2-058")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_058_LinuxInstallerTamperCheck_RejectsCorruptedRunPayload()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-058");
        string runPath = TargetEnvironment.Current.ResolveLinuxRunInstaller();

        try
        {
            await VmTestHelpers.DeployFileAsync(sandbox, "e2e-t2-058", runPath, "/tmp/glide-e2e/e2e-t2-058/installer.run");
            string script = @"
set -euo pipefail
RUN=""/tmp/glide-e2e/e2e-t2-058/installer.run""
chmod +x ""$RUN""
""$RUN"" --verify >/dev/null

BAD=""/tmp/glide-e2e/e2e-t2-058/tampered.run""
cp ""$RUN"" ""$BAD""
printf 'x' >> ""$BAD""
chmod +x ""$BAD""

set +e
OUT_V=""$(""$BAD"" --verify 2>&1)""
RC_V=$?
""$BAD"" --prefix ""$HOME/tampered-prefix"" --no-deps-check --no-cache-refresh >/dev/null 2>&1
RC_I=$?
""$RUN"" --prefix /usr --no-deps-check >/dev/null 2>&1
RC_USR=$?
""$RUN"" --prefix relative/path --no-deps-check >/dev/null 2>&1
RC_REL=$?
""$RUN"" --bogus >/dev/null 2>&1
RC_BOG=$?
set -e

test ""$RC_V"" -ne 0
echo ""$OUT_V"" | grep -qi ""Payload checksum mismatch""
test ""$RC_I"" -ne 0
test ! -e ""$HOME/tampered-prefix/bin/glide""
test ""$RC_USR"" -ne 0
test ""$RC_REL"" -ne 0
test ""$RC_BOG"" -ne 0
echo ""VM_TAMPER_REJECT_OK""
";
            ProcessRunResult res = await E2ETestHelpers.RunVmCommandAsync(sandbox, "e2e-t2-058", script, timeoutSeconds: 60, useSystemPath: true);
            Assert.Equal(0, res.ExitCode);
            Assert.Contains("VM_TAMPER_REJECT_OK", VmTestHelpers.ExtractOutput(res));
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-058");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-059")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "LinuxVm")]
    public async Task E2E_T2_059_LinuxDebUpgrade_PreservesDaemonEnvAndTrustStore()
    {
        TargetEnvironment.Current.RequireVmReady();
        await using var sandbox = new E2ESandbox("E2E-T2-059");
        string debPath = TargetEnvironment.Current.ResolveLinuxDebPackage();

        try
        {
            ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Test-PackageLifecycle.ps1",
                [
                    "-Platform", "Linux",
                    "-PackagePath", debPath,
                    "-UpgradePackagePath", debPath,
                    "-SandboxRoot", "/tmp/glide-e2e/e2e-t2-059",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser
                ],
                TimeSpan.FromSeconds(180));
            Assert.True(res.ExitCode == 0, $"Linux .deb upgrade retention failed: {res.Stderr}\n{res.Stdout}");
        }
        finally
        {
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "e2e-t2-059");
            await VmTestHelpers.CleanupSandboxAsync(sandbox, "pkg-deb-state");
        }
    }

    [Fact]
    [Trait("Id", "E2E-T2-060")]
    [Trait("Tier", "2")]
    [Trait("Feature", "UF12")]
    [Trait("Target", "AndroidEmulator")]
    public async Task E2E_T2_060_AndroidApkUpgrade_PreservesRoomDbAndKeystoreIdentity()
    {
        TargetEnvironment.Current.RequireAndroidReady();
        await using var sandbox = new E2ESandbox("E2E-T2-060");
        string apkPath = TargetEnvironment.Current.ResolveAndroidDebugApk();

        // Seed persisted DataStore Dark theme before upgrade
        await AndroidUiHelpers.LaunchDebugStateAsync(sandbox, "theme_settings");
        await AndroidUiHelpers.TapNodeAsync(sandbox, textExact: "Dark");

        ProcessRunResult upgRes = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Test-PackageLifecycle.ps1",
            [
                "-Platform", "Android",
                "-Action", "Upgrade",
                "-PackagePath", apkPath,
                "-UpgradePackagePath", apkPath,
                "-AndroidSerial", TargetEnvironment.Current.AndroidSerial
            ],
            TimeSpan.FromSeconds(90));
        Assert.Equal(0, upgRes.ExitCode);

        ProcessRunResult stopRes = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Test-PackageLifecycle.ps1",
            [
                "-Platform", "Android",
                "-Action", "ForceStopAndVerify",
                "-PackagePath", apkPath,
                "-AndroidSerial", TargetEnvironment.Current.AndroidSerial
            ],
            TimeSpan.FromSeconds(60));
        Assert.Equal(0, stopRes.ExitCode);
        await AndroidUiHelpers.AssertUiNodeAsync(sandbox, textExact: "Dark");
    }
}
