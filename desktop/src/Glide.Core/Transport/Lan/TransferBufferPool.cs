using System.Buffers;
using System.Collections.Concurrent;

namespace Glide.Core.Transport.Lan;

/// <summary>
/// Explicitly owned, pooled payload buffer that zeroes its contents and releases its process-wide
/// capacity permit only when disposed by its final consumer.
/// </summary>
public sealed class OwnedTransferBuffer : IDisposable
{
    private readonly TransferBufferPool _pool;
    private byte[]? _buffer;
    private int _length;
    private int _disposed;

    internal OwnedTransferBuffer(TransferBufferPool pool, byte[] buffer, int length)
    {
        _pool = pool;
        _buffer = buffer;
        _length = length;
    }

    public bool IsDisposed => Volatile.Read(ref _disposed) != 0;

    public int Length
    {
        get
        {
            ThrowIfDisposed();
            return _length;
        }
        set
        {
            ThrowIfDisposed();
            if (value < 0 || value > _buffer!.Length)
            {
                throw new ArgumentOutOfRangeException(nameof(value));
            }
            _length = value;
        }
    }

    public byte[] RawBuffer
    {
        get
        {
            ThrowIfDisposed();
            return _buffer!;
        }
    }

    public Memory<byte> Memory
    {
        get
        {
            ThrowIfDisposed();
            return _buffer!.AsMemory(0, _length);
        }
    }

    public ReadOnlyMemory<byte> ReadOnlyMemory
    {
        get
        {
            ThrowIfDisposed();
            return _buffer!.AsMemory(0, _length);
        }
    }

    public Span<byte> Span
    {
        get
        {
            ThrowIfDisposed();
            return _buffer!.AsSpan(0, _length);
        }
    }

    public Memory<byte> GetAvailableMemory(int count)
    {
        ThrowIfDisposed();
        if (count < 0 || count > _buffer!.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(count));
        }
        return _buffer.AsMemory(0, count);
    }

    private void ThrowIfDisposed()
    {
        if (Volatile.Read(ref _disposed) != 0)
        {
            throw new ObjectDisposedException(nameof(OwnedTransferBuffer));
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        byte[]? buf = Interlocked.Exchange(ref _buffer, null);
        if (buf != null)
        {
            _pool.ReturnBuffer(buf, _length);
        }
    }
}

/// <summary>
/// Process-wide bounded pool for application-owned transfer buffers.
/// Caps total application-owned transfer buffers at 8 MiB per process by default and
/// awaits capacity when full rather than growing unboundedly or dropping data.
/// </summary>
public sealed class TransferBufferPool : IDisposable
{
    public const int DefaultBufferBytes = 65_536;
    public const int DefaultMaxProcessBytes = 8 * 1024 * 1024; // 8 MiB process cap

    public static TransferBufferPool Shared { get; } = new(DefaultMaxProcessBytes, DefaultBufferBytes);

    private readonly int _bufferBytes;
    private readonly int _maxBuffers;
    private readonly SemaphoreSlim _permits;
    private readonly ConcurrentQueue<byte[]> _freeBuffers = new();
    private int _rentedBuffers;
    private int _peakRentedBuffers;
    private bool _disposed;

    public int BufferBytes => _bufferBytes;
    public int MaxBuffers => _maxBuffers;
    public long MaxProcessBytes => (long)_maxBuffers * _bufferBytes;
    public int RentedBufferCount => Volatile.Read(ref _rentedBuffers);
    public long RentedBytes => (long)Volatile.Read(ref _rentedBuffers) * _bufferBytes;
    public long PeakRentedBytes => (long)Volatile.Read(ref _peakRentedBuffers) * _bufferBytes;

    public TransferBufferPool(int maxProcessBytes = DefaultMaxProcessBytes, int bufferBytes = DefaultBufferBytes)
    {
        if (bufferBytes <= 0) throw new ArgumentOutOfRangeException(nameof(bufferBytes));
        if (maxProcessBytes < bufferBytes) throw new ArgumentOutOfRangeException(nameof(maxProcessBytes));

        _bufferBytes = bufferBytes;
        _maxBuffers = maxProcessBytes / bufferBytes;
        _permits = new SemaphoreSlim(_maxBuffers, _maxBuffers);
    }

    public async ValueTask<OwnedTransferBuffer> RentAsync(
        int initialLength = 0,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (initialLength < 0 || initialLength > _bufferBytes)
        {
            throw new ArgumentOutOfRangeException(nameof(initialLength));
        }

        await _permits.WaitAsync(cancellationToken).ConfigureAwait(false);
        return RentCore(initialLength);
    }

    public OwnedTransferBuffer Rent(int initialLength = 0, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (initialLength < 0 || initialLength > _bufferBytes)
        {
            throw new ArgumentOutOfRangeException(nameof(initialLength));
        }

        _permits.Wait(cancellationToken);
        return RentCore(initialLength);
    }

    private OwnedTransferBuffer RentCore(int initialLength)
    {
        int current = Interlocked.Increment(ref _rentedBuffers);
        while (true)
        {
            int peak = Volatile.Read(ref _peakRentedBuffers);
            if (current <= peak || Interlocked.CompareExchange(ref _peakRentedBuffers, current, peak) == peak)
            {
                break;
            }
        }

        if (!_freeBuffers.TryDequeue(out byte[]? buffer))
        {
            buffer = GC.AllocateUninitializedArray<byte>(_bufferBytes, pinned: false);
        }

        return new OwnedTransferBuffer(this, buffer, initialLength);
    }

    internal void ReturnBuffer(byte[] buffer, int usedLength)
    {
        int clearLength = Math.Clamp(usedLength, 0, buffer.Length);
        if (clearLength > 0)
        {
            Array.Clear(buffer, 0, clearLength);
        }
        else
        {
            Array.Clear(buffer, 0, buffer.Length);
        }

        if (!_disposed && _freeBuffers.Count < _maxBuffers)
        {
            _freeBuffers.Enqueue(buffer);
        }

        Interlocked.Decrement(ref _rentedBuffers);
        try
        {
            _permits.Release();
        }
        catch (ObjectDisposedException)
        {
            // Pool disposed during shutdown
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        while (_freeBuffers.TryDequeue(out byte[]? buf))
        {
            Array.Clear(buf, 0, buf.Length);
        }
        _permits.Dispose();
    }
}
