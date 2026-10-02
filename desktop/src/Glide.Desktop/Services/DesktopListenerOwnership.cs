namespace Glide.Desktop.Services;

/// <summary>
/// Coordinates the Linux GUI and headless CLI so only one per-user process
/// can advertise and serve Glide invitations at a time. The lock file remains
/// in place across restarts to avoid unlink/recreate ownership races.
/// </summary>
public sealed class DesktopListenerOwnership : IDisposable
{
    private static int _inProcessAcquired;
    private readonly FileStream _stream;

    private DesktopListenerOwnership(FileStream stream) => _stream = stream;

    public static DesktopListenerOwnership AcquireLinux()
    {
        if (!OperatingSystem.IsLinux())
            throw new PlatformNotSupportedException("Linux listener ownership requires Linux.");

        if (Interlocked.CompareExchange(ref _inProcessAcquired, 1, 0) != 0)
            throw new IOException("Another Glide process already owns the receiving listener.");

        try
        {
            string? runtimeRoot = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
            bool suppliedBySystem = !string.IsNullOrWhiteSpace(runtimeRoot);
            if (suppliedBySystem)
            {
                try
                {
                    Directory.CreateDirectory(runtimeRoot!);
                    if (new DirectoryInfo(runtimeRoot!).LinkTarget != null)
                        throw new IOException("The runtime directory must not be a symbolic link.");
                    if ((File.GetUnixFileMode(runtimeRoot!) &
                         (UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.GroupExecute |
                          UnixFileMode.OtherRead | UnixFileMode.OtherWrite | UnixFileMode.OtherExecute)) != 0)
                        throw new IOException("The runtime directory is accessible to other users.");

                    string testGlideDir = Path.Combine(runtimeRoot!, "glide");
                    Directory.CreateDirectory(testGlideDir);
                }
                catch (Exception ex) when (ex is UnauthorizedAccessException or IOException)
                {
                    suppliedBySystem = false;
                    runtimeRoot = null;
                }
            }

            if (!suppliedBySystem)
            {
                runtimeRoot = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                    ".local", "share", "glide", "runtime");
                Directory.CreateDirectory(runtimeRoot);
                if (new DirectoryInfo(runtimeRoot).LinkTarget != null)
                    throw new IOException("The runtime directory must not be a symbolic link.");
                File.SetUnixFileMode(runtimeRoot,
                    UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            }

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
        catch
        {
            Interlocked.Exchange(ref _inProcessAcquired, 0);
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
            Interlocked.Exchange(ref _inProcessAcquired, 0);
            _stream.Dispose();
        }
    }
}
