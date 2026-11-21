using System.Text.Json;

namespace Glide.E2E.Tests.Infrastructure;

public sealed class WindowsUiSession : IAsyncDisposable
{
    private readonly E2ESandbox _sandbox;
    private readonly string _exePath;
    private readonly string _sessionFile;
    private readonly string _backupDir;
    public int ListenerPort { get; }

    private WindowsUiSession(E2ESandbox sandbox, string exePath, string sessionFile, string backupDir, int listenerPort)
    {
        _sandbox = sandbox;
        _exePath = exePath;
        _sessionFile = sessionFile;
        _backupDir = backupDir;
        ListenerPort = listenerPort;
    }

    public static async Task<WindowsUiSession> StartAsync(
        E2ESandbox sandbox,
        bool cleanAppData = true,
        bool seedState = true,
        int listenerPort = 0,
        string? receiveDirectory = null,
        IReadOnlyList<string>? activationArgs = null)
    {
        TargetEnvironment.Current.RequireWindowsUiReady();
        string exePath = TargetEnvironment.Current.ResolveWindowsUiExe();
        string sessionFile = Path.Combine(sandbox.RootDir, "winui-session.json");
        string backupDir = Path.Combine(sandbox.RootDir, "appdata-backup");
        int effectivePort = listenerPort > 0 ? listenerPort : E2ESandbox.AllocateEphemeralPort();
        string effectiveRecv = receiveDirectory ?? sandbox.DestDir;

        var args = new List<string>
        {
            "-Action", "StartSession",
            "-ExePath", exePath,
            "-SessionFile", sessionFile,
            "-BackupDir", backupDir,
            "-DiagnosticLogPath", sandbox.DiagnosticLogPath,
            "-TimeoutSeconds", "20"
        };
        if (cleanAppData) args.Add("-CleanAppData");
        if (seedState)
        {
            args.Add("-SeedState");
            args.Add("-ReceiveDirectory");
            args.Add(effectiveRecv);
            args.Add("-ListenerPort");
            args.Add(effectivePort.ToString());
        }
        if (activationArgs != null && activationArgs.Count > 0)
        {
            args.Add("-ActivationArgs");
            args.AddRange(activationArgs);
        }

        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            sandbox, "Invoke-WindowsUi.ps1", args, TimeSpan.FromSeconds(35)).ConfigureAwait(false);
        if (res.ExitCode != 0)
        {
            throw new InvalidOperationException($"Failed to start WindowsUiSession: {res.Stderr}\n{res.Stdout}");
        }

        return new WindowsUiSession(sandbox, exePath, sessionFile, backupDir, effectivePort);
    }

    public async Task SwitchTabAsync(string navAutomationId, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("SwitchTab", automationId: navAutomationId, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task ClickByIdAsync(string automationId, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("Click", automationId: automationId, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task ClickByNameAsync(string name, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("Click", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task WaitForIdAsync(string automationId, int timeoutSeconds = 15)
    {
        await RunUiActionAsync("WaitFor", automationId: automationId, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task WaitForNameAsync(string name, int timeoutSeconds = 15)
    {
        await RunUiActionAsync("WaitFor", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task AssertVisibleByIdAsync(string automationId, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("AssertVisible", automationId: automationId, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task AssertVisibleByNameAsync(string name, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("AssertVisible", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task AssertAbsentByIdAsync(string automationId, int timeoutSeconds = 5)
    {
        await RunUiActionAsync("AssertAbsent", automationId: automationId, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task AssertAbsentByNameAsync(string name, int timeoutSeconds = 5)
    {
        await RunUiActionAsync("AssertAbsent", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task SetTextByIdAsync(string automationId, string value, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("SetText", automationId: automationId, value: value, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task SelectComboByNameAsync(string name, string itemValue, int timeoutSeconds = 10)
    {
        await RunUiActionAsync("SelectCombo", name: name, value: itemValue, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
    }

    public async Task<string> GetTextByNameAsync(string name, int timeoutSeconds = 10)
    {
        JsonElement json = await RunUiActionAsync("GetText", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
        return json.GetProperty("text").GetString() ?? "";
    }

    public async Task<string> GetValueByNameAsync(string name, int timeoutSeconds = 10)
    {
        JsonElement json = await RunUiActionAsync("GetValue", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
        return json.GetProperty("value").GetString() ?? "";
    }

    public async Task<string> GetComboSelectionByNameAsync(string name, int timeoutSeconds = 10)
    {
        JsonElement json = await RunUiActionAsync("GetComboSelection", name: name, timeoutSeconds: timeoutSeconds).ConfigureAwait(false);
        return json.GetProperty("selected").GetString() ?? "";
    }

    public async Task<string> DumpTreeAsync()
    {
        JsonElement json = await RunUiActionAsync("DumpTree", timeoutSeconds: 10).ConfigureAwait(false);
        return json.GetProperty("tree").GetString() ?? "";
    }

    public async Task<string> ReadClipboardAsync()
    {
        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            _sandbox, "Invoke-WindowsUi.ps1", ["-Action", "ReadClipboard"], TimeSpan.FromSeconds(15)).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
        using var doc = JsonDocument.Parse(res.RawStdout);
        return doc.RootElement.GetProperty("text").GetString() ?? "";
    }

    public async Task<int> ForwardActivationAsync(params string[] activationArgs)
    {
        var args = new List<string>
        {
            "-Action", "ForwardActivation",
            "-ExePath", _exePath,
            "-TimeoutSeconds", "10"
        };
        if (activationArgs.Length > 0)
        {
            args.Add("-ActivationArgs");
            args.AddRange(activationArgs);
        }
        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            _sandbox, "Invoke-WindowsUi.ps1", args, TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        if (res.ExitCode != 0)
        {
            throw new InvalidOperationException($"ForwardActivation failed: {res.Stderr}\n{res.Stdout}");
        }
        using var doc = JsonDocument.Parse(res.RawStdout);
        return doc.RootElement.GetProperty("exitCode").GetInt32();
    }

    private async Task<JsonElement> RunUiActionAsync(
        string uiCommand,
        string? automationId = null,
        string? name = null,
        string? value = null,
        int timeoutSeconds = 10)
    {
        var args = new List<string>
        {
            "-Action", "UiAction",
            "-SessionFile", _sessionFile,
            "-UiCommand", uiCommand,
            "-FailureLogDir", _sandbox.LogsDir,
            "-TimeoutSeconds", timeoutSeconds.ToString()
        };
        if (!string.IsNullOrWhiteSpace(automationId)) { args.Add("-AutomationId"); args.Add(automationId); }
        if (!string.IsNullOrWhiteSpace(name)) { args.Add("-Name"); args.Add(name); }
        if (value != null) { args.Add("-Value"); args.Add(value); }

        ProcessRunResult res = await GlideProcessRunner.RunPowerShellDriverAsync(
            _sandbox, "Invoke-WindowsUi.ps1", args, TimeSpan.FromSeconds(timeoutSeconds + 15)).ConfigureAwait(false);
        if (res.ExitCode != 0)
        {
            throw new InvalidOperationException($"UI action '{uiCommand}' (id='{automationId}', name='{name}') failed: {res.Stderr}\n{res.Stdout}");
        }
        using var doc = JsonDocument.Parse(res.RawStdout);
        return doc.RootElement.Clone();
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            await GlideProcessRunner.RunPowerShellDriverAsync(
                _sandbox,
                "Invoke-WindowsUi.ps1",
                ["-Action", "StopSession", "-SessionFile", _sessionFile, "-BackupDir", _backupDir],
                TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        }
        catch
        {
            // Best-effort restore
        }
    }
}
