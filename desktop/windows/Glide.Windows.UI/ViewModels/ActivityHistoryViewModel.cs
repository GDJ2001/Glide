using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows.Data;
using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

/// <summary>Owns the searchable, verified history shown on Activity.</summary>
public sealed class ActivityHistoryViewModel : ViewModelBase
{
    private string _searchText = string.Empty;
    private string _filter = "All";

    public ActivityHistoryViewModel()
    {
        Items = CollectionViewSource.GetDefaultView(Entries);
        Items.Filter = Matches;
    }

    public ObservableCollection<HistoryItemViewModel> Entries { get; } = [];
    public ICollectionView Items { get; }
    public IReadOnlyList<string> FilterOptions { get; } = ["All", "Sent", "Received"];

    public string SearchText
    {
        get => _searchText;
        set
        {
            if (SetProperty(ref _searchText, value)) Items.Refresh();
        }
    }

    public string Filter
    {
        get => _filter;
        set
        {
            if (SetProperty(ref _filter, value)) Items.Refresh();
        }
    }

    private bool Matches(object item)
    {
        if (item is not HistoryItemViewModel entry ||
            (Filter != "All" && entry.Direction != Filter)) return false;

        return string.IsNullOrWhiteSpace(SearchText) ||
            entry.ItemName.Contains(SearchText, StringComparison.OrdinalIgnoreCase) ||
            entry.PeerName.Contains(SearchText, StringComparison.OrdinalIgnoreCase) ||
            entry.TargetPath.Contains(SearchText, StringComparison.OrdinalIgnoreCase);
    }
}
