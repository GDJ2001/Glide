namespace Glide.Core.Models;

/// <summary>
/// Cross-platform Glide protocol capability names.
///
/// identity-v1 and resume-v1 are defined but are not production-default capabilities until
/// HARD-019..021 provide persistent key storage, channel binding and trusted-session enforcement.
/// </summary>
public static class ProtocolCapabilities
{
    public const string StreamV1 = "stream-v1";
    public const string Sha256 = "sha256";
    public const string FolderV1 = "folder-v1";
    public const string IdentityV1 = "identity-v1";
    public const string ResumeV1 = "resume-v1";

    public static readonly HashSet<string> Required =
        [StreamV1, Sha256];

    public static readonly HashSet<string> ProductionOptional =
        [FolderV1];

    public static readonly HashSet<string> TrustExtension =
        [IdentityV1, ResumeV1];

    public static void ValidateDependencies(
        IEnumerable<string> capabilities)
    {
        var set = capabilities.ToHashSet(StringComparer.Ordinal);
        if (set.Contains(ResumeV1) &&
            !set.Contains(IdentityV1))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                "resume-v1 requires identity-v1 in the same negotiated capability set");
        }
    }
}


public static class ResumeRejectCodes
{
    public const string FileChanged = "FILE_CHANGED";
    public const string JournalMismatch = "JOURNAL_MISMATCH";
    public const string Expired = "EXPIRED";
    public const string Unauthorized = "UNAUTHORIZED";
    public const string InvalidOffset = "INVALID_OFFSET";
    public const string CapabilityMismatch = "CAPABILITY_MISMATCH";

    public static readonly HashSet<string> All =
    [
        FileChanged,
        JournalMismatch,
        Expired,
        Unauthorized,
        InvalidOffset,
        CapabilityMismatch
    ];
}
