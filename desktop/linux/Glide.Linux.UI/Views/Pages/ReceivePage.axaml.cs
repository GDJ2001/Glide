using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views.Pages;

public partial class ReceivePage : UserControl
{
    public ReceivePage()
    {
        InitializeComponent();

        var copyLinkBtn = this.FindControl<Button>("CopyLinkButton");
        if (copyLinkBtn != null) copyLinkBtn.Click += OnCopyLinkClicked;
    }

    private async void OnCopyLinkClicked(object? sender, RoutedEventArgs e)
    {
        var topLevel = TopLevel.GetTopLevel(this);
        if (topLevel?.Clipboard == null) return;
        if (DataContext is not LinuxMainViewModel vm || string.IsNullOrWhiteSpace(vm.CurrentInvitationUri)) return;

        await topLevel.Clipboard.SetTextAsync(vm.CurrentInvitationUri);
        vm.StatusMessage = "One-time receiving link copied to clipboard. It expires after two minutes.";
    }
}
