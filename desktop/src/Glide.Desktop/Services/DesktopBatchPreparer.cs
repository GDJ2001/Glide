using System.Security;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Storage;

namespace Glide.Desktop.Services;

public sealed record BatchPreparationResult(
    bool Success,
    IReadOnlyList<DesktopTransferFile> Files,
    long TotalBytes,
    TransferErrorCategory ErrorCategory,
    string Message)
{
    public static BatchPreparationResult Succeeded(IReadOnlyList<DesktopTransferFile> files, long totalBytes) =>
        new(true, files, totalBytes, TransferErrorCategory.None,
            $"{files.Count} files prepared ({DesktopTransferRateCalculator.FormatBytes(totalBytes)}).");

    public static BatchPreparationResult Failed(TransferErrorCategory category, string message) =>
        new(false, Array.Empty<DesktopTransferFile>(), 0, category, message);
}

/// <summary>
/// Expands selected folders into protocol-safe relative paths before starting a
/// transfer. It rejects links, cross-platform unsafe names, collisions and
/// oversized batches rather than discovering those problems after pairing.
/// </summary>
public static class DesktopBatchPreparer
{
    public const int MaxItems = 1_000;

    private static readonly EnumerationOptions SafeEnumeration = new()
    {
        RecurseSubdirectories = true,
        IgnoreInaccessible = true,
        AttributesToSkip = FileAttributes.ReparsePoint
    };

    public static BatchPreparationResult Prepare(
        IEnumerable<string> selectedPaths,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(selectedPaths);

        var files = new List<DesktopTransferFile>();
        var offeredPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        long totalBytes = 0;

        try
        {
            foreach (string path in selectedPaths)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (string.IsNullOrWhiteSpace(path)) continue;

                if (File.Exists(path))
                {
                    AddFile(path, Path.GetFileName(path));
                }
                else if (Directory.Exists(path))
                {
                    if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                        throw new IOException("Symbolic-link folders cannot be shared.");

                    string root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));
                    string rootName = Path.GetFileName(root);
                    var options = new EnumerationOptions
                    {
                        RecurseSubdirectories = true,
                        IgnoreInaccessible = false,
                        AttributesToSkip = FileAttributes.ReparsePoint
                    };
                    foreach (string child in Directory.EnumerateFiles(root, "*", options))
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        string relativePath = Path.Combine(rootName, Path.GetRelativePath(root, child))
                            .Replace('\\', '/');
                        AddFile(child, relativePath);
                    }
                }
                else
                {
                    throw new FileNotFoundException($"File or folder not found: '{path}'.");
                }
            }
        }
        catch (BatchLimitException ex)
        {
            return BatchPreparationResult.Failed(TransferErrorCategory.LimitExceeded, ex.Message);
        }
        catch (OverflowException)
        {
            return BatchPreparationResult.Failed(TransferErrorCategory.LimitExceeded,
                "Total batch size exceeds the 256 GiB limit.");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or SecurityException or ArgumentException)
        {
            return BatchPreparationResult.Failed(TransferErrorCategory.Storage,
                $"Could not prepare this selection: {ex.Message}");
        }

        if (files.Count == 0)
            return BatchPreparationResult.Failed(TransferErrorCategory.None,
                "No files were selected. Empty folders cannot be sent by the current protocol.");

        return BatchPreparationResult.Succeeded(files, totalBytes);

        void AddFile(string path, string relativePath)
        {
            if (files.Count >= MaxItems)
                throw new BatchLimitException($"A batch can contain at most {MaxItems} files.");
            string fileName = Path.GetFileName(path);
            if (fileName.StartsWith(".glide-commit-", StringComparison.Ordinal) &&
                fileName.EndsWith(".part", StringComparison.Ordinal))
                throw new IOException("A Glide temporary commit file cannot be shared.");
            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Symbolic-link files cannot be shared.");

            WindowsPathSanitizer.ValidateRelativePath(relativePath);
            if (!offeredPaths.Add(relativePath))
                throw new IOException($"Two selected files would have the same destination path: '{relativePath}'.");

            var fileInfo = new FileInfo(path);
            long size = fileInfo.Length;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
                throw new BatchLimitException($"File '{fileInfo.Name}' exceeds the 64 GiB limit.");

            totalBytes = checked(totalBytes + size);
            if (totalBytes > ProtocolCatalog.MaxBatchBytes)
                throw new BatchLimitException("Total batch size exceeds the 256 GiB limit.");

            files.Add(new DesktopTransferFile(path, relativePath, KnownSizeBytes: size));
        }
    }

    public static DesktopTransferSelectionResult Expand(
        IEnumerable<string> sourcePaths,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(sourcePaths);

        var files = new List<DesktopTransferFile>();
        var skipped = new List<DesktopTransferSelectionIssue>();
        var seenSourcePaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var seenRelativePaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        long totalBytes = 0;

        foreach (string rawPath in sourcePaths)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (string.IsNullOrWhiteSpace(rawPath))
            {
                continue;
            }

            string path;
            try
            {
                path = Path.GetFullPath(rawPath);
            }
            catch (Exception ex) when (ex is ArgumentException or NotSupportedException)
            {
                skipped.Add(new DesktopTransferSelectionIssue(rawPath, "The selected path is invalid."));
                continue;
            }

            if (File.Exists(path))
            {
                AddExpandedFile(path, Path.GetFileName(path), files, skipped, seenSourcePaths, seenRelativePaths, ref totalBytes);
                continue;
            }

            if (!Directory.Exists(path))
            {
                skipped.Add(new DesktopTransferSelectionIssue(path, "The selected item no longer exists."));
                continue;
            }

            if (IsReparsePoint(path))
            {
                skipped.Add(new DesktopTransferSelectionIssue(path, "Linked folders are not transferred for safety."));
                continue;
            }

            string directoryName = Path.GetFileName(path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
            if (string.IsNullOrWhiteSpace(directoryName))
            {
                skipped.Add(new DesktopTransferSelectionIssue(path, "The folder does not have a transferable name."));
                continue;
            }

            int before = files.Count;
            try
            {
                foreach (string filePath in Directory.EnumerateFiles(path, "*", SafeEnumeration))
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (IsReparsePoint(filePath))
                    {
                        skipped.Add(new DesktopTransferSelectionIssue(filePath, "Linked files are not transferred for safety."));
                        continue;
                    }

                    string insideFolder = Path.GetRelativePath(path, filePath)
                        .Replace(Path.DirectorySeparatorChar, '/')
                        .Replace(Path.AltDirectorySeparatorChar, '/');
                    AddExpandedFile(filePath, $"{directoryName}/{insideFolder}", files, skipped, seenSourcePaths, seenRelativePaths, ref totalBytes);
                }
            }
            catch (UnauthorizedAccessException)
            {
                skipped.Add(new DesktopTransferSelectionIssue(path, "Glide does not have permission to read this folder."));
            }
            catch (IOException)
            {
                skipped.Add(new DesktopTransferSelectionIssue(path, "The folder could not be read."));
            }

            if (files.Count == before)
            {
                skipped.Add(new DesktopTransferSelectionIssue(path, "The folder contains no transferable files. Empty folders are not supported by the current Glide protocol."));
            }
        }

        return new DesktopTransferSelectionResult(files, skipped);
    }

    private static void AddExpandedFile(
        string filePath,
        string relativePath,
        ICollection<DesktopTransferFile> files,
        ICollection<DesktopTransferSelectionIssue> skipped,
        ISet<string> seenSourcePaths,
        ISet<string> seenRelativePaths,
        ref long totalBytes)
    {
        if (!seenSourcePaths.Add(filePath))
        {
            return;
        }

        if (files.Count >= MaxItems)
        {
            skipped.Add(new DesktopTransferSelectionIssue(filePath, $"A batch can contain at most {MaxItems} files."));
            return;
        }

        string fileName = Path.GetFileName(filePath);
        if (fileName.StartsWith(".glide-commit-", StringComparison.Ordinal) &&
            fileName.EndsWith(".part", StringComparison.Ordinal))
        {
            skipped.Add(new DesktopTransferSelectionIssue(filePath, "A Glide temporary commit file cannot be shared."));
            return;
        }

        if (IsReparsePoint(filePath))
        {
            skipped.Add(new DesktopTransferSelectionIssue(filePath, "Linked files are not transferred for safety."));
            return;
        }

        try
        {
            string safeRelativePath = WindowsPathSanitizer.SanitizeRelativePath(relativePath);
            if (!seenRelativePaths.Add(safeRelativePath))
            {
                skipped.Add(new DesktopTransferSelectionIssue(filePath, "Another selected item would use the same destination path."));
                return;
            }

            var fileInfo = new FileInfo(filePath);
            long size = fileInfo.Length;
            if (size < 0 || size > ProtocolCatalog.MaxItemBytes)
            {
                skipped.Add(new DesktopTransferSelectionIssue(filePath, "The file exceeds the 64 GiB limit."));
                return;
            }

            if (totalBytes > ProtocolCatalog.MaxBatchBytes - size)
            {
                skipped.Add(new DesktopTransferSelectionIssue(filePath, "Total batch size exceeds the 256 GiB limit."));
                return;
            }

            totalBytes += size;
            files.Add(new DesktopTransferFile(filePath, safeRelativePath, KnownSizeBytes: size));
        }
        catch (ArgumentException)
        {
            skipped.Add(new DesktopTransferSelectionIssue(filePath, "The file name cannot be safely transferred to Windows."));
        }
        catch (IOException)
        {
            skipped.Add(new DesktopTransferSelectionIssue(filePath, "The file could not be read."));
        }
    }

    private static bool IsReparsePoint(string path) =>
        (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;

    private sealed class BatchLimitException(string message) : Exception(message) { }
}
