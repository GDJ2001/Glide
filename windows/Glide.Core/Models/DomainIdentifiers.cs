using System.Text.RegularExpressions;

namespace Glide.Core.Models;

public static class IdentifierValidation
{
    private static readonly Regex UuidV4Regex = new(
        @"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOptions.Compiled);

    private static readonly Regex Sha256Regex = new(
        @"^[0-9a-f]{64}$",
        RegexOptions.Compiled);

    public static bool IsValidUuidV4(string value) =>
        !string.IsNullOrEmpty(value) && UuidV4Regex.IsMatch(value);

    public static bool IsValidSha256(string value) =>
        !string.IsNullOrEmpty(value) && Sha256Regex.IsMatch(value);
}

public readonly record struct SessionId
{
    public string Value { get; }

    public SessionId(string value)
    {
        if (!IdentifierValidation.IsValidUuidV4(value))
        {
            throw new ArgumentException($"SessionId must be a canonical lowercase UUIDv4, got: '{value}'");
        }
        Value = value;
    }

    public override string ToString() => Value;
    public static implicit operator string(SessionId id) => id.Value;
}

public readonly record struct ItemId
{
    public string Value { get; }

    public ItemId(string value)
    {
        if (!IdentifierValidation.IsValidUuidV4(value))
        {
            throw new ArgumentException($"ItemId must be a canonical lowercase UUIDv4, got: '{value}'");
        }
        Value = value;
    }

    public override string ToString() => Value;
    public static implicit operator string(ItemId id) => id.Value;
}

public readonly record struct BatchId
{
    public string Value { get; }

    public BatchId(string value)
    {
        if (!IdentifierValidation.IsValidUuidV4(value))
        {
            throw new ArgumentException($"BatchId must be a canonical lowercase UUIDv4, got: '{value}'");
        }
        Value = value;
    }

    public override string ToString() => Value;
    public static implicit operator string(BatchId id) => id.Value;
}

public readonly record struct MessageId
{
    public string Value { get; }

    public MessageId(string value)
    {
        if (!IdentifierValidation.IsValidUuidV4(value))
        {
            throw new ArgumentException($"MessageId must be a canonical lowercase UUIDv4, got: '{value}'");
        }
        Value = value;
    }

    public override string ToString() => Value;
    public static implicit operator string(MessageId id) => id.Value;
}

public readonly record struct BulkRef
{
    public string Value { get; }

    public BulkRef(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 64)
        {
            throw new ArgumentException($"BulkRef must be non-blank and at most 64 characters, got: '{value}'");
        }
        Value = value;
    }

    public override string ToString() => Value;
    public static implicit operator string(BulkRef r) => r.Value;
}

public readonly record struct Sha256Digest
{
    public string Value { get; }

    public Sha256Digest(string value)
    {
        if (!IdentifierValidation.IsValidSha256(value))
        {
            throw new ArgumentException($"Sha256Digest must be exactly 64 lowercase hexadecimal characters, got: '{value}'");
        }
        Value = value;
    }

    public override string ToString() => Value;
    public static implicit operator string(Sha256Digest d) => d.Value;
}
