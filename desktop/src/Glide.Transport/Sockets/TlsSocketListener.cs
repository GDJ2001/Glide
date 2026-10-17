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
    public Stream TransportStream { get; private set; }
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
        TransportStream = sslStream;
        LocalPublicKeyBytes = localPublicKeyBytes;
        RemotePublicKeyBytes = remotePublicKeyBytes;
        LocalSpkiSha256 = localSpkiSha256;
        RemoteSpkiSha256 = remoteSpkiSha256;
    }

    public void PrependBytes(ReadOnlySpan<byte> prefix)
    {
        if (prefix.IsEmpty) return;
        TransportStream = new Framing.PrefixedStream(TransportStream, prefix, leaveInnerOpen: true);
    }

    public void AbortTransport()
    {
        try { TcpClient.Client?.Close(0); } catch { }
        try { TcpClient.Close(); } catch { }
        try { SslStream.Close(); } catch { }
    }

    public async ValueTask DisposeAsync()
    {
        try { await SslStream.DisposeAsync(); } catch { }
        try { TcpClient.Dispose(); } catch { }
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

    public Task<TcpClient> AcceptTcpClientAsync(CancellationToken cancellationToken = default) =>
        _listener.AcceptTcpClientAsync(cancellationToken).AsTask();

    public async Task<TlsSocketConnection> AcceptConnectionAsync(
        CancellationToken cancellationToken = default,
        TimeSpan? handshakeTimeout = null)
    {
        var tcpClient = await _listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
        return await AuthenticateAcceptedClientAsync(tcpClient, cancellationToken, handshakeTimeout).ConfigureAwait(false);
    }

    public async Task<TlsSocketConnection> AuthenticateAcceptedClientAsync(
        TcpClient tcpClient,
        CancellationToken cancellationToken = default,
        TimeSpan? handshakeTimeout = null)
    {
        ArgumentNullException.ThrowIfNull(tcpClient);
        tcpClient.NoDelay = true;

        byte[]? remotePublicKey = null;
        string? remoteSpkiSha256 = null;

        var sslStream = new SslStream(tcpClient.GetStream(), leaveInnerStreamOpen: false);

        var options = new SslServerAuthenticationOptions
        {
            ServerCertificate = _serverCertificate,
            // Initial LAN pairing is server-authenticated by the invitation SPKI pin.
            // Persistent mutually-authenticated identities are negotiated only by the
            // versioned trust extension (HARD-018..021), not by an unconditional TLS
            // client-certificate requirement.
            ClientCertificateRequired = false,
            EnabledSslProtocols = SslProtocols.Tls13,
            ApplicationProtocols =
            [
                new SslApplicationProtocol("glide-lan/1")
            ],
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
                // A client certificate is optional for invitation-authenticated pairing.
                return true;
            }
        };

        try
        {
            // An accepted TCP socket must not hold the listener indefinitely without completing TLS.
            TimeSpan effectiveHandshakeTimeout = handshakeTimeout ?? TimeSpan.FromSeconds(15);
            using var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            if (effectiveHandshakeTimeout > TimeSpan.Zero && effectiveHandshakeTimeout != Timeout.InfiniteTimeSpan)
            {
                handshakeCts.CancelAfter(effectiveHandshakeTimeout);
            }
            await sslStream.AuthenticateAsServerAsync(options, handshakeCts.Token).ConfigureAwait(false);

            if (sslStream.SslProtocol != SslProtocols.Tls13)
            {
                throw new AuthenticationException(
                    $"Glide LAN requires TLS 1.3, negotiated {sslStream.SslProtocol}.");
            }

            var expectedAlpn = new SslApplicationProtocol("glide-lan/1");
            if (sslStream.NegotiatedApplicationProtocol != expectedAlpn)
            {
                throw new AuthenticationException(
                    "Glide LAN requires ALPN 'glide-lan/1'.");
            }

            return new TlsSocketConnection(
                tcpClient,
                sslStream,
                _serverCertificate.GetPublicKey(),
                remotePublicKey ?? Array.Empty<byte>(),
                LocalSpkiSha256,
                remoteSpkiSha256);
        }
        catch
        {
            try { await sslStream.DisposeAsync().ConfigureAwait(false); } catch { }
            try { tcpClient.Dispose(); } catch { }
            throw;
        }
    }

    public void Dispose()
    {
        _listener.Stop();
        _serverCertificate.Dispose();
    }
}
