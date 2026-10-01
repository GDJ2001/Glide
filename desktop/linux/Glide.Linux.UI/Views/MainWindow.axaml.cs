using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
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

        var addFilesBtn = this.FindControl<Button>("AddFilesButton");
        if (addFilesBtn != null) addFilesBtn.Click += OnAddFilesClicked;

        var addFolderBtn = this.FindControl<Button>("AddFolderButton");
        if (addFolderBtn != null) addFolderBtn.Click += OnAddFolderClicked;

        var browseDirBtn = this.FindControl<Button>("BrowseDownloadDirButton");
        if (browseDirBtn != null) browseDirBtn.Click += OnBrowseDownloadDirClicked;

        var copyInvitationBtn = this.FindControl<Button>("CopyInvitationButton");
        if (copyInvitationBtn != null) copyInvitationBtn.Click += OnCopyInvitationClicked;
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

    private async void OnAddFilesClicked(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not LinuxMainViewModel vm) return;

        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Select Files to Send",
            AllowMultiple = true
        });

        if (files != null && files.Count > 0)
        {
            var paths = files
                .Select(f => f.TryGetLocalPath())
                .Where(p => !string.IsNullOrEmpty(p))
                .Select(p => p!)
                .ToList();

            await vm.StagePathsAsync(paths);
        }
    }

    private async void OnAddFolderClicked(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not LinuxMainViewModel vm) return;

        var folders = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "Select Folder to Send",
            AllowMultiple = false
        });

        if (folders != null && folders.Count > 0)
        {
            var path = folders[0].TryGetLocalPath();
            if (!string.IsNullOrEmpty(path))
            {
                await vm.StagePathsAsync([path]);
            }
        }
    }

    private async void OnBrowseDownloadDirClicked(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not LinuxMainViewModel vm) return;

        var folders = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "Select Destination Folder",
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

    private async void OnCopyInvitationClicked(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not LinuxMainViewModel vm ||
            string.IsNullOrWhiteSpace(vm.CurrentInvitationUri) || Clipboard is null) return;

        await Clipboard.SetTextAsync(vm.CurrentInvitationUri);
        vm.StatusMessage = "One-time receiving link copied. It expires after two minutes.";
    }
}
