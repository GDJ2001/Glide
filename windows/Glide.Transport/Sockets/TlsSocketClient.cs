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

    public async Task<TlsSocketConnection> ConnectAsync(
        string host,
        int port,
        CancellationToken cancellationToken = default)
    {
        var tcpClient = new TcpClient();
        tcpClient.NoDelay = true;
        await tcpClient.ConnectAsync(host, port, cancellationToken);

        byte[]? remotePublicKey = null;

        var sslStream = new SslStream(tcpClient.GetStream(), leaveInnerStreamOpen: false);

        var clientCertCollection = new X509Certificate2Collection(_clientCertificate);

        var options = new SslClientAuthenticationOptions
        {
            TargetHost = host,
            ClientCertificates = clientCertCollection,
            EnabledSslProtocols = SslProtocols.Tls13,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            LocalCertificateSelectionCallback = (sender, targetHost, localCertificates, remoteCertificate, acceptableIssuers) =>
            {
                return _clientCertificate;
            },
            RemoteCertificateValidationCallback = (sender, certificate, chain, sslPolicyErrors) =>
            {
                if (certificate != null)
                {
                    remotePublicKey = certificate.GetPublicKey();
                    return true;
                }
                return false;
            }
        };

        await sslStream.AuthenticateAsClientAsync(options, cancellationToken);

        if (remotePublicKey == null)
        {
            throw new AuthenticationException("Peer server certificate did not provide a valid public key.");
        }

        return new TlsSocketConnection(
            tcpClient,
            sslStream,
            _clientCertificate.GetPublicKey(),
            remotePublicKey);
    }
}
