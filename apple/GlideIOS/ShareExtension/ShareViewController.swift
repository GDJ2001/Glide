#if os(iOS)
import UIKit
import Social
import UniformTypeIdentifiers
import GlideCoreKit

public final class ShareViewController: UIViewController {
    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        processSharedItems()
    }

    private func processSharedItems() {
        guard let extensionItem = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachments = extensionItem.attachments else {
            completeExtension()
            return
        }

        var processedUrls: [URL] = []
        let group = DispatchGroup()

        for provider in attachments {
            if provider.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.data.identifier, options: nil) { item, error in
                    defer { group.leave() }
                    if let url = item as? URL {
                        processedUrls.append(url)
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            _ = ShareExtensionCoordinator.coordinate(
                itemCount: processedUrls.count,
                totalBytes: 0
            )
            self.completeExtension()
        }
    }

    private func completeExtension() {
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
#endif
