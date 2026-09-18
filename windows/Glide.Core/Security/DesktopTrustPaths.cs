namespace Glide.Core.Security;

public static class DesktopTrustPaths
{
    public static string GetConfigDirectory()
    {
        if (OperatingSystem.IsLinux())
        {
            string? xdg =
                Environment.GetEnvironmentVariable(
                    "XDG_CONFIG_HOME");
            if (!string.IsNullOrWhiteSpace(xdg))
            {
                return Path.Combine(
                    Path.GetFullPath(xdg),
                    "glide");
            }

            string home =
                Environment.GetFolderPath(
                    Environment.SpecialFolder.UserProfile);
            return Path.Combine(
                home,
                ".config",
                "glide");
        }

        if (OperatingSystem.IsWindows())
        {
            return Path.Combine(
                Environment.GetFolderPath(
                    Environment.SpecialFolder.LocalApplicationData),
                "Glide",
                "trust");
        }

        throw new PlatformNotSupportedException(
            "Desktop trust storage currently supports Windows and Linux.");
    }
}
