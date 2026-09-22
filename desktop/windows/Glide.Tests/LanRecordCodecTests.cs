using System.Text;
using Glide.Core.Models;
using Glide.Core.Transport.Lan;
using Xunit;

namespace Glide.Tests;

public class LanRecordCodecTests
{
    private const string TestBulkRef = "00000000-0000-4000-8000-000000000001";

    [Fact]
    public void LanUuidCodec_RoundTrip_MatchesRfc4122NetworkBytes()
    {
        var guid = Guid.Parse(TestBulkRef);
        byte[] netBytes = LanUuidCodec.ToNetworkBytes(guid);

        Assert.Equal(16, netBytes.Length);
        // In big-endian network bytes, the version 4 byte is at index 6 (0x40) and variant byte at index 8 (0x80)
        Assert.Equal(0x40, netBytes[6]);
        Assert.Equal(0x80, netBytes[8]);

        var parsed = LanUuidCodec.FromNetworkBytes(netBytes);
        Assert.Equal(guid, parsed);

        var bulkRef = new BulkRef(TestBulkRef);
        byte[] bulkBytes = LanUuidCodec.BulkRefToNetworkBytes(bulkRef);
        var decodedBulkRef = LanUuidCodec.NetworkBytesToBulkRef(bulkBytes);
        Assert.Equal(bulkRef, decodedBulkRef);
    }

    [Fact]
    public void LanRecordCodec_DataRecord_EncodeDecodeRoundTrip()
    {
        byte[] chunk = Encoding.UTF8.GetBytes("Hello World Stream Payload");
        var dataRecord = new LanRecord.Data(new BulkRef(TestBulkRef), chunk);

        byte[] frame = LanRecordCodec.Encode(dataRecord);
        Assert.Equal(LanRecordCodec.HeaderSize + 16 + chunk.Length, frame.Length);

        var decoded = LanRecordCodec.Decode(frame);
        Assert.Equal(LanRecordKind.Data, decoded.Kind);
        var decodedData = Assert.IsType<LanRecord.Data>(decoded);

        Assert.Equal(dataRecord.BulkRef, decodedData.BulkRef);
        Assert.Equal(chunk, decodedData.Chunk);
    }

    [Fact]
    public void LanRecordCodec_StreamEnd_EncodeDecodeRoundTrip()
    {
        var streamEnd = new LanRecord.StreamEnd(new BulkRef(TestBulkRef), 1024L);

        byte[] frame = LanRecordCodec.Encode(streamEnd);
        Assert.Equal(LanRecordCodec.HeaderSize + 24, frame.Length);

        var decoded = LanRecordCodec.Decode(frame);
        Assert.Equal(LanRecordKind.StreamEnd, decoded.Kind);
        var decodedEnd = Assert.IsType<LanRecord.StreamEnd>(decoded);

        Assert.Equal(streamEnd.BulkRef, decodedEnd.BulkRef);
        Assert.Equal(1024L, decodedEnd.ActualBytes);
    }

    [Fact]
    public async Task LanRecordCodec_StreamReadWrite_PreservesRecord()
    {
        byte[] chunk = new byte[100];
        Random.Shared.NextBytes(chunk);
        var dataRecord = new LanRecord.Data(new BulkRef(TestBulkRef), chunk);

        using var ms = new MemoryStream();
        await LanRecordCodec.WriteRecordAsync(dataRecord, ms);

        ms.Position = 0;
        var readRecord = await LanRecordCodec.ReadRecordAsync(ms);

        Assert.NotNull(readRecord);
        Assert.Equal(LanRecordKind.Data, readRecord.Kind);
        var readData = Assert.IsType<LanRecord.Data>(readRecord);
        Assert.Equal(chunk, readData.Chunk);
    }

    [Fact]
    public void LanRecordCodec_RejectsOversizedPayload()
    {
        byte[] invalidFrame = new byte[LanRecordCodec.HeaderSize];
        invalidFrame[0] = (byte)LanRecordKind.Control;
        // Set payload length to 65537 (> 64 KiB max)
        invalidFrame[1] = 0x00;
        invalidFrame[2] = 0x01;
        invalidFrame[3] = 0x00;
        invalidFrame[4] = 0x01;

        Assert.Throws<ArgumentException>(() => LanRecordCodec.Decode(invalidFrame));
    }
}
