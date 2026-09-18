using System.Net;
using System.Text.Json;
using Glide.Core.Codec;

namespace Glide.Desktop;

/// <summary>
/// Explicit unattended-receive policy for the Linux/headless daemon.
///
/// Persistent cryptographic peer identity is intentionally not invented here; that belongs to
/// HARD-018..021. Until then, unattended authority requires BOTH possession of a freshly generated
/// one-use QR invitation and an exact configured remote IP allowlist.
/// </summary>
public sealed record DaemonReceivePolicy
{
    public string DestinationDirectory { get; init; } = "";
    public string EnrollmentFile { get; init; } = "";
    public IReadOnlyList<string> AllowedRemoteAddresses { get; init; } = [];
    public long MaxItemBytes { get; init; } = 1024L * 1024 * 1024;
    public long MaxBatchBytes { get; init; } = 4L * 1024 * 1024 * 1024;
    public int MaxItems { get; init; } = 100;
    public int InvitationTtlSeconds { get; init; } = 300;
    public string DeviceName { get; init; } = "";

    public static async Task<DaemonReceivePolicy> LoadAsync(
        string policyPath,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(policyPath))
        {
            throw new ArgumentException(
                "Daemon policy path is required.",
                nameof(policyPath));
        }

        string fullPath = Path.GetFullPath(
            Environment.ExpandEnvironmentVariables(
                policyPath.Trim()));

        if (!File.Exists(fullPath))
        {
            throw new FileNotFoundException(
                $"Daemon policy file not found: {fullPath}",
                fullPath);
        }

        await using var stream = new FileStream(
            fullPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            16 * 1024,
            FileOptions.Asynchronous |
            FileOptions.SequentialScan);

        DaemonReceivePolicy? decoded =
            await JsonSerializer.DeserializeAsync<DaemonReceivePolicy>(
                stream,
                new JsonSerializerOptions
                {
                    PropertyNameCaseInsensitive = true
                },
                cancellationToken);

        if (decoded == null)
        {
            throw new InvalidDataException(
                "Daemon policy JSON is empty or invalid.");
        }

        return decoded.Validate();
    }

    public DaemonReceivePolicy Validate()
    {
        string destination =
            NormalizeRequiredAbsolutePath(
                DestinationDirectory,
                nameof(DestinationDirectory));

        string enrollment =
            NormalizeRequiredAbsolutePath(
                EnrollmentFile,
                nameof(EnrollmentFile));

        if (AllowedRemoteAddresses == null ||
            AllowedRemoteAddresses.Count is < 1 or > 32)
        {
            throw new ArgumentException(
                "Daemon policy requires 1-32 explicit remote IP addresses.",
                nameof(AllowedRemoteAddresses));
        }

        var normalizedAddresses =
            new List<string>(
                AllowedRemoteAddresses.Count);

        foreach (string raw in AllowedRemoteAddresses)
        {
            if (!IPAddress.TryParse(
                    raw?.Trim(),
                    out IPAddress? parsed))
            {
                throw new ArgumentException(
                    $"Invalid allowed remote IP address '{raw}'.",
                    nameof(AllowedRemoteAddresses));
            }

            normalizedAddresses.Add(
                NormalizeAddress(parsed).ToString());
        }

        if (MaxItems is < 1 or > 1000)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxItems),
                "maxItems must be between 1 and 1000.");
        }

        if (MaxItemBytes is < 0 or > ProtocolCatalog.MaxItemBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxItemBytes),
                $"maxItemBytes must be between 0 and {ProtocolCatalog.MaxItemBytes}.");
        }

        if (MaxBatchBytes < MaxItemBytes ||
            MaxBatchBytes > ProtocolCatalog.MaxBatchBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxBatchBytes),
                $"maxBatchBytes must be between maxItemBytes and {ProtocolCatalog.MaxBatchBytes}.");
        }

        if (InvitationTtlSeconds is < 60 or > 3600)
        {
            throw new ArgumentOutOfRangeException(
                nameof(InvitationTtlSeconds),
                "invitationTtlSeconds must be between 60 and 3600 seconds.");
        }

        string deviceName =
            string.IsNullOrWhiteSpace(DeviceName)
                ? Environment.MachineName
                : DeviceName.Trim();

        if (deviceName.Length is < 1 or > 64 ||
            deviceName.Any(char.IsControl))
        {
            throw new ArgumentException(
                "deviceName must be 1-64 visible characters.",
                nameof(DeviceName));
        }

        if (string.Equals(
                destination,
                enrollment,
                StringComparison.Ordinal))
        {
            throw new ArgumentException(
                "Enrollment file cannot be the receive destination.");
        }

        return this with
        {
            DestinationDirectory = destination,
            EnrollmentFile = enrollment,
            AllowedRemoteAddresses =
                normalizedAddresses.Distinct(
                    StringComparer.Ordinal).ToArray(),
            DeviceName = deviceName
        };
    }

    public DesktopReceiveLimits ToReceiveLimits() =>
        new DesktopReceiveLimits(
            MaxItems,
            MaxItemBytes,
            MaxBatchBytes,
            ProtocolCatalog.MaxControlBytes).Validate();

    public bool IsRemoteAllowed(IPAddress address)
    {
        ArgumentNullException.ThrowIfNull(address);

        string normalized =
            NormalizeAddress(address).ToString();

        return AllowedRemoteAddresses.Contains(
            normalized,
            StringComparer.Ordinal);
    }

    public bool AuthorizesOffer(DesktopOffer offer)
    {
        ArgumentNullException.ThrowIfNull(offer);

        if (offer.Items.Count < 1 ||
            offer.Items.Count > MaxItems ||
            offer.TotalBytes < 0 ||
            offer.TotalBytes > MaxBatchBytes)
        {
            return false;
        }

        long sum = 0;
        foreach (DesktopOfferItem item in offer.Items)
        {
            if (item.SizeBytes < 0 ||
                item.SizeBytes > MaxItemBytes)
            {
                return false;
            }

            sum = checked(sum + item.SizeBytes);
            if (sum > MaxBatchBytes)
            {
                return false;
            }
        }

        return sum == offer.TotalBytes;
    }

    private static string NormalizeRequiredAbsolutePath(
        string raw,
        string parameterName)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            throw new ArgumentException(
                $"{parameterName} is required.",
                parameterName);
        }

        string expanded =
            Environment.ExpandEnvironmentVariables(
                raw.Trim());

        if (!Path.IsPathFullyQualified(expanded))
        {
            throw new ArgumentException(
                $"{parameterName} must be an absolute path.",
                parameterName);
        }

        return Path.GetFullPath(expanded);
    }

    private static IPAddress NormalizeAddress(
        IPAddress address) =>
        address.IsIPv4MappedToIPv6
            ? address.MapToIPv4()
            : address;
}

/// <summary>
/// Writes the sensitive one-use daemon invitation to a configured local enrollment file without
/// logging its bearer token. On Unix, the file is explicitly restricted to user read/write.
/// </summary>
public sealed class DaemonEnrollmentFile
{
    private readonly string _path;

    public DaemonEnrollmentFile(string path)
    {
        if (string.IsNullOrWhiteSpace(path) ||
            !System.IO.Path.IsPathFullyQualified(path))
        {
            throw new ArgumentException(
                "Enrollment file path must be absolute.",
                nameof(path));
        }

        _path = System.IO.Path.GetFullPath(path);
    }

    public string Path => _path;

    public async Task WriteAsync(
        string invitationUri,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(invitationUri))
        {
            throw new ArgumentException(
                "Invitation URI is required.",
                nameof(invitationUri));
        }

        string? directory =
            System.IO.Path.GetDirectoryName(_path);
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new InvalidOperationException(
                "Enrollment path has no parent directory.");
        }

        Directory.CreateDirectory(directory);

        string tempPath =
            _path + ".tmp-" +
            Guid.NewGuid().ToString("N");

        try
        {
            await File.WriteAllTextAsync(
                tempPath,
                invitationUri + Environment.NewLine,
                cancellationToken);

            RestrictUnixPermissions(tempPath);

            File.Move(
                tempPath,
                _path,
                overwrite: true);

            RestrictUnixPermissions(_path);
        }
        finally
        {
            try
            {
                if (File.Exists(tempPath))
                {
                    File.Delete(tempPath);
                }
            }
            catch
            {
                // Best-effort cleanup.
            }
        }
    }

    public void Delete()
    {
        if (File.Exists(_path))
        {
            File.Delete(_path);
        }
    }

    public bool Exists => File.Exists(_path);

    private static void RestrictUnixPermissions(
        string path)
    {
        if (!OperatingSystem.IsLinux() &&
            !OperatingSystem.IsMacOS())
        {
            return;
        }

        File.SetUnixFileMode(
            path,
            UnixFileMode.UserRead |
            UnixFileMode.UserWrite);
    }
}
