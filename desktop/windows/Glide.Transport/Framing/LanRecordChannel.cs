using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Transport.Lan;

namespace Glide.Transport.Framing;

/// <summary>
/// Bidirectional framed channel operating over a stream for LAN Profile 1 records (LAN-012, Section 6).
/// Strictly handles RecordKind framing: Bootstrap, BootstrapResult, Control envelopes, Data chunks, and StreamEnd.
/// Serializes outgoing writes via an internal semaphore to prevent interleaving of frame headers and payloads.
/// </summary>
public sealed class LanRecordChannel : IAsyncDisposable, IDisposable
{
    private readonly Stream _stream;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private bool _disposed;

    public Stream BaseStream => _stream;

    public LanRecordChannel(Stream stream)
    {
        _stream = stream ?? throw new ArgumentNullException(nameof(stream));
    }

    /// <summary>
    /// Writes a strongly-typed LanRecord with strict serialized framing.
    /// </summary>
    public async Task WriteRecordAsync(LanRecord record, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ArgumentNullException.ThrowIfNull(record);

        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await LanRecordCodec.WriteRecordAsync(record, _stream, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>
    /// Reads the next LanRecord from the stream. Returns null on clean EOF before header bytes.
    /// </summary>
    public async Task<LanRecord?> ReadRecordAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return await LanRecordCodec.ReadRecordAsync(_stream, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Encodes a Glide Protocol V1 Envelope and transmits it as a LanRecord.Control record.
    /// </summary>
    public async Task WriteEnvelopeAsync(Envelope envelope, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        byte[] payload = ProtocolCodec.Encode(envelope);
        var controlRecord = new LanRecord.Control(payload);
        await WriteRecordAsync(controlRecord, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Reads the next record and verifies that it is a LanRecord.Control record, then decodes it as an Envelope.
    /// </summary>
    public async Task<Envelope> ReadEnvelopeAsync(CancellationToken cancellationToken = default)
    {
        var record = await ReadRecordAsync(cancellationToken).ConfigureAwait(false);
        if (record == null)
        {
            throw new EndOfStreamException("Connection closed while expecting CONTROL record");
        }

        if (record is not LanRecord.Control control)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Expected CONTROL record (0x10), got {record.Kind} (0x{(byte)record.Kind:X2})");
        }

        return ProtocolCodec.Decode(control.Payload);
    }

    /// <summary>
    /// Encodes and transmits a LAN pairing bootstrap message as a LanRecord.Bootstrap record.
    /// </summary>
    public async Task WriteBootstrapAsync(LanBootstrapMessage message, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(message);
        byte[] payload = LanBootstrapCodec.EncodeBootstrap(message);
        var bootstrapRecord = new LanRecord.Bootstrap(payload);
        await WriteRecordAsync(bootstrapRecord, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Reads the next record, verifies it is a LanRecord.Bootstrap record, and decodes it.
    /// </summary>
    public async Task<LanBootstrapMessage> ReadBootstrapAsync(CancellationToken cancellationToken = default)
    {
        var record = await ReadRecordAsync(cancellationToken).ConfigureAwait(false);
        if (record == null)
        {
            throw new EndOfStreamException("Connection closed while expecting BOOTSTRAP record");
        }

        if (record is not LanRecord.Bootstrap bootstrap)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Expected BOOTSTRAP record (0x01), got {record.Kind} (0x{(byte)record.Kind:X2})");
        }

        return LanBootstrapCodec.DecodeBootstrap(bootstrap.JsonBytes);
    }

    /// <summary>
    /// Encodes and transmits a LAN pairing bootstrap result as a LanRecord.BootstrapResult record.
    /// </summary>
    public async Task WriteBootstrapResultAsync(LanBootstrapResult result, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(result);
        byte[] payload = LanBootstrapCodec.EncodeResult(result);
        var resultRecord = new LanRecord.BootstrapResult(payload);
        await WriteRecordAsync(resultRecord, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Reads the next record, verifies it is a LanRecord.BootstrapResult record, and decodes it.
    /// </summary>
    public async Task<LanBootstrapResult> ReadBootstrapResultAsync(CancellationToken cancellationToken = default)
    {
        var record = await ReadRecordAsync(cancellationToken).ConfigureAwait(false);
        if (record == null)
        {
            throw new EndOfStreamException("Connection closed while expecting BOOTSTRAP_RESULT record");
        }

        if (record is not LanRecord.BootstrapResult result)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Expected BOOTSTRAP_RESULT record (0x02), got {record.Kind} (0x{(byte)record.Kind:X2})");
        }

        return LanBootstrapCodec.DecodeResult(result.JsonBytes);
    }

    /// <summary>
    /// Writes a bulk data chunk (1..65520 bytes) associated with a bulkRef UUID.
    /// </summary>
    public async Task WriteDataChunkAsync(BulkRef bulkRef, ReadOnlyMemory<byte> chunk, CancellationToken cancellationToken = default)
    {
        var dataRecord = new LanRecord.Data(bulkRef, chunk.ToArray());
        await WriteRecordAsync(dataRecord, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Writes a bulk data chunk (1..65520 bytes) associated with a bulkRef UUID string.
    /// </summary>
    public Task WriteDataChunkAsync(string bulkRef, ReadOnlyMemory<byte> chunk, CancellationToken cancellationToken = default) =>
        WriteDataChunkAsync(new BulkRef(bulkRef), chunk, cancellationToken);

    /// <summary>
    /// Writes a STREAM_END record declaring the terminal total byte count for a bulkRef.
    /// </summary>
    public async Task WriteStreamEndAsync(BulkRef bulkRef, long actualBytes, CancellationToken cancellationToken = default)
    {
        var streamEndRecord = new LanRecord.StreamEnd(bulkRef, actualBytes);
        await WriteRecordAsync(streamEndRecord, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Writes a STREAM_END record declaring the terminal total byte count for a bulkRef string.
    /// </summary>
    public Task WriteStreamEndAsync(string bulkRef, long actualBytes, CancellationToken cancellationToken = default) =>
        WriteStreamEndAsync(new BulkRef(bulkRef), actualBytes, cancellationToken);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _writeLock.Dispose();
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        _writeLock.Dispose();
        await Task.CompletedTask.ConfigureAwait(false);
    }
}
