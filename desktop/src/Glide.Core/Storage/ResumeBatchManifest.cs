using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Glide.Core.Codec;
using Glide.Core.Models;

namespace Glide.Core.Storage;

public sealed record ResumeBatchManifest(
    string SessionId,
    string BatchId,
    string SenderFingerprint,
    string ReceiverFingerprint,
    IReadOnlyList<ResumeBatchManifest.Item> Items)
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public sealed record Item(string Id, string Name, string? RelativePath, long SizeBytes,
        int ChunkSize, string? RootHash, string Digest);

    public string Digest()
    {
        Validate();
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        void Number(int value)
        {
            Span<byte> bytes = stackalloc byte[sizeof(int)];
            BinaryPrimitives.WriteInt32BigEndian(bytes, value);
            hash.AppendData(bytes);
        }
        void Field(string? value)
        {
            byte[]? bytes = value is null ? null : StrictUtf8.GetBytes(value);
            Number(bytes?.Length ?? -1);
            if (bytes is not null)
                hash.AppendData(bytes);
        }

        Field("glide/resume-batch-v1");
        Field(SessionId);
        Field(BatchId);
        Field(SenderFingerprint);
        Field(ReceiverFingerprint);
        Number(Items.Count);
        Span<byte> size = stackalloc byte[sizeof(long)];
        foreach (var item in Items)
        {
            Field(item.Id);
            Field(item.Name);
            Field(item.RelativePath);
            BinaryPrimitives.WriteInt64BigEndian(size, item.SizeBytes);
            hash.AppendData(size);
            Number(item.ChunkSize);
            Field(item.RootHash);
            Field(item.Digest);
        }
        return Convert.ToHexStringLower(hash.GetHashAndReset());
    }

    private void Validate()
    {
        if (!IdentifierValidation.IsValidUuidV4(SessionId) || !IdentifierValidation.IsValidUuidV4(BatchId)
            || !IdentifierValidation.IsValidSha256(SenderFingerprint)
            || !IdentifierValidation.IsValidSha256(ReceiverFingerprint)
            || SenderFingerprint == ReceiverFingerprint || Items.Count is < 1 or > 1000)
            throw new ArgumentException("Invalid resume batch identity or item count.");

        var seen = new HashSet<string>(StringComparer.Ordinal);
        long totalBytes = 0;
        foreach (var item in Items)
        {
            bool validChunk = item.SizeBytes == 0
                ? item.ChunkSize == 0 && item.RootHash is null
                : item.ChunkSize is >= 1 and <= 16_777_216 && item.RootHash is not null
                    && IdentifierValidation.IsValidSha256(item.RootHash);
            if (!IdentifierValidation.IsValidUuidV4(item.Id) || !seen.Add(item.Id)
                || string.IsNullOrWhiteSpace(item.Name) || StrictUtf8.GetByteCount(item.Name) > 1024
                || (item.RelativePath is not null && (string.IsNullOrWhiteSpace(item.RelativePath)
                    || item.RelativePath.Length > 1024))
                || item.SizeBytes is < 0 or > ProtocolCatalog.MaxItemBytes
                || !validChunk || !IdentifierValidation.IsValidSha256(item.Digest))
                throw new ArgumentException("Invalid resume batch item.");
            totalBytes += item.SizeBytes;
        }
        if (totalBytes > ProtocolCatalog.MaxBatchBytes)
            throw new ArgumentException("Resume batch exceeds the byte limit.");
    }
}
