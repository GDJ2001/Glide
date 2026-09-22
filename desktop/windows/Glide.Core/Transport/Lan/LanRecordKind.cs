namespace Glide.Core.Transport.Lan;

/// <summary>
/// Record types defined for Glide LAN Transport Profile 1 (LAN-007, Section 6, ADR-0018).
/// Framing format: 5-byte header:
/// - 1 byte kind (UByte)
/// - 4 bytes payloadLength (UInt32 big-endian)
/// All bounds are strictly enforced before allocating payload buffers.
/// </summary>
public enum LanRecordKind : byte
{
    Bootstrap = 0x01,
    BootstrapResult = 0x02,
    Control = 0x10,
    Data = 0x20,
    StreamEnd = 0x21
}

public static class LanRecordKindExtensions
{
    public static int MinPayloadSize(this LanRecordKind kind) => kind switch
    {
        LanRecordKind.Bootstrap => 1,
        LanRecordKind.BootstrapResult => 1,
        LanRecordKind.Control => 1,
        LanRecordKind.Data => 17,
        LanRecordKind.StreamEnd => 24,
        _ => throw new ArgumentOutOfRangeException(nameof(kind), kind, null)
    };

    public static int MaxPayloadSize(this LanRecordKind kind) => kind switch
    {
        LanRecordKind.Bootstrap => 2048,
        LanRecordKind.BootstrapResult => 512,
        LanRecordKind.Control => 16384,
        LanRecordKind.Data => 65536,
        LanRecordKind.StreamEnd => 24,
        _ => throw new ArgumentOutOfRangeException(nameof(kind), kind, null)
    };
}
