import Foundation
import UniformTypeIdentifiers

/// All batch mutations run on worker. The small cancellation state is separately locked
/// so a UI cancellation can interrupt a large copy without waiting for that worker.
public final class AppGroupProviderImport: @unchecked Sendable {
    private let providers: [NSItemProvider]
    private let maxItems: Int
    private let maxBatchBytes: Int64
    private let completion: (Result<[URL], Error>) -> Void
    private let worker = DispatchQueue(label: "dev.glide.share-import", qos: .userInitiated)
    private let workerKey = DispatchSpecificKey<Bool>()
    private let cancellationLock = NSLock()
    private var cancelled = false
    private var progress: Progress?
    private var batch: AppGroupShareQueue.PendingBatch?
    private var copiedURLs: [URL] = []
    private var index = 0
    private var finished = false
    private var started = false

    public init(providers: [NSItemProvider],
                maxItems: Int = ShareExtensionCoordinator.maxItemCount,
                maxBatchBytes: Int64 = ShareExtensionCoordinator.maxBatchSizeBytes,
                completion: @escaping (Result<[URL], Error>) -> Void) {
        self.providers = providers
        self.maxItems = maxItems
        self.maxBatchBytes = maxBatchBytes
        self.completion = completion
        worker.setSpecific(key: workerKey, value: true)
    }

    public func start() {
        worker.async {
            guard !self.started, !self.finished else { return }
            self.started = true
            do {
                guard !self.isCancelled else { throw CancellationError() }
                guard !self.providers.isEmpty, self.providers.count <= self.maxItems else {
                    throw AppGroupShareQueue.QueueError.tooManyItems
                }
                self.batch = try AppGroupShareQueue.createBatch(maxItems: self.maxItems,
                                                                maxBatchBytes: self.maxBatchBytes)
                self.loadNext()
            } catch { self.finish(.failure(error)) }
        }
    }

    public func cancel() {
        cancellationLock.lock()
        cancelled = true
        let current = progress
        cancellationLock.unlock()
        current?.cancel()
        worker.async { self.finish(.failure(CancellationError())) }
    }

    private var isCancelled: Bool {
        cancellationLock.lock()
        defer { cancellationLock.unlock() }
        return cancelled
    }

    private func loadNext() {
        guard !finished else { return }
        guard !isCancelled else { finish(.failure(CancellationError())); return }
        if index == providers.count {
            // Publish and cancellation are ordered: after publish the handoff is complete.
            cancellationLock.lock()
            let result: Result<[URL], Error>
            do {
                if cancelled { throw CancellationError() }
                try batch!.publish()
                result = .success(copiedURLs)
            } catch { result = .failure(error) }
            cancellationLock.unlock()
            finish(result)
            return
        }
        let provider = providers[index]
        let requestIndex = index
        guard let type = provider.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .data) == true
        }) else { finish(.failure(AppGroupShareQueue.QueueError.invalidFile)); return }

        let current = provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
            // Apple's provider URL expires when this callback returns. Copy synchronously
            // on our worker before returning; never enqueue the temporary URL for later.
            let consume = {
                guard !self.finished, self.index == requestIndex else { return }
                do {
                    if self.isCancelled { throw CancellationError() }
                    if let error { throw error }
                    guard let url else { throw AppGroupShareQueue.QueueError.invalidFile }
                    var name = provider.suggestedName ?? url.lastPathComponent
                    if (name as NSString).pathExtension.isEmpty,
                       let suffix = UTType(type)?.preferredFilenameExtension {
                        name += "." + suffix
                    }
                    let copied = try self.batch!.appendTemporaryFile(url, suggestedName: name,
                                                                    isCancelled: { self.isCancelled })
                    self.copiedURLs.append(copied)
                    self.index += 1
                    self.worker.async { self.loadNext() }
                } catch { self.finish(.failure(error)) }
            }
            if DispatchQueue.getSpecific(key: self.workerKey) == true { consume() }
            else { self.worker.sync(execute: consume) }
        }
        cancellationLock.lock()
        progress = current
        let shouldCancel = cancelled
        cancellationLock.unlock()
        if shouldCancel { current.cancel() }
        worker.asyncAfter(deadline: .now() + 120) { [weak self] in
            guard let self, !self.finished, self.index == requestIndex else { return }
            current.cancel()
            self.finish(.failure(URLError(.timedOut)))
        }
    }

    private func finish(_ result: Result<[URL], Error>) {
        guard !finished else { return }
        finished = true
        batch?.discard()
        batch = nil
        cancellationLock.lock()
        progress = nil
        cancellationLock.unlock()
        completion(result)
    }
}
