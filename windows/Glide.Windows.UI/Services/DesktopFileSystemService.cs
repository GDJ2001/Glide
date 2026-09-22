using Glide.Core.Pairing;
using Glide.Desktop;
using Glide.Transport.Sockets;

namespace Glide.Windows.UI.Services;

/// <summary>
/// File system service for Glide Windows Desktop (HARD-032).
///
/// Handles:
/// - Desktop state persistence (settings, history)
/// - Receive directory management
/// - Settings validation
/// </summary>
public sealed class DesktopFileSystemService
{
    private readonly DesktopStateStore _stateStore = new();
    private DesktopSettings _settings;

    public DesktopStateStore StateStore => _stateStore;

    public DesktopSettings Settings => _settings;

    public DesktopFileSystemService()
    {
        DesktopPersistentState persisted = _stateStore.Load();
        _settings = persisted.Settings;
    }

    /// <summary>
    /// Updates the receive directory and persists settings.
    /// </summary>
    public void SetReceiveDirectory(string path)
    {
        _settings = _settings with { ReceiveDirectory = path };
        PersistSettings();
    }

    /// <summary>
    /// Updates the listener port and persists settings.
    /// </summary>
    public void SetListenerPort(int port)
    {
        if (port is < 1 or > 65535) return;
        _settings = _settings with { ListenerPort = port };
        PersistSettings();
    }

    private void PersistSettings()
    {
        try
        {
            _stateStore.SaveSettings(_settings);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Failed to persist desktop settings: {ex.Message}");
        }
    }
}
