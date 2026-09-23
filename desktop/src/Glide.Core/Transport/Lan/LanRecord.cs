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
        // Guid string without hyphens: 32 hex chars
        string hex = uuid.ToString("N");
        return Convert.FromHexString(hex);
    }

    public static Guid FromNetworkBytes(byte[] bytes, int offset = 0)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (bytes.Length < offset + UuidByteLength)
        {
            throw new ArgumentException($"Insufficient bytes for UUID: required {UuidByteLength}, available {bytes.Length - offset}", nameof(bytes));
        }

        string hex = Convert.ToHexString(bytes, offset, UuidByteLength).ToLowerInvariant();
        string formatted = $"{hex[..8]}-{hex[8..12]}-{hex[12..16]}-{hex[16..20]}-{hex[20..32]}";
        return Guid.Parse(formatted);
    }

    public static byte[] BulkRefToNetworkBytes(BulkRef bulkRef)
    {
        if (!IdentifierValidation.IsValidUuidV4(bulkRef.Value))
        {
            throw new ArgumentException($"LAN bulkRef must be a canonical UUIDv4, got: '{bulkRef.Value}'", nameof(bulkRef));
        }
        Guid guid = Guid.Parse(bulkRef.Value);
        return ToNetworkBytes(guid);
    }

    public static BulkRef NetworkBytesToBulkRef(byte[] bytes, int offset = 0)
    {
        Guid guid = FromNetworkBytes(bytes, offset);
        string canonical = guid.ToString().ToLowerInvariant();
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
    /// Sent once after all DATA records for an item, including zero-byte files.
    /// Raw payload on wire: exactly 24 bytes (16-byte UUID bulkRef + 8-byte actualBytes unsigned big-endian).
    /// </summary>
    public sealed record StreamEnd(BulkRef BulkRef, long ActualBytes) : LanRecord(LanRecordKind.StreamEnd)
    {
        public BulkRef BulkRef { get; } = BulkRef;
        public long ActualBytes { get; } = ActualBytes;
    }
}
