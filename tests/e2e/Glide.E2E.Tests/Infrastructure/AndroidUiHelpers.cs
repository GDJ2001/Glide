using System.Text.Json;

namespace Glide.E2E.Tests.Infrastructure;

public static class AndroidUiHelpers
{
    public static async Task LaunchDebugStateAsync(
        E2ESandbox sandbox,
        string debugState,
        string? debugTheme = null,
        string? debugPosture = null,
        bool noRestart = false)
    {
        var args = new List<string> { "-Action", "LaunchDebugState", "-DebugState", debugState };
        if (!string.IsNullOrWhiteSpace(debugTheme)) { args.Add("-DebugTheme"); args.Add(debugTheme); }
        if (!string.IsNullOrWhiteSpace(debugPosture)) { args.Add("-DebugPosture"); args.Add(debugPosture); }
        if (noRestart) args.Add("-NoRestart");
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(sandbox, args).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
    }

    public static async Task ShareSendAsync(
        E2ESandbox sandbox,
        string contentUri,
        string mimeType = "*/*",
        bool noRestart = false)
    {
        var args = new List<string>
        {
            "-Action", "ShareSend",
            "-ContentUri", contentUri,
            "-MimeType", mimeType
        };
        if (noRestart) args.Add("-NoRestart");
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(sandbox, args).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
    }

    public static async Task ShareTextAsync(E2ESandbox sandbox, string text, bool noRestart = false)
    {
        var args = new List<string> { "-Action", "ShareText", "-Text", text };
        if (noRestart) args.Add("-NoRestart");
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(sandbox, args).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
    }

    public static async Task<string> DumpUiAsync(E2ESandbox sandbox)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(sandbox, ["-Action", "DumpUi"]).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
        Assert.Contains("<hierarchy", res.RawStdout);
        return res.RawStdout;
    }

    public static async Task AssertUiNodeAsync(
        E2ESandbox sandbox,
        string? textExact = null,
        string? textContains = null,
        string? contentDescContains = null,
        string? resourceId = null,
        int index = 0,
        bool absent = false,
        int timeoutSeconds = 15,
        bool ignoreCase = false)
    {
        var args = new List<string>
        {
            "-Action", "AssertUiNode",
            "-Index", index.ToString(),
            "-TimeoutSeconds", timeoutSeconds.ToString()
        };
        if (!string.IsNullOrEmpty(textExact)) { args.Add("-TextExact"); args.Add(textExact); }
        if (!string.IsNullOrEmpty(textContains)) { args.Add("-TextContains"); args.Add(textContains); }
        if (!string.IsNullOrEmpty(contentDescContains)) { args.Add("-ContentDescContains"); args.Add(contentDescContains); }
        if (!string.IsNullOrEmpty(resourceId)) { args.Add("-ResourceId"); args.Add(resourceId); }
        if (ignoreCase) args.Add("-IgnoreCase");
        if (absent) args.Add("-Absent");

        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, args, timeoutSeconds: timeoutSeconds + 15).ConfigureAwait(false);
        Assert.True(res.ExitCode == 0, $"AssertUiNode failed: {res.Stderr}\n{res.Stdout}");
    }

    public static async Task TapNodeAsync(
        E2ESandbox sandbox,
        string? textExact = null,
        string? textContains = null,
        string? contentDescContains = null,
        string? resourceId = null,
        int index = 0,
        int timeoutSeconds = 15,
        bool ignoreCase = false)
    {
        var args = new List<string>
        {
            "-Action", "TapNode",
            "-Index", index.ToString(),
            "-TimeoutSeconds", timeoutSeconds.ToString()
        };
        if (!string.IsNullOrEmpty(textExact)) { args.Add("-TextExact"); args.Add(textExact); }
        if (!string.IsNullOrEmpty(textContains)) { args.Add("-TextContains"); args.Add(textContains); }
        if (!string.IsNullOrEmpty(contentDescContains)) { args.Add("-ContentDescContains"); args.Add(contentDescContains); }
        if (!string.IsNullOrEmpty(resourceId)) { args.Add("-ResourceId"); args.Add(resourceId); }
        if (ignoreCase) args.Add("-IgnoreCase");

        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, args, timeoutSeconds: timeoutSeconds + 15).ConfigureAwait(false);
        Assert.True(res.ExitCode == 0, $"TapNode failed: {res.Stderr}\n{res.Stdout}");
    }

    public static async Task SwipeScrollAsync(E2ESandbox sandbox, string direction = "Down")
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, ["-Action", "SwipeScroll", "-Direction", direction]).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
    }

    public static async Task InputTextAsync(E2ESandbox sandbox, string text)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, ["-Action", "InputText", "-Text", text]).ConfigureAwait(false);
        Assert.True(res.ExitCode == 0, $"InputText failed: {res.Stderr}\n{res.Stdout}");
    }

    public static async Task<string> PushAndIndexFileAsync(E2ESandbox sandbox, string localPath, string devicePath)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox,
            ["-Action", "PushAndIndexFile", "-LocalPath", localPath, "-DevicePath", devicePath]).ConfigureAwait(false);
        Assert.True(res.ExitCode == 0, $"PushAndIndexFile failed: {res.Stderr}\n{res.Stdout}");
        using var doc = JsonDocument.Parse(res.RawStdout);
        return doc.RootElement.GetProperty("contentUri").GetString()!;
    }

    public static async Task GrantPermissionsAsync(E2ESandbox sandbox)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, ["-Action", "GrantPermissions"]).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
    }

    public static async Task<(bool ServiceActive, bool NotificationFound, string NotificationText)> CheckServiceRunningAsync(E2ESandbox sandbox)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, ["-Action", "CheckServiceRunning"]).ConfigureAwait(false);
        Assert.Equal(0, res.ExitCode);
        using var doc = JsonDocument.Parse(res.RawStdout);
        return (
            doc.RootElement.GetProperty("serviceActive").GetBoolean(),
            doc.RootElement.GetProperty("notificationFound").GetBoolean(),
            doc.RootElement.GetProperty("notificationText").GetString() ?? "");
    }

    public static async Task<(long SizeBytes, string Sha256)> HashDeviceFileAsync(E2ESandbox sandbox, string devicePath)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, ["-Action", "HashDeviceFile", "-DevicePath", devicePath]).ConfigureAwait(false);
        Assert.True(res.ExitCode == 0, $"HashDeviceFile failed: {res.Stderr}\n{res.Stdout}");
        using var doc = JsonDocument.Parse(res.RawStdout);
        return (
            doc.RootElement.GetProperty("sizeBytes").GetInt64(),
            doc.RootElement.GetProperty("sha256").GetString()!);
    }

    public static async Task<string> RunAsExecAsync(E2ESandbox sandbox, string command)
    {
        ProcessRunResult res = await E2ETestHelpers.RunAndroidDriverAsync(
            sandbox, ["-Action", "RunAsExec", "-Command", command]).ConfigureAwait(false);
        Assert.True(res.ExitCode == 0, $"RunAsExec failed: {res.Stderr}\n{res.Stdout}");
        using var doc = JsonDocument.Parse(res.RawStdout);
        return doc.RootElement.GetProperty("output").GetString() ?? "";
    }

    public static async Task CleanupAsync(E2ESandbox sandbox, string? devicePath = null)
    {
        var args = new List<string> { "-Action", "Cleanup" };
        if (!string.IsNullOrWhiteSpace(devicePath)) { args.Add("-DevicePath"); args.Add(devicePath); }
        try
        {
            await E2ETestHelpers.RunAndroidDriverAsync(sandbox, args).ConfigureAwait(false);
        }
        catch { }
    }
}
