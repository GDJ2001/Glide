using System.Buffers.Binary;
using System.Net;
using Glide.Transport.Remote;
using Xunit;

namespace Glide.Tests;

public class StunClientTests
{
    [Fact]
    public void BuildBindingRequest_GeneratesValid20ByteHeader()
    {
        byte[] explicitTxId = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        byte[] packet = StunClient.BuildBindingRequest(explicitTxId);

        Assert.Equal(20, packet.Length);

        // Check Type: 0x0001 (Binding Request)
        ushort type = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(0, 2));
        Assert.Equal(StunClient.BindingRequestType, type);

        // Check Length: 0x0000
        ushort len = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(2, 2));
        Assert.Equal(0, len);

        // Check Magic Cookie: 0x2112A442
        uint cookie = BinaryPrimitives.ReadUInt32BigEndian(packet.AsSpan(4, 4));
        Assert.Equal(StunClient.MagicCookie, cookie);

        // Check Transaction ID
        Assert.Equal(explicitTxId, packet[8..20]);
    }

    [Fact]
    public void ParseBindingResponse_XorMappedAddressIPv4_DecodesAccurately()
    {
        byte[] txId = [0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B];
        int originalPort = 54321;
        var originalIp = IPAddress.Parse("203.0.113.195");
        byte[] ipBytes = originalIp.GetAddressBytes();

        // Attribute XOR-MAPPED-ADDRESS: Type (0x0020), Length (8)
        // Value: Reserved (0x00), Family (0x01 IPv4), X-Port (port ^ 0x2112), X-Address (IP ^ MagicCookie)
        ushort xorPort = (ushort)(originalPort ^ (StunClient.MagicCookie >> 16));
        byte[] cookieBytes = [0x21, 0x12, 0xA4, 0x42];
        byte[] xorIp = new byte[4];
        for (int i = 0; i < 4; i++)
        {
            xorIp[i] = (byte)(ipBytes[i] ^ cookieBytes[i]);
        }

        byte[] attr = new byte[12];
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(0, 2), StunClient.AttrXorMappedAddress);
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(2, 2), 8);
        attr[4] = 0x00; // Reserved
        attr[5] = 0x01; // IPv4
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(6, 2), xorPort);
        Buffer.BlockCopy(xorIp, 0, attr, 8, 4);

        // Header: 20 bytes
        byte[] response = new byte[20 + attr.Length];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), StunClient.BindingSuccessResponseType);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), (ushort)attr.Length);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), StunClient.MagicCookie);
        Buffer.BlockCopy(txId, 0, response, 8, 12);
        Buffer.BlockCopy(attr, 0, response, 20, attr.Length);

        var endpoint = StunClient.ParseBindingResponse(response, txId);

        Assert.NotNull(endpoint);
        Assert.Equal(originalIp, endpoint.Address);
        Assert.Equal(originalPort, endpoint.Port);
    }

    [Fact]
    public void ParseBindingResponse_TransactionIdMismatch_ReturnsNull()
    {
        byte[] txId1 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        byte[] txId2 = [9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9];

        byte[] response = new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), StunClient.BindingSuccessResponseType);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), 0);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), StunClient.MagicCookie);
        Buffer.BlockCopy(txId1, 0, response, 8, 12);

        var endpoint = StunClient.ParseBindingResponse(response, txId2);
        Assert.Null(endpoint);
    }

    [Fact]
    public void ParseBindingResponse_InvalidMagicCookie_ReturnsNull()
    {
        byte[] txId = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        byte[] response = new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), StunClient.BindingSuccessResponseType);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), 0);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), 0x12345678); // Corrupted cookie
        Buffer.BlockCopy(txId, 0, response, 8, 12);

        var endpoint = StunClient.ParseBindingResponse(response, txId);
        Assert.Null(endpoint);
    }

    [Fact]
    public void ParseBindingResponse_MappedAddressFallback_DecodesAccurately()
    {
        byte[] txId = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        int originalPort = 42424;
        var originalIp = IPAddress.Parse("198.51.100.45");

        byte[] attr = new byte[12];
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(0, 2), StunClient.AttrMappedAddress);
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(2, 2), 8);
        attr[4] = 0x00; // Reserved
        attr[5] = 0x01; // IPv4
        BinaryPrimitives.WriteUInt16BigEndian(attr.AsSpan(6, 2), (ushort)originalPort);
        Buffer.BlockCopy(originalIp.GetAddressBytes(), 0, attr, 8, 4);

        byte[] response = new byte[20 + attr.Length];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), StunClient.BindingSuccessResponseType);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), (ushort)attr.Length);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), StunClient.MagicCookie);
        Buffer.BlockCopy(txId, 0, response, 8, 12);
        Buffer.BlockCopy(attr, 0, response, 20, attr.Length);

        var endpoint = StunClient.ParseBindingResponse(response, txId);

        Assert.NotNull(endpoint);
        Assert.Equal(originalIp, endpoint.Address);
        Assert.Equal(originalPort, endpoint.Port);
    }
}
