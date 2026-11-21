using System.Collections.Concurrent;
using System.Diagnostics;
using Glide.Core.Models;

namespace Glide.Core.Storage;

/// <summary>
/// Manages in-memory and on-disk active transfer session leases and performs bounded
/// startup cleanup of abandoned quarantine sessions and temporary commit files.
/// </summary>
public sealed class QuarantineLeaseManager
{
    public const string SessionLeaseFileName = ".glide-session.lease";
    public const string SameVolumeStagingSubdirectoryName = ".glide-staging";

    private const int MaxCommitCleanupEntries = 4_096;
    private const int MaxCommitCleanupDepth = 16;
    private const int MaxSessionCleanupEntries = 4_096;

    private static readonly TimeSpan AbandonedCommitAge = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan AbandonedQuarantineAge = TimeSpan.FromDays(1);
    private static readonly ConcurrentDictionary<string, int> ActiveSessionLeases = new(StringComparer.OrdinalIgnoreCase);

    private readonly string _stagingRoot;
    private readonly string _destinationRoot;

    public QuarantineLeaseManager(string stagingRoot, string destinationRoot)
    {
        _stagingRoot = Path.GetFullPath(stagingRoot);
        _destinationRoot = Path.GetFullPath(destinationRoot);
    }

    public IDisposable AcquireSessionLease(SessionId sessionId)
    {
        string key = GetSessionLeaseKey(_stagingRoot, sessionId.Value);
        ActiveSessionLeases.AddOrUpdate(key, 1, static (_, count) => count + 1);
        TryWriteSessionLeaseFile(_stagingRoot, sessionId);
        return new SessionLeaseScope(_stagingRoot, sessionId);
    }

    public void AcquireSessionLeaseReference(SessionId sessionId)
    {
        string key = GetSessionLeaseKey(_stagingRoot, sessionId.Value);
        ActiveSessionLeases.AddOrUpdate(key, 1, static (_, count) => count + 1);
        TryWriteSessionLeaseFile(_stagingRoot, sessionId);
    }

    public void ReleaseSessionLeaseReference(SessionId sessionId) =>
        ReleaseSessionLease(_stagingRoot, sessionId);

    public void DeleteSessionLeaseFile(SessionId sessionId) =>
        TryDeleteSessionLeaseFile(_stagingRoot, sessionId);

    private static string GetSessionLeaseKey(string stagingRoot, string sessionId) =>
        $"{Path.GetFullPath(stagingRoot)}::{sessionId}";

    private sealed class SessionLeaseScope(string stagingRoot, SessionId sessionId) : IDisposable
    {
        private int _disposed;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0)
            {
                return;
            }

            ReleaseSessionLease(stagingRoot, sessionId);
        }
    }

    internal static void ReleaseSessionLease(string stagingRoot, SessionId sessionId)
    {
        string key = GetSessionLeaseKey(stagingRoot, sessionId.Value);
        while (true)
        {
            if (!ActiveSessionLeases.TryGetValue(key, out int current))
            {
                break;
            }

            if (current <= 1)
            {
                if (ActiveSessionLeases.TryRemove(new KeyValuePair<string, int>(key, current)))
                {
                    TryDeleteSessionLeaseFile(stagingRoot, sessionId);
                    break;
                }
            }
            else if (ActiveSessionLeases.TryUpdate(key, current - 1, current))
            {
                break;
            }
        }
    }

    internal static void TryWriteSessionLeaseFile(string stagingRoot, SessionId sessionId)
    {
        try
        {
            string sessionDirectory = Path.Combine(stagingRoot, sessionId.Value);
            if (!Directory.Exists(sessionDirectory))
            {
                return;
            }

            string leasePath = Path.Combine(sessionDirectory, SessionLeaseFileName);
            using var proc = Process.GetCurrentProcess();
            long startTicks = GetProcessStartTicksSafe(proc);
            string payload = $"{Environment.ProcessId}|{startTicks}|{DateTime.UtcNow.Ticks}";
            File.WriteAllText(leasePath, payload);
        }
        catch
        {
            // Best-effort lease marker
        }
    }

    internal static void TryDeleteSessionLeaseFile(string stagingRoot, SessionId sessionId)
    {
        try
        {
            string sessionDirectory = Path.Combine(stagingRoot, sessionId.Value);
            string leasePath = Path.Combine(sessionDirectory, SessionLeaseFileName);
            if (File.Exists(leasePath))
            {
                File.Delete(leasePath);
            }
        }
        catch
        {
            // Best-effort lease cleanup
        }
    }

    public static bool IsSessionActivelyLeased(string stagingRoot, string sessionDirectory)
    {
        string sessionId = Path.GetFileName(sessionDirectory);
        string key = GetSessionLeaseKey(stagingRoot, sessionId);
        if (ActiveSessionLeases.TryGetValue(key, out int count) && count > 0)
        {
            return true;
        }

        string leasePath = Path.Combine(sessionDirectory, SessionLeaseFileName);
        if (!File.Exists(leasePath))
        {
            return false;
        }

        try
        {
            string content = File.ReadAllText(leasePath).Trim();
            string[] parts = content.Split('|');
            if (parts.Length >= 2 &&
                int.TryParse(parts[0], out int pid) &&
                long.TryParse(parts[1], out long expectedStartTicks))
            {
                if (pid == Environment.ProcessId)
                {
                    return ActiveSessionLeases.TryGetValue(key, out int localCount) && localCount > 0;
                }

                using Process proc = Process.GetProcessById(pid);
                if (proc.HasExited)
                {
                    return false;
                }

                long actualStartTicks = GetProcessStartTicksSafe(proc);
                return expectedStartTicks == 0 || actualStartTicks == 0 || actualStartTicks == expectedStartTicks;
            }
        }
        catch
        {
            // Process no longer exists or lease file unreadable -> not actively leased
        }

        return false;
    }

    private static long GetProcessStartTicksSafe(Process process)
    {
        try
        {
            return process.StartTime.ToUniversalTime().Ticks;
        }
        catch
        {
            return 0;
        }
    }

    public int PurgeAbandonedCommitFiles()
    {
        string root = Path.GetFullPath(_destinationRoot);
        if (!Directory.Exists(root)) return 0;
        QuarantineStagingStore.RejectLinkedAncestors(root);

        DateTime cutoff = DateTime.UtcNow - AbandonedCommitAge;
        var pending = new Stack<(string Path, int Depth)>();
        pending.Push((root, 0));
        int examined = 0;
        int removed = 0;

        while (pending.Count > 0 && examined < MaxCommitCleanupEntries)
        {
            (string directory, int depth) = pending.Pop();
            try
            {
                foreach (string entry in Directory.EnumerateFileSystemEntries(directory))
                {
                    if (++examined > MaxCommitCleanupEntries) break;
                    try
                    {
                        FileAttributes attributes = File.GetAttributes(entry);
                        if ((attributes & FileAttributes.ReparsePoint) != 0) continue;
                        if ((attributes & FileAttributes.Directory) != 0)
                        {
                            if (string.Equals(Path.GetFileName(entry), SameVolumeStagingSubdirectoryName, StringComparison.OrdinalIgnoreCase))
                            {
                                continue;
                            }

                            if (depth < MaxCommitCleanupDepth)
                                pending.Push((entry, depth + 1));
                            continue;
                        }

                        if (!IsCommitTemporaryFile(Path.GetFileName(entry)) ||
                            File.GetLastWriteTimeUtc(entry) > cutoff)
                            continue;

                        File.Delete(entry);
                        removed++;
                    }
                    catch (IOException) { }
                    catch (UnauthorizedAccessException) { }
                }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        return removed;
    }

    public int PurgeAbandonedQuarantineSessions()
    {
        string root = Path.GetFullPath(_stagingRoot);
        if (!Directory.Exists(root)) return 0;
        QuarantineStagingStore.RejectLinkedAncestors(root);

        DateTime cutoff = DateTime.UtcNow - AbandonedQuarantineAge;
        int examined = 0;
        int removed = 0;
        foreach (string sessionDirectory in Directory.EnumerateDirectories(root))
        {
            if (++examined > MaxSessionCleanupEntries) break;
            if (!IdentifierValidation.IsValidUuidV4(Path.GetFileName(sessionDirectory)))
                continue;

            try
            {
                if ((File.GetAttributes(sessionDirectory) & FileAttributes.ReparsePoint) != 0)
                    continue;

                if (IsSessionActivelyLeased(_stagingRoot, sessionDirectory))
                    continue;

                var quarantineFiles = new List<string>();
                string? leaseFile = null;
                bool safe = true;
                foreach (string entry in Directory.EnumerateFileSystemEntries(sessionDirectory))
                {
                    if (++examined > MaxSessionCleanupEntries)
                    {
                        safe = false;
                        break;
                    }

                    FileAttributes attrs = File.GetAttributes(entry);
                    if ((attrs & (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0)
                    {
                        safe = false;
                        break;
                    }

                    string fileName = Path.GetFileName(entry);
                    if (string.Equals(fileName, SessionLeaseFileName, StringComparison.Ordinal))
                    {
                        leaseFile = entry;
                        continue;
                    }

                    if (!IsQuarantineFile(fileName) ||
                        File.GetLastWriteTimeUtc(entry) > cutoff)
                    {
                        safe = false;
                        break;
                    }
                    quarantineFiles.Add(entry);
                }

                if (!safe || quarantineFiles.Count == 0) continue;
                foreach (string file in quarantineFiles)
                {
                    File.Delete(file);
                    removed++;
                }
                if (leaseFile != null && File.Exists(leaseFile))
                {
                    File.Delete(leaseFile);
                }
                Directory.Delete(sessionDirectory, recursive: false);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
        return removed;
    }

    private static bool IsQuarantineFile(string name)
    {
        const string suffix = ".quarantine";
        return name.EndsWith(suffix, StringComparison.Ordinal) &&
            IdentifierValidation.IsValidUuidV4(name[..^suffix.Length]);
    }

    private static bool IsCommitTemporaryFile(string name)
    {
        const string prefix = ".glide-commit-";
        const string suffix = ".part";
        if (name.Length != prefix.Length + 32 + suffix.Length ||
            !name.StartsWith(prefix, StringComparison.Ordinal) ||
            !name.EndsWith(suffix, StringComparison.Ordinal))
            return false;
        foreach (char value in name.AsSpan(prefix.Length, 32))
            if (value is not (>= '0' and <= '9' or >= 'a' and <= 'f'))
                return false;
        return true;
    }
}
