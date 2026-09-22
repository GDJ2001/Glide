using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Glide.Transport.Discovery;

/// <summary>
/// Lightweight, zero-dependency mDNS / DNS-SD service for Glide LAN availability.
///
/// This implementation follows the canonical Android availability profile:
/// v=1, tv=1, q=1, p=&lt;128-bit presence id&gt;, dn=&lt;bounded display name&gt;,
/// dt=phone|tablet|desktop|unknown. Discovery is not an authentication channel and therefore
/// never advertises invitation tokens, certificate fingerprints, or trust/resume capabilities.
/// </summary>
public sealed class MdnsDiscoveryService : IDisposable
{
    private static readonly IPAddress MulticastAddress = IPAddress.Parse("224.0.0.251");
    private const int MulticastPort = 5353;
    private const string ServiceType = LanDiscoveryConstants.ServiceTypeWithoutTrailingDot;

    private readonly ConcurrentDictionary<string, (DiscoveredPeer Peer, DateTime LastSeen)> _peers =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Lock _advertisementLock = new();

    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private Task? _listenTask;
    private Task? _queryLoopTask;

    private bool _isAdvertising;
    private string _advertisedDeviceName = LanDiscoveryConstants.NormalizeDeviceName(Environment.MachineName);
    private int _advertisedPort = LanDiscoveryConstants.DefaultPort;
    private string _advertisedPresenceId = string.Empty;

    public event Action<DiscoveredPeer>? PeerDiscovered;
    public event Action<DiscoveredPeer>? PeerLost;

    public IReadOnlyCollection<DiscoveredPeer> ActivePeers =>
        _peers.Values.Select(static value => value.Peer).ToList();

    public void Start(bool enableBrowsing = true)
    {
        if (_udpClient != null) return;

        _cts = new CancellationTokenSource();

        try
        {
            _udpClient = new UdpClient();
            _udpClient.Client.SetSocketOption(
                SocketOptionLevel.Socket,
                SocketOptionName.ReuseAddress,
                true);
            _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, MulticastPort));
            _udpClient.JoinMulticastGroup(MulticastAddress);
        }
        catch (SocketException)
        {
            _udpClient = new UdpClient(0);
            try
            {
                _udpClient.JoinMulticastGroup(MulticastAddress);
            }
            catch
            {
                // Browsing will fail closed if multicast is unavailable.
            }
        }

        _listenTask = Task.Run(() => ListenLoopAsync(_cts.Token));
        if (enableBrowsing)
        {
            _queryLoopTask = Task.Run(() => QueryLoopAsync(_cts.Token));
        }
    }

    public void StartAdvertising(
        string deviceName,
        int port,
        string presenceId)
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

        SendGoodbye();
    }

    public void QueryPeers()
    {
        if (_udpClient == null) return;
        try
        {
            byte[] queryPacket = BuildPtrQueryPacket(ServiceType);
            _udpClient.Send(
                queryPacket,
                queryPacket.Length,
                new IPEndPoint(MulticastAddress, MulticastPort));
        }
        catch
        {
            // Network loss simply ends the current observation attempt.
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
                    TimeSpan.FromSeconds(5),
                    cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task ListenLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _udpClient != null)
        {
            try
            {
                var result = await _udpClient
                    .ReceiveAsync(cancellationToken)
                    .ConfigureAwait(false);
                ProcessIncomingPacket(result.Buffer, result.RemoteEndPoint);
            }
            catch (OperationCanceledException)
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
        if (_udpClient == null) return;

        try
        {
            string deviceName;
            int port;
            string presenceId;

            lock (_advertisementLock)
            {
                if (!_isAdvertising) return;
                deviceName = _advertisedDeviceName;
                port = _advertisedPort;
                presenceId = _advertisedPresenceId;
            }

            byte[] responsePacket = BuildResponsePacket(
                deviceName,
                port,
                presenceId,
                ttlSeconds: 120);
            _udpClient.Send(
                responsePacket,
                responsePacket.Length,
                new IPEndPoint(MulticastAddress, MulticastPort));
        }
        catch
        {
            // Advertising failure is surfaced by absence from discovery; no insecure fallback.
        }
    }

    private void SendGoodbye()
    {
        if (_udpClient == null ||
            !LanDiscoveryConstants.IsValidPresenceId(_advertisedPresenceId))
        {
            return;
        }

        try
        {
            byte[] packet = BuildResponsePacket(
                _advertisedDeviceName,
                _advertisedPort,
                _advertisedPresenceId,
                ttlSeconds: 0);
            _udpClient.Send(
                packet,
                packet.Length,
                new IPEndPoint(MulticastAddress, MulticastPort));
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
            var txtAttributes = new Dictionary<string, string>(StringComparer.Ordinal);
            int txtWireBytes = 0;

            int additionalCount = (buffer[10] << 8) | buffer[11];
            int totalRecords = answerCount + additionalCount;

            for (int i = 0; i < totalRecords && offset < buffer.Length; i++)
            {
                _ = ReadDnsName(buffer, ref offset);
                if (offset + 10 > buffer.Length) break;

                ushort type = (ushort)((buffer[offset] << 8) | buffer[offset + 1]);
                ushort dataLength = (ushort)((buffer[offset + 8] << 8) | buffer[offset + 9]);
                offset += 10;

                int dataEnd = offset + dataLength;
                if (dataEnd > buffer.Length) break;

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
                        txtAttributes[key] = value;
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

            var peer = new DiscoveredPeer(
                deviceName,
                hostIp,
                port,
                presenceId,
                deviceType);
            string cacheKey = $"{hostIp}:{port}:{presenceId}";

            bool isNew = !_peers.ContainsKey(cacheKey);
            _peers[cacheKey] = (peer, DateTime.UtcNow);
            if (isNew)
            {
                PeerDiscovered?.Invoke(peer);
            }
        }
        catch
        {
            // Malformed or hostile discovery packets are ignored.
        }
    }

    internal static bool IsCompatibleAvailabilityRecord(
        IReadOnlyDictionary<string, string> attributes)
    {
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

    private static byte[] BuildPtrQueryPacket(string service)
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
        writer.Write((byte)0x80);
        writer.Write((byte)0x01);

        return stream.ToArray();
    }

    internal static byte[] BuildResponsePacketForTesting(
        string deviceName,
        int port,
        string presenceId,
        uint ttlSeconds = 120) =>
        BuildResponsePacket(deviceName, port, presenceId, ttlSeconds);

    private static byte[] BuildResponsePacket(
        string deviceName,
        int port,
        string presenceId,
        uint ttlSeconds)
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

        string[] txtParts =
        [
            "v=1",
            "tv=1",
            "q=1",
            $"p={presenceId}",
            $"dn={normalizedDeviceName}",
            $"dt={LanDiscoveryConstants.DeviceTypeDesktop}"
        ];

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
        _cts?.Cancel();
        _udpClient?.Dispose();
        _udpClient = null;
        _cts?.Dispose();
        _cts = null;
    }
}
