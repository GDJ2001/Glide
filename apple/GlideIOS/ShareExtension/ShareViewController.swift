#if os(iOS)
import UIKit
import UniformTypeIdentifiers
import GlideCoreKit

/// Copies provider-owned temporary files before their completion handlers return.
/// The containing app imports completed App Group batches on its next activation.
public final class ShareViewController: UIViewController {
    private let message = UILabel()
    private let closeButton = UIButton(type: .system)
    private var stagingError: Error?
    private var importer: ShareProviderImport?

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        message.text = "Adding files to Glide…"
        message.numberOfLines = 0
        message.textAlignment = .center
        message.font = .preferredFont(forTextStyle: .headline)
        message.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(message)
        closeButton.setTitle("Cancel", for: .normal)
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(closeButton)
        NSLayoutConstraint.activate([
            message.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            message.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            message.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -20),
            closeButton.topAnchor.constraint(equalTo: message.bottomAnchor, constant: 16),
            closeButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            closeButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44)
        ])
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty, providers.count <= ShareExtensionCoordinator.maxItemCount else {
            finish(.failure(AppGroupShareQueue.QueueError.tooManyItems))
            return
        }
        let operation = ShareProviderImport(providers: providers) { [weak self] result in
            DispatchQueue.main.async { self?.finish(result) }
        }
        importer = operation
        operation.start()
    }

    private func finish(_ result: Result<Void, Error>) {
        importer = nil
        closeButton.isEnabled = true
        closeButton.setTitle("Close", for: .normal)
        switch result {
        case .success:
            message.text = "Files added. Open Glide to choose a device and send."
        case .failure(let error):
            stagingError = error
            if error is CancellationError {
                extensionContext?.cancelRequest(withError: error)
            } else {
                message.text = "Glide could not add these files. Check available storage and try again."
            }
        }
    }

    @objc private func close() {
        if let importer {
            message.text = "Cancelling…"
            closeButton.isEnabled = false
            importer.cancel()
        } else if let stagingError {
            extensionContext?.cancelRequest(withError: stagingError)
        } else {
            extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }

    deinit { importer?.cancel() }
}

/// All batch mutations run on worker. The small cancellation state is separately locked
/// so a UI cancellation can interrupt a large copy without waiting for that worker.
private final class ShareProviderImport: @unchecked Sendable {
    private let providers: [NSItemProvider]
    private let completion: (Result<Void, Error>) -> Void
    private let worker = DispatchQueue(label: "dev.glide.share-import", qos: .userInitiated)
    private let workerKey = DispatchSpecificKey<Bool>()
    private let cancellationLock = NSLock()
    private var cancelled = false
    private var progress: Progress?
    private var batch: AppGroupShareQueue.PendingBatch?
    private var index = 0
    private var finished = false

    init(providers: [NSItemProvider], completion: @escaping (Result<Void, Error>) -> Void) {
        self.providers = providers
        self.completion = completion
        worker.setSpecific(key: workerKey, value: true)
    }

    func start() {
        worker.async {
            do {
                guard !self.isCancelled else { throw CancellationError() }
                self.batch = try AppGroupShareQueue.createBatch()
                self.loadNext()
            } catch { self.finish(.failure(error)) }
        }
    }

    func cancel() {
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
            let result: Result<Void, Error>
            do {
                if cancelled { throw CancellationError() }
                try batch!.publish()
                result = .success(())
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
                    try self.batch!.appendTemporaryFile(url, suggestedName: provider.suggestedName,
                                                       isCancelled: { self.isCancelled })
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

    private func finish(_ result: Result<Void, Error>) {
        guard !finished else { return }
        finished = true
        batch?.discard()
        batch = nil
        completion(result)
    }
}
#endif
