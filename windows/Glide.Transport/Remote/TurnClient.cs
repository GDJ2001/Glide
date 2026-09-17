using System;
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Glide.Core.Remote;

namespace Glide.Transport.Remote;

/// <summary>
/// Result of an RFC 8656 TURN Allocation transaction.
/// </summary>
public sealed record TurnAllocation(
    IPEndPoint RelayedEndPoint,
    uint LifetimeSeconds,
    byte[] TransactionId);

/// <summary>
/// Data packet received via a TURN Data Indication.
/// </summary>
public sealed record TurnDataPacket(
    IPEndPoint PeerEndPoint,
    byte[] Data);

/// <summary>
/// Data packet received via a TURN ChannelData message (RFC 8656 Section 12).
/// </summary>
public sealed record TurnChannelDataPacket(
    ushort ChannelNumber,
    byte[] Data);

/// <summary>
/// Zero-dependency RFC 8656 Traversal Using Relays around NAT (TURN) client.
/// Provides blind packet relay fallback, allocation management, permission grants,
/// and ChannelData framing. Operates strictly as a blind packet forwarder without
/// payload inspection capabilities.
/// </summary>
public static class TurnClient
{
    public const int DefaultTurnPort = 3478;
    public const uint MagicCookie = 0x2112A442;
    public const int HeaderSize = 20;
    public const int TransactionIdLength = 12;

    // TURN Message Types (RFC 8656 Section 6)
    public const ushort AllocateRequest = 0x0003;
    public const ushort AllocateSuccessResponse = 0x0103;
    public const ushort AllocateErrorResponse = 0x0113;

    public const ushort RefreshRequest = 0x0004;
    public const ushort RefreshSuccessResponse = 0x0104;

    public const ushort CreatePermissionRequest = 0x0008;
    public const ushort CreatePermissionSuccessResponse = 0x0108;

    public const ushort SendIndication = 0x0016;
    public const ushort DataIndication = 0x0017;

    public const ushort ChannelBindRequest = 0x0009;
    public const ushort ChannelBindSuccessResponse = 0x0109;

    // TURN Attributes (RFC 8656 Section 14)
    public const ushort AttrChannelNumber = 0x000C;
    public const ushort AttrLifetime = 0x000D;
    public const ushort AttrXorPeerAddress = 0x0012;
    public const ushort AttrData = 0x0013;
    public const ushort AttrXorRelayedAddress = 0x0016;
    public const ushort AttrRequestedTransport = 0x0019;
    public const ushort AttrDontFragment = 0x001A;

    public const byte TransportUdp = 17;

    /// <summary>
    /// Builds an RFC 8656 TURN Allocate Request packet.
    /// </summary>
    public static byte[] BuildAllocateRequest(
        byte transportProtocol = TransportUdp,
        uint? requestedLifetimeSeconds = null,
        byte[]? transactionId = null)
    {
        byte[] txId = ValidateOrGenerateTransactionId(transactionId);

        // Calculate attribute size:
        // REQUESTED-TRANSPORT: 4 bytes attr header + 4 bytes value = 8 bytes
        int totalAttrLength = 8;
        if (requestedLifetimeSeconds.HasValue)
        {
            // LIFETIME: 4 bytes attr header + 4 bytes value = 8 bytes
            totalAttrLength += 8;
        }

        byte[] packet = new byte[HeaderSize + totalAttrLength];

        // 1. STUN/TURN Header
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(0, 2), AllocateRequest);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2, 2), (ushort)totalAttrLength);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4, 4), MagicCookie);
        Buffer.BlockCopy(txId, 0, packet, 8, TransactionIdLength);

        int offset = HeaderSize;

        // 2. REQUESTED-TRANSPORT attribute (Type 0x0019, Length 4, Protocol + 3 bytes RFFU)
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(offset, 2), AttrRequestedTransport);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(offset + 2, 2), 4);
        packet[offset + 4] = transportProtocol;
        packet[offset + 5] = 0;
        packet[offset + 6] = 0;
        packet[offset + 7] = 0;
        offset += 8;

        // 3. Optional LIFETIME attribute (Type 0x000D, Length 4)
        if (requestedLifetimeSeconds.HasValue)
        {
            BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(offset, 2), AttrLifetime);
            BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(offset + 2, 2), 4);
            BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(offset + 4, 4), requestedLifetimeSeconds.Value);
        }

        return packet;
    }

    /// <summary>
    /// Parses an RFC 8656 TURN Allocate Success Response packet.
    /// Extracts the relayed public endpoint and allocated lifetime.
    /// </summary>
    public static TurnAllocation? ParseAllocateResponse(byte[] response, byte[]? expectedTransactionId = null)
    {
        ArgumentNullException.ThrowIfNull(response);

        if (response.Length < HeaderSize)
        {
            return null;
        }

        ushort messageType = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(0, 2));
        if (messageType != AllocateSuccessResponse)
        {
            return null;
        }

        ushort messageLength = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(2, 2));
        uint cookie = BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(4, 4));

        if (cookie != MagicCookie)
        {
            return null;
        }

        byte[] txId = new byte[TransactionIdLength];
        Buffer.BlockCopy(response, 8, txId, 0, TransactionIdLength);

        if (expectedTransactionId != null)
        {
            if (expectedTransactionId.Length != TransactionIdLength)
            {
                throw new ArgumentException($"Expected transaction ID must be {TransactionIdLength} bytes.", nameof(expectedTransactionId));
            }

            if (!CryptographicOperations.FixedTimeEquals(txId, expectedTransactionId))
            {
                return null;
            }
        }

        IPEndPoint? relayedEndPoint = null;
        uint lifetime = 600; // Default 10 minutes per RFC 8656

        int offset = HeaderSize;
        int end = Math.Min(response.Length, HeaderSize + messageLength);

        while (offset + 4 <= end)
        {
            ushort attrType = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset, 2));
            ushort attrLength = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(offset + 2, 2));
            offset += 4;

            if (offset + attrLength > end)
            {
                break;
            }

            if (attrType == AttrXorRelayedAddress)
            {
                relayedEndPoint = ParseXorAddress(response.AsSpan(offset, attrLength), txId);
            }
            else if (attrType == AttrLifetime && attrLength >= 4)
            {
                lifetime = BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(offset, 4));
            }

            // Attributes are padded to 4-byte boundary
            int padding = (4 - (attrLength % 4)) % 4;
            offset += attrLength + padding;
        }

        if (relayedEndPoint == null)
        {
            return null;
        }

        return new TurnAllocation(relayedEndPoint, lifetime, txId);
    }

    /// <summary>
    /// Builds an RFC 8656 CreatePermission Request packet authorizing a peer address.
    /// </summary>
    public static byte[] BuildCreatePermissionRequest(IPEndPoint peerEndPoint, byte[]? transactionId = null)
    {
        ArgumentNullException.ThrowIfNull(peerEndPoint);

        byte[] txId = ValidateOrGenerateTransactionId(transactionId);
        byte[] xorPeerAttr = BuildXorAddressAttribute(AttrXorPeerAddress, peerEndPoint, txId);

        byte[] packet = new byte[HeaderSize + xorPeerAttr.Length];

        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(0, 2), CreatePermissionRequest);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2, 2), (ushort)xorPeerAttr.Length);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4, 4), MagicCookie);
        Buffer.BlockCopy(txId, 0, packet, 8, TransactionIdLength);

        Buffer.BlockCopy(xorPeerAttr, 0, packet, HeaderSize, xorPeerAttr.Length);
        return packet;
    }

    /// <summary>
    /// Parses an RFC 8656 CreatePermission Success Response.
    /// </summary>
    public static bool ParseCreatePermissionResponse(byte[] response, byte[]? expectedTransactionId = null)
    {
        ArgumentNullException.ThrowIfNull(response);

        if (response.Length < HeaderSize)
        {
            return false;
        }

        ushort messageType = BinaryPrimitives.ReadUInt16BigEndian(response.AsSpan(0, 2));
        if (messageType != CreatePermissionSuccessResponse)
        {
            return false;
        }

        uint cookie = BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(4, 4));
        if (cookie != MagicCookie)
        {
            return false;
        }

        if (expectedTransactionId != null)
        {
            byte[] txId = new byte[TransactionIdLength];
            Buffer.BlockCopy(response, 8, txId, 0, TransactionIdLength);
            if (!CryptographicOperations.FixedTimeEquals(txId, expectedTransactionId))
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>
    /// Builds an RFC 8656 Send Indication packet to send data to a peer through the relay.
    /// </summary>
    public static byte[] BuildSendIndication(IPEndPoint peerEndPoint, ReadOnlySpan<byte> data, byte[]? transactionId = null)
    {
        ArgumentNullException.ThrowIfNull(peerEndPoint);

        byte[] txId = ValidateOrGenerateTransactionId(transactionId);
        byte[] xorPeerAttr = BuildXorAddressAttribute(AttrXorPeerAddress, peerEndPoint, txId);

        int dataPadding = (4 - (data.Length % 4)) % 4;
        int dataAttrTotalLength = 4 + data.Length + dataPadding;
        int totalAttrLength = xorPeerAttr.Length + dataAttrTotalLength;

        byte[] packet = new byte[HeaderSize + totalAttrLength];

        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(0, 2), SendIndication);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2, 2), (ushort)totalAttrLength);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4, 4), MagicCookie);
        Buffer.BlockCopy(txId, 0, packet, 8, TransactionIdLength);

        int offset = HeaderSize;
        Buffer.BlockCopy(xorPeerAttr, 0, packet, offset, xorPeerAttr.Length);
        offset += xorPeerAttr.Length;

        // Write DATA attribute
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(offset, 2), AttrData);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(offset + 2, 2), (ushort)data.Length);
        data.CopyTo(packet.AsSpan(offset + 4));

        return packet;
    }

    /// <summary>
    /// Parses an RFC 8656 Data Indication packet received from a peer through the relay.
    /// </summary>
    public static TurnDataPacket? ParseDataIndication(byte[] indication)
    {
        ArgumentNullException.ThrowIfNull(indication);

        if (indication.Length < HeaderSize)
        {
            return null;
        }

        ushort messageType = BinaryPrimitives.ReadUInt16BigEndian(indication.AsSpan(0, 2));
        if (messageType != DataIndication)
        {
            return null;
        }

        ushort messageLength = BinaryPrimitives.ReadUInt16BigEndian(indication.AsSpan(2, 2));
        uint cookie = BinaryPrimitives.ReadUInt32BigEndian(indication.AsSpan(4, 4));

        if (cookie != MagicCookie)
        {
            return null;
        }

        byte[] txId = new byte[TransactionIdLength];
        Buffer.BlockCopy(indication, 8, txId, 0, TransactionIdLength);

        IPEndPoint? peerEndPoint = null;
        byte[]? payload = null;

        int offset = HeaderSize;
        int end = Math.Min(indication.Length, HeaderSize + messageLength);

        while (offset + 4 <= end)
        {
            ushort attrType = BinaryPrimitives.ReadUInt16BigEndian(indication.AsSpan(offset, 2));
            ushort attrLength = BinaryPrimitives.ReadUInt16BigEndian(indication.AsSpan(offset + 2, 2));
            offset += 4;

            if (offset + attrLength > end)
            {
                break;
            }

            if (attrType == AttrXorPeerAddress)
            {
                peerEndPoint = ParseXorAddress(indication.AsSpan(offset, attrLength), txId);
            }
            else if (attrType == AttrData)
            {
                payload = new byte[attrLength];
                Buffer.BlockCopy(indication, offset, payload, 0, attrLength);
            }

            int padding = (4 - (attrLength % 4)) % 4;
            offset += attrLength + padding;
        }

        if (peerEndPoint == null || payload == null)
        {
            return null;
        }

        return new TurnDataPacket(peerEndPoint, payload);
    }

    /// <summary>
    /// Builds an RFC 8656 ChannelData message (Section 12).
    /// Used for low-overhead packet framing once a channel is bound (0x4000 through 0x7FFE).
    /// </summary>
    public static byte[] BuildChannelData(ushort channelNumber, ReadOnlySpan<byte> data)
    {
        if (channelNumber < 0x4000 || channelNumber > 0x7FFE)
        {
            throw new ArgumentOutOfRangeException(nameof(channelNumber), "Channel number must be in range 0x4000 through 0x7FFE.");
        }

        int padding = (4 - (data.Length % 4)) % 4;
        byte[] packet = new byte[4 + data.Length + padding];

        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(0, 2), channelNumber);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2, 2), (ushort)data.Length);
        data.CopyTo(packet.AsSpan(4));

        return packet;
    }

    /// <summary>
    /// Parses an RFC 8656 ChannelData message.
    /// </summary>
    public static TurnChannelDataPacket? ParseChannelData(ReadOnlySpan<byte> packet)
    {
        if (packet.Length < 4)
        {
            return null;
        }

        ushort channelNumber = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(0, 2));
        if (channelNumber < 0x4000 || channelNumber > 0x7FFE)
        {
            return null;
        }

        ushort length = BinaryPrimitives.ReadUInt16BigEndian(packet.Slice(2, 2));
        if (packet.Length < 4 + length)
        {
            return null;
        }

        byte[] data = new byte[length];
        packet.Slice(4, length).CopyTo(data);

        return new TurnChannelDataPacket(channelNumber, data);
    }

    /// <summary>
    /// Factory for creating an RFC 8445 Relayed IceCandidate from an active TURN allocation.
    /// </summary>
    public static IceCandidate CreateRelayedCandidate(
        IPEndPoint relayedEndPoint,
        IPEndPoint baseHostEndPoint,
        string transport = "udp")
    {
        ArgumentNullException.ThrowIfNull(relayedEndPoint);
        ArgumentNullException.ThrowIfNull(baseHostEndPoint);

        string foundation = IceCandidate.ComputeFoundation("relayed", relayedEndPoint.Address.ToString(), transport);
        return IceCandidate.CreateRelayed(foundation, relayedEndPoint, baseHostEndPoint);
    }

    private static byte[] ValidateOrGenerateTransactionId(byte[]? transactionId)
    {
        if (transactionId != null)
        {
            if (transactionId.Length != TransactionIdLength)
            {
                throw new ArgumentException($"Transaction ID must be {TransactionIdLength} bytes.", nameof(transactionId));
            }
            return transactionId;
        }

        byte[] txId = new byte[TransactionIdLength];
        RandomNumberGenerator.Fill(txId);
        return txId;
    }

    private static byte[] BuildXorAddressAttribute(ushort attrType, IPEndPoint endPoint, byte[] transactionId)
    {
        bool isIpv4 = endPoint.AddressFamily == AddressFamily.InterNetwork;
        int attrLength = isIpv4 ? 8 : 20;
        int totalLength = 4 + attrLength;

        byte[] attr = new byte[totalLength];
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(0, 2), attrType);
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(2, 2), (ushort)attrLength);

        attr[4] = 0; // Reserved
        attr[5] = isIpv4 ? (byte)0x01 : (byte)0x02; // Family

        // XOR Port with most significant 16 bits of Magic Cookie (0x2112)
        ushort xorPort = (ushort)(endPoint.Port ^ (MagicCookie >> 16));
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(6, 2), xorPort);

        byte[] addrBytes = endPoint.Address.GetAddressBytes();
        if (isIpv4)
        {
            uint xorIp = BinaryPrimitives.ReadUInt32BigEndian(addrBytes) ^ MagicCookie;
            BinaryPrimitives.WriteUInt32BigEndian(attr.AsSpan(8, 4), xorIp);
        }
        else
        {
            byte[] xorKey = new byte[16];
            BinaryPrimitives.WriteUInt32BigEndian(xorKey.AsSpan(0, 4), MagicCookie);
            Buffer.BlockCopy(transactionId, 0, xorKey, 4, TransactionIdLength);

            for (int i = 0; i < 16; i++)
            {
                attr[8 + i] = (byte)(addrBytes[i] ^ xorKey[i]);
            }
        }

        return attr;
    }

    private static IPEndPoint? ParseXorAddress(ReadOnlySpan<byte> attrValue, byte[] transactionId)
    {
        if (attrValue.Length < 8)
        {
            return null;
        }

        byte family = attrValue[1];
        ushort xorPort = BinaryPrimitives.ReadUInt16BigEndian(attrValue.Slice(2, 2));
        int port = xorPort ^ (int)(MagicCookie >> 16);

        if (family == 0x01 && attrValue.Length >= 8) // IPv4
        {
            uint xorIp = BinaryPrimitives.ReadUInt32BigEndian(attrValue.Slice(4, 4));
            uint ip = xorIp ^ MagicCookie;
            byte[] ipBytes = new byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(ipBytes, ip);
            return new IPEndPoint(new IPAddress(ipBytes), port);
        }
        else if (family == 0x02 && attrValue.Length >= 20) // IPv6
        {
            byte[] xorKey = new byte[16];
            BinaryPrimitives.WriteUInt32BigEndian(xorKey.AsSpan(0, 4), MagicCookie);
            Buffer.BlockCopy(transactionId, 0, xorKey, 4, TransactionIdLength);

            byte[] ipBytes = new byte[16];
            for (int i = 0; i < 16; i++)
            {
                ipBytes[i] = (byte)(attrValue[4 + i] ^ xorKey[i]);
            }
            return new IPEndPoint(new IPAddress(ipBytes), port);
        }

        return null;
    }
}
