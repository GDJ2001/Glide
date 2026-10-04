using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Glide.Core.Security;
using Glide.Desktop;
using Glide.Linux.UI.ViewModels;
using Glide.Linux.UI.Views;

namespace Glide.Linux.UI;

public partial class App : Application
{
    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var viewModel = new LinuxMainViewModel();
            var mainWindow = new MainWindow
            {
                DataContext = viewModel
            };

            if (desktop.Args is { Length: > 0 })
            {
                var paths = new List<string>();
                for (int i = 0; i < desktop.Args.Length; i++)
                {
                    string arg = desktop.Args[i];
                    if (arg == "--test-modal-sas")
                    {
                        _ = viewModel.ConfirmPeerEnrollmentAsync(
                            new VerifiedDesktopPeerIdentity("peer-1", "MacBook Pro", "A1B2C3D4E5F67890123456789ABCDEF0"),
                            CancellationToken.None);
                    }
                    else if (arg == "--test-modal-offer")
                    {
                        _ = viewModel.ConfirmOfferAsync(
                            new DesktopOffer("batch-1", "Pixel 8 Pro", new[] { new DesktopOfferItem("item-1", "presentation.pdf", 4194304, "presentation.pdf") }, 4194304),
                            CancellationToken.None);
                    }
                    else if (arg == "--test-modal-recipient")
                    {
                        _ = viewModel.ConfirmRecipientAsync("Ubuntu Studio 26.04", CancellationToken.None);
                    }
                    else if (arg == "--tab" && i + 1 < desktop.Args.Length)
                    {
                        viewModel.SwitchTabCommand.Execute(desktop.Args[++i]);
                    }
                    else if (arg == "--theme" && i + 1 < desktop.Args.Length)
                    {
                        viewModel.SetThemeCommand.Execute(desktop.Args[++i]);
                    }
                    else if (arg == "--width" && i + 1 < desktop.Args.Length && double.TryParse(desktop.Args[i + 1], out double w))
                    {
                        mainWindow.Width = w;
                        i++;
                    }
                    else if (arg == "--height" && i + 1 < desktop.Args.Length && double.TryParse(desktop.Args[i + 1], out double h))
                    {
                        mainWindow.Height = h;
                        i++;
                    }
                    else if (arg == "--auto-confirm")
                    {
                        viewModel.AutoConfirmForTesting = true;
                    }
                    else if (arg == "--pair" && i + 1 < desktop.Args.Length)
                    {
                        viewModel.InvitationInput = desktop.Args[++i];
                    }
                    else if (arg == "--listen")
                    {
                        if (!viewModel.IsListening)
                        {
                            _ = viewModel.ToggleListenerCommand.ExecuteAsync(null);
                        }
                    }
                    else if (!arg.StartsWith("--"))
                    {
                        paths.Add(arg);
                    }
                }

                if (paths.Count > 0)
                {
                    viewModel.StagePaths(paths);
                }

                if (desktop.Args.Contains("--send"))
                {
                    _ = Task.Run(async () =>
                    {
                        await Task.Delay(1000);
                        await Avalonia.Threading.Dispatcher.UIThread.InvokeAsync(async () =>
                        {
                            if (viewModel.SendFilesCommand.CanExecute(null))
                            {
                                await viewModel.SendFilesCommand.ExecuteAsync(null);
                            }
                        });
                    });
                }
            }

            desktop.MainWindow = mainWindow;
        }

        base.OnFrameworkInitializationCompleted();
    }
}
