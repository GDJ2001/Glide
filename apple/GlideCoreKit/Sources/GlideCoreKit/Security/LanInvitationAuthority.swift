import Foundation

public enum InvitationClaimDecision: Equatable, Sendable {
    case accepted
    case expired
    case used
    case authenticationFailed
    case unsupportedRole
}

/// Owns one advertised invitation. The bootstrap handler must claim it before
/// any HELLO or file metadata is processed. Publication computes the hash from
/// the encoded wire payload, as the Android and desktop authorities do.
public final class LanInvitationAuthority: @unchecked Sendable {
    private let lock = NSLock()
    private var active: LanInvitation?
    private var consumedIds = Set<String>()
    private var consumedOrder: [String] = []
    private let maxConsumedIds = 128

    public init() {}

    public var activeInvitation: LanInvitation? {
        lock.lock()
        defer { lock.unlock() }
        return active
    }

    @discardableResult
    public func publish(_ invitation: LanInvitation) throws -> LanInvitation {
        let canonical = try InvitationCodec.decode(InvitationCodec.encode(invitation))
        lock.lock()
        defer { lock.unlock() }
        active = canonical
        return canonical
    }

    public func revokeActive() {
        lock.lock()
        defer { lock.unlock() }
        active = nil
    }

    public func revokeAll() {
        lock.lock()
        defer { lock.unlock() }
        active = nil
        consumedIds.removeAll()
        consumedOrder.removeAll()
    }

    public func claim(
        invitationId: String,
        token: String,
        invitationHash: String,
        role: String,
        expectedRole: String = "sender",
        nowEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970)
    ) -> InvitationClaimDecision {
        lock.lock()
        defer { lock.unlock() }

        guard role == expectedRole else { return .unsupportedRole }
        if consumedIds.contains(invitationId) { return .used }
        guard let invitation = active else { return .authenticationFailed }
        if invitation.isExpired(currentEpochSeconds: nowEpochSeconds) {
            active = nil
            return .expired
        }
        guard invitation.invitationId == invitationId,
              Self.fixedTimeEqual(invitation.token, token),
              Self.fixedTimeEqual(invitation.invitationHash, invitationHash) else {
            return .authenticationFailed
        }

        active = nil
        if consumedIds.insert(invitationId).inserted {
            consumedOrder.append(invitationId)
        }
        while consumedOrder.count > maxConsumedIds {
            consumedIds.remove(consumedOrder.removeFirst())
        }
        return .accepted
    }

    private static func fixedTimeEqual(_ left: String, _ right: String) -> Bool {
        let lhs = Array(left.utf8)
        let rhs = Array(right.utf8)
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for index in lhs.indices {
            difference |= lhs[index] ^ rhs[index]
        }
        return difference == 0
    }
}
