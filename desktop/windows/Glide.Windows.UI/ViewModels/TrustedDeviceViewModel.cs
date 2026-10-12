using Glide.Core.Security;

namespace Glide.Windows.UI.ViewModels;

public sealed class TrustedDeviceViewModel
{
    public TrustedDeviceViewModel(DesktopTrustedPeer peer)
    {
        Peer = peer ?? throw new ArgumentNullException(nameof(peer));
        DisplayName = peer.DisplayName;
        FingerprintHex = peer.FingerprintHex;
        LastSeen = DateTimeOffset.FromUnixTimeMilliseconds(peer.LastSeenAtUnixMs).LocalDateTime;
        Platform = peer.Platform;
        LastKnownHost = peer.LastKnownHost;
        LastKnownPort = peer.LastKnownPort;
    }

    public DesktopTrustedPeer Peer { get; }
    public string DisplayName { get; }
    public string FingerprintHex { get; }
    public DateTime LastSeen { get; }
    public string? Platform { get; }
    public string? LastKnownHost { get; }
    public int? LastKnownPort { get; }
    public string FingerprintShort => FingerprintHex.Length >= 16
        ? $"{FingerprintHex[..8]}…{FingerprintHex[^8..]}"
        : FingerprintHex;
    public string LastSeenText => $"Last verified {LastSeen:g}";
    public string ConnectAutomationName => $"Connect to {DisplayName}, identity {FingerprintShort}";
}
