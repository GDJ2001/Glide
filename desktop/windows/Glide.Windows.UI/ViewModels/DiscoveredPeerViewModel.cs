using Glide.Transport.Discovery;
using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

public sealed class DiscoveredPeerViewModel : ViewModelBase
{
    public DiscoveredPeer Peer { get; }

    public string DeviceName => Peer.DeviceName;
    public string Host => Peer.Host;
    public int Port => Peer.Port;
    public string DeviceType => Peer.DeviceType;
    public string EndpointText => $"{Peer.Host}:{Peer.Port}";
    public string DeviceIcon => Peer.DeviceType.ToLowerInvariant() switch
    {
        "mobile" or "phone" => "📱",
        "tablet" => "📱",
        _ => "💻"
    };

    public string CapabilitiesText => "Pairing required • Glide LAN";

    public DiscoveredPeerViewModel(DiscoveredPeer peer)
    {
        Peer = peer ?? throw new ArgumentNullException(nameof(peer));
    }
}
