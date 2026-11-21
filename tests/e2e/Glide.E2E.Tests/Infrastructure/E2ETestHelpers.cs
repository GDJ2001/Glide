using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Glide.E2E.Tests.Infrastructure;

public static class E2ETestHelpers
{
    public static async Task<(ProcessRunResult Receiver, ProcessRunResult Sender)> RunLoopbackTransferAsync(
        E2ESandbox sandbox,
        string sourceDir,
        bool acceptOffer = true,
        bool acceptEnrollment = false,
        long cancelAfterBytes = -1,
        string? expectedPeerSender = null,
        string? expectedPeerReceiver = null,
        string? trustStoreReceiver = null,
        string? identityStoreReceiver = null,
        string? trustStoreSender = null,
        string? identityStoreSender = null,
        int timeoutSeconds = 60,
        IReadOnlyDictionary<string, string>? envOverrides = null)
    {
        var receiverArgs = new List<string>
        {
            "listen-receiver",
            "--port", "0",
            "--advertised-ip", "127.0.0.1",
            "--staging-dir", sandbox.StagingDir,
            "--dest-dir", sandbox.DestDir,
            "--decision-offer", acceptOffer ? "accept" : "decline",
            "--decision-enrollment", acceptEnrollment ? "accept" : "decline",
            "--timeout-seconds", timeoutSeconds.ToString()
        };
        if (cancelAfterBytes > 0)
        {
            receiverArgs.Add("--cancel-after-bytes");
            receiverArgs.Add(cancelAfterBytes.ToString());
        }
        if (!string.IsNullOrWhiteSpace(expectedPeerReceiver))
        {
            receiverArgs.Add("--expected-peer");
            receiverArgs.Add(expectedPeerReceiver);
        }
        if (!string.IsNullOrWhiteSpace(trustStoreReceiver))
        {
            receiverArgs.Add("--trust-store");
            receiverArgs.Add(trustStoreReceiver);
        }
        if (!string.IsNullOrWhiteSpace(identityStoreReceiver))
        {
            receiverArgs.Add("--identity-store");
            receiverArgs.Add(identityStoreReceiver);
        }

        await using InteractiveDriverSession receiver = GlideProcessRunner.StartBackendDriverListener(sandbox, receiverArgs, envOverrides);
        JsonElement invEvt = await receiver.WaitForInvitationAsync(TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        string invitationUri = invEvt.GetProperty("invitationUri").GetString()!;

        var senderArgs = new List<string>
        {
            "connect-sender",
            "--source-dir", sourceDir,
            "--decision-enrollment", acceptEnrollment ? "accept" : "decline",
            "--timeout-seconds", timeoutSeconds.ToString()
        };
        if (!string.IsNullOrWhiteSpace(expectedPeerSender))
        {
            senderArgs.Add("--expected-peer");
            senderArgs.Add(expectedPeerSender);
        }
        if (!string.IsNullOrWhiteSpace(trustStoreSender))
        {
            senderArgs.Add("--trust-store");
            senderArgs.Add(trustStoreSender);
        }
        if (!string.IsNullOrWhiteSpace(identityStoreSender))
        {
            senderArgs.Add("--identity-store");
            senderArgs.Add(identityStoreSender);
        }

        ProcessRunResult senderResult = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            senderArgs,
            stdinLine: invitationUri,
            timeout: TimeSpan.FromSeconds(timeoutSeconds + 15),
            envOverrides: envOverrides).ConfigureAwait(false);

        ProcessRunResult receiverResult = await receiver.WaitForExitAsync(TimeSpan.FromSeconds(timeoutSeconds + 15)).ConfigureAwait(false);
        return (receiverResult, senderResult);
    }

    public static async Task<(ProcessRunResult Sender, ProcessRunResult Receiver)> RunHostedSenderTransferAsync(
        E2ESandbox sandbox,
        string sourceDir,
        bool acceptRecipient = true,
        bool acceptOffer = true,
        bool acceptEnrollment = false,
        int timeoutSeconds = 60)
    {
        var hostedArgs = new List<string>
        {
            "listen-hosted-sender",
            "--port", "0",
            "--advertised-ip", "127.0.0.1",
            "--source-dir", sourceDir,
            "--decision-recipient", acceptRecipient ? "accept" : "decline",
            "--decision-enrollment", acceptEnrollment ? "accept" : "decline",
            "--timeout-seconds", timeoutSeconds.ToString()
        };

        await using InteractiveDriverSession hostedSender = GlideProcessRunner.StartBackendDriverListener(sandbox, hostedArgs);
        JsonElement invEvt = await hostedSender.WaitForInvitationAsync(TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        string invitationUri = invEvt.GetProperty("invitationUri").GetString()!;

        var recvArgs = new List<string>
        {
            "connect-receiver",
            "--staging-dir", sandbox.StagingDir,
            "--dest-dir", sandbox.DestDir,
            "--decision-offer", acceptOffer ? "accept" : "decline",
            "--decision-enrollment", acceptEnrollment ? "accept" : "decline",
            "--timeout-seconds", timeoutSeconds.ToString()
        };

        ProcessRunResult recvResult = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            recvArgs,
            stdinLine: invitationUri,
            timeout: TimeSpan.FromSeconds(timeoutSeconds + 15)).ConfigureAwait(false);

        ProcessRunResult senderResult = await hostedSender.WaitForExitAsync(TimeSpan.FromSeconds(timeoutSeconds + 15)).ConfigureAwait(false);
        return (senderResult, recvResult);
    }

    public static async Task<(ProcessRunResult Receiver, ProcessRunResult AttackResult)> RunAdversarialAttackAsync(
        E2ESandbox sandbox,
        string attackName,
        bool acceptOffer = true,
        int timeoutSeconds = 20)
    {
        var receiverArgs = new[]
        {
            "listen-receiver",
            "--port", "0",
            "--advertised-ip", "127.0.0.1",
            "--staging-dir", sandbox.StagingDir,
            "--dest-dir", sandbox.DestDir,
            "--decision-offer", acceptOffer ? "accept" : "decline",
            "--decision-enrollment", "decline",
            "--timeout-seconds", timeoutSeconds.ToString()
        };

        await using InteractiveDriverSession receiver = GlideProcessRunner.StartBackendDriverListener(sandbox, receiverArgs);
        JsonElement invEvt = await receiver.WaitForInvitationAsync(TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        string invitationUri = invEvt.GetProperty("invitationUri").GetString()!;

        ProcessRunResult attackResult = await GlideProcessRunner.RunBackendDriverAsync(
            sandbox,
            ["adversarial-send", "--attack", attackName, "--timeout-seconds", timeoutSeconds.ToString()],
            stdinLine: invitationUri,
            timeout: TimeSpan.FromSeconds(timeoutSeconds + 10)).ConfigureAwait(false);

        ProcessRunResult receiverResult = await receiver.WaitForExitAsync(TimeSpan.FromSeconds(timeoutSeconds + 10)).ConfigureAwait(false);
        return (receiverResult, attackResult);
    }

    public static async Task<JsonObject> PerformWireHelloAsync(
        WireTlsSession tls,
        WireV1Invitation inv,
        string clientSessionId,
        string[]? requiredCapabilities = null,
        string displayName = "WireProbe-Sender",
        CancellationToken ct = default)
    {
        JsonObject bootRes = await WireClientProbe.SendBootstrapAsync(tls.Ssl, inv, clientSessionId, "sender", ct).ConfigureAwait(false);
        if (bootRes["status"]?.GetValue<string>() != "accepted")
        {
            throw new InvalidOperationException($"Bootstrap rejected: {bootRes.ToJsonString()}");
        }
        string serverSessionId = bootRes["sessionId"]!.GetValue<string>();

        var reqCaps = new JsonArray();
        foreach (string cap in requiredCapabilities ?? ["stream-v1", "sha256"])
        {
            reqCaps.Add(cap);
        }

        var helloBody = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = reqCaps,
            ["optionalCapabilities"] = new JsonArray(),
            ["role"] = "sender",
            ["ephemeralPeerId"] = Guid.NewGuid().ToString("D").ToLowerInvariant(),
            ["displayName"] = displayName
        };

        byte[] helloBytes = WireClientProbe.BuildEnvelopeBytes("HELLO", serverSessionId, 1, helloBody);
        await WireClientProbe.WriteLanRecordAsync(tls.Ssl, WireRecordKind.Control, helloBytes, ct).ConfigureAwait(false);
        JsonObject helloAck = await WireClientProbe.ReadControlEnvelopeAsync(tls.Ssl, ct).ConfigureAwait(false);
        if (helloAck["type"]?.GetValue<string>() != "HELLO_ACK")
        {
            throw new InvalidOperationException($"Expected HELLO_ACK, got {helloAck.ToJsonString()}");
        }
        return helloAck;
    }

    public static async Task<ProcessRunResult> RunVmCommandAsync(
        E2ESandbox sandbox,
        string sandboxId,
        string command,
        int timeoutSeconds = 45,
        bool useSystemPath = false)
    {
        TargetEnvironment.Current.RequireVmReady();
        var args = new List<string>
        {
            "-Action", "Exec",
            "-VmHost", TargetEnvironment.Current.VmHost,
            "-VmUser", TargetEnvironment.Current.VmUser,
            "-SandboxId", sandboxId,
            "-TimeoutSeconds", timeoutSeconds.ToString(),
            "-Command", command
        };
        if (useSystemPath)
        {
            args.Add("-UseSystemPath");
        }
        return await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Invoke-VmRemote.ps1",
            args,
            TimeSpan.FromSeconds(timeoutSeconds + 20)).ConfigureAwait(false);
    }

    public static async Task<ProcessRunResult> RunAndroidDriverAsync(
        E2ESandbox sandbox,
        IEnumerable<string> driverArgs,
        int timeoutSeconds = 45)
    {
        TargetEnvironment.Current.RequireAndroidReady();
        var args = new List<string> { "-Serial", TargetEnvironment.Current.AndroidSerial };
        args.AddRange(driverArgs);
        return await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Invoke-AndroidUi.ps1",
            args,
            TimeSpan.FromSeconds(timeoutSeconds)).ConfigureAwait(false);
    }

    public static void AssertResultSuccess(ProcessRunResult result, string role)
    {
        Assert.False(result.TimedOut, $"{role} timed out. Stderr: {result.Stderr}");
        Assert.Equal(0, result.ExitCode);
        JsonElement? ev = result.FindEvent("result");
        Assert.True(ev.HasValue, $"{role} did not emit result JSON event. Stdout: {result.Stdout}");
        Assert.True(ev.Value.GetProperty("success").GetBoolean(), $"{role} failed: {ev.Value.GetRawText()}");
    }

    public static async Task WaitForRogueServerCompletionAsync(
        Task serverTask,
        CancellationTokenSource serverCts,
        ProcessRunResult senderResult)
    {
        if (senderResult.ExitCode != 0 && !serverTask.IsCompleted)
        {
            Task finished = await Task.WhenAny(serverTask, Task.Delay(2000)).ConfigureAwait(false);
            if (finished != serverTask)
            {
                serverCts.Cancel();
            }
        }
        try
        {
            await serverTask.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // Bounded cancellation when client aborted pre-TLS or failed early
        }
    }
}
