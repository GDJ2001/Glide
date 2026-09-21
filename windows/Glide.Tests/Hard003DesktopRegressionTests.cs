using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Desktop;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using GlideAppProgram = Glide.App.Program;

namespace Glide.Tests;

[CollectionDefinition("HARD-003 console/runtime", DisableParallelization = true)]
public sealed class Hard003ConsoleRuntimeCollectionDefinition
{
}

/// <summary>
/// Executable regressions discovered by HARD-003.
///
/// These cases now serve as ordinary permanent regression gates for HARD-012 through HARD-014:
/// unauthenticated bootstrap/direct send must fail closed, offers must paginate, controls must
/// sequence monotonically, and large-file preparation must remain bounded-memory.
/// </summary>
[Collection("HARD-003 console/runtime")]
public sealed class Hard003DesktopRegressionTests : IDisposable
{
    private readonly string _root;

    public Hard003DesktopRegressionTests()
    {
        _root = Path.Combine(
            Path.GetTempPath(),
            "GlideHard003",
            Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch
        {
            // Test cleanup must not mask the regression result.
        }
    }

    [Fact]
    public async Task DaemonBootstrap_WithoutInvitationAuthority_MustReject()
    {
        using var listener = new TlsSocketListener(port: 0);
        var store = CreateStore("daemon-auth");
        var authority = new LanInvitationAuthority();
        var engine = new DesktopTransferEngine();

        var serverTask = Task.Run(async () =>
        {
            await using var serverConnection =
                await listener.AcceptConnectionAsync();
            return await engine.ReceiveAsync(
                serverConnection,
                new DesktopReceiveRequest(
                    store,
                    authority,
                    "HARD-003 receiver"),
                new RejectingInteraction());
        });

        LanBootstrapResult bootstrapResult;
        var client = new TlsSocketClient();
        await using (var clientConnection = await client.ConnectAsync(
            "127.0.0.1",