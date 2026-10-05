using Avalonia;
using Avalonia.Automation;
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
using System.Net.NetworkInformation;
using Xunit;

[assembly: AvaloniaTestApplication(typeof(Glide.Tests.GlideTestAppBuilder))]

namespace Glide.Tests;

public static class GlideTestAppBuilder
{
    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<Glide.Linux.UI.App>()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions());
}

public static class HeadlessSetup
{
    private static HeadlessUnitTestSession? _session;
    private static readonly object _lock = new();

    public static HeadlessUnitTestSession GetSession()
    {
        lock (_lock)
        {
            return _session ??= HeadlessUnitTestSession.GetOrStartForAssembly(typeof(LinuxRenderedUiTests).Assembly);
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
            Assert.Equal(Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
                scrollViewer.HorizontalScrollBarVisibility);
            var content = Assert.IsType<StackPanel>(scrollViewer.Content);
            Assert.True(content.Bounds.Width <= scrollViewer.Bounds.Width + 1,
                $"Send content exceeds the available width: {content.Bounds.Width} > {scrollViewer.Bounds.Width}");

            window.Close();
        }, CancellationToken.None);
    }

    [Fact]
    public async Task MainWindow_BrandHeader_UsesCommittedGlideLogo()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var vm = new LinuxMainViewModel();
            var window = new MainWindow { DataContext = vm };
            window.Show();
            window.UpdateLayout();

            var logoImage = window.GetVisualDescendants()
                .OfType<Image>()
                .FirstOrDefault(img => AutomationProperties.GetName(img) == "Glide");

            Assert.NotNull(logoImage);
            Assert.True(logoImage.Width >= 120 && logoImage.Width <= 128);
            Assert.NotNull(logoImage.Source);

            window.Close();
        }, CancellationToken.None);
    }

    [Fact]
    public async Task ThemeSwitch_ResolvesThemeSpecificLogoAsset()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var app = Application.Current;
            Assert.NotNull(app);

            // Test Dark Theme resource
            app.RequestedThemeVariant = ThemeVariant.Dark;
            bool foundDark = app.TryFindResource("GlideLogoHorizontal", out var darkLogo);
            Assert.True(foundDark);
            Assert.NotNull(darkLogo);

            // Test Light Theme resource
            app.RequestedThemeVariant = ThemeVariant.Light;
            bool foundLight = app.TryFindResource("GlideLogoHorizontal", out var lightLogo);
            Assert.True(foundLight);
            Assert.NotNull(lightLogo);

            // Reset
            app.RequestedThemeVariant = ThemeVariant.Dark;
        }, CancellationToken.None);
    }

    [Fact]
    public async Task ReceivePage_NatOnlyState_DisplaysGuidanceAndDisablesQr()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(() =>
        {
            var testProvider = new TestNetworkSnapshotProvider();
            testProvider.SetInterfaces(new List<Glide.Desktop.Network.DesktopNetworkInterface>
            {
                new(
                    Id: "eth0",
                    Name: "eth0",
                    InterfaceType: NetworkInterfaceType.Ethernet,
                    OperationalStatus: OperationalStatus.Up,
                    UnicastAddresses: new[]
                    {
                        new Glide.Desktop.Network.DesktopUnicastAddress(System.Net.IPAddress.Parse("10.0.2.15"), System.Net.IPAddress.Parse("255.255.255.0"), 24)
                    },
                    GatewayAddresses: new[] { System.Net.IPAddress.Parse("10.0.2.2") }
                )
            });

            var vm = new LinuxMainViewModel(networkSnapshotProvider: testProvider);
            var window = new MainWindow { DataContext = vm };
            window.Show();

            vm.SwitchTabCommand.Execute("Receive");
            window.UpdateLayout();

            Assert.False(vm.IsNetworkReady);
            Assert.True(vm.IsVirtualMachineNatOnly);
            Assert.Contains("Bridged Adapter", vm.NetworkGuidanceMessage);

            // Receive page must show warning banner
            var receivePage = window.GetVisualDescendants().OfType<ReceivePage>().Single();
            var warningBanners = receivePage.GetVisualDescendants()
                .OfType<Border>()
                .Where(b => AutomationProperties.GetName(b) == "Network reachability warning")
                .ToList();

            Assert.NotEmpty(warningBanners);

            window.Close();
        }, CancellationToken.None);
    }

    [Theory]
    [InlineData("Send")]
    [InlineData("Receive")]
    public async Task NatGuidance_WrapsAtMinimumWindowWidth(string pageName)
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(async () =>
        {
            var testProvider = new TestNetworkSnapshotProvider();
            testProvider.SetInterfaces(new List<Glide.Desktop.Network.DesktopNetworkInterface>
            {
                new(
                    Id: "eth0",
                    Name: "eth0",
                    InterfaceType: NetworkInterfaceType.Ethernet,
                    OperationalStatus: OperationalStatus.Up,
                    UnicastAddresses: new[]
                    {
                        new Glide.Desktop.Network.DesktopUnicastAddress(System.Net.IPAddress.Parse("10.0.2.15"), System.Net.IPAddress.Parse("255.255.255.0"), 24)
                    },
                    GatewayAddresses: new[] { System.Net.IPAddress.Parse("10.0.2.2") }
                )
            });

            using var vm = new LinuxMainViewModel(networkSnapshotProvider: testProvider);
            var window = new MainWindow { DataContext = vm, Width = 760, Height = 520 };
            window.Show();

            if (pageName == "Receive")
            {
                vm.SwitchTabCommand.Execute("Receive");
                await vm.ToggleListenerCommand.ExecuteAsync(null);
            }
            window.UpdateLayout();

            var banner = window.GetVisualDescendants()
                .OfType<Border>()
                .Single(b => b.IsVisible && AutomationProperties.GetName(b) == "Network reachability warning");
            var guidance = banner.GetVisualDescendants()
                .OfType<TextBlock>()
                .Single(t => t.Text == vm.NetworkGuidanceMessage);

            Assert.True(guidance.Bounds.Height >= 36, $"{pageName} guidance did not wrap at 760 DIP.");
            Assert.True(guidance.Bounds.Width <= banner.Bounds.Width - 40,
                $"{pageName} guidance exceeded its warning banner width.");

            window.Close();
        }, CancellationToken.None);
    }

    [Fact]
    public async Task ReceivePage_ReadyState_DisplaysQrAndConnectionDetails()
    {
        var session = HeadlessSetup.GetSession();
        await session.Dispatch(async () =>
        {
            var testProvider = new TestNetworkSnapshotProvider();
            testProvider.SetInterfaces(new List<Glide.Desktop.Network.DesktopNetworkInterface>
            {
                new(
                    Id: "eth0",
                    Name: "eth0",
                    InterfaceType: NetworkInterfaceType.Ethernet,
                    OperationalStatus: OperationalStatus.Up,
                    UnicastAddresses: new[]
                    {
                        new Glide.Desktop.Network.DesktopUnicastAddress(System.Net.IPAddress.Parse("192.168.0.55"), System.Net.IPAddress.Parse("255.255.255.0"), 24)
                    },
                    GatewayAddresses: new[] { System.Net.IPAddress.Parse("192.168.0.1") }
                )
            });

            var vm = new LinuxMainViewModel(networkSnapshotProvider: testProvider);
            var window = new MainWindow { DataContext = vm };
            window.Show();

            vm.SwitchTabCommand.Execute("Receive");
            window.UpdateLayout();

            Assert.True(vm.IsNetworkReady);
            Assert.False(vm.IsVirtualMachineNatOnly);

            // Toggle listener on
            await vm.ToggleListenerCommand.ExecuteAsync(null);
            window.UpdateLayout();

            Assert.True(vm.IsListening);
            Assert.Contains("192.168.0.55", vm.ConnectionDetailsText);

            var receivePage = window.GetVisualDescendants().OfType<ReceivePage>().Single();
            var expanders = receivePage.GetVisualDescendants().OfType<Expander>().ToList();
            Assert.Contains(expanders, e => e.Header?.ToString() == "Connection details");

            window.Close();
        }, CancellationToken.None);
    }

    private sealed class TestNetworkSnapshotProvider : Glide.Desktop.Network.IDesktopNetworkSnapshotProvider
    {
        private List<Glide.Desktop.Network.DesktopNetworkInterface> _interfaces = new();
        public event EventHandler? NetworkAddressChanged;

        public void SetInterfaces(List<Glide.Desktop.Network.DesktopNetworkInterface> interfaces)
        {
            _interfaces = interfaces;
        }

        public void FireNetworkAddressChanged() => NetworkAddressChanged?.Invoke(this, EventArgs.Empty);
        public IReadOnlyList<Glide.Desktop.Network.DesktopNetworkInterface> GetSnapshot() => _interfaces;
    }
}
