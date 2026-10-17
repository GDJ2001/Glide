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
    private readonly CancellationTokenSource _channelCts;
    private readonly CancellationTokenRegistration _lifetimeRegistration;
    private readonly Action? _abortTransport;
    private readonly Timer? _coalesceTimer;
    private byte[]? _writeBuffer;
    private int _pendingWriteBytes;
    private long _firstPendingTimestamp;
    private Task _activeTimerFlushTask = Task.CompletedTask;
    private int _disposeState;
    private ExceptionDispatchInfo? _writeFault;

    public Stream BaseStream => _stream;
    public TransferBufferPool BufferPool => _bufferPool;
    public Task ActiveTimerFlushTask => Volatile.Read(ref _activeTimerFlushTask);

    public LanRecordChannel(
        Stream stream,
        bool coalesceWrites = false,
        int coalesceBufferBytes = DefaultCoalesceBufferBytes,
        int maxCoalesceDelayMs = DefaultMaxCoalesceDelayMs,
        TransferBufferPool? bufferPool = null,
        CancellationToken lifetimeToken = default,
        Action? abortTransport = null)
    {
        _stream = stream ?? throw new ArgumentNullException(nameof(stream));
        _bufferPool = bufferPool ?? TransferBufferPool.Shared;
        _abortTransport = abortTransport;
        _channelCts = lifetimeToken.CanBeCanceled
            ? CancellationTokenSource.CreateLinkedTokenSource(lifetimeToken)
            : new CancellationTokenSource();
        if (lifetimeToken.CanBeCanceled)
        {
            _lifetimeRegistration = lifetimeToken.Register(static state =>
            {
                ((LanRecordChannel)state!).CancelAndAbortTransport();
            }, this);
        }
        _coalesceWrites = coalesceWrites && coalesceBufferBytes >= LanRecordCodec.HeaderSize + LanRecordCodec.MaxRecordPayloadSize;
        _coalesceBufferBytes = Math.Max(coalesceBufferBytes, LanRecordCodec.HeaderSize + LanRecordCodec.MaxRecordPayloadSize);
        _maxCoalesceDelayMs = Math.Max(1, maxCoalesceDelayMs);
        if (_coalesceWrites)
        {
            _coalesceTimer = new Timer(OnCoalesceTimerFired, this, Timeout.Infinite, Timeout.Infinite);
        }
    }

    public void CancelAndAbortTransport()
    {
        try { _channelCts.Cancel(); } catch (ObjectDisposedException) { }
        try { _abortTransport?.Invoke(); } catch { }
        try { _stream.Close(); } catch { }
    }

    private static void OnCoalesceTimerFired(object? state)
    {
        if (state is LanRecordChannel channel)
        {
            if (Volatile.Read(ref channel._disposeState) != 0)
            {
                return;
            }
            Task flushTask = channel.FlushExpiredCoalescedWritesAsync();
            Volatile.Write(ref channel._activeTimerFlushTask, flushTask);
        }
    }

    private async Task FlushExpiredCoalescedWritesAsync()
    {
        if (Volatile.Read(ref _disposeState) != 0 || Volatile.Read(ref _pendingWriteBytes) <= 0)
        {
            return;
        }

        bool acquired;
        try
        {
            acquired = await _writeLock.WaitAsync(0, _channelCts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return;
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
            if (Volatile.Read(ref _disposeState) == 0 && _pendingWriteBytes > 0)
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: true, _channelCts.Token).ConfigureAwait(false);
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

    private async ValueTask AcquireWriteLockAsync(CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposeState) != 0, this);
        ThrowIfWriteFaulted();

        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(_channelCts.Token, cancellationToken);
        try
        {
            await _writeLock.WaitAsync(linkedCts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            CancelAndAbortTransport();
            if (cancellationToken.IsCancellationRequested)
            {
                throw new OperationCanceledException(cancellationToken);
            }
            throw;
        }
    }

    private async ValueTask FlushPendingCoreAsync(bool flushUnderlyingStream, CancellationToken cancellationToken)
    {
        ThrowIfWriteFaulted();
        try
        {
            _coalesceTimer?.Change(Timeout.Infinite, Timeout.Infinite);
        }
        catch (ObjectDisposedException)
        {
        }

        int pending = _pendingWriteBytes;
        if (pending <= 0 || _writeBuffer == null)
        {
            return;
        }

        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(_channelCts.Token, cancellationToken);
        using var abortReg = linkedCts.Token.Register(static state => ((LanRecordChannel)state!).CancelAndAbortTransport(), this);
        try
        {
            await _stream.WriteAsync(_writeBuffer.AsMemory(0, pending), linkedCts.Token).ConfigureAwait(false);
            if (flushUnderlyingStream)
            {
                await _stream.FlushAsync(linkedCts.Token).ConfigureAwait(false);
            }
            _pendingWriteBytes = 0;
        }
        catch (Exception error)
        {
            // A failed write may have transmitted a prefix. Never retry the same frame.
            _writeFault ??= ExceptionDispatchInfo.Capture(error);
            if (cancellationToken.IsCancellationRequested && error is not OperationCanceledException)
            {
                throw new OperationCanceledException("Channel write cancelled.", error, cancellationToken);
            }
            throw;
        }
    }

    /// <summary>
    /// Flushes any pending coalesced records to the underlying stream immediately.
    /// </summary>
    public async Task FlushPendingWritesAsync(CancellationToken cancellationToken = default)
    {
        await AcquireWriteLockAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfWriteFaulted();
            await FlushPendingCoreAsync(flushUnderlyingStream: true, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            try { _writeLock.Release(); } catch (ObjectDisposedException) { }
        }
    }

    /// <summary>
    /// Writes a strongly-typed LanRecord with strict serialized framing.
    /// </summary>
    public async Task WriteRecordAsync(LanRecord record, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(record);

        await AcquireWriteLockAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfWriteFaulted();
            if (_pendingWriteBytes > 0)
            {
                await FlushPendingCoreAsync(flushUnderlyingStream: false, cancellationToken).ConfigureAwait(false);
            }

            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(_channelCts.Token, cancellationToken);
            using var abortReg = linkedCts.Token.Register(static state => ((LanRecordChannel)state!).CancelAndAbortTransport(), this);
            try
            {
                await LanRecordCodec.WriteRecordAsync(record, _stream, linkedCts.Token).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                _writeFault ??= ExceptionDispatchInfo.Capture(error);
                if (cancellationToken.IsCancellationRequested && error is not OperationCanceledException)
                {
                    throw new OperationCanceledException("Channel write cancelled.", error, cancellationToken);
                }
                throw;
            }
        }
        finally
        {
            try { _writeLock.Release(); } catch (ObjectDisposedException) { }
        }
    }

    /// <summary>
    /// Reads the next LanRecord from the stream. Returns null on clean EOF before header bytes.
    /// </summary>
    public async Task<LanRecord?> ReadRecordAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposeState) != 0, this);
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(_channelCts.Token, cancellationToken);
        using var abortReg = cancellationToken.CanBeCanceled
            ? cancellationToken.Register(static state => ((LanRecordChannel)state!).CancelAndAbortTransport(), this)
            : default;
        try
        {
            return await LanRecordCodec.ReadRecordAsync(_stream, linkedCts.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (cancellationToken.IsCancellationRequested && ex is not OperationCanceledException)
        {
            throw new OperationCanceledException("Channel read cancelled.", ex, cancellationToken);
        }
    }

    /// <summary>
    /// Reads the next LanRecord using pooled payload buffers for DATA records (<see cref="LanRecord.OwnedData"/>)
    /// and zero-allocation UUID matching when <paramref name="expectedBulkRef"/> is supplied.
    /// </summary>
    public async ValueTask<LanRecord?> ReadOwnedRecordAsync(
        BulkRef? expectedBulkRef = null,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposeState) != 0, this);
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(_channelCts.Token, cancellationToken);
        using var abortReg = cancellationToken.CanBeCanceled
            ? cancellationToken.Register(static state => ((LanRecordChannel)state!).CancelAndAbortTransport(), this)
            : default;
        try
        {
            return await LanRecordCodec.ReadOwnedRecordAsync(_stream, _bufferPool, expectedBulkRef, linkedCts.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (cancellationToken.IsCancellationRequested && ex is not OperationCanceledException)
        {
            throw new OperationCanceledException("Channel read cancelled.", ex, cancellationToken);
        }
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
        int frameLength = LanRecordCodec.GetDataFrameLength(chunk.Length);
        await AcquireWriteLockAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfWriteFaulted();
            byte[] writeBuf = EnsureWriteBuffer();

            if (!_coalesceWrites)
            {
                int written = LanRecordCodec.EncodeDataRecordToSpan(bulkRef, chunk.Span, writeBuf.AsSpan(0, frameLength));
                using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(_channelCts.Token, cancellationToken);
                using var abortReg = linkedCts.Token.Register(static state => ((LanRecordChannel)state!).CancelAndAbortTransport(), this);
                try
                {
                    await _stream.WriteAsync(writeBuf.AsMemory(0, written), linkedCts.Token).ConfigureAwait(false);
                    await _stream.FlushAsync(linkedCts.Token).ConfigureAwait(false);
                }
                catch (Exception error)
                {
                    _writeFault ??= ExceptionDispatchInfo.Capture(error);
                    if (cancellationToken.IsCancellationRequested && error is not OperationCanceledException)
                    {
                        throw new OperationCanceledException("Channel write cancelled.", error, cancellationToken);
                    }
                    throw;
                }
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
            else if (Volatile.Read(ref _disposeState) == 0)
            {
                try
                {
                    _coalesceTimer?.Change(_maxCoalesceDelayMs, Timeout.Infinite);
                }
                catch (ObjectDisposedException)
                {
                }
            }
        }
        finally
        {
            try { _writeLock.Release(); } catch (ObjectDisposedException) { }
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
        await AcquireWriteLockAsync(cancellationToken).ConfigureAwait(false);
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
            try { _writeLock.Release(); } catch (ObjectDisposedException) { }
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
        if (Interlocked.Exchange(ref _disposeState, 1) != 0) return;

        try { _coalesceTimer?.Change(Timeout.Infinite, Timeout.Infinite); } catch { }
        try { _coalesceTimer?.Dispose(); } catch { }
        try { _lifetimeRegistration.Dispose(); } catch { }

        Task activeFlush = Volatile.Read(ref _activeTimerFlushTask);
        if (_writeLock.CurrentCount == 0 || !activeFlush.IsCompleted)
        {
            CancelAndAbortTransport();
        }
        else
        {
            try { _channelCts.Cancel(); } catch { }
        }

        try { activeFlush.GetAwaiter().GetResult(); } catch { }

        bool lockTaken = false;
        try
        {
            _writeLock.Wait();
            lockTaken = true;
            ReleaseWriteBuffer();
        }
        finally
        {
            if (lockTaken)
            {
                try { _writeLock.Release(); } catch { }
            }
            try { _writeLock.Dispose(); } catch { }
            try { _channelCts.Dispose(); } catch { }
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposeState, 1) != 0) return;

        try { _coalesceTimer?.Change(Timeout.Infinite, Timeout.Infinite); } catch { }
        if (_coalesceTimer != null)
        {
            try { await _coalesceTimer.DisposeAsync().ConfigureAwait(false); } catch { }
        }
        try { _lifetimeRegistration.Dispose(); } catch { }

        Task activeFlush = Volatile.Read(ref _activeTimerFlushTask);
        if (_writeLock.CurrentCount == 0 || !activeFlush.IsCompleted)
        {
            CancelAndAbortTransport();
        }
        else
        {
            try { _channelCts.Cancel(); } catch { }
        }

        try { await activeFlush.ConfigureAwait(false); } catch { }

        bool lockTaken = false;
        try
        {
            await _writeLock.WaitAsync().ConfigureAwait(false);
            lockTaken = true;
            ReleaseWriteBuffer();
        }
        finally
        {
            if (lockTaken)
            {
                try { _writeLock.Release(); } catch { }
            }
            try { _writeLock.Dispose(); } catch { }
            try { _channelCts.Dispose(); } catch { }
        }
    }
}
