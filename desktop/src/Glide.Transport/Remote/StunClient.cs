using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace Glide.Transport.Remote;

/// <summary>
/// Result of an RFC 8489 STUN Binding discovery transaction.
/// </summary>
public sealed record StunDiscoveryResult(
    IPEndPoint PublicEndPoint,
    byte[] TransactionId,
    TimeSpan RoundTripTime);

/// <summary>
/// Zero-dependency RFC 8489 Session Traversal Utilities for NAT (STUN) client.
/// Performs UDP Binding Requests to discover Server-Reflexive public IP:port mappings.
/// Supports RFC 8489 XOR-MAPPED-ADDRESS (0x0020) and RFC 5389 MAPPED-ADDRESS (0x0001) fallback.
/// </summary>
public static class StunClient
{
    public const int DefaultStunPort = 3478;
    public const uint MagicCookie = 0x2112A442;
    public const ushort BindingRequestType = 0x0001;
    public const ushort BindingSuccessResponseType = 0x0101;
    public const ushort BindingErrorResponseType = 0x0111;

    public const ushort AttrMappedAddress = 0x0001;
    public const ushort AttrXorMappedAddress = 0x0020;

    public const int HeaderSize = 20;
    public const int TransactionIdLength = 12;

    /// <summary>
    /// Builds a 20-byte RFC 8489 STUN Binding Request packet.
    /// </summary>
    public static byte[] BuildBindingRequest(byte[]? transactionId = null)
    {
        if (transactionId != null && transactionId.Length != TransactionIdLength)
        {
            throw new ArgumentException($"Transaction ID must be exactly {TransactionIdLength} bytes.", nameof(transactionId));
        }

        byte[] packet = new byte[HeaderSize];

        // 1. Message Type (0x0001 Binding Request)
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(0, 2), BindingRequestType);

        // 2. Message Length (0x0000 - no attributes in base request)
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2, 2), 0);

        // 3. Magic Cookie (0x2112A442)
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4, 4), MagicCookie);

        // 4. Transaction ID (96 bits / 12 bytes)
        if (transactionId != null)
        {
            Buffer.BlockCopy(transactionId, 0, packet, 8, TransactionIdLength);
        }
        else
        {
            RandomNumberGenerator.Fill(packet.AsSpan(8, TransactionIdLength));
        }

        return packet;
    }

    /// <summary>
    /// Parses an RFC 8489 STUN response packet and extracts the public Reflexive IP:port endpoint.
    /// </summary>
    public static IPEndPoint? ParseBindingResponse(byte[] response, byte[]? expectedTransactionId = null)
    {
        ArgumentNullException.ThrowIfNull(response);

        if (response.Length < HeaderSize)
        {
            return null;
        }

        ushort messageType = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(0, 2));
        if (messageType != BindingSuccessResponseType)
        {
            return null;
        }

        ushort messageLength = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(2, 2));
        uint cookie = BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(4, 4));

        if (cookie != MagicCookie)
        {
            return null;
        }

        if (expectedTransactionId != null)
        {
            if (expectedTransactionId.Length != TransactionIdLength)
            {
                throw new ArgumentException($"Expected transaction ID must be {TransactionIdLength} bytes.", nameof(expectedTransactionId));
            }

            if (!response.AsSpan(8, TransactionIdLength).SequenceEqual(expectedTransactionId))
            {
                return null;
            }
        }

        byte[] transactionId = response[8..20];
        int totalPacketLength = Math.Min(response.Length, HeaderSize + messageLength);

        IPEndPoint? mappedAddressFallback = null;
        int offset = HeaderSize;

        while (offset + 4 <= totalPacketLength)
        {
            ushort attrType = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset, 2));
            ushort attrLength = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset + 2, 2));
            int valueOffset = offset + 4;

            if (valueOffset + attrLength > totalPacketLength)
            {
                break;
            }

            if (attrType == AttrXorMappedAddress && attrLength >= 8)
            {
                byte family = response[valueOffset + 1];
                ushort xorPort = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(valueOffset + 2, 2));
                int port = xorPort ^ (int)(MagicCookie >> 16);

                if (family == 0x01 && attrLength >= 8) // IPv4
                {
                    byte[] ipBytes = new byte[4];
                    byte[] cookieBytes = [0x21, 0x12, 0xA4, 0x42];
                    for (int i = 0; i < 4; i++)
                    {
                        ipBytes[i] = (byte)(response[valueOffset + 4 + i] ^ cookieBytes[i]);
                    }
                    return new IPEndPoint(new IPAddress(ipBytes), port);
                }
                else if (family == 0x02 && attrLength >= 20) // IPv6
                {
                    byte[] ipBytes = new byte[16];
                    byte[] xorKey = new byte[16];
                    BinaryPrimitives.WriteUInt32BigEndian(xorKey.AsSpan(0, 4), MagicCookie);
                    Buffer.BlockCopy(transactionId, 0, xorKey, 4, TransactionIdLength);

                    for (int i = 0; i < 16; i++)
                    {
                        ipBytes[i] = (byte)(response[valueOffset + 4 + i] ^ xorKey[i]);
                    }
                    return new IPEndPoint(new IPAddress(ipBytes), port);
                }
            }
            else if (attrType == AttrMappedAddress && attrLength >= 8 && mappedAddressFallback == null)
            {
                byte family = response[valueOffset + 1];
                ushort port = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(valueOffset + 2, 2));

                if (family == 0x01 && attrLength >= 8) // IPv4
                {
                    byte[] ipBytes = response.AsSpan(valueOffset + 4, 4).ToArray();
                    mappedAddressFallback = new IPEndPoint(new IPAddress(ipBytes), port);
                }
                else if (family == 0x02 && attrLength >= 20) // IPv6
                {
                    byte[] ipBytes = response.AsSpan(valueOffset + 4, 16).ToArray();
                    mappedAddressFallback = new IPEndPoint(new IPAddress(ipBytes), port);
                }
            }

            // Advance to next attribute, aligned to 4-byte boundaries (RFC 8489 Section 5)
            offset += 4 + ((attrLength + 3) & ~3);
        }

        return mappedAddressFallback;
    }

    /// <summary>
    /// Performs an asynchronous STUN query to discover the local machine's public server-reflexive endpoint.
    /// </summary>
    public static async Task<StunDiscoveryResult?> QueryServerReflexiveAddressAsync(
        string stunHost,
        int stunPort = DefaultStunPort,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(stunHost);

        var addresses = await Dns.GetHostAddressesAsync(stunHost, cancellationToken);
        var targetIp = addresses.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork)
                       ?? addresses.FirstOrDefault();

        if (targetIp == null)
        {
            return null;
        }

        var serverEndPoint = new IPEndPoint(targetIp, stunPort);
        using var udpClient = new UdpClient(targetIp.AddressFamily);

        byte[] requestPacket = BuildBindingRequest();
        byte[] transactionId = requestPacket[8..20];

        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        await udpClient.SendAsync(requestPacket, serverEndPoint, cancellationToken);

        var effectiveTimeout = timeout ?? TimeSpan.FromSeconds(3);
        using var timeoutCts = new CancellationTokenSource(effectiveTimeout);
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeoutCts.Token);

        try
        {
            var receiveTask = udpClient.ReceiveAsync(linkedCts.Token);
            var result = await receiveTask;
            stopwatch.Stop();

            var endpoint = ParseBindingResponse(result.Buffer, transactionId);
            if (endpoint != null)
            {
                return new StunDiscoveryResult(endpoint, transactionId, stopwatch.Elapsed);
            }

            return null;
        }
        catch (OperationCanceledException) when (timeoutCts.IsCancellationRequested)
        {
            return null;
        }
    }
}
