using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Glide.Core.Models;

namespace Glide.Core.Security;

/// <summary>
/// Canonical binary transcripts for Glide identity-v1 and resume-v1 signatures.
///
/// No JSON serializer output is ever signed. Every string is encoded as a uint32 big-endian
/// UTF-8 length followed by its bytes; numeric values are big-endian. The exact format is shared
/// with transfer-core and locked by protocol/fixtures/identity_resume_v1_vectors.json.
/// </summary>
public static class IdentityResumeTranscript
{
    public const string IdentityProfile = "identity-v1";
    public const string ResumeProfile = "resume-v1";
    public const string ChannelBindingScheme = "tls-server-spki-sha256-v1";
    
    private const string SignDomain = "GLIDE-SIGN-V1";
    private const string CapabilityDomain = "GLIDE-CAPS-V1";
    private const byte PurposeIdentity = 1;
    private const byte PurposeResume = 2;

    private static readonly Regex CapabilityRegex =
        new("^[a-z0-9][a-z0-9-]{0,63}$",
            RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex LowerHex64Regex =
        new("^[0-9a-f]{64}$",
            RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static byte[] CapabilityCanonicalBytes(
        IEnumerable<string> capabilities)
    {
        ArgumentNullException.ThrowIfNull(capabilities);

        string[] normalized = capabilities
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static value => value, StringComparer.Ordinal)
            .ToArray();

        if (normalized.Any(static value => !CapabilityRegex.IsMatch(value)))
        {
            throw new ArgumentException(
                "Capabilities must be lowercase ASCII tokens of at most 64 characters.",
                nameof(capabilities));
        }

        using var stream = new MemoryStream();
        WriteDomain(stream, CapabilityDomain);
        WriteUInt32(stream, checked((uint)normalized.Length));
        foreach (string capability in normalized)
        {
            WriteString(stream, capability);
        }

        return stream.ToArray();
    }

    public static string CapabilityHashHex(
        IEnumerable<string> capabilities) =>
        Convert.ToHexStringLower(
            SHA256.HashData(
                CapabilityCanonicalBytes(capabilities)));

    public static byte[] IdentityProofBytes(
        string activeSessionId,
        string challengeMessageId,
        string signerRole,
        string verifierRole,
        string signerFingerprintHex,
        string verifierFingerprintHex,
        string challengeNonceHex,
        string channelBindingHex,
        string capabilityHashHex)
    {
        RequireUuid(activeSessionId, nameof(activeSessionId));
        RequireUuid(challengeMessageId, nameof(challengeMessageId));
        RequireOppositeRoles(signerRole, verifierRole);
        RequireLowerHex64(signerFingerprintHex, nameof(signerFingerprintHex));
        RequireLowerHex64(verifierFingerprintHex, nameof(verifierFingerprintHex));
        RequireLowerHex64(challengeNonceHex, nameof(challengeNonceHex));
        RequireLowerHex64(channelBindingHex, nameof(channelBindingHex));
        if (channelBindingHex.All(static ch => ch == '0'))
        {
            throw new ArgumentException(
                "channelBindingHex cannot be an all-zero synthetic binding.",
                nameof(channelBindingHex));
        }
        RequireLowerHex64(capabilityHashHex, nameof(capabilityHashHex));

        using var stream = new MemoryStream();
        WriteDomain(stream, SignDomain);
        stream.WriteByte(PurposeIdentity);
        WriteString(stream, IdentityProfile);
        WriteString(stream, ChannelBindingScheme);
        WriteString(stream, activeSessionId);
        WriteString(stream, challengeMessageId);
        WriteString(stream, signerRole);
        WriteString(stream, verifierRole);
        WriteString(stream, signerFingerprintHex);
        WriteString(stream, verifierFingerprintHex);
        WriteString(stream, challengeNonceHex);
        WriteString(stream, channelBindingHex);
        WriteString(stream, capabilityHashHex);
        return stream.ToArray();
    }

    public static byte[] ResumeRequestBytes(
        string activeSessionId,
        string originalSessionId,
        string itemId,
        string requesterRole,
        string responderRole,
        string requesterFingerprintHex,
        string responderFingerprintHex,
        string sessionNonceHex,
        string channelBindingHex,
        string capabilityHashHex,
        int chunkSize,
        long totalSizeBytes,
        long verifiedChunkCount,
        long contiguousByteOffset,
        string chunkRootHashHex)
    {
        RequireUuid(activeSessionId, nameof(activeSessionId));
        RequireUuid(originalSessionId, nameof(originalSessionId));
        RequireUuid(itemId, nameof(itemId));
        RequireOppositeRoles(requesterRole, responderRole);
        if (requesterRole != "receiver" || responderRole != "sender")
        {
            throw new ArgumentException(
                "resume-v1 request roles must be receiver -> sender.");
        }
        RequireLowerHex64(requesterFingerprintHex, nameof(requesterFingerprintHex));
        RequireLowerHex64(responderFingerprintHex, nameof(responderFingerprintHex));
        RequireLowerHex64(sessionNonceHex, nameof(sessionNonceHex));
        RequireLowerHex64(channelBindingHex, nameof(channelBindingHex));
        if (channelBindingHex.All(static ch => ch == '0'))
        {
            throw new ArgumentException(
                "channelBindingHex cannot be an all-zero synthetic binding.",
                nameof(channelBindingHex));
        }
        RequireLowerHex64(capabilityHashHex, nameof(capabilityHashHex));
        RequireLowerHex64(chunkRootHashHex, nameof(chunkRootHashHex));

        if (chunkSize <= 0) throw new ArgumentOutOfRangeException(nameof(chunkSize));
        if (totalSizeBytes < 0) throw new ArgumentOutOfRangeException(nameof(totalSizeBytes));
        if (verifiedChunkCount < 0) throw new ArgumentOutOfRangeException(nameof(verifiedChunkCount));
        if (contiguousByteOffset < 0 || contiguousByteOffset > totalSizeBytes)
        {
            throw new ArgumentOutOfRangeException(nameof(contiguousByteOffset));
        }

        if (contiguousByteOffset % chunkSize != 0 &&
            contiguousByteOffset != totalSizeBytes)
        {
            throw new ArgumentException(
                "contiguousByteOffset must align with chunkSize or equal totalSizeBytes.",
                nameof(contiguousByteOffset));
        }

        long expectedChunkCount =
            contiguousByteOffset == totalSizeBytes &&
            totalSizeBytes % chunkSize != 0
                ? checked((totalSizeBytes + chunkSize - 1L) / chunkSize)
                : contiguousByteOffset / chunkSize;

        if (verifiedChunkCount != expectedChunkCount)
        {
            throw new ArgumentException(
                "verifiedChunkCount does not match contiguousByteOffset/chunkSize.",
                nameof(verifiedChunkCount));
        }

        using var stream = new MemoryStream();
        WriteDomain(stream, SignDomain);
        stream.WriteByte(PurposeResume);
        WriteString(stream, ResumeProfile);
        WriteString(stream, ChannelBindingScheme);
        WriteString(stream, activeSessionId);
        WriteString(stream, originalSessionId);
        WriteString(stream, itemId);
        WriteString(stream, requesterRole);
        WriteString(stream, responderRole);
        WriteString(stream, requesterFingerprintHex);
        WriteString(stream, responderFingerprintHex);
        WriteString(stream, sessionNonceHex);
        WriteString(stream, channelBindingHex);
        WriteString(stream, capabilityHashHex);
        WriteUInt32(stream, checked((uint)chunkSize));
        WriteUInt64(stream, checked((ulong)totalSizeBytes));
        WriteUInt64(stream, checked((ulong)verifiedChunkCount));
        WriteUInt64(stream, checked((ulong)contiguousByteOffset));
        WriteString(stream, chunkRootHashHex);
        return stream.ToArray();
    }

    public static string Sha256Hex(ReadOnlySpan<byte> bytes) =>
        Convert.ToHexStringLower(SHA256.HashData(bytes));

    private static void WriteDomain(Stream stream, string domain)
    {
        stream.Write(Encoding.ASCII.GetBytes(domain));
        stream.WriteByte(0);
    }

    private static void WriteString(Stream stream, string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        WriteUInt32(stream, checked((uint)bytes.Length));
        stream.Write(bytes);
    }

    private static void WriteUInt32(Stream stream, uint value)
    {
        Span<byte> buffer = stackalloc byte[sizeof(uint)];
        BinaryPrimitives.WriteUInt32BigEndian(buffer, value);
        stream.Write(buffer);
    }

    private static void WriteUInt64(Stream stream, ulong value)
    {
        Span<byte> buffer = stackalloc byte[sizeof(ulong)];
        BinaryPrimitives.WriteUInt64BigEndian(buffer, value);
        stream.Write(buffer);
    }

    private static void RequireUuid(string value, string name)
    {
        if (!IdentifierValidation.IsValidUuidV4(value))
        {
            throw new ArgumentException(
                $"{name} must be a canonical lowercase UUIDv4.",
                name);
        }
    }

    private static void RequireOppositeRoles(string first, string second)
    {
        if (first is not ("sender" or "receiver"))
        {
            throw new ArgumentException($"Invalid role '{first}'.");
        }
        if (second is not ("sender" or "receiver"))
        {
            throw new ArgumentException($"Invalid role '{second}'.");
        }
        if (string.Equals(first, second, StringComparison.Ordinal))
        {
            throw new ArgumentException("Identity/resume roles must be opposite.");
        }
    }

    private static void RequireLowerHex64(string value, string name)
    {
        if (!LowerHex64Regex.IsMatch(value))
        {
            throw new ArgumentException(
                $"{name} must be exactly 64 lowercase hexadecimal characters.",
                name);
        }
    }
}
