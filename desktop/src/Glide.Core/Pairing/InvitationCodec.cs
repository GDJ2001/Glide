using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Glide.Core.Codec;

namespace Glide.Core.Pairing;

/// <summary>
/// Thrown when an invitation violates URI format, size bounds, JSON depth,
/// or cryptographic/identity constraints (LAN-006, Section 8).
/// </summary>
public sealed class InvitationException : ArgumentException
{
    public InvitationException(string message) : base(message) { }
    public InvitationException(string message, Exception innerException) : base(message, innerException) { }
}

/// <summary>
/// Strict canonical codec for Glide QR Pairing invitations (LAN-006, Section 8).
/// Encoding format: glide://pair/v1#&lt;unpadded-base64url(UTF-8 JSON)&gt;
/// Invariants:
/// - Decoded JSON &lt;= 2,048 bytes
/// - Raw URI &lt;= 2,768 ASCII characters
/// - JSON depth &lt;= 4
/// - Duplicate keys, unknown keys, non-canonical base64url, extra fields, and invalid UTF-8 are strictly rejected.
/// </summary>
public static class InvitationCodec
{
    public const string UriPrefix = "glide://pair/v1#";
    public const int MaxUriChars = 2768;
    public const int MaxJsonBytes = 2048;
    public const int MaxJsonDepth = 4;

    private static readonly HashSet<string> ExpectedRootKeys = new(StringComparer.Ordinal)
    {
        "v", "transport", "profile", "protocol", "invitationId",
        "presence", "name", "platform", "endpoints", "spkiSha256",
        "token", "expiresAt", "ttlSeconds"
    };

    private static readonly HashSet<string> ExpectedEndpointKeys = new(StringComparer.Ordinal)
    {
        "address", "port"
    };

    private static readonly Regex Base64UrlRegex = new(@"^[A-Za-z0-9_-]+$", RegexOptions.Compiled);

    /// <summary>
    /// Encodes a LanInvitation into the canonical, compact QR URI.
    /// </summary>
    public static string Encode(LanInvitation invitation)
    {
        ArgumentNullException.ThrowIfNull(invitation);

        var endpointsArray = new JsonArray();
        foreach (var endpoint in invitation.Endpoints)
        {
            var epObj = new JsonObject
            {
                ["address"] = endpoint.Address,
                ["port"] = endpoint.Port
            };
            endpointsArray.Add(epObj);
        }

        var jsonObject = new JsonObject
        {
            ["v"] = invitation.V,
            ["transport"] = invitation.Transport,
            ["profile"] = invitation.Profile,
            ["protocol"] = invitation.Protocol,
            ["invitationId"] = invitation.InvitationId,
            ["presence"] = invitation.Presence,
            ["name"] = invitation.Name,
            ["platform"] = invitation.Platform,
            ["endpoints"] = endpointsArray,
            ["spkiSha256"] = invitation.SpkiSha256,
            ["token"] = invitation.Token,
            ["expiresAt"] = invitation.ExpiresAt,
            ["ttlSeconds"] = invitation.TtlSeconds
        };

        byte[] jsonBytes = Encoding.UTF8.GetBytes(jsonObject.ToJsonString());
        if (jsonBytes.Length > MaxJsonBytes)
        {
            throw new InvitationException($"Encoded JSON size {jsonBytes.Length} exceeds maximum {MaxJsonBytes} bytes");
        }

        string base64 = ToUnpaddedBase64Url(jsonBytes);
        string uri = $"{UriPrefix}{base64}";
        if (uri.Length > MaxUriChars)
        {
            throw new InvitationException($"Encoded URI length {uri.Length} exceeds maximum {MaxUriChars} characters");
        }

        return uri;
    }

    /// <summary>
    /// Decodes and strictly validates a QR pairing invitation URI.
    /// </summary>
    public static LanInvitation Decode(string uri)
    {
        if (string.IsNullOrWhiteSpace(uri))
        {
            throw new InvitationException("Invitation URI must not be empty or blank");
        }

        if (uri.Length > MaxUriChars)
        {
            throw new InvitationException($"URI length {uri.Length} exceeds maximum allowed bound of {MaxUriChars} characters");
        }

        if (!uri.StartsWith(UriPrefix, StringComparison.Ordinal))
        {
            throw new InvitationException($"URI must start with '{UriPrefix}', got: '{uri}'");
        }

        string rawBase64Url = uri[UriPrefix.Length..];
        if (string.IsNullOrEmpty(rawBase64Url))
        {
            throw new InvitationException("URI payload fragment must not be empty");
        }

        if (!Base64UrlRegex.IsMatch(rawBase64Url))
        {
            throw new InvitationException("URI payload fragment contains non-base64url characters or padding");
        }

        byte[] jsonBytes;
        try
        {
            jsonBytes = FromUnpaddedBase64Url(rawBase64Url);
        }
        catch (Exception ex) when (ex is not InvitationException)
        {
            throw new InvitationException("Failed to decode base64url payload", ex);
        }

        // Validate canonical base64url encoding (reject unnormalized / non-minimal representations)
        string reencoded = ToUnpaddedBase64Url(jsonBytes);
        if (!string.Equals(rawBase64Url, reencoded, StringComparison.Ordinal))
        {
            throw new InvitationException("Base64url payload is not canonically encoded");
        }

        if (jsonBytes.Length > MaxJsonBytes)
        {
            throw new InvitationException($"Decoded JSON byte length {jsonBytes.Length} exceeds maximum {MaxJsonBytes} bytes");
        }

        // Compute SHA-256 over decoded QR JSON bytes (as specified in Master Plan line 214)
        byte[] hashBytes = SHA256.HashData(jsonBytes);
        string invitationHash = Convert.ToHexString(hashBytes).ToLowerInvariant();

        // Strict JSON parsing enforcing max depth 4 and duplicate key rejection
        JsonNode rootNode;
        try
        {
            rootNode = StrictJsonParser.Parse(jsonBytes, maxDepth: MaxJsonDepth);
        }
        catch (Exception ex)
        {
            throw new InvitationException($"Invalid JSON in invitation: {ex.Message}", ex);
        }

        if (rootNode is not JsonObject rootObj)
        {
            throw new InvitationException("Decoded JSON root must be a JSON object");
        }

        // Strict unknown root key rejection
        var actualKeys = rootObj.Select(kvp => kvp.Key).ToHashSet(StringComparer.Ordinal);
        var unknownKeys = actualKeys.Except(ExpectedRootKeys).ToList();
        if (unknownKeys.Count > 0)
        {
            throw new InvitationException($"Unknown root keys in invitation: [{string.Join(", ", unknownKeys)}]");
        }

        var missingKeys = ExpectedRootKeys.Except(actualKeys).ToList();
        if (missingKeys.Count > 0)
        {
            throw new InvitationException($"Missing required root keys in invitation: [{string.Join(", ", missingKeys)}]");
        }

        try
        {
            int v = GetInt(rootObj["v"], "v");
            string transport = rootObj["transport"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'transport'");
            int profile = GetInt(rootObj["profile"], "profile");
            int protocol = GetInt(rootObj["protocol"], "protocol");
            string invitationId = rootObj["invitationId"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'invitationId'");
            string presence = rootObj["presence"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'presence'");
            string name = rootObj["name"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'name'");
            string platform = rootObj["platform"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'platform'");

            if (rootObj["endpoints"] is not JsonArray endpointsArray)
            {
                throw new InvitationException("'endpoints' must be a JSON array");
            }

            var endpoints = new List<LanEndpoint>();
            foreach (var item in endpointsArray)
            {
                if (item is not JsonObject epObj)
                {
                    throw new InvitationException("Endpoint items must be JSON objects");
                }

                var epKeys = epObj.Select(kvp => kvp.Key).ToHashSet(StringComparer.Ordinal);
                var unknownEpKeys = epKeys.Except(ExpectedEndpointKeys).ToList();
                if (unknownEpKeys.Count > 0)
                {
                    throw new InvitationException($"Unknown keys in endpoint: [{string.Join(", ", unknownEpKeys)}]");
                }

                string addr = epObj["address"]?.GetValue<string>() ?? throw new InvitationException("Missing endpoint 'address'");
                int port = GetInt(epObj["port"], "port");
                endpoints.Add(new LanEndpoint(addr, port));
            }

            string spkiSha256 = rootObj["spkiSha256"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'spkiSha256'");
            string token = rootObj["token"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'token'");
            string expiresAt = rootObj["expiresAt"]?.GetValue<string>() ?? throw new InvitationException("Missing or invalid 'expiresAt'");
            int ttlSeconds = GetInt(rootObj["ttlSeconds"], "ttlSeconds");

            return new LanInvitation(
                invitationId: invitationId,
                presence: presence,
                name: name,
                platform: platform,
                endpoints: endpoints,
                spkiSha256: spkiSha256,
                token: token,
                expiresAt: expiresAt,
                ttlSeconds: ttlSeconds,
                invitationHash: invitationHash,
                v: v,
                transport: transport,
                profile: profile,
                protocol: protocol);
        }
        catch (InvitationException)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new InvitationException($"Failed to extract invitation fields: {ex.Message}", ex);
        }
    }

    private static int GetInt(JsonNode? node, string fieldName)
    {
        if (node == null) throw new InvitationException($"Missing '{fieldName}'");
        if (node is JsonValue val)
        {
            if (val.TryGetValue<int>(out int i)) return i;
            if (val.TryGetValue<long>(out long l)) return checked((int)l);
        }
        throw new InvitationException($"Invalid integer value for '{fieldName}'");
    }

    private static string ToUnpaddedBase64Url(byte[] bytes)
    {
        return Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    private static byte[] FromUnpaddedBase64Url(string base64Url)
    {
        string padded = base64Url.Replace('-', '+').Replace('_', '/');
        int mod = padded.Length % 4;
        if (mod == 2) padded += "==";
        else if (mod == 3) padded += "=";
        else if (mod == 1) throw new InvitationException("Illegal base64url length");

        return Convert.FromBase64String(padded);
    }
}
