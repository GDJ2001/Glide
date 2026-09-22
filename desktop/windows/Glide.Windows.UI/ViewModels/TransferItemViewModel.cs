using System.IO;
using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

public class TransferItemViewModel : ViewModelBase
{
    private string _name;
    private string _filePath;
    private long _sizeBytes;
    private string _status = "Pending";
    private double _progress = 0.0;

    public TransferItemViewModel(string filePath)
    {
        _filePath = filePath;
        var fi = new FileInfo(filePath);
        _name = fi.Name;
        _sizeBytes = fi.Exists ? fi.Length : 0;
    }

    public TransferItemViewModel(string name, long sizeBytes, string filePath = "")
    {
        _name = name;
        _sizeBytes = sizeBytes;
        _filePath = filePath;
    }

    public string Name
    {
        get => _name;
        set => SetProperty(ref _name, value);
    }

    public string FilePath
    {
        get => _filePath;
        set => SetProperty(ref _filePath, value);
    }

    public long SizeBytes
    {
        get => _sizeBytes;
        set => SetProperty(ref _sizeBytes, value);
    }

    public string FormattedSize => FormatSize(_sizeBytes);

    public string Status
    {
        get => _status;
        set => SetProperty(ref _status, value);
    }

    public double Progress
    {
        get => _progress;
        set => SetProperty(ref _progress, value);
    }

    public static string FormatSize(long bytes)
    {
        return bytes switch
        {
            >= 1024 * 1024 * 1024 => $"{bytes / (1024.0 * 1024.0 * 1024.0):F2} GB",
            >= 1024 * 1024 => $"{bytes / (1024.0 * 1024.0):F2} MB",
            >= 1024 => $"{bytes / 1024.0:F1} KB",
            _ => $"{bytes} B"
        };
    }
}
