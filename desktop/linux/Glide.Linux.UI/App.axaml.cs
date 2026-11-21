using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Glide.Linux.UI.ViewModels;
using Glide.Linux.UI.Views;
using Glide.Linux.UI.Services;

namespace Glide.Linux.UI;

public partial class App : Application
{
    internal static LinuxSingleInstance? PrimaryInstance { get; set; }

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var viewModel = new LinuxMainViewModel(autoStartReceiving: true);
            var mainWindow = new MainWindow
            {
                DataContext = viewModel
            };

            void ApplyLaunchArguments(string[] launchArgs)
            {
                var paths = new List<string>();
                for (int i = 0; i < launchArgs.Length; i++)
                {
                    string arg = launchArgs[i];
                    if (arg == "--tab" && i + 1 < launchArgs.Length)
                    {
                        viewModel.SwitchTabCommand.Execute(launchArgs[++i]);
                    }
                    else if (arg == "--theme" && i + 1 < launchArgs.Length)
                    {
                        viewModel.SetThemeCommand.Execute(launchArgs[++i]);
                    }
                    else if (arg == "--width" && i + 1 < launchArgs.Length && double.TryParse(launchArgs[i + 1], out double w))
                    {
                        mainWindow.Width = w;
                        i++;
                    }
                    else if (arg == "--height" && i + 1 < launchArgs.Length && double.TryParse(launchArgs[i + 1], out double h))
                    {
                        mainWindow.Height = h;
                        i++;
                    }
                    else if (arg == "--pair" && i + 1 < launchArgs.Length)
                    {
                        string link = launchArgs[++i];
                        if (link.StartsWith("glide://pair/v2#", StringComparison.OrdinalIgnoreCase))
                        {
                            viewModel.PairLink = link;
                            viewModel.SwitchTabCommand.Execute("Pair");
                            viewModel.ShowPairLinkCommand.Execute(null);
                        }
                        else
                        {
                            viewModel.InvitationInput = link;
                            viewModel.SwitchTabCommand.Execute("Receive");
                        }
                    }
                    else if (arg == "--listen")
                    {
                        _ = viewModel.StartReceivingOnLaunchAsync();
                    }
                    else if (arg != "--send" && !arg.StartsWith("--", StringComparison.Ordinal))
                    {
                        paths.Add(arg);
                    }
                }

                if (paths.Count > 0)
                {
                    viewModel.StagePaths(paths);
                    viewModel.SwitchTabCommand.Execute("Send");
                }
            }

            if (desktop.Args is { Length: > 0 })
                ApplyLaunchArguments(desktop.Args);

            if (PrimaryInstance is { } instance)
            {
                Action<string[]> onActivated = args =>
                    Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                    {
                        if (mainWindow.WindowState == Avalonia.Controls.WindowState.Minimized)
                            mainWindow.WindowState = Avalonia.Controls.WindowState.Normal;
                        mainWindow.Activate();
                        ApplyLaunchArguments(args);
                    });
                instance.ActivatedWithArguments += onActivated;
                desktop.Exit += (_, _) => instance.ActivatedWithArguments -= onActivated;
            }

            desktop.MainWindow = mainWindow;
        }

        base.OnFrameworkInitializationCompleted();
    }
}
