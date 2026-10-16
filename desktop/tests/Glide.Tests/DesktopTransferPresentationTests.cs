using Glide.Desktop;

namespace Glide.Tests;

public sealed class DesktopTransferPresentationTests
{
    [Fact]
    public void QRTransfer_OpensActivityAfterApprovalOnlyOnce_AndKeepsFastResult()
    {
        var state = new DesktopTransferPresentation();
        Guid run = Guid.NewGuid();
        Assert.False(state.ApplySnapshot(new(DesktopTransferPhase.Preparing,
            DesktopTransferDirection.Send, "Phone", "Connecting", RunId: run)));
        Assert.False(state.ApplySnapshot(new(DesktopTransferPhase.AwaitingConsent,
            DesktopTransferDirection.Send, "Phone", "Approval", RunId: run)));
        Assert.True(state.ApplySnapshot(new(DesktopTransferPhase.Transferring,
            DesktopTransferDirection.Send, "Phone", "Sending", RunId: run)));
        Assert.False(state.ApplySnapshot(new(DesktopTransferPhase.Verifying,
            DesktopTransferDirection.Send, "Phone", "Checking", RunId: run)));
        Assert.False(state.ApplySnapshot(new(DesktopTransferPhase.Completed,
            DesktopTransferDirection.Send, "Phone", "Done",
            DesktopTransferResult.Completed("Done", 1, 0), run)));
        Assert.True(state.HasResult);
        Assert.Equal("Transfer complete", state.Title);
        Assert.Equal(100, state.Progress);
    }

    [Fact]
    public void FailedSend_RemainsInSession_WhenASecondAuthenticatedAttemptStarts()
    {
        var state = new DesktopTransferPresentation();
        Guid first = Guid.NewGuid();
        state.ApplySnapshot(new(DesktopTransferPhase.Failed, DesktopTransferDirection.Send,
            "Desktop", "Peer lost", DesktopTransferResult.Failed("Peer lost", TransferErrorCategory.Network), first));
        Assert.True(state.CanRetry);
        Assert.True(state.ApplySnapshot(new(DesktopTransferPhase.Preparing,
            DesktopTransferDirection.Send, "Desktop", "Connecting", RunId: Guid.NewGuid()),
            navigateOnPreparation: true));
        Assert.True(state.HasPreviousResults);
        Assert.Equal("Transfer failed", state.PreviousResults[0].Title);
    }

    [Fact]
    public void AuthenticationRejection_IsShownAsFailure_NotUserDecline()
    {
        var state = new DesktopTransferPresentation();
        state.ApplySnapshot(new(DesktopTransferPhase.Failed, DesktopTransferDirection.Send,
            "Laptop", "Identity changed",
            DesktopTransferResult.RejectedByPeer("Identity changed", TransferErrorCategory.Security),
            Guid.NewGuid()));
        Assert.True(state.IsFailure);
        Assert.Equal("Transfer failed", state.Title);
        Assert.False(state.CanRetry);
    }

    [Fact]
    public void VerifiedReceive_ExposesItsDestinationFolder()
    {
        var state = new DesktopTransferPresentation();
        string path = Path.Combine(Path.GetTempPath(), "glide-test", "received.txt");
        Guid run = Guid.NewGuid();
        state.ApplySnapshot(new(DesktopTransferPhase.Transferring,
            DesktopTransferDirection.Receive, "Laptop", "Receiving", RunId: run));
        state.RecordVerifiedFile(new DesktopHistoryEntry(DesktopTransferDirection.Receive,
            "received.txt", 4, path, "Laptop", DateTimeOffset.UtcNow));
        state.ApplySnapshot(new(DesktopTransferPhase.Completed,
            DesktopTransferDirection.Receive, "Laptop", "Done",
            DesktopTransferResult.Completed("Done", 1, 4), run));
        Assert.True(state.CanOpenFolder);
        Assert.Equal(Path.GetDirectoryName(path), state.ReceivedFolder);
    }

    [Fact]
    public void Verification_HidesTheLastNetworkRate()
    {
        var state = new DesktopTransferPresentation();
        Guid run = Guid.NewGuid();
        state.ApplySnapshot(new(DesktopTransferPhase.Transferring,
            DesktopTransferDirection.Send, "Desktop", "Sending", RunId: run));
        state.ApplyEvent(new TransferEvent.ProgressUpdated(
            new DesktopTransferProgress(DesktopTransferDirection.Send, "Desktop", "large.bin", 100, 200),
            100, "100 B/s", "100 B / 200 B"));
        Assert.Equal("100 B/s", state.RateText);

        state.ApplySnapshot(new(DesktopTransferPhase.Verifying,
            DesktopTransferDirection.Send, "Desktop", "Checking", RunId: run));

        Assert.Equal("Verifying files…", state.PhaseText);
        Assert.Equal("", state.RateText);
    }
}
