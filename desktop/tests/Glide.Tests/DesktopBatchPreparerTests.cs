using Glide.Desktop.Services;
using Xunit;

namespace Glide.Tests;

public sealed class DesktopBatchPreparerTests
{
    [Fact]
    public void FolderSelection_ExpandsFilesWithPortableRelativePaths()
    {
        string root = Path.Combine(Path.GetTempPath(), "glide-batch-" + Guid.NewGuid().ToString("N"));
        string folder = Path.Combine(root, "photos");
        string nested = Path.Combine(folder, "trip");
        Directory.CreateDirectory(nested);
        File.WriteAllBytes(Path.Combine(nested, "view.jpg"), [1, 2, 3]);

        try
        {
            var result = DesktopBatchPreparer.Prepare([folder]);
            Assert.True(result.Success, result.Message);
            Assert.Single(result.Files);
            Assert.Equal("photos/trip/view.jpg", result.Files[0].TransferRelativePath);
            Assert.Equal(3, result.TotalBytes);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void DuplicateReceiverPath_IsRejectedBeforePairing()
    {
        string root = Path.Combine(Path.GetTempPath(), "glide-batch-" + Guid.NewGuid().ToString("N"));
        string first = Path.Combine(root, "one");
        string second = Path.Combine(root, "two");
        Directory.CreateDirectory(first);
        Directory.CreateDirectory(second);
        string a = Path.Combine(first, "same.txt");
        string b = Path.Combine(second, "same.txt");
        File.WriteAllText(a, "a");
        File.WriteAllText(b, "b");

        try
        {
            var result = DesktopBatchPreparer.Prepare([a, b]);
            Assert.False(result.Success);
            Assert.Contains("same destination path", result.Message);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
