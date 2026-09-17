using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Glide.Core.Codec;
using Glide.Core.Models;
using Glide.Core.Pairing;
using Glide.Core.Storage;
using Glide.Core.Transport.Lan;
using Glide.Transport.Framing;
using Glide.Transport.Sockets;
using GlideAppProgram = Glide.App.Program;

namespace Glide.Tests;

[CollectionDefinition("HARD-003 console/runtime", DisableParallelization = true)]
public sealed class Hard003ConsoleRuntimeCollectionDefinition
{
}

/// <summary>
/// Red-before-fix regressions for defects confirmed by the HARD review.
/// These tests intentionally exercise the real Glide.App send/receive orchestration instead of
/// source-text scanning. They are expected to fail until the corresponding HARD-011..016 fixes land.
/// </summary>
[Trait("HARD", "003-red")]
[Collection("HARD-003 console/runtime")]
public sealed class Hard003DesktopRegressionTests : IDisposable
{
    private readonly string _root;

    public Hard003DesktopRegressionTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "GlideHard003", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
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

        var serverTask = Task.Run(async () =>
        {
            await using var serverConnection = await listener.AcceptConnectionAsync();
            try
            {
                return await InvokeReceiveHandlerAsync(serverConnection, store, invitation: null, isDaemon: true);
            }
            catch
            {
                return false;
            }
        });

        LanBootstrapResult bootstrapResult;
        var client = new TlsSocketClient();
        await using (var clientConnection = await client.ConnectAsync("127.0.0.1", listener.BoundPort))
        using (var channel = new LanRecordChannel(clientConnection.SslStream))
        {
            var bootstrap = new LanBootstrapMessage(
                invitationId: NewUuid(),
                token: NewToken(),
                invitationHash: new string('a', 64),
                sessionId: NewUuid());

            await channel.WriteBootstrapAsync(bootstrap);
            bootstrapResult = await channel.ReadBootstrapResultAsync();
        }

        await serverTask;

        Assert.False(
            bootstrapResult.IsAccepted,
            "A headless daemon with no active invitation/enrollment authority must fail closed instead of accepting an arbitrary bootstrap token.");
    }

    [Fact]
    public async Task DirectUnpairedTransfer_BothProductionPaths_MustDisplaySameSas()
    {
        string file = Path.Combine(_root, "sas.txt");
        await File.WriteAllTextAsync(file, "HARD-003 SAS runtime regression");

        using var listener = new TlsSocketListener(port: 0);
        var store = CreateStore("sas");

        var originalOut = Console.Out;
        var capture = new StringWriter();
        Console.SetOut(TextWriter.Synchronized(capture));

        try
        {
            var serverTask = Task.Run(async () =>
            {
                await using var serverConnection = await listener.AcceptConnectionAsync();
                return await InvokeReceiveHandlerAsync(serverConnection, store, invitation: null, isDaemon: false);
            });

            int sendExit = await InvokeSendAsync("127.0.0.1", listener.BoundPort.ToString(), file);
            bool receiveSuccess = await serverTask;

            Assert.Equal(0, sendExit);
            Assert.True(receiveSuccess);
        }
        finally
        {
            Console.SetOut(originalOut);
        }

        MatchCollection codes = Regex.Matches(capture.ToString(), @"6-DIGIT VERIFICATION CODE: \[ (\d{6}) \]");
        Assert.True(codes.Count >= 2, $"Expected both sender and receiver SAS output, got {codes.Count}. Output: {capture}");

        string first = codes[0].Groups[1].Value;
        string second = codes[1].Groups[1].Value;
        Assert.Equal(first, second);
    }

    [Fact]
    public async Task Send_MoreThanOneOfferPage_MustAdvertiseCorrectPageCount()
    {
        var files = new List<string>();
        for (int i = 0; i < 33; i++)
        {
            string path = Path.Combine(_root, $"page-{i:D2}.bin");
            await File.WriteAllBytesAsync(path, Array.Empty<byte>());
            files.Add(path);
        }

        using var listener = new TlsSocketListener(port: 0);
        int observedPageCount = -1;

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);

            Envelope hello = await channel.ReadEnvelopeAsync();
            await channel.WriteEnvelopeAsync(CreateHelloAck(hello.SessionId));

            Envelope offerBegin = await channel.ReadEnvelopeAsync();
            observedPageCount = offerBegin.Body["pageCount"]?.GetValue<int>() ?? -1;
        });

        string[] sendArgs = ["127.0.0.1", listener.BoundPort.ToString(), .. files];
        try
        {
            await InvokeSendAsync(sendArgs);
        }
        catch
        {
            // The current implementation may fault when it tries to encode all 33 items in one page.
        }

        await serverTask;

        Assert.Equal(2, observedPageCount);
    }

    [Fact]
    public async Task Send_MultipleItems_MustUseStrictlyIncreasingControlSequence()
    {
        string firstFile = Path.Combine(_root, "seq-a.bin");
        string secondFile = Path.Combine(_root, "seq-b.bin");
        await File.WriteAllBytesAsync(firstFile, new byte[] { 0x11 });
        await File.WriteAllBytesAsync(secondFile, new byte[] { 0x22 });

        using var listener = new TlsSocketListener(port: 0);
        long firstItemEndSeq = -1;
        long secondItemStartSeq = -1;

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);

            Envelope hello = await channel.ReadEnvelopeAsync();
            await channel.WriteEnvelopeAsync(CreateHelloAck(hello.SessionId));

            Envelope offerBegin = await channel.ReadEnvelopeAsync();
            await channel.ReadEnvelopeAsync(); // OFFER_PAGE
            await channel.ReadEnvelopeAsync(); // OFFER_END

            string batchId = offerBegin.Body["batchId"]!.ToString();
            var decisionBody = new JsonObject { ["batchId"] = batchId, ["accepted"] = true };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.OfferDecision, hello.SessionId, NewMessageId(), 3, decisionBody));

            Envelope itemStart = await channel.ReadEnvelopeAsync();
            string itemId = itemStart.Body["itemId"]!.ToString();
            var readyBody = new JsonObject { ["batchId"] = batchId, ["itemId"] = itemId };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemReady, hello.SessionId, NewMessageId(), 4, readyBody));

            while (true)
            {
                LanRecord? record = await channel.ReadRecordAsync();
                if (record == null)
                {
                    throw new EndOfStreamException("Sender disconnected before STREAM_END.");
                }
                if (record is LanRecord.StreamEnd) break;
            }

            Envelope itemEnd = await channel.ReadEnvelopeAsync();
            firstItemEndSeq = itemEnd.Seq;

            var resultBody = new JsonObject
            {
                ["batchId"] = batchId,
                ["itemId"] = itemId,
                ["status"] = "verified",
                ["sizeBytes"] = itemEnd.Body["actualBytes"]!.ToString(),
                ["sha256"] = itemEnd.Body["sha256"]!.ToString()
            };
            await channel.WriteEnvelopeAsync(new Envelope(1, MessageTypes.ItemResult, hello.SessionId, NewMessageId(), 5, resultBody));

            Envelope nextItemStart = await channel.ReadEnvelopeAsync();
            secondItemStartSeq = nextItemStart.Seq;
        });

        try
        {
            await InvokeSendAsync("127.0.0.1", listener.BoundPort.ToString(), firstFile, secondFile);
        }
        catch
        {
            // Connection closes after the sequence observation is captured.
        }

        await serverTask;

        Assert.True(
            secondItemStartSeq > firstItemEndSeq,
            $"Sender sequence must increase across items; first ITEM_END seq={firstItemEndSeq}, second ITEM_START seq={secondItemStartSeq}.");
    }

    [Fact]
    public async Task Send_PreTransferHashing_MustRemainBoundedMemory()
    {
        const long fileSize = 32L * 1024 * 1024;
        const long allocationBudget = 12L * 1024 * 1024;

        string largeFile = Path.Combine(_root, "bounded-hash.bin");
        await using (var stream = new FileStream(largeFile, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            stream.SetLength(fileSize);
        }

        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
        long before = GC.GetTotalAllocatedBytes(precise: true);

        using var listener = new TlsSocketListener(port: 0);
        long allocationAtOfferPage = -1;

        var serverTask = Task.Run(async () =>
        {
            await using var connection = await listener.AcceptConnectionAsync();
            using var channel = new LanRecordChannel(connection.SslStream);

            Envelope hello = await channel.ReadEnvelopeAsync();
            await channel.WriteEnvelopeAsync(CreateHelloAck(hello.SessionId));
            await channel.ReadEnvelopeAsync(); // OFFER_BEGIN
            await channel.ReadEnvelopeAsync(); // OFFER_PAGE: sender has completed pre-transfer hashing here
            allocationAtOfferPage = GC.GetTotalAllocatedBytes(precise: true) - before;
        });

        try
        {
            await InvokeSendAsync("127.0.0.1", listener.BoundPort.ToString(), largeFile);
        }
        catch
        {
            // Server intentionally closes after observing the allocation point.
        }

        await serverTask;

        Assert.InRange(
            allocationAtOfferPage,
            0L,
            allocationBudget);
    }

    private QuarantineStagingStore CreateStore(string name)
    {
        string staging = Path.Combine(_root, name, "staging");
        string destination = Path.Combine(_root, name, "downloads");
        Directory.CreateDirectory(staging);
        Directory.CreateDirectory(destination);
        return new QuarantineStagingStore(staging, destination);
    }

    private static Envelope CreateHelloAck(SessionId sessionId)
    {
        var limits = new JsonObject
        {
            ["maxItems"] = 1000,
            ["maxItemBytes"] = ProtocolCatalog.MaxItemBytes.ToString(),
            ["maxBatchBytes"] = ProtocolCatalog.MaxBatchBytes.ToString(),
            ["maxControlBytes"] = ProtocolCatalog.MaxControlBytes
        };
        var body = new JsonObject
        {
            ["selectedMajor"] = 1,
            ["capabilities"] = new JsonArray("lan-v1"),
            ["ephemeralPeerId"] = NewUuid(),
            ["displayName"] = "HARD-003 Test Receiver",
            ["limits"] = limits
        };
        return new Envelope(1, MessageTypes.HelloAck, sessionId, NewMessageId(), 2, body);
    }

    private static MessageId NewMessageId() => new(NewUuid());

    private static string NewUuid() => Guid.NewGuid().ToString("D").ToLowerInvariant();

    private static string NewToken()
    {
        return Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    private static async Task<int> InvokeSendAsync(params string[] args)
    {
        MethodInfo method = typeof(GlideAppProgram).GetMethod(
            "RunSendAsync",
            BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new MissingMethodException(typeof(GlideAppProgram).FullName, "RunSendAsync");

        var task = (Task<int>?)method.Invoke(null, new object?[] { args })
            ?? throw new InvalidOperationException("RunSendAsync did not return Task<int>.");
        return await task;
    }

    private static async Task<bool> InvokeReceiveHandlerAsync(
        TlsSocketConnection connection,
        QuarantineStagingStore store,
        LanInvitation? invitation,
        bool isDaemon)
    {
        MethodInfo method = typeof(GlideAppProgram).GetMethod(
            "HandleConnectionTransferAsync",
            BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new MissingMethodException(typeof(GlideAppProgram).FullName, "HandleConnectionTransferAsync");

        var task = (Task<bool>?)method.Invoke(null, new object?[] { connection, store, invitation, isDaemon })
            ?? throw new InvalidOperationException("HandleConnectionTransferAsync did not return Task<bool>.");
        return await task;
    }
}
