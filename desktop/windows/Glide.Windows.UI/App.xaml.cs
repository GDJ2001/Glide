using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
using System.Windows;
using Glide.Windows.UI.ViewModels;
using Glide.Windows.UI.Views;
using Glide.Windows.UI.Services;

[assembly: InternalsVisibleTo("Glide.Windows.UI.Tests")]

namespace Glide.Windows.UI;

public partial class App : Application
{
    internal bool IsIntegrationTestHost { get; set; }
    private GlideUpdateCoordinator? _updateCoordinator;
    private WindowsSingleInstance? _singleInstance;
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AllocConsole();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool FreeConsole();

    private const int AttachParentProcess = -1;

    private static readonly HashSet<string> CliCommands = new(StringComparer.OrdinalIgnoreCase)
    {
        "receive",
        "daemon",
        "send",
        "identity",
        "trust",
        "web",
        "help",
        "--help",
        "-h",
        "--version",
        "-v"
    };

    protected override async void OnStartup(StartupEventArgs e)
    {
        if (IsIntegrationTestHost)
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            return;
        }

        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(
                $"An unexpected error occurred in Glide:\n\n{args.Exception.Message}",
                "Glide",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            args.Handled = true;
        };

        if (e.Args.Length > 0 &&
            (string.Equals(e.Args[0], "--uninstall", StringComparison.OrdinalIgnoreCase) ||
             string.Equals(e.Args[0], "/U", StringComparison.OrdinalIgnoreCase)))
        {
            bool silent = e.Args.Any(a =>
                string.Equals(a, "--silent", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(a, "/S", StringComparison.OrdinalIgnoreCase));
            RunSelfUninstall(silent);
            Shutdown(0);
            return;
        }

        if (e.Args.Length > 0 && CliCommands.Contains(e.Args[0]))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            if (!AttachConsole(AttachParentProcess))
            {
                AllocConsole();
            }

            try
            {
                int exitCode = await Glide.App.Program.Main(e.Args);
                FreeConsole();
                Shutdown(exitCode);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"[ERROR] {ex.Message}");
                FreeConsole();
                Shutdown(1);
            }
            return;
        }

        _singleInstance = WindowsSingleInstance.Acquire();
        if (!_singleInstance.IsPrimaryInstance)
        {
            bool forwarded = await WindowsSingleInstance.ForwardActivationAsync(e.Args);
            if (!forwarded)
            {
                MessageBox.Show(
                    "Glide is already starting or running, but it could not receive this request. Try again in a moment.",
                    "Glide",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
            }
            await _singleInstance.DisposeAsync();
            _singleInstance = null;
            Shutdown(0);
            return;
        }

        base.OnStartup(e);

        var mainWindow = new MainWindow();
        MainWindow = mainWindow;

        ActivateArguments(mainWindow, e.Args);

        _singleInstance.Start(arguments => Dispatcher.InvokeAsync(() =>
        {
            ActivateArguments(mainWindow, arguments);
            BringToFront(mainWindow);
        }).Task);

        mainWindow.Show();

        _updateCoordinator = new GlideUpdateCoordinator(
            GlideUpdateConfiguration.FromBuildProperties(),
            () => mainWindow.DataContext is MainViewModel viewModel && viewModel.IsTransferActive);
        _updateCoordinator.StartDailyChecks();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _updateCoordinator?.Dispose();
        // Do not synchronously wait for a pipe handler here: it may already be posting an
        // activation to this dispatcher during shutdown. Process exit also releases the mutex.
        if (_singleInstance is not null)
        {
            _ = _singleInstance.DisposeAsync();
        }
        base.OnExit(e);
    }

    private static void ActivateArguments(MainWindow mainWindow, IReadOnlyList<string> arguments)
    {
        if (mainWindow.DataContext is not MainViewModel vm || arguments.Count == 0)
        {
            return;
        }

        var filesToQueue = new List<string>();
        foreach (string arg in arguments)
        {
            if (arg.StartsWith("glide://", StringComparison.OrdinalIgnoreCase))
            {
                vm.PairInputUri = arg;
                vm.SubmitPairModalCommand.Execute(null);
            }
            else if (File.Exists(arg) || Directory.Exists(arg))
            {
                filesToQueue.Add(Path.GetFullPath(arg));
            }
        }

        if (filesToQueue.Count > 0)
        {
            vm.AddDroppedFiles(filesToQueue.ToArray());
            vm.CurrentTab = "Send";
            BringToFront(mainWindow);
        }
    }

    private static void BringToFront(MainWindow mainWindow)
    {
        if (!mainWindow.IsVisible)
        {
            mainWindow.Show();
        }
        if (mainWindow.WindowState == WindowState.Minimized)
        {
            mainWindow.WindowState = WindowState.Normal;
        }
        mainWindow.Activate();
    }

    private static void RunSelfUninstall(bool silent)
    {
        string uninstallerPath = Path.Combine(AppContext.BaseDirectory, "Uninstall.exe");
        if (!File.Exists(uninstallerPath))
        {
            if (!silent)
            {
                MessageBox.Show(
                    "Glide was not installed with the current installer. Remove the portable application folder manually.",
                    "Glide",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
            }
            return;
        }

        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
        {
            FileName = uninstallerPath,
            Arguments = silent ? "/S" : string.Empty,
            UseShellExecute = true
        });
    }
}

