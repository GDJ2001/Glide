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
        if (args.Length < 2 || !IdentifierValidation.IsValidUuidV4(args[1]))
        {
            PrintUsage();
            return 1;
        }
        if (args is ["discard", _])
        {
            return await store.PurgeOriginalSessionAsync(new SessionId(args[1])).ConfigureAwait(false) > 0 ? 0 : 1;
        }
        if (args[0] != "run" || (args.Length != 2 && (args.Length != 4 || args[2] != "--connect")))
        {
            PrintUsage();
            return 1;
        }
        var batch = entries.FirstOrDefault(entry => entry.Batch.Manifest.SessionId == args[1])?.Batch;
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
        if (args.Length == 4)
        {
            if (!TryParseConnectEndpoint(args[3], out string? host, out int port) || port == 0 ||
                !System.Net.IPAddress.TryParse(host, out var address) || !TrustedEndpointResolver.IsValidCandidateAddress(address))
            {
                Console.Error.WriteLine("Use a current local IPv4 address and listening port.");
                return 1;
            }
            candidates.Insert(0, new TrustedEndpointCandidate(new LanEndpoint(host!, port), false, true));
        }
        var local = new DesktopReceiveRequest(new QuarantineStagingStore(), LocalDeviceName: Environment.MachineName,
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
                new ConsoleDesktopTransferInteraction(isDaemon: false, trustManager: trust), cancellation.Token)
                .ConfigureAwait(false);
            Console.WriteLine(result.Message);
            return result.Success ? 0 : 1;
        }
        finally
        {
            Console.CancelKeyPress -= cancel;
        }
    }
}
