using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;

namespace Glide.Core.Pairing;

public static class Profile2Constants
{
    public const int WireVersion = 1;
    public const int MaxControlPayloadBytes = 16_384;

    public const string PurposePairOnly = "PAIR_ONLY";
    public const string PurposeTrustedSend = "TRUSTED_SEND";

    public const string AuthMethodSpake2PinV1 = "spake2-pin-v1";
    public const string AuthMethodQrTokenV2 = "qr-token-v2";
    public const string AuthMethodIdentityV1 = "identity-v1";

    public const string RoleInitiator = "initiator";
    public const string RoleResponder = "responder";

    public const string ErrorBusy = "BUSY";
    public const string ErrorExpired = "EXPIRED";
    public const string ErrorAuthFailed = "AUTH_FAILED";
    public const string ErrorDeclined = "DECLINED";
    public const string ErrorUntrustedPeer = "UNTRUSTED_PEER";
    public const string ErrorIdentityChanged = "IDENTITY_CHANGED";
    public const string ErrorUnsupportedVersion = "UNSUPPORTED_VERSION";
    public const string ErrorProtocolOrder = "PROTOCOL_ORDER";

    public const int DefaultPairWindowSeconds = 120;
    public const int MaxPinAttemptsPerWindow = 3;
}

public abstract record Profile2Message(int V, string Type, string SessionId)
{
    public sealed record SessionOpen(
        string SessionId,
        string Purpose,
        string AuthMethod,
        string InitiatorNonce,
        string InitiatorDeviceName,
        string InitiatorPlatform,
        string InitiatorIdentityPublicKey,
        string? ExpectedPresenceId = null,
        string? InvitationId = null,
        int V = Profile2Constants.WireVersion)
        : Profile2Message(V, "SESSION_OPEN", SessionId);

    public sealed record SessionOpenAck(
        string SessionId,
        bool Accepted,
        string? ErrorCode = null,
        string? Message = null,
        string? ResponderNonce = null,
        string? ResponderDeviceName = null,
        string? ResponderPlatform = null,
        string? ResponderIdentityPublicKey = null,
        string? AdvertisedPresenceId = null,
        int V = Profile2Constants.WireVersion)
        : Profile2Message(V, "SESSION_OPEN_ACK", SessionId);

    public sealed record PairMsg(
        string SessionId,
        string Role,
        string? SpakePublicValue = null,
        string? InvitationHash = null,
        int V = Profile2Constants.WireVersion)
        : Profile2Message(V, "PAIR_MSG", SessionId);

    public sealed record PairConfirm(
        string SessionId,
        string Role,
        string KeyConfirmationMac,
        string IdentitySignature,
        int V = Profile2Constants.WireVersion)
        : Profile2Message(V, "PAIR_CONFIRM", SessionId);

    public sealed record PairApproval(
        string SessionId,
        string Role,
        bool Approved,
        string? Reason = null,
        int V = Profile2Constants.WireVersion)
        : Profile2Message(V, "PAIR_APPROVAL", SessionId);

    public sealed record PairResult(
        string SessionId,
        bool Paired,
        string? ErrorCode = null,
        string? Message = null,
        int V = Profile2Constants.WireVersion)
        : Profile2Message(V, "PAIR_RESULT", SessionId);
}

public static class Profile2WireCodec
{
    public static byte[] Encode(Profile2Message message)
    {
        ArgumentNullException.ThrowIfNull(message);

        var obj = new JsonObject
        {
            ["v"] = message.V,
            ["type"] = message.Type,
            ["sessionId"] = message.SessionId
        };

        switch (message)
        {
            case Profile2Message.SessionOpen open:
                obj["purpose"] = open.Purpose;
                obj["authMethod"] = open.AuthMethod;
                obj["initiatorNonce"] = open.InitiatorNonce;
                obj["initiatorDeviceName"] = open.InitiatorDeviceName;
                obj["initiatorPlatform"] = open.InitiatorPlatform;
                obj["initiatorIdentityPublicKey"] = open.InitiatorIdentityPublicKey;
                if (open.ExpectedPresenceId != null) obj["expectedPresenceId"] = open.ExpectedPresenceId;
                if (open.InvitationId != null) obj["invitationId"] = open.InvitationId;
                break;

            case Profile2Message.SessionOpenAck ack:
                obj["accepted"] = ack.Accepted;
                if (ack.ErrorCode != null) obj["errorCode"] = ack.ErrorCode;
                if (ack.Message != null) obj["message"] = ack.Message;
                if (ack.ResponderNonce != null) obj["responderNonce"] = ack.ResponderNonce;
                if (ack.ResponderDeviceName != null) obj["responderDeviceName"] = ack.ResponderDeviceName;
                if (ack.ResponderPlatform != null) obj["responderPlatform"] = ack.ResponderPlatform;
                if (ack.ResponderIdentityPublicKey != null) obj["responderIdentityPublicKey"] = ack.ResponderIdentityPublicKey;
                if (ack.AdvertisedPresenceId != null) obj["advertisedPresenceId"] = ack.AdvertisedPresenceId;
                break;

            case Profile2Message.PairMsg pairMsg:
                obj["role"] = pairMsg.Role;
                if (pairMsg.SpakePublicValue != null) obj["spakePublicValue"] = pairMsg.SpakePublicValue;
                if (pairMsg.InvitationHash != null) obj["invitationHash"] = pairMsg.InvitationHash;
                break;

            case Profile2Message.PairConfirm confirm:
                obj["role"] = confirm.Role;
                obj["keyConfirmationMac"] = confirm.KeyConfirmationMac;
                obj["identitySignature"] = confirm.IdentitySignature;
                break;

            case Profile2Message.PairApproval approval:
                obj["role"] = approval.Role;
                obj["approved"] = approval.Approved;
                if (approval.Reason != null) obj["reason"] = approval.Reason;
                break;

            case Profile2Message.PairResult result:
                obj["paired"] = result.Paired;
                if (result.ErrorCode != null) obj["errorCode"] = result.ErrorCode;
                if (result.Message != null) obj["message"] = result.Message;
                break;

            default:
                throw new ArgumentException($"Unsupported Profile2Message type: {message.GetType().Name}");
        }

        byte[] bytes = Encoding.UTF8.GetBytes(obj.ToJsonString());
        if (bytes.Length is <= 0 or > Profile2Constants.MaxControlPayloadBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Profile 2 control message size {bytes.Length} exceeds {Profile2Constants.MaxControlPayloadBytes} bytes.");
        }

        return bytes;
    }

    public static Profile2Message Decode(ReadOnlySpan<byte> utf8Json)
    {
        if (utf8Json.Length is <= 0 or > Profile2Constants.MaxControlPayloadBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Profile 2 control message size {utf8Json.Length} is out of bounds.");
        }

        JsonNode rootNode;
        try
        {
            rootNode = StrictJsonParser.Parse(utf8Json.ToArray(), maxDepth: 4);
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Invalid JSON in Profile 2 message: {ex.Message}");
        }

        if (rootNode is not JsonObject obj)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "Profile 2 control message root must be a JSON object.");
        }

        int v = GetRequiredInt(obj, "v");
        if (v != Profile2Constants.WireVersion)
        {
            throw new ProtocolViolationException(
                Profile2Constants.ErrorUnsupportedVersion,
                $"Unsupported Profile 2 message version {v}.");
        }

        string type = GetRequiredString(obj, "type");
        string sessionId = GetRequiredString(obj, "sessionId");
        if (!IdentifierValidation.IsValidUuidV4(sessionId))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "Profile 2 sessionId must be a canonical lowercase UUIDv4.");
        }

        return type switch
        {
            "SESSION_OPEN" => new Profile2Message.SessionOpen(
                SessionId: sessionId,
                Purpose: GetRequiredString(obj, "purpose"),
                AuthMethod: GetRequiredString(obj, "authMethod"),
                InitiatorNonce: GetRequiredString(obj, "initiatorNonce"),
                InitiatorDeviceName: GetRequiredString(obj, "initiatorDeviceName"),
                InitiatorPlatform: GetRequiredString(obj, "initiatorPlatform"),
                InitiatorIdentityPublicKey: GetRequiredString(obj, "initiatorIdentityPublicKey"),
                ExpectedPresenceId: GetOptionalString(obj, "expectedPresenceId"),
                InvitationId: GetOptionalString(obj, "invitationId"),
                V: v),

            "SESSION_OPEN_ACK" => new Profile2Message.SessionOpenAck(
                SessionId: sessionId,
                Accepted: GetRequiredBool(obj, "accepted"),
                ErrorCode: GetOptionalString(obj, "errorCode"),
                Message: GetOptionalString(obj, "message"),
                ResponderNonce: GetOptionalString(obj, "responderNonce"),
                ResponderDeviceName: GetOptionalString(obj, "responderDeviceName"),
                ResponderPlatform: GetOptionalString(obj, "responderPlatform"),
                ResponderIdentityPublicKey: GetOptionalString(obj, "responderIdentityPublicKey"),
                AdvertisedPresenceId: GetOptionalString(obj, "advertisedPresenceId"),
                V: v),

            "PAIR_MSG" => new Profile2Message.PairMsg(
                SessionId: sessionId,
                Role: GetRequiredString(obj, "role"),
                SpakePublicValue: GetOptionalString(obj, "spakePublicValue"),
                InvitationHash: GetOptionalString(obj, "invitationHash"),
                V: v),

            "PAIR_CONFIRM" => new Profile2Message.PairConfirm(
                SessionId: sessionId,
                Role: GetRequiredString(obj, "role"),
                KeyConfirmationMac: GetRequiredString(obj, "keyConfirmationMac"),
                IdentitySignature: GetRequiredString(obj, "identitySignature"),
                V: v),

            "PAIR_APPROVAL" => new Profile2Message.PairApproval(
                SessionId: sessionId,
                Role: GetRequiredString(obj, "role"),
                Approved: GetRequiredBool(obj, "approved"),
                Reason: GetOptionalString(obj, "reason"),
                V: v),

            "PAIR_RESULT" => new Profile2Message.PairResult(
                SessionId: sessionId,
                Paired: GetRequiredBool(obj, "paired"),
                ErrorCode: GetOptionalString(obj, "errorCode"),
                Message: GetOptionalString(obj, "message"),
                V: v),

            _ => throw new ProtocolViolationException(
                Profile2Constants.ErrorProtocolOrder,
                $"Unexpected or forbidden Profile 2 message type '{type}'.")
        };
    }

    public static async Task WriteMessageAsync(
        Stream stream,
        Profile2Message message,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        byte[] payload = Encode(message);
        byte[] header = new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(header, (uint)payload.Length);
        await stream.WriteAsync(header, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    public static async Task<Profile2Message> ReadMessageAsync(
        Stream stream,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        byte[] header = new byte[4];
        await ReadExactAsync(stream, header, cancellationToken).ConfigureAwait(false);
        uint length = BinaryPrimitives.ReadUInt32BigEndian(header);
        if (length is 0 or > Profile2Constants.MaxControlPayloadBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Profile 2 frame length {length} exceeds maximum {Profile2Constants.MaxControlPayloadBytes} bytes.");
        }

        byte[] payload = new byte[length];
        await ReadExactAsync(stream, payload, cancellationToken).ConfigureAwait(false);
        return Decode(payload);
    }

    private static async Task ReadExactAsync(
        Stream stream,
        Memory<byte> buffer,
        CancellationToken cancellationToken)
    {
        int offset = 0;
        while (offset < buffer.Length)
        {
            int read = await stream.ReadAsync(buffer[offset..], cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                throw new EndOfStreamException("Connection closed while reading Profile 2 control frame.");
            }
            offset += read;
        }
    }

    private static string GetRequiredString(JsonObject obj, string key) =>
        obj[key]?.GetValue<string>()
        ?? throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Missing required string field '{key}'.");

    private static string? GetOptionalString(JsonObject obj, string key) =>
        obj[key]?.GetValue<string>();

    private static int GetRequiredInt(JsonObject obj, string key)
    {
        if (obj[key] is JsonValue val)
        {
            if (val.TryGetValue<int>(out int i)) return i;
            if (val.TryGetValue<long>(out long l)) return checked((int)l);
        }
        throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Missing or invalid integer field '{key}'.");
    }

    private static bool GetRequiredBool(JsonObject obj, string key)
    {
        if (obj[key] is JsonValue val && val.TryGetValue<bool>(out bool b))
        {
            return b;
        }
        throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Missing or invalid boolean field '{key}'.");
    }
}

/// <summary>
/// Manages a 2-minute "Ready to pair" window on a desktop target, holding the ephemeral 6-digit PIN,
/// the matching one-use `glide://pair/v2#...` QR/link invitation, one-use consumption, and the
/// 3-attempt failure ceiling.
/// </summary>
public sealed class PairReadyWindowAuthority : IDisposable
{
    private readonly Lock _lock = new();
    private byte[] _pinAsciiBytes;
    private int _failedAttempts;
    private bool _consumed;
    private bool _cancelled;

    public string PresenceId { get; }
    public string FormattedPinDisplay { get; }
    public LanPairV2Invitation QrInvitation { get; }
    public string QrInvitationUri { get; }
    public DateTimeOffset ExpiresAtUtc { get; }

    public PairReadyWindowAuthority(
        string presenceId,
        string deviceName,
        string platform,
        IReadOnlyList<LanEndpoint> endpoints,
        string serverSpkiSha256Hex,
        TimeSpan? ttl = null,
        string? fixedPinForTest = null)
    {
        PresenceId = presenceId;
        int ttlSeconds = (int)Math.Clamp((ttl ?? TimeSpan.FromSeconds(Profile2Constants.DefaultPairWindowSeconds)).TotalSeconds, 1, Profile2Constants.DefaultPairWindowSeconds);
        ExpiresAtUtc = DateTimeOffset.UtcNow.AddSeconds(ttlSeconds);

        string rawPin = fixedPinForTest ?? RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6", CultureInfo.InvariantCulture);
        if (rawPin.Length != 6 || !rawPin.All(char.IsAsciiDigit))
        {
            throw new ArgumentException("Pairing PIN must be exactly 6 ASCII digits.", nameof(fixedPinForTest));
        }

        _pinAsciiBytes = Encoding.ASCII.GetBytes(rawPin);
        FormattedPinDisplay = $"{rawPin[..3]} {rawPin[3..]}";

        QrInvitation = LanPairV2Invitation.CreateFresh(
            presence: presenceId,
            name: deviceName,
            platform: platform,
            endpoints: endpoints,
            spkiSha256: serverSpkiSha256Hex,
            ttlSeconds: ttlSeconds);
        QrInvitationUri = InvitationCodec.EncodePairV2(QrInvitation);
    }

    public bool IsActive(DateTimeOffset? now = null)
    {
        lock (_lock)
        {
            if (_cancelled || _consumed || _failedAttempts >= Profile2Constants.MaxPinAttemptsPerWindow)
            {
                return false;
            }
            return (now ?? DateTimeOffset.UtcNow) <= ExpiresAtUtc;
        }
    }

    public int RemainingSeconds(DateTimeOffset? now = null)
    {
        var current = now ?? DateTimeOffset.UtcNow;
        double diff = (ExpiresAtUtc - current).TotalSeconds;
        return diff <= 0 ? 0 : (int)Math.Ceiling(diff);
    }

    public int FailedAttempts
    {
        get
        {
            lock (_lock) return _failedAttempts;
        }
    }

    public bool TryCopyPinBytes(out byte[] pinBytes, out string? errorCode, out string? userMessage)
    {
        lock (_lock)
        {
            if (_cancelled || _consumed || _failedAttempts >= Profile2Constants.MaxPinAttemptsPerWindow || DateTimeOffset.UtcNow > ExpiresAtUtc)
            {
                pinBytes = Array.Empty<byte>();
                errorCode = Profile2Constants.ErrorExpired;
                userMessage = "Code expired — get a new code";
                return false;
            }

            pinBytes = _pinAsciiBytes.ToArray();
            errorCode = null;
            userMessage = null;
            return true;
        }
    }

    /// <summary>
    /// Records a failed PIN verification attempt. Returns true if the window is now invalidated
    /// because the 3-attempt ceiling was reached.
    /// </summary>
    public bool RecordFailedPinAttempt()
    {
        lock (_lock)
        {
            _failedAttempts++;
            if (_failedAttempts >= Profile2Constants.MaxPinAttemptsPerWindow)
            {
                ZeroPinLocked();
                return true;
            }
            return false;
        }
    }

    public bool TryConsumeSuccess()
    {
        lock (_lock)
        {
            if (_cancelled || _consumed || _failedAttempts >= Profile2Constants.MaxPinAttemptsPerWindow || DateTimeOffset.UtcNow > ExpiresAtUtc)
            {
                return false;
            }
            _consumed = true;
            ZeroPinLocked();
            return true;
        }
    }

    public void Cancel()
    {
        lock (_lock)
        {
            _cancelled = true;
            ZeroPinLocked();
        }
    }

    private void ZeroPinLocked()
    {
        if (_pinAsciiBytes.Length > 0)
        {
            CryptographicOperations.ZeroMemory(_pinAsciiBytes);
        }
    }

    public void Dispose() => Cancel();
}
