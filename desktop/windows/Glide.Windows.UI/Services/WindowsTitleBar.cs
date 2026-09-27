using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Colors the native Windows 11 caption from the application's active palette.
/// Keeps native caption buttons, Snap layouts, dragging and system accessibility.
/// </summary>
internal static class WindowsTitleBar
{
    private const int ImmersiveDarkMode = 20;
    private const int CaptionColor = 35;
    private const int TextColor = 36;
    private const uint SystemDefaultColor = 0xFFFFFFFF;

    internal static void Apply(Window window)
    {
        if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000)) return;
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == IntPtr.Zero) return;

        // High contrast owns its caption colors; do not override the user's palette.
        if (SystemParameters.HighContrast || window.Background is not SolidColorBrush background)
        {
            Set(handle, ImmersiveDarkMode, 0);
            Set(handle, CaptionColor, SystemDefaultColor);
            Set(handle, TextColor, SystemDefaultColor);
            return;
        }

        var color = background.Color;
        bool dark = (color.R * 0.2126 + color.G * 0.7152 + color.B * 0.0722) < 128;
        var text = window.Foreground is SolidColorBrush foreground
            ? foreground.Color
            : dark ? Colors.White : Colors.Black;
        Set(handle, ImmersiveDarkMode, dark ? 1u : 0u);
        Set(handle, CaptionColor, ToColorRef(color));
        Set(handle, TextColor, ToColorRef(text));
    }

    private static uint ToColorRef(Color color) =>
        (uint)(color.R | color.G << 8 | color.B << 16);

    private static void Set(IntPtr handle, int attribute, uint value)
    {
        // Unsupported attributes fall back to the standard caption without breaking startup.
        _ = DwmSetWindowAttribute(handle, attribute, ref value, sizeof(uint));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(
        IntPtr handle, int attribute, ref uint value, int size);
}
