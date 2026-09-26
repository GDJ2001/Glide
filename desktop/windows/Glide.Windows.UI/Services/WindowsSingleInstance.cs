using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Per-user single-instance ownership with bounded, same-user activation forwarding.
/// No process is found or terminated by name; a second launch only sends its arguments to the
/// already-running instance and exits.
/// </summary>
public sealed class WindowsSingleInstance : IAsyncDisposable
{
    private const string MutexName = "Local\\Glide.Windows.UI";
    private const string PipeName = "Glide.Windows.UI.Activation";
    private const int MaxActivationBytes = 64 * 1024;

    private readonly Mutex _mutex;
    private readonly CancellationTokenSource _stop = new();
    private Task? _listenTask;

    private WindowsSingleInstance(Mutex mutex, bool ownsMutex)
    {
        _mutex = mutex;
        IsPrimaryInstance = ownsMutex;
    }

    public bool IsPrimaryInstance { get; }

    public static WindowsSingleInstance Acquire()
    {
        var mutex = new Mutex(initiallyOwned: true, MutexName, out bool ownsMutex);
        return new WindowsSingleInstance(mutex, ownsMutex);
    }

    public void Start(Func<IReadOnlyList<string>, Task> activationHandler)
    {
        if (!IsPrimaryInstance)
        {
            throw new InvalidOperationException("Only the primary Glide process can receive activations.");
        }

        ArgumentNullException.ThrowIfNull(activationHandler);
        _listenTask ??= ListenAsync(activationHandler, _stop.Token);
    }

    public static async Task<bool> ForwardActivationAsync(
        IReadOnlyList<string> arguments,
        CancellationToken cancellationToken = default)
    {
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(arguments);
        if (payload.Length > MaxActivationBytes)
        {
            return false;
        }

        try
        {
            using var client = new NamedPipeClientStream(
                ".", PipeName, PipeDirection.Out, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            await client.ConnectAsync(1_000, cancellationToken).ConfigureAwait(false);
            await WriteMessageAsync(client, payload, cancellationToken).ConfigureAwait(false);
            await client.FlushAsync(cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static async Task WriteMessageAsync(Stream stream, byte[] payload, CancellationToken cancellationToken)
    {
        byte[] length = BitConverter.GetBytes(payload.Length);
        await stream.WriteAsync(length, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
    }

    private async Task ListenAsync(Func<IReadOnlyList<string>, Task> activationHandler, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(
                    PipeName,
                    PipeDirection.In,
                    1,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                byte[]? payload = await ReadMessageAsync(server, cancellationToken).ConfigureAwait(false);
                if (payload is null)
                {
                    continue;
                }

                string[]? arguments = JsonSerializer.Deserialize<string[]>(payload);
                if (arguments is not null)
                {
                    await activationHandler(arguments).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (IOException)
            {
                // A malformed or disconnected client cannot compromise the existing UI process.
            }
            catch (JsonException)
            {
                // Ignore malformed activation input.
            }
        }
    }

    private static async Task<byte[]?> ReadMessageAsync(Stream stream, CancellationToken cancellationToken)
    {
        byte[] length = new byte[sizeof(int)];
        if (!await ReadExactlyAsync(stream, length, cancellationToken).ConfigureAwait(false))
        {
            return null;
        }

        int payloadLength = BitConverter.ToInt32(length, 0);
        if (payloadLength is < 0 or > MaxActivationBytes)
        {
            return null;
        }

        byte[] payload = new byte[payloadLength];
        return await ReadExactlyAsync(stream, payload, cancellationToken).ConfigureAwait(false)
            ? payload
            : null;
    }

    private static async Task<bool> ReadExactlyAsync(Stream stream, byte[] buffer, CancellationToken cancellationToken)
    {
        int offset = 0;
        while (offset < buffer.Length)
        {
            int read = await stream.ReadAsync(buffer.AsMemory(offset), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                return false;
            }
            offset += read;
        }
        return true;
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        if (_listenTask is not null)
        {
            try
            {
                await _listenTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // Shutdown cancellation is expected.
            }
        }
        _stop.Dispose();
        if (IsPrimaryInstance)
        {
            _mutex.ReleaseMutex();
        }
        _mutex.Dispose();
    }
}
