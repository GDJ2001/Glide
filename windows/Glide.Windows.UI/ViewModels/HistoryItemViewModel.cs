using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

public class HistoryItemViewModel : ViewModelBase
{
    public string Direction { get; init; } = "Received"; // "Received" or "Sent"
    public string ItemName { get; init; } = "";
    public string FormattedSize { get; init; } = "";
    public string TargetPath { get; init; } = "";
    public string PeerName { get; init; } = "";
    public DateTime Timestamp { get; init; } = DateTime.Now;
    public string FormattedTime => Timestamp.ToString("HH:mm:ss - MMM dd");
    public string Status { get; init; } = "Verified";
}
