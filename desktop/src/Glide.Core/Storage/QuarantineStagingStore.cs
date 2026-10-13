using System.Security.Cryptography;
using Glide.Core.Models;

namespace Glide.Core.Storage;

public sealed class QuarantineStagingStore
{
    private const int MaxCommitCleanupEntries = 4_096;
    private const int MaxCommitCleanupDepth = 16;
    private const int MaxSessionCleanupEntries = 4_096;
    private static readonly TimeSpan AbandonedCommitAge = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan AbandonedQuarantineAge = TimeSpan.FromDays(1);
    private readonly string _stagingRoot;
    private readonly string _destinationRoot;
    private readonly Func<long>? _freeSpaceProvider;
    private int _commitCleanupAttempted;

    public string StagingRoot => _stagingRoot;
    public string DestinationRoot => _destinationRoot;

    public QuarantineStagingStore(string? stagingRoot = null, string? destinationRoot = null, Func<long>? freeSpaceProvider = null)
    {
        _stagingRoot = Path.GetFullPath(stagingRoot ?? GetDefaultStagingRoot());

        _destinationRoot = destinationRoot ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads");

        _freeSpaceProvider = freeSpaceProvider;
    }

    private static string GetDefaultStagingRoot()
    {
        if (OperatingSystem.IsLinux())
        {
            string? xdgData = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            if (!string.IsNullOrWhiteSpace(xdgData))
            {
                return Path.Combine(xdgData, "glide", "staging");
            }
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Glide", "staging");
    }

    public long GetAvailableFreeSpaceBytes()
    {
        if (_freeSpaceProvider != null)
        {
            return _freeSpaceProvider();
        }

        try
        {
            string root =
                Path.GetPathRoot(_stagingRoot)
                ?? (OperatingSystem.IsWindows()
                    ? Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\"
                    : "/");
            return new DriveInfo(root).AvailableFreeSpace;
        }
        catch
        {
            // Unknown capacity is not evidence of enough capacity.
            return 0;
        }
    }

    /// <summary>
    /// Checks that verified files can be committed to the selected destination before
    /// an offer is accepted. Commit still revalidates the path and handles later changes.
    /// </summary>
    public async Task VerifyDestinationWritableAsync(CancellationToken cancellationToken = default)
    {
        string root = Path.GetFullPath(_destinationRoot);
        RejectLinkedAncestors(root);
        Directory.CreateDirectory(root);
        RejectLinkedAncestors(root);

        string probe = Path.Combine(root, $".glide-write-check-{Guid.NewGuid():N}.tmp");
        try
        {
            await using var stream = new FileStream(probe, FileMode.CreateNew, FileAccess.Write,
                FileShare.None, 1, FileOptions.Asynchronous | FileOptions.DeleteOnClose);
            await stream.WriteAsync(new byte[] { 0 }, cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }
        finally
        {
            // DeleteOnClose handles normal disposal; this covers platforms that leave a
            // probe behind after an interrupted open or flush.
            if (File.Exists(probe)) File.Delete(probe);
        }
    }

    public string GetQuarantinePath(SessionId sessionId, ItemId itemId)
    {
        return Path.Combine(_stagingRoot, sessionId.Value, $"{itemId.Value}.quarantine");
    }

    public FileStream OpenQuarantineWrite(SessionId sessionId, ItemId itemId)
    {
        if (Interlocked.Exchange(ref _commitCleanupAttempted, 1) == 0)
        {
            PurgeAbandonedCommitFiles();
            PurgeAbandonedQuarantineSessions();
        }
        string sessionDirectory = Path.Combine(_stagingRoot, sessionId.Value);
        EnsurePrivateDirectory(_stagingRoot);
        EnsurePrivateDirectory(sessionDirectory);
        string path = GetQuarantinePath(sessionId, itemId);
        var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write,
            FileShare.None, 65_536, useAsync: true);
        try
        {
            if (!OperatingSystem.IsWindows())
                File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            return stream;
        }
        catch
        {
            stream.Dispose();
            File.Delete(path);
            throw;
        }
    }


    public async Task<(string committedPath, Sha256Digest actualDigest)> WriteAndCommitAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Stream inputStream,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default)
    {
        if (expectedSizeBytes < 0 || expectedSizeBytes > Glide.Core.Codec.ProtocolCatalog.MaxItemBytes)
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded, "The declared item size exceeds the protocol limit.");
        string sanitizedRelativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);
        string quarantinePath = GetQuarantinePath(sessionId, itemId);

        byte[] computedHash;
        long totalWritten = 0;

        try
        {
            using (var fileStream = OpenQuarantineWrite(sessionId, itemId))
            using (var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                byte[] buffer = new byte[65536]; // Bounded 64 KiB buffer
                int bytesRead;

                while ((bytesRead = await inputStream.ReadAsync(buffer, 0, buffer.Length, cancellationToken)) > 0)
                {
                    if (bytesRead > expectedSizeBytes - totalWritten)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.LimitExceeded,
                            $"Stream exceeded declared item size {expectedSizeBytes} bytes.");
                    }
                    await fileStream.WriteAsync(buffer.AsMemory(0, bytesRead), cancellationToken);
                    sha256.AppendData(buffer, 0, bytesRead);
                    totalWritten = checked(totalWritten + bytesRead);
                }

                if (totalWritten != expectedSizeBytes)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.TransferInterrupted,
                        $"Stream finished early: expected {expectedSizeBytes} bytes, but received {totalWritten} bytes");
                }

                computedHash = sha256.GetHashAndReset();
            }

            string actualHashHex = Convert.ToHexStringLower(computedHash);
            var actualDigest = new Sha256Digest(actualHashHex);

            if (!string.Equals(actualHashHex, expectedDigest.Value, StringComparison.OrdinalIgnoreCase))
            {
                PurgeQuarantine(sessionId, itemId);
                throw new ProtocolViolationException(
                    WireErrorCodes.IntegrityMismatch,
                    $"SHA-256 integrity mismatch! Expected {expectedDigest.Value}, got {actualHashHex}");
            }

            string finalPath = await CommitVerifiedQuarantineFileAsync(
                sessionId, itemId, sanitizedRelativePath, actualDigest,
                expectedSizeBytes, cancellationToken);
            return (finalPath, actualDigest);
        }
        catch
        {
            PurgeQuarantine(sessionId, itemId);
            throw;
        }
    }

    public void PurgeQuarantine(SessionId sessionId, ItemId itemId)
    {
        try
        {
            string quarantinePath = GetQuarantinePath(sessionId, itemId);
            if (File.Exists(quarantinePath))
            {
                File.Delete(quarantinePath);
            }
        }
        catch
        {
            // Ignore purge cleanup errors
        }
    }

    public void PurgeSession(SessionId sessionId)
    {
        try
        {
            string sessionDir = Path.Combine(_stagingRoot, sessionId.Value);
            if (Directory.Exists(sessionDir))
            {
                Directory.Delete(sessionDir, recursive: true);
            }
        }
        catch
        {
            // Ignore purge cleanup errors
        }
    }

    public async Task<string> CommitVerifiedQuarantineFileAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default)
    {
        string quarantinePath = GetQuarantinePath(sessionId, itemId);
        if (!File.Exists(quarantinePath))
            throw new FileNotFoundException($"Quarantine file not found: {quarantinePath}");
        if ((File.GetAttributes(quarantinePath) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("The quarantine file is a symbolic link or reparse point.");
        if (expectedSizeBytes < 0 || expectedSizeBytes > Glide.Core.Codec.ProtocolCatalog.MaxItemBytes)
            throw new ArgumentOutOfRangeException(nameof(expectedSizeBytes));

        string finalPath = ResolveSafeDestinationPath(rawRelativePath);
        string targetDirectory = Path.GetDirectoryName(finalPath)!;
        string temporaryPath = Path.Combine(targetDirectory,
            $".glide-commit-{Guid.NewGuid():N}.part");

        try
        {
            await using (var source = new FileStream(quarantinePath, FileMode.Open,
                FileAccess.Read, FileShare.None, 65_536, useAsync: true))
            {
                await using (var target = new FileStream(temporaryPath, FileMode.CreateNew,
                    FileAccess.Write, FileShare.None, 65_536, useAsync: true))
                {
                    if (!OperatingSystem.IsWindows())
                        File.SetUnixFileMode(temporaryPath,
                            UnixFileMode.UserRead | UnixFileMode.UserWrite);

                    using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                    byte[] buffer = new byte[65_536];
                    long copied = 0;
                    int count;
                    while ((count = await source.ReadAsync(buffer, cancellationToken)) > 0)
                    {
                        copied = checked(copied + count);
                        if (copied > expectedSizeBytes)
                            throw new IOException("The staged file exceeded its verified length.");
                        await target.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
                        hasher.AppendData(buffer, 0, count);
                    }

                    string copiedDigest = Convert.ToHexStringLower(hasher.GetHashAndReset());
                    if (copied != expectedSizeBytes ||
                        !string.Equals(copiedDigest, expectedDigest.Value, StringComparison.Ordinal))
                        throw new IOException("The staged file changed before final commit.");

                    await target.FlushAsync(cancellationToken);
                    target.Flush(flushToDisk: true);
                }
            }

            // The temporary file and final file are in the same directory. This
            // final rename cannot turn into a cross-volume copy, unlike moving
            // directly from the application's quarantine root.
            finalPath = ResolveSafeDestinationPath(rawRelativePath);
            if (!string.Equals(Path.GetDirectoryName(finalPath), targetDirectory,
                    OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
                throw new IOException("The destination directory changed before commit.");
            File.Move(temporaryPath, finalPath, overwrite: false);
            try { File.Delete(quarantinePath); }
            catch (IOException) { /* Verified public file is already committed. */ }
            catch (UnauthorizedAccessException) { /* Retry cleanup during maintenance. */ }
            return finalPath;
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }

    /// <summary>
    /// Best-effort restart maintenance. Only Glide's exact temporary filename
    /// is eligible, and recent files are left alone for another active process.
    /// Traversal is bounded so a large Downloads tree cannot stall receiving.
    /// </summary>
    public int PurgeAbandonedCommitFiles()
    {
        string root = Path.GetFullPath(_destinationRoot);
        if (!Directory.Exists(root)) return 0;
        RejectLinkedAncestors(root);

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
                    catch (IOException) { /* File changed or is in use. */ }
                    catch (UnauthorizedAccessException) { /* Skip inaccessible entry. */ }
                }
            }
            catch (IOException) { /* Directory changed during traversal. */ }
            catch (UnauthorizedAccessException) { /* Skip inaccessible directory. */ }
        }

        return removed;
    }

    /// <summary>
    /// Removes only old, protocol-shaped private session files. A session with
    /// recent, linked, nested, or unexpected contents is left untouched.
    /// </summary>
    public int PurgeAbandonedQuarantineSessions()
    {
        string root = Path.GetFullPath(_stagingRoot);
        if (!Directory.Exists(root)) return 0;
        RejectLinkedAncestors(root);

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

                var files = new List<string>();
                bool safe = true;
                foreach (string entry in Directory.EnumerateFileSystemEntries(sessionDirectory))
                {
                    if (++examined > MaxSessionCleanupEntries ||
                        (File.GetAttributes(entry) &
                         (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0 ||
                        !IsQuarantineFile(Path.GetFileName(entry)) ||
                        File.GetLastWriteTimeUtc(entry) > cutoff)
                    {
                        safe = false;
                        break;
                    }
                    files.Add(entry);
                }

                if (!safe || files.Count == 0) continue;
                foreach (string file in files)
                {
                    File.Delete(file);
                    removed++;
                }
                Directory.Delete(sessionDirectory, recursive: false);
            }
            catch (IOException) { /* An active or changed session is left alone. */ }
            catch (UnauthorizedAccessException) { /* Skip inaccessible session. */ }
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

    private string ResolveSafeDestinationPath(string rawRelativePath)
    {
        string safeRelativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);
        string root = Path.GetFullPath(_destinationRoot);
        RejectLinkedAncestors(root);
        Directory.CreateDirectory(root);
        RejectLinkedAncestors(root);

        string[] segments = safeRelativePath.Split('/');
        string current = root;
        for (int i = 0; i < segments.Length - 1; i++)
        {
            current = Path.Combine(current, segments[i]);
            Directory.CreateDirectory(current);
            RejectLink(current);
        }

        return ResolveCollision(Path.Combine(current, segments[^1]));
    }

    private static void EnsurePrivateDirectory(string path)
    {
        RejectLinkedAncestors(path);
        if (OperatingSystem.IsWindows())
            Directory.CreateDirectory(path);
        else
        {
            Directory.CreateDirectory(path,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            RejectLink(path);
            File.SetUnixFileMode(path,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
        RejectLinkedAncestors(path);
    }

    private static void RejectLinkedAncestors(string path)
    {
        string? current = Path.GetFullPath(path);
        while (current != null)
        {
            try
            {
                if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                    throw new IOException("A transfer path contains a symbolic link or reparse point.");
            }
            catch (FileNotFoundException) { /* Parent not created yet. */ }
            catch (DirectoryNotFoundException) { /* Parent not created yet. */ }
            string? parent = Path.GetDirectoryName(current);
            if (parent == current) break;
            current = parent;
        }
    }

    private static void RejectLink(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("A transfer path contains a symbolic link or reparse point.");
    }

    public static string ResolveCollision(string targetPath)
    {
        if (!File.Exists(targetPath))
        {
            return targetPath;
        }

        string directory = Path.GetDirectoryName(targetPath) ?? string.Empty;
        string fileNameWithoutExt = Path.GetFileNameWithoutExtension(targetPath);
        string extension = Path.GetExtension(targetPath);

        int counter = 1;
        while (true)
        {
            string candidate = Path.Combine(directory, $"{fileNameWithoutExt} ({counter}){extension}");
            if (!File.Exists(candidate))
            {
                return candidate;
            }
            counter++;
        }
    }
}
