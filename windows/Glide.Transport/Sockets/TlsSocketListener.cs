using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Glide.Transport.Security;

namespace Glide.Transport.Sockets;

public sealed class TlsSocketConnection : IAsyncDisposable
{
    public TcpClient TcpClient { get; }
    public SslStream SslStream { get; }
    public byte[] LocalPublicKeyBytes { get; }
    public byte[] RemotePublicKeyBytes { get; }
    public string? LocalSpkiSha256 { get; }
    public string? RemoteSpkiSha256 { get; }

    public TlsSocketConnection(
        TcpClient tcpClient,
        SslStream sslStream,
        byte[] localPublicKeyBytes,
        byte[] remotePublicKeyBytes,
        string? localSpkiSha256 = null,
        string? remoteSpkiSha256 = null)
    {
        TcpClient = tcpClient;
        SslStream = sslStream;
        LocalPublicKeyBytes = localPublicKeyBytes;
        RemotePublicKeyBytes = remotePublicKeyBytes;
        LocalSpkiSha256 = localSpkiSha256;
        RemoteSpkiSha256 = remoteSpkiSha256;
    }

    public async ValueTask DisposeAsync()
    {
        await SslStream.DisposeAsync();
        TcpClient.Dispose();
    }
}

public sealed class TlsSocketListener : IDisposable
{
    private readonly TcpListener _listener;
    private readonly X509Certificate2 _serverCertificate;
    public int BoundPort { get; }
    public X509Certificate2 ServerCertificate => _serverCertificate;
    public string LocalSpkiSha256 => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(_serverCertificate.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();

    public TlsSocketListener(int port = 0, X509Certificate2? certificate = null)
    {
        _serverCertificate = certificate ?? CertificateGenerator.GenerateEphemeralCertificate("Glide-Desktop-Server");
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        BoundPort = ((IPEndPoint)_listener.LocalEndpoint).Port;
    }

    public async Task<TlsSocketConnection> AcceptConnectionAsync(CancellationToken cancellationToken = default)
    {
        var tcpClient = await _listener.AcceptTcpClientAsync(cancellationToken);
        tcpClient.NoDelay = true;

        byte[]? remotePublicKey = null;
        string? remoteSpkiSha256 = null;

        var sslStream = new SslStream(tcpClient.GetStream(), leaveInnerStreamOpen: false);

        var options = new SslServerAuthenticationOptions
        {
            ServerCertificate = _serverCertificate,
            ClientCertificateRequired = true,
            EnabledSslProtocols = SslProtocols.Tls13,
            ApplicationProtocols = new List<SslApplicationProtocol>
            {
                new SslApplicationProtocol("glide-lan/1"),
                new SslApplicationProtocol("glide-v1")
            },
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = (sender, certificate, chain, sslPolicyErrors) =>
            {
                if (certificate != null)
                {
                    remotePublicKey = certificate.GetPublicKey();
                    if (certificate is X509Certificate2 x509)
                    {
                        remoteSpkiSha256 = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(x509.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
                    }
                    else
                    {
                        using var tempCert = new X509Certificate2(certificate);
                        remoteSpkiSha256 = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(tempCert.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
                    }
                    return true;
                }
                return false;
            }
        };

        await sslStream.AuthenticateAsServerAsync(options, cancellationToken);

        if (remotePublicKey == null)
        {
            throw new AuthenticationException("Peer client certificate did not provide a valid public key.");
        }

        return new TlsSocketConnection(
            tcpClient,
            sslStream,
            _serverCertificate.GetPublicKey(),
            remotePublicKey,
            LocalSpkiSha256,
            remoteSpkiSha256);
    }

    public void Dispose()
    {
        _listener.Stop();
        _serverCertificate.Dispose();
    }
}
