using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace Glide.E2E.Tests.Infrastructure;

public sealed partial class E2ESandbox : IAsyncDisposable
{
    private readonly List<Process> _trackedProcesses = [];
    private readonly object _lock = new();
    private bool _preserveOnExit;

    public string TestId { get; }
    public string RootDir { get; }
    public string SourceDir { get; }
    public string StagingDir { get; }
    public string DestDir { get; }
    public string ConfigDir { get; }
    public string DataDir { get; }
    public string StateDir { get; }
    public string CacheDir { get; }
    public string RuntimeDir { get; }
    public string LogsDir { get; }
    public string TrustStorePath { get; }
    public string IdentityStorePath { get; }
    public string DiagnosticLogPath { get; }

    public E2ESandbox(string testId)
    {
        TestId = testId;
        string runId = TargetEnvironment.Current.RunId;
        string suffix = Guid.NewGuid().ToString("N")[..8];
        RootDir = Path.Combine(Path.GetTempPath(), "glide-e2e", runId, $"{testId}-{suffix}");
        SourceDir = Path.Combine(RootDir, "src");
        StagingDir = Path.Combine(RootDir, "staging");
        DestDir = Path.Combine(RootDir, "dest");
        ConfigDir = Path.Combine(RootDir, "config");
        DataDir = Path.Combine(RootDir, "data");
        StateDir = Path.Combine(RootDir, "state");
        CacheDir = Path.Combine(RootDir, "cache");
        RuntimeDir = Path.Combine(RootDir, "runtime");
        LogsDir = Path.Combine(RootDir, "logs");

        Directory.CreateDirectory(SourceDir);
        Directory.CreateDirectory(StagingDir);
        Directory.CreateDirectory(DestDir);
        Directory.CreateDirectory(ConfigDir);
        Directory.CreateDirectory(DataDir);
        Directory.CreateDirectory(StateDir);
        Directory.CreateDirectory(CacheDir);
        Directory.CreateDirectory(RuntimeDir);
        Directory.CreateDirectory(LogsDir);

        TrustStorePath = Path.Combine(ConfigDir, "trust-v1.json");
        IdentityStorePath = Path.Combine(ConfigDir, "identity-v1.json");
        DiagnosticLogPath = Path.Combine(LogsDir, "glide-diagnostics.jsonl");
        _preserveOnExit = TargetEnvironment.Current.KeepArtifacts;
    }

    public string CreateSubDir(string name)
    {
        string path = Path.Combine(RootDir, name);
        Directory.CreateDirectory(path);
        return path;
    }

    public static int AllocateEphemeralPort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    public void TrackProcess(Process process)
    {
        lock (_lock)
        {
            _trackedProcesses.Add(process);
        }
    }

    public void MarkPreserveArtifacts() => _preserveOnExit = true;

    public void AssertStagingClean() => AssertDirectoryCleanOfStagingArtifacts(StagingDir);

    public static void AssertDirectoryCleanOfStagingArtifacts(string stagingDir)
    {
        if (!Directory.Exists(stagingDir))
        {
            return;
        }

        string[] leftovers = Directory.GetFiles(stagingDir, "*", SearchOption.AllDirectories)
            .Where(f =>
            {
                string name = Path.GetFileName(f);
                return name.EndsWith(".part", StringComparison.OrdinalIgnoreCase)
                    || name.EndsWith(".quarantine", StringComparison.OrdinalIgnoreCase)
                    || name.EndsWith(".lease", StringComparison.OrdinalIgnoreCase);
            })
            .ToArray();

        if (leftovers.Length > 0)
        {
            throw new InvalidOperationException(
                $"Staging directory '{stagingDir}' is not clean; found leftover staging files: {string.Join(", ", leftovers)}");
        }

        string[] anyFiles = Directory.GetFiles(stagingDir, "*", SearchOption.AllDirectories);
        if (anyFiles.Length > 0)
        {
            throw new InvalidOperationException(
                $"Staging directory '{stagingDir}' still contains files: {string.Join(", ", anyFiles)}");
        }
    }

    public static string RedactSecrets(string? input)
    {
        if (string.IsNullOrEmpty(input))
        {
            return string.Empty;
        }

        string redacted = PairUriRegex().Replace(input, "glide://pair/$1#[REDACTED]");
        redacted = TokenJsonRegex().Replace(redacted, "$1[REDACTED]$2");
        redacted = EscapedTokenJsonRegex().Replace(redacted, "$1[REDACTED]$2");
        redacted = XmlQuotTokenJsonRegex().Replace(redacted, "$1[REDACTED]$2");
        redacted = DoubleEscapedXmlQuotTokenJsonRegex().Replace(redacted, "$1[REDACTED]$2");
        return redacted;
    }

    public async ValueTask DisposeAsync()
    {
        List<Process> snapshot;
        lock (_lock)
        {
            snapshot = [.. _trackedProcesses];
            _trackedProcesses.Clear();
        }

        foreach (Process proc in snapshot)
        {
            try
            {
                if (!proc.HasExited)
                {
                    proc.Kill(entireProcessTree: true);
                    using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                    await proc.WaitForExitAsync(cts.Token).ConfigureAwait(false);
                }
            }
            catch
            {
                // Best-effort cleanup
            }
            finally
            {
                proc.Dispose();
            }
        }

        if (!_preserveOnExit && Directory.Exists(RootDir))
        {
            for (int attempt = 0; attempt < 5; attempt++)
            {
                try
                {
                    Directory.Delete(RootDir, recursive: true);
                    break;
                }
                catch when (attempt < 4)
                {
                    await Task.Delay(100).ConfigureAwait(false);
                }
                catch
                {
                    // Best-effort temp cleanup
                }
            }

            string? runDir = Path.GetDirectoryName(RootDir);
            if (!string.IsNullOrEmpty(runDir) && Directory.Exists(runDir))
            {
                try
                {
                    if (!Directory.EnumerateFileSystemEntries(runDir).Any())
                    {
                        Directory.Delete(runDir, recursive: false);
                    }
                }
                catch
                {
                    // Best-effort parent pruning
                }
            }
        }
    }

    [GeneratedRegex(@"glide://pair/(v\d+)#[A-Za-z0-9_-]+", RegexOptions.CultureInvariant)]
    private static partial Regex PairUriRegex();

    [GeneratedRegex(@"(""token""\s*:\s*"")[^""]+("")", RegexOptions.CultureInvariant)]
    private static partial Regex TokenJsonRegex();

    [GeneratedRegex(@"(\\""token\\""\s*:\s*\\"")[^""\\]+(\\"")", RegexOptions.CultureInvariant)]
    private static partial Regex EscapedTokenJsonRegex();

    [GeneratedRegex(@"(&quot;token&quot;\s*:\s*&quot;)[^&<]+(&quot;)", RegexOptions.CultureInvariant)]
    private static partial Regex XmlQuotTokenJsonRegex();

    [GeneratedRegex(@"(&amp;quot;token&amp;quot;\s*:\s*&amp;quot;)[^&<]+(&amp;quot;)", RegexOptions.CultureInvariant)]
    private static partial Regex DoubleEscapedXmlQuotTokenJsonRegex();
}
