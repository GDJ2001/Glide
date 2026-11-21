using System.Net;
using System.Net.Sockets;
using System.Text.Json;

namespace Glide.E2E.Tests.Infrastructure;

public static class VmTestHelpers
{
    private static readonly SemaphoreSlim DeployGate = new(1, 1);
    private static string? _deployedRunId;

    public static async Task EnsureVmSuiteDeployedAsync(E2ESandbox sandbox)
    {
        TargetEnvironment.Current.RequireVmReady();
        string runId = TargetEnvironment.Current.RunId;
        if (string.Equals(_deployedRunId, runId, StringComparison.Ordinal))
        {
            return;
        }

        await DeployGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (string.Equals(_deployedRunId, runId, StringComparison.Ordinal))
            {
                return;
            }

            ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Invoke-VmRemote.ps1",
                [
                    "-Action", "DeploySuite",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser,
                    "-TimeoutSeconds", "120"
                ],
                TimeSpan.FromSeconds(135)).ConfigureAwait(false);

            if (res.ExitCode != 0)
            {
                throw new InvalidOperationException($"DeploySuite to VM failed: {res.Stderr}\n{res.Stdout}");
            }
            _deployedRunId = runId;
        }
        finally
        {
            DeployGate.Release();
        }
    }

    public static async Task DeployFileAsync(E2ESandbox sandbox, string sandboxId, string localPath, string remotePath)
    {
        TargetEnvironment.Current.RequireVmReady();
        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Invoke-VmRemote.ps1",
            [
                "-Action", "Deploy",
                "-VmHost", TargetEnvironment.Current.VmHost,
                "-VmUser", TargetEnvironment.Current.VmUser,
                "-SandboxId", sandboxId,
                "-LocalPath", localPath,
                "-RemotePath", remotePath
            ],
            TimeSpan.FromSeconds(60)).ConfigureAwait(false);
        if (res.ExitCode != 0)
        {
            throw new InvalidOperationException($"VM Deploy failed for '{localPath}' -> '{remotePath}': {res.Stderr}\n{res.Stdout}");
        }
    }

    public static async Task<(long SizeBytes, string Sha256)> HashFileAsync(E2ESandbox sandbox, string remotePath)
    {
        TargetEnvironment.Current.RequireVmReady();
        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox,
            "Invoke-VmRemote.ps1",
            [
                "-Action", "HashFile",
                "-VmHost", TargetEnvironment.Current.VmHost,
                "-VmUser", TargetEnvironment.Current.VmUser,
                "-RemotePath", remotePath
            ],
            TimeSpan.FromSeconds(45)).ConfigureAwait(false);
        if (res.ExitCode != 0)
        {
            throw new InvalidOperationException($"VM HashFile failed for '{remotePath}': {res.Stderr}\n{res.Stdout}");
        }
        using var doc = JsonDocument.Parse(res.RawStdout);
        return (
            doc.RootElement.GetProperty("sizeBytes").GetInt64(),
            doc.RootElement.GetProperty("sha256").GetString()!);
    }

    public static async Task CleanupSandboxAsync(E2ESandbox sandbox, string sandboxId)
    {
        try
        {
            await GlideProcessRunner.RunPowerShellDriverAsync(
                sandbox,
                "Invoke-VmRemote.ps1",
                [
                    "-Action", "Cleanup",
                    "-VmHost", TargetEnvironment.Current.VmHost,
                    "-VmUser", TargetEnvironment.Current.VmUser,
                    "-SandboxId", sandboxId
                ],
                TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        }
        catch { }
    }

    public static string ExtractOutput(ProcessRunResult res)
    {
        try
        {
            using var doc = JsonDocument.Parse(res.RawStdout);
            return doc.RootElement.GetProperty("output").GetString() ?? "";
        }
        catch
        {
            return res.Stdout;
        }
    }

    public sealed class TcpPortForwarder : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _cts = new();
        private readonly Task _acceptLoop;

        public int BoundPort { get; }

        public TcpPortForwarder(string targetHost, int targetPort)
        {
            _listener = new TcpListener(IPAddress.Any, 0);
            _listener.Start();
            BoundPort = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _acceptLoop = Task.Run(() => RunLoopAsync(targetHost, targetPort, _cts.Token));
        }

        private async Task RunLoopAsync(string targetHost, int targetPort, CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                TcpClient client;
                try
                {
                    client = await _listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                }
                catch
                {
                    break;
                }

                _ = Task.Run(async () =>
                {
                    using var c1 = client;
                    using var c2 = new TcpClient();
                    try
                    {
                        await c2.ConnectAsync(targetHost, targetPort, ct).ConfigureAwait(false);
                        await using NetworkStream s1 = c1.GetStream();
                        await using NetworkStream s2 = c2.GetStream();
                        await Task.WhenAny(s1.CopyToAsync(s2, ct), s2.CopyToAsync(s1, ct)).ConfigureAwait(false);
                    }
                    catch { }
                }, ct);
            }
        }

        public async ValueTask DisposeAsync()
        {
            _cts.Cancel();
            try { _listener.Stop(); } catch { }
            await Task.WhenAny(_acceptLoop, Task.Delay(1000)).ConfigureAwait(false);
            _cts.Dispose();
        }
    }
}
