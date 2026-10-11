using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Glide.Transport.Discovery;

/// <summary>
/// Canonical Glide LAN availability profile shared with Android.
///
/// Discovery is deliberately unauthenticated and carries only ephemeral availability metadata.
/// Authentication material (SPKI pin and invitation token) is exchanged exclusively through the
/// out-of-band pairing invitation.
/// </summary>
public static class LanDiscoveryConstants
{
    public const string ServiceType = "_glide._tcp.local.";
    public const string ServiceTypeWithoutTrailingDot = "_glide._tcp.local";
    public const int DefaultPort = 42424;

    public const int ProtocolVersion = 1;
    public const int TransportProfileVersion = 1;
    public const int PairingRequired = 1;

    public const string DeviceTypePhone = "phone";
    public const string DeviceTypeTablet = "tablet";
    public const string DeviceTypeDesktop = "desktop";
    public const string DeviceTypeUnknown = "unknown";

    public const int MaxTxtBudgetBytes = 512;
    public const int MaxDeviceNameCodePoints = 64;
    public const int MaxDeviceNameUtf8Bytes = 128;

    private static readonly Regex PresenceRegex =
        new("^[0-9a-f]{32}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static bool IsValidPresenceId(string? value) =>
        value != null && PresenceRegex.IsMatch(value);

    public static bool IsValidDeviceType(string? value) =>
        value is DeviceTypePhone or DeviceTypeTablet or DeviceTypeDesktop or DeviceTypeUnknown;

    public static string GeneratePresenceId() =>
        Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();

    public static bool IsValidDeviceName(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return false;
        if (value.EnumerateRunes().Count() > MaxDeviceNameCodePoints) return false;
        return Encoding.UTF8.GetByteCount(value) <= MaxDeviceNameUtf8Bytes;
    }

    public static string NormalizeDeviceName(string? value)
    {
        string candidate = string.IsNullOrWhiteSpace(value)
            ? Environment.MachineName
            : value.Trim();

        string result = string.Concat(
            candidate.EnumerateRunes()
                .Take(MaxDeviceNameCodePoints)
                .Select(static rune => rune.ToString()));

        while (result.Length > 0 &&
               Encoding.UTF8.GetByteCount(result) > MaxDeviceNameUtf8Bytes)
        {
            int removeLength = char.IsLowSurrogate(result[^1]) && result.Length > 1
                ? 2
                : 1;
            result = result[..^removeLength];
        }

        return string.IsNullOrWhiteSpace(result) ? "Glide Device" : result;
    }
}

public enum LanDiscoveryDiagnosticStatus
{
    Healthy,
    NoEligibleInterfaces,
    MulticastBlocked
}

public sealed record LanDiscoveryDiagnostics(
    LanDiscoveryDiagnosticStatus Status,
    IReadOnlyList<string> ActiveInterfaceAddresses,
    string? ErrorMessage = null)
{
    public bool IsHealthy => Status == LanDiscoveryDiagnosticStatus.Healthy;
}

public sealed record DiscoveredPeer(
    string DeviceName,
    string Host,
    int Port,
    string PresenceId,
    string DeviceType = LanDiscoveryConstants.DeviceTypeUnknown,
    bool IsPairReady = false,
    bool SupportsTrustedReconnect = false
);

