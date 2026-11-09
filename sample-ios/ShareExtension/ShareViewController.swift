import UIKit
import ShareInboxKit
// ShareLoader.swift is compiled into this sample target. In your app, import
// ShareInboxExtension after adding the local Swift package instead.

actor InboxWriter {
    func save(_ share: LoadedShare, id: String) throws {
        let inbox = try IosShareInbox.companion.forAppGroup(identifier: "group.dev.shareinbox.sample")
        let files = share.files.map {
            ImportFile(sourcePath: $0.url.path, name: $0.name, contentType: $0.contentType)
        }
        _ = try inbox.receive(text: share.text, url: share.url, files: files, deliveryId: id)
    }
}

final class ShareViewController: UIViewController {
    private let status = UILabel()
    private let deliveryId = UUID().uuidString
    private var started = false
    private var work: Task<Void, Never>?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        status.text = "Saving to ShareInbox…"
        status.numberOfLines = 0
        status.textAlignment = .center
        status.translatesAutoresizingMaskIntoConstraints = false
        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.addTarget(self, action: #selector(cancelShare), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(status)
        view.addSubview(cancel)
        NSLayoutConstraint.activate([
            status.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            status.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            status.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            cancel.topAnchor.constraint(equalTo: status.bottomAnchor, constant: 24),
            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor)
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        work = Task {
            do {
                let share = try await ShareLoader().load(providers)
                defer { share.cleanup() }
                try Task.checkCancellation()
                try await InboxWriter().save(share, id: deliveryId)
                extensionContext?.completeRequest(returningItems: nil)
            } catch is CancellationError {
                // cancelShare already completed the request.
            } catch {
                status.text = "Could not save this share.\n\(error.localizedDescription)"
            }
        }
    }

    @objc private func cancelShare() {
        work?.cancel()
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }
}
