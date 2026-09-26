using Glide.Desktop;

namespace Glide.Tests;

public sealed class DesktopTransferSelectionTests : IDisposable
{
    private readonly string _root = Path.Combine(
        Path.GetTempPath(),
        "glide-selection-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void Expand_PreservesFolderRelativePaths()
    {
        string folder = Path.Combine(_root, "Project");
        string nested = Path.Combine(folder, "nested");
        Directory.CreateDirectory(nested);
        File.WriteAllText(Path.Combine(folder, "readme.txt"), "root");
        File.WriteAllText(Path.Combine(nested, "notes.txt"), "nested");

        DesktopTransferSelectionResult result =
            DesktopTransferSelection.Expand([folder]);

        Assert.Empty(result.Skipped);
        Assert.Equal(
            ["Project/nested/notes.txt", "Project/readme.txt"],
            result.Files.Select(static item => item.TransferRelativePath).OrderBy(static path => path));
    }

    [Fact]
    public void Expand_RejectsDuplicateDestinationPaths()
    {
        string first = Path.Combine(_root, "one", "same.txt");
        string second = Path.Combine(_root, "two", "same.txt");
        Directory.CreateDirectory(Path.GetDirectoryName(first)!);
        Directory.CreateDirectory(Path.GetDirectoryName(second)!);
        File.WriteAllText(first, "one");
        File.WriteAllText(second, "two");

        DesktopTransferSelectionResult result =
            DesktopTransferSelection.Expand([first, second]);

        Assert.Single(result.Files);
        Assert.Contains(result.Skipped, issue => issue.Reason.Contains("same destination path", StringComparison.Ordinal));
    }

    [Fact]
    public void Expand_ReportsEmptyFoldersWithoutCreatingSyntheticFiles()
    {
        string folder = Path.Combine(_root, "empty");
        Directory.CreateDirectory(folder);

        DesktopTransferSelectionResult result =
            DesktopTransferSelection.Expand([folder]);

        Assert.Empty(result.Files);
        Assert.Contains(result.Skipped, issue => issue.Reason.Contains("Empty folders", StringComparison.Ordinal));
    }

    public void Dispose()
    {
        if (Directory.Exists(_root))
        {
            Directory.Delete(_root, recursive: true);
        }
    }
}
