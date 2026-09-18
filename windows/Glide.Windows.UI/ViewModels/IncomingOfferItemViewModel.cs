using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

public sealed class IncomingOfferItemViewModel : ViewModelBase
{
    public IncomingOfferItemViewModel(
        string name,
        long sizeBytes,
        string relativePath)
    {
        Name = name;
        SizeBytes = sizeBytes;
        RelativePath = relativePath;
    }

    public string Name { get; }
    public long SizeBytes { get; }
    public string RelativePath { get; }
    public string FormattedSize => TransferItemViewModel.FormatSize(SizeBytes);
}
