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

    private static DesktopSendRequest Request() =>
        new("unused", 1, []);

    private sealed class BlockingExecutor : IDesktopTransferExecutor
    {
        private readonly TaskCompletionSource<DesktopTransferResult> _completion = new(
            TaskCreationOptions.RunContinuationsAsynchronously);

        public TaskCompletionSource<bool> Started { get; } = new(
            TaskCreationOptions.RunContinuationsAsynchronously);

        public Task<DesktopTransferResult> SendAsync(
            DesktopSendRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default)
        {
            Started.TrySetResult(true);
            return _completion.Task.WaitAsync(cancellationToken);
        }

        public Task<DesktopTransferResult> ReceiveAsync(
            TlsSocketConnection connection,
            DesktopReceiveRequest request,
            IDesktopTransferInteraction interaction,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();

        public void Complete(DesktopTransferResult result) =>
            _completion.TrySetResult(result);
    }

    private sealed class TestInteraction : DesktopTransferInteractionBase
    {
    }
}
