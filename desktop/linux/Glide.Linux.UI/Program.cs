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
#pragma warning disable AVALONIA_X11_CSD // Avalonia 12 draws Linux resize grips for our themed title bar.
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .With(new X11PlatformOptions { EnableDrawnDecorations = true })
            .WithInterFont()
            .LogToTrace();
#pragma warning restore AVALONIA_X11_CSD
}
