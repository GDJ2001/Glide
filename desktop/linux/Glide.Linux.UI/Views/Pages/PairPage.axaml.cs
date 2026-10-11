using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views.Pages;

public partial class PairPage : UserControl
{
    public PairPage()
    {
        InitializeComponent();
        var copyButton = this.FindControl<Button>("CopyPairLinkButton");
        if (copyButton != null) copyButton.Click += CopyPairLink;
    }

    private async void CopyPairLink(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not LinuxMainViewModel vm ||
            string.IsNullOrWhiteSpace(vm.Pairing.QrUri)) return;
        if (TopLevel.GetTopLevel(this)?.Clipboard is { } clipboard)
            await clipboard.SetTextAsync(vm.Pairing.QrUri);
    }
}
