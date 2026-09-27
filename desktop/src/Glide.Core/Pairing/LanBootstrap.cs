using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Glide.Core.Codec;
using Glide.Core.Models;

namespace Glide.Core.Pairing;

/// <summary>
/// Valid failure codes catalog for LAN Profile 1 bootstrap result (LAN-007, Section 8, Section 20).
/// </summary>
public enum LanBootstrapFailureCode
{
    AUTHENTICATION_FAILED,
    INVITATION_EXPIRED,
    INVITATION_USED,
    BUSY,
    UNSUPPORTED_PROFILE
}

/// <summary>
/// Client's first record after TLS verification (LAN-007, Section 8).
/// Carries no file names or paths.
/// </summary>
public sealed record LanBootstrapMessage
{
    public const string RoleSender = "sender";
    public const string RoleReceiver = "receiver";
    private static readonly Regex TokenRegex = new(@"^[A-Za-z0-9_-]{43}$", RegexOptions.Compiled);

    public int V { get; init; } = 1;
    public string InvitationId { get; init; }
    public string Token { get; init; }
    public string InvitationHash { get; init; }
    public string SessionId { get; init; }
    public string Role { get; init; } = RoleSender;

    public LanBootstrapMessage(
        string invitationId,
        string token,
        string invitationHash,
        string sessionId,
        string role = RoleSender,
        int v = 1)
    {
        if (v != 1) throw new ArgumentException($"Unsupported bootstrap version: {v}", nameof(v));
        if (!IdentifierValidation.IsValidUuidV4(invitationId))
        {
            throw new ArgumentException($"invitationId must be a canonical UUIDv4, got: '{invitationId}'", nameof(invitationId));
        }
        if (!TokenRegex.IsMatch(token))
        {
            throw new ArgumentException($"token must be exactly 43 base64url characters, got length {token?.Length ?? 0}", nameof(token));
        }
        if (!IdentifierValidation.IsValidSha256(invitationHash))
        {
            throw new ArgumentException($"invitationHash must be 64 lowercase hex characters, got: '{invitationHash}'", nameof(invitationHash));
        }
        if (!IdentifierValidation.IsValidUuidV4(sessionId))
        {
            throw new ArgumentException($"sessionId must be a canonical UUIDv4, got: '{sessionId}'", nameof(sessionId));
        }
        if (role != RoleSender && role != RoleReceiver)
        {
            throw new ArgumentException($"role must be '{RoleSender}' or '{RoleReceiver}', got: '{role}'", nameof(role));
        }

        V = v;
        InvitationId = invitationId;
        Token = token;
        InvitationHash = invitationHash;
        SessionId = sessionId;
        Role = role;
    }

    public override string ToString()
    {
        return $"LanBootstrapMessage(v={V}, invitationId={InvitationId}, token=[REDACTED], invitationHash={InvitationHash}, sessionId={SessionId}, role={Role})";
    }
}

/// <summary>
/// Server's one bootstrap response (LAN-007, Section 8).
/// </summary>
public sealed record LanBootstrapResult
{
    public const string StatusAccepted = "accepted";
    public const string StatusRejected = "rejected";

    public int V { get; init; } = 1;
    public string Status { get; init; }
    public string? SessionId { get; init; }
    public string? Code { get; init; }

    public bool IsAccepted => Status == StatusAccepted;

    public LanBootstrapResult(int v, string status, string? sessionId, string? code)
    {
        if (v != 1) throw new ArgumentException($"Unsupported bootstrap result version: {v}", nameof(v));

        if (status == StatusAccepted)
        {
            if (sessionId == null || !IdentifierValidation.IsValidUuidV4(sessionId))
            {
                throw new ArgumentException($"accepted status requires canonical UUIDv4 sessionId, got '{sessionId}'", nameof(sessionId));
            }
            if (code != null)
            {
                throw new ArgumentException("accepted status must not have code", nameof(code));
            }
        }
        else if (status == StatusRejected)
        {
            if (code == null || !Enum.TryParse<LanBootstrapFailureCode>(code, out _))
            {
                throw new ArgumentException($"rejected status requires valid failure code, got '{code}'", nameof(code));
            }
            if (sessionId != null)
            {
                throw new ArgumentException("rejected status must not have sessionId", nameof(sessionId));
            }
        }
        else
        {
            throw new ArgumentException($"status must be '{StatusAccepted}' or '{StatusRejected}', got: '{status}'", nameof(status));
        }

        V = v;
        Status = status;
        SessionId = sessionId;
        Code = code;
    }

    public static LanBootstrapResult Accepted(string sessionId) =>
        new(1, StatusAccepted, sessionId, null);

    public static LanBootstrapResult Rejected(LanBootstrapFailureCode code) =>
        new(1, StatusRejected, null, code.ToString());
}

/// <summary>
/// Strict canonical JSON codec for LAN Profile 1 BOOTSTRAP and BOOTSTRAP_RESULT messages.
/// Enforces depth &lt;= 4, strict field bounds, no unknown fields, no duplicate keys, and bounds checking.
/// </summary>
public static class LanBootstrapCodec
{
    public const int MaxBootstrapJsonBytes = 2048;
    public const int MaxResultJsonBytes = 512;
    public const int MaxJsonDepth = 4;

    private static readonly HashSet<string> AllowedBootstrapKeys = new(StringComparer.Ordinal)
    {
        "v", "invitationId", "token", "invitationHash", "sessionId", "role"
    };

    private static readonly HashSet<string> AllowedResultKeys = new(StringComparer.Ordinal)
    {
        "v", "status", "sessionId", "code"
    };

    public static byte[] EncodeBootstrap(LanBootstrapMessage msg)
    {
        ArgumentNullException.ThrowIfNull(msg);

        var json = new JsonObject
        {
            ["v"] = msg.V,
            ["invitationId"] = msg.InvitationId,
            ["token"] = msg.Token,
            ["invitationHash"] = msg.InvitationHash,
            ["sessionId"] = msg.SessionId,
            ["role"] = msg.Role
        };

        byte[] bytes = Encoding.UTF8.GetBytes(json.ToJsonString());
        if (bytes.Length > MaxBootstrapJsonBytes)
        {
            throw new ArgumentException($"Encoded bootstrap JSON exceeds {MaxBootstrapJsonBytes} bytes: {bytes.Length}");
        }
        return bytes;
    }

    public static LanBootstrapMessage DecodeBootstrap(byte[] bytes)
    {
        if (bytes == null || bytes.Length == 0)
        {
            throw new ArgumentException("Bootstrap JSON payload must not be empty", nameof(bytes));
        }
        if (bytes.Length > MaxBootstrapJsonBytes)
        {
            throw new ArgumentException($"Bootstrap JSON payload exceeds {MaxBootstrapJsonBytes} bytes: {bytes.Length}", nameof(bytes));
        }

        JsonNode root = StrictJsonParser.Parse(bytes, maxDepth: MaxJsonDepth);
        if (root is not JsonObject rootObj)
        {
            throw new ArgumentException("Bootstrap JSON root must be an object");
        }

        var keys = rootObj.Select(k => k.Key).ToHashSet(StringComparer.Ordinal);
        var unknownKeys = keys.Except(AllowedBootstrapKeys).ToList();
        if (unknownKeys.Count > 0)
        {
            throw new ArgumentException($"Unknown keys in bootstrap JSON: [{string.Join(", ", unknownKeys)}]");
        }

        int v = GetInt(rootObj["v"], "v");
        string invitationId = rootObj["invitationId"]?.GetValue<string>() ?? throw new ArgumentException("Missing 'invitationId'");
        string token = rootObj["token"]?.GetValue<string>() ?? throw new ArgumentException("Missing 'token'");
        string invitationHash = rootObj["invitationHash"]?.GetValue<string>() ?? throw new ArgumentException("Missing 'invitationHash'");
        string sessionId = rootObj["sessionId"]?.GetValue<string>() ?? throw new ArgumentException("Missing 'sessionId'");
        string role = rootObj["role"]?.GetValue<string>() ?? throw new ArgumentException("Missing 'role'");

        return new LanBootstrapMessage(invitationId, token, invitationHash, sessionId, role, v);
    }

    public static byte[] EncodeResult(LanBootstrapResult result)
    {
        ArgumentNullException.ThrowIfNull(result);

        var json = new JsonObject
        {
            ["v"] = result.V,
            ["status"] = result.Status
        };
        if (result.SessionId != null)
        {
            json["sessionId"] = result.SessionId;
        }
        if (result.Code != null)
        {
            json["code"] = result.Code;
        }

        byte[] bytes = Encoding.UTF8.GetBytes(json.ToJsonString());
        if (bytes.Length > MaxResultJsonBytes)
        {
            throw new ArgumentException($"Encoded result JSON exceeds {MaxResultJsonBytes} bytes: {bytes.Length}");
        }
        return bytes;
    }

    public static LanBootstrapResult DecodeResult(byte[] bytes)
    {
        if (bytes == null || bytes.Length == 0)
        {
            throw new ArgumentException("Result JSON payload must not be empty", nameof(bytes));
        }
        if (bytes.Length > MaxResultJsonBytes)
        {
            throw new ArgumentException($"Result JSON payload exceeds {MaxResultJsonBytes} bytes: {bytes.Length}", nameof(bytes));
        }

        JsonNode root = StrictJsonParser.Parse(bytes, maxDepth: MaxJsonDepth);
        if (root is not JsonObject rootObj)
        {
            throw new ArgumentException("Result JSON root must be an object");
        }

        var keys = rootObj.Select(k => k.Key).ToHashSet(StringComparer.Ordinal);
        var unknownKeys = keys.Except(AllowedResultKeys).ToList();
        if (unknownKeys.Count > 0)
        {
            throw new ArgumentException($"Unknown keys in result JSON: [{string.Join(", ", unknownKeys)}]");
        }

        int v = GetInt(rootObj["v"], "v");
        string status = rootObj["status"]?.GetValue<string>() ?? throw new ArgumentException("Missing 'status'");
        string? sessionId = rootObj["sessionId"]?.GetValue<string>();
        string? code = rootObj["code"]?.GetValue<string>();

        return new LanBootstrapResult(v, status, sessionId, code);
    }

    private static int GetInt(JsonNode? node, string fieldName)
    {
        if (node == null) throw new ArgumentException($"Missing '{fieldName}'");
        if (node is JsonValue val)
        {
            if (val.TryGetValue<int>(out int i)) return i;
            if (val.TryGetValue<long>(out long l)) return checked((int)l);
        }
        throw new ArgumentException($"Invalid integer value for '{fieldName}'");
    }
}
