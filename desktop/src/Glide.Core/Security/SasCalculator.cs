using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Glide.Core.Security;

public static class SasCalculator
{
    public static int ExtractDecimal6(byte[] digest)
    {
        if (digest == null || digest.Length < 4)
        {
            throw new ArgumentException("Digest must be at least 4 bytes to extract 6-digit decimal SAS.", nameof(digest));
        }

        uint val = BinaryPrimitives.ReadUInt32BigEndian(digest.AsSpan(0, 4));
        return (int)((val % 900_000) + 100_000);
    }

    public static int CalculateSas(byte[] clientPublicKey, byte[] serverPublicKey, byte[] sessionNonce)
    {
        if (clientPublicKey == null || clientPublicKey.Length == 0)
            throw new ArgumentException("Client public key must be non-empty.", nameof(clientPublicKey));
        if (serverPublicKey == null || serverPublicKey.Length == 0)
            throw new ArgumentException("Server public key must be non-empty.", nameof(serverPublicKey));
        if (sessionNonce == null || sessionNonce.Length == 0)
            throw new ArgumentException("Session nonce must be non-empty.", nameof(sessionNonce));

        byte[] combined = new byte[clientPublicKey.Length + serverPublicKey.Length + sessionNonce.Length];
        Buffer.BlockCopy(clientPublicKey, 0, combined, 0, clientPublicKey.Length);
        Buffer.BlockCopy(serverPublicKey, 0, combined, clientPublicKey.Length, serverPublicKey.Length);
        Buffer.BlockCopy(sessionNonce, 0, combined, clientPublicKey.Length + serverPublicKey.Length, sessionNonce.Length);

        byte[] digest = SHA256.HashData(combined);
        return ExtractDecimal6(digest);
    }
}
