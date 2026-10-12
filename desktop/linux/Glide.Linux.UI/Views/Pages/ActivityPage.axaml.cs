using Avalonia.Controls;

namespace Glide.Linux.UI.Views.Pages;

public partial class ActivityPage : UserControl
{
    public ActivityPage()
    {
        InitializeComponent();
        SizeChanged += (_, _) =>
        {
            bool narrow = Bounds.Width < 620;
            Grid.SetRow(HistoryHeaderActions, narrow ? 1 : 0);
            Grid.SetColumn(HistoryHeaderActions, narrow ? 0 : 1);
            Grid.SetColumnSpan(HistoryHeaderActions, narrow ? 2 : 1);
            HistoryHeaderActions.Margin = narrow
                ? new Avalonia.Thickness(0, 12, 0, 0)
                : new Avalonia.Thickness(0);
        };
    }
}
