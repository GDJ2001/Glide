import XCTest
@testable import GlideCoreKit

final class MacCompanionCoordinatorTests: XCTestCase {
    private final class MockListenerProvider: IMacNetworkListenerProvider, @unchecked Sendable {
        var startedPorts: [Int] = []
        var stopCount = 0
        var _listening = false

        var isListening: Bool { _listening }

        func start(port: Int) throws {
            startedPorts.append(port)
            _listening = true
        }

        func stop() {
            stopCount += 1
            _listening = false
        }
    }

    func testInitialStateIsIdle() {
        let mock = MockListenerProvider()
        let coordinator = MacCompanionCoordinator(listenerProvider: mock)

        XCTAssertEqual(coordinator.status, .idle)
        XCTAssertEqual(coordinator.port, 42424)
        XCTAssertFalse(coordinator.isListening)
        XCTAssertFalse(coordinator.openAtLogin)
        XCTAssertEqual(coordinator.statusItemTitle, "Glide")
        XCTAssertEqual(coordinator.statusItemToolTip, "Glide: Idle")
    }

    func testStartAndStopListener() throws {
        let mock = MockListenerProvider()
        let coordinator = MacCompanionCoordinator(listenerProvider: mock, port: 42424)

        try coordinator.startListener()
        XCTAssertEqual(coordinator.status, .advertising(port: 42424))
        XCTAssertTrue(coordinator.isListening)
        XCTAssertEqual(mock.startedPorts, [42424])
        XCTAssertEqual(coordinator.statusItemTitle, "Glide")
        XCTAssertEqual(coordinator.statusItemToolTip, "Glide: Listening on port 42424")

        coordinator.stopListener()
        XCTAssertEqual(coordinator.status, .paused)
        XCTAssertFalse(coordinator.isListening)
        XCTAssertEqual(mock.stopCount, 1)
        XCTAssertEqual(coordinator.statusItemTitle, "Glide (Paused)")

        coordinator.resetToIdle()
        XCTAssertEqual(coordinator.status, .idle)
    }

    func testTransferProgressAndCompletion() throws {
        let mock = MockListenerProvider()
        let coordinator = MacCompanionCoordinator(listenerProvider: mock)
        try coordinator.startListener()

        coordinator.setTransferring(peerName: "Pixel 8 Pro", bytesTransferred: 500, totalBytes: 1000)
        XCTAssertEqual(
            coordinator.status,
            .transferring(peerName: "Pixel 8 Pro", progress: 0.5, bytesTransferred: 500, totalBytes: 1000)
        )
        XCTAssertEqual(coordinator.statusItemTitle, "Glide (50%)")
        XCTAssertTrue(coordinator.statusItemToolTip.contains("Pixel 8 Pro"))
        XCTAssertTrue(coordinator.statusItemToolTip.contains("50%"))

        coordinator.completeTransfer()
        XCTAssertEqual(coordinator.status, .advertising(port: 42424))
    }

    func testErrorState() {
        let coordinator = MacCompanionCoordinator()
        coordinator.setError(message: "TLS handshake failed")

        XCTAssertEqual(coordinator.status, .error(message: "TLS handshake failed"))
        XCTAssertEqual(coordinator.statusItemTitle, "Glide (!)")
        XCTAssertTrue(coordinator.statusItemToolTip.contains("TLS handshake failed"))
    }

    func testOpenAtLoginToggle() {
        let coordinator = MacCompanionCoordinator()
        XCTAssertFalse(coordinator.openAtLogin)

        coordinator.setOpenAtLogin(true)
        XCTAssertTrue(coordinator.openAtLogin)

        coordinator.setOpenAtLogin(false)
        XCTAssertFalse(coordinator.openAtLogin)
    }

    func testCustomDownloadsDirectory() {
        let tempDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("GlideDownloadsTest")
        let coordinator = MacCompanionCoordinator(downloadsUrl: tempDir)

        XCTAssertEqual(coordinator.downloadsDirectoryUrl, tempDir)

        let newDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("CustomGlide")
        coordinator.setDownloadsDirectoryUrl(newDir)
        XCTAssertEqual(coordinator.downloadsDirectoryUrl, newDir)
    }

    func testGenerateMenuItemsEmptyPeers() throws {
        let coordinator = MacCompanionCoordinator()
        try coordinator.startListener()

        let items = coordinator.generateMenuItems(discoveredPeers: [])

        XCTAssertTrue(items.contains { $0.id == "status" && $0.title.contains("42424") })
        XCTAssertTrue(items.contains { $0.id == "no_peers" })
        XCTAssertTrue(items.contains { $0.action == .sendFiles })
        XCTAssertTrue(items.contains { $0.action == .openDownloads })
        XCTAssertTrue(items.contains { $0.action == .showHistory })
        XCTAssertTrue(items.contains { $0.action == .pauseReceiving })
        XCTAssertTrue(items.contains { $0.action == .toggleLaunchAtLogin })
        XCTAssertTrue(items.contains { $0.action == .quit })
    }

    func testGenerateMenuItemsWithDiscoveredPeers() {
        let coordinator = MacCompanionCoordinator()
        let dummySpki = String(repeating: "ab", count: 32)
        let peer = BonjourPeer(
            name: "Galaxy S24",
            endpoint: "192.168.1.55:42424",
            spkiSha256: dummySpki,
            presenceToken: "token123"
        )

        let items = coordinator.generateMenuItems(discoveredPeers: [peer])

        XCTAssertTrue(items.contains { $0.id == "peers_header" })
        XCTAssertTrue(items.contains { $0.id == "peer_\(dummySpki)" && $0.title.contains("Galaxy S24") })
        XCTAssertTrue(items.contains { $0.action == .selectPeer(spkiFingerprint: dummySpki) })
        XCTAssertTrue(items.contains { $0.action == .resumeReceiving }) // since not listening
    }
}
