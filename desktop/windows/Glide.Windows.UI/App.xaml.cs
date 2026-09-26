using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using Glide.Windows.UI.ViewModels;
using Glide.Windows.UI.Views;

namespace Glide.Windows.UI;

public partial class App : Application
{
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

        base.OnStartup(e);

        var mainWindow = new MainWindow();
        MainWindow = mainWindow;

        if (mainWindow.DataContext is MainViewModel vm && e.Args.Length > 0)
        {
            var filesToQueue = new List<string>();
            foreach (string arg in e.Args)
            {
                if (arg.StartsWith("glide://", StringComparison.OrdinalIgnoreCase))
                {
                    vm.PairInputUri = arg;
                    vm.SubmitPairModalCommand.Execute(null);
                }
                else if (File.Exists(arg))
                {
                    filesToQueue.Add(Path.GetFullPath(arg));
                }
                else if (Directory.Exists(arg))
                {
                    try
                    {
                        filesToQueue.AddRange(Directory.GetFiles(arg, "*", SearchOption.AllDirectories));
                    }
                    catch
                    {
                        // Ignore inaccessible subdirectories
                    }
                }
            }

            if (filesToQueue.Count > 0)
            {
                vm.AddDroppedFiles(filesToQueue.ToArray());
                vm.CurrentTab = "Send";
            }
        }

        mainWindow.Show();
    }

    private static void RunSelfUninstall(bool silent)
    {
        string installDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (!silent)
        {
            var confirm = MessageBox.Show(
                $"Are you sure you want to uninstall Glide from:\n{installDir}\n\nYour received files in Downloads will NOT be deleted.",
                "Uninstall Glide",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (confirm != MessageBoxResult.Yes)
            {
                return;
            }
        }

        try
        {
            Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(
                @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Glide",
                throwOnMissingSubKey: false);
        }
        catch { }

        try
        {
            string startMenuLnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs", "Glide.lnk");
            string desktopLnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Glide.lnk");
            string sendToLnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Send with Glide.lnk");
            if (File.Exists(startMenuLnk)) File.Delete(startMenuLnk);
            if (File.Exists(desktopLnk)) File.Delete(desktopLnk);
            if (File.Exists(sendToLnk)) File.Delete(sendToLnk);
        }
        catch { }

        try
        {
            if (Directory.Exists(installDir))
            {
                string cmdCleanup = $"/C timeout /t 2 /nobreak > NUL & rmdir /s /q \"{installDir}\"";
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = cmdCleanup,
                    CreateNoWindow = true,
                    UseShellExecute = false
                });
            }
        }
        catch { }

        if (!silent)
        {
            MessageBox.Show("Glide has been uninstalled from your computer.", "Uninstall Complete", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }
}

