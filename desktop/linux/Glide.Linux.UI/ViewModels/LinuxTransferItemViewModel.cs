namespace Glide.Linux.UI.ViewModels;

public sealed class LinuxTransferItemViewModel : ViewModelBase
{
    public string FullPath { get; }
    public string Name { get; }
    public long Size { get; }
    public bool IsDirectory { get; }

    public string FormattedSize => FormatBytes(Size);

    public LinuxTransferItemViewModel(string fullPath, string name, long size, bool isDirectory)
    {
        FullPath = fullPath;
        Name = name;
        Size = size;
        IsDirectory = isDirectory;
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return $"{kb:F1} KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return $"{mb:F1} MB";
        double gb = mb / 1024.0;
        return $"{gb:F2} GB";
    }
}
