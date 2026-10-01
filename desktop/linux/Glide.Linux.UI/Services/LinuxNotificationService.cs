using System.Diagnostics;

namespace Glide.Linux.UI.Services;

/// <summary>
/// Dispatches native desktop notifications on Linux systems via notify-send.
/// </summary>
public static class LinuxNotificationService
{
    public static void ShowNotification(string title, string body, string? iconPath = null)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "notify-send",
                ArgumentList = { "--app-name=Glide", title, body },
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            if (!string.IsNullOrWhiteSpace(iconPath))
            {
                psi.ArgumentList.Insert(1, $"--icon={iconPath}");
            }

            using var proc = Process.Start(psi);
        }
        catch
        {
            // Silently ignore if notify-send is unavailable
        }
    }
}
