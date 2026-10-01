namespace Glide.Desktop.Services;

/// <summary>
/// Coordinates the Linux GUI and headless CLI so only one per-user process
/// can advertise and serve Glide invitations at a time. The lock file remains
/// in place across restarts to avoid unlink/recreate ownership races.
/// </summary>
public sealed class DesktopListenerOwnership : IDisposable
{
    private readonly FileStream _stream;

    private DesktopListenerOwnership(FileStream stream) => _stream = stream;

    public static DesktopListenerOwnership AcquireLinux()
    {
        if (!OperatingSystem.IsLinux())
            throw new PlatformNotSupportedException("Linux listener ownership requires Linux.");

        string? runtimeRoot = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
        bool suppliedBySystem = !string.IsNullOrWhiteSpace(runtimeRoot);
        if (!suppliedBySystem)
            runtimeRoot = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                ".local", "share", "glide", "runtime");

        Directory.CreateDirectory(runtimeRoot!);
        if (new DirectoryInfo(runtimeRoot!).LinkTarget != null)
            throw new IOException("The runtime directory must not be a symbolic link.");
        if (!suppliedBySystem)
            File.SetUnixFileMode(runtimeRoot!,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        else if ((File.GetUnixFileMode(runtimeRoot!) &
                  (UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.GroupExecute |
                   UnixFileMode.OtherRead | UnixFileMode.OtherWrite | UnixFileMode.OtherExecute)) != 0)
            throw new IOException("The runtime directory is accessible to other users.");

        string directory = Path.Combine(runtimeRoot!, "glide");
        Directory.CreateDirectory(directory);
        if (new DirectoryInfo(directory).LinkTarget != null)
            throw new IOException("The Glide runtime directory must not be a symbolic link.");
        File.SetUnixFileMode(directory,
            UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);

        string lockPath = Path.Combine(directory, "listener.lock");
        var stream = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
        try
        {
            File.SetUnixFileMode(lockPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            stream.Lock(0, 1);
            return new DesktopListenerOwnership(stream);
        }
        catch (IOException ex)
        {
            stream.Dispose();
            throw new IOException("Another Glide process already owns the receiving listener.", ex);
        }
        catch
        {
            stream.Dispose();
            throw;
        }
    }

    public void Dispose()
    {
        try
        {
            if (OperatingSystem.IsLinux())
                _stream.Unlock(0, 1);
        }
        finally
        {
            _stream.Dispose();
        }
    }
}
