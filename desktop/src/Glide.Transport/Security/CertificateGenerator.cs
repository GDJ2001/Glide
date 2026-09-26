using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Glide.Transport.Security;

public static class CertificateGenerator
{
    public static X509Certificate2 GenerateEphemeralCertificate(string commonName = "Glide-Desktop")
    {
        using var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(
            $"CN={commonName}",
            ecdsa,
            HashAlgorithmName.SHA256);

        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(false, false, 0, false));

        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
                true));

        var eku = new OidCollection
        {
            new Oid("1.3.6.1.5.5.7.3.1"), // Server Authentication
            new Oid("1.3.6.1.5.5.7.3.2")  // Client Authentication
        };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(eku, false));

        var sanBuilder = new SubjectAlternativeNameBuilder();
        sanBuilder.AddDnsName("localhost");
        if (!string.IsNullOrWhiteSpace(Environment.MachineName))
        {
            sanBuilder.AddDnsName(Environment.MachineName);
        }
        sanBuilder.AddIpAddress(IPAddress.Loopback);
        sanBuilder.AddIpAddress(IPAddress.IPv6Loopback);

        var addedIps = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            IPAddress.Loopback.ToString(),
            IPAddress.IPv6Loopback.ToString()
        };

        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily is AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)
                    {
                        var cleanIp = ua.Address.IsIPv6LinkLocal
                            ? new IPAddress(ua.Address.GetAddressBytes())
                            : ua.Address;
                        if (addedIps.Add(cleanIp.ToString()))
                        {
                            sanBuilder.AddIpAddress(cleanIp);
                        }
                    }
                }
            }

            foreach (var hostIp in Dns.GetHostAddresses(Dns.GetHostName()))
            {
                if (hostIp.AddressFamily is AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)
                {
                    var cleanIp = hostIp.IsIPv6LinkLocal
                        ? new IPAddress(hostIp.GetAddressBytes())
                        : hostIp;
                    if (addedIps.Add(cleanIp.ToString()))
                    {
                        sanBuilder.AddIpAddress(cleanIp);
                    }
                }
            }
        }
        catch
        {
            // Non-fatal fallback if interface enumeration fails
        }

        request.CertificateExtensions.Add(sanBuilder.Build());

        var notBefore = DateTimeOffset.UtcNow.AddMinutes(-5);
        var notAfter = DateTimeOffset.UtcNow.AddDays(1);

        using var cert = request.CreateSelfSigned(notBefore, notAfter);
        byte[] pfx = cert.Export(X509ContentType.Pfx, (string?)null);

        // On Windows SChannel, EphemeralKeySet is explicitly not supported.
        // UserKeySet | Exportable allows SChannel to acquire the credentials handle.
        return X509CertificateLoader.LoadPkcs12(pfx, (string?)null, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
    }
}
