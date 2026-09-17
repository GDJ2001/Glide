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
        [MessageTypes.IdentityChallenge] = ["nonceHex"],
        [MessageTypes.IdentityResponse] = ["signatureHex", "publicKeyDerHex"],
        [MessageTypes.ResumeRequest] = ["sessionId", "itemId", "verifiedChunkCount", "contiguousByteOffset", "chunkRootHashHex", "sessionNonceHex", "signatureHex"],
        [MessageTypes.ResumeAccept] = ["sessionId", "itemId", "startChunkIndex", "startByteOffset"],
        [MessageTypes.ResumeReject] = ["sessionId", "itemId", "reason"],
    };

    public static readonly HashSet<string> AllowedLimitsKeys = ["maxItems", "maxItemBytes", "maxBatchBytes", "maxControlBytes"];
    public static readonly HashSet<string> AllowedItemKeys = ["itemId", "name", "mime", "sizeBytes", "relativePath", "rootHash", "chunkSize"];
}
