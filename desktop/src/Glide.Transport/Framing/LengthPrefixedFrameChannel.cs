using System.Buffers.Binary;
using Glide.Core.Codec;
using Glide.Core.Models;

namespace Glide.Transport.Framing;

public sealed class LengthPrefixedFrameChannel
{
    public const int MaxControlBytes = 16384;
    public const int ChunkSize = 65536; // 64 KiB

    private readonly Stream _stream;

    public LengthPrefixedFrameChannel(Stream stream)
    {
        _stream = stream ?? throw new ArgumentNullException(nameof(stream));
    }

    public async Task WriteEnvelopeAsync(Envelope envelope, CancellationToken cancellationToken = default)
    {
        byte[] payload = ProtocolCodec.Encode(envelope);
        if (payload.Length > MaxControlBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Control payload size {payload.Length} exceeds maximum {MaxControlBytes} bytes");
        }

        byte[] header = new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(header, (uint)payload.Length);

        await _stream.WriteAsync(header, cancellationToken);
        await _stream.WriteAsync(payload, cancellationToken);
        await _stream.FlushAsync(cancellationToken);
    }

    public async Task<Envelope> ReadEnvelopeAsync(CancellationToken cancellationToken = default)
    {
        byte[] header = new byte[4];
        await ReadExactAsync(_stream, header, 0, 4, cancellationToken);

        uint len = BinaryPrimitives.ReadUInt32BigEndian(header);
        if (len == 0)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "Framed message length must be positive (got 0)");
        }

        if (len > MaxControlBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Framed control message size {len} exceeds maximum {MaxControlBytes} bytes");
        }

        byte[] payload = new byte[len];
        await ReadExactAsync(_stream, payload, 0, (int)len, cancellationToken);

        return ProtocolCodec.Decode(payload);
    }

    public async Task WriteBulkStreamAsync(Stream sourceStream, long totalBytes, CancellationToken cancellationToken = default)
    {
        byte[] buffer = new byte[ChunkSize];
        long remaining = totalBytes;

        while (remaining > 0)
        {
            int toRead = (int)Math.Min(buffer.Length, remaining);
            int read = await sourceStream.ReadAsync(buffer.AsMemory(0, toRead), cancellationToken);
            if (read <= 0)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.ReadFailure,
                    $"Premature EOF reading bulk stream: expected {totalBytes} bytes, stopped at {totalBytes - remaining}");
            }

            await _stream.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            remaining -= read;
        }

        await _stream.FlushAsync(cancellationToken);
    }

    private static async Task ReadExactAsync(Stream stream, byte[] buffer, int offset, int count, CancellationToken cancellationToken)
    {
        int readTotal = 0;
        while (readTotal < count)
        {
            int r = await stream.ReadAsync(buffer.AsMemory(offset + readTotal, count - readTotal), cancellationToken);
            if (r <= 0)
            {
                if (readTotal == 0) throw new EndOfStreamException("End of stream reached");
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    $"Stream truncated: expected {count} bytes, read only {readTotal}");
            }
            readTotal += r;
        }
    }
}
