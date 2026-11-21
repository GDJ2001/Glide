using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace Glide.E2E.Tests.Infrastructure;

public sealed class TargetUnavailableException : Exception
{
    public string TargetName { get; }

    public TargetUnavailableException(string targetName, string reason)
        : base($"Target '{targetName}' is unavailable: {reason}")
    {
        TargetName = targetName;
    }
}

public sealed class TargetEnvironment
{
    public string RepoRoot { get; }
    public string Configuration { get; }
    public string Mode { get; }
    public string RunId { get; }
    public string EvidenceDir { get; }
    public bool KeepArtifacts { get; }
    public string VmHost { get; }
    public string VmUser { get; }
    public string AndroidSerial { get; }
    public bool IsVmReady { get; }
    public bool IsAndroidReady { get; }
    public bool IsWindowsUiReady { get; }

    private TargetEnvironment()
    {
        RepoRoot = ResolveRepoRoot();
        Configuration = Environment.GetEnvironmentVariable("GLIDE_E2E_CONFIGURATION") ?? "Debug";
        Mode = Environment.GetEnvironmentVariable("GLIDE_E2E_MODE") ?? "LocalLoopback";
        RunId = Environment.GetEnvironmentVariable("GLIDE_E2E_RUN_ID")
            ?? DateTimeOffset.UtcNow.ToString("yyyyMMddTHHmmssZ");
        EvidenceDir = Environment.GetEnvironmentVariable("GLIDE_E2E_EVIDENCE_DIR")
            ?? Path.Combine(RepoRoot, "build", "test-evidence", "e2e", RunId);
        KeepArtifacts = Environment.GetEnvironmentVariable("GLIDE_E2E_KEEP_ARTIFACTS") == "1";
        VmHost = Environment.GetEnvironmentVariable("GLIDE_E2E_VM_HOST") ?? "192.168.0.245";
        VmUser = Environment.GetEnvironmentVariable("GLIDE_E2E_VM_USER") ?? "Ubuntu 26";
        AndroidSerial = Environment.GetEnvironmentVariable("GLIDE_E2E_ANDROID_SERIAL") ?? "emulator-5554";
        IsVmReady = Environment.GetEnvironmentVariable("GLIDE_E2E_VM_READY") == "1";
        IsAndroidReady = Environment.GetEnvironmentVariable("GLIDE_E2E_ANDROID_READY") == "1";
        IsWindowsUiReady = Environment.GetEnvironmentVariable("GLIDE_E2E_WINDOWS_UI_READY") == "1";
    }

    public static TargetEnvironment Current { get; } = new();

    public string ResolveBackendTestDriverDll() =>
        ResolveBinary("Glide.BackendTestDriver",
            Path.Combine(RepoRoot, "desktop", "tests", "Glide.BackendTestDriver", "bin"));

    public string ResolveGlideAppDll() =>
        ResolveBinary("Glide.App",
            Path.Combine(RepoRoot, "desktop", "src", "Glide.App", "bin"));

    public string ResolveWindowsUiExe()
    {
        string? overridePath = Environment.GetEnvironmentVariable("GLIDE_E2E_WINDOWS_UI_EXE");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            string full = Path.GetFullPath(overridePath);
            if (File.Exists(full)) return full;
            throw new FileNotFoundException($"GLIDE_E2E_WINDOWS_UI_EXE points to missing file: {full}");
        }

        string[] candidates =
        [
            Path.Combine(RepoRoot, "desktop", "windows", "Glide.Windows.UI", "bin", Configuration, "net10.0-windows", "Glide.exe"),
            Path.Combine(RepoRoot, "desktop", "windows", "Glide.Windows.UI", "bin", "Release", "net10.0-windows", "Glide.exe"),
            Path.Combine(RepoRoot, "desktop", "windows", "Glide.Windows.UI", "bin", "Debug", "net10.0-windows", "Glide.exe")
        ];
        foreach (string path in candidates)
        {
            if (File.Exists(path)) return path;
        }
        throw new FileNotFoundException("Glide.exe not found. Build desktop/windows/Glide.Windows.UI first.");
    }

    public string ResolveWindowsPackagesDir()
    {
        string? overrideDir = Environment.GetEnvironmentVariable("GLIDE_E2E_WINDOWS_PACKAGES_DIR");
        if (!string.IsNullOrWhiteSpace(overrideDir))
        {
            return Path.GetFullPath(overrideDir);
        }
        return Path.Combine(RepoRoot, "build", "distributions", "windows");
    }

    public string ResolveWindowsNsisInstaller() =>
        ResolvePackageFile(ResolveWindowsPackagesDir(), "Glide-Setup-*.exe",
            "Windows NSIS installer (.exe) not found. Build with desktop/windows/package-windows.ps1.");

    public string ResolveWindowsMsiInstaller() =>
        ResolvePackageFile(ResolveWindowsPackagesDir(), "Glide-Setup-*.msi",
            "Windows MSI installer (.msi) not found. Build with desktop/windows/package-windows-msi.ps1.");

    public string ResolveWindowsPortableZip() =>
        ResolvePackageFile(ResolveWindowsPackagesDir(), "glide-windows-*.zip",
            "Windows portable archive (.zip) not found. Build with desktop/windows/package-windows.ps1.");

    public string ResolveLinuxRunInstaller()
    {
        string? overridePath = Environment.GetEnvironmentVariable("GLIDE_E2E_LINUX_RUN_PATH");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            string full = Path.GetFullPath(overridePath);
            if (File.Exists(full)) return full;
            throw new FileNotFoundException($"GLIDE_E2E_LINUX_RUN_PATH points to missing file: {full}");
        }

        return ResolvePackageFileAcrossDirs(
            [
                Path.Combine(RepoRoot, "build", "distributions", "linux"),
                Path.Combine(RepoRoot, "build", "distributions")
            ],
            "*.run",
            "Linux .run installer not found under build/distributions. Package Linux artifacts or set GLIDE_E2E_LINUX_RUN_PATH.");
    }

    public string ResolveLinuxDebPackage()
    {
        string? overridePath = Environment.GetEnvironmentVariable("GLIDE_E2E_LINUX_DEB_PATH");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            string full = Path.GetFullPath(overridePath);
            if (File.Exists(full)) return full;
            throw new FileNotFoundException($"GLIDE_E2E_LINUX_DEB_PATH points to missing file: {full}");
        }

        return ResolvePackageFileAcrossDirs(
            [
                Path.Combine(RepoRoot, "build", "distributions", "linux"),
                Path.Combine(RepoRoot, "build", "distributions")
            ],
            "*.deb",
            "Linux .deb package not found under build/distributions. Package Linux artifacts or set GLIDE_E2E_LINUX_DEB_PATH.");
    }

    public string ResolveAndroidDebugApk()
    {
        string? overridePath = Environment.GetEnvironmentVariable("GLIDE_E2E_ANDROID_APK");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            string full = Path.GetFullPath(overridePath);
            if (File.Exists(full)) return full;
            throw new FileNotFoundException($"GLIDE_E2E_ANDROID_APK points to missing file: {full}");
        }

        string[] candidates =
        [
            Path.Combine(RepoRoot, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
            Path.Combine(RepoRoot, "build", "distributions", "android", "app-debug.apk")
        ];
        foreach (string c in candidates)
        {
            if (File.Exists(c)) return c;
        }
        throw new FileNotFoundException("Android debug APK not found at android/app/build/outputs/apk/debug/app-debug.apk or GLIDE_E2E_ANDROID_APK.");
    }

    public string ResolveGlideSpake2NativePath()
    {
        string fileName = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? "glide_spake2.dll"
            : "libglide_spake2.so";
        string rid = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? "win-x64"
            : (RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "linux-arm64" : "linux-x64");

        string[] candidates =
        [
            Path.Combine(RepoRoot, "desktop", "src", "Glide.App", "bin", Configuration, "net10.0", fileName),
            Path.Combine(RepoRoot, "desktop", "src", "Glide.App", "bin", Configuration, "net10.0", "runtimes", rid, "native", fileName),
            Path.Combine(RepoRoot, "desktop", "src", "Glide.App", "bin", "Release", "net10.0", fileName),
            Path.Combine(RepoRoot, "desktop", "src", "Glide.App", "bin", "Debug", "net10.0", fileName),
            Path.Combine(RepoRoot, "desktop", "src", "Glide.Core", "bin", Configuration, "net10.0", fileName),
            Path.Combine(RepoRoot, "desktop", "src", "Glide.Core", "bin", "Release", "net10.0", fileName),
            Path.Combine(RepoRoot, "desktop", "src", "Glide.Core", "bin", "Debug", "net10.0", fileName),
            Path.Combine(RepoRoot, "desktop", "windows", "Glide.Windows.UI", "bin", "Release", "net10.0-windows", fileName),
            Path.Combine(RepoRoot, "desktop", "windows", "Glide.Windows.UI", "bin", "Debug", "net10.0-windows", fileName)
        ];

        foreach (string c in candidates)
        {
            if (File.Exists(c)) return c;
        }
        throw new FileNotFoundException($"Native SPAKE2 library '{fileName}' not found under desktop/ bin directories.");
    }

    public string ResolveWindowsLocalIpForVm()
    {
        string? explicitIp = Environment.GetEnvironmentVariable("GLIDE_E2E_WINDOWS_ADVERTISED_IP");
        if (!string.IsNullOrWhiteSpace(explicitIp))
        {
            return explicitIp.Trim();
        }

        try
        {
            using var udp = new UdpClient();
            udp.Connect(VmHost, 22);
            if (udp.Client.LocalEndPoint is IPEndPoint ep && !IPAddress.IsLoopback(ep.Address))
            {
                return ep.Address.ToString();
            }
        }
        catch
        {
            // Fall through to host address inspection
        }

        foreach (IPAddress ip in Dns.GetHostAddresses(Dns.GetHostName()))
        {
            if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
            {
                return ip.ToString();
            }
        }

        return "127.0.0.1";
    }

    public string ResolveDriverScript(string fileName)
    {
        string path = Path.Combine(RepoRoot, "tests", "e2e", "drivers", fileName);
        if (!File.Exists(path))
        {
            throw new FileNotFoundException($"E2E driver script not found: {path}");
        }
        return path;
    }

    public string ResolveGoldenFixturesDir() =>
        Path.Combine(RepoRoot, "android", "core", "src", "test", "resources", "fixtures");

    public string ResolveProfile2FixtureFile() =>
        Path.Combine(RepoRoot, "doc", "protocol", "fixtures", "profile2-fixtures.json");

    public void RequireVmReady()
    {
        if (!IsVmReady)
        {
            throw new TargetUnavailableException("LinuxVm",
                $"Ubuntu VM ({VmUser}@{VmHost}) is not marked reachable (set GLIDE_E2E_VM_READY=1 via run-e2e.ps1 -Mode LiveVm|All).");
        }
    }

    public void RequireAndroidReady()
    {
        if (!IsAndroidReady)
        {
            throw new TargetUnavailableException("AndroidEmulator",
                $"Android target ({AndroidSerial}) is not marked reachable (set GLIDE_E2E_ANDROID_READY=1 via run-e2e.ps1 -Mode LiveAndroid|All).");
        }
    }

    public void RequireWindowsUiReady()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows) || !IsWindowsUiReady)
        {
            throw new TargetUnavailableException("WindowsUi",
                "Live Windows WPF UI automation requires Windows host + GLIDE_E2E_WINDOWS_UI_READY=1.");
        }
    }

    public void RequireCrossPlatformVmReady() => RequireVmReady();

    public void RequireCrossPlatformAndroidReady() => RequireAndroidReady();

    public void RequireCrossPlatformAllReady()
    {
        RequireVmReady();
        RequireAndroidReady();
    }

    private static string ResolvePackageFile(string dir, string pattern, string errorMessage)
    {
        if (!Directory.Exists(dir))
        {
            throw new FileNotFoundException(errorMessage);
        }
        string? found = Directory.GetFiles(dir, pattern, SearchOption.TopDirectoryOnly)
            .OrderByDescending(File.GetLastWriteTimeUtc)
            .FirstOrDefault();
        return found ?? throw new FileNotFoundException(errorMessage);
    }

    private static string ResolvePackageFileAcrossDirs(string[] dirs, string pattern, string errorMessage)
    {
        foreach (string dir in dirs)
        {
            if (!Directory.Exists(dir)) continue;
            string? found = Directory.GetFiles(dir, pattern, SearchOption.AllDirectories)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (!string.IsNullOrEmpty(found)) return found;
        }
        throw new FileNotFoundException(errorMessage);
    }

    private string ResolveBinary(string assemblyName, string binRoot)
    {
        string[] configs = Configuration.Equals("Release", StringComparison.OrdinalIgnoreCase)
            ? ["Release", "Debug"]
            : ["Debug", "Release"];

        foreach (string cfg in configs)
        {
            string dll = Path.Combine(binRoot, cfg, "net10.0", $"{assemblyName}.dll");
            if (File.Exists(dll))
            {
                return dll;
            }
        }

        throw new FileNotFoundException(
            $"Built {assemblyName}.dll not found under {binRoot} (checked {string.Join(", ", configs)}). Run dotnet build first.");
    }

    private static string ResolveRepoRoot()
    {
        string? envRoot = Environment.GetEnvironmentVariable("GLIDE_E2E_REPO_ROOT");
        if (!string.IsNullOrWhiteSpace(envRoot) && Directory.Exists(envRoot))
        {
            return Path.GetFullPath(envRoot);
        }

        string? dir = AppContext.BaseDirectory;
        while (!string.IsNullOrEmpty(dir))
        {
            if (File.Exists(Path.Combine(dir, "desktop", "Glide.slnx")))
            {
                return dir;
            }
            dir = Path.GetDirectoryName(dir);
        }

        throw new DirectoryNotFoundException("Could not locate Glide repository root (desktop/Glide.slnx).");
    }
}
