using Glide.Core.Models;

namespace Glide.Core.Codec;

public static class ProtocolCatalog
{
    public const int MaxControlBytes = 16384;
    public const int MaxExtensionsBytes = 1024;
    public const int MaxJsonDepth = 8;
    public const int ProtocolMajorVersion = 1;
    public const long MaxItemBytes = 68_719_476_736L; // 64 GiB
    public const long MaxBatchBytes = 274_877_906_944L; // 256 GiB

    public static readonly HashSet<string> AllowedEnvelopeKeys =
    [
        "v", "type", "sessionId", "messageId", "seq", "body", "extensions"
    ];

    public static readonly Dictionary<string, HashSet<string>> AllowedBodyKeys = new()
    {
        [MessageTypes.Hello] = ["supportedMajors", "requiredCapabilities", "optionalCapabilities", "role", "ephemeralPeerId", "displayName"],
        [MessageTypes.HelloAck] = ["selectedMajor", "capabilities", "ephemeralPeerId", "displayName", "limits"],
        [MessageTypes.OfferBegin] = ["batchId", "itemCount", "totalBytes", "pageCount"],
        [MessageTypes.OfferPage] = ["batchId", "pageIndex", "items"],
        [MessageTypes.OfferEnd] = ["batchId"],
        [MessageTypes.OfferDecision] = ["batchId", "accepted", "code"],
        [MessageTypes.ItemStart] = ["batchId", "itemId", "bulkRef", "sizeBytes"],
        [MessageTypes.ItemReady] = ["batchId", "itemId"],
        [MessageTypes.ItemEnd] = ["batchId", "itemId", "actualBytes", "sha256"],
        [MessageTypes.ItemResult] = ["batchId", "itemId", "status", "sizeBytes", "sha256", "code"],
        [MessageTypes.BatchEnd] = ["batchId"],
        [MessageTypes.BatchResult] = ["batchId", "status", "verifiedCount", "totalVerifiedBytes"],
        [MessageTypes.Cancel] = ["batchId", "code"],
        [MessageTypes.CancelAck] = ["replyTo"],
        [MessageTypes.Error] = ["code", "batchId", "itemId", "detail"],
        [MessageTypes.Ping] = ["nonce"],
        [MessageTypes.Pong] = ["nonce"],
        [MessageTypes.IdentityChallenge] =
        [
            "profile", "challengeNonceHex", "verifierFingerprintHex",
            "verifierRole", "signerRole", "channelBindingHex", "capabilityHashHex"
        ],
        [MessageTypes.IdentityResponse] =
        [
            "profile", "challengeMessageId", "challengeNonceHex",
            "signerFingerprintHex", "publicKeyDerHex", "signatureHex"
        ],
        [MessageTypes.ResumeRequest] =
        [
            "profile", "originalSessionId", "itemId",
            "requesterFingerprintHex", "responderFingerprintHex",
            "requesterRole", "responderRole", "verifiedChunkCount",
            "contiguousByteOffset", "chunkSize", "totalSizeBytes",
            "chunkRootHashHex", "sessionNonceHex", "channelBindingHex",
            "capabilityHashHex", "signatureHex"
        ],
        [MessageTypes.ResumeAccept] =
        [
            "profile", "requestMessageId", "originalSessionId",
            "itemId", "startChunkIndex", "startByteOffset"
        ],
        [MessageTypes.ResumeReject] =
        [
            "profile", "requestMessageId", "originalSessionId", "itemId", "code"
        ],
        [MessageTypes.ResumeStreamStart] =
        [
            "profile", "requestMessageId", "originalSessionId", "itemId",
            "bulkRef", "startChunkIndex", "startByteOffset", "remainingBytes",
            "chunkSize", "totalSizeBytes", "chunkRootHashHex", "wholeFileSha256Hex"
        ],
        [MessageTypes.ResumeStreamReady] =
        [
            "profile", "requestMessageId", "originalSessionId", "itemId", "bulkRef"
        ],
        [MessageTypes.ResumeResult] =
        [
            "profile", "requestMessageId", "originalSessionId", "itemId",
            "status", "sizeBytes", "sha256", "code"
        ],
    };

    public static readonly HashSet<string> AllowedLimitsKeys = ["maxItems", "maxItemBytes", "maxBatchBytes", "maxControlBytes"];
    public static readonly HashSet<string> AllowedItemKeys = ["itemId", "name", "mime", "sizeBytes", "relativePath", "rootHash", "chunkSize"];
}
