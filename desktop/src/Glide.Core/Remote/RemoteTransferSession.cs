using System;
using System.Collections.Generic;
using System.Linq;

namespace Glide.Core.Remote;

/// <summary>
/// Lifecycle states of an end-to-end remote internet transfer session.
/// </summary>
public enum RemoteSessionState
{
    Created,
    AwaitingUserConsent,
    Signaling,
    GatheringCandidates,
    NegotiatingPath,
    Transferring,
    Completed,
    Failed
}

/// <summary>
/// Resolved transport path between remote peers.
/// </summary>
public enum ConnectionPath
{
    DirectHost,
    ServerReflexive,
    RelayedFallback
}

/// <summary>
/// Orchestrates the lifecycle of a remote internet transfer session.
/// Coordinates invitation validation, metered network consent, candidate selection
/// (RFC 8445 priority: Host > ServerReflexive > Relayed), relay anti-abuse quotas (100 MB cap),
/// and end-to-end session progress tracking.
/// Conforms to ADR-0022 and REMOTE_INTERNET_TRANSFER_SPECIFICATION.md.
/// </summary>
public sealed class RemoteTransferSession
{
    private readonly IMeteredNetworkDetector _meteredDetector;
    private readonly MeteredTransferPolicy _meteredPolicy;
    private readonly RelayQuotaTracker _quotaTracker;
    private readonly object _lock = new();

    public RemoteInvitation Invitation { get; }
    public string ItemName { get; }
    public long TotalSizeBytes { get; }

    public RemoteSessionState State { get; private set; } = RemoteSessionState.Created;
    public ConnectionPath? SelectedPath { get; private set; }
    public IceCandidate? LocalCandidate { get; private set; }
    public IceCandidate? RemoteCandidate { get; private set; }
    public long TransferredBytes { get; private set; }
    public string? FailureReason { get; private set; }

    public RemoteTransferSession(
        RemoteInvitation invitation,
        string itemName,
        long totalSizeBytes,
        IMeteredNetworkDetector? meteredDetector = null,
        RelayQuotaTracker? quotaTracker = null)
    {
        ArgumentNullException.ThrowIfNull(invitation);
        ArgumentException.ThrowIfNullOrWhiteSpace(itemName);
        if (totalSizeBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(totalSizeBytes), "Total size cannot be negative.");
        }

        Invitation = invitation;
        ItemName = itemName;
        TotalSizeBytes = totalSizeBytes;

        _meteredDetector = meteredDetector ?? new DefaultUnmeteredDetector();
        _meteredPolicy = new MeteredTransferPolicy(_meteredDetector);
        _quotaTracker = quotaTracker ?? new RelayQuotaTracker();
    }

    /// <summary>
    /// Evaluates whether the proposed transfer requires explicit user confirmation
    /// due to a metered cellular connection.
    /// </summary>
    public bool EvaluateMeteredPolicy(out MeteredTransferPrompt? prompt)
    {
        lock (_lock)
        {
            if (Invitation.IsExpired())
            {
                Fail("Remote invitation has expired.");
                prompt = null;
                return false;
            }

            if (_meteredPolicy.RequiresConfirmation(TotalSizeBytes))
            {
                State = RemoteSessionState.AwaitingUserConsent;
                prompt = _meteredPolicy.CreatePrompt(ItemName, TotalSizeBytes);
                return true;
            }

            State = RemoteSessionState.Signaling;
            prompt = null;
            return false;
        }
    }

    /// <summary>
    /// Processes user decision when prompted for metered cellular transfer consent.
    /// </summary>
    public void ConfirmMeteredTransfer(bool proceed)
    {
        lock (_lock)
        {
            if (State != RemoteSessionState.AwaitingUserConsent)
            {
                throw new InvalidOperationException($"Cannot confirm consent when session state is {State}.");
            }

            if (!proceed)
            {
                Fail("Transfer cancelled by user due to metered connection.");
                return;
            }

            State = RemoteSessionState.Signaling;
        }
    }

    /// <summary>
    /// Selects the optimal candidate pair adhering strictly to RFC 8445 priority:
    /// Direct Host > Server-Reflexive (STUN) > Relayed Fallback (TURN).
    /// Enforces the 100 MB relay quota if the relayed fallback path is chosen.
    /// </summary>
    public ConnectionPath SelectCandidatePair(
        IReadOnlyList<IceCandidate> localCandidates,
        IReadOnlyList<IceCandidate> remoteCandidates)
    {
        ArgumentNullException.ThrowIfNull(localCandidates);
        ArgumentNullException.ThrowIfNull(remoteCandidates);

        lock (_lock)
        {
            if (State == RemoteSessionState.Failed)
            {
                throw new InvalidOperationException($"Session failed: {FailureReason}");
            }

            State = RemoteSessionState.NegotiatingPath;

            // Sort descending by RFC 8445 priority (Host: ~2.1B > Reflexive: ~1.8B > Relayed: <65K)
            var sortedLocal = localCandidates.OrderByDescending(c => c.Priority).ToList();
            var sortedRemote = remoteCandidates.OrderByDescending(c => c.Priority).ToList();

            // 1. Direct Host match
            var hostLocal = sortedLocal.FirstOrDefault(c => c.Type == IceCandidateType.Host);
            var hostRemote = sortedRemote.FirstOrDefault(c => c.Type == IceCandidateType.Host);
            if (hostLocal != null && hostRemote != null)
            {
                LocalCandidate = hostLocal;
                RemoteCandidate = hostRemote;
                SelectedPath = ConnectionPath.DirectHost;
                State = RemoteSessionState.Transferring;
                return ConnectionPath.DirectHost;
            }

            // 2. Server-Reflexive STUN match
            var srflxLocal = sortedLocal.FirstOrDefault(c => c.Type == IceCandidateType.ServerReflexive);
            var srflxRemote = sortedRemote.FirstOrDefault(c => c.Type == IceCandidateType.ServerReflexive);
            if (srflxLocal != null && srflxRemote != null)
            {
                LocalCandidate = srflxLocal;
                RemoteCandidate = srflxRemote;
                SelectedPath = ConnectionPath.ServerReflexive;
                State = RemoteSessionState.Transferring;
                return ConnectionPath.ServerReflexive;
            }

            // 3. Relayed fallback (TURN)
            var relayLocal = sortedLocal.FirstOrDefault(c => c.Type == IceCandidateType.Relayed);
            var relayRemote = sortedRemote.FirstOrDefault(c => c.Type == IceCandidateType.Relayed);
            if (relayLocal != null || relayRemote != null)
            {
                LocalCandidate = relayLocal ?? hostLocal ?? srflxLocal;
                RemoteCandidate = relayRemote ?? hostRemote ?? srflxRemote;
                SelectedPath = ConnectionPath.RelayedFallback;

                // Enforce 100 MB relay quota ceiling
                try
                {
                    _quotaTracker.CheckTransferOffer(TotalSizeBytes);
                    _quotaTracker.TryAcquireSession(Invitation.RendezvousId);
                }
                catch (RelayQuotaExceededException ex)
                {
                    Fail(ex.Message);
                    throw;
                }
                catch (RelayConcurrencyLimitExceededException ex)
                {
                    Fail(ex.Message);
                    throw;
                }

                State = RemoteSessionState.Transferring;
                return ConnectionPath.RelayedFallback;
            }

            Fail("No mutually viable ICE candidate pair found.");
            throw new InvalidOperationException("No compatible candidate pair.");
        }
    }

    /// <summary>
    /// Records transferred bytes and asserts that relayed bandwidth stays within the 100 MB ceiling.
    /// </summary>
    public void RecordProgress(long bytes)
    {
        lock (_lock)
        {
            if (State != RemoteSessionState.Transferring)
            {
                throw new InvalidOperationException($"Cannot record progress when session is {State}.");
            }

            TransferredBytes += bytes;

            if (SelectedPath == ConnectionPath.RelayedFallback)
            {
                try
                {
                    _quotaTracker.RecordBytesTransferred(bytes);
                }
                catch (RelayQuotaExceededException ex)
                {
                    Fail(ex.Message);
                    throw;
                }
            }
        }
    }

    /// <summary>
    /// Marks the session as successfully completed and releases relay resources.
    /// </summary>
    public void Complete()
    {
        lock (_lock)
        {
            if (State != RemoteSessionState.Transferring)
            {
                throw new InvalidOperationException($"Cannot complete session from state {State}.");
            }

            if (SelectedPath == ConnectionPath.RelayedFallback)
            {
                _quotaTracker.ReleaseSession(Invitation.RendezvousId);
            }

            State = RemoteSessionState.Completed;
        }
    }

    /// <summary>
    /// Aborts the session with a failure reason.
    /// </summary>
    public void Fail(string reason)
    {
        lock (_lock)
        {
            if (SelectedPath == ConnectionPath.RelayedFallback && State == RemoteSessionState.Transferring)
            {
                _quotaTracker.ReleaseSession(Invitation.RendezvousId);
            }

            State = RemoteSessionState.Failed;
            FailureReason = reason;
        }
    }

    private sealed class DefaultUnmeteredDetector : IMeteredNetworkDetector
    {
        public MeteredNetworkState GetCurrentNetworkState() =>
            new(IsMetered: false, NetworkCostType.Unrestricted);
    }
}
