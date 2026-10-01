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
    private var importer: AppGroupProviderImport?

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
        let operation = AppGroupProviderImport(providers: providers) { [weak self] result in
            DispatchQueue.main.async { self?.finish(result) }
        }
        importer = operation
        operation.start()
    }

    private func finish(_ result: Result<[URL], Error>) {
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

#endif
