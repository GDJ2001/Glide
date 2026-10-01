using Glide.Transport.Discovery;

namespace Glide.Linux.UI.ViewModels;

public sealed class LinuxDiscoveredPeerViewModel : ViewModelBase
{
    public DiscoveredPeer Peer { get; }

    public string DisplayName => Peer.DeviceName;
    public string Host => Peer.Host;
    public int Port => Peer.Port;
    public string PresenceId => Peer.PresenceId;
    public string EndpointText => $"{Host}:{Port}";

    public LinuxDiscoveredPeerViewModel(DiscoveredPeer peer)
    {
        Peer = peer;
    }
}
