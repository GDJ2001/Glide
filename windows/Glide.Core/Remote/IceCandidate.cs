using System.Net;
using System.Text.Json.Nodes;

namespace Glide.Core.Remote;

/// <summary>
/// RFC 8445 ICE Candidate Types.
/// </summary>
public enum IceCandidateType
{
    Host,
    ServerReflexive,
    PeerReflexive,
    Relayed
}

/// <summary>
/// Represents an RFC 8445 Interactive Connectivity Establishment (ICE) candidate.
/// Encapsulates local and public IP:port candidate descriptors, transport protocol,
/// and deterministic priority calculation (RFC 8445 Section 5.1.2).
/// </summary>
public sealed class IceCandidate
{
    public string Foundation { get; }
    public byte ComponentId { get; }
    public string Transport { get; }
    public uint Priority { get; }
    public IPEndPoint EndPoint { get; }
    public IceCandidateType Type { get; }
    public IPEndPoint? RelatedEndPoint { get; }

    public IceCandidate(
        string foundation,
        byte componentId,
        string transport,
        uint priority,
        IPEndPoint endPoint,
        IceCandidateType type,
        IPEndPoint? relatedEndPoint = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(foundation);
        ArgumentException.ThrowIfNullOrWhiteSpace(transport);
        ArgumentNullException.ThrowIfNull(endPoint);

        Foundation = foundation;
        ComponentId = componentId;
        Transport = transport.ToLowerInvariant();
        Priority = priority;
        EndPoint = endPoint;
        Type = type;
        RelatedEndPoint = relatedEndPoint;
    }

    /// <summary>
    /// Computes candidate priority per RFC 8445 Section 5.1.2:
    /// priority = (2^24 * typePreference) + (2^8 * localPreference) + (256 - componentId)
    /// </summary>
    public static uint CalculatePriority(
        IceCandidateType type,
        ushort localPreference = 65535,
        byte componentId = 1)
    {
        uint typePref = type switch
        {
            IceCandidateType.Host => 126,
            IceCandidateType.PeerReflexive => 110,
            IceCandidateType.ServerReflexive => 100,
            IceCandidateType.Relayed => 0,
            _ => 0
        };

        return (typePref << 24) | ((uint)localPreference << 8) | (uint)(256 - componentId);
    }

    /// <summary>
    /// Computes an RFC 8445 candidate foundation string based on candidate type, IP address, and transport protocol.
    /// </summary>
    public static string ComputeFoundation(string candidateType, string ip, string transport)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(candidateType);
        ArgumentException.ThrowIfNullOrWhiteSpace(ip);
        ArgumentException.ThrowIfNullOrWhiteSpace(transport);

        byte[] input = System.Text.Encoding.UTF8.GetBytes($"{candidateType}:{ip}:{transport.ToLowerInvariant()}");
        byte[] hash = System.Security.Cryptography.SHA256.HashData(input);
        return Convert.ToHexString(hash)[..8].ToLowerInvariant();
    }

    /// <summary>
    /// Factory for creating a Host candidate.
    /// </summary>
    public static IceCandidate CreateHost(string foundation, IPEndPoint endPoint, ushort localPref = 65535)
    {
        uint priority = CalculatePriority(IceCandidateType.Host, localPref, componentId: 1);
        return new IceCandidate(foundation, 1, "udp", priority, endPoint, IceCandidateType.Host);
    }

    /// <summary>
    /// Factory for creating a Server-Reflexive candidate discovered via STUN.
    /// </summary>
    public static IceCandidate CreateServerReflexive(
        string foundation,
        IPEndPoint publicEndPoint,
        IPEndPoint baseEndPoint,
        ushort localPref = 65535)
    {
        uint priority = CalculatePriority(IceCandidateType.ServerReflexive, localPref, componentId: 1);
        return new IceCandidate(foundation, 1, "udp", priority, publicEndPoint, IceCandidateType.ServerReflexive, baseEndPoint);
    }

    /// <summary>
    /// Factory for creating a Relayed candidate discovered via TURN.
    /// </summary>
    public static IceCandidate CreateRelayed(
        string foundation,
        IPEndPoint relayedEndPoint,
        IPEndPoint baseEndPoint,
        ushort localPref = 0)
    {
        uint priority = CalculatePriority(IceCandidateType.Relayed, localPref, componentId: 1);
        return new IceCandidate(foundation, 1, "udp", priority, relayedEndPoint, IceCandidateType.Relayed, baseEndPoint);
    }

    /// <summary>
    /// Formats candidate into standard SDP candidate-attribute line (RFC 8445 Section 5.1.3).
    /// Format: candidate:<foundation> <component-id> <transport> <priority> <address> <port> typ <type> [raddr <raddress> rport <rport>]
    /// </summary>
    public string ToSdpString()
    {
        string typeStr = Type switch
        {
            IceCandidateType.Host => "host",
            IceCandidateType.ServerReflexive => "srflx",
            IceCandidateType.PeerReflexive => "prflx",
            IceCandidateType.Relayed => "relay",
            _ => "host"
        };

        string sdp = $"candidate:{Foundation} {ComponentId} {Transport.ToUpperInvariant()} {Priority} {EndPoint.Address} {EndPoint.Port} typ {typeStr}";

        if (RelatedEndPoint != null)
        {
            sdp += $" raddr {RelatedEndPoint.Address} rport {RelatedEndPoint.Port}";
        }

        return sdp;
    }

    /// <summary>
    /// Parses an SDP candidate-attribute line into an IceCandidate instance.
    /// </summary>
    public static IceCandidate ParseSdpString(string sdp)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sdp);

        string cleaned = sdp.Trim();
        if (cleaned.StartsWith("candidate:", StringComparison.OrdinalIgnoreCase))
        {
            cleaned = cleaned["candidate:".Length..];
        }

        string[] tokens = cleaned.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (tokens.Length < 8)
        {
            throw new FormatException($"Invalid SDP candidate line: '{sdp}'");
        }

        string foundation = tokens[0];
        byte componentId = byte.Parse(tokens[1]);
        string transport = tokens[2].ToLowerInvariant();
        uint priority = uint.Parse(tokens[3]);
        var address = IPAddress.Parse(tokens[4]);
        int port = int.Parse(tokens[5]);
        var endPoint = new IPEndPoint(address, port);

        if (!string.Equals(tokens[6], "typ", StringComparison.OrdinalIgnoreCase))
        {
            throw new FormatException($"Expected 'typ' keyword in SDP candidate line: '{sdp}'");
        }

        var type = tokens[7].ToLowerInvariant() switch
        {
            "host" => IceCandidateType.Host,
            "srflx" => IceCandidateType.ServerReflexive,
            "prflx" => IceCandidateType.PeerReflexive,
            "relay" => IceCandidateType.Relayed,
            _ => throw new FormatException($"Unknown candidate type: '{tokens[7]}'")
        };

        IPEndPoint? relatedEndPoint = null;
        for (int i = 8; i + 3 < tokens.Length; i += 2)
        {
            if (string.Equals(tokens[i], "raddr", StringComparison.OrdinalIgnoreCase) &&
                string.Equals(tokens[i + 2], "rport", StringComparison.OrdinalIgnoreCase))
            {
                var rAddress = IPAddress.Parse(tokens[i + 1]);
                int rPort = int.Parse(tokens[i + 3]);
                relatedEndPoint = new IPEndPoint(rAddress, rPort);
                break;
            }
        }

        return new IceCandidate(foundation, componentId, transport, priority, endPoint, type, relatedEndPoint);
    }

    /// <summary>
    /// Serializes candidate to a compact JsonObject for encrypted signaling transmission.
    /// </summary>
    public JsonObject ToJson()
    {
        var obj = new JsonObject
        {
            ["foundation"] = Foundation,
            ["componentId"] = ComponentId,
            ["transport"] = Transport,
            ["priority"] = Priority,
            ["ip"] = EndPoint.Address.ToString(),
            ["port"] = EndPoint.Port,
            ["type"] = Type.ToString().ToLowerInvariant()
        };

        if (RelatedEndPoint != null)
        {
            obj["relatedIp"] = RelatedEndPoint.Address.ToString();
            obj["relatedPort"] = RelatedEndPoint.Port;
        }

        return obj;
    }

    /// <summary>
    /// Deserializes candidate from a JsonObject.
    /// </summary>
    public static IceCandidate FromJson(JsonObject json)
    {
        ArgumentNullException.ThrowIfNull(json);

        string foundation = json["foundation"]!.ToString();
        byte componentId = json["componentId"]!.GetValue<byte>();
        string transport = json["transport"]!.ToString();
        uint priority = json["priority"]!.GetValue<uint>();
        var ip = IPAddress.Parse(json["ip"]!.ToString());
        int port = json["port"]!.GetValue<int>();
        var endPoint = new IPEndPoint(ip, port);

        string typeStr = json["type"]!.ToString();
        var type = typeStr.ToLowerInvariant() switch
        {
            "host" => IceCandidateType.Host,
            "serverreflexive" => IceCandidateType.ServerReflexive,
            "peerreflexive" => IceCandidateType.PeerReflexive,
            "relayed" => IceCandidateType.Relayed,
            _ => throw new FormatException($"Unknown candidate type: '{typeStr}'")
        };

        IPEndPoint? relatedEndPoint = null;
        if (json.TryGetPropertyValue("relatedIp", out var rIpNode) && rIpNode != null &&
            json.TryGetPropertyValue("relatedPort", out var rPortNode) && rPortNode != null)
        {
            var rIp = IPAddress.Parse(rIpNode.ToString());
            int rPort = rPortNode.GetValue<int>();
            relatedEndPoint = new IPEndPoint(rIp, rPort);
        }

        return new IceCandidate(foundation, componentId, transport, priority, endPoint, type, relatedEndPoint);
    }
}
