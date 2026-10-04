using Avalonia.Controls;
using Avalonia.Input;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views.Controls;

public partial class ModalHost : UserControl
{
    public ModalHost()
    {
        InitializeComponent();
        AddHandler(KeyDownEvent, OnKeyDown, Avalonia.Interactivity.RoutingStrategies.Tunnel);
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && DataContext is LinuxMainViewModel vm && vm.IsAnyModalVisible)
        {
            vm.DismissActiveModalCommand.Execute(null);
            e.Handled = true;
        }
    }
}
