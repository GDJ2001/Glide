using System.Buffers.Binary;
using Glide.Core.Models;

namespace Glide.Core.Transport.Lan;

/// <summary>
/// RFC 4122 standard network-byte-order UUID codec for LAN binary records.
/// Encodes MSB (8 bytes big-endian) then LSB (8 bytes big-endian).
/// Does not use Windows GUID mixed-endian layout.
/// </summary>
public static class LanUuidCodec
{
    public const int UuidByteLength = 16;

    public static byte[] ToNetworkBytes(Guid uuid)
    {
        byte[] bytes = new byte[UuidByteLength];
        uuid.TryWriteBytes(bytes, bigEndian: true, out _);
        return bytes;
    }

    public static void WriteNetworkBytes(Guid uuid, Span<byte> destination)
    {
        if (destination.Length < UuidByteLength)
        {
            throw new ArgumentException($"Destination span must be at least {UuidByteLength} bytes.", nameof(destination));
        }
        uuid.TryWriteBytes(destination, bigEndian: true, out _);
    }

    public static Guid FromNetworkBytes(byte[] bytes, int offset = 0)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (offset < 0 || bytes.Length < offset + UuidByteLength)
        {
            throw new ArgumentException($"Insufficient bytes for UUID: required {UuidByteLength}, available {bytes.Length - offset}", nameof(bytes));
        }

        return FromNetworkBytes(bytes.AsSpan(offset, UuidByteLength));
    }

    public static Guid FromNetworkBytes(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length < UuidByteLength)
        {
            throw new ArgumentException($"Insufficient bytes for UUID: required {UuidByteLength}, available {bytes.Length}", nameof(bytes));
        }

        return new Guid(bytes[..UuidByteLength], bigEndian: true);
    }

    public static byte[] BulkRefToNetworkBytes(BulkRef bulkRef)
    {
        byte[] bytes = new byte[UuidByteLength];
        WriteBulkRefNetworkBytes(bulkRef, bytes);
        return bytes;
    }

    public static void WriteBulkRefNetworkBytes(BulkRef bulkRef, Span<byte> destination)
    {
        if (!IdentifierValidation.IsValidUuidV4(bulkRef.Value) ||
            !Guid.TryParseExact(bulkRef.Value, "D", out Guid guid))
        {
            throw new ArgumentException($"LAN bulkRef must be a canonical UUIDv4, got: '{bulkRef.Value}'", nameof(bulkRef));
        }
        WriteNetworkBytes(guid, destination);
    }

    public static BulkRef NetworkBytesToBulkRef(byte[] bytes, int offset = 0)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (offset < 0 || bytes.Length < offset + UuidByteLength)
        {
            throw new ArgumentException($"Insufficient bytes for UUID: required {UuidByteLength}, available {bytes.Length - offset}", nameof(bytes));
        }
        return NetworkBytesToBulkRef(bytes.AsSpan(offset, UuidByteLength));
    }

    public static BulkRef NetworkBytesToBulkRef(ReadOnlySpan<byte> bytes, BulkRef? expectedBulkRef = null)
    {
        if (bytes.Length < UuidByteLength)
        {
            throw new ArgumentException($"Insufficient bytes for UUID: required {UuidByteLength}, available {bytes.Length}", nameof(bytes));
        }

        ReadOnlySpan<byte> uuidSlice = bytes[..UuidByteLength];

        // Check RFC 4122 UUIDv4 version (byte 6 high nibble == 4) and variant (byte 8 high 2 bits == 0b10)
        if ((uuidSlice[6] >> 4) != 4 || (uuidSlice[8] & 0xC0) != 0x80)
        {
            Guid badGuid = FromNetworkBytes(uuidSlice);
            throw new ArgumentException($"Decoded UUID from network bytes is not a canonical UUIDv4: '{badGuid:D}'");
        }

        if (expectedBulkRef is { } expected &&
            Guid.TryParseExact(expected.Value, "D", out Guid expectedGuid))
        {
            Span<byte> expectedBytes = stackalloc byte[UuidByteLength];
            expectedGuid.TryWriteBytes(expectedBytes, bigEndian: true, out _);
            if (uuidSlice.SequenceEqual(expectedBytes))
            {
                return expected;
            }
        }

        Guid guid = FromNetworkBytes(uuidSlice);
        string canonical = guid.ToString("D");
        if (!IdentifierValidation.IsValidUuidV4(canonical))
        {
            throw new ArgumentException($"Decoded UUID from network bytes is not a canonical UUIDv4: '{canonical}'");
        }
        return new BulkRef(canonical);
    }
}

/// <summary>
/// Sealed hierarchy of typed records transmitted over the LAN TLS connection (LAN-007, Section 6).
/// </summary>
public abstract record LanRecord(LanRecordKind Kind)
{
    /// <summary>
    /// Client's first record after TLS handshake validation. Contains UTF-8 strict JSON of LanBootstrapMessage.
    /// </summary>
    public sealed record Bootstrap(byte[] JsonBytes) : LanRecord(LanRecordKind.Bootstrap)
    {
        public byte[] JsonBytes { get; } = JsonBytes ?? throw new ArgumentNullException(nameof(JsonBytes));

        public bool Equals(Bootstrap? other)
        {
            if (other is null) return false;
            if (ReferenceEquals(this, other)) return true;
            return JsonBytes.AsSpan().SequenceEqual(other.JsonBytes);
        }

        public override int GetHashCode()
        {
            var hash = new HashCode();
            hash.AddBytes(JsonBytes);
            return hash.ToHashCode();
        }
    }

    /// <summary>
    /// Server's one bootstrap response. Contains UTF-8 strict JSON of LanBootstrapResult.
    /// </summary>
    public sealed record BootstrapResult(byte[] JsonBytes) : LanRecord(LanRecordKind.BootstrapResult)
    {
        public byte[] JsonBytes { get; } = JsonBytes ?? throw new ArgumentNullException(nameof(JsonBytes));

        public bool Equals(BootstrapResult? other)
        {
            if (other is null) return false;
            if (ReferenceEquals(this, other)) return true;
            return JsonBytes.AsSpan().SequenceEqual(other.JsonBytes);
        }

        public override int GetHashCode()
        {
            var hash = new HashCode();
            hash.AddBytes(JsonBytes);
            return hash.ToHashCode();
        }
    }

    /// <summary>
    /// Existing ProtocolCodec UTF-8 envelope for session control messages.
    /// </summary>
    public sealed record Control(byte[] Payload) : LanRecord(LanRecordKind.Control)
    {
        public byte[] Payload { get; } = Payload ?? throw new ArgumentNullException(nameof(Payload));

        public bool Equals(Control? other)
        {
            if (other is null) return false;
            if (ReferenceEquals(this, other)) return true;
            return Payload.AsSpan().SequenceEqual(other.Payload);
        }

        public override int GetHashCode()
        {
            var hash = new HashCode();
            hash.AddBytes(Payload);
            return hash.ToHashCode();
        }
    }

    /// <summary>
    /// Binary data chunk for an expected item.
    /// Raw payload on wire: 16 bytes network-order UUID bulkRef + 1..65,520 bytes file data.
    /// </summary>
    public sealed record Data(BulkRef BulkRef, byte[] Chunk) : LanRecord(LanRecordKind.Data)
    {
        public BulkRef BulkRef { get; } = BulkRef;
        public byte[] Chunk { get; } = Chunk ?? throw new ArgumentNullException(nameof(Chunk));

        public bool Equals(Data? other)
        {
            if (other is null) return false;
            if (ReferenceEquals(this, other)) return true;
            return BulkRef == other.BulkRef && Chunk.AsSpan().SequenceEqual(other.Chunk);
        }

        public override int GetHashCode()
        {
            var hash = new HashCode();
            hash.Add(BulkRef);
            hash.AddBytes(Chunk);
            return hash.ToHashCode();
        }
    }

    /// <summary>
    /// Pooled binary data chunk whose payload buffer is explicitly owned and returned to the pool on Dispose.
    /// </summary>
    public sealed record OwnedData(BulkRef BulkRef, OwnedTransferBuffer Buffer) : LanRecord(LanRecordKind.Data), IDisposable
    {
        public BulkRef BulkRef { get; } = BulkRef;
        public OwnedTransferBuffer Buffer { get; } = Buffer ?? throw new ArgumentNullException(nameof(Buffer));

        public ReadOnlyMemory<byte> ChunkMemory => Buffer.ReadOnlyMemory;
        public ReadOnlySpan<byte> ChunkSpan => Buffer.Span;
        public int ChunkLength => Buffer.Length;

        public void Dispose() => Buffer.Dispose();
    }

    /// <summary>
    /// Sent once after all DATA records for an item, including zero-byte files.
    /// Raw payload on wire: exactly 24 bytes (16-byte UUID bulkRef + 8-byte actualBytes unsigned big-endian).
    /// </summary>
    public sealed record StreamEnd(BulkRef BulkRef, long ActualBytes) : LanRecord(LanRecordKind.StreamEnd)
    {
        public BulkRef BulkRef { get; } = BulkRef;
        public long ActualBytes { get; } = ActualBytes;
    }
}
