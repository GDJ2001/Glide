using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views.Pages;

public partial class SettingsPage : UserControl
{
    public SettingsPage()
    {
        InitializeComponent();

        var browseBtn = this.FindControl<Button>("BrowseDownloadDirButton");
        if (browseBtn != null) browseBtn.Click += OnBrowseDownloadDirClicked;
    }

    private async void OnBrowseDownloadDirClicked(object? sender, RoutedEventArgs e)
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
}
