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

            files.Add(new DesktopTransferFile(path, relativePath));
        }
    }

    private sealed class BatchLimitException(string message) : Exception(message) { }
}
