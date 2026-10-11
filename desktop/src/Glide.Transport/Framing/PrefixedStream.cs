using System.Text;
using Glide.Core.Transport.Lan;
using Glide.Transport.Sockets;

namespace Glide.Transport.Framing;

/// <summary>
/// Wraps an underlying bidirectional stream and replays a fixed prefix buffer on initial reads
/// before delegating all subsequent reads and all writes to the inner stream.
/// Used by the listener preamble inspector to preserve Profile 1 LanRecord framing after
/// inspecting the first 4 bytes for the "GLD2" Profile 2 preamble.
/// </summary>
public sealed class PrefixedStream : Stream
{
    private readonly Stream _inner;
    private readonly byte[] _prefix;
    private int _prefixOffset;
    private readonly bool _leaveInnerOpen;

    public PrefixedStream(Stream inner, ReadOnlySpan<byte> prefix, bool leaveInnerOpen = true)
    {
        _inner = inner ?? throw new ArgumentNullException(nameof(inner));
        _prefix = prefix.ToArray();
        _prefixOffset = 0;
        _leaveInnerOpen = leaveInnerOpen;
    }

    public override bool CanRead => _inner.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => _inner.CanWrite;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush() => _inner.Flush();
    public override Task FlushAsync(CancellationToken cancellationToken) => _inner.FlushAsync(cancellationToken);

    public override int Read(byte[] buffer, int offset, int count) =>
        Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        if (_prefixOffset < _prefix.Length)
        {
            int available = _prefix.Length - _prefixOffset;
            int toCopy = Math.Min(available, buffer.Length);
            _prefix.AsSpan(_prefixOffset, toCopy).CopyTo(buffer);
            _prefixOffset += toCopy;
            return toCopy;
        }

        return _inner.Read(buffer);
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        if (_prefixOffset < _prefix.Length)
        {
            int available = _prefix.Length - _prefixOffset;
            int toCopy = Math.Min(available, buffer.Length);
            _prefix.AsMemory(_prefixOffset, toCopy).CopyTo(buffer);
            _prefixOffset += toCopy;
            return ValueTask.FromResult(toCopy);
        }

        return _inner.ReadAsync(buffer, cancellationToken);
    }

    public override void Write(byte[] buffer, int offset, int count) =>
        _inner.Write(buffer, offset, count);

    public override void Write(ReadOnlySpan<byte> buffer) =>
        _inner.Write(buffer);

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        _inner.WriteAsync(buffer, offset, count, cancellationToken);

    public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) =>
        _inner.WriteAsync(buffer, cancellationToken);

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing && !_leaveInnerOpen)
        {
            _inner.Dispose();
        }
        base.Dispose(disposing);
    }

    public override async ValueTask DisposeAsync()
    {
        if (!_leaveInnerOpen)
        {
            await _inner.DisposeAsync().ConfigureAwait(false);
        }
        await base.DisposeAsync().ConfigureAwait(false);
    }
}

public enum LanSessionDispatchKind
{
    EndOfStream,
    Profile1LanRecord,
    Profile2Gld2,
    Unknown
}

public static class LanSessionPreambleInspector
{
    public static ReadOnlySpan<byte> Gld2Magic => "GLD2"u8;

    public static async Task WriteGld2PreambleAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        byte[] magic = Gld2Magic.ToArray();
        await stream.WriteAsync(magic, cancellationToken).ConfigureAwait(false);
    }

    public static async Task<LanSessionDispatchKind> InspectAndPrepareAsync(
        TlsSocketConnection connection,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(connection);
        return await InspectStreamAsync(
            connection.TransportStream,
            prefix => connection.PrependBytes(prefix),
            cancellationToken).ConfigureAwait(false);
    }

    public static async Task<LanSessionDispatchKind> InspectStreamAsync(
        Stream stream,
        Action<ReadOnlySpan<byte>> prependPrefix,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(prependPrefix);

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(10));
        CancellationToken token = timeoutCts.Token;

        byte[] preamble = new byte[4];
        int offset = 0;
        while (offset < 4)
        {
            int read = await stream.ReadAsync(preamble.AsMemory(offset, 4 - offset), token).ConfigureAwait(false);
            if (read == 0)
            {
                return offset == 0
                    ? LanSessionDispatchKind.EndOfStream
                    : LanSessionDispatchKind.Unknown;
            }
            offset += read;
        }

        if (preamble.AsSpan().SequenceEqual(Gld2Magic))
        {
            return LanSessionDispatchKind.Profile2Gld2;
        }

        if (Enum.IsDefined(typeof(LanRecordKind), preamble[0]))
        {
            prependPrefix(preamble);
            return LanSessionDispatchKind.Profile1LanRecord;
        }

        return LanSessionDispatchKind.Unknown;
    }
}
