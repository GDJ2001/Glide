using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Input;
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
    public async Task InteractiveConsent_RequiresExplicitDecision_AndCancellationDeclines()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(async () =>
        {
            using var vm = new LinuxMainViewModel();

            using var recipientCancellation = new CancellationTokenSource();
            var recipient = vm.ConfirmRecipientAsync("Another device", recipientCancellation.Token);
            Assert.False(recipient.IsCompleted);
            Assert.True(vm.IsRecipientModalVisible);
            recipientCancellation.Cancel();
            Assert.False(await recipient);
            Assert.False(vm.IsRecipientModalVisible);

            using var offerCancellation = new CancellationTokenSource();
            var offer = vm.ConfirmOfferAsync(
                new DesktopOffer("batch-1", "Another device", [], 0), offerCancellation.Token);
            Assert.False(offer.IsCompleted);
            Assert.True(vm.IsOfferModalVisible);
            offerCancellation.Cancel();
            Assert.False(await offer);
            Assert.False(vm.IsOfferModalVisible);

            using var trustCancellation = new CancellationTokenSource();
            var trust = vm.ConfirmPeerEnrollmentAsync(
                new VerifiedDesktopPeerIdentity("peer-1", "Another device", "A1B2C3D4E5F67890123456789ABCDEF0"),
                trustCancellation.Token);
            Assert.False(trust.IsCompleted);
            Assert.True(vm.IsSasModalVisible);
            trustCancellation.Cancel();
            Assert.False(await trust);
            Assert.False(vm.IsSasModalVisible);
        }, CancellationToken.None);
    }

    [Fact]
    public async Task MainWindow_EscapeKey_DismissesActiveModal()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var vm = new LinuxMainViewModel();
            var window = new MainWindow { DataContext = vm };
            window.Show();

            var offer = new DesktopOffer("batch-1", "Peer Device", [], 0);
            _ = vm.ConfirmOfferAsync(offer, CancellationToken.None);

            Assert.True(vm.IsOfferModalVisible);
            Assert.True(vm.IsAnyModalVisible);

            window.KeyPress(Key.Escape, RawInputModifiers.None, PhysicalKey.Escape, null);

            Assert.False(vm.IsOfferModalVisible);
            Assert.False(vm.IsAnyModalVisible);

            window.Close();
        }, CancellationToken.None);
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

    [Fact]
    public async Task SendPage_WithStagedItems_RendersQueueAndAllowsItemRemoval()
    {
        var session = HeadlessSetup.GetSession();
        string tempDir = Path.Combine(Path.GetTempPath(), $"glide_rendered_stage_test_{Guid.NewGuid():N}");
        Directory.CreateDirectory(tempDir);

        try
        {
            string file1 = Path.Combine(tempDir, "document.pdf");
            string file2 = Path.Combine(tempDir, "archive.tar");
            await File.WriteAllBytesAsync(file1, new byte[1024]);
            await File.WriteAllBytesAsync(file2, new byte[2048]);

            await session.Dispatch(async () =>
            {
                var vm = new LinuxMainViewModel();
                var window = new MainWindow { DataContext = vm };
                window.Show();

                await vm.StagePathsAsync([file1, file2]);
                window.UpdateLayout();

                var sendPage = window.GetVisualDescendants().OfType<SendPage>().Single();
                Assert.True(sendPage.IsVisible);
                Assert.True(vm.HasStagedItems);
                Assert.False(vm.HasNoStagedItems);
                Assert.Equal(2, vm.StagedCount);

                // Find items control in send page
                var itemsControl = sendPage.GetVisualDescendants().OfType<ItemsControl>().First();
                Assert.NotNull(itemsControl);

                // Remove the first item via its RemoveCommand
                var firstItem = vm.StagedItems[0];
                Assert.NotNull(firstItem.RemoveCommand);
                Assert.True(firstItem.RemoveCommand.CanExecute(null));
                firstItem.RemoveCommand.Execute(null);

                Assert.Equal(1, vm.StagedCount);
                Assert.Equal("archive.tar", vm.StagedItems[0].Name);

                window.Close();
            }, CancellationToken.None);
        }
        finally
        {
            if (Directory.Exists(tempDir))
            {
                Directory.Delete(tempDir, true);
            }
        }
    }

    [Fact]
    public async Task SendPage_AtMinimumWindowWidth_DoesNotRequireHorizontalScrolling()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var vm = new LinuxMainViewModel();
            var window = new MainWindow { DataContext = vm, Width = 760, Height = 520 };
            window.Show();
            window.UpdateLayout();

            var sendPage = window.GetVisualDescendants().OfType<SendPage>().Single();
            var scrollViewer = sendPage.GetVisualDescendants().OfType<ScrollViewer>().First();
            var content = Assert.IsType<StackPanel>(scrollViewer.Content);
            Assert.True(content.Bounds.Width <= scrollViewer.Bounds.Width + 1,
                $"Send content exceeds the available width: {content.Bounds.Width} > {scrollViewer.Bounds.Width}");

            window.Close();
        }, CancellationToken.None);
    }
}
