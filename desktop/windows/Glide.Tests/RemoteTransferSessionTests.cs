using System;
using System.Collections.Generic;
using System.Net;
using Glide.Core.Remote;
using Xunit;

namespace Glide.Tests;

public class RemoteTransferSessionTests
{
    private sealed class MockMeteredDetector : IMeteredNetworkDetector
    {
        public bool IsMetered { get; set; }
        public NetworkCostType CostType { get; set; }

        public MockMeteredDetector(bool isMetered, NetworkCostType costType = NetworkCostType.Metered)
        {
            IsMetered = isMetered;
            CostType = costType;
        }

        public MeteredNetworkState GetCurrentNetworkState() =>
            new(IsMetered, CostType, "CellularTest");
    }

    private static RemoteInvitation CreateValidInvitation(int ttlSeconds = 120)
    {
        return new RemoteInvitation(
            rendezvousId: "8a32a67e-071a-4d23-92b4-3ec9fef39db3",
            ephemeralPublicKeyHex: new string('a', 64),
            signalingEndpoint: "wss://signal.glide.app/v1",
            relayBlindToken: "validBlindToken123",
            expiresAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds() + ttlSeconds,
            ttlSeconds: ttlSeconds);
    }

    [Fact]
    public void CandidateSelection_DirectHostPriority_OverReflexiveAndRelayed()
    {
        var session = new RemoteTransferSession(CreateValidInvitation(), "photo.jpg", 10_000_000);

        var localHost = IceCandidate.CreateHost("1", new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000));
        var localSrflx = IceCandidate.CreateServerReflexive("2", new IPEndPoint(IPAddress.Parse("203.0.113.1"), 50000), localHost.EndPoint);
        var localRelay = IceCandidate.CreateRelayed("3", new IPEndPoint(IPAddress.Parse("198.51.100.1"), 40000), localHost.EndPoint);

        var remoteHost = IceCandidate.CreateHost("4", new IPEndPoint(IPAddress.Parse("192.168.1.20"), 50000));
        var remoteSrflx = IceCandidate.CreateServerReflexive("5", new IPEndPoint(IPAddress.Parse("203.0.113.2"), 50000), remoteHost.EndPoint);

        var path = session.SelectCandidatePair(
            new List<IceCandidate> { localRelay, localSrflx, localHost },
            new List<IceCandidate> { remoteSrflx, remoteHost });

        Assert.Equal(ConnectionPath.DirectHost, path);
        Assert.Equal(localHost, session.LocalCandidate);
        Assert.Equal(remoteHost, session.RemoteCandidate);
        Assert.Equal(RemoteSessionState.Transferring, session.State);
    }

    [Fact]
    public void CandidateSelection_ServerReflexivePriority_WhenHostNotAvailable()
    {
        var session = new RemoteTransferSession(CreateValidInvitation(), "archive.tar", 20_000_000);

        var localBase = new IPEndPoint(IPAddress.Parse("10.0.0.5"), 50000);
        var localSrflx = IceCandidate.CreateServerReflexive("1", new IPEndPoint(IPAddress.Parse("203.0.113.50"), 50000), localBase);
        var localRelay = IceCandidate.CreateRelayed("2", new IPEndPoint(IPAddress.Parse("198.51.100.1"), 40000), localBase);

        var remoteBase = new IPEndPoint(IPAddress.Parse("172.16.0.8"), 50000);
        var remoteSrflx = IceCandidate.CreateServerReflexive("3", new IPEndPoint(IPAddress.Parse("203.0.113.99"), 50000), remoteBase);

        var path = session.SelectCandidatePair(
            new List<IceCandidate> { localRelay, localSrflx },
            new List<IceCandidate> { remoteSrflx });

        Assert.Equal(ConnectionPath.ServerReflexive, path);
        Assert.Equal(localSrflx, session.LocalCandidate);
        Assert.Equal(remoteSrflx, session.RemoteCandidate);
    }

    [Fact]
    public void CandidateSelection_RelayedFallback_Enforces100MBCap()
    {
        long oversizedBytes = 105 * 1024 * 1024; // 105 MB
        var session = new RemoteTransferSession(CreateValidInvitation(), "large_video.mp4", oversizedBytes);

        var localBase = new IPEndPoint(IPAddress.Parse("10.0.0.5"), 50000);
        var localRelay = IceCandidate.CreateRelayed("1", new IPEndPoint(IPAddress.Parse("198.51.100.1"), 40000), localBase);

        var remoteBase = new IPEndPoint(IPAddress.Parse("172.16.0.8"), 50000);
        var remoteRelay = IceCandidate.CreateRelayed("2", new IPEndPoint(IPAddress.Parse("198.51.100.2"), 40000), remoteBase);

        // Relayed fallback with 105 MB must throw RelayQuotaExceededException
        var ex = Assert.Throws<RelayQuotaExceededException>(() =>
            session.SelectCandidatePair(
                new List<IceCandidate> { localRelay },
                new List<IceCandidate> { remoteRelay }));

        Assert.Equal(oversizedBytes, ex.RequestedBytes);
        Assert.Equal(RemoteSessionState.Failed, session.State);
        Assert.Contains("exceeds the 100 MB quota", session.FailureReason);
    }

    [Fact]
    public void DirectP2P_Uncapped_PermitsLargeTransfers()
    {
        long largeBytes = 500L * 1024L * 1024L; // 500 MB
        var session = new RemoteTransferSession(CreateValidInvitation(), "dataset.zip", largeBytes);

        var localHost = IceCandidate.CreateHost("1", new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000));
        var remoteHost = IceCandidate.CreateHost("2", new IPEndPoint(IPAddress.Parse("192.168.1.20"), 50000));

        var path = session.SelectCandidatePair(
            new List<IceCandidate> { localHost },
            new List<IceCandidate> { remoteHost });

        Assert.Equal(ConnectionPath.DirectHost, path);

        // Direct P2P transfer of 500 MB does NOT violate relay quota
        session.RecordProgress(largeBytes);
        Assert.Equal(largeBytes, session.TransferredBytes);
        session.Complete();
        Assert.Equal(RemoteSessionState.Completed, session.State);
    }

    [Fact]
    public void MeteredCellular_RequiresUserConsent_BeforeSignaling()
    {
        var meteredDetector = new MockMeteredDetector(isMetered: true, costType: NetworkCostType.Metered);
        var session = new RemoteTransferSession(
            CreateValidInvitation(),
            "document.pdf",
            50_000_000,
            meteredDetector: meteredDetector);

        bool promptRequired = session.EvaluateMeteredPolicy(out MeteredTransferPrompt? prompt);

        Assert.True(promptRequired);
        Assert.NotNull(prompt);
        Assert.Equal(RemoteSessionState.AwaitingUserConsent, session.State);
        Assert.Contains("mobile cellular data", prompt.WarningMessage);

        // User declines
        session.ConfirmMeteredTransfer(proceed: false);
        Assert.Equal(RemoteSessionState.Failed, session.State);
        Assert.Contains("cancelled by user", session.FailureReason);
    }

    [Fact]
    public void ExpiredInvitation_FailsSession()
    {
        // Invitation expired 10 seconds ago
        var expiredInv = CreateValidInvitation(ttlSeconds: -10);
        var session = new RemoteTransferSession(expiredInv, "file.bin", 1024);

        bool promptRequired = session.EvaluateMeteredPolicy(out _);

        Assert.False(promptRequired);
        Assert.Equal(RemoteSessionState.Failed, session.State);
        Assert.Contains("expired", session.FailureReason);
    }

    [Fact]
    public void RelayedTransfer_CumulativeProgressExceeds100MB_FailsSession()
    {
        var session = new RemoteTransferSession(CreateValidInvitation(), "multi_stream.bin", 90_000_000);

        var localBase = new IPEndPoint(IPAddress.Parse("10.0.0.5"), 50000);
        var localRelay = IceCandidate.CreateRelayed("1", new IPEndPoint(IPAddress.Parse("198.51.100.1"), 40000), localBase);
        var remoteRelay = IceCandidate.CreateRelayed("2", new IPEndPoint(IPAddress.Parse("198.51.100.2"), 40000), localBase);

        session.SelectCandidatePair(new List<IceCandidate> { localRelay }, new List<IceCandidate> { remoteRelay });

        session.RecordProgress(80L * 1024L * 1024L); // 80 MB
        Assert.Equal(RemoteSessionState.Transferring, session.State);

        // Additional 25 MB pushes total to 105 MB -> quota violation
        Assert.Throws<RelayQuotaExceededException>(() => session.RecordProgress(25L * 1024L * 1024L));
        Assert.Equal(RemoteSessionState.Failed, session.State);
    }
}
