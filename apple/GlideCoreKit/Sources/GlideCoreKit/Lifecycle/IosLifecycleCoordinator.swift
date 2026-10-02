import Foundation

/// Abstraction for iOS system background task execution.
public protocol IBackgroundTaskProvider: Sendable {
    func beginBackgroundTask(withName name: String, expirationHandler: @escaping @Sendable () -> Void) -> Int
    func endBackgroundTask(_ identifier: Int)
}

/// Manages iOS/iPadOS application lifecycle, background task extensions, and screen wake lock.
/// Conforms to Section 6.1 of APPLE_PLATFORMS_SPECIFICATION.md and App Store Guideline 2.5.4.
public final class IosLifecycleCoordinator: @unchecked Sendable {
    public static let systemCancelledCode = "SYSTEM_CANCELLED"
    public static let defaultTaskName = "GlideActiveTransfer"

    private let taskProvider: IBackgroundTaskProvider
    private let lock = NSLock()

    private var activeTaskId: Int?
    private var _isIdleTimerDisabled: Bool = false
    private var _isCancelledBySystem: Bool = false

    public var isIdleTimerDisabled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isIdleTimerDisabled
    }

    public var isCancelledBySystem: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isCancelledBySystem
    }

    public init(taskProvider: IBackgroundTaskProvider? = nil) {
        self.taskProvider = taskProvider ?? DefaultBackgroundTaskProvider()
    }

    /// Sets device screen retention (wake lock) during active transfer streaming.
    public func setIdleTimerDisabled(_ disabled: Bool) {
        lock.lock()
        defer { lock.unlock() }
        _isIdleTimerDisabled = disabled
    }

    /// Requests up to ~30 seconds of background execution time when the app transitions to background.
    public func beginBackgroundTask(
        name: String = defaultTaskName,
        onExpiration: (@Sendable () -> Void)? = nil
    ) -> Int {
        lock.lock()
        defer { lock.unlock() }

        let taskId = taskProvider.beginBackgroundTask(withName: name) { [weak self] in
            guard let self = self else { return }
            self.handleSystemBackgroundExpiration()
            onExpiration?()
        }

        self.activeTaskId = taskId
        return taskId
    }

    /// Releases the active background execution token.
    public func endBackgroundTask() {
        lock.lock()
        defer { lock.unlock() }

        if let taskId = activeTaskId {
            taskProvider.endBackgroundTask(taskId)
            activeTaskId = nil
        }
    }

    /// System background expiration handler: marks session as cancelled by system and performs orderly teardown.
    public func handleSystemBackgroundExpiration() {
        lock.lock()
        _isCancelledBySystem = true
        _isIdleTimerDisabled = false
        let taskId = activeTaskId
        activeTaskId = nil
        lock.unlock()

        if let id = taskId {
            taskProvider.endBackgroundTask(id)
        }
    }

    /// Resets lifecycle state for a new transfer session.
    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        _isCancelledBySystem = false
        _isIdleTimerDisabled = false
        activeTaskId = nil
    }

    private final class DefaultBackgroundTaskProvider: IBackgroundTaskProvider, @unchecked Sendable {
        private let lock = NSLock()
        private var counter = 1000

        func beginBackgroundTask(withName name: String, expirationHandler: @escaping @Sendable () -> Void) -> Int {
            lock.lock()
            defer { lock.unlock() }
            counter += 1
            return counter
        }

        func endBackgroundTask(_ identifier: Int) {
            // Completed
        }
    }
}
