using System.IO;

namespace Glide.Linux.UI.Services;

/// <summary>
/// Implements XDG Base Directory specification compliance and standard Linux environment paths.
/// </summary>
public static class LinuxEnvironment
{
    private static readonly string HomeDir = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

    /// <summary>
    /// Path to user data directory (~/.local/share/glide).
    /// </summary>
    public static string DataHome
    {
        get
        {
            var env = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            var basePath = string.IsNullOrWhiteSpace(env) ? Path.Combine(HomeDir, ".local", "share") : env;
            var glidePath = Path.Combine(basePath, "glide");
            Directory.CreateDirectory(glidePath);
            return glidePath;
        }
    }

    /// <summary>
    /// Path to user config directory (~/.config/glide).
    /// </summary>
    public static string ConfigHome
    {
        get
        {
            var env = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
            var basePath = string.IsNullOrWhiteSpace(env) ? Path.Combine(HomeDir, ".config") : env;
            var glidePath = Path.Combine(basePath, "glide");
            Directory.CreateDirectory(glidePath);
            return glidePath;
        }
    }

    /// <summary>
    /// Path to user runtime directory (/run/user/{uid} or private data-directory fallback).
    /// </summary>
    public static string RuntimeDir
    {
        get
        {
            var env = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
            if (!string.IsNullOrWhiteSpace(env)) return env;
            var fallback = Path.Combine(DataHome, "runtime");
            Directory.CreateDirectory(fallback);
            if (OperatingSystem.IsLinux())
                File.SetUnixFileMode(fallback, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            return fallback;
        }
    }

    /// <summary>
    /// Resolves standard XDG downloads directory, parsing user-dirs.dirs if present, otherwise ~/Downloads.
    /// </summary>
    public static string ResolveDownloadsDirectory()
    {
        try
        {
            var userDirsFile = Path.Combine(HomeDir, ".config", "user-dirs.dirs");
            if (File.Exists(userDirsFile))
            {
                foreach (var line in File.ReadAllLines(userDirsFile))
                {
                    var trimmed = line.Trim();
                    if (trimmed.StartsWith("XDG_DOWNLOAD_DIR=", StringComparison.OrdinalIgnoreCase))
                    {
                        var rawPath = trimmed["XDG_DOWNLOAD_DIR=".Length..].Trim('"', '\'');
                        rawPath = rawPath.Replace("$HOME", HomeDir, StringComparison.Ordinal);
                        if (Directory.Exists(rawPath))
                        {
                            return rawPath;
                        }
                    }
                }
            }
        }
        catch
        {
            // Fall back to standard folder
        }

        var fallback = Path.Combine(HomeDir, "Downloads");
        if (!Directory.Exists(fallback))
        {
            Directory.CreateDirectory(fallback);
        }
        return fallback;
    }
}
