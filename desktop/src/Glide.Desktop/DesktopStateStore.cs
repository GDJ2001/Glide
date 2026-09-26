using System.Text.Json;

namespace Glide.Desktop;

public sealed record DesktopSettings(
    string ReceiveDirectory,
    int ListenerPort)
{
    public static DesktopSettings CreateDefault() =>
        new(
            Path.Combine(
                Environment.GetFolderPath(
                    Environment.SpecialFolder.UserProfile),
                "Downloads",
                "Glide"),
            42424);
}

public sealed record DesktopPersistentState(
    DesktopSettings Settings,
    IReadOnlyList<DesktopHistoryEntry> History,
    int SchemaVersion = DesktopPersistentState.CurrentSchemaVersion)
{
    public const int CurrentSchemaVersion = 1;
}

/// <summary>
/// Small desktop-owned persistence boundary for non-secret settings and verified transfer history.
///
/// Pairing invitations, tokens, certificate pins, and live session state are deliberately never
/// persisted here. Writes are atomic within the state directory and history is bounded.
/// </summary>
public sealed class DesktopStateStore
{
    private const int MaxHistoryEntries = 500;

    private readonly object _gate = new();
    private readonly string _statePath;
    private DesktopPersistentState? _cached;

    public string? LastRecoveryPath { get; private set; }

    public DesktopStateStore(string? statePath = null)
    {
        _statePath =
            statePath ?? GetDefaultStatePath();
    }

    public DesktopPersistentState Load()
    {
        lock (_gate)
        {
            if (_cached != null)
            {
                return _cached;
            }

            try
            {
                if (!File.Exists(_statePath))
                {
                    return _cached = CreateDefaultState();
                }

                string json =
                    File.ReadAllText(_statePath);
                using JsonDocument document = JsonDocument.Parse(json);
                bool hasSchemaVersion = document.RootElement.TryGetProperty(
                    "SchemaVersion",
                    out _);
                DesktopPersistentState? state =
                    JsonSerializer.Deserialize<
                        DesktopPersistentState>(json);
                if (state == null ||
                    string.IsNullOrWhiteSpace(
                        state.Settings?.ReceiveDirectory) ||
                    state.Settings.ListenerPort
                        is < 1 or > 65535 ||
                    state.History is null ||
                    state.SchemaVersion > DesktopPersistentState.CurrentSchemaVersion)
                {
                    throw new InvalidDataException(
                        "Desktop state is invalid.");
                }

                var boundedHistory =
                    state.History
                        .OrderByDescending(
                            static entry =>
                                entry.Timestamp)
                        .Take(MaxHistoryEntries)
                        .ToList();

                DesktopPersistentState normalized = state with
                {
                    SchemaVersion = DesktopPersistentState.CurrentSchemaVersion,
                    History = boundedHistory
                };

                // Legacy documents omit SchemaVersion and deserialize through the optional
                // constructor default. Persist the normalized form so later migrations have a
                // stable starting point.
                if (!hasSchemaVersion || state.SchemaVersion != normalized.SchemaVersion)
                {
                    PersistLocked(normalized);
                }

                return _cached = normalized;
            }
            catch (JsonException)
            {
                return RecoverInvalidStateLocked();
            }
            catch (InvalidDataException)
            {
                return RecoverInvalidStateLocked();
            }
            catch (IOException)
            {
                // Do not overwrite an unavailable or locked state file. The caller gets safe
                // defaults and can still operate; a later load may recover the existing state.
                return _cached = CreateDefaultState();
            }
            catch (UnauthorizedAccessException)
            {
                return _cached = CreateDefaultState();
            }
        }
    }

    public void SaveSettings(
        DesktopSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        if (string.IsNullOrWhiteSpace(
                settings.ReceiveDirectory))
        {
            throw new ArgumentException(
                "Receive directory cannot be blank.",
                nameof(settings));
        }

        if (settings.ListenerPort
            is < 1 or > 65535)
        {
            throw new ArgumentOutOfRangeException(
                nameof(settings),
                "Listener port must be in 1..65535.");
        }

        lock (_gate)
        {
            DesktopPersistentState current =
                _cached ?? Load();
            _cached = current with
            {
                Settings = settings
            };
            PersistLocked(_cached);
        }
    }

    public void AppendVerifiedHistory(
        DesktopHistoryEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);

        lock (_gate)
        {
            DesktopPersistentState current =
                _cached ?? Load();
            var history =
                new List<DesktopHistoryEntry>(
                    current.History.Count + 1)
                {
                    entry
                };
            history.AddRange(
                current.History);

            _cached = current with
            {
                History = history
                    .Take(MaxHistoryEntries)
                    .ToList()
            };
            PersistLocked(_cached);
        }
    }

    private void PersistLocked(
        DesktopPersistentState state)
    {
        string? directory =
            Path.GetDirectoryName(_statePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(
                directory);
        }

        string tempPath =
            _statePath + ".tmp";
        string json =
            JsonSerializer.Serialize(
                state,
                new JsonSerializerOptions
                {
                    WriteIndented = true
                });

        File.WriteAllText(
            tempPath,
            json);
        File.Move(
            tempPath,
            _statePath,
            overwrite: true);
    }

    private DesktopPersistentState RecoverInvalidStateLocked()
    {
        LastRecoveryPath = null;
        try
        {
            if (File.Exists(_statePath))
            {
                string recoveryPath = $"{_statePath}.corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}";
                File.Move(_statePath, recoveryPath);
                LastRecoveryPath = recoveryPath;
            }
        }
        catch (IOException)
        {
            // Keep the original file for manual recovery if it cannot be moved.
        }
        catch (UnauthorizedAccessException)
        {
            // Keep the original file for manual recovery if permissions do not allow archival.
        }

        return _cached = CreateDefaultState();
    }

    private static DesktopPersistentState CreateDefaultState() =>
        new(DesktopSettings.CreateDefault(), []);

    private static string GetDefaultStatePath()
    {
        string root;
        if (OperatingSystem.IsLinux())
        {
            root =
                Environment.GetEnvironmentVariable(
                    "XDG_STATE_HOME")
                ?? Path.Combine(
                    Environment.GetFolderPath(
                        Environment.SpecialFolder.UserProfile),
                    ".local",
                    "state");
        }
        else
        {
            root =
                Environment.GetFolderPath(
                    Environment.SpecialFolder.LocalApplicationData);
        }

        return Path.Combine(
            root,
            "Glide",
            "desktop-state.json");
    }
}
