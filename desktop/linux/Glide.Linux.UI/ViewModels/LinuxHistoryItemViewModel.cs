namespace Glide.Linux.UI.ViewModels;

public sealed class LinuxHistoryItemViewModel : ViewModelBase
{
    public string Id { get; }
    public DateTime Timestamp { get; }
    public string Direction { get; }
    public string PeerName { get; }
    public int FileCount { get; }
    public long TotalBytes { get; }
    public string Status { get; }
    public string? ErrorMessage { get; }

    public string FormattedTimestamp => Timestamp.ToString("MMM dd, yyyy HH:mm");
    public string FormattedTotalBytes => FormatBytes(TotalBytes);

    public LinuxHistoryItemViewModel(
        string id,
        DateTime timestamp,
        string direction,
        string peerName,
        int fileCount,
        long totalBytes,
        string status,
        string? errorMessage = null)
    {
        Id = id;
        Timestamp = timestamp;
        Direction = direction;
        PeerName = peerName;
        FileCount = fileCount;
        TotalBytes = totalBytes;
        Status = status;
        ErrorMessage = errorMessage;
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
