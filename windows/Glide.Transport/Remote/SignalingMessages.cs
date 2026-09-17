using System.Text.Json;
using System.Text.Json.Nodes;

namespace Glide.Transport.Remote;

/// <summary>
/// Abstract base class for all remote signaling protocol envelopes.
/// </summary>
public abstract record SignalingMessage
{
    public abstract string ToJsonString();
}

/// <summary>
/// Host message sent to signaling broker to register listening on an ephemeral rendezvous ID.
/// Schema: { "action": "listen", "rendezvousId": "..." }
/// </summary>
public sealed record ListenRequest(string RendezvousId) : SignalingMessage
{
    public override string ToJsonString()
    {
        var obj = new JsonObject
        {
            ["action"] = "listen",
            ["rendezvousId"] = RendezvousId
        };
        return obj.ToJsonString();
    }

    public static ListenRequest FromJson(JsonObject json)
    {
        return new ListenRequest(json["rendezvousId"]!.ToString());
    }
}

/// <summary>
/// Client message sent to signaling broker to rendezvous with host and exchange encrypted candidates.
/// Schema: { "action": "rendezvous", "rendezvousId": "...", "clientPublicKey": "...", "encryptedPayload": "..." }
/// </summary>
public sealed record RendezvousRequest(
    string RendezvousId,
    string ClientPublicKeyHex,
    string EncryptedPayload) : SignalingMessage
{
    public override string ToJsonString()
    {
        var obj = new JsonObject
        {
            ["action"] = "rendezvous",
            ["rendezvousId"] = RendezvousId,
            ["clientPublicKey"] = ClientPublicKeyHex,
            ["encryptedPayload"] = EncryptedPayload
        };
        return obj.ToJsonString();
    }

    public static RendezvousRequest FromJson(JsonObject json)
    {
        return new RendezvousRequest(
            json["rendezvousId"]!.ToString(),
            json["clientPublicKey"]!.ToString(),
            json["encryptedPayload"]!.ToString());
    }
}

/// <summary>
/// Broker notification informing host that client has arrived with key and encrypted payload.
/// Schema: { "event": "peer_joined", "peerPublicKey": "...", "encryptedPayload": "..." }
/// </summary>
public sealed record PeerJoinedEvent(
    string PeerPublicKeyHex,
    string EncryptedPayload) : SignalingMessage
{
    public override string ToJsonString()
    {
        var obj = new JsonObject
        {
            ["event"] = "peer_joined",
            ["peerPublicKey"] = PeerPublicKeyHex,
            ["encryptedPayload"] = EncryptedPayload
        };
        return obj.ToJsonString();
    }

    public static PeerJoinedEvent FromJson(JsonObject json)
    {
        return new PeerJoinedEvent(
            json["peerPublicKey"]!.ToString(),
            json["encryptedPayload"]!.ToString());
    }
}

/// <summary>
/// Broker notification informing host that listening state is active.
/// Schema: { "event": "listening", "rendezvousId": "..." }
/// </summary>
public sealed record ListeningEvent(string RendezvousId) : SignalingMessage
{
    public override string ToJsonString()
    {
        var obj = new JsonObject
        {
            ["event"] = "listening",
            ["rendezvousId"] = RendezvousId
        };
        return obj.ToJsonString();
    }

    public static ListeningEvent FromJson(JsonObject json)
    {
        return new ListeningEvent(json["rendezvousId"]!.ToString());
    }
}

/// <summary>
/// Broker error notification.
/// Schema: { "error": "...", "message": "..." }
/// </summary>
public sealed record SignalingError(
    string ErrorCode,
    string? Message = null) : SignalingMessage
{
    public override string ToJsonString()
    {
        var obj = new JsonObject
        {
            ["error"] = ErrorCode
        };
        if (Message != null) obj["message"] = Message;
        return obj.ToJsonString();
    }

    public static SignalingError FromJson(JsonObject json)
    {
        string code = json["error"]!.ToString();
        string? msg = json.TryGetPropertyValue("message", out var m) && m != null ? m.ToString() : null;
        return new SignalingError(code, msg);
    }
}

/// <summary>
/// Parser and serializer for JSON-RPC remote signaling envelopes.
/// </summary>
public static class SignalingMessageCodec
{
    public static SignalingMessage Parse(string jsonString)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(jsonString);

        var node = JsonNode.Parse(jsonString);
        if (node is not JsonObject obj)
        {
            throw new FormatException("Signaling message must be a JSON object.");
        }

        if (obj.TryGetPropertyValue("action", out var actionNode) && actionNode != null)
        {
            return actionNode.ToString() switch
            {
                "listen" => ListenRequest.FromJson(obj),
                "rendezvous" => RendezvousRequest.FromJson(obj),
                _ => throw new FormatException($"Unknown action: '{actionNode}'")
            };
        }

        if (obj.TryGetPropertyValue("event", out var eventNode) && eventNode != null)
        {
            return eventNode.ToString() switch
            {
                "peer_joined" => PeerJoinedEvent.FromJson(obj),
                "listening" => ListeningEvent.FromJson(obj),
                _ => throw new FormatException($"Unknown event: '{eventNode}'")
            };
        }

        if (obj.TryGetPropertyValue("error", out _))
        {
            return SignalingError.FromJson(obj);
        }

        throw new FormatException("Unrecognized signaling message schema.");
    }
}
