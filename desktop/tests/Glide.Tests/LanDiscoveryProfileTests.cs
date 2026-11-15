using System.Net;
using System.Text;
using Glide.Core.Pairing;
using Glide.Core.Transport.Lan;
using Glide.Transport.Discovery;
using Glide.Transport.Framing;

namespace Glide.Tests;

public sealed class LanDiscoveryProfileTests
{
    private const string Presence =
        "0123456789abcdef0123456789abcdef";

    // Android NsdManager response captured from a phone advertising a live trusted
    // listener. Its additional records use compressed DNS names and include an
    // AAAA record before the TXT and SRV records.
    private const string AndroidTrustedAnnouncement =
        "000084000000000100000006065F676C696465045F746370056C6F63616C00000C00010000119400110E476C6964652D3439313534343333C00C" +
        "10416E64726F69645F4D54305643434D46C018001C8001000000780010FE80000000000000700C2EFFFECC0AC2C03A002F800100000078" +
        "0008C03A000440000008C0290010800100001194004E22703D3439313534343333646230393437393238386133373834353364323764666663" +
        "03713D3103763D310F646E3D476C696465204465766963650864743D70686F6E650474723D310474763D31C02900218001000000780008" +
        "000000008693C03AC03A00018001000000780004C0A8007DC029002F8001000000780009C02900050000800040";

    [Fact]
    public void AndroidCompressedAnnouncement_ExposesCurrentTrustedEndpoint()
    {
        using var service = new MdnsDiscoveryService();
        service.ProcessIncomingPacketForTesting(
            Convert.FromHexString(AndroidTrustedAnnouncement),
            new IPEndPoint(IPAddress.Parse("192.168.0.125"), 5353));

        DiscoveredPeer peer = Assert.Single(service.Snapshot.Peers);
        Assert.Equal("192.168.0.125", peer.Host);
        Assert.Equal(34451, peer.Port);
        Assert.True(peer.SupportsTrustedReconnect);
    }

    [Fact]
    public void CanonicalAvailabilityRecord_MatchesAndroidProfile()
    {
        var attributes = new Dictionary<string, string>
        {
            ["v"] = "1",
            ["tv"] = "1",
            ["q"] = "1",
            ["p"] = Presence,
            ["dn"] = "Desktop Peer",
            ["dt"] = LanDiscoveryConstants.DeviceTypeDesktop
        };

        Assert.True(
            MdnsDiscoveryService
                .IsCompatibleAvailabilityRecord(attributes));
    }

    [Fact]
    public void CanonicalAvailabilityRecord_SupportsPairReadyAndTrustedReconnect()
    {
        var attributes = new Dictionary<string, string>
        {
            ["v"] = "1",
            ["tv"] = "1",
            ["q"] = "1",
            ["p"] = Presence,
            ["dn"] = "Desktop Peer",
            ["dt"] = LanDiscoveryConstants.DeviceTypeDesktop,
            ["pair"] = "1",
            ["tr"] = "1"
        };

        Assert.True(MdnsDiscoveryService.IsCompatibleAvailabilityRecord(attributes));
    }

    [Theory]
    [InlineData("spki", "aaaa")]
    [InlineData("pin", "123456")]
    [InlineData("token", "secret")]
    [InlineData("trust", "1")]
    [InlineData("fp", "a1b2c3d4")]
    public void AvailabilityRecord_RejectsForbiddenKeys(string forbiddenKey, string forbiddenValue)
    {
        var attributes = new Dictionary<string, string>
        {
            ["v"] = "1",
            ["tv"] = "1",
            ["q"] = "1",
            ["p"] = Presence,
            ["dn"] = "Desktop Peer",
            ["dt"] = LanDiscoveryConstants.DeviceTypeDesktop,
            [forbiddenKey] = forbiddenValue
        };

        Assert.False(MdnsDiscoveryService.IsCompatibleAvailabilityRecord(attributes));
    }

    [Fact]
    public void LegacyFingerprintCapabilityRecord_IsRejected()
    {
        var legacy = new Dictionary<string, string>
        {
            ["v"] = "1",
            ["dn"] = "Desktop Peer",
            ["dt"] = LanDiscoveryConstants.DeviceTypeDesktop,
            ["fp"] = "a1b2c3d4",
            ["cap"] = "7",
            ["alpn"] = "glide-v1"
        };

        Assert.False(
            MdnsDiscoveryService
                .IsCompatibleAvailabilityRecord(legacy));
    }

    [Fact]
    public void Advertisement_DoesNotExposeAuthenticationMaterial_AndIncludesPairReadyWhenRequested()
    {
        byte[] packet =
            MdnsDiscoveryService.BuildResponsePacketForTesting(
                "Desktop Peer",
                42424,
                Presence,
                pairReady: true,
                trustedReconnectSupported: true);
        string printable =
            Encoding.UTF8.GetString(packet);

        Assert.Contains("v=1", printable);
        Assert.Contains("tv=1", printable);
        Assert.Contains("q=1", printable);
        Assert.Contains($"p={Presence}", printable);
        Assert.Contains("dn=Desktop Peer", printable);
        Assert.Contains("dt=desktop", printable);
        Assert.Contains("pair=1", printable);
        Assert.Contains("tr=1", printable);

        Assert.DoesNotContain("spki=", printable);
        Assert.DoesNotContain("pin=", printable);
        Assert.DoesNotContain("fp=", printable);
        Assert.DoesNotContain("cap=", printable);
        Assert.DoesNotContain("alpn=", printable);
        Assert.DoesNotContain("token", printable);
        Assert.True(packet.Length < 1_500);
    }

    [Fact]
    public void DiscoveryDiagnostics_ReportsNoEligibleInterfacesWhenEmpty()
    {
        using var service = new MdnsDiscoveryService(() => Array.Empty<IPAddress>());
        service.Start(enableBrowsing: true);

        Assert.Equal(LanDiscoveryDiagnosticStatus.NoEligibleInterfaces, service.Diagnostics.Status);
        Assert.Empty(service.Diagnostics.ActiveInterfaceAddresses);
    }

    [Fact]
    public void DeviceName_NormalizationStaysInsideCrossPlatformBounds()
    {
        string source =
            string.Concat(
                Enumerable.Repeat("😀", 100));
        string normalized =
            LanDiscoveryConstants.NormalizeDeviceName(source);

        Assert.InRange(
            normalized.EnumerateRunes().Count(),
            1,
            LanDiscoveryConstants.MaxDeviceNameCodePoints);
        Assert.InRange(
            Encoding.UTF8.GetByteCount(normalized),
            1,
            LanDiscoveryConstants.MaxDeviceNameUtf8Bytes);
    }

    [Fact]
    public async Task PreambleInspector_PreservesProfile1LanRecordFraming()
    {
        const string invId = "550e8400-e29b-41d4-a716-446655440000";
        const string sessId = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";
        const string token = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
        var bootstrap = new LanBootstrapMessage(
            invitationId: invId,
            token: token,
            invitationHash: new string('a', 64),
            sessionId: sessId,
            role: LanBootstrapMessage.RoleSender);
        byte[] payload = LanBootstrapCodec.EncodeBootstrap(bootstrap);
        var record = new LanRecord.Bootstrap(payload);

        using var wireStream = new MemoryStream();
        await LanRecordCodec.WriteRecordAsync(record, wireStream);
        wireStream.Position = 0;

        Stream activeStream = wireStream;
        var dispatch = await LanSessionPreambleInspector.InspectStreamAsync(
            activeStream,
            prefix => activeStream = new PrefixedStream(activeStream, prefix, leaveInnerOpen: true));

        Assert.Equal(LanSessionDispatchKind.Profile1LanRecord, dispatch);

        await using var channel = new LanRecordChannel(activeStream);
        LanBootstrapMessage decoded = await channel.ReadBootstrapAsync();
        Assert.Equal(invId, decoded.InvitationId);
        Assert.Equal(sessId, decoded.SessionId);
    }

    [Fact]
    public async Task PreambleInspector_DetectsAndConsumesProfile2Gld2Magic()
    {
        using var wireStream = new MemoryStream();
        await LanSessionPreambleInspector.WriteGld2PreambleAsync(wireStream);
        byte[] trailing = Encoding.UTF8.GetBytes("{\"type\":\"SESSION_OPEN\"}");
        await wireStream.WriteAsync(trailing);
        wireStream.Position = 0;

        Stream activeStream = wireStream;
        var dispatch = await LanSessionPreambleInspector.InspectStreamAsync(
            activeStream,
            prefix => activeStream = new PrefixedStream(activeStream, prefix, leaveInnerOpen: true));

        Assert.Equal(LanSessionDispatchKind.Profile2Gld2, dispatch);

        using var reader = new StreamReader(activeStream, Encoding.UTF8);
        string remaining = await reader.ReadToEndAsync();
        Assert.Equal("{\"type\":\"SESSION_OPEN\"}", remaining);
    }

    [Fact]
    public async Task PreambleInspector_RejectsUnknownMagic()
    {
        using var wireStream = new MemoryStream("NOPE1234"u8.ToArray());
        Stream activeStream = wireStream;
        var dispatch = await LanSessionPreambleInspector.InspectStreamAsync(
            activeStream,
            prefix => activeStream = new PrefixedStream(activeStream, prefix, leaveInnerOpen: true));

        Assert.Equal(LanSessionDispatchKind.Unknown, dispatch);
    }
}
