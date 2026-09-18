using System.Runtime.Versioning;
using System.Security.Cryptography;

namespace Glide.Core.Security;

public interface IDesktopPersistentIdentityStore
{
    bool HasIdentity();
    byte[] GetOrCreatePublicKeyDer();
    string GetFingerprintHex();
    byte[] SignTranscript(ReadOnlySpan<byte> transcript);
    void DeleteIdentity();
}

/// <summary>
/// Persistent P-256 signing identity for Windows/Linux.
///
/// Windows uses a named current-user CNG key with export disabled. Linux stores a PKCS#8 key only
/// in an owner-only directory/file (0700/0600) and refuses insecure existing permissions.
/// </summary>
public sealed class DesktopPersistentIdentityStore :
    IDesktopPersistentIdentityStore
{
    public const string DefaultWindowsKeyName = "Glide.Identity.v1";

    private readonly string _windowsKeyName;
    private readonly string _linuxKeyPath;

    public DesktopPersistentIdentityStore(
        string? linuxKeyPath = null,
        string windowsKeyName = DefaultWindowsKeyName)
    {
        _windowsKeyName =
            string.IsNullOrWhiteSpace(windowsKeyName)
                ? throw new ArgumentException(
                    "Windows identity key name cannot be blank.",
                    nameof(windowsKeyName))
                : windowsKeyName;

        _linuxKeyPath =
            linuxKeyPath ?? Path.Combine(
                DesktopTrustPaths.GetConfigDirectory(),
                "identity-v1.pk8");
    }

    public bool HasIdentity()
    {
        if (OperatingSystem.IsWindows())
        {
            return WindowsKeyExists();
        }

        if (OperatingSystem.IsLinux())
        {
            return File.Exists(_linuxKeyPath);
        }

        throw new PlatformNotSupportedException(
            "Persistent desktop identity currently supports Windows and Linux.");
    }

    public byte[] GetOrCreatePublicKeyDer()
    {
        using ECDsa key = OpenOrCreateKey();
        return key.ExportSubjectPublicKeyInfo();
    }

    public string GetFingerprintHex() =>
        Convert.ToHexStringLower(
            SHA256.HashData(
                GetOrCreatePublicKeyDer()));

    public byte[] SignTranscript(
        ReadOnlySpan<byte> transcript)
    {
        if (transcript.IsEmpty)
        {
            throw new ArgumentException(
                "Canonical signature transcript cannot be empty.",
                nameof(transcript));
        }

        using ECDsa key = OpenOrCreateKey();
        return key.SignData(
            transcript,
            HashAlgorithmName.SHA256,
            DSASignatureFormat.Rfc3279DerSequence);
    }

    public void DeleteIdentity()
    {
        if (OperatingSystem.IsWindows())
        {
            DeleteWindowsKey();
            return;
        }

        if (OperatingSystem.IsLinux())
        {
            if (File.Exists(_linuxKeyPath))
            {
                RejectSymlink(_linuxKeyPath);
                File.Delete(_linuxKeyPath);
            }
            return;
        }

        throw new PlatformNotSupportedException(
            "Persistent desktop identity currently supports Windows and Linux.");
    }

    private ECDsa OpenOrCreateKey()
    {
        if (OperatingSystem.IsWindows())
        {
            return OpenOrCreateWindowsKey();
        }

        if (OperatingSystem.IsLinux())
        {
            return OpenOrCreateLinuxKey();
        }

        throw new PlatformNotSupportedException(
            "Persistent desktop identity currently supports Windows and Linux.");
    }

    [SupportedOSPlatform("windows")]
    private bool WindowsKeyExists() =>
        CngKey.Exists(
            _windowsKeyName,
            CngProvider.MicrosoftSoftwareKeyStorageProvider,
            CngKeyOpenOptions.None);

    [SupportedOSPlatform("windows")]
    private ECDsa OpenOrCreateWindowsKey()
    {
        CngKey cngKey;
        if (WindowsKeyExists())
        {
            cngKey = CngKey.Open(
                _windowsKeyName,
                CngProvider.MicrosoftSoftwareKeyStorageProvider,
                CngKeyOpenOptions.None);
        }
        else
        {
            var parameters =
                new CngKeyCreationParameters
                {
                    Provider =
                        CngProvider.MicrosoftSoftwareKeyStorageProvider,
                    KeyUsage =
                        CngKeyUsages.Signing,
                    ExportPolicy =
                        CngExportPolicies.None,
                    KeyCreationOptions =
                        CngKeyCreationOptions.None
                };
            cngKey = CngKey.Create(
                CngAlgorithm.ECDsaP256,
                _windowsKeyName,
                parameters);
        }

        return new ECDsaCng(cngKey)
        {
            HashAlgorithm = CngAlgorithm.Sha256
        };
    }

    [SupportedOSPlatform("windows")]
    private void DeleteWindowsKey()
    {
        if (!WindowsKeyExists())
        {
            return;
        }

        using CngKey key = CngKey.Open(
            _windowsKeyName,
            CngProvider.MicrosoftSoftwareKeyStorageProvider,
            CngKeyOpenOptions.None);
        key.Delete();
    }

    [SupportedOSPlatform("linux")]
    private ECDsa OpenOrCreateLinuxKey()
    {
        string? directory =
            Path.GetDirectoryName(_linuxKeyPath);
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new InvalidOperationException(
                "Linux identity path must have a parent directory.");
        }

        EnsureOwnerOnlyDirectory(directory);

        if (File.Exists(_linuxKeyPath))
        {
            RejectSymlink(_linuxKeyPath);
            RequireOwnerOnlyFile(_linuxKeyPath);

            byte[] privateKey =
                File.ReadAllBytes(_linuxKeyPath);
            try
            {
                ECDsa existing =
                    ECDsa.Create();
                existing.ImportPkcs8PrivateKey(
                    privateKey,
                    out int bytesRead);
                if (bytesRead != privateKey.Length)
                {
                    existing.Dispose();
                    throw new CryptographicException(
                        "Persistent identity key contains trailing data.");
                }

                return existing;
            }
            finally
            {
                CryptographicOperations.ZeroMemory(
                    privateKey);
            }
        }

        using ECDsa generated =
            ECDsa.Create(
                ECCurve.NamedCurves.nistP256);
        byte[] encoded =
            generated.ExportPkcs8PrivateKey();

        string tempPath =
            _linuxKeyPath + "." +
            Guid.NewGuid().ToString("N") +
            ".tmp";
        try
        {
            using (var stream =
                new FileStream(
                    tempPath,
                    FileMode.CreateNew,
                    FileAccess.Write,
                    FileShare.None,
                    bufferSize: 4096,
                    FileOptions.WriteThrough))
            {
                stream.Write(encoded);
                stream.Flush(flushToDisk: true);
            }

            File.SetUnixFileMode(
                tempPath,
                UnixFileMode.UserRead |
                UnixFileMode.UserWrite);

            try
            {
                File.Move(
                    tempPath,
                    _linuxKeyPath,
                    overwrite: false);
            }
            catch (IOException)
                when (File.Exists(_linuxKeyPath))
            {
                File.Delete(tempPath);
            }

            RequireOwnerOnlyFile(
                _linuxKeyPath);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(
                encoded);
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }
        }

        byte[] stored =
            File.ReadAllBytes(_linuxKeyPath);
        try
        {
            ECDsa result = ECDsa.Create();
            result.ImportPkcs8PrivateKey(
                stored,
                out int bytesRead);
            if (bytesRead != stored.Length)
            {
                result.Dispose();
                throw new CryptographicException(
                    "Persistent identity key contains trailing data.");
            }
            return result;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(
                stored);
        }
    }

    [SupportedOSPlatform("linux")]
    private static void EnsureOwnerOnlyDirectory(
        string directory)
    {
        Directory.CreateDirectory(directory);
        File.SetUnixFileMode(
            directory,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite |
            UnixFileMode.UserExecute);

        UnixFileMode mode =
            File.GetUnixFileMode(directory);
        UnixFileMode forbidden =
            UnixFileMode.GroupRead |
            UnixFileMode.GroupWrite |
            UnixFileMode.GroupExecute |
            UnixFileMode.OtherRead |
            UnixFileMode.OtherWrite |
            UnixFileMode.OtherExecute;
        if ((mode & forbidden) != 0)
        {
            throw new UnauthorizedAccessException(
                "Persistent identity directory is accessible by group/other users.");
        }
    }

    [SupportedOSPlatform("linux")]
    private static void RequireOwnerOnlyFile(
        string path)
    {
        UnixFileMode mode =
            File.GetUnixFileMode(path);
        UnixFileMode forbidden =
            UnixFileMode.GroupRead |
            UnixFileMode.GroupWrite |
            UnixFileMode.GroupExecute |
            UnixFileMode.OtherRead |
            UnixFileMode.OtherWrite |
            UnixFileMode.OtherExecute;
        if ((mode & forbidden) != 0)
        {
            throw new UnauthorizedAccessException(
                "Persistent identity key permissions must be owner-only (0600).");
        }
    }

    private static void RejectSymlink(
        string path)
    {
        var info = new FileInfo(path);
        if (info.LinkTarget != null)
        {
            throw new UnauthorizedAccessException(
                "Persistent identity key path must not be a symbolic link.");
        }
    }
}
