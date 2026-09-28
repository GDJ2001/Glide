using Glide.Core.Security;

namespace Glide.Windows.UI.ViewModels;

public sealed class TrustedDeviceViewModel
{
    public TrustedDeviceViewModel(DesktopTrustedPeer peer)
    {
        DisplayName = peer.DisplayName;
        FingerprintHex = peer.FingerprintHex;
        LastSeen = DateTimeOffset.FromUnixTimeMilliseconds(peer.LastSeenAtUnixMs).LocalDateTime;
    }

    public string DisplayName { get; }
    public string FingerprintHex { get; }
    public DateTime LastSeen { get; }
    public string FingerprintShort => $"{FingerprintHex[..8]}…{FingerprintHex[^8..]}";
    public string LastSeenText => $"Last verified {LastSeen:g}";
}
