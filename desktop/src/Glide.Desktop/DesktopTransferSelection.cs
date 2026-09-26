using Glide.Core.Storage;

namespace Glide.Desktop;

/// <summary>
/// Expands files and folders chosen by a desktop user into protocol-safe transfer items.
/// The source tree is never followed through reparse points, and receiver-visible paths are
/// validated before a connection is opened.
/// </summary>
public static class DesktopTransferSelection
{
    private static readonly EnumerationOptions SafeEnumeration = new()
    {
        RecurseSubdirectories = true,
        IgnoreInaccessible = true,
        AttributesToSkip = FileAttributes.ReparsePoint
    };

    public static DesktopTransferSelectionResult Expand(
        IEnumerable<string> sourcePaths)
    {
        ArgumentNullException.ThrowIfNull(sourcePaths);

        var files = new List<DesktopTransferFile>();
        var skipped = new List<DesktopTransferSelectionIssue>();
        var seenSourcePaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var seenRelativePaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (string rawPath in sourcePaths)
        {
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
                AddFile(path, Path.GetFileName(path), files, skipped, seenSourcePaths, seenRelativePaths);
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
                    if (IsReparsePoint(filePath))
                    {
                        skipped.Add(new DesktopTransferSelectionIssue(filePath, "Linked files are not transferred for safety."));
                        continue;
                    }

                    string insideFolder = Path.GetRelativePath(path, filePath)
                        .Replace(Path.DirectorySeparatorChar, '/')
                        .Replace(Path.AltDirectorySeparatorChar, '/');
                    AddFile(filePath, $"{directoryName}/{insideFolder}", files, skipped, seenSourcePaths, seenRelativePaths);
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

    private static void AddFile(
        string filePath,
        string relativePath,
        ICollection<DesktopTransferFile> files,
        ICollection<DesktopTransferSelectionIssue> skipped,
        ISet<string> seenSourcePaths,
        ISet<string> seenRelativePaths)
    {
        if (!seenSourcePaths.Add(filePath))
        {
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

            files.Add(new DesktopTransferFile(filePath, safeRelativePath));
        }
        catch (ArgumentException)
        {
            skipped.Add(new DesktopTransferSelectionIssue(filePath, "The file name cannot be safely transferred to Windows."));
        }
    }

    private static bool IsReparsePoint(string path) =>
        (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
}

public sealed record DesktopTransferSelectionIssue(string Path, string Reason);

public sealed record DesktopTransferSelectionResult(
    IReadOnlyList<DesktopTransferFile> Files,
    IReadOnlyList<DesktopTransferSelectionIssue> Skipped);
