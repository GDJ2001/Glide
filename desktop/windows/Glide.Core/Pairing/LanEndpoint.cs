using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace Glide.Core.Pairing;

/// <summary>
/// Numeric IP and port endpoint for LAN direct connection (LAN-006, Section 8).
/// Enforces numeric-only IPv4/IPv6 literals without hostnames, schemes, paths, or percent-zones.
/// </summary>
public sealed record LanEndpoint
{
    private static readonly Regex Ipv4Regex = new(
        @"^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$",
        RegexOptions.Compiled);

    public string Address { get; }
    public int Port { get; }

    public LanEndpoint(string address, int port)
    {
        if (!IsValidAddress(address))
        {
            throw new ArgumentException($"Endpoint address must be a valid numeric IPv4 or IPv6 literal: '{address}'", nameof(address));
        }

        if (port is < 1 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(port), $"Endpoint port must be in range 1..65535, got: {port}");
        }

        Address = address;
        Port = port;
    }

    public static bool IsValidAddress(string? addr)
    {
        if (string.IsNullOrWhiteSpace(addr) ||
            addr.Contains('/') ||
            addr.Contains('%') ||
            addr.Contains(' ') ||
            addr.Contains("://"))
        {
            return false;
        }

        if (Ipv4Regex.IsMatch(addr))
        {
            string[] parts = addr.Split('.');
            foreach (string part in parts)
            {
                if (part.Length > 1 && part.StartsWith('0'))
                {
                    return false;
                }
            }
            return true;
        }

        return IsValidIpv6(addr);
    }

    private static bool IsValidIpv6(string addr)
    {
        if (!addr.Contains(':')) return false;
        if (addr.StartsWith(':') && !addr.StartsWith("::")) return false;
        if (addr.EndsWith(':') && !addr.EndsWith("::")) return false;
        if (addr.Count(c => c == ':') > 7 && !addr.Contains("::")) return false;

        int firstDoubleColon = addr.IndexOf("::", StringComparison.Ordinal);
        int lastDoubleColon = addr.LastIndexOf("::", StringComparison.Ordinal);
        if (firstDoubleColon != lastDoubleColon) return false;

        string[] parts = addr.Split(':');
        foreach (string p in parts)
        {
            if (p.Length == 0) continue;
            if (p.Length > 4) return false;
            foreach (char c in p)
            {
                if (!char.IsAsciiHexDigit(c))
                {
                    return false;
                }
            }
        }

        return IPAddress.TryParse(addr, out var ip) && ip.AddressFamily == AddressFamily.InterNetworkV6;
    }
}
