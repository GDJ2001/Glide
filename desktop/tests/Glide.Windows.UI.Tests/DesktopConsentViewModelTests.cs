using Glide.Desktop;
using Glide.Windows.UI.ViewModels;

namespace Glide.Windows.UI.Tests;

public sealed class DesktopConsentViewModelTests
{
    [Fact]
    public async Task IncomingOffer_RequiresExplicitAcceptance()
    {
        var consent = new DesktopConsentViewModel(action => action());
        var offer = new DesktopOffer("batch", "Peer PC",
            [new DesktopOfferItem("item", "file.txt", 16, "file.txt")], 16);

        Task<bool> pending = consent.ConfirmOfferAsync(offer, CancellationToken.None);

        Assert.True(consent.IsOfferModalVisible);
        Assert.Equal("Peer PC", consent.IncomingOfferPeer);
        Assert.False(pending.IsCompleted);
        consent.AcceptOfferCommand.Execute(null);
        Assert.True(await pending);
        Assert.False(consent.IsOfferModalVisible);
    }

    [Fact]
    public async Task ReplacedRecipientDecision_DoesNotLetOldCleanupHideNewPrompt()
    {
        var consent = new DesktopConsentViewModel(action => action());
        Task<bool> first = consent.ConfirmRecipientAsync("Old peer", CancellationToken.None);
        Task<bool> second = consent.ConfirmRecipientAsync("New peer", CancellationToken.None);

        Assert.False(await first);
        Assert.True(consent.IsRecipientConfirmationModalVisible);
        Assert.Equal("New peer", consent.RecipientConfirmationPeerName);
        consent.RejectRecipientCommand.Execute(null);
        Assert.False(await second);
        Assert.False(consent.IsRecipientConfirmationModalVisible);
    }

    [Fact]
    public async Task CancelledTrustDecision_ClearsPromptWithoutEnrollingPeer()
    {
        var consent = new DesktopConsentViewModel(action => action());
        using var cancellation = new CancellationTokenSource();
        Task<bool> pending = consent.ConfirmTrustEnrollmentAsync(
            "Peer PC", "ABCD", cancellation.Token);

        Assert.True(consent.IsTrustEnrollmentModalVisible);
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
        Assert.False(consent.IsTrustEnrollmentModalVisible);
    }
}
