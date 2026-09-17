using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace Glide.Transport.Discovery;

/// <summary>
/// Lightweight, zero-dependency mDNS / DNS-SD service for local network peer discovery (RFC 6762 / 6763).
/// Announces the Glide Windows service and discovers nearby Android and Windows peers broadcasting _glide._tcp.local.
/// </summary>
public sealed class MdnsDiscoveryService : IDisposable
{
    private static readonly IPAddress MulticastAddress = IPAddress.Parse("224.0.0.251");
    private const int MulticastPort = 5353;
    private const string ServiceType = "_glide._tcp.local";

    private readonly ConcurrentDictionary<string, (DiscoveredPeer Peer, DateTime LastSeen)> _peers = new(StringComparer.OrdinalIgnoreCase);
    private readonly Lock _advertisementLock = new();

    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private Task? _listenTask;
    private Task? _queryLoopTask;

    private bool _isAdvertising;
    private string _advertisedDeviceName = Environment.MachineName;
    private int _advertisedPort = LanDiscoveryConstants.DefaultPort;
    private string _advertisedFp = "";
    private byte _advertisedCap = LanDiscoveryConstants.DefaultCapabilities;

    public event Action<DiscoveredPeer>? PeerDiscovered;
    public event Action<DiscoveredPeer>? PeerLost;

    public IReadOnlyCollection<DiscoveredPeer> ActivePeers =>
        _peers.Values.Select(v => v.Peer).ToList();

    public void Start(bool enableBrowsing = true)
    {
        if (_udpClient != null) return;

        _cts = new CancellationTokenSource();

        try
        {
            _udpClient = new UdpClient();
            _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, MulticastPort));
            _udpClient.JoinMulticastGroup(MulticastAddress);
        }
        catch (SocketException)
        {
            // If port 5353 is already exclusively held, fall back to ephemeral port for browsing
            _udpClient = new UdpClient(0);
            try
            {
                _udpClient.JoinMulticastGroup(MulticastAddress);
            }
            catch
            {
                // Ignored if multicast join fails on ephemeral
            }
        }

        _listenTask = Task.Run(() => ListenLoopAsync(_cts.Token));

        if (enableBrowsing)
        {
            _queryLoopTask = Task.Run(() => QueryLoopAsync(_cts.Token));
        }
    }

    public void StartAdvertising(string deviceName, int port, string certFingerprintPrefix = "", byte capabilities = LanDiscoveryConstants.DefaultCapabilities)
    {
        lock (_advertisementLock)
        {
            _advertisedDeviceName = string.IsNullOrWhiteSpace(deviceName) ? Environment.MachineName : deviceName;
            _advertisedPort = port;
            _advertisedFp = certFingerprintPrefix.Length > 8 ? certFingerprintPrefix[..8] : certFingerprintPrefix;
            _advertisedCap = capabilities;
            _isAdvertising = true;
        }

        SendAnnouncement();
    }

    public void StopAdvertising()
    {
        lock (_advertisementLock)
        {
            if (!_isAdvertising) return;
            _isAdvertising = false;
        }

        // Send goodbye with TTL 0
        SendGoodbye();
    }

    public void QueryPeers()
    {
        if (_udpClient == null) return;
        try
        {
            byte[] queryPacket = BuildPtrQueryPacket(ServiceType);
            _udpClient.Send(queryPacket, queryPacket.Length, new IPEndPoint(MulticastAddress, MulticastPort));
        }
        catch
        {
            // Suppress send errors if network drops
        }
    }

    private async Task QueryLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            QueryPeers();
            PruneStalePeers();

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(5), ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task ListenLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _udpClient != null)
        {
            try
            {
                var result = await _udpClient.ReceiveAsync(ct).ConfigureAwait(false);
                ProcessIncomingPacket(result.Buffer, result.RemoteEndPoint);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                if (ct.IsCancellationRequested) break;
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
        }
        else
        {
            // It's a query: check if someone is querying for _glide._tcp.local
            lock (_advertisementLock)
            {
                if (_isAdvertising)
                {
                    SendAnnouncement();
                }
            }
        }
    }

    private void SendAnnouncement()
    {
        if (_udpClient == null) return;

        try
        {
            string devName;
            int port;
            string fp;
            byte cap;

            lock (_advertisementLock)
            {
                if (!_isAdvertising) return;
                devName = _advertisedDeviceName;
                port = _advertisedPort;
                fp = _advertisedFp;
                cap = _advertisedCap;
            }

            byte[] responsePacket = BuildResponsePacket(devName, port, fp, cap, ttlSeconds: 120);
            _udpClient.Send(responsePacket, responsePacket.Length, new IPEndPoint(MulticastAddress, MulticastPort));
        }
        catch
        {
            // Ignored
        }
    }

    private void SendGoodbye()
    {
        if (_udpClient == null) return;

        try
        {
            byte[] goodbyePacket = BuildResponsePacket(_advertisedDeviceName, _advertisedPort, _advertisedFp, _advertisedCap, ttlSeconds: 0);
            _udpClient.Send(goodbyePacket, goodbyePacket.Length, new IPEndPoint(MulticastAddress, MulticastPort));
        }
        catch
        {
            // Ignored
        }
    }

    private void ParseDnsResponse(byte[] buffer, IPEndPoint remoteEndPoint)
    {
        try
        {
            int offset = 12; // Skip 12-byte header
            ushort qdCount = (ushort)((buffer[4] << 8) | buffer[5]);
            ushort anCount = (ushort)((buffer[6] << 8) | buffer[7]);

            // Skip questions
            for (int i = 0; i < qdCount; i++)
            {
                SkipDnsName(buffer, ref offset);
                offset += 4; // QTYPE (2) + QCLASS (2)
            }

            string? instanceName = null;
            int port = LanDiscoveryConstants.DefaultPort;
            var txtAttrs = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            // Read answers & additional records
            int totalRecords = anCount + ((buffer[10] << 8) | buffer[11]); // ANCOUNT + ARCOUNT
            for (int i = 0; i < totalRecords && offset < buffer.Length; i++)
            {
                string name = ReadDnsName(buffer, ref offset);
                if (offset + 10 > buffer.Length) break;

                ushort type = (ushort)((buffer[offset] << 8) | buffer[offset + 1]);
                ushort rdLength = (ushort)((buffer[offset + 8] << 8) | buffer[offset + 9]);
                offset += 10;

                int rdEnd = offset + rdLength;
                if (rdEnd > buffer.Length) break;

                if (type == 12) // PTR
                {
                    int ptrOffset = offset;
                    instanceName = ReadDnsName(buffer, ref ptrOffset);
                }
                else if (type == 33) // SRV
                {
                    if (rdLength >= 6)
                    {
                        port = (buffer[offset + 4] << 8) | buffer[offset + 5];
                    }
                }
                else if (type == 16) // TXT
                {
                    int txtOffset = offset;
                    while (txtOffset < rdEnd)
                    {
                        int len = buffer[txtOffset++];
                        if (txtOffset + len > rdEnd) break;
                        string attr = Encoding.UTF8.GetString(buffer, txtOffset, len);
                        txtOffset += len;
                        int eq = attr.IndexOf('=');
                        if (eq > 0)
                        {
                            txtAttrs[attr[..eq]] = attr[(eq + 1)..];
                        }
                    }
                }

                offset = rdEnd;
            }

            if (instanceName != null && instanceName.Contains("_glide", StringComparison.OrdinalIgnoreCase))
            {
                string devName = txtAttrs.GetValueOrDefault("dn", instanceName.Split('.')[0]);
                string devType = txtAttrs.GetValueOrDefault("dt", LanDiscoveryConstants.DeviceTypeDesktop);
                string fp = txtAttrs.GetValueOrDefault("fp", "");
                byte cap = LanDiscoveryConstants.DefaultCapabilities;
                if (txtAttrs.TryGetValue("cap", out var capStr) && capStr.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
                {
                    _ = byte.TryParse(capStr[2..], System.Globalization.NumberStyles.HexNumber, null, out cap);
                }

                string hostIp = remoteEndPoint.Address.ToString();

                // Skip our own advertised self if device name and port match
                if (_isAdvertising && devName == _advertisedDeviceName && port == _advertisedPort)
                {
                    return;
                }

                var peer = new DiscoveredPeer(devName, hostIp, port, fp, cap, devType);
                string key = $"{hostIp}:{port}";

                bool isNew = !_peers.ContainsKey(key);
                _peers[key] = (peer, DateTime.UtcNow);

                if (isNew)
                {
                    PeerDiscovered?.Invoke(peer);
                }
            }
        }
        catch
        {
            // Ignore malformed incoming packets
        }
    }

    private void PruneStalePeers()
    {
        var now = DateTime.UtcNow;
        var staleTimeout = TimeSpan.FromSeconds(15);

        foreach (var (key, (peer, lastSeen)) in _peers)
        {
            if (now - lastSeen > staleTimeout)
            {
                if (_peers.TryRemove(key, out var removed))
                {
                    PeerLost?.Invoke(removed.Peer);
                }
            }
        }
    }

    private static byte[] BuildPtrQueryPacket(string service)
    {
        var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);

        // Header: ID = 0, Flags = 0x0000, QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        w.Write((ushort)0);
        w.Write((byte)0x00); w.Write((byte)0x00);
        w.Write((byte)0x00); w.Write((byte)0x01); // QDCOUNT = 1
        w.Write((ushort)0); // ANCOUNT
        w.Write((ushort)0); // NSCOUNT
        w.Write((ushort)0); // ARCOUNT

        // Question: QNAME
        WriteDnsName(w, service);
        w.Write((byte)0x00); w.Write((byte)0x0C); // QTYPE = PTR (12)
        w.Write((byte)0x80); w.Write((byte)0x01); // QCLASS = IN (1) with unicast-response bit (0x8000)

        return ms.ToArray();
    }

    private static byte[] BuildResponsePacket(string deviceName, int port, string fp, byte cap, uint ttlSeconds)
    {
        var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);

        string serviceInstance = $"{deviceName}.{ServiceType}";

        // Header: ID = 0, Flags = 0x8400 (Response, Authoritative), QDCOUNT = 0, ANCOUNT = 3, ARCOUNT = 0
        w.Write((ushort)0);
        w.Write((byte)0x84); w.Write((byte)0x00);
        w.Write((ushort)0); // QDCOUNT
        w.Write((byte)0x00); w.Write((byte)0x03); // ANCOUNT = 3 (PTR, SRV, TXT)
        w.Write((ushort)0); // NSCOUNT
        w.Write((ushort)0); // ARCOUNT

        // Answer 1: PTR Record (_glide._tcp.local -> deviceName._glide._tcp.local)
        WriteDnsName(w, ServiceType);
        w.Write((byte)0x00); w.Write((byte)0x0C); // TYPE = PTR
        w.Write((byte)0x00); w.Write((byte)0x01); // CLASS = IN
        WriteUInt32BigEndian(w, ttlSeconds); // TTL
        byte[] ptrTarget = EncodeDnsName(serviceInstance);
        WriteUInt16BigEndian(w, (ushort)ptrTarget.Length);
        w.Write(ptrTarget);

        // Answer 2: SRV Record (Priority=0, Weight=0, Port, Target=hostname.local)
        WriteDnsName(w, serviceInstance);
        w.Write((byte)0x00); w.Write((byte)0x21); // TYPE = SRV (33)
        w.Write((byte)0x80); w.Write((byte)0x01); // CLASS = IN | Flush (0x8001)
        WriteUInt32BigEndian(w, ttlSeconds); // TTL
        byte[] srvHost = EncodeDnsName($"{Environment.MachineName.ToLowerInvariant()}.local");
        WriteUInt16BigEndian(w, (ushort)(6 + srvHost.Length));
        w.Write((ushort)0); // Priority
        w.Write((ushort)0); // Weight
        WriteUInt16BigEndian(w, (ushort)port); // Port
        w.Write(srvHost);

        // Answer 3: TXT Record (v=1, dn=..., dt=desktop, alpn=glide-v1, fp=..., cap=0x07)
        WriteDnsName(w, serviceInstance);
        w.Write((byte)0x00); w.Write((byte)0x10); // TYPE = TXT (16)
        w.Write((byte)0x80); w.Write((byte)0x01); // CLASS = IN | Flush (0x8001)
        WriteUInt32BigEndian(w, ttlSeconds); // TTL

        var txtParts = new List<string>
        {
            "v=1",
            $"dn={deviceName}",
            "dt=desktop",
            "alpn=glide-v1",
            $"cap=0x{cap:X2}"
        };
        if (!string.IsNullOrEmpty(fp))
        {
            txtParts.Add($"fp={fp}");
        }

        var txtStream = new MemoryStream();
        foreach (string part in txtParts)
        {
            byte[] partBytes = Encoding.UTF8.GetBytes(part);
            txtStream.WriteByte((byte)partBytes.Length);
            txtStream.Write(partBytes);
        }
        byte[] txtPayload = txtStream.ToArray();
        WriteUInt16BigEndian(w, (ushort)txtPayload.Length);
        w.Write(txtPayload);

        return ms.ToArray();
    }

    private static void WriteDnsName(BinaryWriter w, string name)
    {
        byte[] encoded = EncodeDnsName(name);
        w.Write(encoded);
    }

    private static byte[] EncodeDnsName(string name)
    {
        var ms = new MemoryStream();
        string[] parts = name.Split('.', StringSplitOptions.RemoveEmptyEntries);
        foreach (string part in parts)
        {
            byte[] b = Encoding.UTF8.GetBytes(part);
            ms.WriteByte((byte)b.Length);
            ms.Write(b);
        }
        ms.WriteByte(0x00);
        return ms.ToArray();
    }

    private static string ReadDnsName(byte[] buffer, ref int offset)
    {
        var sb = new StringBuilder();
        int visited = 0;
        int current = offset;

        while (current < buffer.Length && visited < 10)
        {
            byte len = buffer[current++];
            if (len == 0)
            {
                offset = current;
                break;
            }

            if ((len & 0xC0) == 0xC0) // Compression pointer
            {
                if (current >= buffer.Length) break;
                int pointerOffset = ((len & 0x3F) << 8) | buffer[current++];
                offset = current; // Return original flow after first pointer
                current = pointerOffset;
                visited++;
                continue;
            }

            if (current + len > buffer.Length) break;
            if (sb.Length > 0) sb.Append('.');
            sb.Append(Encoding.UTF8.GetString(buffer, current, len));
            current += len;
        }

        return sb.ToString();
    }

    private static void SkipDnsName(byte[] buffer, ref int offset)
    {
        while (offset < buffer.Length)
        {
            byte len = buffer[offset++];
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0)
            {
                offset++; // Skip 2nd byte of pointer
                break;
            }
            offset += len;
        }
    }

    private static void WriteUInt16BigEndian(BinaryWriter w, ushort val)
    {
        w.Write((byte)((val >> 8) & 0xFF));
        w.Write((byte)(val & 0xFF));
    }

    private static void WriteUInt32BigEndian(BinaryWriter w, uint val)
    {
        w.Write((byte)((val >> 24) & 0xFF));
        w.Write((byte)((val >> 16) & 0xFF));
        w.Write((byte)((val >> 8) & 0xFF));
        w.Write((byte)(val & 0xFF));
    }

    public void Dispose()
    {
        StopAdvertising();
        _cts?.Cancel();
        _udpClient?.Dispose();
        _udpClient = null;
        _cts?.Dispose();
        _cts = null;
    }
}
