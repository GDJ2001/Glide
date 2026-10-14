using Glide.Core.Models;
using Glide.Core.Security;
using Glide.Desktop;
using Glide.Desktop.Services;
using Glide.Linux.UI.ViewModels;
using Xunit;

namespace Glide.Tests;

public sealed class LinuxPresentationTests
{
    [Fact]
    public void TabNavigation_SwitchesActiveTabsCorrectly()
    {
        var vm = new LinuxMainViewModel();
        Assert.Equal("Send", vm.CurrentTab);

        vm.SwitchTabCommand.Execute("Receive");
        Assert.Equal("Receive", vm.CurrentTab);

        vm.SwitchTabCommand.Execute("Pair");
        Assert.Equal("Pair", vm.CurrentTab);

        vm.SwitchTabCommand.Execute("Activity");
        Assert.Equal("Activity", vm.CurrentTab);

        vm.SwitchTabCommand.Execute("Settings");
        Assert.Equal("Settings", vm.CurrentTab);
    }

    [Fact]
    public async Task FileStaging_StageAndRemoveItems_UpdatesStagedItemsAndCounts()
    {
        var vm = new LinuxMainViewModel();
        Assert.Empty(vm.StagedItems);
        Assert.Equal(0, vm.StagedCount);

        // Create temporary test files
        string tempDir = Path.Combine(Path.GetTempPath(), "GlideLinuxTest_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempDir);

        try
        {
            string file1 = Path.Combine(tempDir, "document.pdf");
            string file2 = Path.Combine(tempDir, "image_sample.png");
            File.WriteAllBytes(file1, new byte[1024]);
            File.WriteAllBytes(file2, new byte[2048]);

            await vm.StagePathsAsync([file1, file2]);
            Assert.Equal(2, vm.StagedCount);
            Assert.Equal("document.pdf", vm.StagedItems[0].Name);
            Assert.Equal("1.0 KB", vm.StagedItems[0].FormattedSize);
            Assert.Equal(3072, vm.TotalStagedBytes);

            // Removing one item
            var firstItem = vm.StagedItems[0];
            vm.RemoveStagedItemCommand.Execute(firstItem);
            Assert.Equal(1, vm.StagedCount);
            Assert.Equal("image_sample.png", vm.StagedItems[0].Name);

            // Clearing all items
            vm.ClearStagedItemsCommand.Execute(null);
            Assert.Empty(vm.StagedItems);
            Assert.Equal(0, vm.StagedCount);
        }
        finally
        {
            Directory.Delete(tempDir, true);
        }
    }

    [Fact]
    public async Task VerifiedIdentityEnrollment_Confirm_ShowsFullFingerprint()
    {
        var vm = new LinuxMainViewModel();
        Assert.False(vm.IsSasModalVisible);

        var peer = new VerifiedDesktopPeerIdentity(
            fingerprintHex: "1234567890ABCDEF",
            publicKeyDerHex: new string('a', 64),
            displayName: "Ubuntu Workstation");

        var sasTask = vm.ConfirmPeerEnrollmentAsync(peer, CancellationToken.None);
        Assert.True(vm.IsSasModalVisible);
        Assert.Equal("Ubuntu Workstation", vm.SasPeerName);
        Assert.Equal("12345678 90ABCDEF", vm.SasCode);

        // User clicks Accept
        vm.AcceptSasCommand.Execute(null);
        bool result = await sasTask;

        Assert.True(result);
        Assert.False(vm.IsSasModalVisible);
    }

    [Fact]
    public async Task VerifiedIdentityEnrollment_Decline_ReturnsFalse()
    {
        var vm = new LinuxMainViewModel();
        Assert.False(vm.IsSasModalVisible);

        var peer = new VerifiedDesktopPeerIdentity(
            fingerprintHex: "FEDCBA0987654321",
            publicKeyDerHex: new string('b', 64),
            displayName: "Pixel 8");

        var sasTask = vm.ConfirmPeerEnrollmentAsync(peer, CancellationToken.None);
        Assert.True(vm.IsSasModalVisible);

        // User clicks Decline
        vm.DeclineSasCommand.Execute(null);
        bool result = await sasTask;

        Assert.False(result);
        Assert.False(vm.IsSasModalVisible);
    }

    [Fact]
    public async Task IncomingOfferModal_Accept_ReturnsTrue()
    {
        var vm = new LinuxMainViewModel();
        Assert.False(vm.IsOfferModalVisible);

        var offer = new DesktopOffer(
            BatchId: "b1a7c001-0000-0000-0000-000000000001",
            PeerName: "MacBook Pro",
            Items: [new DesktopOfferItem("item-1", "photo.jpg", 1048576, "photo.jpg")],
            TotalBytes: 1048576);

        var offerTask = vm.ConfirmOfferAsync(offer, CancellationToken.None);
        Assert.True(vm.IsOfferModalVisible);
        Assert.Equal("MacBook Pro", vm.OfferPeerName);
        Assert.Contains("1 file(s)", vm.OfferSummaryText);

        // User accepts
        vm.AcceptOfferCommand.Execute(null);
        bool result = await offerTask;

        Assert.True(result);
        Assert.False(vm.IsOfferModalVisible);
    }

    [Fact]
    public async Task IncomingOfferModal_Decline_ReturnsFalse()
    {
        var vm = new LinuxMainViewModel();
        Assert.False(vm.IsOfferModalVisible);

        var offer = new DesktopOffer(
            BatchId: "b1a7c001-0000-0000-0000-000000000002",
            PeerName: "Untrusted Host",
            Items: [new DesktopOfferItem("item-1", "malware.exe", 512, "malware.exe")],
            TotalBytes: 512);

        var offerTask = vm.ConfirmOfferAsync(offer, CancellationToken.None);
        Assert.True(vm.IsOfferModalVisible);

        // User rejects
        vm.DeclineOfferCommand.Execute(null);
        bool result = await offerTask;

        Assert.False(result);
        Assert.False(vm.IsOfferModalVisible);
    }

    [Fact]
    public async Task IncomingOfferModal_ReplacedRequestCannotHideNewOffer()
    {
        using var vm = new LinuxMainViewModel();
        var first = new DesktopOffer("first", "First device", [], 0);
        var second = new DesktopOffer("second", "Second device", [], 0);

        Task<bool> firstDecision = vm.ConfirmOfferAsync(first, CancellationToken.None);
        Task<bool> secondDecision = vm.ConfirmOfferAsync(second, CancellationToken.None);

        Assert.False(await firstDecision);
        Assert.True(vm.IsOfferModalVisible);
        Assert.Equal("Second device", vm.OfferPeerName);

        vm.AcceptOfferCommand.Execute(null);
        Assert.True(await secondDecision);
        Assert.False(vm.IsOfferModalVisible);
    }

    [Fact]
    public async Task IncomingOfferModal_CancellationDeclinesAndClearsPrompt()
    {
        using var vm = new LinuxMainViewModel();
        using var cancellation = new CancellationTokenSource();
        var offer = new DesktopOffer("cancelled", "Peer", [], 0);

        Task<bool> decision = vm.ConfirmOfferAsync(offer, cancellation.Token);
        Assert.True(vm.IsOfferModalVisible);
        cancellation.Cancel();

        Assert.False(await decision);
        Assert.False(vm.IsOfferModalVisible);
    }

    [Fact]
    public void TransferProgressAndStatus_UpdatesPresentationProperties()
    {
        var vm = new LinuxMainViewModel();
        vm.HistoryItems.Clear();

        // Simulate progress update
        var progress = new DesktopTransferProgress(
            Direction: DesktopTransferDirection.Send,
            PeerName: "Android Phone",
            ItemName: "archive.zip",
            TransferredBytes: 524288,
            TotalBytes: 1048576);

        vm.OnProgress(progress);
        Assert.Equal(0.5, vm.TransferProgressFraction);
        Assert.Equal(50, vm.TransferProgressPercent);
        Assert.Contains("512.0 KB", vm.TransferredBytesText);

        // Simulate item status update
        vm.OnItemStatus(DesktopTransferDirection.Send, "archive.zip", "Transferring");
        Assert.Equal("archive.zip (Transferring)", vm.CurrentTransferFile);

        // Simulate history record
        var historyEntry = new DesktopHistoryEntry(
            Direction: DesktopTransferDirection.Send,
            ItemName: "archive.zip",
            SizeBytes: 1048576,
            Path: "/home/user/archive.zip",
            PeerName: "Android Phone",
            Timestamp: DateTimeOffset.UtcNow);

        vm.OnHistory(historyEntry);
        Assert.Single(vm.HistoryItems);
        Assert.Equal("Android Phone", vm.HistoryItems[0].PeerName);
    }
}
