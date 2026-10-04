using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Platform.Storage;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        Closed += (_, _) => (DataContext as LinuxMainViewModel)?.Dispose();
        AddHandler(DragDrop.DropEvent, OnDrop);
        AddHandler(KeyDownEvent, (sender, e) =>
        {
            if (e.Key == Key.Escape && DataContext is LinuxMainViewModel vm && vm.IsAnyModalVisible)
            {
                vm.DismissActiveModalCommand.Execute(null);
                e.Handled = true;
            }
        }, Avalonia.Interactivity.RoutingStrategies.Tunnel);
    }

    private async void OnDrop(object? sender, DragEventArgs e)
    {
        if (DataContext is LinuxMainViewModel vm)
        {
            var files = e.DataTransfer.TryGetFiles();
            if (files != null)
            {
                var paths = files
                    .Select(f => f.TryGetLocalPath())
                    .Where(p => !string.IsNullOrEmpty(p))
                    .Select(p => p!)
                    .ToList();

                if (paths.Count > 0)
                {
                    await vm.StagePathsAsync(paths);
                }
            }
        }
    }
}
