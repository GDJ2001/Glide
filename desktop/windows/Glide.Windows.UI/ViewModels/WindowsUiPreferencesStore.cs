using System.IO;
using System.Text.Json;

namespace Glide.Windows.UI.ViewModels;

public sealed record WindowsUiPreferences(string AppearanceMode = "System");

/// <summary>Stores presentation-only choices separately from transfer state and history.</summary>
public sealed class WindowsUiPreferencesStore
{
    private readonly string _path = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Glide", "windows-ui.json");

    public WindowsUiPreferences Load()
    {
        try
        {
            WindowsUiPreferences? preferences = File.Exists(_path)
                ? JsonSerializer.Deserialize<WindowsUiPreferences>(File.ReadAllText(_path))
                : null;
            if (preferences is not null &&
                (preferences.AppearanceMode is "System" or "Light" or "Dark"))
            {
                return preferences;
            }
        }
        catch (JsonException) { }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }

        return new WindowsUiPreferences();
    }

    public void Save(WindowsUiPreferences preferences)
    {
        try
        {
            string? directory = Path.GetDirectoryName(_path);
            if (directory is not null)
            {
                Directory.CreateDirectory(directory);
            }

            File.WriteAllText(_path, JsonSerializer.Serialize(preferences));
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
