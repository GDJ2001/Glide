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
    IReadOnlyList<DesktopHistoryEntry> History);

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
                    return _cached =
                        new DesktopPersistentState(
                            DesktopSettings.CreateDefault(),
                            []);
                }

                string json =
                    File.ReadAllText(_statePath);
                DesktopPersistentState? state =
                    JsonSerializer.Deserialize<
                        DesktopPersistentState>(json);
                if (state == null ||
                    string.IsNullOrWhiteSpace(
                        state.Settings.ReceiveDirectory) ||
                    state.Settings.ListenerPort
                        is < 1 or > 65535)
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

                return _cached =
                    state with
                    {
                        History = boundedHistory
                    };
            }
            catch
            {
                return _cached =
                    new DesktopPersistentState(
                        DesktopSettings.CreateDefault(),
                        []);
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
