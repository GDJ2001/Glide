using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace Glide.Transport.Discovery;

/// <summary>
/// Lightweight, zero-dependency, multi-interface mDNS / DNS-SD service for Glide LAN availability.
///
/// This implementation follows the canonical availability profile:
/// v=1, tv=1, q=1, p=&lt;128-bit presence id&gt;, dn=&lt;bounded display name&gt;,
/// dt=phone|tablet|desktop|unknown, plus optional pair=1 (during a 2-minute Ready-to-pair window)
/// and tr=1 (trusted reconnect support).
/// Discovery is never an authentication channel and never advertises PINs, tokens, trust status,
/// or certificate fingerprints.
/// </summary>
public sealed class MdnsDiscoveryService : IDisposable
{
    private static readonly IPAddress MulticastAddress = IPAddress.Parse("224.0.0.251");
    private const int MulticastPort = 5353;
    private const string ServiceType = LanDiscoveryConstants.ServiceTypeWithoutTrailingDot;

    private static readonly HashSet<string> ForbiddenTxtKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "spki", "pin", "token", "trust", "fp"
    };

    private readonly Func<IReadOnlyList<IPAddress>> _interfaceAddressProvider;
    private readonly ConcurrentDictionary<string, (DiscoveredPeer Peer, DateTime LastSeen)> _peers =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Lock _advertisementLock = new();
    private readonly Lock _socketLock = new();

    private UdpClient? _udpClient;
    private readonly List<UdpClient> _interfaceClients = [];
    private CancellationTokenSource? _cts;
    private readonly List<Task> _listenTasks = [];
    private Task? _queryLoopTask;

    private bool _isAdvertising;
    private string _advertisedDeviceName = LanDiscoveryConstants.NormalizeDeviceName(Environment.MachineName);
    private int _advertisedPort = LanDiscoveryConstants.DefaultPort;
    private string _advertisedPresenceId = string.Empty;
    private bool _advertisedPairReady;
    private bool _advertisedTrustedReconnect = true;

    private LanDiscoveryDiagnostics _diagnostics = new(
        LanDiscoveryDiagnosticStatus.Healthy,
        Array.Empty<string>());

    public MdnsDiscoveryService(Func<IReadOnlyList<IPAddress>>? interfaceAddressProvider = null)
    {
        _interfaceAddressProvider = interfaceAddressProvider ?? EnumerateEligibleIpv4Addresses;
    }

    public event Action<DiscoveredPeer>? PeerDiscovered;
    public event Action<DiscoveredPeer>? PeerLost;
    public event Action<LanDiscoveryDiagnostics>? DiagnosticsChanged;

    public LanDiscoveryDiagnostics Diagnostics
    {
        get
        {
            lock (_socketLock)
            {
                return _diagnostics;
            }
        }
    }

    public IReadOnlyCollection<DiscoveredPeer> ActivePeers =>
        _peers.Values.Select(static value => value.Peer).ToList();

    public void Start(bool enableBrowsing = true)
    {
        lock (_socketLock)
        {
            if (_udpClient != null || _interfaceClients.Count > 0) return;

            _cts = new CancellationTokenSource();
            var eligibleIps = _interfaceAddressProvider();

            if (eligibleIps.Count == 0)
            {
                UpdateDiagnosticsLocked(new LanDiscoveryDiagnostics(
                    LanDiscoveryDiagnosticStatus.NoEligibleInterfaces,
                    Array.Empty<string>(),
                    "Couldn't reach the local network — check your Wi-Fi or Ethernet connection."));
            }

            int joinedCount = 0;
            Exception? lastSocketError = null;

            try
            {
                _udpClient = CreateSharedMulticastClient(
                    IPAddress.Any,
                    MulticastPort,
                    eligibleIps,
                    ref joinedCount,
                    ref lastSocketError);
            }
            catch (Exception ex)
            {
                lastSocketError = ex;
                try
                {
                    _udpClient = CreateSharedMulticastClient(
                        IPAddress.Any,
                        0,
                        eligibleIps,
                        ref joinedCount,
                        ref lastSocketError);
                }
                catch (Exception fallbackEx)
                {
                    lastSocketError = fallbackEx;
                    _udpClient = null;
                }
            }

            foreach (IPAddress ip in eligibleIps)
            {
                try
                {
                    UdpClient ifaceClient = CreatePerInterfaceClient(ip, MulticastPort);
                    _interfaceClients.Add(ifaceClient);
                    joinedCount++;
                }
                catch (Exception ex)
                {
                    lastSocketError = ex;
                    try
                    {
                        UdpClient fallbackClient = CreatePerInterfaceClient(ip, 0);
                        _interfaceClients.Add(fallbackClient);
                        joinedCount++;
                    }
                    catch (Exception fallbackEx)
                    {
                        lastSocketError = fallbackEx;
                    }
                }
            }

            if (eligibleIps.Count > 0)
            {
                if (joinedCount == 0 && _udpClient == null && _interfaceClients.Count == 0)
                {
                    UpdateDiagnosticsLocked(new LanDiscoveryDiagnostics(
                        LanDiscoveryDiagnosticStatus.MulticastBlocked,
                        eligibleIps.Select(static ip => ip.ToString()).ToList(),
                        lastSocketError?.Message ?? "UDP multicast port 5353 is blocked by firewall or network policy."));
                }
                else
                {
                    UpdateDiagnosticsLocked(new LanDiscoveryDiagnostics(
                        LanDiscoveryDiagnosticStatus.Healthy,
                        eligibleIps.Select(static ip => ip.ToString()).ToList()));
                }
            }

            CancellationToken token = _cts.Token;
            if (_udpClient != null)
            {
                UdpClient primary = _udpClient;
                _listenTasks.Add(Task.Run(() => ListenLoopAsync(primary, token), token));
            }

            foreach (UdpClient ifaceClient in _interfaceClients)
            {
                UdpClient captured = ifaceClient;
                _listenTasks.Add(Task.Run(() => ListenLoopAsync(captured, token), token));
            }

            if (enableBrowsing)
            {
                _queryLoopTask = Task.Run(() => QueryLoopAsync(token), token);
            }
        }
    }

    private static UdpClient CreateSharedMulticastClient(
        IPAddress bindAddress,
        int port,
        IReadOnlyList<IPAddress> eligibleIps,
        ref int joinedCount,
        ref Exception? lastError)
    {
        var client = new UdpClient(AddressFamily.InterNetwork);
        client.ExclusiveAddressUse = false;
        client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        client.Client.Bind(new IPEndPoint(bindAddress, port));

        if (eligibleIps.Count == 0)
        {
            try
            {
                client.JoinMulticastGroup(MulticastAddress);
                joinedCount++;
            }
            catch (Exception ex)
            {
                lastError = ex;
            }
        }
        else
        {
            foreach (IPAddress ip in eligibleIps)
            {
                try
                {
                    client.JoinMulticastGroup(MulticastAddress, ip);
                    joinedCount++;
                }
                catch (Exception ex)
                {
                    lastError = ex;
                }
            }
        }

        return client;
    }

    private static UdpClient CreatePerInterfaceClient(IPAddress interfaceIp, int port)
    {
        var client = new UdpClient(AddressFamily.InterNetwork);
        client.ExclusiveAddressUse = false;
        client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        client.Client.Bind(new IPEndPoint(interfaceIp, port));
        try
        {
            client.JoinMulticastGroup(MulticastAddress, interfaceIp);
        }
        catch
        {
            // Outbound interface binding still succeeds even if duplicate membership is rejected on some kernels.
        }
        try
        {
            client.Client.SetSocketOption(
                SocketOptionLevel.IP,
                SocketOptionName.MulticastTimeToLive,
                255);
        }
        catch
        {
            // Non-fatal if OS restricts TTL option.
        }
        return client;
    }

    public static IReadOnlyList<IPAddress> EnumerateEligibleIpv4Addresses()
    {
        try
        {
            var adapters = NetworkInterface.GetAllNetworkInterfaces();
            var primaryLan = new List<IPAddress>();
            var secondaryLan = new List<IPAddress>();
            var vboxNat = new List<IPAddress>();

            foreach (NetworkInterface adapter in adapters)
            {
                if (adapter.OperationalStatus != OperationalStatus.Up) continue;
                if (adapter.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

                IPInterfaceProperties ipProps;
                try
                {
                    ipProps = adapter.GetIPProperties();
                }
                catch
                {
                    continue;
                }

                var gateways = new List<IPAddress>();
                try
                {
                    foreach (GatewayIPAddressInformation gw in ipProps.GatewayAddresses)
                    {
                        if (gw?.Address != null &&
                            gw.Address.AddressFamily == AddressFamily.InterNetwork &&
                            !gw.Address.Equals(IPAddress.Any))
                        {
                            gateways.Add(gw.Address);
                        }
                    }
                }
                catch
                {
                    // Ignore gateway enumeration errors
                }

                try
                {
                    foreach (UnicastIPAddressInformation unicast in ipProps.UnicastAddresses)
                    {
                        IPAddress addr = unicast.Address;
                        if (addr.AddressFamily != AddressFamily.InterNetwork) continue;
                        if (IPAddress.IsLoopback(addr) || addr.Equals(IPAddress.Any)) continue;

                        byte[] bytes = addr.GetAddressBytes();
                        // Exclude IPv4 link-local 169.254.0.0/16
                        if (bytes[0] == 169 && bytes[1] == 254) continue;

                        bool isVboxNat = bytes[0] == 10 && bytes[1] == 0 && bytes[2] == 2 &&
                                         gateways.Any(static g =>
                                         {
                                             byte[] gb = g.GetAddressBytes();
                                             return gb.Length == 4 && gb[0] == 10 && gb[1] == 0 && gb[2] == 2 && gb[3] == 2;
                                         });

                        if (isVboxNat)
                        {
                            vboxNat.Add(addr);
                        }
                        else if (gateways.Count > 0)
                        {
                            primaryLan.Add(addr);
                        }
                        else
                        {
                            secondaryLan.Add(addr);
                        }
                    }
                }
                catch
                {
                    // Ignore unicast enumeration errors
                }
            }

            var result = new List<IPAddress>();
            foreach (IPAddress ip in primaryLan.Concat(secondaryLan))
            {
                if (!result.Contains(ip))
                {
                    result.Add(ip);
                }
            }

            if (result.Count == 0 && vboxNat.Count > 0)
            {
                foreach (IPAddress ip in vboxNat)
                {
                    if (!result.Contains(ip))
                    {
                        result.Add(ip);
                    }
                }
            }

            return result;
        }
        catch
        {
            return Array.Empty<IPAddress>();
        }
    }

    public void StartAdvertising(
        string deviceName,
        int port,
        string presenceId,
        bool pairReady = false,
        bool trustedReconnectSupported = true)
    {
        if (port is < 1 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(port));
        }

        if (!LanDiscoveryConstants.IsValidPresenceId(presenceId))
        {
            throw new ArgumentException(
                "Presence id must be exactly 32 lowercase hexadecimal characters.",
                nameof(presenceId));
        }

        lock (_advertisementLock)
        {
            _advertisedDeviceName = LanDiscoveryConstants.NormalizeDeviceName(deviceName);
            _advertisedPort = port;
            _advertisedPresenceId = presenceId;
            _advertisedPairReady = pairReady;
            _advertisedTrustedReconnect = trustedReconnectSupported;
            _isAdvertising = true;
        }

        SendAnnouncement();
    }

    public void SetPairReadiness(bool pairReady)
    {
        bool shouldAnnounce = false;
        lock (_advertisementLock)
        {
            if (_advertisedPairReady != pairReady)
            {
                _advertisedPairReady = pairReady;
                shouldAnnounce = _isAdvertising;
            }
        }

        if (shouldAnnounce)
        {
            SendAnnouncement();
        }
    }

    public void StopAdvertising()
    {
        lock (_advertisementLock)
        {
            if (!_isAdvertising) return;
            _isAdvertising = false;
            _advertisedPairReady = false;
        }

        SendGoodbye();
    }

    public void QueryPeers()
    {
        byte[] multicastQuery = BuildPtrQueryPacket(ServiceType, unicastResponse: false);
        byte[] unicastQuery = BuildPtrQueryPacket(ServiceType, unicastResponse: true);
        SendMulticastPacket(multicastQuery);
        SendMulticastPacket(unicastQuery);
    }

    private void SendMulticastPacket(byte[] packet)
    {
        var destination = new IPEndPoint(MulticastAddress, MulticastPort);
        List<UdpClient> clients;
        UdpClient? fallbackPrimary;

        lock (_socketLock)
        {
            clients = [.. _interfaceClients];
            fallbackPrimary = _udpClient;
        }

        int sentCount = 0;
        Exception? lastSendError = null;

        foreach (UdpClient client in clients)
        {
            try
            {
                client.Send(packet, packet.Length, destination);
                sentCount++;
            }
            catch (Exception ex)
            {
                lastSendError = ex;
            }
        }

        if (fallbackPrimary != null)
        {
            try
            {
                fallbackPrimary.Send(packet, packet.Length, destination);
                sentCount++;
            }
            catch (Exception ex)
            {
                lastSendError = ex;
            }
        }

        if (sentCount == 0 && (clients.Count > 0 || fallbackPrimary != null))
        {
            lock (_socketLock)
            {
                UpdateDiagnosticsLocked(new LanDiscoveryDiagnostics(
                    LanDiscoveryDiagnosticStatus.MulticastBlocked,
                    _diagnostics.ActiveInterfaceAddresses,
                    lastSendError?.Message ?? "Unable to send mDNS multicast packets on the local network."));
            }
        }
    }

    private void UpdateDiagnosticsLocked(LanDiscoveryDiagnostics next)
    {
        bool changed = _diagnostics.Status != next.Status ||
                       !string.Equals(_diagnostics.ErrorMessage, next.ErrorMessage, StringComparison.Ordinal);
        _diagnostics = next;
        if (changed)
        {
            DiagnosticsChanged?.Invoke(next);
        }
    }

    private async Task QueryLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            QueryPeers();
            PruneStalePeers();

            try
            {
                await Task.Delay(
                    TimeSpan.FromSeconds(3),
                    cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task ListenLoopAsync(UdpClient client, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var result = await client
                    .ReceiveAsync(cancellationToken)
                    .ConfigureAwait(false);
                ProcessIncomingPacket(result.Buffer, result.RemoteEndPoint);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch
            {
                if (cancellationToken.IsCancellationRequested) break;
            }
        }
    }

    private void ProcessIncomingPacket(byte[] buffer, IPEndPoint remoteEndPoint)
    {
        if (buffer.Length < 12) return;

        ushort flags = (ushort)((buffer[2] << 8) | buffer[3]);
        bool isResponse = (flags & 0x8000) != 0;

        if (isResponse)
        {
            ParseDnsResponse(buffer, remoteEndPoint);
            return;
        }

        lock (_advertisementLock)
        {
            if (_isAdvertising)
            {
                SendAnnouncement();
            }
        }
    }

    private void SendAnnouncement()
    {
        try
        {
            string deviceName;
            int port;
            string presenceId;
            bool pairReady;
            bool trustedReconnect;

            lock (_advertisementLock)
            {
                if (!_isAdvertising) return;
                deviceName = _advertisedDeviceName;
                port = _advertisedPort;
                presenceId = _advertisedPresenceId;
                pairReady = _advertisedPairReady;
                trustedReconnect = _advertisedTrustedReconnect;
            }

            byte[] responsePacket = BuildResponsePacket(
                deviceName,
                port,
                presenceId,
                ttlSeconds: 120,
                pairReady: pairReady,
                trustedReconnectSupported: trustedReconnect);
            SendMulticastPacket(responsePacket);
        }
        catch
        {
            // Advertising failure is surfaced by diagnostics and absence from discovery; no insecure fallback.
        }
    }

    private void SendGoodbye()
    {
        if (!LanDiscoveryConstants.IsValidPresenceId(_advertisedPresenceId))
        {
            return;
        }

        try
        {
            byte[] packet = BuildResponsePacket(
                _advertisedDeviceName,
                _advertisedPort,
                _advertisedPresenceId,
                ttlSeconds: 0,
                pairReady: false,
                trustedReconnectSupported: _advertisedTrustedReconnect);
            SendMulticastPacket(packet);
        }
        catch
        {
            // Best-effort DNS-SD goodbye.
        }
    }

    private void ParseDnsResponse(byte[] buffer, IPEndPoint remoteEndPoint)
    {
        try
        {
            int offset = 12;
            ushort questionCount = (ushort)((buffer[4] << 8) | buffer[5]);
            ushort answerCount = (ushort)((buffer[6] << 8) | buffer[7]);

            for (int i = 0; i < questionCount; i++)
            {
                SkipDnsName(buffer, ref offset);
                if (offset + 4 > buffer.Length) return;
                offset += 4;
            }

            string? instanceName = null;
            int port = LanDiscoveryConstants.DefaultPort;
            uint minTtl = uint.MaxValue;
            var txtAttributes = new Dictionary<string, string>(StringComparer.Ordinal);
            int txtWireBytes = 0;

            int additionalCount = (buffer[10] << 8) | buffer[11];
            int totalRecords = answerCount + additionalCount;

            for (int i = 0; i < totalRecords && offset < buffer.Length; i++)
            {
                _ = ReadDnsName(buffer, ref offset);
                if (offset + 10 > buffer.Length) break;

                ushort type = (ushort)((buffer[offset] << 8) | buffer[offset + 1]);
                uint recordTtl = ((uint)buffer[offset + 4] << 24) |
                                 ((uint)buffer[offset + 5] << 16) |
                                 ((uint)buffer[offset + 6] << 8) |
                                 buffer[offset + 7];
                ushort dataLength = (ushort)((buffer[offset + 8] << 8) | buffer[offset + 9]);
                offset += 10;

                int dataEnd = offset + dataLength;
                if (dataEnd > buffer.Length) break;

                if (type is 12 or 33 or 16)
                {
                    if (recordTtl < minTtl) minTtl = recordTtl;
                }

                if (type == 12)
                {
                    int ptrOffset = offset;
                    instanceName = ReadDnsName(buffer, ref ptrOffset);
                }
                else if (type == 33 && dataLength >= 6)
                {
                    port = (buffer[offset + 4] << 8) | buffer[offset + 5];
                }
                else if (type == 16)
                {
                    int txtOffset = offset;
                    while (txtOffset < dataEnd)
                    {
                        int length = buffer[txtOffset++];
                        if (txtOffset + length > dataEnd) return;

                        txtWireBytes = checked(txtWireBytes + 1 + length);
                        if (txtWireBytes > LanDiscoveryConstants.MaxTxtBudgetBytes)
                        {
                            return;
                        }

                        string attribute = Encoding.UTF8.GetString(
                            buffer,
                            txtOffset,
                            length);
                        txtOffset += length;

                        int equalsIndex = attribute.IndexOf('=');
                        if (equalsIndex <= 0) continue;

                        string key = attribute[..equalsIndex];
                        string value = attribute[(equalsIndex + 1)..];
                        if (!txtAttributes.TryAdd(key, value))
                        {
                            // Reject packets with duplicate TXT keys
                            return;
                        }
                    }
                }

                offset = dataEnd;
            }

            if (instanceName == null ||
                !instanceName.Contains("_glide", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }

            if (!IsCompatibleAvailabilityRecord(txtAttributes))
            {
                return;
            }

            string presenceId = txtAttributes["p"];
            string deviceName = txtAttributes["dn"];
            string deviceType = txtAttributes.GetValueOrDefault(
                "dt",
                LanDiscoveryConstants.DeviceTypeUnknown);
            bool isPairReady = txtAttributes.TryGetValue("pair", out string? pairVal) && pairVal == "1";
            bool supportsTrustedReconnect = txtAttributes.TryGetValue("tr", out string? trVal) && trVal == "1";
            string hostIp = remoteEndPoint.Address.ToString();

            lock (_advertisementLock)
            {
                if (_isAdvertising &&
                    string.Equals(
                        presenceId,
                        _advertisedPresenceId,
                        StringComparison.Ordinal) &&
                    port == _advertisedPort)
                {
                    return;
                }
            }

            string cacheKey = $"{hostIp}:{port}:{presenceId}";
            if (minTtl == 0)
            {
                if (_peers.TryRemove(cacheKey, out var removed))
                {
                    PeerLost?.Invoke(removed.Peer);
                }
                return;
            }

            var peer = new DiscoveredPeer(
                deviceName,
                hostIp,
                port,
                presenceId,
                deviceType,
                isPairReady,
                supportsTrustedReconnect);

            bool shouldNotify = !_peers.TryGetValue(cacheKey, out var existing) || existing.Peer != peer;
            _peers[cacheKey] = (peer, DateTime.UtcNow);
            if (shouldNotify)
            {
                PeerDiscovered?.Invoke(peer);
            }
        }
        catch
        {
            // Malformed or hostile discovery packets are ignored.
        }
    }

    public static bool IsCompatibleAvailabilityRecord(
        IReadOnlyDictionary<string, string> attributes)
    {
        foreach (string key in attributes.Keys)
        {
            if (ForbiddenTxtKeys.Contains(key))
            {
                return false;
            }
        }

        if (!attributes.TryGetValue("v", out string? v) || v != "1") return false;
        if (!attributes.TryGetValue("tv", out string? tv) || tv != "1") return false;
        if (!attributes.TryGetValue("q", out string? q) || q != "1") return false;
        if (!attributes.TryGetValue("p", out string? presenceId) ||
            !LanDiscoveryConstants.IsValidPresenceId(presenceId))
        {
            return false;
        }

        if (!attributes.TryGetValue("dn", out string? deviceName) ||
            !LanDiscoveryConstants.IsValidDeviceName(deviceName))
        {
            return false;
        }

        if (attributes.TryGetValue("pair", out string? pairVal) && pairVal is not ("0" or "1"))
        {
            return false;
        }

        if (attributes.TryGetValue("tr", out string? trVal) && trVal is not ("0" or "1"))
        {
            return false;
        }

        string deviceType = attributes.GetValueOrDefault(
            "dt",
            LanDiscoveryConstants.DeviceTypeUnknown);
        return LanDiscoveryConstants.IsValidDeviceType(deviceType);
    }

    private void PruneStalePeers()
    {
        DateTime now = DateTime.UtcNow;
        TimeSpan staleTimeout = TimeSpan.FromSeconds(15);

        foreach (var (key, (_, lastSeen)) in _peers)
        {
            if (now - lastSeen <= staleTimeout) continue;
            if (_peers.TryRemove(key, out var removed))
            {
                PeerLost?.Invoke(removed.Peer);
            }
        }
    }

    private static byte[] BuildPtrQueryPacket(string service, bool unicastResponse = false)
    {
        var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);

        writer.Write((ushort)0);
        writer.Write((byte)0x00);
        writer.Write((byte)0x00);
        writer.Write((byte)0x00);
        writer.Write((byte)0x01);
        writer.Write((ushort)0);
        writer.Write((ushort)0);
        writer.Write((ushort)0);

        WriteDnsName(writer, service);
        writer.Write((byte)0x00);
        writer.Write((byte)0x0C);
        writer.Write(unicastResponse ? (byte)0x80 : (byte)0x00);
        writer.Write((byte)0x01);

        return stream.ToArray();
    }

    internal static byte[] BuildResponsePacketForTesting(
        string deviceName,
        int port,
        string presenceId,
        uint ttlSeconds = 120,
        bool pairReady = false,
        bool trustedReconnectSupported = true) =>
        BuildResponsePacket(deviceName, port, presenceId, ttlSeconds, pairReady, trustedReconnectSupported);

    private static byte[] BuildResponsePacket(
        string deviceName,
        int port,
        string presenceId,
        uint ttlSeconds,
        bool pairReady = false,
        bool trustedReconnectSupported = true)
    {
        string normalizedDeviceName =
            LanDiscoveryConstants.NormalizeDeviceName(deviceName);
        if (!LanDiscoveryConstants.IsValidPresenceId(presenceId))
        {
            throw new ArgumentException("Invalid presence id.", nameof(presenceId));
        }

        var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);

        string serviceInstance = $"Glide-{presenceId[..8]}.{ServiceType}";

        writer.Write((ushort)0);
        writer.Write((byte)0x84);
        writer.Write((byte)0x00);
        writer.Write((ushort)0);
        writer.Write((byte)0x00);
        writer.Write((byte)0x03);
        writer.Write((ushort)0);
        writer.Write((ushort)0);

        WriteDnsName(writer, ServiceType);
        writer.Write((byte)0x00);
        writer.Write((byte)0x0C);
        writer.Write((byte)0x00);
        writer.Write((byte)0x01);
        WriteUInt32BigEndian(writer, ttlSeconds);
        byte[] ptrTarget = EncodeDnsName(serviceInstance);
        WriteUInt16BigEndian(writer, (ushort)ptrTarget.Length);
        writer.Write(ptrTarget);

        WriteDnsName(writer, serviceInstance);
        writer.Write((byte)0x00);
        writer.Write((byte)0x21);
        writer.Write((byte)0x80);
        writer.Write((byte)0x01);
        WriteUInt32BigEndian(writer, ttlSeconds);
        byte[] serverHost = EncodeDnsName(
            $"{Environment.MachineName.ToLowerInvariant()}.local");
        WriteUInt16BigEndian(writer, (ushort)(6 + serverHost.Length));
        writer.Write((ushort)0);
        writer.Write((ushort)0);
        WriteUInt16BigEndian(writer, (ushort)port);
        writer.Write(serverHost);

        WriteDnsName(writer, serviceInstance);
        writer.Write((byte)0x00);
        writer.Write((byte)0x10);
        writer.Write((byte)0x80);
        writer.Write((byte)0x01);
        WriteUInt32BigEndian(writer, ttlSeconds);

        var txtParts = new List<string>(8)
        {
            "v=1",
            "tv=1",
            "q=1",
            $"p={presenceId}",
            $"dn={normalizedDeviceName}",
            $"dt={LanDiscoveryConstants.DeviceTypeDesktop}"
        };

        if (pairReady)
        {
            txtParts.Add("pair=1");
        }

        if (trustedReconnectSupported)
        {
            txtParts.Add("tr=1");
        }

        using var txtStream = new MemoryStream();
        foreach (string part in txtParts)
        {
            byte[] partBytes = Encoding.UTF8.GetBytes(part);
            if (partBytes.Length > byte.MaxValue)
            {
                throw new InvalidOperationException(
                    "DNS-SD TXT attribute exceeds the 255-byte record limit.");
            }

            txtStream.WriteByte((byte)partBytes.Length);
            txtStream.Write(partBytes);
        }

        byte[] txtPayload = txtStream.ToArray();
        if (txtPayload.Length > LanDiscoveryConstants.MaxTxtBudgetBytes)
        {
            throw new InvalidOperationException(
                $"DNS-SD TXT payload exceeds {LanDiscoveryConstants.MaxTxtBudgetBytes} bytes.");
        }

        WriteUInt16BigEndian(writer, (ushort)txtPayload.Length);
        writer.Write(txtPayload);

        return stream.ToArray();
    }

    private static void WriteDnsName(BinaryWriter writer, string name) =>
        writer.Write(EncodeDnsName(name));

    private static byte[] EncodeDnsName(string name)
    {
        var stream = new MemoryStream();
        foreach (string part in name.Split('.', StringSplitOptions.RemoveEmptyEntries))
        {
            byte[] bytes = Encoding.UTF8.GetBytes(part);
            if (bytes.Length > 63)
            {
                throw new InvalidOperationException(
                    "DNS label exceeds RFC 1035 63-byte limit.");
            }

            stream.WriteByte((byte)bytes.Length);
            stream.Write(bytes);
        }

        stream.WriteByte(0x00);
        return stream.ToArray();
    }

    private static string ReadDnsName(byte[] buffer, ref int offset)
    {
        var builder = new StringBuilder();
        int visited = 0;
        int current = offset;

        while (current < buffer.Length && visited < 10)
        {
            byte length = buffer[current++];
            if (length == 0)
            {
                offset = current;
                break;
            }

            if ((length & 0xC0) == 0xC0)
            {
                if (current >= buffer.Length) break;
                int pointerOffset =
                    ((length & 0x3F) << 8) | buffer[current++];
                offset = current;
                current = pointerOffset;
                visited++;
                continue;
            }

            if (current + length > buffer.Length) break;
            if (builder.Length > 0) builder.Append('.');
            builder.Append(
                Encoding.UTF8.GetString(buffer, current, length));
            current += length;
        }

        return builder.ToString();
    }

    private static void SkipDnsName(byte[] buffer, ref int offset)
    {
        while (offset < buffer.Length)
        {
            byte length = buffer[offset++];
            if (length == 0) break;
            if ((length & 0xC0) == 0xC0)
            {
                offset++;
                break;
            }

            offset += length;
        }
    }

    private static void WriteUInt16BigEndian(BinaryWriter writer, ushort value)
    {
        writer.Write((byte)((value >> 8) & 0xFF));
        writer.Write((byte)(value & 0xFF));
    }

    private static void WriteUInt32BigEndian(BinaryWriter writer, uint value)
    {
        writer.Write((byte)((value >> 24) & 0xFF));
        writer.Write((byte)((value >> 16) & 0xFF));
        writer.Write((byte)((value >> 8) & 0xFF));
        writer.Write((byte)(value & 0xFF));
    }

    public void Dispose()
    {
        StopAdvertising();
        lock (_socketLock)
        {
            _cts?.Cancel();
            _udpClient?.Dispose();
            _udpClient = null;
            foreach (UdpClient ifaceClient in _interfaceClients)
            {
                try { ifaceClient.Dispose(); } catch { }
            }
            _interfaceClients.Clear();
            _listenTasks.Clear();
            _cts?.Dispose();
            _cts = null;
        }
    }
}
