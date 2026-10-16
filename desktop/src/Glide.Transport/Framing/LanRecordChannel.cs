using System.Buffers;
using System.Diagnostics;
using System.Runtime.ExceptionServices;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Transport.Lan;

namespace Glide.Transport.Framing;

/// <summary>
/// Bidirectional framed channel operating over a stream for LAN Profile 1 records (LAN-012, Section 6).
/// Strictly handles RecordKind framing: Bootstrap, BootstrapResult, Control envelopes, Data chunks, and StreamEnd.
/// Serializes outgoing writes via an internal semaphore to prevent interleaving of frame headers and payloads.
/// Supports bounded 256 KiB record coalescing with prompt delivery at item boundaries, control records, or after 5 ms.
/// </summary>
public sealed class LanRecordChannel : IAsyncDisposable, IDisposable
{
    public const int DefaultCoalesceBufferBytes = 256 * 1024;
    public const int DefaultMaxCoalesceDelayMs = 5;

    private readonly Stream _stream;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly TransferBufferPool _bufferPool;
    private readonly bool _coalesceWrites;
    private readonly int _coalesceBufferBytes;
    private readonly int _maxCoalesceDelayMs;
    private readonly Timer? _coalesceTimer;
    private byte[]? _writeBuffer;
    private int _pendingWriteBytes;
    private long _firstPendingTimestamp;
    private bool _disposed;
    private ExceptionDispatchInfo? _writeFault;

    public Stream BaseStream => _stream;
    public TransferBufferPool BufferPool => _bufferPool;

    public LanRecordChannel(
        Stream stream,
        bool coalesceWrites = false,
        int coalesceBufferBytes = DefaultCoalesceBufferBytes,
        int maxCoalesceDelayMs = DefaultMaxCoalesceDelayMs,
        TransferBufferPool? bufferPool = null)
    {
        _stream = stream ?? throw new ArgumentNullException(nameof(stream));
        _bufferPool = bufferPool ?? TransferBufferPool.Shared;
        _coalesceWrites = coalesceWrites && coalesceBufferBytes >= LanRecordCodec.HeaderSize + LanRecordCodec.MaxRecordPayloadSize;
        _coalesceBufferBytes = Math.Max(coalesceBufferBytes, LanRecordCodec.HeaderSize + LanRecordCodec.MaxRecordPayloadSize);
        _maxCoalesceDelayMs = Math.Max(1, maxCoalesceDelayMs);
        if (_coalesceWrites)
        {
            _coalesceTimer = new Timer(OnCoalesceTimerFired, this, Timeout.Infinite, Timeout.Infinite);
        }
    }

    private static void OnCoalesceTimerFired(object? state)
    {
        if (state is LanRecordChannel channel)
        {
            _ = channel.FlushExpiredCoalescedWritesAsync();
        }
    }

    private async Task FlushExpiredCoalescedWritesAsync()
    {
        if (_disposed || Volatile.Read(ref _pendingWriteBytes) <= 0)
        {
            return;
        }

        bool acquired;
        try
        {
            acquired = await _writeLock.WaitAsync(0).ConfigureAwait(false);
        }
        catch (ObjectDisposedException)
        {
            return;
        }
        if (!acquired)
        {
            return;
        }

        try
        {
            if (!_disposed && _pendingWriteBytes > 0 &&
                Stopwatch.GetElapsedTime(_firstPendingTimestamp).TotalMilliseconds >= _maxCoalesceDelayMs)
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: true, CancellationToken.None).ConfigureAwait(false);
            }
        }
        catch (Exception error)
        {
            // Timer callbacks have no caller. Keep the original failure for the next ordered write.
            _writeFault ??= ExceptionDispatchInfo.Capture(error);
        }
        finally
        {
            try
            {
                _writeLock.Release();
            }
            catch (ObjectDisposedException)
            {
            }
        }
    }

    private byte[] EnsureWriteBuffer()
    {
        return _writeBuffer ??= ArrayPool<byte>.Shared.Rent(_coalesceBufferBytes);
    }

    private void ThrowIfWriteFaulted() => _writeFault?.Throw();

    private async ValueTask FlushPendingCoreAsync(bool flushUnderlyingStream, CancellationToken cancellationToken)
    {
        ThrowIfWriteFaulted();
        _coalesceTimer?.Change(Timeout.Infinite, Timeout.Infinite);
        int pending = _pendingWriteBytes;
        if (pending <= 0 || _writeBuffer == null)
        {
            return;
        }

        try
        {
            await _stream.WriteAsync(_writeBuffer.AsMemory(0, pending), cancellationToken).ConfigureAwait(false);
            if (flushUnderlyingStream)
            {
                await _stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            _pendingWriteBytes = 0;
        }
        catch (Exception error)
        {
            // A failed write may have transmitted a prefix. Never retry the same frame.
            _writeFault ??= ExceptionDispatchInfo.Capture(error);
            throw;
        }
    }

    /// <summary>
    /// Flushes any pending coalesced records to the underlying stream immediately.
    /// </summary>
    public async Task FlushPendingWritesAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfWriteFaulted();
            await FlushPendingCoreAsync(flushUnderlyingStream: true, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
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
            ThrowIfWriteFaulted();
            if (_pendingWriteBytes > 0)
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: false, cancellationToken).ConfigureAwait(false);
            }
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
    /// Reads the next LanRecord using pooled payload buffers for DATA records (<see cref="LanRecord.OwnedData"/>)
    /// and zero-allocation UUID matching when <paramref name="expectedBulkRef"/> is supplied.
    /// </summary>
    public ValueTask<LanRecord?> ReadOwnedRecordAsync(
        BulkRef? expectedBulkRef = null,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return LanRecordCodec.ReadOwnedRecordAsync(_stream, _bufferPool, expectedBulkRef, cancellationToken);
    }

    /// <summary>
    /// Encodes a Glide Protocol V1 Envelope and transmits it as a LanRecord.Control record.
    /// Control messages trigger prompt ordered delivery of any pending coalesced data.
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
            if (record is IDisposable disposable) disposable.Dispose();
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
            if (record is IDisposable disposable) disposable.Dispose();
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
            if (record is IDisposable disposable) disposable.Dispose();
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Expected BOOTSTRAP_RESULT record (0x02), got {record.Kind} (0x{(byte)record.Kind:X2})");
        }

        return LanBootstrapCodec.DecodeResult(result.JsonBytes);
    }

    /// <summary>
    /// Writes a bulk data chunk (1..65520 bytes) associated with a bulkRef UUID without intermediate array copies.
    /// When coalescing is enabled, batches encoded records up to 256 KiB or 5 ms maximum delay.
    /// </summary>
    public async Task WriteDataChunkAsync(BulkRef bulkRef, ReadOnlyMemory<byte> chunk, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        int frameLength = LanRecordCodec.GetDataFrameLength(chunk.Length);
        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfWriteFaulted();
            byte[] writeBuf = EnsureWriteBuffer();

            if (!_coalesceWrites)
            {
                int written = LanRecordCodec.EncodeDataRecordToSpan(bulkRef, chunk.Span, writeBuf.AsSpan(0, frameLength));
                await _stream.WriteAsync(writeBuf.AsMemory(0, written), cancellationToken).ConfigureAwait(false);
                await _stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                return;
            }

            long now = Stopwatch.GetTimestamp();
            if (_pendingWriteBytes > 0 &&
                (_pendingWriteBytes + frameLength > _coalesceBufferBytes ||
                 Stopwatch.GetElapsedTime(_firstPendingTimestamp, now).TotalMilliseconds >= _maxCoalesceDelayMs))
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: false, cancellationToken).ConfigureAwait(false);
            }

            if (_pendingWriteBytes == 0)
            {
                _firstPendingTimestamp = Stopwatch.GetTimestamp();
            }

            int encodedBytes = LanRecordCodec.EncodeDataRecordToSpan(
                bulkRef,
                chunk.Span,
                writeBuf.AsSpan(_pendingWriteBytes, frameLength));
            _pendingWriteBytes += encodedBytes;

            // If another full-sized DATA record cannot fit in the 256 KiB coalescing buffer, send immediately.
            int maxNextDataFrame = LanRecordCodec.GetDataFrameLength(LanRecordCodec.MaxDataChunkSize);
            if (_pendingWriteBytes + maxNextDataFrame > _coalesceBufferBytes)
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: false, cancellationToken).ConfigureAwait(false);
            }
            else
            {
                _coalesceTimer?.Change(_maxCoalesceDelayMs, Timeout.Infinite);
            }
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>
    /// Writes a bulk data chunk (1..65520 bytes) associated with a bulkRef UUID string.
    /// </summary>
    public Task WriteDataChunkAsync(string bulkRef, ReadOnlyMemory<byte> chunk, CancellationToken cancellationToken = default) =>
        WriteDataChunkAsync(new BulkRef(bulkRef), chunk, cancellationToken);

    /// <summary>
    /// Writes a STREAM_END record declaring the terminal total byte count for a bulkRef and flushes pending item data.
    /// </summary>
    public async Task WriteStreamEndAsync(BulkRef bulkRef, long actualBytes, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfWriteFaulted();
            byte[] writeBuf = EnsureWriteBuffer();
            if (_pendingWriteBytes > 0 &&
                _pendingWriteBytes + LanRecordCodec.StreamEndFrameSize > writeBuf.Length)
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: false, cancellationToken).ConfigureAwait(false);
            }

            int encoded = LanRecordCodec.EncodeStreamEndToSpan(
                bulkRef,
                actualBytes,
                writeBuf.AsSpan(_pendingWriteBytes, LanRecordCodec.StreamEndFrameSize));
            _pendingWriteBytes += encoded;

            await FlushPendingCoreAsync(flushUnderlyingStream: true, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>
    /// Writes a STREAM_END record declaring the terminal total byte count for a bulkRef string.
    /// </summary>
    public Task WriteStreamEndAsync(string bulkRef, long actualBytes, CancellationToken cancellationToken = default) =>
        WriteStreamEndAsync(new BulkRef(bulkRef), actualBytes, cancellationToken);

    private void ReleaseWriteBuffer()
    {
        byte[]? buf = Interlocked.Exchange(ref _writeBuffer, null);
        if (buf != null)
        {
            Array.Clear(buf, 0, buf.Length);
            ArrayPool<byte>.Shared.Return(buf);
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _coalesceTimer?.Dispose();
        _writeLock.Wait();
        ReleaseWriteBuffer();
        _writeLock.Release();
        _writeLock.Dispose();
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        if (_coalesceTimer != null)
        {
            await _coalesceTimer.DisposeAsync().ConfigureAwait(false);
        }
        await _writeLock.WaitAsync().ConfigureAwait(false);
        ReleaseWriteBuffer();
        _writeLock.Release();
        _writeLock.Dispose();
    }
}
