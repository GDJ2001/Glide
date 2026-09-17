using System.Text.Json.Nodes;

namespace Glide.Core.Models;

public class ProtocolViolationException : Exception
{
    public string FailureCode { get; }

    public ProtocolViolationException(string failureCode, string message, Exception? inner = null)
        : base(message, inner)
    {
        FailureCode = failureCode;
    }
}

public static class MessageTypes
{
    public const string Hello = "HELLO";
    public const string HelloAck = "HELLO_ACK";
    public const string OfferBegin = "OFFER_BEGIN";
    public const string OfferPage = "OFFER_PAGE";
    public const string OfferEnd = "OFFER_END";
    public const string OfferDecision = "OFFER_DECISION";
    public const string ItemStart = "ITEM_START";
    public const string ItemReady = "ITEM_READY";
    public const string ItemEnd = "ITEM_END";
    public const string ItemResult = "ITEM_RESULT";
    public const string BatchEnd = "BATCH_END";
    public const string BatchResult = "BATCH_RESULT";
    public const string Cancel = "CANCEL";
    public const string CancelAck = "CANCEL_ACK";
    public const string Error = "ERROR";
    public const string Ping = "PING";
    public const string Pong = "PONG";
    public const string IdentityChallenge = "IDENTITY_CHALLENGE";
    public const string IdentityResponse = "IDENTITY_RESPONSE";
    public const string ResumeRequest = "RESUME_REQUEST";
    public const string ResumeAccept = "RESUME_ACCEPT";
    public const string ResumeReject = "RESUME_REJECT";

    public static readonly HashSet<string> All =
    [
        Hello, HelloAck,
        OfferBegin, OfferPage, OfferEnd, OfferDecision,
        ItemStart, ItemReady, ItemEnd, ItemResult,
        BatchEnd, BatchResult,
        Cancel, CancelAck,
        Error, Ping, Pong,
        IdentityChallenge, IdentityResponse,
        ResumeRequest, ResumeAccept, ResumeReject
    ];
}

public static class WireErrorCodes
{
    public const string UnsupportedProtocol = "UNSUPPORTED_PROTOCOL";
    public const string MalformedMessage = "MALFORMED_MESSAGE";
    public const string ProtocolOrder = "PROTOCOL_ORDER";
    public const string AuthenticationFailed = "AUTHENTICATION_FAILED";
    public const string UserRejected = "USER_REJECTED";
    public const string UserCancelled = "USER_CANCELLED";
    public const string SystemCancelled = "SYSTEM_CANCELLED";
    public const string SourceUnavailable = "SOURCE_UNAVAILABLE";
    public const string ReadFailure = "READ_FAILURE";
    public const string DestinationUnavailable = "DESTINATION_UNAVAILABLE";
    public const string WriteFailure = "WRITE_FAILURE";
    public const string InsufficientStorage = "INSUFFICIENT_STORAGE";
    public const string TransferInterrupted = "TRANSFER_INTERRUPTED";
    public const string Timeout = "TIMEOUT";
    public const string PeerLost = "PEER_LOST";
    public const string IntegrityMismatch = "INTEGRITY_MISMATCH";
    public const string LimitExceeded = "LIMIT_EXCEEDED";
    public const string Busy = "BUSY";

    public static readonly HashSet<string> All =
    [
        UnsupportedProtocol,
        MalformedMessage,
        ProtocolOrder,
        AuthenticationFailed,
        UserRejected,
        UserCancelled,
        SystemCancelled,
        SourceUnavailable,
        ReadFailure,
        DestinationUnavailable,
        WriteFailure,
        InsufficientStorage,
        TransferInterrupted,
        Timeout,
        PeerLost,
        IntegrityMismatch,
        LimitExceeded,
        Busy
    ];
}

public sealed record Envelope(
    int V,
    string Type,
    SessionId SessionId,
    MessageId MessageId,
    long Seq,
    JsonObject Body,
    JsonObject? Extensions = null
);
