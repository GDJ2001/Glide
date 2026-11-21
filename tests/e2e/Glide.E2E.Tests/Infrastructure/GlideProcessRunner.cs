using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace Glide.E2E.Tests.Infrastructure;

public sealed record ProcessRunResult(
    int ExitCode,
    string Stdout,
    string Stderr,
    IReadOnlyList<JsonElement> Events,
    bool TimedOut,
    string RawStdout = "",
    string RawStderr = "")
{
    public JsonElement? FindEvent(string eventName)
    {
        foreach (JsonElement elem in Events)
        {
            if (elem.TryGetProperty("event", out JsonElement ev) &&
                string.Equals(ev.GetString(), eventName, StringComparison.Ordinal))
            {
                return elem;
            }
        }
        return null;
    }
}

public sealed class InteractiveDriverSession : IAsyncDisposable
{
    private readonly Process _process;
    private readonly List<string> _stdoutLines = [];
    private readonly List<string> _stderrLines = [];
    private readonly object _sync = new();
    private readonly Task _stdoutTask;
    private readonly Task _stderrTask;
    private readonly TaskCompletionSource<JsonElement> _invitationTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);

    internal InteractiveDriverSession(Process process)
    {
        _process = process;
        _stdoutTask = Task.Run(ReadStdoutLoopAsync);
        _stderrTask = Task.Run(ReadStderrLoopAsync);
    }

    public async Task<JsonElement> WaitForInvitationAsync(TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        using var reg = cts.Token.Register(() => _invitationTcs.TrySetCanceled(cts.Token));
        return await _invitationTcs.Task.ConfigureAwait(false);
    }

    public async Task<ProcessRunResult> WaitForExitAsync(TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        bool timedOut = false;
        try
        {
            await _process.WaitForExitAsync(cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            timedOut = true;
            try { _process.Kill(entireProcessTree: true); } catch { }
        }

        await Task.WhenAll(
            Task.WhenAny(_stdoutTask, Task.Delay(1500)),
            Task.WhenAny(_stderrTask, Task.Delay(1500))).ConfigureAwait(false);

        List<string> outCopy;
        List<string> errCopy;
        lock (_sync)
        {
            outCopy = [.. _stdoutLines];
            errCopy = [.. _stderrLines];
        }

        string rawOut = string.Join(Environment.NewLine, outCopy);
        string rawErr = string.Join(Environment.NewLine, errCopy);
        var events = GlideProcessRunner.ParseJsonEvents(outCopy);
        return new ProcessRunResult(
            timedOut ? -1 : _process.ExitCode,
            E2ESandbox.RedactSecrets(rawOut),
            E2ESandbox.RedactSecrets(rawErr),
            events,
            timedOut,
            rawOut,
            rawErr);
    }

    public void KillNow()
    {
        try
        {
            if (!_process.HasExited)
            {
                _process.Kill(entireProcessTree: true);
            }
        }
        catch { }
    }

    private async Task ReadStdoutLoopAsync()
    {
        while (true)
        {
            string? line = await _process.StandardOutput.ReadLineAsync().ConfigureAwait(false);
            if (line == null) break;
            lock (_sync)
            {
                _stdoutLines.Add(line);
            }

            if (line.TrimStart().StartsWith('{'))
            {
                try
                {
                    using var doc = JsonDocument.Parse(line);
                    if (doc.RootElement.TryGetProperty("event", out JsonElement ev) &&
                        ev.GetString() == "invitation")
                    {
                        _invitationTcs.TrySetResult(doc.RootElement.Clone());
                    }
                }
                catch { }
            }
        }

        if (!_invitationTcs.Task.IsCompleted)
        {
            await Task.WhenAny(_stderrTask, Task.Delay(300)).ConfigureAwait(false);
            List<string> errCopy;
            lock (_sync)
            {
                errCopy = [.. _stderrLines];
            }
            _invitationTcs.TrySetException(new InvalidOperationException(
                $"Driver exited before emitting invitation. Stderr: {E2ESandbox.RedactSecrets(string.Join(" | ", errCopy))}"));
        }
    }

    private async Task ReadStderrLoopAsync()
    {
        while (true)
        {
            string? line = await _process.StandardError.ReadLineAsync().ConfigureAwait(false);
            if (line == null) break;
            lock (_sync)
            {
                _stderrLines.Add(line);
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        KillNow();
        await Task.WhenAny(_stdoutTask, Task.Delay(500)).ConfigureAwait(false);
        await Task.WhenAny(_stderrTask, Task.Delay(500)).ConfigureAwait(false);
        _process.Dispose();
    }
}

public static class GlideProcessRunner
{
    public static InteractiveDriverSession StartBackendDriverListener(
        E2ESandbox sandbox,
        IEnumerable<string> args,
        IReadOnlyDictionary<string, string>? envOverrides = null)
    {
        string driverDll = TargetEnvironment.Current.ResolveBackendTestDriverDll();
        var psi = CreateDotnetProcessStartInfo(driverDll, args, sandbox, envOverrides);
        var process = Process.Start(psi) ?? throw new InvalidOperationException("Failed to start Glide.BackendTestDriver.");
        sandbox.TrackProcess(process);
        return new InteractiveDriverSession(process);
    }

    public static Task<ProcessRunResult> RunBackendDriverAsync(
        E2ESandbox sandbox,
        IEnumerable<string> args,
        string? stdinLine = null,
        TimeSpan? timeout = null,
        IReadOnlyDictionary<string, string>? envOverrides = null)
    {
        string driverDll = TargetEnvironment.Current.ResolveBackendTestDriverDll();
        return RunDotnetAsync(sandbox, driverDll, args, stdinLine, timeout ?? TimeSpan.FromSeconds(90), envOverrides);
    }

    public static Task<ProcessRunResult> RunGlideAppAsync(
        E2ESandbox sandbox,
        IEnumerable<string> args,
        string? stdinLine = null,
        TimeSpan? timeout = null,
        IReadOnlyDictionary<string, string>? envOverrides = null)
    {
        string appDll = TargetEnvironment.Current.ResolveGlideAppDll();
        return RunDotnetAsync(sandbox, appDll, args, stdinLine, timeout ?? TimeSpan.FromSeconds(30), envOverrides);
    }

    public static async Task<ProcessRunResult> RunPowerShellDriverAsync(
        E2ESandbox sandbox,
        string driverFileName,
        IEnumerable<string> args,
        TimeSpan? timeout = null)
    {
        string scriptPath = TargetEnvironment.Current.ResolveDriverScript(driverFileName);
        var psi = new ProcessStartInfo("pwsh")
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = TargetEnvironment.Current.RepoRoot
        };
        psi.ArgumentList.Add("-NoProfile");
        psi.ArgumentList.Add("-File");
        psi.ArgumentList.Add(scriptPath);
        foreach (string a in args)
        {
            psi.ArgumentList.Add(a);
        }

        InjectSandboxEnvironment(psi, sandbox, null);
        return await ExecuteProcessAsync(psi, sandbox, null, timeout ?? TimeSpan.FromSeconds(60)).ConfigureAwait(false);
    }

    private static async Task<ProcessRunResult> RunDotnetAsync(
        E2ESandbox sandbox,
        string dllPath,
        IEnumerable<string> args,
        string? stdinLine,
        TimeSpan timeout,
        IReadOnlyDictionary<string, string>? envOverrides)
    {
        var psi = CreateDotnetProcessStartInfo(dllPath, args, sandbox, envOverrides);
        return await ExecuteProcessAsync(psi, sandbox, stdinLine, timeout).ConfigureAwait(false);
    }

    private static ProcessStartInfo CreateDotnetProcessStartInfo(
        string dllPath,
        IEnumerable<string> args,
        E2ESandbox sandbox,
        IReadOnlyDictionary<string, string>? envOverrides)
    {
        string dotnetExe = ResolveDotnetExe();
        var psi = new ProcessStartInfo(dotnetExe)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = sandbox.RootDir
        };
        psi.ArgumentList.Add(dllPath);
        foreach (string a in args)
        {
            psi.ArgumentList.Add(a);
        }

        InjectSandboxEnvironment(psi, sandbox, envOverrides);
        return psi;
    }

    private static async Task<ProcessRunResult> ExecuteProcessAsync(
        ProcessStartInfo psi,
        E2ESandbox sandbox,
        string? stdinLine,
        TimeSpan timeout)
    {
        using var process = Process.Start(psi) ?? throw new InvalidOperationException($"Failed to start {psi.FileName}.");
        sandbox.TrackProcess(process);

        if (stdinLine != null)
        {
            await process.StandardInput.WriteLineAsync(stdinLine).ConfigureAwait(false);
            await process.StandardInput.FlushAsync().ConfigureAwait(false);
        }
        process.StandardInput.Close();

        Task<string> stdoutTask = process.StandardOutput.ReadToEndAsync();
        Task<string> stderrTask = process.StandardError.ReadToEndAsync();

        using var cts = new CancellationTokenSource(timeout);
        bool timedOut = false;
        try
        {
            await process.WaitForExitAsync(cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            timedOut = true;
            try { process.Kill(entireProcessTree: true); } catch { }
        }

        await Task.WhenAll(
            Task.WhenAny(stdoutTask, Task.Delay(1500)),
            Task.WhenAny(stderrTask, Task.Delay(1500))).ConfigureAwait(false);

        string stdout = stdoutTask.IsCompletedSuccessfully ? stdoutTask.Result : string.Empty;
        string stderr = stderrTask.IsCompletedSuccessfully ? stderrTask.Result : string.Empty;
        string[] lines = stdout.Split(["\r\n", "\n"], StringSplitOptions.RemoveEmptyEntries);
        var events = ParseJsonEvents(lines);

        return new ProcessRunResult(
            timedOut ? -1 : process.ExitCode,
            E2ESandbox.RedactSecrets(stdout),
            E2ESandbox.RedactSecrets(stderr),
            events,
            timedOut,
            stdout,
            stderr);
    }

    internal static IReadOnlyList<JsonElement> ParseJsonEvents(IEnumerable<string> lines)
    {
        var events = new List<JsonElement>();
        foreach (string raw in lines)
        {
            string trimmed = raw.Trim();
            if (!trimmed.StartsWith('{') || !trimmed.EndsWith('}'))
            {
                continue;
            }
            try
            {
                using var doc = JsonDocument.Parse(trimmed);
                events.Add(doc.RootElement.Clone());
            }
            catch
            {
                // Ignore non-JSON lines
            }
        }
        return events;
    }

    private static void InjectSandboxEnvironment(
        ProcessStartInfo psi,
        E2ESandbox sandbox,
        IReadOnlyDictionary<string, string>? envOverrides)
    {
        psi.Environment["GLIDE_DIAGNOSTIC_LOG_PATH"] = sandbox.DiagnosticLogPath;
        if (OperatingSystem.IsLinux())
        {
            psi.Environment["HOME"] = sandbox.RootDir;
            psi.Environment["XDG_CONFIG_HOME"] = sandbox.ConfigDir;
            psi.Environment["XDG_DATA_HOME"] = sandbox.DataDir;
            psi.Environment["XDG_STATE_HOME"] = sandbox.StateDir;
            psi.Environment["XDG_CACHE_HOME"] = sandbox.CacheDir;
            psi.Environment["XDG_RUNTIME_DIR"] = sandbox.RuntimeDir;
        }
        if (envOverrides != null)
        {
            foreach ((string key, string val) in envOverrides)
            {
                psi.Environment[key] = val;
            }
        }
    }

    private static string ResolveDotnetExe()
    {
        string? dotnetRoot = Environment.GetEnvironmentVariable("DOTNET_ROOT");
        if (!string.IsNullOrWhiteSpace(dotnetRoot))
        {
            string candidate = Path.Combine(dotnetRoot, OperatingSystem.IsWindows() ? "dotnet.exe" : "dotnet");
            if (File.Exists(candidate)) return candidate;
        }

        string userCandidate = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            ".dotnet",
            OperatingSystem.IsWindows() ? "dotnet.exe" : "dotnet");
        if (File.Exists(userCandidate)) return userCandidate;

        return "dotnet";
    }
}
