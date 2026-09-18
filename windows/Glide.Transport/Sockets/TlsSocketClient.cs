using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Glide.Transport.Security;

namespace Glide.Transport.Sockets;

public sealed class TlsSocketClient
{
    private readonly X509Certificate2 _clientCertificate;

    public TlsSocketClient(X509Certificate2? clientCertificate = null)
    {
        _clientCertificate = clientCertificate ?? CertificateGenerator.GenerateEphemeralCertificate("Glide-Desktop-Client");
    }

    public Task<TlsSocketConnection> ConnectAsync(
        string host,
        int port,
        CancellationToken cancellationToken) =>
        ConnectAsync(host, port, expectedSpkiSha256: null, cancellationToken);

    public async Task<TlsSocketConnection> ConnectAsync(
        string host,
        int port,
        string? expectedSpkiSha256 = null,
        CancellationToken cancellationToken = default)
    {
        var tcpClient = new TcpClient();
        tcpClient.NoDelay = true;
        await tcpClient.ConnectAsync(host, port, cancellationToken);

        byte[]? remotePublicKey = null;
        string? remoteSpkiSha256 = null;

        var sslStream = new SslStream(tcpClient.GetStream(), leaveInnerStreamOpen: false);

        var clientCertCollection = new X509Certificate2Collection(_clientCertificate);

        var options = new SslClientAuthenticationOptions
        {
            TargetHost = host,
            ClientCertificates = clientCertCollection,
            EnabledSslProtocols = SslProtocols.Tls13,
            ApplicationProtocols = new List<SslApplicationProtocol>
            {
                new SslApplicationProtocol("glide-lan/1")
            },
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            LocalCertificateSelectionCallback = (sender, targetHost, localCertificates, remoteCertificate, acceptableIssuers) =>
            {
                return _clientCertificate;
            },
            RemoteCertificateValidationCallback = (sender, certificate, chain, sslPolicyErrors) =>
            {
                if (certificate != null)
                {
                    string computedSpkiHex;
                    if (certificate is X509Certificate2 x509)
                    {
                        computedSpkiHex = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(x509.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
                    }
                    else
                    {
                        using var tempCert = new X509Certificate2(certificate);
                        computedSpkiHex = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(tempCert.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
                    }

                    if (!string.IsNullOrEmpty(expectedSpkiSha256))
                    {
                        if (!string.Equals(computedSpkiHex, expectedSpkiSha256, StringComparison.OrdinalIgnoreCase))
                        {
                            // SPKI Pin mismatch -> rejects connection immediately
                            return false;
                        }
                    }

                    remotePublicKey = certificate.GetPublicKey();
                    remoteSpkiSha256 = computedSpkiHex;
                    return true;
                }
                return false;
            }
        };

        await sslStream.AuthenticateAsClientAsync(options, cancellationToken);

        if (sslStream.NegotiatedApplicationProtocol != new SslApplicationProtocol("glide-lan/1"))
        {
            await sslStream.DisposeAsync();
            tcpClient.Dispose();
            throw new AuthenticationException("Peer did not negotiate required ALPN 'glide-lan/1'.");
        }

        if (remotePublicKey == null)
        {
            throw new AuthenticationException("Peer server certificate did not provide a valid public key or failed SPKI pinning.");
        }

        string localSpkiHex = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(_clientCertificate.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();

        return new TlsSocketConnection(
            tcpClient,
            sslStream,
            _clientCertificate.GetPublicKey(),
            remotePublicKey,
            localSpkiHex,
            remoteSpkiSha256);
    }
}
