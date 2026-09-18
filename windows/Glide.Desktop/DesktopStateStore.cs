using System.Text.Json;

namespace Glide.Desktop;

public sealed record DesktopSettings(
    string DeviceName,
    string ReceiveDirectory,
    int HistoryLimit = 200)
{
    public const int MaxDeviceNameLength = 64;
    public const int MinHistoryLimit = 1;
    public const int MaxHistoryLimit = 1000;

    public static DesktopSettings CreateDefault()
    {
        string deviceName = Environment.MachineName;
        string receiveDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads");

        return new DesktopSettings(
            string.IsNullOrWhiteSpace(deviceName) ? "Glide Desktop" : deviceName,
            receiveDirectory,
            200);
    }

    public DesktopSettings Validate()
    {
        string normalizedName = (DeviceName ?? string.Empty).Trim();
        if (normalizedName.Length is < 1 or > MaxDeviceNameLength ||
            normalizedName.Any(char.IsControl))
        {
            throw new ArgumentException(
                $"Device name must be 1-{MaxDeviceNameLength} visible characters.",
                nameof(DeviceName));
        }

        if (string.IsNullOrWhiteSpace(ReceiveDirectory))
        {
            throw new ArgumentException(
                "Receive directory cannot be empty.",
                nameof(ReceiveDirectory));
        }

        string expandedDirectory =
            Environment.ExpandEnvironmentVariables(
                ReceiveDirectory.Trim());

        if (!Path.IsPathFullyQualified(expandedDirectory))
        {
            throw new ArgumentException(
                "Receive directory must be an absolute path.",
                nameof(ReceiveDirectory));
        }

        string normalizedDirectory =
            Path.GetFullPath(expandedDirectory);

        if (HistoryLimit is < MinHistoryLimit or > MaxHistoryLimit)
        {
            throw new ArgumentOutOfRangeException(
                nameof(HistoryLimit),
                $"History limit must be between {MinHistoryLimit} and {MaxHistoryLimit}.");
        }

        return this with
        {
            DeviceName = normalizedName,
            ReceiveDirectory = normalizedDirectory
        };
    }
}

public sealed record DesktopPersistentState(
    DesktopSettings Settings,
    IReadOnlyList<DesktopHistoryEntry> History);

public interface IDesktopStateStore
{
    Task<DesktopPersistentState> LoadAsync(
        CancellationToken cancellationToken = default);

    Task SaveSettingsAsync(
        DesktopSettings settings,
        CancellationToken cancellationToken = default);

    Task AppendHistoryAsync(
        DesktopHistoryEntry entry,
        CancellationToken cancellationToken = default);

    Task ClearHistoryAsync(
        CancellationToken cancellationToken = default);
}

/// <summary>
/// Small atomic JSON state store for desktop user preferences and verified transfer history.
///
/// This is deliberately local/account-free state. Writes are serialized, written to a temporary
/// file in the same directory, flushed, then atomically replaced. The in-memory state is only
/// updated after durable publication succeeds.
/// </summary>
public sealed class JsonDesktopStateStore : IDesktopStateStore, IDisposable
{
    private const int SchemaVersion = 1;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private readonly string _statePath;
    private readonly SemaphoreSlim _gate = new(1, 1);

    private StateEnvelope? _cached;
    private bool _disposed;

    public JsonDesktopStateStore(string? statePath = null)
    {
        _statePath = Path.GetFullPath(
            statePath ?? GetDefaultStatePath());
    }

    public string StatePath => _statePath;

    public async Task<DesktopPersistentState> LoadAsync(
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken);
        try
        {
            ThrowIfDisposed();
            StateEnvelope state = await EnsureLoadedLockedAsync(
                cancellationToken);

            return new DesktopPersistentState(
                state.Settings,
                state.History
                    .OrderByDescending(static entry => entry.Timestamp)
                    .ToArray());
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SaveSettingsAsync(
        DesktopSettings settings,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(settings);
        DesktopSettings validated = settings.Validate();

        await _gate.WaitAsync(cancellationToken);
        try
        {
            ThrowIfDisposed();
            StateEnvelope current = await EnsureLoadedLockedAsync(
                cancellationToken);

            List<DesktopHistoryEntry> retained = current.History
                .OrderByDescending(static entry => entry.Timestamp)
                .Take(validated.HistoryLimit)
                .ToList();

            var next = new StateEnvelope(
                SchemaVersion,
                validated,
                retained);

            await WriteLockedAsync(next, cancellationToken);
            _cached = next;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task AppendHistoryAsync(
        DesktopHistoryEntry entry,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(entry);

        await _gate.WaitAsync(cancellationToken);
        try
        {
            ThrowIfDisposed();
            StateEnvelope current = await EnsureLoadedLockedAsync(
                cancellationToken);

            var history = new List<DesktopHistoryEntry>(
                Math.Min(
                    current.Settings.HistoryLimit,
                    current.History.Count + 1))
            {
                entry
            };

            history.AddRange(
                current.History
                    .OrderByDescending(static existing => existing.Timestamp)
                    .Take(Math.Max(0, current.Settings.HistoryLimit - 1)));

            var next = current with
            {
                History = history
            };

            await WriteLockedAsync(next, cancellationToken);
            _cached = next;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task ClearHistoryAsync(
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken);
        try
        {
            ThrowIfDisposed();
            StateEnvelope current = await EnsureLoadedLockedAsync(
                cancellationToken);

            var next = current with
            {
                History = []
            };

            await WriteLockedAsync(next, cancellationToken);
            _cached = next;
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<StateEnvelope> EnsureLoadedLockedAsync(
        CancellationToken cancellationToken)
    {
        if (_cached != null)
        {
            return _cached;
        }

        if (!File.Exists(_statePath))
        {
            _cached = CreateDefaultEnvelope();
            return _cached;
        }

        try
        {
            await using var stream = new FileStream(
                _statePath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan);

            StateEnvelope? decoded =
                await JsonSerializer.DeserializeAsync<StateEnvelope>(
                    stream,
                    JsonOptions,
                    cancellationToken);

            if (decoded == null ||
                decoded.Version != SchemaVersion ||
                decoded.Settings == null ||
                decoded.History == null)
            {
                throw new InvalidDataException(
                    "Desktop state file has an unsupported or incomplete schema.");
            }

            DesktopSettings validated = decoded.Settings.Validate();
            _cached = new StateEnvelope(
                SchemaVersion,
                validated,
                decoded.History
                    .OrderByDescending(static entry => entry.Timestamp)
                    .Take(validated.HistoryLimit)
                    .ToList());

            return _cached;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
            when (ex is JsonException or
                     IOException or
                     UnauthorizedAccessException or
                     ArgumentException)
        {
            // Corrupt/unreadable local UI state must not prevent the transfer app from starting.
            // Preserve the bad file for diagnosis and continue with safe defaults.
            TryPreserveCorruptState();
            _cached = CreateDefaultEnvelope();
            return _cached;
        }
    }

    private async Task WriteLockedAsync(
        StateEnvelope state,
        CancellationToken cancellationToken)
    {
        string? directory = Path.GetDirectoryName(_statePath);
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new InvalidOperationException(
                "Desktop state path has no parent directory.");
        }

        Directory.CreateDirectory(directory);

        string tempPath = _statePath + ".tmp-" +
            Guid.NewGuid().ToString("N");

        try
        {
            await using (var stream = new FileStream(
                tempPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                16 * 1024,
                FileOptions.Asynchronous |
                FileOptions.SequentialScan))
            {
                await JsonSerializer.SerializeAsync(
                    stream,
                    state,
                    JsonOptions,
                    cancellationToken);
                await stream.FlushAsync(cancellationToken);
                stream.Flush(flushToDisk: true);
            }

            File.Move(
                tempPath,
                _statePath,
                overwrite: true);
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
                // Best-effort temp cleanup after the durable operation has resolved.
            }
        }
    }

    private void TryPreserveCorruptState()
    {
        try
        {
            if (!File.Exists(_statePath))
            {
                return;
            }

            string corruptPath = _statePath +
                ".corrupt-" +
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            File.Move(
                _statePath,
                corruptPath,
                overwrite: false);
        }
        catch
        {
            // State recovery should never prevent app startup.
        }
    }

    private static StateEnvelope CreateDefaultEnvelope() =>
        new(
            SchemaVersion,
            DesktopSettings.CreateDefault().Validate(),
            []);

    private static string GetDefaultStatePath()
    {
        if (OperatingSystem.IsLinux())
        {
            string? xdgConfig =
                Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
            string configRoot =
                !string.IsNullOrWhiteSpace(xdgConfig)
                    ? xdgConfig
                    : Path.Combine(
                        Environment.GetFolderPath(
                            Environment.SpecialFolder.UserProfile),
                        ".config");

            return Path.Combine(
                configRoot,
                "glide",
                "desktop-state.json");
        }

        return Path.Combine(
            Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData),
            "Glide",
            "desktop-state.json");
    }

    private void ThrowIfDisposed()
    {
        ObjectDisposedException.ThrowIf(
            _disposed,
            this);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _gate.Dispose();
    }

    private sealed record StateEnvelope(
        int Version,
        DesktopSettings Settings,
        List<DesktopHistoryEntry> History);
}
