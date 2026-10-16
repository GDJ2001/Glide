using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using Glide.Core.Models;

namespace Glide.Desktop;

public static class DesktopTransferFailures
{
    public static TransferErrorCategory FromBootstrapCode(string? code) => code switch
    {
        "BUSY" => TransferErrorCategory.Protocol,
        "AUTHENTICATION_FAILED" or "INVITATION_EXPIRED" or "INVITATION_USED" => TransferErrorCategory.Security,
        _ => TransferErrorCategory.Protocol
    };

    public static TransferErrorCategory Classify(Exception error) => error switch
    {
        ProtocolViolationException protocol => FromCode(protocol.FailureCode),
        AuthenticationException or CryptographicException => TransferErrorCategory.Security,
        SocketException or EndOfStreamException or TimeoutException => TransferErrorCategory.Network,
        UnauthorizedAccessException or DirectoryNotFoundException or FileNotFoundException => TransferErrorCategory.Storage,
        IOException io when io.InnerException is SocketException => TransferErrorCategory.Network,
        IOException => TransferErrorCategory.Storage,
        OverflowException => TransferErrorCategory.LimitExceeded,
        _ => TransferErrorCategory.Unknown
    };

    public static TransferErrorCategory FromCode(string code) => code switch
    {
        WireErrorCodes.AuthenticationFailed or WireErrorCodes.IntegrityMismatch => TransferErrorCategory.Security,
        WireErrorCodes.InsufficientStorage or WireErrorCodes.SourceUnavailable or
            WireErrorCodes.DestinationUnavailable or WireErrorCodes.ReadFailure or
            WireErrorCodes.WriteFailure => TransferErrorCategory.Storage,
        WireErrorCodes.TransferInterrupted or WireErrorCodes.Timeout or WireErrorCodes.PeerLost => TransferErrorCategory.Network,
        WireErrorCodes.LimitExceeded => TransferErrorCategory.LimitExceeded,
        WireErrorCodes.UserRejected => TransferErrorCategory.UserRejected,
        WireErrorCodes.UserCancelled or WireErrorCodes.SystemCancelled => TransferErrorCategory.UserCancelled,
        _ => TransferErrorCategory.Protocol
    };

    public static bool IsDiskFull(Exception error)
    {
        if (error is ProtocolViolationException pve &&
            string.Equals(pve.FailureCode, WireErrorCodes.InsufficientStorage, StringComparison.Ordinal))
        {
            return true;
        }

        if (error is IOException io)
        {
            const int HrErrorDiskFull = unchecked((int)0x80070070);
            const int HrErrorHandleDiskFull = unchecked((int)0x80070027);
            const int Enospc = 28;
            return io.HResult == HrErrorDiskFull ||
                   io.HResult == HrErrorHandleDiskFull ||
                   io.HResult == Enospc ||
                   io.Message.Contains("not enough space", StringComparison.OrdinalIgnoreCase) ||
                   io.Message.Contains("disk full", StringComparison.OrdinalIgnoreCase);
        }

        return false;
    }
}
