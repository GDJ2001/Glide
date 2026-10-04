using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Glide.Linux.UI.ViewModels;

namespace Glide.Linux.UI.Views.Pages;

public partial class SendPage : UserControl
{
    public SendPage()
    {
        InitializeComponent();

        var addFilesEmptyBtn = this.FindControl<Button>("AddFilesEmptyButton");
        if (addFilesEmptyBtn != null) addFilesEmptyBtn.Click += OnAddFilesClicked;

        var addFolderEmptyBtn = this.FindControl<Button>("AddFolderEmptyButton");
        if (addFolderEmptyBtn != null) addFolderEmptyBtn.Click += OnAddFolderClicked;

        var addFilesStagedBtn = this.FindControl<Button>("AddFilesStagedButton");
        if (addFilesStagedBtn != null) addFilesStagedBtn.Click += OnAddFilesClicked;

        var addFolderStagedBtn = this.FindControl<Button>("AddFolderStagedButton");
        if (addFolderStagedBtn != null) addFolderStagedBtn.Click += OnAddFolderClicked;
    }

    private async void OnAddFilesClicked(object? sender, RoutedEventArgs e)
    {
        var topLevel = TopLevel.GetTopLevel(this);
        if (topLevel?.StorageProvider == null) return;
        if (DataContext is not LinuxMainViewModel vm) return;

        var files = await topLevel.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
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

            if (paths.Count > 0)
            {
                await vm.StagePathsAsync(paths);
            }
        }
    }

    private async void OnAddFolderClicked(object? sender, RoutedEventArgs e)
    {
        var topLevel = TopLevel.GetTopLevel(this);
        if (topLevel?.StorageProvider == null) return;
        if (DataContext is not LinuxMainViewModel vm) return;

        var folders = await topLevel.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
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
}
