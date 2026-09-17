using System.Text.RegularExpressions;

namespace Glide.Core.Remote;

/// <summary>
/// Thrown when a remote invitation violates URI format, size bounds, key constraints,
/// or expiration limits (ADR-0022, Section 2.1).
/// </summary>
public sealed class RemoteInvitationException : ArgumentException
{
    public RemoteInvitationException(string message) : base(message) { }
    public RemoteInvitationException(string message, Exception innerException) : base(message, innerException) { }
}

/// <summary>
/// Strict canonical codec for Glide Remote Pairing invitations (ADR-0022, Section 2.1).
/// Format: glide://remote/v1#id=&lt;UUID&gt;&amp;key=&lt;HEX&gt;&amp;sig=&lt;URL_ENCODED_URI&gt;&amp;token=&lt;BASE64URL&gt;&amp;exp=&lt;TIMESTAMP&gt;
/// Invariants:
/// - Total URI length &lt;= 2,048 characters
/// - Exactly 5 required parameters: id, key, sig, token, exp (no unknown or duplicate parameters)
/// - id must be a canonical UUIDv4
/// - key must be 64 lowercase hexadecimal characters (32-byte X25519 public key)
/// - sig must be a valid wss:// or ws:// WebSocket URI
/// - token must be valid Base64url (unpadded)
/// - exp must be a positive 64-bit Unix timestamp
/// </summary>
public static class RemoteInvitationCodec
{
    public const string UriPrefix = "glide://remote/v1#";
    public const int MaxUriLength = 2048;

    private static readonly HashSet<string> RequiredKeys = new(StringComparer.Ordinal)
    {
        "id", "key", "sig", "token", "exp"
    };

    private static readonly Regex UuidRegex = new(
        @"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private static readonly Regex HexKeyRegex = new(
        @"^[0-9a-f]{64}$",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private static readonly Regex Base64UrlRegex = new(
        @"^[A-Za-z0-9_-]+$",
        RegexOptions.Compiled);

    /// <summary>
    /// Encodes a RemoteInvitation into the canonical URI format.
    /// </summary>
    public static string Encode(RemoteInvitation invitation)
    {
        ArgumentNullException.ThrowIfNull(invitation);

        if (!UuidRegex.IsMatch(invitation.RendezvousId))
        {
            throw new RemoteInvitationException($"Rendezvous ID '{invitation.RendezvousId}' is not a valid canonical UUIDv4.");
        }

        if (!HexKeyRegex.IsMatch(invitation.EphemeralPublicKeyHex))
        {
            throw new RemoteInvitationException("Ephemeral public key must be exactly 64 hexadecimal characters.");
        }

        if (!Base64UrlRegex.IsMatch(invitation.RelayBlindToken))
        {
            throw new RemoteInvitationException("Relay blind token must be a valid Base64url string.");
        }

        if (invitation.ExpiresAt <= 0)
        {
            throw new RemoteInvitationException("Expiration timestamp must be a positive integer.");
        }

        if (!Uri.TryCreate(invitation.SignalingEndpoint, UriKind.Absolute, out var sigUri) ||
            (sigUri.Scheme != "wss" && sigUri.Scheme != "ws"))
        {
            throw new RemoteInvitationException($"Signaling endpoint '{invitation.SignalingEndpoint}' must be an absolute ws:// or wss:// URI.");
        }

        string escapedSig = Uri.EscapeDataString(invitation.SignalingEndpoint);
        string uri = $"{UriPrefix}id={invitation.RendezvousId}&key={invitation.EphemeralPublicKeyHex}&sig={escapedSig}&token={invitation.RelayBlindToken}&exp={invitation.ExpiresAt}";

        if (uri.Length > MaxUriLength)
        {
            throw new RemoteInvitationException($"Encoded remote invitation URI length ({uri.Length}) exceeds maximum {MaxUriLength} characters.");
        }

        return uri;
    }

    /// <summary>
    /// Parses and strictly validates a remote invitation URI.
    /// </summary>
    public static RemoteInvitation Decode(string uri, bool checkExpiry = true, long? currentEpochSeconds = null)
    {
        if (string.IsNullOrWhiteSpace(uri))
        {
            throw new RemoteInvitationException("Remote invitation URI cannot be null, empty, or whitespace.");
        }

        if (uri.Length > MaxUriLength)
        {
            throw new RemoteInvitationException($"Remote invitation URI length ({uri.Length}) exceeds maximum {MaxUriLength} characters.");
        }

        if (!uri.StartsWith(UriPrefix, StringComparison.Ordinal))
        {
            throw new RemoteInvitationException($"Remote invitation URI must start with '{UriPrefix}'.");
        }

        string fragment = uri[UriPrefix.Length..];
        if (string.IsNullOrWhiteSpace(fragment))
        {
            throw new RemoteInvitationException("Remote invitation URI fragment is empty.");
        }

        string[] pairs = fragment.Split('&');
        var parsedParams = new Dictionary<string, string>(StringComparer.Ordinal);

        foreach (string pair in pairs)
        {
            int eqIdx = pair.IndexOf('=');
            if (eqIdx <= 0 || eqIdx == pair.Length - 1)
            {
                throw new RemoteInvitationException($"Malformed key-value pair in URI fragment: '{pair}'");
            }

            string key = pair[..eqIdx];
            string value = pair[(eqIdx + 1)..];

            if (!RequiredKeys.Contains(key))
            {
                throw new RemoteInvitationException($"Unknown or unauthorized query parameter in remote URI: '{key}'");
            }

            if (parsedParams.ContainsKey(key))
            {
                throw new RemoteInvitationException($"Duplicate parameter in remote URI: '{key}'");
            }

            parsedParams[key] = value;
        }

        if (parsedParams.Count != RequiredKeys.Count)
        {
            var missing = RequiredKeys.Where(k => !parsedParams.ContainsKey(k));
            throw new RemoteInvitationException($"Missing required parameters in remote URI: {string.Join(", ", missing)}");
        }

        string id = parsedParams["id"];
        if (!UuidRegex.IsMatch(id))
        {
            throw new RemoteInvitationException($"Invalid rendezvousId format: '{id}' (must be canonical UUIDv4).");
        }

        string keyHex = parsedParams["key"];
        if (!HexKeyRegex.IsMatch(keyHex))
        {
            throw new RemoteInvitationException($"Invalid ephemeralPublicKeyHex: '{keyHex}' (must be 64 hex characters).");
        }

        string unescapedSig = Uri.UnescapeDataString(parsedParams["sig"]);
        if (!Uri.TryCreate(unescapedSig, UriKind.Absolute, out var parsedSigUri) ||
            (parsedSigUri.Scheme != "wss" && parsedSigUri.Scheme != "ws"))
        {
            throw new RemoteInvitationException($"Invalid signalingEndpoint URI: '{unescapedSig}' (must be absolute ws:// or wss://).");
        }

        string token = parsedParams["token"];
        if (!Base64UrlRegex.IsMatch(token))
        {
            throw new RemoteInvitationException($"Invalid relayBlindToken format: '{token}' (must be Base64url).");
        }

        if (!long.TryParse(parsedParams["exp"], out long expiresAt) || expiresAt <= 0)
        {
            throw new RemoteInvitationException($"Invalid expiresAt timestamp: '{parsedParams["exp"]}'.");
        }

        var invitation = new RemoteInvitation(
            rendezvousId: id,
            ephemeralPublicKeyHex: keyHex,
            signalingEndpoint: unescapedSig,
            relayBlindToken: token,
            expiresAt: expiresAt);

        if (checkExpiry && invitation.IsExpired(currentEpochSeconds))
        {
            throw new RemoteInvitationException($"Remote invitation expired at Unix epoch {expiresAt}.");
        }

        return invitation;
    }
}
