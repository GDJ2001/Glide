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

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        message.text = "Adding files to Glide…"
        message.numberOfLines = 0
        message.textAlignment = .center
        message.font = .preferredFont(forTextStyle: .headline)
        message.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(message)
        closeButton.setTitle("Close", for: .normal)
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.isHidden = true
        view.addSubview(closeButton)
        NSLayoutConstraint.activate([
            message.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            message.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            message.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -20),
            closeButton.topAnchor.constraint(equalTo: message.bottomAnchor, constant: 16),
            closeButton.centerXAnchor.constraint(equalTo: view.centerXAnchor)
        ])
        processSharedItems()
    }

    private func processSharedItems() {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty, providers.count <= ShareExtensionCoordinator.maxItemCount else {
            showFailure("Choose between 1 and 1,000 files to share.")
            return
        }

        let batch: AppGroupShareQueue.PendingBatch
        do {
            batch = try AppGroupShareQueue.createBatch()
        } catch {
            showFailure("Glide could not open its private sharing folder.")
            return
        }

        let group = DispatchGroup()
        let lock = NSLock()
        var firstError: Error?
        for provider in providers {
            guard let type = provider.registeredTypeIdentifiers.first(where: {
                UTType($0)?.conforms(to: .data) == true
            }) else {
                firstError = AppGroupShareQueue.QueueError.invalidFile
                continue
            }
            group.enter()
            provider.loadFileRepresentation(forTypeIdentifier: type) { fileURL, error in
                lock.lock()
                defer { lock.unlock(); group.leave() }
                if let error { firstError = firstError ?? error; return }
                guard let fileURL else {
                    firstError = firstError ?? AppGroupShareQueue.QueueError.invalidFile
                    return
                }
                do {
                    try batch.appendTemporaryFile(fileURL, suggestedName: provider.suggestedName)
                } catch {
                    firstError = firstError ?? error
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { batch.discard(); return }
            if let firstError {
                batch.discard()
                self.stagingError = firstError
                self.showFailure("Glide could not add these files. Check available storage and try again.")
                return
            }
            do {
                try batch.publish()
                self.message.text = "Files added. Open Glide to choose a device and send."
                self.closeButton.isHidden = false
            } catch {
                batch.discard()
                self.stagingError = error
                self.showFailure("Glide could not finish adding these files.")
            }
        }
    }

    private func showFailure(_ text: String) {
        message.text = text
        closeButton.isHidden = false
    }

    @objc private func close() {
        if let stagingError {
            extensionContext?.cancelRequest(withError: stagingError)
        } else {
            extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }
}
#endif
