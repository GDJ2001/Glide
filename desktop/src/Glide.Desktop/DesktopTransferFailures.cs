using System.ComponentModel;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using Glide.Core.Models;
using Glide.Core.Storage;

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
        IOException io when io.Message.Contains("integrity", StringComparison.OrdinalIgnoreCase) ||
                            io.Message.Contains("SHA-256", StringComparison.OrdinalIgnoreCase) => TransferErrorCategory.Security,
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

        if (error is Win32Exception win32 && win32.NativeErrorCode == QuarantineStagingStore.LinuxEnospc)
        {
            return true;
        }

        if (error is IOException io)
        {
            const int HrErrorDiskFull = unchecked((int)0x80070070);
            const int HrErrorHandleDiskFull = unchecked((int)0x80070027);
            const int Enospc = QuarantineStagingStore.LinuxEnospc;
            return io.HResult == HrErrorDiskFull ||
                   io.HResult == HrErrorHandleDiskFull ||
                   io.HResult == Enospc ||
                   io.InnerException is Win32Exception { NativeErrorCode: Enospc } ||
                   io.Message.Contains("not enough space", StringComparison.OrdinalIgnoreCase) ||
                   io.Message.Contains("disk full", StringComparison.OrdinalIgnoreCase) ||
                   io.Message.Contains("Insufficient disk space", StringComparison.OrdinalIgnoreCase) ||
                   io.Message.Contains("No space left on device", StringComparison.OrdinalIgnoreCase);
        }

        return false;
    }

    public static bool IsPermissionDenied(Exception error)
    {
        if (error is UnauthorizedAccessException)
        {
            return true;
        }

        if (error is Win32Exception win32 &&
            win32.NativeErrorCode is QuarantineStagingStore.LinuxEacces or QuarantineStagingStore.LinuxEperm)
        {
            return true;
        }

        return error is IOException io &&
               io.InnerException is Win32Exception
               {
                   NativeErrorCode: QuarantineStagingStore.LinuxEacces or QuarantineStagingStore.LinuxEperm
               };
    }

    public static bool IsDestinationUnavailable(Exception error)
    {
        if (error is DirectoryNotFoundException)
        {
            return true;
        }

        if (error is ProtocolViolationException pve &&
            string.Equals(pve.FailureCode, WireErrorCodes.DestinationUnavailable, StringComparison.Ordinal))
        {
            return true;
        }

        return error is IOException io &&
               (io.InnerException is Win32Exception { NativeErrorCode: QuarantineStagingStore.LinuxErofs } ||
                io.Message.Contains("read-only", StringComparison.OrdinalIgnoreCase) ||
                io.Message.Contains("destination directory", StringComparison.OrdinalIgnoreCase));
    }

    public static string DescribeUserMessage(DesktopTransferDirection direction, Exception error)
    {
        if (IsDiskFull(error))
        {
            return direction == DesktopTransferDirection.Receive
                ? "Receive failed: Not enough free disk space in the receiving folder or staging area. Free up space or choose another folder in Settings."
                : "Send failed: Not enough free disk space to complete this transfer.";
        }

        if (IsPermissionDenied(error))
        {
            return direction == DesktopTransferDirection.Receive
                ? "Receive failed: Permission denied when writing to the receiving folder. Choose a writable folder in Settings."
                : "Send failed: Permission denied when reading a selected file.";
        }

        if (IsDestinationUnavailable(error))
        {
            return "Receive failed: The receiving folder is unavailable or read-only. Choose a writable folder in Settings.";
        }

        string prefix = direction == DesktopTransferDirection.Receive ? "Receive failed" : "Send failed";
        return $"{prefix}: {error.Message}";
    }
}
