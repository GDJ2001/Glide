<#
.SYNOPSIS
    Builds the unified single-executable release distribution and interactive Installation Wizard for Glide on Windows.
.DESCRIPTION
    1. Compiles the unified Glide.exe (WPF Desktop GUI + CLI/Daemon in a single executable) targeting win-x64 without .pdb files.
    2. Compiles a lightweight Glide-Uninstall.exe (~150 KB) for clean uninstallation.
    3. Packages glide-windows-win-x64.zip.
    4. Compiles Glide-Setup-v1.0.0-win-x64.exe — a graphical WPF Installation Wizard (with full --silent / --dir / --save-dir / --uninstall / --verify-payload CLI support)
       allowing the user to choose:
         - Where to install Glide (Application Install Folder)
         - Where to save received files (Default Received Files Folder, persisted to %LOCALAPPDATA%\Glide\desktop-state.json)
         - Desktop shortcut, Start Menu shortcut, Explorer "Send with Glide" menu, and User PATH integration.
#>

param(
    [string]$Configuration = "Release",
    [string[]]$Runtimes = @("win-x64")
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."
$WpfProject = Join-Path $RepoRoot "desktop\windows\Glide.Windows.UI\Glide.Windows.UI.csproj"
$IconPath = Join-Path $RepoRoot "desktop\windows\Glide.Windows.UI\glide.ico"
$OutputDir = Join-Path $RepoRoot "build\distributions\windows"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "     Building Glide Unified Windows Distribution & Setup Wizard  " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

foreach ($rid in $Runtimes) {
    $publishDir = Join-Path $OutputDir "stage-$rid"
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
    New-Item -ItemType Directory -Path $publishDir -Force | Out-Null

    Write-Host "`n[1/4] Publishing unified Glide.exe (Desktop GUI + CLI) for $rid ($Configuration)..." -ForegroundColor Yellow
    dotnet publish $WpfProject `
        -c $Configuration `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        /p:IncludeNativeLibrariesForSelfExtract=true `
        /p:DebugType=none `
        /p:DebugSymbols=false `
        -o $publishDir
    if ($LASTEXITCODE -ne 0) { throw "Failed to publish unified Glide.exe for $rid" }

    # Ensure zero .pdb or extra runtimeconfig files exist in publishDir
    Get-ChildItem -Path $publishDir -Filter "*.pdb" -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem -Path $publishDir -Filter "*.runtimeconfig.json" -ErrorAction SilentlyContinue | Remove-Item -Force

    # Create convenience glide.cmd wrapper pointing to the unified Glide.exe
    Set-Content -Path (Join-Path $publishDir "glide.cmd") -Value "@echo off`r`n`"%~dp0Glide.exe`" %*" -Encoding ASCII

    # Create portable zip archive
    $zipPath = Join-Path $OutputDir "glide-windows-$rid.zip"
    Write-Host "[2/3] Packaging portable archive $zipPath..." -ForegroundColor Green
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path "$publishDir\*" -DestinationPath $zipPath -CompressionLevel Optimal

    # Build Full Windows Setup Wizard (Glide-Setup-v1.0.0-win-x64.exe)
    Write-Host "[3/3] Building Interactive Windows Setup Wizard (Glide-Setup-v1.0.0-$rid.exe)..." -ForegroundColor Yellow
    $installerStage = Join-Path $OutputDir "installer-src-$rid"
    if (Test-Path $installerStage) { Remove-Item -Recurse -Force $installerStage }
    New-Item -ItemType Directory -Path $installerStage -Force | Out-Null

    Copy-Item $zipPath (Join-Path $installerStage "payload.zip") -Force
    Copy-Item $IconPath (Join-Path $installerStage "glide.ico") -Force

    $installerCsproj = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework>
    <AssemblyName>Glide-Setup-v1.0.0-$rid</AssemblyName>
    <UseWPF>true</UseWPF>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <PublishSingleFile>true</PublishSingleFile>
    <SelfContained>true</SelfContained>
    <IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
    <ApplicationIcon>glide.ico</ApplicationIcon>
    <DebugType>none</DebugType>
    <DebugSymbols>false</DebugSymbols>
    <Version>1.0.0</Version>
    <Product>Glide Setup Wizard</Product>
    <Company>Glide Open Source Project</Company>
  </PropertyGroup>
  <ItemGroup>
    <EmbeddedResource Include="payload.zip" />
    <Resource Include="glide.ico" />
  </ItemGroup>
</Project>
"@
    Set-Content -Path (Join-Path $installerStage "GlideInstaller.csproj") -Value $installerCsproj -Encoding UTF8

    $installerProgram = @'
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace GlideInstaller;

public sealed class InstallOptions
{
    public string InstallDirectory { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Programs",
        "Glide");

    public string ReceiveDirectory { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        "Downloads",
        "Glide");

    public int ListenerPort { get; set; } = 42424;
    public bool CreateDesktopShortcut { get; set; } = true;
    public bool CreateStartMenuShortcut { get; set; } = true;
    public bool AddExplorerSendTo { get; set; } = true;
    public bool RegisterUserPath { get; set; } = true;
    public bool LaunchAfterInstall { get; set; } = true;
}

public static class Program
{
    public const string AppVersion = "1.0.0";
    public const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Glide";

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AllocConsole();

    [STAThread]
    public static int Main(string[] args)
    {
        bool silent = false;
        bool uninstall = false;
        bool verifyPayload = false;
        var options = new InstallOptions();

        for (int i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (string.Equals(arg, "--silent", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(arg, "/S", StringComparison.OrdinalIgnoreCase))
            {
                silent = true;
                options.LaunchAfterInstall = false;
            }
            else if (string.Equals(arg, "--uninstall", StringComparison.OrdinalIgnoreCase) ||
                     string.Equals(arg, "/U", StringComparison.OrdinalIgnoreCase))
            {
                uninstall = true;
            }
            else if (string.Equals(arg, "--verify-payload", StringComparison.OrdinalIgnoreCase))
            {
                verifyPayload = true;
            }
            else if (string.Equals(arg, "--dir", StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
            {
                options.InstallDirectory = Path.GetFullPath(args[++i]);
            }
            else if (string.Equals(arg, "--save-dir", StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
            {
                options.ReceiveDirectory = Path.GetFullPath(args[++i]);
            }
        }

        if (verifyPayload)
        {
            AttachConsole(-1);
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("GlideInstaller.payload.zip");
            if (stream == null)
            {
                Console.Error.WriteLine("ERROR: Embedded payload.zip missing.");
                return 2;
            }
            using var archive = new ZipArchive(stream, ZipArchiveMode.Read);
            Console.WriteLine($"Glide Windows Installer v{AppVersion} Verified: {archive.Entries.Count} embedded files ({stream.Length} bytes).");
            return 0;
        }

        if (uninstall)
        {
            if (silent) AttachConsole(-1);
            return ExecuteUninstall(options.InstallDirectory, silent);
        }

        if (silent)
        {
            AttachConsole(-1);
            return ExecuteInstall(options, msg => Console.WriteLine(msg));
        }

        var app = new Application();
        var wizard = new SetupWizardWindow(options);
        app.Run(wizard);
        return wizard.ExitCode;
    }

    public static int ExecuteInstall(InstallOptions options, Action<string> log)
    {
        string installDir = Path.GetFullPath(options.InstallDirectory);
        string receiveDir = Path.GetFullPath(options.ReceiveDirectory);

        log($"Preparing Glide v{AppVersion} installation...");
        log($"Install Location: {installDir}");
        log($"Received Files Folder: {receiveDir}");

        // Terminate any running Glide instances so files can be updated cleanly
        foreach (string procName in new[] { "Glide", "Glide.Windows.UI", "Glide.App" })
        {
            try
            {
                foreach (var proc in Process.GetProcessesByName(procName))
                {
                    try { proc.Kill(); proc.WaitForExit(3000); } catch { }
                }
            }
            catch { }
        }

        Directory.CreateDirectory(installDir);
        Directory.CreateDirectory(receiveDir);

        // Remove all .pdb debug files and legacy duplicate executables from previous installs
        try
        {
            foreach (string pdbFile in Directory.GetFiles(installDir, "*.pdb"))
            {
                try { File.Delete(pdbFile); } catch { }
            }
        }
        catch { }

        foreach (string legacyFile in new[]
        {
            "Glide.Windows.UI.exe",
            "Glide.App.exe",
            "Glide-Uninstall.exe"
        })
        {
            string legacyPath = Path.Combine(installDir, legacyFile);
            if (File.Exists(legacyPath))
            {
                try
                {
                    File.Delete(legacyPath);
                    log($"Removed legacy executable: {legacyFile}");
                }
                catch { }
            }
        }

        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("GlideInstaller.payload.zip");
        if (stream == null)
        {
            throw new InvalidOperationException("Embedded payload.zip resource was not found.");
        }

        using (var archive = new ZipArchive(stream, ZipArchiveMode.Read))
        {
            foreach (var entry in archive.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name))
                    continue;
                string destPath = Path.Combine(installDir, entry.FullName);
                Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
                entry.ExtractToFile(destPath, overwrite: true);
                log($"Installed: {entry.FullName} ({entry.Length / 1024:N0} KB)");
            }
        }

        // Persist user's chosen ReceiveDirectory and ListenerPort into %LOCALAPPDATA%\Glide\desktop-state.json
        try
        {
            string stateDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Glide");
            Directory.CreateDirectory(stateDir);
            string statePath = Path.Combine(stateDir, "desktop-state.json");

            JsonArray existingHistory = new();
            if (File.Exists(statePath))
            {
                try
                {
                    var node = JsonNode.Parse(File.ReadAllText(statePath));
                    if (node?["History"] is JsonArray hist)
                    {
                        existingHistory = JsonNode.Parse(hist.ToJsonString())!.AsArray();
                    }
                }
                catch { }
            }

            var rootObj = new JsonObject
            {
                ["Settings"] = new JsonObject
                {
                    ["ReceiveDirectory"] = receiveDir,
                    ["ListenerPort"] = options.ListenerPort
                },
                ["History"] = existingHistory
            };

            File.WriteAllText(
                statePath,
                rootObj.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
            log($"Configured default receive folder: {receiveDir}");
        }
        catch (Exception ex)
        {
            log($"Note: Could not write initial desktop-state.json ({ex.Message}).");
        }

        string targetExe = Path.Combine(installDir, "Glide.exe");

        try
        {
            using var fwProc = Process.Start(new ProcessStartInfo
            {
                FileName = "netsh",
                Arguments = $"advfirewall firewall add rule name=\"Glide LAN Transfer\" dir=in action=allow program=\"{targetExe}\" enable=yes profile=any",
                CreateNoWindow = true,
                UseShellExecute = false
            });
            fwProc?.WaitForExit(3000);
        }
        catch
        {
            // Non-fatal if user is not elevated
        }

        if (options.RegisterUserPath)
        {
            try
            {
                string userPath = Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.User) ?? string.Empty;
                if (!userPath.Contains(installDir, StringComparison.OrdinalIgnoreCase))
                {
                    string updatedPath = string.IsNullOrEmpty(userPath) ? installDir : $"{userPath};{installDir}";
                    Environment.SetEnvironmentVariable("PATH", updatedPath, EnvironmentVariableTarget.User);
                    log("Registered Glide in User PATH.");
                }
            }
            catch (Exception ex)
            {
                log($"Note: PATH update skipped ({ex.Message}).");
            }
        }

        if (options.CreateStartMenuShortcut)
        {
            try
            {
                string startMenuLnk = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
                    "Programs",
                    "Glide.lnk");
                CreateShortcut(startMenuLnk, targetExe, installDir, "Glide — Local File Sharing");
                log("Created Start Menu shortcut.");
            }
            catch { }
        }

        if (options.CreateDesktopShortcut)
        {
            try
            {
                string desktopLnk = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                    "Glide.lnk");
                CreateShortcut(desktopLnk, targetExe, installDir, "Glide — Local File Sharing");
                log("Created Desktop shortcut.");
            }
            catch { }
        }

        if (options.AddExplorerSendTo)
        {
            try
            {
                string sendToLnk = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.SendTo),
                    "Send with Glide.lnk");
                CreateShortcut(sendToLnk, targetExe, installDir, "Send selected files with Glide");
                log("Added 'Send with Glide' to Windows Explorer SendTo menu.");
            }
            catch { }
        }

        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(UninstallKeyPath);
            if (key != null)
            {
                key.SetValue("DisplayName", "Glide");
                key.SetValue("DisplayVersion", AppVersion);
                key.SetValue("Publisher", "Glide Open Source Project");
                key.SetValue("InstallLocation", installDir);
                key.SetValue("DisplayIcon", targetExe);
                key.SetValue("UninstallString", $"\"{targetExe}\" --uninstall");
                key.SetValue("QuietUninstallString", $"\"{targetExe}\" --uninstall --silent");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                log("Registered Windows Apps & Features uninstaller.");
            }
        }
        catch { }

        log("Installation completed successfully!");
        return 0;
    }

    public static int ExecuteUninstall(string installDir, bool silent)
    {
        string targetExe = Path.Combine(installDir, "Glide.exe");
        if (File.Exists(targetExe))
        {
            var proc = Process.Start(new ProcessStartInfo
            {
                FileName = targetExe,
                Arguments = silent ? "--uninstall --silent" : "--uninstall",
                UseShellExecute = true
            });
            proc?.WaitForExit(5000);
            return 0;
        }

        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(UninstallKeyPath, throwOnMissingSubKey: false);
        }
        catch { }
        return 0;
    }

    private static void CreateShortcut(string shortcutPath, string targetExe, string workingDir, string description)
    {
        string psCommand = $"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{shortcutPath.Replace("'", "''")}');" +
                           $"$s.TargetPath='{targetExe.Replace("'", "''")}';" +
                           $"$s.WorkingDirectory='{workingDir.Replace("'", "''")}';" +
                           $"$s.IconLocation='{targetExe.Replace("'", "''")},0';" +
                           $"$s.Description='{description.Replace("'", "''")}';" +
                           $"$s.Save()";
        using var proc = Process.Start(new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = $"-NoProfile -NonInteractive -Command \"{psCommand}\"",
            CreateNoWindow = true,
            UseShellExecute = false
        });
        proc?.WaitForExit(4000);
    }
}

public sealed class SetupWizardWindow : Window
{
    private readonly InstallOptions _options;
    private int _stepIndex = 0;

    private readonly Grid _step1Panel = new();
    private readonly Grid _step2Panel = new();
    private readonly Grid _step3Panel = new();

    private readonly TextBox _installDirBox = new();
    private readonly TextBox _receiveDirBox = new();
    private readonly TextBox _portBox = new();
    private readonly CheckBox _desktopShortcutCheck = new();
    private readonly CheckBox _startMenuShortcutCheck = new();
    private readonly CheckBox _sendToCheck = new();
    private readonly CheckBox _pathCheck = new();
    private readonly CheckBox _launchNowCheck = new();

    private readonly ProgressBar _progressBar = new();
    private readonly TextBox _logBox = new();
    private readonly TextBlock _step3Title = new();

    private readonly Button _backButton = new();
    private readonly Button _nextButton = new();
    private readonly Button _cancelButton = new();

    public int ExitCode { get; private set; } = 0;

    private static readonly SolidColorBrush BgBrush = new((Color)ColorConverter.ConvertFromString("#0D1121"));
    private static readonly SolidColorBrush SurfaceBrush = new((Color)ColorConverter.ConvertFromString("#111629"));
    private static readonly SolidColorBrush SurfaceHighBrush = new((Color)ColorConverter.ConvertFromString("#1B223D"));
    private static readonly SolidColorBrush PrimaryBrush = new((Color)ColorConverter.ConvertFromString("#375BEE"));
    private static readonly SolidColorBrush AccentBrush = new((Color)ColorConverter.ConvertFromString("#75D7EA"));
    private static readonly SolidColorBrush TextPrimaryBrush = new((Color)ColorConverter.ConvertFromString("#EDF0FF"));
    private static readonly SolidColorBrush TextSecondaryBrush = new((Color)ColorConverter.ConvertFromString("#9EA8C7"));
    private static readonly SolidColorBrush BorderStrokeBrush = new((Color)ColorConverter.ConvertFromString("#253056"));

    public SetupWizardWindow(InstallOptions options)
    {
        _options = options;
        Title = "Glide v1.0.0 Setup Wizard";
        Width = 680;
        Height = 540;
        MinWidth = 640;
        MinHeight = 500;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = BgBrush;
        Foreground = TextPrimaryBrush;

        var root = new Grid { Margin = new Thickness(24) };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        // Header
        var headerBorder = new Border
        {
            Background = SurfaceBrush,
            BorderBrush = BorderStrokeBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(18, 12, 18, 12),
            Margin = new Thickness(0, 0, 0, 16)
        };
        var headerStack = new StackPanel { Orientation = Orientation.Horizontal };
        headerStack.Children.Add(new TextBlock
        {
            Text = "Glide",
            FontSize = 22,
            FontWeight = FontWeights.Bold,
            Foreground = PrimaryBrush,
            VerticalAlignment = VerticalAlignment.Center
        });
        headerStack.Children.Add(new TextBlock
        {
            Text = "  •  Windows Setup Wizard (v1.0.0)",
            FontSize = 14,
            FontWeight = FontWeights.SemiBold,
            Foreground = AccentBrush,
            VerticalAlignment = VerticalAlignment.Center
        });
        headerBorder.Child = headerStack;
        Grid.SetRow(headerBorder, 0);
        root.Children.Add(headerBorder);

        // Content Card
        var contentBorder = new Border
        {
            Background = SurfaceBrush,
            BorderBrush = BorderStrokeBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(24)
        };
        var contentGrid = new Grid();
        BuildStep1();
        BuildStep2();
        BuildStep3();
        contentGrid.Children.Add(_step1Panel);
        contentGrid.Children.Add(_step2Panel);
        contentGrid.Children.Add(_step3Panel);
        contentBorder.Child = contentGrid;
        Grid.SetRow(contentBorder, 1);
        root.Children.Add(contentBorder);

        // Footer Navigation
        var footerGrid = new Grid { Margin = new Thickness(0, 16, 0, 0) };
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        StyleButton(_cancelButton, "Cancel", isPrimary: false);
        _cancelButton.Click += (_, _) => { ExitCode = 1; Close(); };
        Grid.SetColumn(_cancelButton, 0);
        footerGrid.Children.Add(_cancelButton);

        StyleButton(_backButton, "← Back", isPrimary: false);
        _backButton.Margin = new Thickness(0, 0, 10, 0);
        _backButton.Click += (_, _) => ShowStep(_stepIndex - 1);
        Grid.SetColumn(_backButton, 2);
        footerGrid.Children.Add(_backButton);

        StyleButton(_nextButton, "Next →", isPrimary: true);
        _nextButton.Click += async (_, _) => await HandleNextAsync();
        Grid.SetColumn(_nextButton, 3);
        footerGrid.Children.Add(_nextButton);

        Grid.SetRow(footerGrid, 2);
        root.Children.Add(footerGrid);

        Content = root;
        ShowStep(0);
    }

    private void BuildStep1()
    {
        var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        stack.Children.Add(new TextBlock
        {
            Text = "Welcome to Glide for Windows",
            FontSize = 22,
            FontWeight = FontWeights.Bold,
            Foreground = TextPrimaryBrush,
            Margin = new Thickness(0, 0, 0, 12)
        });
        stack.Children.Add(new TextBlock
        {
            Text = "This wizard will install Glide (unified Windows Desktop & CLI application) onto your computer and configure your default folders.",
            FontSize = 14,
            Foreground = TextSecondaryBrush,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 0, 0, 20)
        });

        string[] highlights =
        {
            "✓  Single unified executable (Glide.exe) for both Windows GUI and CLI commands",
            "✓  Custom installation folder and custom received files save directory",
            "✓  Windows Explorer 'Send with Glide' context integration & shortcuts",
            "✓  Lightweight uninstaller in Windows Apps & Features"
        };
        foreach (string line in highlights)
        {
            stack.Children.Add(new TextBlock
            {
                Text = line,
                FontSize = 13,
                Foreground = TextPrimaryBrush,
                Margin = new Thickness(0, 6, 0, 6)
            });
        }

        _step1Panel.Children.Add(stack);
    }

    private void BuildStep2()
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        var stack = new StackPanel();

        stack.Children.Add(new TextBlock
        {
            Text = "Choose Installation & File Save Locations",
            FontSize = 17,
            FontWeight = FontWeights.Bold,
            Foreground = TextPrimaryBrush,
            Margin = new Thickness(0, 0, 0, 14)
        });

        // 1. Where to install Glide
        stack.Children.Add(new TextBlock
        {
            Text = "1. Application Install Folder (Where to install Glide.exe):",
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = AccentBrush,
            Margin = new Thickness(0, 0, 0, 6)
        });

        var installRow = new Grid { Margin = new Thickness(0, 0, 0, 14) };
        installRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        installRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        StyleTextBox(_installDirBox, _options.InstallDirectory);
        Grid.SetColumn(_installDirBox, 0);
        installRow.Children.Add(_installDirBox);

        var browseInstallBtn = new Button { Margin = new Thickness(8, 0, 0, 0) };
        StyleButton(browseInstallBtn, "Browse...", isPrimary: false);
        browseInstallBtn.Click += (_, _) =>
        {
            var dlg = new OpenFolderDialog
            {
                Title = "Select folder where Glide should be installed",
                InitialDirectory = Directory.Exists(_installDirBox.Text)
                    ? _installDirBox.Text
                    : Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)
            };
            if (dlg.ShowDialog() == true)
            {
                _installDirBox.Text = dlg.FolderName.EndsWith("Glide", StringComparison.OrdinalIgnoreCase)
                    ? dlg.FolderName
                    : Path.Combine(dlg.FolderName, "Glide");
            }
        };
        Grid.SetColumn(browseInstallBtn, 1);
        installRow.Children.Add(browseInstallBtn);
        stack.Children.Add(installRow);

        // 2. Where to save received files
        stack.Children.Add(new TextBlock
        {
            Text = "2. Default Received Files Folder (Where incoming files are saved):",
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = AccentBrush,
            Margin = new Thickness(0, 0, 0, 6)
        });

        var receiveRow = new Grid { Margin = new Thickness(0, 0, 0, 16) };
        receiveRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        receiveRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        StyleTextBox(_receiveDirBox, _options.ReceiveDirectory);
        Grid.SetColumn(_receiveDirBox, 0);
        receiveRow.Children.Add(_receiveDirBox);

        var browseReceiveBtn = new Button { Margin = new Thickness(8, 0, 0, 0) };
        StyleButton(browseReceiveBtn, "Browse...", isPrimary: false);
        browseReceiveBtn.Click += (_, _) =>
        {
            var dlg = new OpenFolderDialog
            {
                Title = "Select folder where received files should be saved",
                InitialDirectory = Directory.Exists(_receiveDirBox.Text)
                    ? _receiveDirBox.Text
                    : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
            };
            if (dlg.ShowDialog() == true)
            {
                _receiveDirBox.Text = dlg.FolderName;
            }
        };
        Grid.SetColumn(browseReceiveBtn, 1);
        receiveRow.Children.Add(browseReceiveBtn);
        stack.Children.Add(receiveRow);

        // 3. Default Listener Port + Checkboxes
        var portRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 12) };
        portRow.Children.Add(new TextBlock
        {
            Text = "Default LAN Listener Port: ",
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = TextSecondaryBrush,
            Margin = new Thickness(0, 0, 8, 0)
        });
        StyleTextBox(_portBox, _options.ListenerPort.ToString());
        _portBox.Width = 90;
        portRow.Children.Add(_portBox);
        stack.Children.Add(portRow);

        StyleCheckBox(_desktopShortcutCheck, "Create Desktop shortcut", _options.CreateDesktopShortcut);
        StyleCheckBox(_startMenuShortcutCheck, "Create Start Menu shortcut", _options.CreateStartMenuShortcut);
        StyleCheckBox(_sendToCheck, "Add 'Send with Glide' to Windows Explorer SendTo menu", _options.AddExplorerSendTo);
        StyleCheckBox(_pathCheck, "Add Glide CLI to User PATH", _options.RegisterUserPath);

        stack.Children.Add(_desktopShortcutCheck);
        stack.Children.Add(_startMenuShortcutCheck);
        stack.Children.Add(_sendToCheck);
        stack.Children.Add(_pathCheck);

        scroll.Content = stack;
        _step2Panel.Children.Add(scroll);
    }

    private void BuildStep3()
    {
        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        _step3Title.Text = "Installing Glide...";
        _step3Title.FontSize = 18;
        _step3Title.FontWeight = FontWeights.Bold;
        _step3Title.Margin = new Thickness(0, 0, 0, 12);
        Grid.SetRow(_step3Title, 0);
        grid.Children.Add(_step3Title);

        _progressBar.Height = 10;
        _progressBar.Minimum = 0;
        _progressBar.Maximum = 100;
        _progressBar.Value = 20;
        _progressBar.Foreground = PrimaryBrush;
        _progressBar.Background = SurfaceHighBrush;
        _progressBar.Margin = new Thickness(0, 0, 0, 14);
        Grid.SetRow(_progressBar, 1);
        grid.Children.Add(_progressBar);

        StyleTextBox(_logBox, "");
        _logBox.IsReadOnly = true;
        _logBox.TextWrapping = TextWrapping.Wrap;
        _logBox.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        _logBox.FontFamily = new FontFamily("Consolas");
        _logBox.FontSize = 11.5;
        Grid.SetRow(_logBox, 2);
        grid.Children.Add(_logBox);

        StyleCheckBox(_launchNowCheck, "Launch Glide now", true);
        _launchNowCheck.Margin = new Thickness(0, 12, 0, 0);
        _launchNowCheck.Visibility = Visibility.Collapsed;
        Grid.SetRow(_launchNowCheck, 3);
        grid.Children.Add(_launchNowCheck);

        _step3Panel.Children.Add(grid);
    }

    private void ShowStep(int index)
    {
        _stepIndex = index;
        _step1Panel.Visibility = index == 0 ? Visibility.Visible : Visibility.Collapsed;
        _step2Panel.Visibility = index == 1 ? Visibility.Visible : Visibility.Collapsed;
        _step3Panel.Visibility = index == 2 ? Visibility.Visible : Visibility.Collapsed;

        _backButton.Visibility = index == 1 ? Visibility.Visible : Visibility.Collapsed;
        if (index == 0)
        {
            _nextButton.Content = "Next →";
        }
        else if (index == 1)
        {
            _nextButton.Content = "Install Now";
        }
    }

    private async Task HandleNextAsync()
    {
        if (_stepIndex == 0)
        {
            ShowStep(1);
            return;
        }

        if (_stepIndex == 1)
        {
            _options.InstallDirectory = _installDirBox.Text.Trim();
            _options.ReceiveDirectory = _receiveDirBox.Text.Trim();
            if (int.TryParse(_portBox.Text.Trim(), out int parsedPort) && parsedPort is >= 1 and <= 65535)
            {
                _options.ListenerPort = parsedPort;
            }
            _options.CreateDesktopShortcut = _desktopShortcutCheck.IsChecked == true;
            _options.CreateStartMenuShortcut = _startMenuShortcutCheck.IsChecked == true;
            _options.AddExplorerSendTo = _sendToCheck.IsChecked == true;
            _options.RegisterUserPath = _pathCheck.IsChecked == true;

            ShowStep(2);
            _backButton.IsEnabled = false;
            _nextButton.IsEnabled = false;
            _cancelButton.IsEnabled = false;
            _progressBar.IsIndeterminate = true;

            try
            {
                await Task.Run(() =>
                {
                    Program.ExecuteInstall(_options, msg =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            _logBox.AppendText(msg + Environment.NewLine);
                            _logBox.ScrollToEnd();
                        });
                    });
                });

                _progressBar.IsIndeterminate = false;
                _progressBar.Value = 100;
                _step3Title.Text = "Glide Installation Complete!";
                _launchNowCheck.Visibility = Visibility.Visible;
                _nextButton.Content = "Finish";
                _nextButton.IsEnabled = true;
                _stepIndex = 3;
            }
            catch (Exception ex)
            {
                _progressBar.IsIndeterminate = false;
                _step3Title.Text = "Installation Failed";
                _logBox.AppendText($"ERROR: {ex.Message}{Environment.NewLine}");
                _cancelButton.Content = "Close";
                _cancelButton.IsEnabled = true;
                ExitCode = 1;
            }
            return;
        }

        if (_stepIndex == 3)
        {
            if (_launchNowCheck.IsChecked == true)
            {
                string glideExe = Path.Combine(_options.InstallDirectory, "Glide.exe");
                if (File.Exists(glideExe))
                {
                    try
                    {
                        Process.Start(new ProcessStartInfo
                        {
                            FileName = glideExe,
                            WorkingDirectory = _options.InstallDirectory,
                            UseShellExecute = true
                        });
                    }
                    catch { }
                }
            }
            Close();
        }
    }

    private static void StyleButton(Button btn, string text, bool isPrimary)
    {
        btn.Content = text;
        btn.Padding = new Thickness(18, 8, 18, 8);
        btn.FontSize = 13;
        btn.FontWeight = FontWeights.SemiBold;
        btn.Background = isPrimary ? PrimaryBrush : SurfaceHighBrush;
        btn.Foreground = TextPrimaryBrush;
        btn.BorderBrush = BorderStrokeBrush;
        btn.BorderThickness = new Thickness(isPrimary ? 0 : 1);
    }

    private static void StyleTextBox(TextBox box, string text)
    {
        box.Text = text;
        box.Background = SurfaceHighBrush;
        box.Foreground = TextPrimaryBrush;
        box.BorderBrush = BorderStrokeBrush;
        box.BorderThickness = new Thickness(1);
        box.Padding = new Thickness(8, 6, 8, 6);
        box.FontSize = 12.5;
    }

    private static void StyleCheckBox(CheckBox cb, string text, bool isChecked)
    {
        cb.Content = text;
        cb.IsChecked = isChecked;
        cb.Foreground = TextPrimaryBrush;
        cb.FontSize = 12.5;
        cb.Margin = new Thickness(0, 4, 0, 4);
    }
}
'@
    Set-Content -Path (Join-Path $installerStage "Program.cs") -Value $installerProgram -Encoding UTF8

    $installerOutDir = Join-Path $installerStage "out"
    dotnet publish (Join-Path $installerStage "GlideInstaller.csproj") `
        -c Release `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        /p:DebugType=none `
        /p:DebugSymbols=false `
        -o $installerOutDir
    if ($LASTEXITCODE -ne 0) { throw "Failed to compile Glide-Setup-v1.0.0-$rid.exe" }

    $setupExeDest = Join-Path $OutputDir "Glide-Setup-v1.0.0-$rid.exe"
    Copy-Item (Join-Path $installerOutDir "Glide-Setup-v1.0.0-$rid.exe") $setupExeDest -Force

    # Clean up temporary build staging folders so OutputDir contains only the final distribution artifacts
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir -ErrorAction SilentlyContinue }
    if (Test-Path $installerStage) { Remove-Item -Recurse -Force $installerStage -ErrorAction SilentlyContinue }
    $legacyUninstallStage = Join-Path $OutputDir "uninstaller-src-$rid"
    if (Test-Path $legacyUninstallStage) { Remove-Item -Recurse -Force $legacyUninstallStage -ErrorAction SilentlyContinue }

    # Verify the installer binary right away
    $verifyProc = Start-Process -FilePath $setupExeDest -ArgumentList "--verify-payload" -Wait -PassThru -NoNewWindow
    if ($verifyProc.ExitCode -ne 0) { throw "Installer payload verification failed!" }

    Write-Host "`n=================================================================" -ForegroundColor Cyan
    Write-Host "  Windows Distribution & Setup Wizard Ready!                     " -ForegroundColor Cyan
    Write-Host "  - Portable Archive:   $zipPath                                 " -ForegroundColor Green
    Write-Host "  - Setup Wizard:       $setupExeDest                            " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
}
