using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Runtime.Versioning;

namespace Glide.Core.Security;

public sealed record DesktopTrustedPeer(
    string FingerprintHex,
    string PublicKeyDerHex,
    string DisplayName,
    long EnrolledAtUnixMs,
    long LastSeenAtUnixMs)
{
    private static readonly Regex LowerHex64 =
        new(
            "^[0-9a-f]{64}$",
            RegexOptions.Compiled |
            RegexOptions.CultureInvariant);

    private static readonly Regex LowerHex =
        new(
            "^[0-9a-f]+$",
            RegexOptions.Compiled |
            RegexOptions.CultureInvariant);

    public void Validate()
    {
        if (!LowerHex64.IsMatch(FingerprintHex))
        {
            throw new InvalidDataException(
                "Trusted peer fingerprint must be exactly 64 lowercase hexadecimal characters.");
        }

        if (string.IsNullOrEmpty(PublicKeyDerHex) ||
            PublicKeyDerHex.Length > 4096 ||
            PublicKeyDerHex.Length % 2 != 0 ||
            !LowerHex.IsMatch(PublicKeyDerHex))
        {
            throw new InvalidDataException(
                "Trusted peer public key must be canonical lowercase DER hexadecimal.");
        }

        int codePoints =
            DisplayName.EnumerateRunes().Count();
        if (string.IsNullOrWhiteSpace(DisplayName) ||
            codePoints is < 1 or > 64 ||
            System.Text.Encoding.UTF8.GetByteCount(DisplayName) > 256)
        {
            throw new InvalidDataException(
                "Trusted peer display name must be 1..64 code points and <= 256 UTF-8 bytes.");
        }

        if (EnrolledAtUnixMs <= 0 ||
            LastSeenAtUnixMs < EnrolledAtUnixMs)
        {
            throw new InvalidDataException(
                "Trusted peer timestamps are invalid.");
        }
    }
}

public sealed record HeadlessReceivePolicy(
    string PeerFingerprintHex,
    string DestinationRoot,
    long MaxOfferBytes,
    bool Enabled)
{
    private static readonly Regex LowerHex64 =
        new(
            "^[0-9a-f]{64}$",
            RegexOptions.Compiled |
            RegexOptions.CultureInvariant);

    public void Validate()
    {
        if (!LowerHex64.IsMatch(PeerFingerprintHex))
        {
            throw new InvalidDataException(
                "Headless policy peer fingerprint must be exactly 64 lowercase hexadecimal characters.");
        }

        if (string.IsNullOrWhiteSpace(DestinationRoot) ||
            !Path.IsPathFullyQualified(DestinationRoot))
        {
            throw new InvalidDataException(
                "Headless policy destination must be an absolute local path.");
        }

        if (MaxOfferBytes <= 0 ||
            MaxOfferBytes >
                Glide.Core.Codec.ProtocolCatalog.MaxBatchBytes)
        {
            throw new InvalidDataException(
                $"Headless policy max offer bytes must be in 1..{Glide.Core.Codec.ProtocolCatalog.MaxBatchBytes}.");
        }
    }
}

public sealed class DesktopTrustDocument
{
    [JsonPropertyName("version")]
    public int Version { get; set; } = 1;

    [JsonPropertyName("localIdentityFingerprintHex")]
    public string? LocalIdentityFingerprintHex { get; set; }

    [JsonPropertyName("peers")]
    public List<DesktopTrustedPeer> Peers { get; set; } = [];

    [JsonPropertyName("headlessPolicies")]
    public List<HeadlessReceivePolicy> HeadlessPolicies { get; set; } = [];
}

public sealed class DesktopTrustStore
{
    public const int CurrentVersion = 1;

    private static readonly Regex LowerHex64 =
        new(
            "^[0-9a-f]{64}$",
            RegexOptions.Compiled |
            RegexOptions.CultureInvariant);

    private static readonly JsonSerializerOptions JsonOptions =
        new()
        {
            PropertyNamingPolicy = null,
            WriteIndented = true,
            UnmappedMemberHandling =
                JsonUnmappedMemberHandling.Disallow
        };

    private readonly object _gate = new();
    private readonly string _path;

    public DesktopTrustStore(
        string? path = null)
    {
        _path =
            path ?? Path.Combine(
                DesktopTrustPaths.GetConfigDirectory(),
                "trust-v1.json");
    }

    public string PathOnDisk => _path;

    public DesktopTrustDocument Load()
    {
        lock (_gate)
        {
            if (!File.Exists(_path))
            {
                return new DesktopTrustDocument();
            }

            RejectSymlink(_path);
            RequireOwnerOnlyFileWhenUnix(_path);

            string json =
                File.ReadAllText(_path);

            using (JsonDocument parsed =
                JsonDocument.Parse(json))
            {
                if (parsed.RootElement.ValueKind !=
                        JsonValueKind.Object ||
                    !parsed.RootElement.TryGetProperty(
                        "version",
                        out _) ||
                    !parsed.RootElement.TryGetProperty(
                        "localIdentityFingerprintHex",
                        out _) ||
                    !parsed.RootElement.TryGetProperty(
                        "peers",
                        out _) ||
                    !parsed.RootElement.TryGetProperty(
                        "headlessPolicies",
                        out _))
                {
                    throw new InvalidDataException(
                        "Desktop trust document is missing required versioned fields.");
                }
            }

            DesktopTrustDocument document =
                JsonSerializer.Deserialize<
                    DesktopTrustDocument>(
                        json,
                        JsonOptions)
                ?? throw new InvalidDataException(
                    "Desktop trust document is empty.");

            ValidateDocument(document);
            return Clone(document);
        }
    }

    public void Save(
        DesktopTrustDocument document)
    {
        ArgumentNullException.ThrowIfNull(document);
        ValidateDocument(document);

        lock (_gate)
        {
            string? directory =
                Path.GetDirectoryName(_path);
            if (string.IsNullOrWhiteSpace(directory))
            {
                throw new InvalidOperationException(
                    "Trust database path must have a parent directory.");
            }

            Directory.CreateDirectory(directory);
            EnsureOwnerOnlyDirectoryWhenUnix(
                directory);

            if (File.Exists(_path))
            {
                RejectSymlink(_path);
            }

            string tempPath =
                _path + "." +
                Guid.NewGuid().ToString("N") +
                ".tmp";

            try
            {
                byte[] bytes =
                    JsonSerializer.SerializeToUtf8Bytes(
                        document,
                        JsonOptions);

                using (var stream =
                    new FileStream(
                        tempPath,
                        FileMode.CreateNew,
                        FileAccess.Write,
                        FileShare.None,
                        bufferSize: 4096,
                        FileOptions.WriteThrough))
                {
                    stream.Write(bytes);
                    stream.Flush(
                        flushToDisk: true);
                }

                SetOwnerOnlyFileWhenUnix(
                    tempPath);

                File.Move(
                    tempPath,
                    _path,
                    overwrite: true);

                SetOwnerOnlyFileWhenUnix(
                    _path);
            }
            finally
            {
                if (File.Exists(tempPath))
                {
                    File.Delete(tempPath);
                }
            }
        }
    }

    private static void ValidateDocument(
        DesktopTrustDocument document)
    {
        if (document.Version != CurrentVersion)
        {
            throw new InvalidDataException(
                $"Unsupported desktop trust document version {document.Version}.");
        }

        if (document.LocalIdentityFingerprintHex != null &&
            !LowerHex64.IsMatch(
                document.LocalIdentityFingerprintHex))
        {
            throw new InvalidDataException(
                "Stored local identity fingerprint is not canonical.");
        }

        foreach (DesktopTrustedPeer peer in
            document.Peers)
        {
            peer.Validate();
        }

        foreach (HeadlessReceivePolicy policy in
            document.HeadlessPolicies)
        {
            policy.Validate();
        }

        if (document.Peers
            .Select(static p => p.FingerprintHex)
            .Distinct(StringComparer.Ordinal)
            .Count() != document.Peers.Count)
        {
            throw new InvalidDataException(
                "Desktop trust document contains duplicate peer fingerprints.");
        }

        if (document.HeadlessPolicies
            .Select(static p => p.PeerFingerprintHex)
            .Distinct(StringComparer.Ordinal)
            .Count() != document.HeadlessPolicies.Count)
        {
            throw new InvalidDataException(
                "Desktop trust document contains duplicate headless policies.");
        }

        var peerIds =
            document.Peers
                .Select(static p => p.FingerprintHex)
                .ToHashSet(StringComparer.Ordinal);
        if (document.HeadlessPolicies.Any(
                policy =>
                    !peerIds.Contains(
                        policy.PeerFingerprintHex)))
        {
            throw new InvalidDataException(
                "Headless policy references a peer that is not enrolled.");
        }
    }

    private static DesktopTrustDocument Clone(
        DesktopTrustDocument source) =>
        new()
        {
            Version = source.Version,
            LocalIdentityFingerprintHex =
                source.LocalIdentityFingerprintHex,
            Peers = [.. source.Peers],
            HeadlessPolicies =
                [.. source.HeadlessPolicies]
        };

    private static void RejectSymlink(
        string path)
    {
        var info = new FileInfo(path);
        if (info.LinkTarget != null)
        {
            throw new UnauthorizedAccessException(
                "Trust database path must not be a symbolic link.");
        }
    }

    private static void EnsureOwnerOnlyDirectoryWhenUnix(
        string directory)
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        File.SetUnixFileMode(
            directory,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite |
            UnixFileMode.UserExecute);
        RequireNoGroupOtherPermissions(
            directory,
            "Trust directory");
    }

    private static void SetOwnerOnlyFileWhenUnix(
        string path)
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        File.SetUnixFileMode(
            path,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite);
    }

    private static void RequireOwnerOnlyFileWhenUnix(
        string path)
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        RequireNoGroupOtherPermissions(
            path,
            "Trust database");
    }

    [SupportedOSPlatform("linux")]
    private static void RequireNoGroupOtherPermissions(
        string path,
        string label)
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
                $"{label} permissions must be owner-only.");
        }
    }
}
