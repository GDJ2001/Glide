using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Transport.Discovery;

namespace Glide.App;

public static partial class Program
{
    private static readonly Lazy<DesktopResumeStore> ResumeStore = new(() => new DesktopResumeStore());

    public sealed record ResumeRunOptions(string SessionId, string? ConnectEndpoint, string? DestinationRoot);

    public static bool TryParseResumeRunArgs(string[] args, out ResumeRunOptions? options)
    {
        options = null;
        if (args.Length < 2 || args[0] != "run" || !IdentifierValidation.IsValidUuidV4(args[1]))
            return false;

        string? connect = null;
        string? destination = null;
        for (int i = 2; i < args.Length; i++)
        {
            if (args[i] == "--connect" && i + 1 < args.Length && connect == null)
            {
                connect = args[++i];
            }
            else if (args[i] == "--destination" && i + 1 < args.Length && destination == null && !string.IsNullOrWhiteSpace(args[i + 1]))
            {
                destination = Path.GetFullPath(args[++i]);
            }
            else
            {
                return false;
            }
        }
        options = new ResumeRunOptions(args[1], connect, destination);
        return true;
    }

    private static async Task<int> RunResumeAsync(string[] args)
    {
        var store = ResumeStore.Value;
        var trust = CreateDesktopTrustManager();
        trust.Initialize();
        var entries = await DesktopResumeEntry.LoadAsync(store, trust).ConfigureAwait(false);
        if (args is ["list"])
        {
            foreach (var entry in entries)
                Console.WriteLine($"{entry.Batch.Manifest.SessionId}  {entry.Label}");
            return 0;
        }
        if (args is ["discard", _] && IdentifierValidation.IsValidUuidV4(args[1]))
        {
            return await store.PurgeOriginalSessionAsync(new SessionId(args[1])).ConfigureAwait(false) > 0 ? 0 : 1;
        }
        if (!TryParseResumeRunArgs(args, out var runOptions) || runOptions == null)
        {
            PrintUsage();
            return 1;
        }
        var batch = entries.FirstOrDefault(entry => entry.Batch.Manifest.SessionId == runOptions.SessionId)?.Batch;
        if (batch == null || trust.GetTrustedPeer(batch.PeerFingerprintHex) is not { } peer)
        {
            Console.Error.WriteLine("No matching active transfer for a paired device.");
            return 1;
        }
        using var mdns = new MdnsDiscoveryService();
        mdns.Start(enableBrowsing: true);
        mdns.QueryPeers();
        await Task.Delay(450).ConfigureAwait(false);
        var candidates = TrustedEndpointResolver.ResolveCandidates(peer, mdns.Snapshot.Peers).ToList();
        if (runOptions.ConnectEndpoint != null)
        {
            if (!TryParseConnectEndpoint(runOptions.ConnectEndpoint, out string? host, out int port) || port == 0 ||
                !System.Net.IPAddress.TryParse(host, out var address) || !TrustedEndpointResolver.IsValidCandidateAddress(address))
            {
                Console.Error.WriteLine("Use a current local IPv4 address and listening port.");
                return 1;
            }
            candidates.Insert(0, new TrustedEndpointCandidate(new LanEndpoint(host!, port), false, true));
        }
        var stagingStore = string.IsNullOrWhiteSpace(runOptions.DestinationRoot)
            ? new QuarantineStagingStore()
            : new QuarantineStagingStore(destinationRoot: runOptions.DestinationRoot);
        var local = new DesktopReceiveRequest(stagingStore, LocalDeviceName: Environment.MachineName,
            TrustManager: trust, ResumeStore: store);
        await using var runtime = new DesktopTransferRuntime();
        using var cancellation = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            cancellation.Cancel();
        };
        Console.CancelKeyPress += cancel;
        try
        {
            var result = await runtime.ResumeTrustedAsync(new DesktopResumeRequest(batch, local, candidates),
                new ConsoleDesktopTransferInteraction(isDaemon: false, trustManager: trust, destinationRoot: runOptions.DestinationRoot),
                cancellation.Token).ConfigureAwait(false);
            Console.WriteLine(result.Message);
            return result.Success ? 0 : 1;
        }
        finally
        {
            Console.CancelKeyPress -= cancel;
        }
    }
}
