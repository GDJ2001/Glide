namespace Glide.Transport.Discovery;

public static class LanDiscoveryConstants
{
    public const string ServiceType = "_glide._tcp.local.";
    public const int DefaultPort = 42424;
    public const string AlpnToken = "glide-v1";
    public const string DeviceTypeDesktop = "desktop";

    public const byte CapFolderTransfer = 0x01;
    public const byte CapPersistentIdentity = 0x02;
    public const byte CapTrueResume = 0x04;
    public const byte DefaultCapabilities = CapFolderTransfer | CapPersistentIdentity | CapTrueResume; // 0x07
}

public sealed record DiscoveredPeer(
    string DeviceName,
    string Host,
    int Port,
    string FingerprintPrefix,
    byte Capabilities,
    string DeviceType = LanDiscoveryConstants.DeviceTypeDesktop
);
