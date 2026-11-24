using System.Runtime.InteropServices;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Detects whether the current process is running with an AppModel package identity
/// (for example, inside a Microsoft Store MSIX container).
/// </summary>
internal static class PackageIdentityHelper
{
    internal const int ErrorSuccess = 0;
    internal const int ErrorInsufficientBuffer = 122;
    internal const int AppModelErrorNoPackage = 15700;

    private static readonly Lazy<bool> CachedHasPackageIdentity = new(DetectPackageIdentity);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int GetCurrentPackageFullName(ref int packageFullNameLength, char[]? packageFullName);

    /// <summary>
    /// Returns true if the current process possesses an AppModel package identity.
    /// </summary>
    public static bool HasPackageIdentity => CachedHasPackageIdentity.Value;

    internal static bool IsPackagedResult(int win32Error) =>
        win32Error is ErrorSuccess or ErrorInsufficientBuffer;

    private static bool DetectPackageIdentity()
    {
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }

        try
        {
            int length = 0;
            int result = GetCurrentPackageFullName(ref length, null);
            return IsPackagedResult(result);
        }
        catch (EntryPointNotFoundException)
        {
            return false;
        }
        catch (DllNotFoundException)
        {
            return false;
        }
    }
}
