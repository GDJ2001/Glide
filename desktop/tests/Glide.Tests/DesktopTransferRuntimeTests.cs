using Glide.Desktop;
using Glide.Transport.Sockets;

namespace Glide.Tests;

public sealed class DesktopTransferRuntimeTests
{
    [Fact]
    public async Task Runtime_RejectsSecondBatchWhileFirstBatchOwnsTheSlot()
    {
        var executor = new BlockingExecutor();
        await using var runtime = new DesktopTransferRuntime(executor);

        Task<DesktopTransferResult> first = runtime.SendAsync(Request(), new TestInteraction());
        await executor.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        DesktopTransferResult second = await runtime.SendAsync(Request(), new TestInteraction());

        Assert.False(second.Success);
        Assert.Contains("busy", second.Message, StringComparison.OrdinalIgnoreCase);

        executor.Complete(DesktopTransferResult.Completed("Done", 1, 1));
        Assert.True((await first).Success);
        Assert.Equal(DesktopTransferPhase.Completed, runtime.Snapshot.Phase);
    }

    [Fact]
    public async Task Runtime_CancellationProducesTruthfulTerminalState()
    {
        var executor = new BlockingExecutor();
        await using var runtime = new DesktopTransferRuntime(executor);

        Task<DesktopTransferResult> active = runtime.SendAsync(Request(), new TestInteraction());
        await executor.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.True(runtime.CancelActiveTransfer());
        DesktopTransferResult result = await active.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.True(result.Cancelled);
        Assert.Equal(DesktopTransferPhase.Cancelled, runtime.Snapshot.Phase);
    }

    [Fact]
    public async Task Runtime_WaitsForActualTransferPhaseBeforeReportingDataMovement()
    {
        var executor = new BlockingExecutor();
        await using var runtime = new DesktopTransferRuntime(executor);
        Task<DesktopTransferResult> active = runtime.SendAsync(Request(), new TestInteraction());
        await executor.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Equal(DesktopTransferPhase.Preparing, runtime.Snapshot.Phase);
        Assert.NotEqual(Guid.Empty, runtime.Snapshot.RunId);
        executor.Interaction!.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.AwaitingConsent);
        Assert.Equal(DesktopTransferPhase.AwaitingConsent, runtime.Snapshot.Phase);
        executor.Interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Transferring);
        Assert.Equal(DesktopTransferPhase.Transferring, runtime.Snapshot.Phase);

        executor.Complete(DesktopTransferResult.Completed("Verified", 1, 0));
        Assert.True((await active).Success);
        Assert.Equal(1, runtime.Snapshot.Result!.VerifiedCount);
    }

    [Fact]
    public async Task Runtime_ConsentDoesNotPrematurelyReportTransferring()
    {
        var executor = new BlockingExecutor();
        await using var runtime = new DesktopTransferRuntime(executor);
        Task<DesktopTransferResult> active = runtime.SendAsync(Request(), new ApprovingInteraction());
        await executor.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.True(await executor.Interaction!.ConfirmRecipientAsync("Test peer", CancellationToken.None));
        Assert.Equal(DesktopTransferPhase.AwaitingConsent, runtime.Snapshot.Phase);

        var offer = new DesktopOffer("batch", "Test peer", [], 0);
        Assert.True(await executor.Interaction.ConfirmOfferAsync(offer, CancellationToken.None));
        Assert.Equal(DesktopTransferPhase.AwaitingConsent, runtime.Snapshot.Phase);

        executor.Interaction.OnPhase(DesktopTransferDirection.Send, DesktopTransferPhase.Transferring);
        Assert.Equal(DesktopTransferPhase.Transferring, runtime.Snapshot.Phase);
        executor.Complete(DesktopTransferResult.Completed("Verified", 0, 0));
        Assert.True((await active).Success);
    }

    [Fact]
    public async Task Runtime_UnhandledExecutorErrorStillProducesOneTerminalFailure()
    {
        await using var runtime = new DesktopTransferRuntime(new ThrowingExecutor());
        var terminal = new List<DesktopTransferSnapshot>();
        runtime.SnapshotChanged += (_, snapshot) =>
        {
            if (snapshot.Result is not null) terminal.Add(snapshot);
        };

        DesktopTransferResult result = await runtime.SendAsync(Request(), new TestInteraction());

        Assert.False(result.Success);
        Assert.Equal(TransferErrorCategory.Network, result.ErrorCategory);
        Assert.Single(terminal);
        Assert.Equal(result, runtime.Snapshot.Result);
    }

    [Fact]
    public async Task Runtime_CoalescesChunkProgressButKeepsFirstItemChangeAndCompletion()
    {
        var executor = new BlockingExecutor();
        await using var runtime = new DesktopTransferRuntime(executor);
        var updates = new List<TransferEvent.ProgressUpdated>();
        var interaction = new ProgressInteraction();
        runtime.EventOccurred += (_, ev) =>
        {
            if (ev is TransferEvent.ProgressUpdated update) updates.Add(update);
        };

        Task<DesktopTransferResult> active = runtime.SendAsync(Request(), interaction);
        await executor.Started.Task.WaitAsync(TimeSpan.FromSeconds(2));

        for (int i = 1; i <= 100; i++)
        {
            executor.Interaction!.OnProgress(new DesktopTransferProgress(
                DesktopTransferDirection.Send, "Peer", "first.bin", i * 64_000, 10_000_000));
        }
        executor.Interaction!.OnProgress(new DesktopTransferProgress(
            DesktopTransferDirection.Send, "Peer", "second.bin", 7_000_000, 10_000_000));
        executor.Interaction.OnProgress(new DesktopTransferProgress(
            DesktopTransferDirection.Send, "Peer", "second.bin", 10_000_000, 10_000_000));

        Assert.InRange(updates.Count, 3, 10);
        Assert.Equal(updates.Count, interaction.ProgressCount);
        Assert.Equal("first.bin", updates[0].Progress.ItemName);
        Assert.Contains(updates, update => update.Progress.ItemName == "second.bin");
        Assert.Equal(10_000_000, updates[^1].Progress.TransferredBytes);

        executor.Complete(DesktopTransferResult.Completed("Done", 2, 10_000_000));
        Assert.True((await active).Success);
    }

    private static DesktopSendRequest Request() =>
        new("unused", 1, []);

    private sealed class BlockingExecutor : IDesktopTransferExecutor
    {
        private readonly TaskCompletionSource<DesktopTransferResult> _completion = new(
            TaskCreationOptions.RunContinuationsAsynchronously);

        public TaskCompletionSource<bool> Started { get; } = new(
            TaskCreationOptions.RunContinuationsAsynchronously);
        public IDesktopTransferInteraction? Interaction { get; private set; }

        public Task<DesktopTransferResult> SendAsync(
            DesktopSendRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default)
        {
            Interaction = interaction;
            Started.TrySetResult(true);
            return _completion.Task.WaitAsync(cancellationToken);
        }

        public Task<DesktopTransferResult> ReceiveAsync(
            TlsSocketConnection connection,
            DesktopReceiveRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public Task<DesktopTransferResult> ReceiveFromInvitationAsync(
            DesktopReceiveRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public void Complete(DesktopTransferResult result) =>
            _completion.TrySetResult(result);
    }

    private sealed class ThrowingExecutor : IDesktopTransferExecutor
    {
        public Task<DesktopTransferResult> SendAsync(DesktopSendRequest request,
            IDesktopTransferInteraction interaction, CancellationToken cancellationToken = default) =>
            throw new System.Net.Sockets.SocketException();
        public Task<DesktopTransferResult> ReceiveAsync(TlsSocketConnection connection,
            DesktopReceiveRequest request, IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default) => throw new NotSupportedException();
    }

    private sealed class TestInteraction : DesktopTransferInteractionBase
    {
    }

    private sealed class ApprovingInteraction : DesktopTransferInteractionBase
    {
        public override Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken) =>
            Task.FromResult(true);

        public override Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken) =>
            Task.FromResult(true);
    }

    private sealed class ProgressInteraction : DesktopTransferInteractionBase
    {
        public int ProgressCount { get; private set; }
        public override void OnProgress(DesktopTransferProgress progress) => ProgressCount++;
    }
}
