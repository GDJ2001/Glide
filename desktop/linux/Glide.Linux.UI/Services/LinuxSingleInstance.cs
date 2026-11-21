using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Glide.Linux.UI.Services;

/// <summary>
/// Provides single-instance application coordination via local Unix domain socket.
/// </summary>
public sealed class LinuxSingleInstance : IDisposable
{
    private readonly string _socketPath;
    private Socket? _listener;
    private FileStream? _ownerLock;
    private CancellationTokenSource? _cts;
    private bool _ownsSocket;

    public event Action? Activated;
    public event Action<string[]>? ActivatedWithArguments;

    public LinuxSingleInstance()
    {
        _socketPath = Path.Combine(LinuxEnvironment.RuntimeDir, "glide-desktop.sock");
    }

    /// <summary>
    /// Attempts to register as the primary instance. Returns false if an active instance responded.
    /// </summary>
    public bool TryAcquire(string[]? launchArguments = null)
    {
        if (!OperatingSystem.IsLinux()) return false;
        var lockPath = _socketPath + ".lock";
        try
        {
            _ownerLock = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
            if (OperatingSystem.IsLinux())
                File.SetUnixFileMode(lockPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            _ownerLock.Lock(0, 1);
        }
        catch (IOException)
        {
            _ownerLock?.Dispose();
            _ownerLock = null;
            ActivateExisting(launchArguments);
            return false;
        }

        try
        {
            // Holding the lock proves no live Glide GUI owns this socket path.
            if (File.Exists(_socketPath)) File.Delete(_socketPath);
            _listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            var endpoint = new UnixDomainSocketEndPoint(_socketPath);
            _listener.Bind(endpoint);
            _listener.Listen(5);
            _ownsSocket = true;

            _cts = new CancellationTokenSource();
            _ = Task.Run(() => AcceptLoopAsync(_cts.Token));
            return true;
        }
        catch
        {
            _listener?.Dispose();
            _listener = null;
            _ownerLock?.Dispose();
            _ownerLock = null;
            return false; // Never launch a second process when ownership is uncertain.
        }
    }

    private void ActivateExisting(string[]? launchArguments)
    {
        try
        {
            using var client = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            client.Connect(new UnixDomainSocketEndPoint(_socketPath));
            string encodedArgs = Convert.ToBase64String(
                Encoding.UTF8.GetBytes(JsonSerializer.Serialize(launchArguments ?? [])));
            byte[] message = Encoding.UTF8.GetBytes($"ACTIVATE {encodedArgs}\n");
            if (message.Length > 16 * 1024) return;
            int offset = 0;
            while (offset < message.Length)
                offset += client.Send(message.AsSpan(offset));
        }
        catch (SocketException)
        {
            // Ownership is uncertain; fail closed rather than starting a second UI.
        }
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener != null)
        {
            try
            {
                var client = await _listener.AcceptAsync(ct);
                _ = Task.Run(() => HandleClientAsync(client), ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                // Continue listening
            }
        }
    }

    private async Task HandleClientAsync(Socket client)
    {
        using (client)
        {
            var buffer = new byte[16 * 1024];
            int read = 0;
            while (read < buffer.Length)
            {
                int next = await client.ReceiveAsync(buffer.AsMemory(read), SocketFlags.None);
                if (next == 0) break;
                read += next;
                if (buffer.AsSpan(0, read).Contains((byte)'\n')) break;
            }
            if (read == 0 || read == buffer.Length || buffer[read - 1] != (byte)'\n') return;
            string msg = Encoding.UTF8.GetString(buffer, 0, read - 1);
            if (!msg.StartsWith("ACTIVATE", StringComparison.Ordinal)) return;
            string[] arguments = [];
            if (msg.Length > "ACTIVATE ".Length && msg.StartsWith("ACTIVATE ", StringComparison.Ordinal))
            {
                try
                {
                    byte[] json = Convert.FromBase64String(msg["ACTIVATE ".Length..]);
                    arguments = JsonSerializer.Deserialize<string[]>(json) ?? [];
                }
                catch (Exception ex) when (ex is FormatException or JsonException)
                {
                    return;
                }
            }
            Activated?.Invoke();
            ActivatedWithArguments?.Invoke(arguments);
        }
    }

    public void Dispose()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;

        try
        {
            _listener?.Dispose();
            _listener = null;
        }
        catch { }

        try
        {
            if (_ownsSocket && File.Exists(_socketPath))
            {
                File.Delete(_socketPath);
            }
        }
        catch { }

        if (OperatingSystem.IsLinux()) _ownerLock?.Unlock(0, 1);
        _ownerLock?.Dispose();
        _ownerLock = null;
    }
}
