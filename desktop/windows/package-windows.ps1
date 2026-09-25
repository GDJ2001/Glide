<#
.SYNOPSIS
    Builds full self-contained release distribution and native setup installer for Glide on Windows.
.DESCRIPTION
    1. Compiles Glide.Windows.UI (WPF Desktop Companion) and Glide.App (CLI/Daemon) as self-contained
       single-file executables targeting win-x64.
    2. Packages glide-windows-win-x64.zip.
    3. Compiles Glide-Setup-v1.0.0-win-x64.exe — a standalone self-extracting Windows installer
       that embeds the payload archive, installs into %LOCALAPPDATA%\Programs\Glide, creates Start Menu
       and Desktop shortcuts, registers the CLI in PATH, and adds an Add/Remove Programs uninstaller entry.
#>

param(
    [string]$Configuration = "Release",
    [string[]]$Runtimes = @("win-x64")
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."
$WpfProject = Join-Path $RepoRoot "desktop\windows\Glide.Windows.UI\Glide.Windows.UI.csproj"
$CliProject = Join-Path $RepoRoot "desktop\src\Glide.App\Glide.App.csproj"
$OutputDir = Join-Path $RepoRoot "build\distributions\windows"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Building Glide Windows Self-Contained Distribution        " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

foreach ($rid in $Runtimes) {
    $publishDir = Join-Path $OutputDir "stage-$rid"
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
    New-Item -ItemType Directory -Path $publishDir -Force | Out-Null

    Write-Host "`n[1/4] Publishing Glide.Windows.UI (WPF) for $rid ($Configuration)..." -ForegroundColor Yellow
    dotnet publish $WpfProject `
        -c $Configuration `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        /p:IncludeNativeLibrariesForSelfExtract=true `
        -o $publishDir
    if ($LASTEXITCODE -ne 0) { throw "Failed to publish Glide.Windows.UI for $rid" }

    Write-Host "[2/4] Publishing Glide.App (CLI/Daemon) for $rid ($Configuration)..." -ForegroundColor Yellow
    dotnet publish $CliProject `
        -c $Configuration `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        /p:IncludeNativeLibrariesForSelfExtract=true `
        -o $publishDir
    if ($LASTEXITCODE -ne 0) { throw "Failed to publish Glide.App for $rid" }

    # Create convenience glide.cmd wrapper
    Set-Content -Path (Join-Path $publishDir "glide.cmd") -Value "@echo off`r`n`"%~dp0Glide.App.exe`" %*" -Encoding ASCII

    # Create zip archive
    $zipPath = Join-Path $OutputDir "glide-windows-$rid.zip"
    Write-Host "[3/4] Packaging portable archive $zipPath..." -ForegroundColor Green
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path "$publishDir\*" -DestinationPath $zipPath -CompressionLevel Optimal

    # Build Full Windows Setup Installer (Glide-Setup-v1.0.0-win-x64.exe)
    Write-Host "[4/4] Building Full Windows Setup Installer (Glide-Setup-v1.0.0-$rid.exe)..." -ForegroundColor Yellow
    $installerStage = Join-Path $OutputDir "installer-src-$rid"
    if (Test-Path $installerStage) { Remove-Item -Recurse -Force $installerStage }
    New-Item -ItemType Directory -Path $installerStage -Force | Out-Null

    Copy-Item $zipPath (Join-Path $installerStage "payload.zip") -Force

    $installerCsproj = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework>
    <AssemblyName>Glide-Setup-v1.0.0-$rid</AssemblyName>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <PublishSingleFile>true</PublishSingleFile>
    <SelfContained>true</SelfContained>
    <IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
    <Version>1.0.0</Version>
    <Product>Glide Authenticated File Transfer Setup</Product>
    <Company>Glide Open Source Project</Company>
  </PropertyGroup>
  <ItemGroup>
    <EmbeddedResource Include="payload.zip" />
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
using Microsoft.Win32;

namespace GlideInstaller;

public static class Program
{
    private const string AppVersion = "1.0.0";
    private const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Glide";

    public static int Main(string[] args)
    {
        bool silent = false;
        bool uninstall = false;
        bool verifyPayload = false;
        string installDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            "Glide");

        for (int i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (string.Equals(arg, "--silent", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(arg, "/S", StringComparison.OrdinalIgnoreCase))
            {
                silent = true;
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
                installDir = Path.GetFullPath(args[++i]);
            }
        }

        if (verifyPayload)
        {
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
            return RunUninstall(installDir, silent);
        }

        return RunInstall(installDir, silent);
    }

    private static int RunInstall(string installDir, bool silent)
    {
        Console.WriteLine($"==============================================================");
        Console.WriteLine($"  Glide v{AppVersion} — Windows x64 Setup Installer");
        Console.WriteLine($"==============================================================");
        Console.WriteLine($"Target Directory: {installDir}");

        Directory.CreateDirectory(installDir);

        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("GlideInstaller.payload.zip");
        if (stream == null)
        {
            Console.Error.WriteLine("Fatal: Embedded payload.zip resource was not found.");
            return 1;
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
                Console.WriteLine($"  Installed: {entry.FullName} ({entry.Length:N0} bytes)");
            }
        }

        // Copy installer itself as Uninstall.exe
        string currentExe = Environment.ProcessPath ?? string.Empty;
        string uninstallerExe = Path.Combine(installDir, "Glide-Uninstall.exe");
        if (!string.IsNullOrEmpty(currentExe) && File.Exists(currentExe))
        {
            File.Copy(currentExe, uninstallerExe, overwrite: true);
        }

        // Add to User PATH
        try
        {
            string userPath = Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.User) ?? string.Empty;
            if (!userPath.Contains(installDir, StringComparison.OrdinalIgnoreCase))
            {
                string updatedPath = string.IsNullOrEmpty(userPath) ? installDir : $"{userPath};{installDir}";
                Environment.SetEnvironmentVariable("PATH", updatedPath, EnvironmentVariableTarget.User);
                Console.WriteLine("  Registered Glide CLI in User PATH.");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"  Note: Could not update PATH automatically ({ex.Message}).");
        }

        // Create Start Menu & Desktop Shortcuts via PowerShell WScript.Shell
        try
        {
            string startMenuLnk = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
                "Programs",
                "Glide.lnk");
            string desktopLnk = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                "Glide.lnk");
            string targetExe = Path.Combine(installDir, "Glide.Windows.UI.exe");
            CreateShortcut(startMenuLnk, targetExe, installDir);
            CreateShortcut(desktopLnk, targetExe, installDir);
            Console.WriteLine("  Created Desktop and Start Menu shortcuts.");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"  Note: Shortcut creation skipped ({ex.Message}).");
        }

        // Register in Windows Add/Remove Programs (HKCU Uninstall)
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(UninstallKeyPath);
            if (key != null)
            {
                key.SetValue("DisplayName", "Glide Authenticated File Sharing");
                key.SetValue("DisplayVersion", AppVersion);
                key.SetValue("Publisher", "Glide Open Source Project");
                key.SetValue("InstallLocation", installDir);
                key.SetValue("DisplayIcon", Path.Combine(installDir, "Glide.Windows.UI.exe"));
                key.SetValue("UninstallString", $"\"{uninstallerExe}\" --uninstall");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                Console.WriteLine("  Registered Windows Uninstall entry.");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"  Note: Registry uninstall registration skipped ({ex.Message}).");
        }

        Console.WriteLine($"==============================================================");
        Console.WriteLine($"  Glide v{AppVersion} installation completed successfully!");
        Console.WriteLine($"  - Desktop UI: {Path.Combine(installDir, "Glide.Windows.UI.exe")}");
        Console.WriteLine($"  - CLI / Daemon: {Path.Combine(installDir, "Glide.App.exe")}");
        Console.WriteLine($"==============================================================");
        return 0;
    }

    private static int RunUninstall(string installDir, bool silent)
    {
        Console.WriteLine("Uninstalling Glide...");
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(UninstallKeyPath, throwOnMissingSubKey: false);
        }
        catch { }

        try
        {
            string startMenuLnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs", "Glide.lnk");
            string desktopLnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Glide.lnk");
            if (File.Exists(startMenuLnk)) File.Delete(startMenuLnk);
            if (File.Exists(desktopLnk)) File.Delete(desktopLnk);
        }
        catch { }

        Console.WriteLine($"Removed Glide shortcuts and registry keys from {installDir}.");
        return 0;
    }

    private static void CreateShortcut(string shortcutPath, string targetExe, string workingDir)
    {
        string psCommand = $"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{shortcutPath.Replace("'", "''")}');" +
                           $"$s.TargetPath='{targetExe.Replace("'", "''")}';" +
                           $"$s.WorkingDirectory='{workingDir.Replace("'", "''")}';" +
                           $"$s.Description='Glide Authenticated File Transfer';" +
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
'@
    Set-Content -Path (Join-Path $installerStage "Program.cs") -Value $installerProgram -Encoding UTF8

    $installerOutDir = Join-Path $installerStage "out"
    dotnet publish (Join-Path $installerStage "GlideInstaller.csproj") `
        -c Release `
        -r $rid `
        --self-contained true `
        /p:PublishSingleFile=true `
        -o $installerOutDir
    if ($LASTEXITCODE -ne 0) { throw "Failed to compile Glide-Setup-v1.0.0-$rid.exe" }

    $setupExeDest = Join-Path $OutputDir "Glide-Setup-v1.0.0-$rid.exe"
    Copy-Item (Join-Path $installerOutDir "Glide-Setup-v1.0.0-$rid.exe") $setupExeDest -Force

    # Verify the installer binary right away
    & $setupExeDest --verify-payload
    if ($LASTEXITCODE -ne 0) { throw "Installer payload verification failed!" }

    # Clean temporary stage directories
    Remove-Item -Recurse -Force $publishDir -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $installerStage -ErrorAction SilentlyContinue

    $setupItem = Get-Item $setupExeDest
    $zipItem = Get-Item $zipPath
    Write-Host "[SUCCESS] Full Windows Installer: $setupExeDest ($($setupItem.Length) bytes)" -ForegroundColor Green
    Write-Host "[SUCCESS] Portable Windows Zip:   $zipPath ($($zipItem.Length) bytes)" -ForegroundColor Green
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "        All Windows Installers Built Successfully!               " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
