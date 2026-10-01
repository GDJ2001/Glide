using System.Security.Cryptography;
using Glide.Core.Security;
using Glide.Transport.Sockets;

namespace Glide.Desktop.Services;

public static class SasCoordinator
{
    public static string CalculateSas(byte[] clientPublicKey, byte[] serverPublicKey, byte[] sessionNonce)
    {
        int code = SasCalculator.CalculateSas(clientPublicKey, serverPublicKey, sessionNonce);
        return code.ToString("D6");
    }

    public static string CalculateSas(TlsSocketConnection connection, string sessionId)
    {
        ArgumentNullException.ThrowIfNull(connection);

        byte[] localSpki = connection.LocalSpkiSha256 != null
            ? Convert.FromHexString(connection.LocalSpkiSha256)
            : Array.Empty<byte>();

        byte[] remoteSpki = connection.RemoteSpkiSha256 != null
            ? Convert.FromHexString(connection.RemoteSpkiSha256)
            : Array.Empty<byte>();

        byte[] nonce = System.Text.Encoding.UTF8.GetBytes(sessionId ?? "glide-sas-session");

        if (localSpki.Length == 0 || remoteSpki.Length == 0)
        {
            return "000000";
        }

        int code = SasCalculator.CalculateSas(localSpki, remoteSpki, nonce);
        return code.ToString("D6");
    }
}
