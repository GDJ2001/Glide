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
    public const int MaxRecordPayloadSize = 65536; // 64 KiB ceiling across all record kinds

    /// <summary>
    /// Encodes a LanRecord into its on-wire 5-byte header + payload format.
    /// </summary>
    public static byte[] Encode(LanRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);

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

    private static byte[] EncodeDataPayload(LanRecord.Data data)
    {
        byte[] uuidBytes = LanUuidCodec.BulkRefToNetworkBytes(data.BulkRef);
        byte[] payload = new byte[LanUuidCodec.UuidByteLength + data.Chunk.Length];
        Buffer.BlockCopy(uuidBytes, 0, payload, 0, LanUuidCodec.UuidByteLength);
        Buffer.BlockCopy(data.Chunk, 0, payload, LanUuidCodec.UuidByteLength, data.Chunk.Length);
        return payload;
    }

    private static byte[] EncodeStreamEndPayload(LanRecord.StreamEnd streamEnd)
    {
        byte[] uuidBytes = LanUuidCodec.BulkRefToNetworkBytes(streamEnd.BulkRef);
        byte[] payload = new byte[24];
        Buffer.BlockCopy(uuidBytes, 0, payload, 0, LanUuidCodec.UuidByteLength);
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
}
