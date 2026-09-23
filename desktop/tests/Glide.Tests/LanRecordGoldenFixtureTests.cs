using System.Text;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Transport.Lan;
using Xunit;

namespace Glide.Tests;

public class LanRecordGoldenFixtureTests
{
    private static readonly string FixturesDir = LocateFixturesDir();

    private static string LocateFixturesDir()
    {
        var current = new DirectoryInfo(AppContext.BaseDirectory);
        while (current != null)
        {
            string candidate = Path.Combine(current.FullName, "android", "core", "src", "test", "resources", "fixtures", "lan");
            if (Directory.Exists(candidate))
            {
                return candidate;
            }
            current = current.Parent;
        }
        throw new DirectoryNotFoundException("Could not locate lan fixtures directory.");
    }

    private static byte[] LoadFixture(string fileName)
    {
        string path = Path.Combine(FixturesDir, fileName);
        Assert.True(File.Exists(path), $"Fixture file missing: {path}");
        return File.ReadAllBytes(path);
    }

    [Fact]
    public void TestGoldenBootstrapRecord()
    {
        byte[] bytes = LoadFixture("bootstrap.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.Bootstrap, record.Kind);
        var bootstrap = Assert.IsType<LanRecord.Bootstrap>(record);

        var msg = LanBootstrapCodec.DecodeBootstrap(bootstrap.JsonBytes);
        Assert.Equal(1, msg.V);
        Assert.Equal("00000000-0000-4000-8000-000000000001", msg.InvitationId);
        Assert.Equal("0123456789abcdefghijklmnopqrstuvwxyzABCDEFG", msg.Token);
        Assert.Equal("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", msg.InvitationHash);
        Assert.Equal("00000000-0000-4000-8000-000000000002", msg.SessionId);
        Assert.Equal("sender", msg.Role);

        // Re-encoding must match original exactly
        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }

    [Fact]
    public void TestGoldenBootstrapResultAcceptedRecord()
    {
        byte[] bytes = LoadFixture("bootstrap_result_accepted.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.BootstrapResult, record.Kind);
        var resultRecord = Assert.IsType<LanRecord.BootstrapResult>(record);

        var result = LanBootstrapCodec.DecodeResult(resultRecord.JsonBytes);
        Assert.Equal(1, result.V);
        Assert.Equal("accepted", result.Status);
        Assert.Equal("00000000-0000-4000-8000-000000000002", result.SessionId);
        Assert.Null(result.Code);
        Assert.True(result.IsAccepted);

        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }

    [Fact]
    public void TestGoldenBootstrapResultRejectedRecord()
    {
        byte[] bytes = LoadFixture("bootstrap_result_rejected.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.BootstrapResult, record.Kind);
        var resultRecord = Assert.IsType<LanRecord.BootstrapResult>(record);

        var result = LanBootstrapCodec.DecodeResult(resultRecord.JsonBytes);
        Assert.Equal(1, result.V);
        Assert.Equal("rejected", result.Status);
        Assert.Equal("AUTHENTICATION_FAILED", result.Code);
        Assert.Null(result.SessionId);
        Assert.False(result.IsAccepted);

        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }

    [Fact]
    public void TestGoldenControlHelloRecord()
    {
        byte[] bytes = LoadFixture("control_hello.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.Control, record.Kind);
        var control = Assert.IsType<LanRecord.Control>(record);

        string json = Encoding.UTF8.GetString(control.Payload);
        Assert.Contains("\"type\": \"HELLO\"", json);

        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }

    [Fact]
    public void TestGoldenDataChunkRecord()
    {
        byte[] bytes = LoadFixture("data_chunk.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.Data, record.Kind);
        var data = Assert.IsType<LanRecord.Data>(record);

        Assert.Equal("00000000-0000-4000-8000-000000000001", data.BulkRef.Value);
        Assert.Equal("Glide-LAN-Profile-1-Binary-Chunk", Encoding.UTF8.GetString(data.Chunk));

        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }

    [Fact]
    public void TestGoldenStreamEndEmptyRecord()
    {
        byte[] bytes = LoadFixture("stream_end_empty.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.StreamEnd, record.Kind);
        var streamEnd = Assert.IsType<LanRecord.StreamEnd>(record);

        Assert.Equal("00000000-0000-4000-8000-000000000001", streamEnd.BulkRef.Value);
        Assert.Equal(0L, streamEnd.ActualBytes);

        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }

    [Fact]
    public void TestGoldenStreamEndNonEmptyRecord()
    {
        byte[] bytes = LoadFixture("stream_end_nonempty.bin");
        var record = LanRecordCodec.Decode(bytes);

        Assert.Equal(LanRecordKind.StreamEnd, record.Kind);
        var streamEnd = Assert.IsType<LanRecord.StreamEnd>(record);

        Assert.Equal("00000000-0000-4000-8000-000000000001", streamEnd.BulkRef.Value);
        Assert.Equal(32L, streamEnd.ActualBytes);

        byte[] reEncoded = LanRecordCodec.Encode(record);
        Assert.Equal(bytes, reEncoded);
    }
}
