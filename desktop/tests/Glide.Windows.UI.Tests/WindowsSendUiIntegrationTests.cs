using System.Runtime.ExceptionServices;
using System.IO;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Automation.Peers;
using System.Windows.Automation.Provider;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Glide.Desktop;
using Rectangle = System.Windows.Shapes.Rectangle;
using Glide.Windows.UI.ViewModels;
using Glide.Windows.UI.Views;
using Glide.Core.Security;

namespace Glide.Windows.UI.Tests;

public sealed class WindowsSendUiIntegrationTests
{
    [Fact]
    public void ChoosingOneSendMethod_ControlsPairingAndSendVisibility()
    {
        Exception? failure = null;
        var completed = new ManualResetEventSlim();
        var thread = new Thread(() =>
        {
            string testFile = Path.Combine(
                Path.GetTempPath(),
                $"glide-wpf-send-{Guid.NewGuid():N}.txt");
            MainWindow? window = null;
            Glide.Windows.UI.App? application = null;

            try
            {
                File.WriteAllText(testFile, "Glide WPF send-button integration test.");

                application = new Glide.Windows.UI.App();
                application.InitializeComponent();
                foreach (string icon in new[]
                {
                    "IconSend", "IconReceive", "IconActivity", "IconSettings",
                    "IconFolderOpen", "IconDocumentAdd", "IconFolderAdd",
                    "IconPhone", "IconLaptop", "IconRefresh"
                })
                {
                    Assert.IsAssignableFrom<Geometry>(application.FindResource(icon));
                }
                window = new MainWindow
                {
                    ShowActivated = false,
                    ShowInTaskbar = false,
                    Opacity = 0
                };
                window.Show();
                window.UpdateLayout();

                var viewModel = Assert.IsType<MainViewModel>(window.DataContext);
                string[] destinations = ["Send files", "Receive files", "Transfer activity", "Pair devices", "Settings"];
                foreach (string destination in destinations)
                {
                    RadioButton navigationButton = Descendants(window).OfType<RadioButton>()
                        .Single(candidate => AutomationProperties.GetName(candidate) == destination);
                    Assert.True(navigationButton.ActualWidth > 0);
                    Assert.True(navigationButton.ActualHeight > 0);
                }
                window.Width = 820;
                window.Height = 560;
                foreach (string tab in new[] { "Send", "Receive", "Activity", "Pair devices", "Settings" })
                {
                    viewModel.CurrentTab = tab;
                    window.UpdateLayout();
                    string pageName = tab == "Pair devices" ? "PairDevicesPage" : $"{tab}Page";
                    var page = Assert.IsType<ScrollViewer>(window.FindName(pageName));
                    Assert.Equal(Visibility.Visible, page.Visibility);
                    Assert.True(page.ViewportWidth > 0);
                    Assert.Equal(0, page.ScrollableWidth);
                }
                viewModel.CurrentTab = "Send";
                window.UpdateLayout();
                var dropZone = Assert.IsType<Border>(window.FindName("SendDropZone"));
                var emptyFileState = Assert.IsType<StackPanel>(window.FindName("EmptyFileState"));
                var selectedFileState = Assert.IsType<Grid>(window.FindName("SelectedFileState"));
                var sendPage = Assert.IsType<ScrollViewer>(window.FindName("SendPage"));
                Assert.True(dropZone.ActualWidth <= sendPage.ViewportWidth + 1);
                Assert.True(dropZone.AllowDrop);
                Assert.Equal(Visibility.Visible, emptyFileState.Visibility);
                Assert.Equal(Visibility.Collapsed, selectedFileState.Visibility);
                double emptyCardHeight = dropZone.ActualHeight;
                viewModel.CurrentTab = "Receive";
                window.UpdateLayout();
                var receiveButton = Assert.IsType<Button>(window.FindName("ReceiveControlButton"));
                var receivePage = Assert.IsType<ScrollViewer>(window.FindName("ReceivePage"));
                Assert.True(receiveButton.ActualWidth <= receivePage.ViewportWidth);
                Button showQr = Assert.IsType<Button>(window.FindName("ReceiveShowQrButton"));
                Button copyLink = Assert.IsType<Button>(window.FindName("ReceiveCopyLinkButton"));
                Button refreshQr = Assert.IsType<Button>(window.FindName("ReceiveRefreshQrButton"));
                Assert.Equal(showQr.ActualWidth, copyLink.ActualWidth);
                Assert.Equal(showQr.ActualWidth, refreshQr.ActualWidth);
                Assert.Equal(showQr.TranslatePoint(new Point(), window).X,
                    copyLink.TranslatePoint(new Point(), window).X, 1);
                Assert.Equal(showQr.TranslatePoint(new Point(), window).X,
                    refreshQr.TranslatePoint(new Point(), window).X, 1);
                viewModel.TrustedDeviceCount = 0;
                Assert.StartsWith("No trusted devices", viewModel.TrustedDevicesSummary);
                viewModel.TrustedDeviceCount = 1;
                Assert.StartsWith("1 device trusted", viewModel.TrustedDevicesSummary);
                viewModel.TrustedDeviceCount = 3;
                Assert.StartsWith("3 devices trusted", viewModel.TrustedDevicesSummary);
                viewModel.CurrentTab = "Activity";
                window.UpdateLayout();
                var historyList = Assert.IsType<ListBox>(window.FindName("ActivityHistoryList"));
                var activityPage = Assert.IsType<ScrollViewer>(window.FindName("ActivityPage"));
                Assert.True(historyList.ActualWidth <= activityPage.ViewportWidth + 1);
                var historyActions = Assert.IsType<StackPanel>(window.FindName("ActivityHistoryActions"));
                Assert.Equal(0, Grid.GetRow(historyActions));
                Assert.True(historyActions.TranslatePoint(new Point(), window).X >
                    activityPage.TranslatePoint(new Point(), window).X + activityPage.ActualWidth / 2);
                var progressBar = Assert.IsType<ProgressBar>(window.FindName("ActivityTransferProgressBar"));
                Guid progressRun = Guid.NewGuid();
                viewModel.Transfer.ApplySnapshot(new DesktopTransferSnapshot(
                    DesktopTransferPhase.Transferring, DesktopTransferDirection.Send,
                    "Test desktop", "Transferring", RunId: progressRun));
                viewModel.Transfer.ApplyEvent(new TransferEvent.ProgressUpdated(
                    new DesktopTransferProgress(DesktopTransferDirection.Send,
                        "Test desktop", "large-file.bin", 6, 65),
                    1, "1 B/s", "6 B / 65 B"));
                window.UpdateLayout();
                progressBar.ApplyTemplate();
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                var indicator = Assert.IsType<Rectangle>(
                    progressBar.Template.FindName("PART_Indicator", progressBar));
                double initialFillWidth = indicator.ActualWidth;
                viewModel.Transfer.ApplyEvent(new TransferEvent.ProgressUpdated(
                    new DesktopTransferProgress(DesktopTransferDirection.Send,
                        "Test desktop", "large-file.bin", 61, 65),
                    1, "1 B/s", "61 B / 65 B"));
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                Assert.InRange(progressBar.Value, 93, 95);
                Assert.True(indicator.ActualWidth > progressBar.ActualWidth * 0.8,
                    $"The progress fill stayed empty: value={progressBar.Value}, " +
                    $"bar width={progressBar.ActualWidth}, fill width={indicator.ActualWidth}.");
                Assert.True(indicator.ActualWidth > initialFillWidth * 3,
                    $"The progress fill did not advance: initial={initialFillWidth}, current={indicator.ActualWidth}.");
                viewModel.Transfer.ApplySnapshot(DesktopTransferSnapshot.Idle);
                viewModel.CurrentTab = "Send";
                viewModel.AddDroppedFiles([testFile]);
                window.Dispatcher.Invoke(
                    () => { },
                    DispatcherPriority.Background);
                window.UpdateLayout();
                Assert.Equal(Visibility.Collapsed, emptyFileState.Visibility);
                Assert.Equal(Visibility.Visible, selectedFileState.Visibility);
                Assert.True(dropZone.ActualHeight < emptyCardHeight);
                Assert.Contains("1 file", viewModel.SelectedFilesSummary);
                Assert.Contains("B", viewModel.SelectedFilesSummary);
                var selectedFilesList = Assert.IsType<ListBox>(window.FindName("SelectedFilesList"));
                Assert.Single(selectedFilesList.Items);
                Assert.Contains(Descendants(selectedFileState).OfType<Button>(),
                    button => AutomationProperties.GetName(button) == "Add more files to send");
                Assert.Contains(Descendants(selectedFileState).OfType<Button>(),
                    button => AutomationProperties.GetName(button) == "Add another folder to send");
                window.Width = 500;
                window.Height = 520;
                PumpDispatcherUntil(() => window.ActualWidth < 640, TimeSpan.FromSeconds(2));
                window.UpdateLayout();
                Assert.Equal(500, window.MinWidth);
                Assert.Equal(480, window.MinHeight);
                Assert.Equal(64, Assert.IsType<ColumnDefinition>(window.FindName("SidebarColumn")).ActualWidth);
                Assert.Equal(Visibility.Collapsed,
                    Assert.IsType<TextBlock>(window.FindName("NavSendLabel")).Visibility);
                Assert.True(window.ActualWidth < 640,
                    $"The test window did not reach the narrow layout: Width={window.Width}, ActualWidth={window.ActualWidth}, " +
                    $"RootDesired={Assert.IsType<Grid>(window.Content).DesiredSize.Width}, " +
                    $"MainDesired={Assert.IsType<Grid>(window.FindName("MainContent")).DesiredSize.Width}, " +
                    $"MainActual={Assert.IsType<Grid>(window.FindName("MainContent")).ActualWidth}, " +
                    $"MainChildren={string.Join(",", Assert.IsType<Grid>(window.FindName("MainContent")).Children.Cast<FrameworkElement>().Select(child => child.DesiredSize.Width))}, " +
                    $"SendDesired={sendPage.DesiredSize.Width}, DropDesired={dropZone.DesiredSize.Width}, " +
                    $"HeaderDesired={Assert.IsType<Grid>(window.FindName("SelectedFileHeader")).DesiredSize.Width}, " +
                    $"FileListDesired={selectedFilesList.DesiredSize.Width}, " +
                    $"MethodDesired={Descendants(window).OfType<RadioButton>().Single(button => AutomationProperties.GetName(button) == "Quick share with QR code").DesiredSize.Width}.");
                Assert.Equal(1, Grid.GetRow(Assert.IsType<StackPanel>(window.FindName("SelectedFileActions"))));
                Assert.Equal(Orientation.Vertical,
                    Assert.IsType<StackPanel>(window.FindName("ReceiveQrLayout")).Orientation);
                Assert.Equal(1, Grid.GetRow(historyActions));
                Assert.Equal(1, Grid.GetRow(Assert.IsType<StackPanel>(window.FindName("ActivityTransferActions"))));
                foreach (string tab in new[] { "Send", "Receive", "Activity", "Pair devices", "Settings" })
                {
                    viewModel.CurrentTab = tab;
                    window.UpdateLayout();
                    string pageName = tab == "Pair devices" ? "PairDevicesPage" : $"{tab}Page";
                    var page = Assert.IsType<ScrollViewer>(window.FindName(pageName));
                    Assert.True(page.ViewportWidth > 0);
                    Assert.True(page.ScrollableWidth <= 1, $"{tab} overflows the 500-DIP Snap width by {page.ScrollableWidth}.");
                }
                viewModel.CurrentTab = "Send";
                window.Width = 1040;
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                Assert.Equal(212, Assert.IsType<ColumnDefinition>(window.FindName("SidebarColumn")).ActualWidth);
                Assert.Equal(Visibility.Visible,
                    Assert.IsType<TextBlock>(window.FindName("NavSendLabel")).Visibility);
                window.Width = 820;
                window.Height = 560;
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                var sendButton = Assert.IsType<Button>(window.FindName("SendChosenDeviceButton"));
                Assert.Equal(Visibility.Collapsed, sendButton.Visibility);
                Assert.False(viewModel.IsSendActionVisible);

                TextBlock fileName = Descendants(window).OfType<TextBlock>()
                    .Single(text => text.Text == Path.GetFileName(testFile));
                string originalAppearance = viewModel.AppearanceMode;
                try
                {
                    viewModel.AppearanceMode = "Dark";
                    window.UpdateLayout();
                    Color foreground = Assert.IsType<SolidColorBrush>(fileName.Foreground).Color;
                    Color surface = Assert.IsType<SolidColorBrush>(application.Resources["SurfaceBrush"]).Color;
                    Assert.True(foreground.R > 200 && foreground.G > 200 && foreground.B > 200);
                    Assert.True(surface.R < 80 && surface.G < 80 && surface.B < 80);
                }
                finally
                {
                    viewModel.AppearanceMode = originalAppearance;
                }

                RadioButton quickQr = Descendants(window).OfType<RadioButton>()
                    .Single(button => AutomationProperties.GetName(button) == "Quick share with QR code");
                window.Width = 500;
                window.Height = 480;
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                Assert.True(quickQr.IsEnabled);
                quickQr.Command.Execute(quickQr.CommandParameter);
                PumpDispatcherUntil(() => viewModel.IsPairModalVisible, TimeSpan.FromSeconds(5));
                window.UpdateLayout();

                Assert.Equal(SendMethodChoice.QuickQr, viewModel.SendMethod);
                Assert.True(viewModel.IsPairModalVisible);
                Assert.True(viewModel.IsHostedSendQrVisible);
                Assert.Equal(Visibility.Collapsed, sendButton.Visibility);
                Assert.Contains("Scan the QR code", viewModel.StatusMessage);

                Image qrImage = Descendants(window)
                    .OfType<Image>()
                    .Single(image =>
                        AutomationProperties.GetName(image) ==
                        "QR code for receiving selected files");
                Assert.Equal(Visibility.Visible, qrImage.Visibility);
                PumpDispatcherUntil(() =>
                {
                    System.Windows.Data.BindingOperations.GetBindingExpression(qrImage, Image.SourceProperty)?.UpdateTarget();
                    return qrImage.Source != null;
                }, TimeSpan.FromSeconds(10), window);
                Assert.NotNull(qrImage.Source);
                var cancelQuickShare = Assert.IsType<Button>(window.FindName("CancelQuickShareButton"));
                double cancelBottom = cancelQuickShare.TranslatePoint(
                    new Point(0, cancelQuickShare.ActualHeight), window).Y;
                Assert.True(cancelBottom <= window.ActualHeight,
                    "The QR popup's Cancel action must remain visible at the minimum window size.");

                viewModel.CancelPairModalCommand.Execute(null);
                window.Width = 820;
                window.Height = 560;
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                viewModel.CurrentTab = "Pair devices";
                window.UpdateLayout();
                Assert.Contains(Descendants(window).OfType<Button>(), button =>
                    AutomationProperties.GetName(button) == "Find pair-ready Glide devices");
                Assert.Contains(Descendants(window).OfType<Button>(), button =>
                    AutomationProperties.GetName(button) == "Show pairing code for this PC");
                viewModel.CurrentTab = "Send";
                Assert.DoesNotContain(Descendants(window).OfType<RadioButton>(), button =>
                    AutomationProperties.GetName(button) == "Nearby device");
                RadioButton trusted = Descendants(window).OfType<RadioButton>()
                    .Single(button => AutomationProperties.GetName(button) == "Paired device");
                trusted.Command.Execute(trusted.CommandParameter);
                window.Width = 500;
                window.Height = 480;
                PumpDispatcherUntil(() => window.ActualWidth < 640, TimeSpan.FromSeconds(2));
                window.UpdateLayout();
                Assert.True(viewModel.IsTrustedDevicePickerVisible);
                Assert.Equal(Visibility.Collapsed, sendButton.Visibility);
                viewModel.TrustedDevices.Add(new TrustedDeviceViewModel(new DesktopTrustedPeer(
                    new string('b', 64), "00", "ubuntu with a deliberately long device name for layout", 1, 1)));
                viewModel.TrustedDevices.Add(new TrustedDeviceViewModel(new DesktopTrustedPeer(
                    new string('c', 64), "00", "ubuntu", 1, 1)));
                window.UpdateLayout();
                var pickerRows = Descendants(Assert.IsType<ScrollViewer>(window.FindName("TrustedDevicesScroll")))
                    .OfType<Button>().Where(button =>
                        AutomationProperties.GetName(button)?.StartsWith("Connect to ") == true).ToList();
                Assert.True(pickerRows.Count >= 2);
                Assert.Contains(pickerRows, row => AutomationProperties.GetName(row).Contains("bbbbbbbb"));
                Assert.Contains(pickerRows, row => AutomationProperties.GetName(row).Contains("cccccccc"));
                foreach (Button row in pickerRows)
                {
                    var rowGrid = Assert.IsType<Grid>(row.Content);
                    TextBlock connect = rowGrid.Children.OfType<TextBlock>().Single(t => t.Text == "Connect");
                    Assert.True(connect.TranslatePoint(new Point(), row).X > row.ActualWidth / 2);
                    Assert.True(connect.TranslatePoint(new Point(connect.ActualWidth, 0), row).X <= row.ActualWidth + 1);
                }
                var closePicker = Assert.IsType<Button>(window.FindName("CloseTrustedDevicePickerButton"));
                Assert.True(closePicker.TranslatePoint(new Point(0, closePicker.ActualHeight), window).Y <= window.ActualHeight);
                var savedDevice = new TrustedDeviceViewModel(
                    new DesktopTrustedPeer(new string('a', 64), "00", "Saved test device", 1, 1));
                viewModel.SelectTrustedDeviceCommand.Execute(savedDevice);
                window.UpdateLayout();
                Assert.Equal("Send", viewModel.CurrentTab);
                Assert.False(viewModel.IsTrustedDevicePickerVisible);
                Assert.False(viewModel.IsSavedDeviceConnectVisible);
                Assert.True(viewModel.IsSendActionVisible);
                Assert.Equal(Visibility.Visible, sendButton.Visibility);
                Assert.Equal(string.Empty, viewModel.PairInputUri);
                window.Width = 820;
                window.Height = 560;
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                window.UpdateLayout();
                sendPage.ScrollToVerticalOffset(50);
                window.UpdateLayout();
                viewModel.ClearFilesCommand.Execute(null);
                window.Dispatcher.Invoke(
                    () => { },
                    DispatcherPriority.Background);
                window.UpdateLayout();
                Assert.Equal(Visibility.Visible, emptyFileState.Visibility);
                Assert.Equal(Visibility.Collapsed, selectedFileState.Visibility);
                Assert.Equal(SendMethodChoice.None, viewModel.SendMethod);
                Assert.Equal("Add files or a folder to get started.", viewModel.StatusMessage);
                Assert.Equal(0, sendPage.VerticalOffset);
                viewModel.AddDroppedFiles([testFile]);
                viewModel.RemoveFileCommand.Execute(Assert.Single(viewModel.SelectedFiles));
                window.UpdateLayout();
                Assert.Equal(Visibility.Visible, emptyFileState.Visibility);
                Assert.Equal(Visibility.Collapsed, selectedFileState.Visibility);
                viewModel.CurrentTab = "Pair devices";
                viewModel.FindPairDevicesCommand.Execute(null);
                window.UpdateLayout();
                Assert.True(viewModel.IsPairDiscovery);
                Assert.False(viewModel.HasSelectedFiles);
                viewModel.CancelPairCommand.Execute(null);
            }
            catch (Exception ex)
            {
                failure = ex;
            }
            finally
            {
                try
                {
                    if (window?.DataContext is MainViewModel viewModel &&
                        viewModel.IsListening)
                    {
                        viewModel.ToggleListenerCommand.Execute(null);
                    }
                    window?.Close();
                    application?.Shutdown();
                    if (File.Exists(testFile))
                    {
                        File.Delete(testFile);
                    }
                }
                finally
                {
                    completed.Set();
                }
            }
        });

        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();

        Assert.True(
            completed.Wait(TimeSpan.FromSeconds(30)),
            "The WPF send-button test did not complete within 30 seconds.");
        thread.Join();

        if (failure is not null)
        {
            ExceptionDispatchInfo.Capture(failure).Throw();
        }
    }

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        int childCount = VisualTreeHelper.GetChildrenCount(root);
        for (int index = 0; index < childCount; index++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, index);
            yield return child;
            foreach (DependencyObject descendant in Descendants(child))
            {
                yield return descendant;
            }
        }
    }

    private static Border ContainingCard(DependencyObject element)
    {
        DependencyObject? ancestor = VisualTreeHelper.GetParent(element);
        while (ancestor is not null)
        {
            if (ancestor is Border border) return border;
            ancestor = VisualTreeHelper.GetParent(ancestor);
        }
        throw new InvalidOperationException("The pairing panel is not inside a card.");
    }

    private static void PumpDispatcherUntil(
        Func<bool> condition,
        TimeSpan timeout,
        UIElement? visualToUpdate = null)
    {
        DateTime deadline = DateTime.UtcNow + timeout;
        while (!condition() && DateTime.UtcNow < deadline)
        {
            var frame = new DispatcherFrame();
            Dispatcher.CurrentDispatcher.BeginInvoke(
                DispatcherPriority.Background,
                new Action(() => frame.Continue = false));
            Dispatcher.PushFrame(frame);
            visualToUpdate?.UpdateLayout();
            Thread.Sleep(20);
        }
    }
}
