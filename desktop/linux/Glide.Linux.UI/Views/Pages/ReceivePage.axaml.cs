using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views.Pages;

public partial class ReceivePage : UserControl
{
    public ReceivePage()
    {
        InitializeComponent();

        var changeFolderBtn = this.FindControl<Button>("ChangeFolderButton");
        if (changeFolderBtn != null) changeFolderBtn.Click += OnChangeFolderClicked;

        var copyLinkBtn = this.FindControl<Button>("CopyLinkButton");
        if (copyLinkBtn != null) copyLinkBtn.Click += OnCopyLinkClicked;
    }

    private async void OnChangeFolderClicked(object? sender, RoutedEventArgs e)
    {
        var topLevel = TopLevel.GetTopLevel(this);
        if (topLevel?.StorageProvider == null) return;
        if (DataContext is not LinuxMainViewModel vm) return;

        var folders = await topLevel.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "Select Destination Folder for Received Files",
            AllowMultiple = false
        });

        if (folders != null && folders.Count > 0)
        {
            var path = folders[0].TryGetLocalPath();
            if (!string.IsNullOrEmpty(path))
            {
                vm.DownloadDirectory = path;
            }
        }
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
