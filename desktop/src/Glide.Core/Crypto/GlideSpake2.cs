using System.Buffers.Binary;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Glide.Core.Codec;

namespace Glide.Core.Crypto;

public enum Spake2Role
{
    Alice = 0,
    Bob = 1
}

/// <summary>
/// Managed P/Invoke wrapper around the pinned BoringSSL SPAKE2 C ABI (`glide_spake2`,
/// commit 3fed4f207543470b3d639db6ed40737e30308b51) and Profile 2 key confirmation
/// and transcript construction.
/// </summary>
public sealed class GlideSpake2Session : IDisposable
{
    public const string PinnedBoringSslCommit = "3fed4f207543470b3d639db6ed40737e30308b51";
    public const int MessageSize = 32;
    public const int SharedSecretSize = 64;

    private const string LibraryName = "glide_spake2";

    static GlideSpake2Session()
    {
        NativeLibrary.SetDllImportResolver(typeof(GlideSpake2Session).Assembly, ResolveNativeLibrary);
    }

    private static nint ResolveNativeLibrary(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
    {
        if (!string.Equals(libraryName, LibraryName, StringComparison.Ordinal))
        {
            return nint.Zero;
        }

        string baseDir = AppContext.BaseDirectory;
        string[] candidates;
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            candidates =
            [
                Path.Combine(baseDir, "glide_spake2.dll"),
                Path.Combine(baseDir, "runtimes", "win-x64", "native", "glide_spake2.dll")
            ];
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            candidates =
            [
                Path.Combine(baseDir, "libglide_spake2.so"),
                Path.Combine(baseDir, "runtimes", "linux-x64", "native", "libglide_spake2.so")
            ];
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
        {
            candidates =
            [
                Path.Combine(baseDir, "libglide_spake2.dylib"),
                Path.Combine(baseDir, "runtimes", "osx", "native", "libglide_spake2.dylib")
            ];
        }
        else
        {
            candidates = [];
        }

        foreach (string path in candidates)
        {
            if (File.Exists(path) && NativeLibrary.TryLoad(path, out nint handle))
            {
                return handle;
            }
        }

        return nint.Zero;
    }

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern nint glide_spake2_boringssl_commit();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern unsafe nint glide_spake2_ctx_new(
        int role,
        byte* myName,
        nuint myNameLen,
        byte* theirName,
        nuint theirNameLen);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern void glide_spake2_ctx_free(nint ctx);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern unsafe int glide_spake2_generate_msg(
        nint ctx,
        byte* outMsg,
        out nuint outMsgLen,
        nuint maxOutMsgLen,
        byte* password,
        nuint passwordLen);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern unsafe int glide_spake2_generate_msg_deterministic_for_test(
        nint ctx,
        byte* outMsg,
        out nuint outMsgLen,
        nuint maxOutMsgLen,
        byte* password,
        nuint passwordLen,
        byte* entropy64);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern unsafe int glide_spake2_process_msg(
        nint ctx,
        byte* outKey,
        out nuint outKeyLen,
        nuint maxOutKeyLen,
        byte* theirMsg,
        nuint theirMsgLen);

    private nint _ctx;
    private bool _disposed;

    public static string GetBoringSslCommit()
    {
        nint ptr = glide_spake2_boringssl_commit();
        return Marshal.PtrToStringUTF8(ptr) ?? string.Empty;
    }

    public unsafe GlideSpake2Session(
        Spake2Role role,
        ReadOnlySpan<byte> myName,
        ReadOnlySpan<byte> theirName)
    {
        fixed (byte* myPtr = myName)
        fixed (byte* theirPtr = theirName)
        {
            _ctx = glide_spake2_ctx_new(
                (int)role,
                myPtr,
                (nuint)myName.Length,
                theirPtr,
                (nuint)theirName.Length);
        }

        if (_ctx == nint.Zero)
        {
            throw new CryptographicException("Failed to initialize BoringSSL SPAKE2 context.");
        }
    }

    public unsafe byte[] GenerateMessage(ReadOnlySpan<byte> password)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (password.IsEmpty)
        {
            throw new ArgumentException("SPAKE2 password must not be empty.", nameof(password));
        }

        byte[] outMsg = new byte[MessageSize];
        fixed (byte* outPtr = outMsg)
        fixed (byte* pwdPtr = password)
        {
            int rc = glide_spake2_generate_msg(
                _ctx,
                outPtr,
                out nuint outLen,
                (nuint)outMsg.Length,
                pwdPtr,
                (nuint)password.Length);
            if (rc != 1 || outLen != MessageSize)
            {
                throw new CryptographicException("BoringSSL SPAKE2_generate_msg failed.");
            }
        }

        return outMsg;
    }

    public unsafe byte[] GenerateMessageDeterministicForTest(
        ReadOnlySpan<byte> password,
        ReadOnlySpan<byte> entropy64)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (entropy64.Length != 64)
        {
            throw new ArgumentException("Deterministic test entropy must be 64 bytes.", nameof(entropy64));
        }

        byte[] outMsg = new byte[MessageSize];
        fixed (byte* outPtr = outMsg)
        fixed (byte* pwdPtr = password)
        fixed (byte* entPtr = entropy64)
        {
            int rc = glide_spake2_generate_msg_deterministic_for_test(
                _ctx,
                outPtr,
                out nuint outLen,
                (nuint)outMsg.Length,
                pwdPtr,
                (nuint)password.Length,
                entPtr);
            if (rc != 1 || outLen != MessageSize)
            {
                throw new CryptographicException("BoringSSL deterministic SPAKE2_generate_msg failed.");
            }
        }

        return outMsg;
    }

    public unsafe byte[] ProcessPeerMessage(ReadOnlySpan<byte> peerMessage)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (peerMessage.Length != MessageSize)
        {
            throw new CryptographicException($"SPAKE2 peer message must be {MessageSize} bytes.");
        }

        byte[] outKey = new byte[SharedSecretSize];
        fixed (byte* outPtr = outKey)
        fixed (byte* peerPtr = peerMessage)
        {
            int rc = glide_spake2_process_msg(
                _ctx,
                outPtr,
                out nuint outLen,
                (nuint)outKey.Length,
                peerPtr,
                (nuint)peerMessage.Length);
            if (rc != 1 || outLen != SharedSecretSize)
            {
                CryptographicOperations.ZeroMemory(outKey);
                throw new CryptographicException("BoringSSL SPAKE2_process_msg rejected peer point or state.");
            }
        }

        return outKey;
    }

    /// <summary>
    /// Derives directional 32-byte HMAC-SHA256 confirmation keys from a 64-byte shared secret
    /// (either BoringSSL SPAKE2 output or QR v2 token HKDF material) and the 32-byte SHA-256
    /// transcript hash (`GLIDE-PAIR-V2`).
    /// </summary>
    public static (byte[] ConfirmI2RKey, byte[] ConfirmR2IKey) DeriveConfirmationKeys(
        ReadOnlySpan<byte> sharedSecret,
        ReadOnlySpan<byte> transcriptHash32)
    {
        if (sharedSecret.IsEmpty)
        {
            throw new ArgumentException("Shared secret must not be empty.", nameof(sharedSecret));
        }
        if (transcriptHash32.Length != 32)
        {
            throw new ArgumentException("Transcript hash salt must be 32 bytes.", nameof(transcriptHash32));
        }

        byte[] prk = new byte[32];
        HKDF.Extract(HashAlgorithmName.SHA256, sharedSecret, transcriptHash32, prk);
        try
        {
            byte[] i2r = new byte[32];
            byte[] r2i = new byte[32];
            HKDF.Expand(HashAlgorithmName.SHA256, prk, i2r, "glide-pair-v2-confirm-i2r"u8);
            HKDF.Expand(HashAlgorithmName.SHA256, prk, r2i, "glide-pair-v2-confirm-r2i"u8);
            return (i2r, r2i);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(prk);
        }
    }

    public static byte[] ComputeConfirmationMac(
        ReadOnlySpan<byte> confirmationKey32,
        ReadOnlySpan<byte> transcriptHash32)
    {
        if (confirmationKey32.Length != 32)
        {
            throw new ArgumentException("Confirmation key must be 32 bytes.", nameof(confirmationKey32));
        }
        if (transcriptHash32.Length != 32)
        {
            throw new ArgumentException("Transcript hash must be 32 bytes.", nameof(transcriptHash32));
        }

        return HMACSHA256.HashData(confirmationKey32, transcriptHash32);
    }

    /// <summary>
    /// Builds the canonical binary `GLIDE-PAIR-V2` transcript defined in
    /// `doc/protocol/PROFILE_2_PAIRING_AND_RECONNECT.md` Section 4.3.
    /// </summary>
    public static byte[] BuildPairV2TranscriptBytes(
        string sessionId,
        ReadOnlySpan<byte> initiatorNonce32,
        ReadOnlySpan<byte> responderNonce32,
        ReadOnlySpan<byte> initiatorIdentitySpki,
        ReadOnlySpan<byte> responderIdentitySpki,
        string advertisedPresenceIdHex,
        string serverTlsSpkiSha256Hex,
        string purpose = "PAIR_ONLY",
        string method = "spake2-pin-v1")
    {
        if (initiatorNonce32.Length != 32)
            throw new ArgumentException("Initiator nonce must be 32 bytes.", nameof(initiatorNonce32));
        if (responderNonce32.Length != 32)
            throw new ArgumentException("Responder nonce must be 32 bytes.", nameof(responderNonce32));
        if (initiatorIdentitySpki.IsEmpty)
            throw new ArgumentException("Initiator identity SPKI is required.", nameof(initiatorIdentitySpki));
        if (responderIdentitySpki.IsEmpty)
            throw new ArgumentException("Responder identity SPKI is required.", nameof(responderIdentitySpki));

        string initFp = Convert.ToHexStringLower(SHA256.HashData(initiatorIdentitySpki));
        string respFp = Convert.ToHexStringLower(SHA256.HashData(responderIdentitySpki));
        string presence = string.IsNullOrWhiteSpace(advertisedPresenceIdHex)
            ? new string('0', 32)
            : advertisedPresenceIdHex.ToLowerInvariant();

        return BuildPairV2TranscriptBytes(
            method: method,
            sessionId: sessionId,
            initiatorNonceHex: Convert.ToHexStringLower(initiatorNonce32),
            responderNonceHex: Convert.ToHexStringLower(responderNonce32),
            initiatorFingerprintHex: initFp,
            initiatorPublicKeyDerHex: Convert.ToHexStringLower(initiatorIdentitySpki),
            responderFingerprintHex: respFp,
            responderPublicKeyDerHex: Convert.ToHexStringLower(responderIdentitySpki),
            responderPresenceHex: presence,
            tlsServerSpkiSha256Hex: serverTlsSpkiSha256Hex,
            purpose: purpose);
    }

    public static byte[] BuildPairV2TranscriptBytes(
        string method,
        string sessionId,
        string initiatorNonceHex,
        string responderNonceHex,
        string initiatorFingerprintHex,
        string initiatorPublicKeyDerHex,
        string responderFingerprintHex,
        string responderPublicKeyDerHex,
        string responderPresenceHex,
        string tlsServerSpkiSha256Hex,
        string purpose = "PAIR_ONLY")
    {
        using var ms = new MemoryStream(640);

        static void WriteString(MemoryStream stream, string value)
        {
            byte[] data = Encoding.UTF8.GetBytes(value);
            Span<byte> lenBuf = stackalloc byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(lenBuf, (uint)data.Length);
            stream.Write(lenBuf);
            stream.Write(data);
        }

        ms.Write("GLIDE-PAIR-V2"u8);
        ms.WriteByte(0);
        WriteString(ms, method);
        WriteString(ms, sessionId.ToLowerInvariant());
        WriteString(ms, "initiator");
        WriteString(ms, "responder");
        WriteString(ms, initiatorNonceHex.ToLowerInvariant());
        WriteString(ms, responderNonceHex.ToLowerInvariant());
        WriteString(ms, initiatorFingerprintHex.ToLowerInvariant());
        WriteString(ms, initiatorPublicKeyDerHex.ToLowerInvariant());
        WriteString(ms, responderFingerprintHex.ToLowerInvariant());
        WriteString(ms, responderPublicKeyDerHex.ToLowerInvariant());
        WriteString(ms, (responderPresenceHex ?? string.Empty).ToLowerInvariant());
        WriteString(ms, tlsServerSpkiSha256Hex.ToLowerInvariant());
        WriteString(ms, purpose);

        return ms.ToArray();
    }

    public static void BuildRoleIdentifiers(
        string sessionId,
        ReadOnlySpan<byte> initiatorNonce32,
        ReadOnlySpan<byte> responderNonce32,
        out byte[] aliceName,
        out byte[] bobName)
    {
        byte[] prefixI = Encoding.ASCII.GetBytes($"glide-pair-v2:initiator:{sessionId.ToLowerInvariant()}:");
        aliceName = new byte[prefixI.Length + initiatorNonce32.Length];
        Buffer.BlockCopy(prefixI, 0, aliceName, 0, prefixI.Length);
        initiatorNonce32.CopyTo(aliceName.AsSpan(prefixI.Length));

        byte[] prefixR = Encoding.ASCII.GetBytes($"glide-pair-v2:responder:{sessionId.ToLowerInvariant()}:");
        bobName = new byte[prefixR.Length + responderNonce32.Length];
        Buffer.BlockCopy(prefixR, 0, bobName, 0, prefixR.Length);
        responderNonce32.CopyTo(bobName.AsSpan(prefixR.Length));
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_ctx != nint.Zero)
        {
            glide_spake2_ctx_free(_ctx);
            _ctx = nint.Zero;
        }
    }
}
