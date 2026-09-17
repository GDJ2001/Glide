using System.Security.Cryptography;
using Glide.Core.Models;

namespace Glide.Core.Storage;

public sealed class QuarantineStagingStore
{
    private readonly string _stagingRoot;
    private readonly string _destinationRoot;

    public QuarantineStagingStore(string? stagingRoot = null, string? destinationRoot = null)
    {
        _stagingRoot = stagingRoot ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Glide", "staging");

        _destinationRoot = destinationRoot ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads");
    }

    public string GetQuarantinePath(SessionId sessionId, ItemId itemId)
    {
        return Path.Combine(_stagingRoot, sessionId.Value, $"{itemId.Value}.quarantine");
    }

    public async Task<(string committedPath, Sha256Digest actualDigest)> WriteAndCommitAsync(
        SessionId sessionId,
        ItemId itemId,
        string rawRelativePath,
        Stream inputStream,
        Sha256Digest expectedDigest,
        long expectedSizeBytes,
        CancellationToken cancellationToken = default)
    {
        string sanitizedRelativePath = WindowsPathSanitizer.SanitizeRelativePath(rawRelativePath);
        string quarantinePath = GetQuarantinePath(sessionId, itemId);
        string? quarantineDir = Path.GetDirectoryName(quarantinePath);
        if (quarantineDir != null && !Directory.Exists(quarantineDir))
        {
            Directory.CreateDirectory(quarantineDir);
        }

        byte[] computedHash;
        long totalWritten = 0;

        try
        {
            using (var fileStream = new FileStream(quarantinePath, FileMode.Create, FileAccess.Write, FileShare.None, 65536, useAsync: true))
            using (var sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                byte[] buffer = new byte[65536]; // Bounded 64 KiB buffer
                int bytesRead;

                while ((bytesRead = await inputStream.ReadAsync(buffer, 0, buffer.Length, cancellationToken)) > 0)
                {
                    await fileStream.WriteAsync(buffer.AsMemory(0, bytesRead), cancellationToken);
                    sha256.AppendData(buffer, 0, bytesRead);
                    totalWritten += bytesRead;

                    if (totalWritten > expectedSizeBytes)
                    {
                        throw new ProtocolViolationException(
                            WireErrorCodes.LimitExceeded,
                            $"Stream exceeded declared item size {expectedSizeBytes} bytes (read {totalWritten} bytes)");
                    }
                }

                if (totalWritten != expectedSizeBytes)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.TransferInterrupted,
                        $"Stream finished early: expected {expectedSizeBytes} bytes, but received {totalWritten} bytes");
                }

                computedHash = sha256.GetHashAndReset();
            }

            string actualHashHex = Convert.ToHexStringLower(computedHash);
            var actualDigest = new Sha256Digest(actualHashHex);

            if (!string.Equals(actualHashHex, expectedDigest.Value, StringComparison.OrdinalIgnoreCase))
            {
                PurgeQuarantine(sessionId, itemId);
                throw new ProtocolViolationException(
                    WireErrorCodes.IntegrityMismatch,
                    $"SHA-256 integrity mismatch! Expected {expectedDigest.Value}, got {actualHashHex}");
            }

            // Target destination
            string targetPath = Path.Combine(_destinationRoot, sanitizedRelativePath.Replace('/', Path.DirectorySeparatorChar));
            string finalPath = ResolveCollision(targetPath);

            string? targetDir = Path.GetDirectoryName(finalPath);
            if (targetDir != null && !Directory.Exists(targetDir))
            {
                Directory.CreateDirectory(targetDir);
            }

            // Atomic move from quarantine to committed target
            File.Move(quarantinePath, finalPath, overwrite: false);

            return (finalPath, actualDigest);
        }
        catch
        {
            PurgeQuarantine(sessionId, itemId);
            throw;
        }
    }

    public void PurgeQuarantine(SessionId sessionId, ItemId itemId)
    {
        try
        {
            string quarantinePath = GetQuarantinePath(sessionId, itemId);
            if (File.Exists(quarantinePath))
            {
                File.Delete(quarantinePath);
            }
        }
        catch
        {
            // Ignore purge cleanup errors
        }
    }

    public void PurgeSession(SessionId sessionId)
    {
        try
        {
            string sessionDir = Path.Combine(_stagingRoot, sessionId.Value);
            if (Directory.Exists(sessionDir))
            {
                Directory.Delete(sessionDir, recursive: true);
            }
        }
        catch
        {
            // Ignore purge cleanup errors
        }
    }

    public static string ResolveCollision(string targetPath)
    {
        if (!File.Exists(targetPath))
        {
            return targetPath;
        }

        string directory = Path.GetDirectoryName(targetPath) ?? string.Empty;
        string fileNameWithoutExt = Path.GetFileNameWithoutExtension(targetPath);
        string extension = Path.GetExtension(targetPath);

        int counter = 1;
        while (true)
        {
            string candidate = Path.Combine(directory, $"{fileNameWithoutExt} ({counter}){extension}");
            if (!File.Exists(candidate))
            {
                return candidate;
            }
            counter++;
        }
    }
}
