using System.Text;

namespace Glide.App;

/// <summary>
/// Keeps short-lived daemon pairing secrets out of stdout and service journals.
/// The containing directory and file are readable only by the current user.
/// </summary>
internal sealed class DaemonInvitationFile : IDisposable
{
    private readonly string _directory;
    public string Path { get; }

    public DaemonInvitationFile()
    {
        string? configuredRuntimeRoot = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
        bool usesSystemRuntimeDirectory = !string.IsNullOrWhiteSpace(configuredRuntimeRoot);
        string runtimeRoot = usesSystemRuntimeDirectory
            ? configuredRuntimeRoot!
            : OperatingSystem.IsWindows()
                ? Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)
                : System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                    ".local", "share", "glide", "runtime");

        Directory.CreateDirectory(runtimeRoot);
        if (!OperatingSystem.IsWindows())
        {
            if (new DirectoryInfo(runtimeRoot).LinkTarget != null)
                throw new IOException("The runtime directory must not be a symbolic link.");
            if (!usesSystemRuntimeDirectory)
                File.SetUnixFileMode(runtimeRoot,
                    UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            else if ((File.GetUnixFileMode(runtimeRoot) &
                      (UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.GroupExecute |
                       UnixFileMode.OtherRead | UnixFileMode.OtherWrite | UnixFileMode.OtherExecute)) != 0)
                throw new IOException("The system runtime directory is accessible to other users.");
        }

        _directory = System.IO.Path.Combine(runtimeRoot, "glide");
        Directory.CreateDirectory(_directory);
        if (!OperatingSystem.IsWindows())
        {
            if (new DirectoryInfo(_directory).LinkTarget != null)
                throw new IOException("The Glide runtime directory must not be a symbolic link.");
            File.SetUnixFileMode(_directory,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
        Path = System.IO.Path.Combine(_directory, "daemon-invitation.txt");
    }

    public void Replace(string invitationUri)
    {
        string temporary = System.IO.Path.Combine(
            _directory, $".daemon-invitation-{Guid.NewGuid():N}.tmp");
        try
        {
            using (var stream = new FileStream(
                temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                if (!OperatingSystem.IsWindows())
                    File.SetUnixFileMode(temporary,
                        UnixFileMode.UserRead | UnixFileMode.UserWrite);
                byte[] bytes = Encoding.UTF8.GetBytes(invitationUri + "\n");
                stream.Write(bytes);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporary, Path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public void Clear()
    {
        if (File.Exists(Path)) File.Delete(Path);
    }

    public void Dispose() => Clear();
}
