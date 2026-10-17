using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;

namespace Glide.Transport.Sockets;

/// <summary>
/// Glide LAN TLS 1.3 client.
///
/// Initial pairing authenticates the receiver with the exact SPKI SHA-256 pin carried by the
/// out-of-band invitation. A client certificate is optional and reserved for a future negotiated
/// persistent-identity profile; it is never generated implicitly for an initial pairing.
/// </summary>
public sealed class TlsSocketClient
{
    private readonly X509Certificate2? _clientCertificate;

    public TlsSocketClient(X509Certificate2? clientCertificate = null)
    {
        _clientCertificate = clientCertificate;
    }

    /// <summary>
    /// Test/low-level transport overload. Production transfer orchestration must use the pinned
    /// overload with an authenticated invitation.
    /// </summary>
    public Task<TlsSocketConnection> ConnectAsync(
        string host,
        int port,
        CancellationToken cancellationToken) =>
        ConnectAsync(
            host,
            port,
            expectedSpkiSha256: null,
            cancellationToken);

    public async Task<TlsSocketConnection> ConnectAsync(
        string host,
        int port,
        string? expectedSpkiSha256 = null,
        CancellationToken cancellationToken = default,
        TimeSpan? tcpConnectTimeout = null,
        TimeSpan? tlsHandshakeTimeout = null)
    {
        var tcpClient = new TcpClient
        {
            NoDelay = true
        };

        TimeSpan effectiveConnectTimeout = tcpConnectTimeout ?? TimeSpan.FromSeconds(5);
        TimeSpan effectiveHandshakeTimeout = tlsHandshakeTimeout ?? TimeSpan.FromSeconds(15);

        SslStream? sslStream = null;
        try
        {
            using (var connectCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
            {
                if (effectiveConnectTimeout > TimeSpan.Zero && effectiveConnectTimeout != Timeout.InfiniteTimeSpan)
                {
                    connectCts.CancelAfter(effectiveConnectTimeout);
                }

                try
                {
                    await tcpClient.ConnectAsync(host, port, connectCts.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    throw new TimeoutException($"TCP connection to {host}:{port} timed out after {effectiveConnectTimeout.TotalSeconds:F1}s.");
                }
            }

            byte[]? remotePublicKey = null;
            string? remoteSpkiSha256 = null;

            sslStream = new SslStream(
                tcpClient.GetStream(),
                leaveInnerStreamOpen: false);

            var options = new SslClientAuthenticationOptions
            {
                TargetHost = host,
                EnabledSslProtocols = SslProtocols.Tls13,
                ApplicationProtocols =
                [
                    new SslApplicationProtocol("glide-lan/1")
                ],
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                RemoteCertificateValidationCallback =
                    (_, certificate, _, _) =>
                    {
                        if (certificate == null) return false;

                        string computedSpkiHex;
                        if (certificate is X509Certificate2 x509)
                        {
                            computedSpkiHex = Convert.ToHexString(
                                System.Security.Cryptography.SHA256.HashData(
                                    x509.PublicKey.ExportSubjectPublicKeyInfo()))
                                .ToLowerInvariant();
                        }
                        else
                        {
                            using var tempCertificate =
                                new X509Certificate2(certificate);
                            computedSpkiHex = Convert.ToHexString(
                                System.Security.Cryptography.SHA256.HashData(
                                    tempCertificate.PublicKey
                                        .ExportSubjectPublicKeyInfo()))
                                .ToLowerInvariant();
                        }

                        if (!string.IsNullOrEmpty(expectedSpkiSha256) &&
                            !string.Equals(
                                computedSpkiHex,
                                expectedSpkiSha256,
                                StringComparison.Ordinal))
                        {
                            return false;
                        }

                        remotePublicKey = certificate.GetPublicKey();
                        remoteSpkiSha256 = computedSpkiHex;
                        return true;
                    }
            };

            if (_clientCertificate != null)
            {
                options.ClientCertificates =
                    new X509Certificate2Collection(_clientCertificate);
                options.LocalCertificateSelectionCallback =
                    (_, _, _, _, _) => _clientCertificate;
            }

            using (var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
            {
                if (effectiveHandshakeTimeout > TimeSpan.Zero && effectiveHandshakeTimeout != Timeout.InfiniteTimeSpan)
                {
                    handshakeCts.CancelAfter(effectiveHandshakeTimeout);
                }

                try
                {
                    await sslStream.AuthenticateAsClientAsync(
                        options,
                        handshakeCts.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    throw new TimeoutException($"TLS 1.3 handshake with {host}:{port} timed out after {effectiveHandshakeTimeout.TotalSeconds:F1}s.");
                }
            }

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

            if (remotePublicKey == null || remoteSpkiSha256 == null)
            {
                throw new AuthenticationException(
                    "Peer server certificate did not provide a usable public key.");
            }

            byte[] localPublicKey = _clientCertificate?.GetPublicKey()
                ?? Array.Empty<byte>();
            string? localSpkiSha256 = _clientCertificate == null
                ? null
                : Convert.ToHexString(
                    System.Security.Cryptography.SHA256.HashData(
                        _clientCertificate.PublicKey
                            .ExportSubjectPublicKeyInfo()))
                    .ToLowerInvariant();

            return new TlsSocketConnection(
                tcpClient,
                sslStream,
                localPublicKey,
                remotePublicKey,
                localSpkiSha256,
                remoteSpkiSha256);
        }
        catch
        {
            if (sslStream != null)
            {
                await sslStream.DisposeAsync();
            }
            tcpClient.Dispose();
            throw;
        }
    }
}
