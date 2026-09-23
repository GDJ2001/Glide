using System.Buffers.Binary;
using System.Net;
using Glide.Core.Remote;
using Glide.Transport.Remote;
using Xunit;

namespace Glide.Tests;

public class TurnClientTests
{
    [Fact]
    public void BuildAllocateRequest_ConstructsValidRFC8656HeaderAndTransport()
    {
        byte[] txId = new byte[12] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        byte[] packet = TurnClient.BuildAllocateRequest(transportProtocol: 17, transactionId: txId);

        // Header: 20 bytes + Requested-Transport: 8 bytes = 28 bytes
        Assert.Equal(28, packet.Length);

        // Message Type: Allocate Request (0x0003)
        ushort msgType = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(0, 2));
        Assert.Equal(TurnClient.AllocateRequest, msgType);

        // Message Length: 8 bytes (attributes)
        ushort msgLen = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(2, 2));
        Assert.Equal(8, msgLen);

        // Magic Cookie: 0x2112A442
        uint cookie = BinaryPrimitives.ReadUInt32BigEndian(packet.AsSpan(4, 4));
        Assert.Equal(TurnClient.MagicCookie, cookie);

        // Transaction ID
        Assert.Equal(txId, packet.AsSpan(8, 12).ToArray());

        // Attr: REQUESTED-TRANSPORT (0x0019)
        ushort attrType = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(20, 2));
        Assert.Equal(TurnClient.AttrRequestedTransport, attrType);
        ushort attrLen = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(22, 2));
        Assert.Equal(4, attrLen);
        Assert.Equal(17, packet[24]); // UDP
    }

    [Fact]
    public void BuildAllocateRequest_WithLifetime_IncludesLifetimeAttribute()
    {
        byte[] packet = TurnClient.BuildAllocateRequest(transportProtocol: 17, requestedLifetimeSeconds: 300);

        // 20 header + 8 transport + 8 lifetime = 36 bytes
        Assert.Equal(36, packet.Length);
        ushort msgLen = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(2, 2));
        Assert.Equal(16, msgLen);

        ushort lifetimeType = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(28, 2));
        Assert.Equal(TurnClient.AttrLifetime, lifetimeType);
        uint lifetimeVal = BinaryPrimitives.ReadUInt32BigEndian(packet.AsSpan(32, 4));
        Assert.Equal(300u, lifetimeVal);
    }

    [Fact]
    public void ParseAllocateResponse_ValidIpv4Response_ExtractsRelayedEndpointAndLifetime()
    {
        byte[] txId = new byte[12] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        var expectedRelayedEp = new IPEndPoint(IPAddress.Parse("198.51.100.25"), 49152);

        // Build synthetic 0x0103 Allocate Success Response
        // Attributes: XOR-RELAYED-ADDRESS (12 bytes) + LIFETIME (8 bytes) = 20 bytes
        byte[] response = new byte[20 + 12 + 8];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), TurnClient.AllocateSuccessResponse);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), 20);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), TurnClient.MagicCookie);
        Buffer.BlockCopy(txId, 0, response, 8, 12);

        // XOR-RELAYED-ADDRESS attr
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(20, 2), TurnClient.AttrXorRelayedAddress);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(22, 2), 8);
        response[24] = 0; // reserved
        response[25] = 1; // IPv4
        ushort xorPort = (ushort)(49152 ^ (TurnClient.MagicCookie >> 16));
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(26, 2), xorPort);
        uint xorIp = BinaryPrimitives.ReadUInt32BigEndian(expectedRelayedEp.Address.GetAddressBytes()) ^ TurnClient.MagicCookie;
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(28, 4), xorIp);

        // LIFETIME attr
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(32, 2), TurnClient.AttrLifetime);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(34, 2), 4);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(36, 4), 900);

        TurnAllocation? allocation = TurnClient.ParseAllocateResponse(response, txId);
        Assert.NotNull(allocation);
        Assert.Equal(expectedRelayedEp, allocation.RelayedEndPoint);
        Assert.Equal(900u, allocation.LifetimeSeconds);
        Assert.Equal(txId, allocation.TransactionId);
    }

    [Fact]
    public void ParseAllocateResponse_TransactionMismatch_ReturnsNull()
    {
        byte[] txId = new byte[12] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        byte[] wrongTxId = new byte[12] { 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9 };

        byte[] response = new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), TurnClient.AllocateSuccessResponse);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), 0);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), TurnClient.MagicCookie);
        Buffer.BlockCopy(txId, 0, response, 8, 12);

        TurnAllocation? allocation = TurnClient.ParseAllocateResponse(response, wrongTxId);
        Assert.Null(allocation);
    }

    [Fact]
    public void BuildCreatePermissionRequest_AndParseResponse_RoundTripsSuccessfully()
    {
        byte[] txId = new byte[12] { 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120 };
        var peerEp = new IPEndPoint(IPAddress.Parse("203.0.113.50"), 8888);

        byte[] request = TurnClient.BuildCreatePermissionRequest(peerEp, txId);
        Assert.Equal(32, request.Length); // 20 header + 12 xor-peer attr
        Assert.Equal(TurnClient.CreatePermissionRequest, BinaryPrimitives.ReadUInt16BigEndian(request.AsSpan(0, 2)));

        // Create success response
        byte[] response = new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(0, 2), TurnClient.CreatePermissionSuccessResponse);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(2, 2), 0);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(4, 4), TurnClient.MagicCookie);
        Buffer.BlockCopy(txId, 0, response, 8, 12);

        bool success = TurnClient.ParseCreatePermissionResponse(response, txId);
        Assert.True(success);
    }

    [Fact]
    public void BuildSendIndication_AndParseDataIndication_RoundTripsData()
    {
        byte[] txId = new byte[12] { 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24 };
        var peerEp = new IPEndPoint(IPAddress.Parse("192.0.2.77"), 54321);
        byte[] payload = "Hello Glide Blind Relay"u8.ToArray();

        byte[] indication = TurnClient.BuildSendIndication(peerEp, payload, txId);
        Assert.Equal(TurnClient.SendIndication, BinaryPrimitives.ReadUInt16BigEndian(indication.AsSpan(0, 2)));

        // Convert the indication to a DataIndication by modifying message type to 0x0017
        BinaryPrimitives.WriteUInt16BigEndian(indication.AsSpan(0, 2), TurnClient.DataIndication);

        TurnDataPacket? received = TurnClient.ParseDataIndication(indication);
        Assert.NotNull(received);
        Assert.Equal(peerEp, received.PeerEndPoint);
        Assert.Equal(payload, received.Data);
    }

    [Fact]
    public void ChannelData_FramingAndParsing_RoundTripsPayload()
    {
        ushort channel = 0x4005;
        byte[] data = new byte[] { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE }; // 5 bytes (padded to 8 bytes)

        byte[] packet = TurnClient.BuildChannelData(channel, data);
        Assert.Equal(12, packet.Length); // 4 header + 5 data + 3 padding = 12 bytes

        TurnChannelDataPacket? parsed = TurnClient.ParseChannelData(packet);
        Assert.NotNull(parsed);
        Assert.Equal(channel, parsed.ChannelNumber);
        Assert.Equal(data, parsed.Data);
    }

    [Fact]
    public void ChannelData_InvalidChannelNumber_ThrowsArgumentOutOfRangeException()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            TurnClient.BuildChannelData(0x3FFF, new byte[10]));
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            TurnClient.BuildChannelData(0x7FFF, new byte[10]));
    }

    [Fact]
    public void CreateRelayedCandidate_GeneratesValidRFC8445RelayedCandidate()
    {
        var relayedEp = new IPEndPoint(IPAddress.Parse("203.0.113.99"), 44300);
        var baseEp = new IPEndPoint(IPAddress.Parse("192.168.1.50"), 50000);

        IceCandidate candidate = TurnClient.CreateRelayedCandidate(relayedEp, baseEp, "udp");

        Assert.Equal(IceCandidateType.Relayed, candidate.Type);
        Assert.Equal(relayedEp, candidate.EndPoint);
        Assert.Equal(baseEp, candidate.RelatedEndPoint);
        Assert.Equal("udp", candidate.Transport);
        Assert.Equal(1, candidate.ComponentId);

        // RFC 8445: Relayed candidate priority has typePreference = 0
        // Formula: (0 << 24) | (localPref << 8) | (256 - componentId)
        Assert.True(candidate.Priority < 65536);
        Assert.Contains("typ relay", candidate.ToSdpString());
        Assert.Contains($"raddr {baseEp.Address} rport {baseEp.Port}", candidate.ToSdpString());
    }
}
