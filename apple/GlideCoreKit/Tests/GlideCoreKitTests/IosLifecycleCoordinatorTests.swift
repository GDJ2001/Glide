import XCTest
@testable import GlideCoreKit

final class IosLifecycleCoordinatorTests: XCTestCase {
    private final class MockBackgroundTaskProvider: IBackgroundTaskProvider, @unchecked Sendable {
        var begunTasks: [String] = []
        var endedTasks: [Int] = []
        var currentExpirationHandler: (@Sendable () -> Void)?
        private var idCounter = 100

        func beginBackgroundTask(withName name: String, expirationHandler: @escaping @Sendable () -> Void) -> Int {
            begunTasks.append(name)
            currentExpirationHandler = expirationHandler
            idCounter += 1
            return idCounter
        }

        func endBackgroundTask(_ identifier: Int) {
            endedTasks.append(identifier)
        }

        func triggerExpiration() {
            currentExpirationHandler?()
        }
    }

    func testIdleTimerRetention() {
        let coordinator = IosLifecycleCoordinator()
        XCTAssertFalse(coordinator.isIdleTimerDisabled)

        coordinator.setIdleTimerDisabled(true)
        XCTAssertTrue(coordinator.isIdleTimerDisabled)

        coordinator.setIdleTimerDisabled(false)
        XCTAssertFalse(coordinator.isIdleTimerDisabled)
    }

    func testBackgroundTaskBeginsAndEnds() {
        let mock = MockBackgroundTaskProvider()
        let coordinator = IosLifecycleCoordinator(taskProvider: mock)

        let taskId = coordinator.beginBackgroundTask(name: "TestTask")
        XCTAssertEqual(mock.begunTasks, ["TestTask"])

        coordinator.endBackgroundTask()
        XCTAssertEqual(mock.endedTasks, [taskId])
    }

    func testSystemBackgroundExpirationTriggersCancellation() {
        let mock = MockBackgroundTaskProvider()
        let coordinator = IosLifecycleCoordinator(taskProvider: mock)

        coordinator.setIdleTimerDisabled(true)
        _ = coordinator.beginBackgroundTask(name: "ActiveTransfer")

        XCTAssertFalse(coordinator.isCancelledBySystem)
        XCTAssertTrue(coordinator.isIdleTimerDisabled)

        // Simulate system expiration
        mock.triggerExpiration()

        XCTAssertTrue(coordinator.isCancelledBySystem)
        XCTAssertFalse(coordinator.isIdleTimerDisabled)
    }

    func testResetClearsState() {
        let coordinator = IosLifecycleCoordinator()
        coordinator.setIdleTimerDisabled(true)
        coordinator.handleSystemBackgroundExpiration()

        XCTAssertTrue(coordinator.isCancelledBySystem)

        coordinator.reset()
        XCTAssertFalse(coordinator.isCancelledBySystem)
        XCTAssertFalse(coordinator.isIdleTimerDisabled)
    }
}
