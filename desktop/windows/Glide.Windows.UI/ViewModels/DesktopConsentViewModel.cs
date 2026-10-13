using Glide.Desktop;
using Glide.Windows.UI.Common;

namespace Glide.Windows.UI.ViewModels;

/// <summary>
/// Owns the three user decisions that can pause a desktop transfer. Each pending
/// decision is tied to its own task so a late cleanup cannot hide a newer prompt.
/// </summary>
public sealed class DesktopConsentViewModel : ViewModelBase
{
    private readonly Action<Action> _runOnUi;
    private TaskCompletionSource<bool>? _offerDecision;
    private TaskCompletionSource<bool>? _recipientDecision;
    private TaskCompletionSource<bool>? _trustDecision;
    private bool _isOfferVisible;
    private bool _isRecipientVisible;
    private bool _isTrustVisible;
    private string _offerPeer = "";
    private string _offerSummary = "";
    private string _recipientPeer = "";
    private string _trustPeer = "";
    private string _trustFingerprint = "";

    public DesktopConsentViewModel(Action<Action> runOnUi)
    {
        _runOnUi = runOnUi;
        AcceptOfferCommand = new RelayCommand(() => _offerDecision?.TrySetResult(true));
        RejectOfferCommand = new RelayCommand(() => _offerDecision?.TrySetResult(false));
        AcceptRecipientCommand = new RelayCommand(() => _recipientDecision?.TrySetResult(true));
        RejectRecipientCommand = new RelayCommand(() => _recipientDecision?.TrySetResult(false));
        AcceptTrustEnrollmentCommand = new RelayCommand(() => _trustDecision?.TrySetResult(true));
        RejectTrustEnrollmentCommand = new RelayCommand(() => _trustDecision?.TrySetResult(false));
    }

    public RelayCommand AcceptOfferCommand { get; }
    public RelayCommand RejectOfferCommand { get; }
    public RelayCommand AcceptRecipientCommand { get; }
    public RelayCommand RejectRecipientCommand { get; }
    public RelayCommand AcceptTrustEnrollmentCommand { get; }
    public RelayCommand RejectTrustEnrollmentCommand { get; }

    public bool IsOfferModalVisible { get => _isOfferVisible; set => SetProperty(ref _isOfferVisible, value); }
    public bool IsRecipientConfirmationModalVisible { get => _isRecipientVisible; set => SetProperty(ref _isRecipientVisible, value); }
    public bool IsTrustEnrollmentModalVisible { get => _isTrustVisible; set => SetProperty(ref _isTrustVisible, value); }
    public string IncomingOfferPeer { get => _offerPeer; private set => SetProperty(ref _offerPeer, value); }
    public string IncomingOfferSummary { get => _offerSummary; private set => SetProperty(ref _offerSummary, value); }
    public string RecipientConfirmationPeerName { get => _recipientPeer; private set => SetProperty(ref _recipientPeer, value); }
    public string TrustEnrollmentPeerName { get => _trustPeer; private set => SetProperty(ref _trustPeer, value); }
    public string TrustEnrollmentFingerprint { get => _trustFingerprint; private set => SetProperty(ref _trustFingerprint, value); }

    public async Task<bool> ConfirmOfferAsync(DesktopOffer offer, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var decision = NewDecision();
        _runOnUi(() =>
        {
            _offerDecision?.TrySetResult(false);
            _offerDecision = decision;
            IncomingOfferPeer = offer.PeerName;
            IncomingOfferSummary = $"{offer.Items.Count} item(s) • {TransferItemViewModel.FormatSize(offer.TotalBytes)}";
            IsOfferModalVisible = true;
        });
        return await WaitForDecisionAsync(decision, cancellationToken, () =>
        {
            if (!ReferenceEquals(_offerDecision, decision)) return;
            _offerDecision = null;
            IsOfferModalVisible = false;
        });
    }

    public async Task<bool> ConfirmRecipientAsync(string peerName, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var decision = NewDecision();
        _runOnUi(() =>
        {
            _recipientDecision?.TrySetResult(false);
            _recipientDecision = decision;
            RecipientConfirmationPeerName = peerName;
            IsRecipientConfirmationModalVisible = true;
        });
        return await WaitForDecisionAsync(decision, cancellationToken, () =>
        {
            if (!ReferenceEquals(_recipientDecision, decision)) return;
            _recipientDecision = null;
            IsRecipientConfirmationModalVisible = false;
        });
    }

    public async Task<bool> ConfirmTrustEnrollmentAsync(string peerName, string fingerprint,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var decision = NewDecision();
        _runOnUi(() =>
        {
            _trustDecision?.TrySetResult(false);
            _trustDecision = decision;
            TrustEnrollmentPeerName = peerName;
            TrustEnrollmentFingerprint = fingerprint;
            IsTrustEnrollmentModalVisible = true;
        });
        return await WaitForDecisionAsync(decision, cancellationToken, () =>
        {
            if (!ReferenceEquals(_trustDecision, decision)) return;
            _trustDecision = null;
            IsTrustEnrollmentModalVisible = false;
        });
    }

    private static TaskCompletionSource<bool> NewDecision() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private async Task<bool> WaitForDecisionAsync(TaskCompletionSource<bool> decision,
        CancellationToken cancellationToken, Action clear)
    {
        try { return await decision.Task.WaitAsync(cancellationToken); }
        finally { _runOnUi(clear); }
    }
}
