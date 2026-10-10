using Avalonia;
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
        UpdateWindowSurfaceCorners();
        PropertyChanged += (_, e) =>
        {
            if (e.Property == WindowStateProperty)
            {
                UpdateWindowSurfaceCorners();
            }
        };
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

    private void UpdateWindowSurfaceCorners()
    {
        WindowSurface.CornerRadius = WindowState == WindowState.Normal
            ? new CornerRadius(12)
            : new CornerRadius(0);
        bool maximized = WindowState == WindowState.Maximized;
        MaximizeWindowIcon.IsVisible = !maximized;
        RestoreWindowIcon.IsVisible = maximized;
        Avalonia.Automation.AutomationProperties.SetName(MaximizeWindowButton,
            maximized ? "Restore Glide" : "Maximize Glide");
    }

    private void OnMinimizeWindow(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => WindowState = WindowState.Minimized;

    private void OnMaximizeWindow(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void OnCloseWindow(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => Close();

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
