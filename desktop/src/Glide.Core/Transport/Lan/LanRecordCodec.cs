using System.Buffers;
using System.Buffers.Binary;
using Glide.Core.Models;

namespace Glide.Core.Transport.Lan;

/// <summary>
/// Encodes, decodes, and frames binary records for Glide LAN Transport Profile 1 (LAN-007, Section 6, Section 19).
/// Framing format: 5-byte header:
/// - byte 0: kind (UByte)
/// - bytes 1..4: payloadLength (UInt32 big-endian)
/// Enforces strict bounds checking and signed-overflow rejection before allocating payload buffers.
/// </summary>
public static class LanRecordCodec
{
    public const int HeaderSize = 5;
    public const int DataHeaderAndUuidSize = HeaderSize + LanUuidCodec.UuidByteLength; // 21 bytes
    public const int StreamEndFrameSize = HeaderSize + 24; // 29 bytes
    public const int MaxRecordPayloadSize = 65536; // 64 KiB ceiling across all record kinds
    public const int MaxDataChunkSize = MaxRecordPayloadSize - LanUuidCodec.UuidByteLength; // 65520 bytes

    /// <summary>
    /// Encodes a LanRecord into its on-wire 5-byte header + payload format.
    /// </summary>
    public static byte[] Encode(LanRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);

        if (record is LanRecord.OwnedData ownedData)
        {
            int totalBytes = GetDataFrameLength(ownedData.ChunkLength);
            byte[] ownedFrame = new byte[totalBytes];
            EncodeDataRecordToSpan(ownedData.BulkRef, ownedData.ChunkSpan, ownedFrame);
            return ownedFrame;
        }

        byte[] payloadBytes = record switch
        {
            LanRecord.Bootstrap bootstrap => bootstrap.JsonBytes,
            LanRecord.BootstrapResult bootstrapResult => bootstrapResult.JsonBytes,
            LanRecord.Control control => control.Payload,
            LanRecord.Data data => EncodeDataPayload(data),
            LanRecord.StreamEnd streamEnd => EncodeStreamEndPayload(streamEnd),
            _ => throw new ArgumentOutOfRangeException(nameof(record), $"Unsupported record type: {record.GetType().Name}")
        };

        var kind = record.Kind;
        int minSize = kind.MinPayloadSize();
        int maxSize = kind.MaxPayloadSize();

        if (payloadBytes.Length < minSize || payloadBytes.Length > maxSize)
        {
            throw new ArgumentException(
                $"Payload size {payloadBytes.Length} out of bounds for {kind} ({minSize}..{maxSize})");
        }

        byte[] frame = new byte[HeaderSize + payloadBytes.Length];
        frame[0] = (byte)kind;
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(1, 4), (uint)payloadBytes.Length);
        payloadBytes.CopyTo(frame, HeaderSize);

        return frame;
    }

    public static int GetDataFrameLength(int chunkLength) =>
        DataHeaderAndUuidSize + chunkLength;

    /// <summary>
    /// Encodes a DATA record (5-byte header + 16-byte network-order UUID + chunk) directly into a destination span
    /// without intermediate byte[] allocations.
    /// </summary>
    public static int EncodeDataRecordToSpan(BulkRef bulkRef, ReadOnlySpan<byte> chunk, Span<byte> destination)
    {
        if (chunk.Length < 1 || chunk.Length > MaxDataChunkSize)
        {
            throw new ArgumentException(
                $"DATA chunk length {chunk.Length} out of bounds (1..{MaxDataChunkSize})",
                nameof(chunk));
        }

        int payloadLength = LanUuidCodec.UuidByteLength + chunk.Length;
        int frameLength = HeaderSize + payloadLength;
        if (destination.Length < frameLength)
        {
            throw new ArgumentException(
                $"Destination span length {destination.Length} is smaller than required DATA frame length {frameLength}",
                nameof(destination));
        }

        destination[0] = (byte)LanRecordKind.Data;
        BinaryPrimitives.WriteUInt32BigEndian(destination.Slice(1, 4), (uint)payloadLength);
        LanUuidCodec.WriteBulkRefNetworkBytes(bulkRef, destination.Slice(HeaderSize, LanUuidCodec.UuidByteLength));
        chunk.CopyTo(destination.Slice(DataHeaderAndUuidSize, chunk.Length));
        return frameLength;
    }

    /// <summary>
    /// Encodes a STREAM_END record (5-byte header + 16-byte network-order UUID + 8-byte big-endian actualBytes)
    /// directly into a destination span without intermediate byte[] allocations.
    /// </summary>
    public static int EncodeStreamEndToSpan(BulkRef bulkRef, long actualBytes, Span<byte> destination)
    {
        if (destination.Length < StreamEndFrameSize)
        {
            throw new ArgumentException(
                $"Destination span length {destination.Length} is smaller than required STREAM_END frame length {StreamEndFrameSize}",
                nameof(destination));
        }

        destination[0] = (byte)LanRecordKind.StreamEnd;
        BinaryPrimitives.WriteUInt32BigEndian(destination.Slice(1, 4), 24u);
        LanUuidCodec.WriteBulkRefNetworkBytes(bulkRef, destination.Slice(HeaderSize, LanUuidCodec.UuidByteLength));
        BinaryPrimitives.WriteUInt64BigEndian(destination.Slice(DataHeaderAndUuidSize, 8), (ulong)actualBytes);
        return StreamEndFrameSize;
    }

    private static byte[] EncodeDataPayload(LanRecord.Data data)
    {
        byte[] payload = new byte[LanUuidCodec.UuidByteLength + data.Chunk.Length];
        LanUuidCodec.WriteBulkRefNetworkBytes(data.BulkRef, payload.AsSpan(0, LanUuidCodec.UuidByteLength));
        Buffer.BlockCopy(data.Chunk, 0, payload, LanUuidCodec.UuidByteLength, data.Chunk.Length);
        return payload;
    }

    private static byte[] EncodeStreamEndPayload(LanRecord.StreamEnd streamEnd)
    {
        byte[] payload = new byte[24];
        LanUuidCodec.WriteBulkRefNetworkBytes(streamEnd.BulkRef, payload.AsSpan(0, LanUuidCodec.UuidByteLength));
        BinaryPrimitives.WriteUInt64BigEndian(payload.AsSpan(16, 8), (ulong)streamEnd.ActualBytes);
        return payload;
    }

    /// <summary>
    /// Decodes a complete on-wire frame from a byte array.
    /// </summary>
    public static LanRecord Decode(byte[] frame, int offset = 0, int length = -1)
    {
        ArgumentNullException.ThrowIfNull(frame);
        if (length < 0) length = frame.Length - offset;

        if (length < HeaderSize)
        {
            throw new ArgumentException($"Frame length {length} is less than minimum header size {HeaderSize}");
        }

        byte kindByte = frame[offset];
        if (!Enum.IsDefined(typeof(LanRecordKind), kindByte))
        {
            throw new ArgumentException($"Unknown record kind: 0x{kindByte:X2}");
        }

        var kind = (LanRecordKind)kindByte;
        uint payloadLength = BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(offset + 1, 4));

        if (payloadLength > MaxRecordPayloadSize)
        {
            throw new ArgumentException($"Payload length {payloadLength} exceeds maximum {MaxRecordPayloadSize}");
        }

        int minSize = kind.MinPayloadSize();
        int maxSize = kind.MaxPayloadSize();
        if (payloadLength < (uint)minSize || payloadLength > (uint)maxSize)
        {
            throw new ArgumentException($"Payload length {payloadLength} out of bounds for {kind} ({minSize}..{maxSize})");
        }

        if (length != HeaderSize + (int)payloadLength)
        {
            throw new ArgumentException($"Frame size {length} does not match declared size {HeaderSize + payloadLength}");
        }

        byte[] payload = new byte[payloadLength];
        Buffer.BlockCopy(frame, offset + HeaderSize, payload, 0, (int)payloadLength);

        return DecodePayload(kind, payload);
    }

    private static LanRecord DecodePayload(LanRecordKind kind, byte[] payload)
    {
        return kind switch
        {
            LanRecordKind.Bootstrap => new LanRecord.Bootstrap(payload),
            LanRecordKind.BootstrapResult => new LanRecord.BootstrapResult(payload),
            LanRecordKind.Control => new LanRecord.Control(payload),
            LanRecordKind.Data => DecodeData(payload),
            LanRecordKind.StreamEnd => DecodeStreamEnd(payload),
            _ => throw new ArgumentOutOfRangeException(nameof(kind), kind, null)
        };
    }

    private static LanRecord.Data DecodeData(byte[] payload)
    {
        if (payload.Length < LanUuidCodec.UuidByteLength + 1)
        {
            throw new ArgumentException($"DATA payload too small: {payload.Length}");
        }
        var bulkRef = LanUuidCodec.NetworkBytesToBulkRef(payload, 0);
        int chunkLen = payload.Length - LanUuidCodec.UuidByteLength;
        byte[] chunk = new byte[chunkLen];
        Buffer.BlockCopy(payload, LanUuidCodec.UuidByteLength, chunk, 0, chunkLen);
        return new LanRecord.Data(bulkRef, chunk);
    }

    private static LanRecord.StreamEnd DecodeStreamEnd(byte[] payload)
    {
        if (payload.Length != 24)
        {
            throw new ArgumentException($"STREAM_END payload must be exactly 24 bytes, got: {payload.Length}");
        }
        var bulkRef = LanUuidCodec.NetworkBytesToBulkRef(payload, 0);
        ulong actualBytes = BinaryPrimitives.ReadUInt64BigEndian(payload.AsSpan(16, 8));
        return new LanRecord.StreamEnd(bulkRef, (long)actualBytes);
    }

    /// <summary>
    /// Writes an encoded LanRecord directly to a stream.
    /// </summary>
    public static async Task WriteRecordAsync(LanRecord record, Stream stream, CancellationToken cancellationToken = default)
    {
        byte[] frame = Encode(record);
        await stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Asynchronously reads a single LanRecord from a stream.
    /// Returns null if stream ended cleanly before any header bytes.
    /// </summary>
    public static async Task<LanRecord?> ReadRecordAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        byte[] header = new byte[HeaderSize];
        int headerRead = 0;
        while (headerRead < HeaderSize)
        {
            int read = await stream.ReadAsync(header.AsMemory(headerRead, HeaderSize - headerRead), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                if (headerRead == 0) return null; // Clean EOF
                throw new EndOfStreamException($"Stream ended prematurely after {headerRead} header bytes");
            }
            headerRead += read;
        }

        byte kindByte = header[0];
        if (!Enum.IsDefined(typeof(LanRecordKind), kindByte))
        {
            throw new ArgumentException($"Unknown record kind: 0x{kindByte:X2}");
        }

        var kind = (LanRecordKind)kindByte;
        uint payloadLength = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(1, 4));

        if (payloadLength > MaxRecordPayloadSize)
        {
            throw new ArgumentException($"Payload length {payloadLength} exceeds maximum {MaxRecordPayloadSize}");
        }

        int minSize = kind.MinPayloadSize();
        int maxSize = kind.MaxPayloadSize();
        if (payloadLength < (uint)minSize || payloadLength > (uint)maxSize)
        {
            throw new ArgumentException($"Payload length {payloadLength} out of bounds for {kind} ({minSize}..{maxSize})");
        }

        byte[] payload = new byte[payloadLength];
        int payloadRead = 0;
        while (payloadRead < (int)payloadLength)
        {
            int read = await stream.ReadAsync(payload.AsMemory(payloadRead, (int)payloadLength - payloadRead), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                throw new EndOfStreamException($"Stream ended prematurely after {payloadRead} payload bytes (expected {payloadLength})");
            }
            payloadRead += read;
        }

        return DecodePayload(kind, payload);
    }

    /// <summary>
    /// Reads the next LanRecord from the stream using pooled buffers for DATA and STREAM_END records.
    /// DATA records are returned as <see cref="LanRecord.OwnedData"/> backed by <paramref name="bufferPool"/>,
    /// avoiding per-chunk heap allocations.
    /// </summary>
    public static async ValueTask<LanRecord?> ReadOwnedRecordAsync(
        Stream stream,
        TransferBufferPool bufferPool,
        BulkRef? expectedBulkRef = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(bufferPool);

        byte[] scratch = ArrayPool<byte>.Shared.Rent(32);
        try
        {
            int headerRead = 0;
            while (headerRead < HeaderSize)
            {
                int read = await stream.ReadAsync(
                    scratch.AsMemory(headerRead, HeaderSize - headerRead),
                    cancellationToken).ConfigureAwait(false);
                if (read == 0)
                {
                    if (headerRead == 0) return null; // Clean EOF
                    throw new EndOfStreamException($"Stream ended prematurely after {headerRead} header bytes");
                }
                headerRead += read;
            }

            byte kindByte = scratch[0];
            if (!Enum.IsDefined(typeof(LanRecordKind), kindByte))
            {
                throw new ArgumentException($"Unknown record kind: 0x{kindByte:X2}");
            }

            var kind = (LanRecordKind)kindByte;
            uint payloadLength = BinaryPrimitives.ReadUInt32BigEndian(scratch.AsSpan(1, 4));

            if (payloadLength > MaxRecordPayloadSize)
            {
                throw new ArgumentException($"Payload length {payloadLength} exceeds maximum {MaxRecordPayloadSize}");
            }

            int minSize = kind.MinPayloadSize();
            int maxSize = kind.MaxPayloadSize();
            if (payloadLength < (uint)minSize || payloadLength > (uint)maxSize)
            {
                throw new ArgumentException($"Payload length {payloadLength} out of bounds for {kind} ({minSize}..{maxSize})");
            }

            if (kind == LanRecordKind.Data)
            {
                await ReadExactAsync(stream, scratch.AsMemory(0, LanUuidCodec.UuidByteLength), cancellationToken).ConfigureAwait(false);
                BulkRef bulkRef = LanUuidCodec.NetworkBytesToBulkRef(
                    scratch.AsSpan(0, LanUuidCodec.UuidByteLength),
                    expectedBulkRef);

                int chunkLength = (int)payloadLength - LanUuidCodec.UuidByteLength;
                OwnedTransferBuffer ownedBuffer = await bufferPool.RentAsync(chunkLength, cancellationToken).ConfigureAwait(false);
                try
                {
                    await ReadExactAsync(stream, ownedBuffer.Memory, cancellationToken).ConfigureAwait(false);
                    return new LanRecord.OwnedData(bulkRef, ownedBuffer);
                }
                catch
                {
                    ownedBuffer.Dispose();
                    throw;
                }
            }

            if (kind == LanRecordKind.StreamEnd)
            {
                await ReadExactAsync(stream, scratch.AsMemory(0, 24), cancellationToken).ConfigureAwait(false);
                BulkRef bulkRef = LanUuidCodec.NetworkBytesToBulkRef(
                    scratch.AsSpan(0, LanUuidCodec.UuidByteLength),
                    expectedBulkRef);
                ulong actualBytes = BinaryPrimitives.ReadUInt64BigEndian(scratch.AsSpan(16, 8));
                return new LanRecord.StreamEnd(bulkRef, (long)actualBytes);
            }

            byte[] payload = new byte[payloadLength];
            await ReadExactAsync(stream, payload, cancellationToken).ConfigureAwait(false);
            return DecodePayload(kind, payload);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(scratch);
        }
    }

    private static async ValueTask ReadExactAsync(
        Stream stream,
        Memory<byte> destination,
        CancellationToken cancellationToken)
    {
        int totalRead = 0;
        while (totalRead < destination.Length)
        {
            int read = await stream.ReadAsync(
                destination.Slice(totalRead),
                cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                throw new EndOfStreamException(
                    $"Stream ended prematurely after {totalRead} payload bytes (expected {destination.Length})");
            }
            totalRead += read;
        }
    }
}
