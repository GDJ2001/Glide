using System.Reflection;
using Glide.Desktop;
using GlideAppProgram = Glide.App.Program;

namespace Glide.Tests;

[Collection("HARD-003 console/runtime")]
public sealed class DesktopStateAndDaemonPolicyTests : IDisposable
{
    private readonly string _root =
        Path.Combine(
            Path.GetTempPath(),
            "GlideDesktopStateTests",
            Guid.NewGuid().ToString("N"));

    public DesktopStateAndDaemonPolicyTests()
    {
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(
                    _root,
                    recursive: true);
            }
        }
        catch
        {
        }
    }

    [Fact]
    public void StateStore_PersistsSettingsAndVerifiedHistoryWithoutSecrets()
    {
        string path =
            Path.Combine(
                _root,
                "desktop-state.json");
        string destination =
            Path.Combine(
                _root,
                "downloads");
        var store =
            new DesktopStateStore(path);

        var settings =
            new DesktopSettings(
                destination,
                43210);
        store.SaveSettings(settings);

        var entry =
            new DesktopHistoryEntry(
                DesktopTransferDirection.Receive,
                "verified.bin",
                1234,
                Path.Combine(
                    destination,
                    "verified.bin"),
                "Peer",
                DateTimeOffset.UtcNow);
        store.AppendVerifiedHistory(entry);

        var reloaded =
            new DesktopStateStore(path)
                .Load();

        Assert.Equal(
            destination,
            reloaded.Settings.ReceiveDirectory);
        Assert.Equal(
            43210,
            reloaded.Settings.ListenerPort);
        Assert.Single(
            reloaded.History);
        Assert.Equal(
            "verified.bin",
            reloaded.History[0].ItemName);

        string raw =
            File.ReadAllText(path);
        Assert.DoesNotContain(
            "invitation",
            raw,
            StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(
            "token",
            raw,
            StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(
            "spki",
            raw,
            StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Daemon_WithoutExplicitHeadlessAuthorization_FailsClosed()
    {
        MethodInfo method =
            typeof(GlideAppProgram)
                .GetMethod(
                    "RunDaemonAsync",
                    BindingFlags.NonPublic |
                    BindingFlags.Static)
            ?? throw new MissingMethodException(
                typeof(GlideAppProgram)
                    .FullName,
                "RunDaemonAsync");

        var task =
            (Task<int>?)method.Invoke(
                null,
                new object?[]
                {
                    Array.Empty<string>()
                })
            ?? throw new InvalidOperationException(
                "RunDaemonAsync did not return Task<int>.");

        int exitCode =
            await task.WaitAsync(
                TimeSpan.FromSeconds(2));

        Assert.Equal(
            1,
            exitCode);
    }
}
