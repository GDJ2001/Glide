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

    public string CapabilitiesText
    {
        get
        {
            var caps = new List<string>();
            if ((Peer.Capabilities & LanDiscoveryConstants.CapFolderTransfer) != 0) caps.Add("Folders");
            if ((Peer.Capabilities & LanDiscoveryConstants.CapTrueResume) != 0) caps.Add("Resume");
            return caps.Count > 0 ? string.Join(" • ", caps) : "Glide v1";
        }
    }

    public DiscoveredPeerViewModel(DiscoveredPeer peer)
    {
        Peer = peer ?? throw new ArgumentNullException(nameof(peer));
    }
}
