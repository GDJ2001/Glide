using System.Text;

namespace Glide.Transport.Discovery;

public static class LanDiscoveryConstants
{
    public const string ServiceType = "_glide._tcp.local.";
    public const string AlpnToken = "glide-lan/1";
    public const int ProtocolVersion = 1;
    public const int TransportProfileVersion = 1;
    public const string DeviceTypeDesktop = "desktop";
    public const int MaxTxtBytes = 512;
    public const int MaxDeviceNameCodePoints = 64;
    public const int MaxDeviceNameUtf8Bytes = 128;

    // Retained as protocol capability bit definitions for UI/protocol code.
    // HARD-012 deliberately does not advertise them in DNS-SD.
    public const byte CapFolderTransfer = 0x01;
    public const byte CapPersistentIdentity = 0x02;
    public const byte CapTrueResume = 0x04;
    public const byte DefaultCapabilities = CapFolderTransfer | CapPersistentIdentity | CapTrueResume;
}

public sealed record LanDiscoveryTxtRecord(
    int Version,
    int TransportVersion,
    bool InvitationRequired,
    string PresenceId,
    string DeviceName,
    string DeviceType);

public static class LanDiscoveryTxtCodec
{
    private static readonly HashSet<string> AllowedDeviceTypes =
        new(StringComparer.Ordinal)
        {
            "phone",
            "tablet",
            "desktop",
            "unknown"
        };

    public static IReadOnlyDictionary<string, string> Encode(
        string presenceId,
        string deviceName,
        string deviceType = LanDiscoveryConstants.DeviceTypeDesktop)
    {
        ValidatePresenceId(presenceId);

        string safeName = ValidateDeviceName(deviceName);
        if (!AllowedDeviceTypes.Contains(deviceType))
        {
            throw new ArgumentException(
                $"Unsupported DNS-SD device type '{deviceType}'.",
                nameof(deviceType));
        }

        string safeDeviceType = deviceType;

        var attributes = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["v"] = LanDiscoveryConstants.ProtocolVersion.ToString(),
            ["tv"] = LanDiscoveryConstants.TransportProfileVersion.ToString(),
            ["q"] = "1",
            ["p"] = presenceId,
            ["dn"] = safeName,
            ["dt"] = safeDeviceType
        };

        EnsureTxtBudget(attributes);
        return attributes;
    }

    public static bool TryDecode(
        IReadOnlyDictionary<string, string> attributes,
        out LanDiscoveryTxtRecord? record)
    {
        record = null;

        if (!attributes.TryGetValue("v", out string? versionText) ||
            !int.TryParse(versionText, out int version) ||
            version != LanDiscoveryConstants.ProtocolVersion)
        {
            return false;
        }

        if (!attributes.TryGetValue("tv", out string? transportVersionText) ||
            !int.TryParse(transportVersionText, out int transportVersion) ||
            transportVersion != LanDiscoveryConstants.TransportProfileVersion)
        {
            return false;
        }

        if (!attributes.TryGetValue("q", out string? invitationRequiredText) ||
            invitationRequiredText != "1")
        {
            return false;
        }

        if (!attributes.TryGetValue("p", out string? presenceId) ||
            !IsValidPresenceId(presenceId))
        {
            return false;
        }

        if (!attributes.TryGetValue("dn", out string? deviceName) ||
            string.IsNullOrWhiteSpace(deviceName) ||
            deviceName.EnumerateRunes().Count() > LanDiscoveryConstants.MaxDeviceNameCodePoints ||
            Encoding.UTF8.GetByteCount(deviceName) > LanDiscoveryConstants.MaxDeviceNameUtf8Bytes)
        {
            return false;
        }

        string deviceType = "unknown";
        if (attributes.TryGetValue("dt", out string? dt))
        {
            if (!AllowedDeviceTypes.Contains(dt))
            {
                return false;
            }

            deviceType = dt;
        }

        try
        {
            EnsureTxtBudget(attributes);
        }
        catch (ArgumentException)
        {
            return false;
        }

        record = new LanDiscoveryTxtRecord(
            version,
            transportVersion,
            InvitationRequired: true,
            presenceId,
            deviceName,
            deviceType);
        return true;
    }

    private static string ValidateDeviceName(string deviceName)
    {
        string value = string.IsNullOrWhiteSpace(deviceName)
            ? "Glide Desktop"
            : deviceName.Trim();

        if (value.EnumerateRunes().Count() > LanDiscoveryConstants.MaxDeviceNameCodePoints)
        {
            value = string.Concat(value.EnumerateRunes()
                .Take(LanDiscoveryConstants.MaxDeviceNameCodePoints)
                .Select(r => r.ToString()));
        }

        while (Encoding.UTF8.GetByteCount(value) > LanDiscoveryConstants.MaxDeviceNameUtf8Bytes &&
               value.Length > 0)
        {
            value = value[..^1];
        }

        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException("Device name must contain at least one visible character.", nameof(deviceName));
        }

        return value;
    }

    private static void ValidatePresenceId(string presenceId)
    {
        if (!IsValidPresenceId(presenceId))
        {
            throw new ArgumentException(
                "Presence ID must be exactly 32 lowercase hexadecimal characters.",
                nameof(presenceId));
        }
    }

    private static bool IsValidPresenceId(string presenceId) =>
        presenceId.Length == 32 &&
        presenceId.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static void EnsureTxtBudget(IReadOnlyDictionary<string, string> attributes)
    {
        int encodedBytes = attributes.Sum(pair =>
        {
            int bytes = Encoding.UTF8.GetByteCount($"{pair.Key}={pair.Value}");
            if (bytes > byte.MaxValue)
            {
                throw new ArgumentException($"DNS-SD TXT entry '{pair.Key}' exceeds the 255-byte entry limit.");
            }

            return 1 + bytes;
        });

        if (encodedBytes > LanDiscoveryConstants.MaxTxtBytes)
        {
            throw new ArgumentException(
                $"DNS-SD TXT payload exceeds {LanDiscoveryConstants.MaxTxtBytes} bytes.");
        }
    }
}

public sealed record DiscoveredPeer(
    string DeviceName,
    string Host,
    int Port,
    string FingerprintPrefix,
    byte Capabilities,
    string DeviceType = LanDiscoveryConstants.DeviceTypeDesktop,
    string PresenceId = ""
);
