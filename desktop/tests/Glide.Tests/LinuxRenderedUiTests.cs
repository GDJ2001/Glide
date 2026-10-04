using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Styling;
using Avalonia.VisualTree;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Linux.UI;
using Glide.Linux.UI.ViewModels;
using Glide.Linux.UI.Views;
using Glide.Linux.UI.Views.Controls;
using Glide.Linux.UI.Views.Pages;
using Xunit;

namespace Glide.Tests;

public static class HeadlessSetup
{
    private static HeadlessUnitTestSession? _session;
    private static readonly object _lock = new();

    public static HeadlessUnitTestSession GetSession()
    {
        lock (_lock)
        {
            return _session ??= HeadlessUnitTestSession.GetOrStartForAssembly(typeof(Glide.Linux.UI.App).Assembly);
        }
    }
}

public sealed class LinuxRenderedUiTests
{
    [Fact]
    public async Task MainWindow_DefaultState_ShowsSendPageOnly()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var vm = new LinuxMainViewModel();
            var window = new MainWindow { DataContext = vm };
            window.Show();

            var sendPage = window.GetVisualDescendants().OfType<SendPage>().Single();
            var receivePage = window.GetVisualDescendants().OfType<ReceivePage>().Single();
            var pairPage = window.GetVisualDescendants().OfType<PairPage>().Single();
            var activityPage = window.GetVisualDescendants().OfType<ActivityPage>().Single();
            var settingsPage = window.GetVisualDescendants().OfType<SettingsPage>().Single();

            // Exactly Send page must be visible
            Assert.True(sendPage.IsVisible);
            Assert.False(receivePage.IsVisible);
            Assert.False(pairPage.IsVisible);
            Assert.False(activityPage.IsVisible);
            Assert.False(settingsPage.IsVisible);

            // Assert exactly one page is visible across the entire visual tree
            var allPages = new UserControl[] { sendPage, receivePage, pairPage, activityPage, settingsPage };
            Assert.Single(allPages, p => p.IsVisible);

            window.Close();
        }, CancellationToken.None);
    }

    [Theory]
    [InlineData("Send", typeof(SendPage))]
    [InlineData("Receive", typeof(ReceivePage))]
    [InlineData("Pair", typeof(PairPage))]
    [InlineData("Activity", typeof(ActivityPage))]
    [InlineData("Settings", typeof(SettingsPage))]
    public async Task MainWindow_TabNavigation_DisplaysOnlyTargetPage(string tabName, Type expectedVisibleType)
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var vm = new LinuxMainViewModel();
            var window = new MainWindow { DataContext = vm };
            window.Show();

            vm.SwitchTabCommand.Execute(tabName);

            var sendPage = window.GetVisualDescendants().OfType<SendPage>().Single();
            var receivePage = window.GetVisualDescendants().OfType<ReceivePage>().Single();
            var pairPage = window.GetVisualDescendants().OfType<PairPage>().Single();
            var activityPage = window.GetVisualDescendants().OfType<ActivityPage>().Single();
            var settingsPage = window.GetVisualDescendants().OfType<SettingsPage>().Single();

            var allPages = new UserControl[] { sendPage, receivePage, pairPage, activityPage, settingsPage };

            // Exactly one page must be visible and match expected type
            Assert.Single(allPages, p => p.IsVisible);
            var visiblePage = allPages.Single(p => p.IsVisible);
            Assert.IsType(expectedVisibleType, visiblePage);

            window.Close();
        }, CancellationToken.None);
    }

    [Fact]
    public async Task SendPage_FileQueue_TransformsFromDropTargetToStagedList()
    {
        var vm = new LinuxMainViewModel();
        Assert.True(vm.HasNoStagedItems);
        Assert.False(vm.HasStagedItems);

        string tempFile = Path.GetTempFileName();
        try
        {
            await File.WriteAllTextAsync(tempFile, "hello glide test");
            await vm.StagePathsAsync([tempFile]);

            Assert.False(vm.HasNoStagedItems);
            Assert.True(vm.HasStagedItems);
            Assert.Equal(1, vm.StagedCount);

            vm.ClearStagedItemsCommand.Execute(null);

            Assert.True(vm.HasNoStagedItems);
            Assert.False(vm.HasStagedItems);
            Assert.Equal(0, vm.StagedCount);
        }
        finally
        {
            if (File.Exists(tempFile)) File.Delete(tempFile);
        }
    }

    [Fact]
    public void SendPage_SendingMethods_AreMutuallyExclusive()
    {
        var vm = new LinuxMainViewModel();

        // Default: QuickShareQr
        Assert.Equal(SendMethod.QuickShareQr, vm.SelectedSendMethod);
        Assert.True(vm.IsQuickShareMethodSelected);
        Assert.False(vm.IsNearbyMethodSelected);
        Assert.False(vm.IsSavedMethodSelected);

        // Switch to NearbyDevice
        vm.SelectSendMethodCommand.Execute("NearbyDevice");
        Assert.Equal(SendMethod.NearbyDevice, vm.SelectedSendMethod);
        Assert.False(vm.IsQuickShareMethodSelected);
        Assert.True(vm.IsNearbyMethodSelected);
        Assert.False(vm.IsSavedMethodSelected);

        // Switch to SavedDevice
        vm.SelectSendMethodCommand.Execute("SavedDevice");
        Assert.Equal(SendMethod.SavedDevice, vm.SelectedSendMethod);
        Assert.False(vm.IsQuickShareMethodSelected);
        Assert.False(vm.IsNearbyMethodSelected);
        Assert.True(vm.IsSavedMethodSelected);
    }

    [Fact]
    public void ModalHost_DismissCommand_ClosesActiveModals()
    {
        var vm = new LinuxMainViewModel();
        Assert.False(vm.IsAnyModalVisible);

        // Simulate incoming offer
        var offer = new DesktopOffer("batch-1", "Peer Device", [], 0);
        _ = vm.ConfirmOfferAsync(offer, CancellationToken.None);

        Assert.True(vm.IsOfferModalVisible);
        Assert.True(vm.IsAnyModalVisible);

        // Dismiss via active modal command (Escape behavior)
        vm.DismissActiveModalCommand.Execute(null);

        Assert.False(vm.IsOfferModalVisible);
        Assert.False(vm.IsAnyModalVisible);
    }

    [Fact]
    public async Task Appearance_ThemeSelection_AppliesThemeVariant()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var vm = new LinuxMainViewModel();

            vm.SetThemeCommand.Execute("Light");
            Assert.Equal(AppThemePreference.Light, vm.CurrentThemePreference);
            Assert.Equal(ThemeVariant.Light, Application.Current!.RequestedThemeVariant);

            vm.SetThemeCommand.Execute("Dark");
            Assert.Equal(AppThemePreference.Dark, vm.CurrentThemePreference);
            Assert.Equal(ThemeVariant.Dark, Application.Current.RequestedThemeVariant);

            vm.SetThemeCommand.Execute("System");
            Assert.Equal(AppThemePreference.System, vm.CurrentThemePreference);
            Assert.Equal(ThemeVariant.Default, Application.Current.RequestedThemeVariant);
        }, CancellationToken.None);
    }

    [Fact]
    public void Activity_FilterAndSearch_FiltersHistoryItems()
    {
        var vm = new LinuxMainViewModel();
        vm.HistoryItems.Clear();

        vm.HistoryItems.Add(new LinuxHistoryItemViewModel(
            "1", DateTime.Now, "Send", "Pixel 8", 1, 1024, "Completed", itemName: "report.pdf"));
        vm.HistoryItems.Add(new LinuxHistoryItemViewModel(
            "2", DateTime.Now, "Receive", "MacBook Pro", 2, 2048, "Completed", itemName: "vacation.zip"));

        // All filter
        vm.SelectedActivityFilter = ActivityFilter.All;
        Assert.Equal(2, vm.FilteredHistoryItems.Count());

        // Sent filter
        vm.SelectedActivityFilter = ActivityFilter.Sent;
        Assert.Single(vm.FilteredHistoryItems);
        Assert.Equal("Pixel 8", vm.FilteredHistoryItems.First().PeerName);

        // Received filter
        vm.SelectedActivityFilter = ActivityFilter.Received;
        Assert.Single(vm.FilteredHistoryItems);
        Assert.Equal("MacBook Pro", vm.FilteredHistoryItems.First().PeerName);

        // Search query
        vm.SelectedActivityFilter = ActivityFilter.All;
        vm.ActivitySearchQuery = "report";
        Assert.Single(vm.FilteredHistoryItems);
        Assert.Equal("Pixel 8", vm.FilteredHistoryItems.First().PeerName);
    }
}
