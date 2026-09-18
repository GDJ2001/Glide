using System.Security.Cryptography;
using Glide.Core.Models;

namespace Glide.Core.Security;

/// <summary>
/// Incremental SHA-256 accumulator for bounded-memory transfer paths.
/// The accumulator never buffers the full payload and tracks byte counts with Int64.
/// </summary>
public sealed class StreamingSha256 : IDisposable
{
    private readonly IncrementalHash _hash =
        IncrementalHash.CreateHash(HashAlgorithmName.SHA256);

    private bool _completed;
    private bool _disposed;

    public long BytesProcessed { get; private set; }

    public void Append(ReadOnlySpan<byte> data)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_completed)
        {
            throw new InvalidOperationException(
                "Cannot append data after the SHA-256 digest has been completed.");
        }

        BytesProcessed = checked(BytesProcessed + data.Length);
        if (!data.IsEmpty)
        {
            _hash.AppendData(data);
        }
    }

    public void Append(ReadOnlyMemory<byte> data) => Append(data.Span);

    public Sha256Digest Complete()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_completed)
        {
            throw new InvalidOperationException(
                "SHA-256 digest has already been completed.");
        }

        _completed = true;
        byte[] digest = _hash.GetHashAndReset();
        return new Sha256Digest(Convert.ToHexStringLower(digest));
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _hash.Dispose();
    }
}
