using System.Runtime.ExceptionServices;
using System.IO;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Automation.Peers;
using System.Windows.Automation.Provider;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Glide.Windows.UI.ViewModels;
using Glide.Windows.UI.Views;

namespace Glide.Windows.UI.Tests;

public sealed class WindowsSendUiIntegrationTests
{
    [Fact]
    public void SelectingAFileAndClickingPrimarySend_OpensPhonePairingWorkflow()
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
                window = new MainWindow
                {
                    ShowActivated = false,
                    ShowInTaskbar = false,
                    Opacity = 0
                };
                window.Show();
                window.UpdateLayout();

                var viewModel = Assert.IsType<MainViewModel>(window.DataContext);
                string[] destinations = ["Send files", "Receive files", "Transfer activity", "Settings"];
                foreach (string destination in destinations)
                {
                    RadioButton navigationButton = Descendants(window).OfType<RadioButton>()
                        .Single(candidate => AutomationProperties.GetName(candidate) == destination);
                    Assert.True(navigationButton.ActualWidth > 0);
                    Assert.True(navigationButton.ActualHeight > 0);
                }
                viewModel.AddDroppedFiles([testFile]);
                window.Dispatcher.Invoke(
                    () => { },
                    DispatcherPriority.Background);

                Button sendButton = Descendants(window)
                    .OfType<Button>()
                    .Single(button =>
                        AutomationProperties.GetName(button) ==
                        "Send selected files to phone");

                Assert.Equal(Visibility.Visible, sendButton.Visibility);
                Assert.True(sendButton.ActualWidth > 0);
                Assert.True(sendButton.ActualHeight > 0);
                Assert.True(sendButton.IsEnabled);
                Assert.Equal("Choose phone and send", sendButton.Content);

                var peer = new ButtonAutomationPeer(sendButton);
                var invokeProvider = Assert.IsAssignableFrom<IInvokeProvider>(
                    peer.GetPattern(PatternInterface.Invoke));
                invokeProvider.Invoke();
                window.Dispatcher.Invoke(
                    () => { },
                    DispatcherPriority.Background);
                window.UpdateLayout();

                Assert.True(viewModel.IsPairModalVisible);
                Assert.True(viewModel.IsHostedSendQrVisible);
                Assert.Contains("Scan the QR code", viewModel.StatusMessage);

                Image qrImage = Descendants(window)
                    .OfType<Image>()
                    .Single(image =>
                        AutomationProperties.GetName(image) ==
                        "QR code for receiving selected files");
                Assert.Equal(Visibility.Visible, qrImage.Visibility);
                Assert.NotNull(qrImage.Source);
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

    private static void PumpDispatcherUntil(
        Func<bool> condition,
        TimeSpan timeout)
    {
        DateTime deadline = DateTime.UtcNow + timeout;
        while (!condition() && DateTime.UtcNow < deadline)
        {
            var frame = new DispatcherFrame();
            Dispatcher.CurrentDispatcher.BeginInvoke(
                DispatcherPriority.Background,
                new Action(() => frame.Continue = false));
            Dispatcher.PushFrame(frame);
            Thread.Sleep(20);
        }
    }
}
