using System.Collections.ObjectModel;

namespace Glide.Linux.UI.ViewModels;

/// <summary>Owns verified history filtering and its empty state on Activity.</summary>
public sealed class LinuxActivityHistoryViewModel : ViewModelBase
{
    private ActivityFilter _selectedFilter = ActivityFilter.All;
    private string _searchQuery = string.Empty;
    private IReadOnlyList<LinuxHistoryItemViewModel> _filteredItems = [];

    public LinuxActivityHistoryViewModel()
    {
        Items.CollectionChanged += (_, _) => RefreshResults();
    }

    public ObservableCollection<LinuxHistoryItemViewModel> Items { get; } = [];
    public IReadOnlyList<LinuxHistoryItemViewModel> FilteredItems => _filteredItems;
    public bool HasResults => _filteredItems.Count > 0;
    public string EmptyTitle => Items.Count == 0 ? "No transfer history yet" : "No matching transfers";
    public string EmptyDescription => Items.Count == 0
        ? "Verified transfers appear here."
        : "Try a different search or filter.";

    public ActivityFilter SelectedFilter
    {
        get => _selectedFilter;
        set
        {
            if (SetProperty(ref _selectedFilter, value)) RefreshResults();
        }
    }

    public string SearchQuery
    {
        get => _searchQuery;
        set
        {
            if (SetProperty(ref _searchQuery, value)) RefreshResults();
        }
    }

    private void RefreshResults()
    {
        IEnumerable<LinuxHistoryItemViewModel> query = Items;
        if (SelectedFilter == ActivityFilter.Sent)
            query = query.Where(item =>
                string.Equals(item.Direction, "Send", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(item.Direction, "Sent", StringComparison.OrdinalIgnoreCase));
        else if (SelectedFilter == ActivityFilter.Received)
            query = query.Where(item =>
                string.Equals(item.Direction, "Receive", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(item.Direction, "Received", StringComparison.OrdinalIgnoreCase));

        if (!string.IsNullOrWhiteSpace(SearchQuery))
        {
            string search = SearchQuery.Trim();
            query = query.Where(item =>
                (item.PeerName?.Contains(search, StringComparison.OrdinalIgnoreCase) ?? false) ||
                (item.ItemName?.Contains(search, StringComparison.OrdinalIgnoreCase) ?? false));
        }

        _filteredItems = query.ToList();
        OnPropertyChanged(nameof(FilteredItems));
        OnPropertyChanged(nameof(HasResults));
        OnPropertyChanged(nameof(EmptyTitle));
        OnPropertyChanged(nameof(EmptyDescription));
    }
}
