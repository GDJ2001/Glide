using System.Diagnostics;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using Glide.App;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;

namespace Glide.Tests;

[CollectionDefinition("HARD-016 console/runtime", DisableParallelization = true)]
public sealed class Hard016ConsoleRuntimeCollectionDefinition
{
}

[Collection("HARD-016 console/runtime")]
public sealed class Hard016LinuxDaemonTests : IDisposable
{
    private readonly string _root =
        Path.Combine(
            Path.GetTempPath(),
            "glide-hard016-" + Guid.NewGuid().ToString("N"));

    public Hard016LinuxDaemonTests()
    {
        Directory.CreateDirectory(_root);
    }

    [Fact]
    public async Task DaemonWithoutPolicy_FailsClosedBeforeListening()
    {
        int result =
            await Program.Main(["daemon"]);

        Assert.Equal(1, result);
    }

    [Fact]
    public async Task PolicyLoad_MissingPeerEnrollment_IsRejected()
    {
        string policyPath =
            Path.Combine(_root, "invalid-policy.json");

        await File.WriteAllTextAsync(
            policyPath,
            $$"""
            {
              "destinationDirectory": "{{EscapeJson(Path.Combine(_root, "downloads"))}}",
              "enrollmentFile": "{{EscapeJson(Path.Combine(_root, "enrollment.txt"))}}",
              "allowedRemoteAddresses": [],
              "maxItemBytes": 1024,
              "maxBatchBytes": 4096,
              "maxItems": 10,
              "invitationTtlSeconds": 120,
              "deviceName": "Invalid Policy"
            }
            """);

        await Assert.ThrowsAsync<ArgumentException>(
            async () =>
                await DaemonReceivePolicy.LoadAsync(
                    policyPath));
    }

    [Fact]
    public void Policy_RejectsWrongRemotePeerAndOversizeOffer()
    {
        DaemonReceivePolicy policy =
            CreatePolicy(
                allowedAddress: "127.0.0.1",
                maxItemBytes: 1024,
                maxBatchBytes: 2048,
                maxItems: 2);

        Assert.True(
            policy.IsRemoteAllowed(
                IPAddress.Parse("127.0.0.1")));
        Assert.False(
            policy.IsRemoteAllowed(
                IPAddress.Parse("127.0.0.2")));

        Assert.True(
            policy.AuthorizesOffer(
                new DesktopOffer(
                    NewUuid(),
                    "Peer",
                    [
                        new DesktopOfferItem(
                            NewUuid(),
                            "ok.bin",
                            1024,
                            "ok.bin")
                    ],
                    1024)));

        Assert.False(
            policy.AuthorizesOffer(
                new DesktopOffer(
                    NewUuid(),
                    "Peer",
                    [
                        new DesktopOfferItem(
                            NewUuid(),
                            "too-large.bin",
                            1025,
                            "too-large.bin")
                    ],
                    1025)));
    }

    [Fact]
    public async Task EnrollmentFile_IsPrivateAndDeletesCleanly()
    {
        string path =
            Path.Combine(
                _root,
                "enrollment",
                "active.txt");

        var file =
            new DaemonEnrollmentFile(path);

        await file.WriteAsync(
            "glide://pair/v1#sensitive-test-bearer");

        Assert.True(File.Exists(path));

        if (OperatingSystem.IsLinux() ||
            OperatingSystem.IsMacOS())
        {
            UnixFileMode mode =
                File.GetUnixFileMode(path);

            Assert.Equal(
                UnixFileMode.UserRead |
                UnixFileMode.UserWrite,
                mode);
        }

        file.Delete();
        Assert.False(File.Exists(path));
    }

    [Fact]
    public async Task DaemonCancellation_RemovesEnrollmentAndStopsWithinBound()
    {
        DaemonReceivePolicy policy =
            CreatePolicy(
                allowedAddress: "127.0.0.1",
                invitationTtlSeconds: 60);

        using var cts =
            new CancellationTokenSource();

        Task<int> daemon =
            InvokeDaemonWithPolicyAsync(
                policy,
                port: 0,
                cts.Token);

        await WaitForAsync(
            () => File.Exists(policy.EnrollmentFile),
            TimeSpan.FromSeconds(5));

        var stopwatch =
            Stopwatch.StartNew();

        cts.Cancel();

        int result =
            await daemon.WaitAsync(
                TimeSpan.FromSeconds(5));

        stopwatch.Stop();

        Assert.Equal(0, result);
        Assert.False(
            File.Exists(policy.EnrollmentFile));
        Assert.True(
            stopwatch.Elapsed <
            TimeSpan.FromSeconds(5),
            $"Daemon cancellation took {stopwatch.Elapsed}.");
    }

    [Fact]
    public async Task ReceiveLimits_RejectMaliciousOversizeOfferBeforeConsent()
    {
        using var listener =
            new TlsSocketListener(0);

        LanInvitation invitation =
            CreateInvitation(listener);

        string destination =
            Path.Combine(_root, "limits-downloads");
        string staging =
            Path.Combine(_root, "limits-staging");

        var store =
            new QuarantineStagingStore(
                staging,
                destination,
                () => 1024 * 1024);

        var interaction =
            new CountingOfferInteraction();

        var receiverTask = Task.Run(async () =>
        {
            await using var connection =
                await listener.AcceptConnectionAsync();

            return await new DesktopTransferEngine().ReceiveAsync(
                connection,
                new DesktopReceiveRequest(
                    store,
                    new LanInvitationAuthority(invitation),
                    "Limited Receiver",
                    new DesktopReceiveLimits(
                        MaxItems: 1,
                        MaxItemBytes: 1024,
                        MaxBatchBytes: 1024)),
                interaction);
        });

        var client =
            new TlsSocketClient();

        await using var senderConnection =
            await client.ConnectAsync(
                "127.0.0.1",
                listener.BoundPort,
                invitation.SpkiSha256);

        using var channel =
            new LanRecordChannel(
                senderConnection.SslStream);

        string sessionId = NewUuid();

        await channel.WriteBootstrapAsync(
            new LanBootstrapMessage(
                invitation.InvitationId,
                invitation.Token,
                invitation.InvitationHash,
                sessionId));

        Assert.True(
            (await channel.ReadBootstrapResultAsync())
                .IsAccepted);

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.Hello,
                new SessionId(sessionId),
                NewMessageId(),
                1,
                new System.Text.Json.Nodes.JsonObject
                {
                    ["supportedMajors"] =
                        new System.Text.Json.Nodes.JsonArray(1),
                    ["requiredCapabilities"] =
                        new System.Text.Json.Nodes.JsonArray(
                            "stream-v1",
                            "sha256"),
                    ["optionalCapabilities"] =
                        new System.Text.Json.Nodes.JsonArray(),
                    ["role"] = "sender",
                    ["ephemeralPeerId"] = NewUuid(),
                    ["displayName"] = "Malicious Sender"
                }));

        Envelope helloAck =
            await channel.ReadEnvelopeAsync();

        Assert.Equal(
            "1024",
            helloAck.Body["maxBatchBytes"]?.ToString()
                ?? helloAck.Body["limits"]?["maxBatchBytes"]?.ToString());

        await channel.WriteEnvelopeAsync(
            new Envelope(
                1,
                MessageTypes.OfferBegin,
                new SessionId(sessionId),
                NewMessageId(),
                2,
                new System.Text.Json.Nodes.JsonObject
                {
                    ["batchId"] = NewUuid(),
                    ["itemCount"] = 1,
                    ["totalBytes"] = "2048",
                    ["pageCount"] = 1
                }));

        DesktopTransferResult result =
            await receiverTask.WaitAsync(
                TimeSpan.FromSeconds(5));

        Assert.False(result.Success);
        Assert.Equal(0, interaction.ConfirmOfferCalls);
        Assert.True(
            result.Message.Contains(
                "configured receive policy",
                StringComparison.OrdinalIgnoreCase),
            result.Message);
    }

    [Fact]
    public void SystemdUnit_RequiresPolicyAndBoundedSigtermShutdown()
    {
        string root = ResolveRepositoryRoot();
        string service =
            File.ReadAllText(
                Path.Combine(
                    root,
                    "packaging",
                    "linux",
                    "glide.service"));

        Assert.Contains(
            "daemon --policy %h/.config/glide/daemon-policy.json",
            service);
        Assert.Contains("KillSignal=SIGTERM", service);
        Assert.Contains("TimeoutStopSec=15s", service);
        Assert.Contains("UMask=0077", service);
        Assert.Contains("NoNewPrivileges=true", service);
        Assert.Contains("ReadWritePaths=", service);
    }

    private DaemonReceivePolicy CreatePolicy(
        string allowedAddress,
        long maxItemBytes = 1024 * 1024,
        long maxBatchBytes = 4 * 1024 * 1024,
        int maxItems = 10,
        int invitationTtlSeconds = 120) =>
        new DaemonReceivePolicy
        {
            DestinationDirectory =
                Path.Combine(_root, "downloads"),
            EnrollmentFile =
                Path.Combine(_root, "daemon-enrollment.txt"),
            AllowedRemoteAddresses =
                [allowedAddress],
            MaxItemBytes = maxItemBytes,
            MaxBatchBytes = maxBatchBytes,
            MaxItems = maxItems,
            InvitationTtlSeconds = invitationTtlSeconds,
            DeviceName = "HARD-016 Daemon"
        }.Validate();

    private static async Task<int> InvokeDaemonWithPolicyAsync(
        DaemonReceivePolicy policy,
        int port,
        CancellationToken cancellationToken)
    {
        MethodInfo method =
            typeof(Program).GetMethod(
                "RunDaemonWithPolicyAsync",
                BindingFlags.NonPublic |
                BindingFlags.Static)
            ?? throw new MissingMethodException(
                typeof(Program).FullName,
                "RunDaemonWithPolicyAsync");

        var task =
            (Task<int>?)method.Invoke(
                null,
                [policy, port, cancellationToken])
            ?? throw new InvalidOperationException(
                "RunDaemonWithPolicyAsync did not return Task<int>.");

        return await task;
    }

    private static async Task WaitForAsync(
        Func<bool> condition,
        TimeSpan timeout)
    {
        Stopwatch stopwatch =
            Stopwatch.StartNew();

        while (!condition())
        {
            if (stopwatch.Elapsed >= timeout)
            {
                throw new TimeoutException(
                    "Condition was not reached within the test timeout.");
            }

            await Task.Delay(25);
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
                presence:
                    Convert.ToHexString(
                            RandomNumberGenerator.GetBytes(16))
                        .ToLowerInvariant(),
                name: "HARD-016 Receiver",
                platform: "linux",
                endpoints:
                [
                    new LanEndpoint(
                        "127.0.0.1",
                        listener.BoundPort)
                ],
                spkiSha256:
                    listener.LocalSpkiSha256,
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

    private static string EscapeJson(
        string value) =>
        value.Replace("\\", "\\\\")
             .Replace(""", "\\"");

    private static MessageId NewMessageId() =>
        new(NewUuid());

    private static string NewUuid() =>
        Guid.NewGuid()
            .ToString("D")
            .ToLowerInvariant();

    private static string ResolveRepositoryRoot()
    {
        var directory =
            new DirectoryInfo(
                AppContext.BaseDirectory);

        while (directory != null)
        {
            if (Directory.Exists(
                    Path.Combine(
                        directory.FullName,
                        ".git")) ||
                File.Exists(
                    Path.Combine(
                        directory.FullName,
                        "settings.gradle.kts")))
            {
                return directory.FullName;
            }

            directory = directory.Parent;
        }

        throw new DirectoryNotFoundException(
            "Unable to locate Glide repository root.");
    }

    private sealed class CountingOfferInteraction :
        DesktopTransferInteractionBase
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
