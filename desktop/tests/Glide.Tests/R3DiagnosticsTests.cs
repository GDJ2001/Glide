using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Security;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Desktop.Diagnostics;
using Glide.Desktop.Services;
using Glide.Transport.Sockets;
using Xunit;

namespace Glide.Tests;

[Collection("HARD-003 console/runtime")]
public sealed class R3DiagnosticsTests : IDisposable
{
    private readonly string _tempRoot;

    public R3DiagnosticsTests()
    {
        _tempRoot = Path.Combine(Path.GetTempPath(), "Glide_R3DiagTests_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_tempRoot);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_tempRoot))
            {
                Directory.Delete(_tempRoot, recursive: true);
            }
        }
        catch { }
    }

    [Fact]
    public void MetricValue_EmitsUnavailableSentinel_AndRoundTripsNumbers()
    {
        var payload = new
        {
            cpuMissing = MetricValue<double>.Unavailable,
            cpuNan = MetricValue<double>.Available(double.NaN),
            cpuInf = MetricValue<double>.Available(double.PositiveInfinity),
            cpuOk = MetricValue<double>.Available(42.5),
            countMissing = MetricValue<int>.Unavailable,
            countOk = MetricValue<int>.Available(16),
            flagMissing = MetricValue<bool>.Unavailable,
            flagOk = MetricValue<bool>.Available(true),
            textMissing = MetricValue<string>.Unavailable,
            textUnavailableLiteral = MetricValue<string>.Available("unavailable"),
            textOk = MetricValue<string>.Available("NTFS")
        };

        string json = JsonSerializer.Serialize(payload);
        using var doc = JsonDocument.Parse(json);
        JsonElement root = doc.RootElement;

        Assert.Equal("unavailable", root.GetProperty("cpuMissing").GetString());
        Assert.Equal("unavailable", root.GetProperty("cpuNan").GetString());
        Assert.Equal("unavailable", root.GetProperty("cpuInf").GetString());
        Assert.Equal(42.5, root.GetProperty("cpuOk").GetDouble());
        Assert.Equal("unavailable", root.GetProperty("countMissing").GetString());
        Assert.Equal(16, root.GetProperty("countOk").GetInt32());
        Assert.Equal("unavailable", root.GetProperty("flagMissing").GetString());
        Assert.True(root.GetProperty("flagOk").GetBoolean());
        Assert.Equal("unavailable", root.GetProperty("textMissing").GetString());
        Assert.Equal("unavailable", root.GetProperty("textUnavailableLiteral").GetString());
        Assert.Equal("NTFS", root.GetProperty("textOk").GetString());

        var roundTripped = JsonSerializer.Deserialize<MetricValue<double>>("\"unavailable\"");
        Assert.False(roundTripped.IsAvailable);
        var roundTrippedNum = JsonSerializer.Deserialize<MetricValue<double>>("99.25");
        Assert.True(roundTrippedNum.IsAvailable);
        Assert.Equal(99.25, roundTrippedNum.Value);
    }

    [Fact]
    public void FiveSecondWindows_AndSteadyQuarterMath_ComputeAccurateRatesAndCaps()
    {
        // 20 seconds at steady 250 MB/s (total 5,000,000,000 bytes)
        var steadyPoints = new List<(double ElapsedSec, long Bytes)>();
        for (int s = 0; s <= 20; s++)
        {
            steadyPoints.Add((s, s * 250_000_000L));
        }

        IReadOnlyList<R3FiveSecondRateWindow> windows = BoundedTransferDiagnosticSink.ComputeFiveSecondRateWindows(steadyPoints);
        Assert.Equal(4, windows.Count);
        for (int i = 0; i < 4; i++)
        {
            Assert.Equal(i, windows[i].WindowIndex);
            Assert.Equal(i * 5.0, windows[i].StartElapsedSec);
            Assert.Equal((i + 1) * 5.0, windows[i].EndElapsedSec);
            Assert.Equal(1_250_000_000L, windows[i].Bytes);
            Assert.Equal(250.0, windows[i].RateMBps);
        }

        var (q1, q4, ratio) = BoundedTransferDiagnosticSink.ComputeSteadyQuarterRates(steadyPoints, 5_000_000_000L);
        Assert.True(q1.IsAvailable && q4.IsAvailable && ratio.IsAvailable);
        Assert.Equal(250.0, q1.Value);
        Assert.Equal(250.0, q4.Value);
        Assert.Equal(1.0, ratio.Value);

        // Simulated final-quarter collapse: first 12.5s at 300 MB/s (3.75 GB), final 25s at 50 MB/s (1.25 GB -> 5.0 GB)
        var collapsedPoints = new List<(double ElapsedSec, long Bytes)>
        {
            (0.0, 0L),
            (12.5, 3_750_000_000L),
            (37.5, 5_000_000_000L)
        };
        var (cq1, cq4, cratio) = BoundedTransferDiagnosticSink.ComputeSteadyQuarterRates(collapsedPoints, 5_000_000_000L);
        Assert.Equal(300.0, cq1.Value);
        Assert.Equal(50.0, cq4.Value);
        Assert.True(cratio.Value < 0.20);

        // Enforce 2,160-window ceiling on very long runs
        var hugePoints = new List<(double ElapsedSec, long Bytes)>
        {
            (0.0, 0L),
            (15_000.0, 15_000L * 1_000_000L)
        };
        IReadOnlyList<R3FiveSecondRateWindow> capped = BoundedTransferDiagnosticSink.ComputeFiveSecondRateWindows(hugePoints);
        Assert.Equal(2_160, capped.Count);
    }

    [Fact]
    public async Task R3ReportAndSample_ContainRequiredSchemaFields_AndZeroSecrets()
    {
        string logPath = Path.Combine(_tempRoot, "logs", "r3-diag.jsonl");
        string srcDir = Path.Combine(_tempRoot, "src");
        string stgDir = Path.Combine(_tempRoot, "stg");
        string dstDir = Path.Combine(_tempRoot, "dst");
        Directory.CreateDirectory(srcDir);
        Directory.CreateDirectory(stgDir);
        Directory.CreateDirectory(dstDir);

        string filePath = Path.Combine(srcDir, "payload.bin");
        byte[] data = RandomNumberGenerator.GetBytes(2 * 1024 * 1024);
        await File.WriteAllBytesAsync(filePath, data);

        using var listener = new TlsSocketListener(0);
        var authority = new LanInvitationAuthority();
        string rawToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
        var rawInv = new LanInvitation(
            invitationId: Guid.NewGuid().ToString("D").ToLowerInvariant(),
            presence: Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant(),
            name: "R3-Receiver",
            platform: OperatingSystem.IsLinux() ? "linux" : "windows",
            endpoints: [new LanEndpoint("127.0.0.1", listener.BoundPort)],
            spkiSha256: listener.LocalSpkiSha256,
            token: rawToken,
            expiresAt: (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120).ToString(),
            ttlSeconds: 120);
        LanInvitation inv = authority.Publish(InvitationCodec.Decode(InvitationCodec.Encode(rawInv)));

        await using var sink = new BoundedTransferDiagnosticSink(logPath, buildCommit: "4a102faddd09ba851a319be1a8b522af9f9d581c");
        var senderEngine = new DesktopTransferEngine(diagnostics: sink);
        var receiverEngine = new DesktopTransferEngine(diagnostics: sink);
        var store = new QuarantineStagingStore(stgDir, dstDir);

        Task<DesktopTransferResult> recvTask = Task.Run(async () =>
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync();
            return await receiverEngine.ReceiveAsync(
                conn,
                new DesktopReceiveRequest(store, authority, "R3-Receiver"),
                new AutoAcceptInteraction());
        });

        DesktopTransferResult sendRes = await senderEngine.SendAsync(
            new DesktopSendRequest("127.0.0.1", listener.BoundPort, [new DesktopTransferFile(filePath)], inv, "R3-Sender"),
            new AutoAcceptInteraction());
        DesktopTransferResult recvRes = await recvTask;

        Assert.True(sendRes.Success);
        Assert.True(recvRes.Success);

        R3DiagnosticReport? sendReport = senderEngine.LastR3DiagnosticReport;
        R3DiagnosticReport? recvReport = receiverEngine.LastR3DiagnosticReport;
        Assert.NotNull(sendReport);
        Assert.NotNull(recvReport);

        string sendJson = sendReport!.ToJson();
        string recvJson = recvReport!.ToJson();

        // Verify zero secrets
        Assert.DoesNotContain(rawToken, sendJson, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(rawToken, recvJson, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("glide://pair/", sendJson, StringComparison.OrdinalIgnoreCase);

        // Validate complete R3 JSON schema structure
        using var sendDoc = JsonDocument.Parse(sendJson);
        JsonElement sRoot = sendDoc.RootElement;
        Assert.Equal("glide-r3-diagnostic-v1", sRoot.GetProperty("schemaVersion").GetString());
        Assert.Equal("4a102faddd09ba851a319be1a8b522af9f9d581c", sRoot.GetProperty("sourceCommit").GetString());
        foreach (string section in new[]
        {
            "platform", "hardware", "runtime", "networkLink", "storage", "dataset",
            "testConfiguration", "outcome", "phaseDurationsMs", "bytes", "throughput",
            "pipelineTimingsMs", "resources", "queueOccupancy", "diskActivity",
            "cancellation", "androidConditions", "linuxConditions"
        })
        {
            Assert.True(sRoot.TryGetProperty(section, out _), $"Missing R3 section '{section}'");
        }

        Assert.Equal("unavailable", sRoot.GetProperty("androidConditions").GetProperty("thermalStatusStart").GetString());
        Assert.Equal("unavailable", sRoot.GetProperty("phaseDurationsMs").GetProperty("discoveryMs").GetString());
        Assert.True(sRoot.GetProperty("phaseDurationsMs").GetProperty("connectionMs").GetDouble() >= 0.0);
        Assert.True(sRoot.GetProperty("phaseDurationsMs").GetProperty("authenticationMs").GetDouble() >= 0.0);
        Assert.True(sRoot.GetProperty("phaseDurationsMs").GetProperty("finalAcknowledgementMs").GetDouble() >= 0.0);

        using var recvDoc = JsonDocument.Parse(recvJson);
        JsonElement rRoot = recvDoc.RootElement;
        Assert.Equal(data.Length, rRoot.GetProperty("bytes").GetProperty("verifiedBytes").GetInt64());
        Assert.True(rRoot.GetProperty("outcome").GetProperty("sha256Verified").GetBoolean());

        // Check sample JSONL fields consumed by diagnose-desktop-transfer.ps1
        DesktopTransferDiagnosticSample? terminalSample = senderEngine.LastDiagnosticSample;
        Assert.NotNull(terminalSample);
        string sampleJson = JsonSerializer.Serialize(terminalSample);
        using var sampleDoc = JsonDocument.Parse(sampleJson);
        JsonElement smp = sampleDoc.RootElement;
        Assert.Equal("glide-r3-sample-v1", smp.GetProperty("schemaVersion").GetString());
        foreach (string legacyField in new[]
        {
            "phase", "managedAllocatedMBPerSec", "gcPauseMs", "managedHeapMiB",
            "senderQueueDepth", "receiverQueueDepth", "savingMs", "fiveSecondWindowRateMBps",
            "nativeMemoryMiB", "thermalStatus", "batteryLevelPercent"
        })
        {
            Assert.True(smp.TryGetProperty(legacyField, out _), $"Missing sample field '{legacyField}'");
        }
    }

    [Fact]
    public void Sanitization_RedactsPairLinksSecretsAndUserProfilePaths()
    {
        string rawText = "Failed glide://pair/v1#abc123?token=mySecretToken99&pin=654321 password=hunter2 spki=deadbeef";
        string sanitized = BoundedTransferDiagnosticSink.SanitizeDiagnosticText(rawText);
        Assert.DoesNotContain("abc123", sanitized, StringComparison.Ordinal);
        Assert.DoesNotContain("mySecretToken99", sanitized, StringComparison.Ordinal);
        Assert.DoesNotContain("654321", sanitized, StringComparison.Ordinal);
        Assert.DoesNotContain("hunter2", sanitized, StringComparison.Ordinal);
        Assert.DoesNotContain("deadbeef", sanitized, StringComparison.Ordinal);
        Assert.Contains("glide://pair/[REDACTED]", sanitized, StringComparison.Ordinal);

        string winPath = BoundedTransferDiagnosticSink.SanitizePathForDiagnostics(@"C:\Users\Alice\Documents\secret.txt");
        Assert.Equal(@"~\Documents\secret.txt", winPath);

        string linPath = BoundedTransferDiagnosticSink.SanitizePathForDiagnostics("/home/alice/glide/staging");
        Assert.Equal("~/glide/staging", linPath);

        string longMsg = new('a', 700);
        Assert.Equal(512, BoundedTransferDiagnosticSink.TruncateMessage(longMsg, 512).Length);
    }

    [Fact]
    public async Task RuntimeCancellation_RecordsUiAndResourceReleaseLatenciesInR3Report()
    {
        string srcDir = Path.Combine(_tempRoot, "cancel_src");
        string stgDir = Path.Combine(_tempRoot, "cancel_stg");
        string dstDir = Path.Combine(_tempRoot, "cancel_dst");
        Directory.CreateDirectory(srcDir);
        Directory.CreateDirectory(stgDir);
        Directory.CreateDirectory(dstDir);

        string filePath = Path.Combine(srcDir, "cancel.bin");
        await File.WriteAllBytesAsync(filePath, RandomNumberGenerator.GetBytes(1024 * 1024));

        using var listener = new TlsSocketListener(0);
        var authority = new LanInvitationAuthority();
        string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var rawInv = new LanInvitation(
            Guid.NewGuid().ToString("D").ToLowerInvariant(),
            Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant(),
            "Cancel-Receiver",
            OperatingSystem.IsLinux() ? "linux" : "windows",
            [new LanEndpoint("127.0.0.1", listener.BoundPort)],
            listener.LocalSpkiSha256,
            token,
            (DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 120).ToString(),
            120);
        LanInvitation inv = authority.Publish(InvitationCodec.Decode(InvitationCodec.Encode(rawInv)));

        var engine = new DesktopTransferEngine();
        var runtime = new DesktopTransferRuntime(engine);
        var offerSeen = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        Task<DesktopTransferResult> recvTask = Task.Run(async () =>
        {
            await using TlsSocketConnection conn = await listener.AcceptConnectionAsync();
            var receiverEngine = new DesktopTransferEngine();
            return await receiverEngine.ReceiveAsync(
                conn,
                new DesktopReceiveRequest(new QuarantineStagingStore(stgDir, dstDir), authority, "Cancel-Receiver"),
                new BlockingOfferInteraction(offerSeen));
        });

        Task<DesktopTransferResult> sendTask = runtime.SendAsync(
            new DesktopSendRequest("127.0.0.1", listener.BoundPort, [new DesktopTransferFile(filePath)], inv, "Cancel-Sender"),
            new AutoAcceptInteraction());

        await offerSeen.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(runtime.CancelActiveTransfer());

        DesktopTransferResult sendRes = await sendTask;
        await recvTask;

        Assert.True(sendRes.Cancelled);
        Assert.NotNull(runtime.LastCancellationTiming);
        Assert.True(runtime.LastCancellationTiming!.UiCancelLatencyMs >= 0.0);
        Assert.True(runtime.LastCancellationTiming.ResourceReleaseLatencyMs >= 0.0);
        Assert.NotNull(runtime.LastR3DiagnosticReport);
        Assert.True(runtime.LastR3DiagnosticReport!.Cancellation.CancellationRequested);
        Assert.True(runtime.LastR3DiagnosticReport.Cancellation.UiCancelLatencyMs.IsAvailable);
        Assert.True(runtime.LastR3DiagnosticReport.Cancellation.ResourceReleaseLatencyMs.IsAvailable);
    }

    private sealed class AutoAcceptInteraction : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken) => Task.FromResult(true);
        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) => Task.FromResult(true);
        public override Task<bool> ConfirmPeerEnrollmentAsync(VerifiedDesktopPeerIdentity peer, CancellationToken cancellationToken) => Task.FromResult(true);
    }

    private sealed class BlockingOfferInteraction(TaskCompletionSource offerReached) : DesktopTransferInteractionBase
    {
        public override async Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
        {
            offerReached.TrySetResult();
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken);
            }
            catch (OperationCanceledException) { }
            return false;
        }
    }
}
