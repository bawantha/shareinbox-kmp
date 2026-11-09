import SwiftUI
import ShareInboxKit

private let appGroup = "group.dev.shareinbox.sample"

actor InboxReader {
    func read() throws -> [SharedItem] {
        try IosShareInbox.companion.forAppGroup(identifier: appGroup).pending()
    }
    func acknowledge(_ id: String) throws {
        _ = try IosShareInbox.companion.forAppGroup(identifier: appGroup).acknowledge(id: id)
    }
}

@main
struct ShareInboxDemoApp: App {
    var body: some Scene { WindowGroup { InboxView() } }
}

struct InboxView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var items: [SharedItem] = []
    @State private var errorMessage: String?
    private let reader = InboxReader()

    var body: some View {
        NavigationView {
            List {
                Text("Share a link, photo, or PDF to ShareInbox. Return here to see saved items.")
                if let errorMessage { Text(errorMessage).foregroundColor(.red) }
                if items.isEmpty { Text("Your inbox is empty.").foregroundColor(.secondary) }
                ForEach(items, id: \.id) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        if let text = item.text { Text(text) }
                        if let url = item.url { Text(url).foregroundColor(.secondary) }
                        ForEach(item.attachments, id: \.localPath) { file in
                            Text("\(file.name) · \(file.sizeBytes) bytes")
                        }
                        Button("Acknowledge and delete", role: .destructive) {
                            Task {
                                do { try await reader.acknowledge(item.id); await reload() }
                                catch { errorMessage = error.localizedDescription }
                            }
                        }
                    }
                }
            }
            .navigationTitle("ShareInbox")
            .refreshable { await reload() }
            .task { await reload() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { Task { await reload() } }
            }
        }
    }

    @MainActor private func reload() async {
        do { items = try await reader.read(); errorMessage = nil }
        catch { errorMessage = error.localizedDescription }
    }
}
