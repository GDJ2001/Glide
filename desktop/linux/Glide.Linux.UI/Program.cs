using Avalonia;
using Glide.Linux.UI.Services;

namespace Glide.Linux.UI;

sealed class Program
{
    [STAThread]
    public static void Main(string[] args)
    {
        using var singleInstance = new LinuxSingleInstance();
        if (!singleInstance.TryAcquire())
        {
            return;
        }

        BuildAvaloniaApp()
            .StartWithClassicDesktopLifetime(args);
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();
}
