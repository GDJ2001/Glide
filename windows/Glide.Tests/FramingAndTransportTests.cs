using System.Text.Json.Nodes;
using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using Xunit;
using ProtocolViolationException = Glide.Core.Models.ProtocolViolationException;

namespace Glide.Tests;

public class FramingAndTransportTests
{
    [Fact]
    public async Task LengthPrefixedFrameChannel_RoundtripEnvelope_Succeeds()
    {
        using var stream = new MemoryStream();
        var channel = new LengthPrefixedFrameChannel(stream);

        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var messageId = new MessageId("22222222-2222-4222-8222-222222222222");
        var body = new JsonObject
        {
            ["nonce"] = "33333333-3333-4333-8333-333333333333"
        };
        var envelope = new Envelope(1, MessageTypes.Ping, sessionId, messageId, 1, body);

        await channel.WriteEnvelopeAsync(envelope);

        stream.Position = 0;
        var received = await channel.ReadEnvelopeAsync();

        Assert.Equal(envelope.V, received.V);
        Assert.Equal(envelope.Type, received.Type);
        Assert.Equal(envelope.SessionId, received.SessionId);
        Assert.Equal(envelope.MessageId, received.MessageId);
        Assert.Equal(envelope.Seq, received.Seq);
    }

    [Fact]
    public async Task LengthPrefixedFrameChannel_Exceeds16KiB_ThrowsProtocolViolationException()
    {
        using var stream = new MemoryStream();
        // Write a 4-byte header indicating 16385 bytes
        byte[] header = [0x00, 0x00, 0x40, 0x01]; // 16385 in big-endian
        await stream.WriteAsync(header);
        stream.Position = 0;

        var channel = new LengthPrefixedFrameChannel(stream);
        await Assert.ThrowsAsync<ProtocolViolationException>(async () =>
        {
            await channel.ReadEnvelopeAsync();
        });
    }

    [Fact]
    public async Task TlsSocket_MutualAuthLoopbackAndSasDerivation_Succeeds()
    {
        using var listener = new TlsSocketListener(port: 0);
        int port = listener.BoundPort;
        Assert.True(port > 0);

        var client = new TlsSocketClient();

        var serverTask = Task.Run(async () => await listener.AcceptConnectionAsync());
        var clientTask = Task.Run(async () => await client.ConnectAsync("127.0.0.1", port));

        await Task.WhenAll(serverTask, clientTask);

        await using var serverConn = await serverTask;
        await using var clientConn = await clientTask;

        Assert.NotNull(serverConn.RemotePublicKeyBytes);
        Assert.NotNull(clientConn.RemotePublicKeyBytes);

        // Derive SAS on both sides
        byte[] sessionNonce = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];

        int serverSas = SasCalculator.CalculateSas(clientConn.LocalPublicKeyBytes, serverConn.LocalPublicKeyBytes, sessionNonce);
        int clientSas = SasCalculator.CalculateSas(clientConn.LocalPublicKeyBytes, clientConn.RemotePublicKeyBytes, sessionNonce);

        Assert.InRange(serverSas, 100_000, 999_999);
        Assert.Equal(serverSas, clientSas);

        // Exchange framed HELLO / HELLO_ACK message
        var serverChannel = new LengthPrefixedFrameChannel(serverConn.SslStream);
        var clientChannel = new LengthPrefixedFrameChannel(clientConn.SslStream);

        var sessionId = new SessionId("11111111-1111-4111-8111-111111111111");
        var messageId = new MessageId("22222222-2222-4222-8222-222222222222");

        var helloBody = new JsonObject
        {
            ["supportedMajors"] = new JsonArray(1),
            ["requiredCapabilities"] = new JsonArray(),
            ["role"] = "sender",
            ["ephemeralPeerId"] = "33333333-3333-4333-8333-333333333333",
            ["displayName"] = "Windows-Sender"
        };
        var helloEnvelope = new Envelope(1, MessageTypes.Hello, sessionId, messageId, 1, helloBody);

        await clientChannel.WriteEnvelopeAsync(helloEnvelope);
        var receivedHello = await serverChannel.ReadEnvelopeAsync();

        Assert.Equal(MessageTypes.Hello, receivedHello.Type);
        Assert.Equal("Windows-Sender", receivedHello.Body["displayName"]?.ToString());
    }
}
